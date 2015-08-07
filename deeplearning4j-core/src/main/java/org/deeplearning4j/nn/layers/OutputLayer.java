/*
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.nn.layers;

import java.io.*;


import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.api.Classifier;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.params.DefaultParamInitializer;
import org.deeplearning4j.optimize.Solver;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.LossFunction;
import org.nd4j.linalg.api.ops.impl.transforms.SoftMax;
import org.nd4j.linalg.dataset.api.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.lossfunctions.LossCalculation;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.nd4j.linalg.util.FeatureUtil;
import org.nd4j.linalg.util.LinAlgExceptions;

import static org.nd4j.linalg.ops.transforms.Transforms.pow;
import static org.nd4j.linalg.ops.transforms.Transforms.sqrt;


/**
 * Output layer with different objective
 * incooccurrences for different objectives.
 * This includes classification as well as prediction
 * @author Adam Gibson
 *
 */
public class OutputLayer extends BaseLayer implements Serializable,Classifier {

    //current input and label matrices
    private INDArray labels;

    public OutputLayer(NeuralNetConfiguration conf) {
        super(conf);
    }

    public OutputLayer(NeuralNetConfiguration conf, INDArray input) {
        super(conf, input);
    }

    @Override
    public void computeGradientAndScore() {
        if(input == null || labels == null)
            return;

        INDArray output = output(input);
        Pair<Gradient,INDArray> pair = getGradientsAndDelta(output);
        this.gradient = pair.getFirst();
        setScoreWithZ(output);

    }

    @Override
    protected void setScoreWithZ(INDArray z) {
        if (conf.getLossFunction() == LossFunctions.LossFunction.CUSTOM) {
            LossFunction create = Nd4j.getOpFactory().createLossFunction(conf.getCustomLossFunction(), input, z);
            create.exec();
            score = create.currentResult().doubleValue();
        }

        else {
            score = LossCalculation.builder()
                    .l1(conf.getL1()).l2(conf.getL2())
                    .l1Magnitude(l1Magnitude()).l2Magnitude(l2Magnitude())
                    .labels(labels).z(z).lossFunction(conf.getLossFunction())
                    .useRegularization(conf.isUseRegularization()).build().score();
        }
    }

    @Override
    public Pair<Gradient, Double> gradientAndScore() {
        return new Pair<>(gradient(),score());
    }

    @Override
    public Pair<Gradient,INDArray> backpropGradient(INDArray epsilon, Gradient nextGradient, Layer layer) {
	Pair<Gradient,INDArray> pair = getGradientsAndDelta(output(input));	//Returns Gradient and delta^(this), not Gradient and epsilon^(this-1)
    	INDArray delta = pair.getSecond();

        INDArray epsilonNext = params.get(DefaultParamInitializer.WEIGHT_KEY).mmul(delta.transpose()).transpose();
        return new Pair<>(pair.getFirst(),epsilonNext);
    }

    /**
     * Gets the gradient from one training iteration
     * @return the gradient (bias and weight matrix)
     */
    @Override
    public Gradient gradient() {
        LinAlgExceptions.assertRows(input, labels);
        return gradient;

    }
    
    private Pair<Gradient,INDArray> getGradientsAndDelta(INDArray output){
    	INDArray labelsSubOut = labels.sub(output);
    	Gradient gradient = new DefaultGradient();
    	gradient.gradientForVariable().put(DefaultParamInitializer.BIAS_KEY, labelsSubOut.sum(0));
    	
    	switch (conf.getLossFunction()) {
    	case MCXENT:	//cross-entropy (multi-class, with one-hot encoding)
    		gradient.gradientForVariable().put(DefaultParamInitializer.WEIGHT_KEY, input.transpose().mmul(labelsSubOut));
    		return new Pair<>(gradient,labelsSubOut);
        case XENT: // cross-entropy (single binary output variable)
        	gradient.gradientForVariable().put(DefaultParamInitializer.WEIGHT_KEY, input.transpose().mmul(labelsSubOut.div(output.mul(output.rsub(1)))));
        	return new Pair<>(gradient,labelsSubOut);

        case MSE: // mean squared error
        	gradient.gradientForVariable().put(DefaultParamInitializer.WEIGHT_KEY, input.transpose().mmul(labelsSubOut.neg()));
            return new Pair<>(gradient,labelsSubOut);
        	
        case EXPLL: // exponential logarithmic
        	gradient.gradientForVariable().put(DefaultParamInitializer.WEIGHT_KEY, input.transpose().mmul(labels.rsub(1).divi(output)));
            return new Pair<>(gradient,labelsSubOut);
            
        case RMSE_XENT: // root mean squared error cross entropy
        	INDArray squaredrmseXentDiff = pow(labelsSubOut, 2.0);
        	INDArray sqrt = sqrt(squaredrmseXentDiff);
        	gradient.gradientForVariable().put(DefaultParamInitializer.WEIGHT_KEY, input.transpose().mmul(sqrt));
            return new Pair<>(gradient,labelsSubOut);
        	
        case SQUARED_LOSS:
        	gradient.gradientForVariable().put(DefaultParamInitializer.WEIGHT_KEY, input.transpose().mmul(input.transpose().mmul(pow(labelsSubOut,2))));
            return new Pair<>(gradient,labelsSubOut);
            
        case NEGATIVELOGLIKELIHOOD: // mulit-class cross-entropy
        	gradient.gradientForVariable().put(DefaultParamInitializer.WEIGHT_KEY, input.transpose().mmul(labelsSubOut));
            return new Pair<>(gradient,labelsSubOut);
        default:
        	throw new IllegalStateException("Invalid loss function: " + conf.getLossFunction());
    	}
    }


    @Override
    public INDArray activate(INDArray input, boolean training) {
        setInput(input, training);
        return output(training);
    }

    @Override
    public INDArray activate(INDArray input) {
        setInput(input);
        return output(true);
    }

    @Override
    public INDArray activate() {
        return output(false);
    }

    public  INDArray output(INDArray input, boolean training) {
        setInput(input, training);
        return output(training);
    }

    public  INDArray output(INDArray input) {
        setInput(input, false);
        return output(false);
    }

    /**
     * Classify input
     * @param training determines if its training
     * the input (can either be a matrix or vector)
     * If it's a matrix, each row is considered an example
     * and associated rows are classified accordingly.
     * Each row will be the likelihood of a label given that example
     * @return a probability distribution for each row
     */
    public  INDArray output(boolean training) {
        if(input == null)
            throw new IllegalArgumentException("No null input allowed");

        INDArray preOutput = preOutput(input, training);
        if(conf.getActivationFunction().equals("softmax")) {
            SoftMax softMax = new SoftMax(preOutput);
            softMax.exec(1);
            return softMax.z();
        }

        if(training)
            applyDropOutIfNecessary(input(),training);

        return super.activate(true);
    }


    /**
     * Sets the input and labels and returns a score for the prediction
     * wrt true labels
     *
     * @param data the data to score
     * @return the score for the given input,label pairs
     */
    public double f1Score(DataSet data) {
        return f1Score(data.getFeatureMatrix(), data.getLabels());
    }

    /**
     * Returns the f1 score for the given examples.
     * Think of this to be like a percentage right.
     * The higher the number the more it got right.
     * This is on a scale from 0 to 1.
     *
     * @param examples te the examples to classify (one example in each row)
     * @param labels   the true labels
     * @return the scores for each ndarray
     */
    public double f1Score(INDArray examples, INDArray labels) {
        Evaluation eval = new Evaluation();
        eval.eval(labels,labelProbabilities(examples));
        return  eval.f1();

    }

    /**
     * Returns the number of possible labels
     *
     * @return the number of possible labels for this classifier
     */
    @Override
    public int numLabels() {
        return labels.columns();
    }

    @Override
    public void fit(DataSetIterator iter) {
        while(iter.hasNext())
            fit(iter.next());
    }

    /**
     * Returns the predictions for each example in the dataset
     * @param input the matrix to predict
     * @return the prediction for the dataset
     */
    @Override
    public int[] predict(INDArray input) {
        INDArray output = output(input);
        int[] ret = new int[input.rows()];
        for(int i = 0; i < ret.length; i++)
            ret[i] = Nd4j.getBlasWrapper().iamax(output.getRow(i));
        return ret;
    }

    /**
     * Returns the probabilities for each label
     * for each example row wise
     *
     * @param examples the examples to classify (one example in each row)
     * @return the likelihoods of each example and each label
     */
    @Override
    public INDArray labelProbabilities(INDArray examples) {
        return output(examples);
    }

    /**
     * Fit the model
     *
     * @param input the examples to classify (one example in each row)
     * @param labels   the example labels(a binary outcome matrix)
     */
    @Override
    public void fit(INDArray input, INDArray labels) {
        setInput(input);
        setLabels(labels);
        applyDropOutIfNecessary(this.input, true);
        Solver solver = new Solver.Builder()
                .configure(conf())
                .listeners(getListeners())
                .model(this).build();
        solver.optimize();
    }

    /**
     * Fit the model
     *
     * @param data the data to train on
     */
    @Override
    public void fit(DataSet data) {
        fit(data.getFeatureMatrix(), data.getLabels());
    }

    /**
     * Fit the model
     *
     * @param examples the examples to classify (one example in each row)
     * @param labels   the labels for each example (the number of labels must match
     */
    @Override
    public void fit(INDArray examples, int[] labels) {
        INDArray outcomeMatrix = FeatureUtil.toOutcomeMatrix(labels, numLabels());
        fit(examples,outcomeMatrix);

    }

    @Override
    public void clear() {
        super.clear();
        if(labels != null) {
            labels.data().destroy();
            labels = null;
        }
    }


    /**
     * Set the parameters for this model.
     * This expects a linear ndarray which then be unpacked internally
     * relative to the expected ordering of the model
     *
     * @param params the parameters for the model
     */
    @Override
    public void setParams(INDArray params) {
        INDArray wParams = params.get(NDArrayIndex.interval(0, conf.getNIn() * conf.getNOut()));
        INDArray W = getParam(DefaultParamInitializer.WEIGHT_KEY);
        W.assign(wParams);
        INDArray bias = getParam(DefaultParamInitializer.BIAS_KEY);
        int biasBegin = params.length() - bias.length();
        int biasEnd = params.length();
        INDArray biasAssign = params.get(NDArrayIndex.interval(biasBegin, biasEnd));
        bias.assign(biasAssign);
    }
    /**
     * Fit the model to the given data
     *
     * @param data the data to fit the model to
     */
    @Override
    public void fit(INDArray data) {
        //no-op

    }

    @Override
    public void iterate(INDArray input) {
        throw new UnsupportedOperationException();
    }


    public  INDArray getLabels() {
        return labels;
    }

    public  void setLabels(INDArray labels) {
        this.labels = labels;
    }


}