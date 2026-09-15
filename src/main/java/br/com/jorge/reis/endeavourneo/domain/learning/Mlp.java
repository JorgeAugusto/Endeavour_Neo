/*
 * Endeavour Neo -- a desktop application shell in Swing.
 * Copyright (C) 2026  Jorge Reis
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see <https://www.gnu.org/licenses/>.
 */
package br.com.jorge.reis.endeavourneo.domain.learning;

import java.util.Random;

/**
 * The multi-layer network of the integrated fade model, and its training.
 *
 * <p>Ported from the specification's sections 7 to 9:
 *
 * <pre>{@code
 * Dense(inputs, 96) -> SiLU -> Dropout(0.10) -> Dense(96, 48) -> SiLU -> Dense(48, 9)
 * }</pre>
 *
 * <p>The output is linear and there is no softmax, because this is <b>regression
 * with many outputs</b> and not classification: each output is the estimated
 * utility of one target/stop pair, and they do not compete to sum to one.
 *
 * <h2>This will not reproduce the Python's numbers, and cannot</h2>
 *
 * <p>The arithmetic below is the arithmetic the specification describes. The
 * WEIGHTS will still differ from PyTorch's, on the same data with the same seed,
 * and so will some decisions — because the initial values come out of a
 * different random generator and the dropout masks come out of another. The
 * specification says so itself in section 14.6, and section 9.2 recommends
 * either LibTorch or golden fixtures exported from Python for strict parity.
 *
 * <p>This project has no runtime dependency and is not about to acquire one for
 * this, so what is here is an honest implementation of the described model and
 * not a bit-for-bit port. Anything measured with it is measured with THIS
 * network. Whoever wants parity starts by exporting fixtures.
 *
 * <p>What was matched deliberately, because it is cheap and it removes one
 * source of difference: the initial values follow PyTorch's own default for a
 * linear layer — weights and biases uniform in
 * {@code +/- 1/sqrt(fanIn)} — and the optimiser is AdamW with decoupled decay in
 * the order PyTorch applies it.
 *
 * <h2>Double and not float</h2>
 *
 * <p>The Python runs in float32. This runs in double throughout, which makes the
 * gradients slightly different in the last digits and the whole thing slower to
 * no benefit the model can see. It is here because a gradient check — the one
 * test that can actually prove a backward pass — needs the forward pass to be
 * precise enough that a finite difference means something, and in float32 it is
 * not.
 */
public final class Mlp {

    /** The hidden widths of section 7. */
    public static final int FIRST = 96;

    public static final int SECOND = 48;

    /** The nine target/stop pairs. */
    public static final int ACTIONS = 9;

    public static final double DROPOUT = 0.10;

    public static final double LEARNING_RATE = 0.002;

    public static final double WEIGHT_DECAY = 0.001;

    /** Adam's own three, which the specification leaves at the framework default. */
    private static final double BETA_ONE = 0.9;

    private static final double BETA_TWO = 0.999;

    private static final double EPSILON = 1e-8;

    /** {@code SmoothL1}/{@code Huber} with this delta are the same function. */
    private static final double DELTA = 1;

    private final int inputs;

    private final double[][] firstWeight;

    private final double[] firstBias;

    private final double[][] secondWeight;

    private final double[] secondBias;

    private final double[][] outWeight;

    private final double[] outBias;

    private final Moments moments;

    private final Random dice;

    /**
     * @param inputs how many attributes each sample carries
     * @param seed   the day's seed; the specification's is {@code 141 + dayOfYear}
     */
    public Mlp(int inputs, long seed) {
        if (inputs < 1) {
            throw new IllegalArgumentException("a network over " + inputs + " inputs is not one");
        }

        this.inputs = inputs;
        this.dice = new Random(seed);

        firstWeight = new double[FIRST][inputs];
        firstBias = new double[FIRST];
        secondWeight = new double[SECOND][FIRST];
        secondBias = new double[SECOND];
        outWeight = new double[ACTIONS][SECOND];
        outBias = new double[ACTIONS];

        fill(firstWeight, firstBias, inputs);
        fill(secondWeight, secondBias, FIRST);
        fill(outWeight, outBias, SECOND);

        moments = new Moments();
    }

    /**
     * PyTorch's default for a linear layer: uniform in {@code +/- 1/sqrt(fanIn)}.
     *
     * <p>It is {@code kaiming_uniform_} with {@code a = sqrt(5)}, which works out
     * to exactly that bound, and the bias gets the same one. Copied rather than
     * invented so that one fewer thing differs from the original.</p>
     */
    private void fill(double[][] weight, double[] bias, int fanIn) {
        double bound = 1.0 / Math.sqrt(fanIn);

        for (int out = 0; out < weight.length; out++) {
            for (int in = 0; in < fanIn; in++) {
                weight[out][in] = (dice.nextDouble() * 2 - 1) * bound;
            }

            bias[out] = (dice.nextDouble() * 2 - 1) * bound;
        }
    }

    public int inputs() {
        return inputs;
    }

    // ------------------------------------------------------------------ SiLU

    /** {@code x * sigmoid(x)}, the activation of section 7. */
    private static double silu(double x) {
        return x / (1 + Math.exp(-x));
    }

    /** Its derivative: {@code s * (1 + x * (1 - s))}, with {@code s = sigmoid(x)}. */
    private static double siluPrime(double x) {
        double s = 1 / (1 + Math.exp(-x));

        return s * (1 + x * (1 - s));
    }

    // --------------------------------------------------------------- forward

    /**
     * What one forward pass remembers, so the backward pass can use it.
     *
     * @param firstScale what each hidden unit's output was MULTIPLIED by: zero
     *                   where dropout took it, {@code 1/(1-p)} where it kept it,
     *                   and one throughout when dropout was not applied at all
     *
     * <p>That last array is not bookkeeping — it is the difference between a
     * correct gradient and one that is wrong by exactly {@code 1/(1-p)}. The
     * backward pass used to derive the factor from the stored output, reading a
     * zero as "dropped" and anything else as "kept and scaled up". That reads
     * right and is right during training, and is silently WRONG whenever the
     * forward pass ran with dropout off: nothing was scaled, and the gradient
     * came out eleven per cent too large. Caught by the gradient check, which is
     * the only thing that could have caught it.</p>
     */
    private record Pass(double[] firstRaw, double[] firstOut, double[] firstScale,
                        double[] secondRaw, double[] secondOut, double[] output) { }

    /**
     * @param sample   one row of attributes
     * @param training whether dropout is applied
     * @return the nine utilities
     */
    public double[] predict(double[] sample) {
        return forward(sample, false).output();
    }

    private Pass forward(double[] sample, boolean training) {
        double[] firstRaw = new double[FIRST];
        double[] firstOut = new double[FIRST];

        for (int out = 0; out < FIRST; out++) {
            double total = firstBias[out];
            double[] row = firstWeight[out];

            for (int in = 0; in < inputs; in++) {
                total += row[in] * sample[in];
            }

            firstRaw[out] = total;
            firstOut[out] = silu(total);
        }

        double[] firstScale = new double[FIRST];

        java.util.Arrays.fill(firstScale, 1);

        if (training && DROPOUT > 0) {
            // INVERTED DROPOUT, which is what every framework does: the kept
            // units are scaled up during training so that nothing has to be
            // scaled down at inference. Evaluating with dropout still on is the
            // commonest way a network looks worse than it is.
            double keep = 1 - DROPOUT;

            for (int out = 0; out < FIRST; out++) {
                firstScale[out] = dice.nextDouble() < DROPOUT ? 0 : 1 / keep;
                firstOut[out] *= firstScale[out];
            }
        }

        double[] secondRaw = new double[SECOND];
        double[] secondOut = new double[SECOND];

        for (int out = 0; out < SECOND; out++) {
            double total = secondBias[out];
            double[] row = secondWeight[out];

            for (int in = 0; in < FIRST; in++) {
                total += row[in] * firstOut[in];
            }

            secondRaw[out] = total;
            secondOut[out] = silu(total);
        }

        double[] output = new double[ACTIONS];

        for (int out = 0; out < ACTIONS; out++) {
            double total = outBias[out];
            double[] row = outWeight[out];

            for (int in = 0; in < SECOND; in++) {
                total += row[in] * secondOut[in];
            }

            output[out] = total;
        }

        return new Pass(firstRaw, firstOut, firstScale, secondRaw, secondOut, output);
    }

    // -------------------------------------------------------------- training

    /**
     * Trains on the whole set at once, for as many epochs as asked.
     *
     * <p>Full batch and no shuffling, which is the specification's section 9.2
     * and not an oversight: with one gradient step per epoch the order of the
     * rows cannot matter, and there is nothing to shuffle.</p>
     *
     * @param x      one row of attributes per sample, already normalised
     * @param y      one row of nine targets per sample, already standardised
     * @param epochs how many passes; the specification's is 180
     * @return the mean loss of the last epoch, for whoever wants to watch it
     */
    public double train(double[][] x, double[][] y, int epochs) {
        if (x.length != y.length) {
            throw new IllegalArgumentException(x.length + " samples against " + y.length
                    + " targets is not a training set");
        }

        double loss = Double.NaN;

        for (int epoch = 0; epoch < epochs; epoch++) {
            loss = step(x, y);
        }

        return loss;
    }

    /** One epoch: gradients over every sample, then one move of the optimiser. */
    private double step(double[][] x, double[][] y) {
        Grads grads = new Grads();
        double total = 0;
        int counted = 0;

        for (int sample = 0; sample < x.length; sample++) {
            Pass pass = forward(x[sample], true);

            double[] back = new double[ACTIONS];

            for (int action = 0; action < ACTIONS; action++) {
                double error = pass.output()[action] - y[sample][action];

                // HUBER, delta one. Quadratic near zero so small errors are
                // taken seriously, linear beyond it so one wild sample cannot
                // drag every weight behind it.
                total += Math.abs(error) < DELTA
                        ? 0.5 * error * error
                        : DELTA * (Math.abs(error) - 0.5 * DELTA);

                back[action] = Math.abs(error) < DELTA ? error : Math.signum(error);

                counted++;
            }

            accumulate(grads, x[sample], pass, back);
        }

        // THE MEAN, over samples AND outputs, which is what a reduction of
        // "mean" means for a loss with nine outputs.
        double scale = 1.0 / Math.max(1, counted);

        grads.scale(scale);
        moments.apply(this, grads);

        return total * scale;
    }

    private void accumulate(Grads grads, double[] sample, Pass pass, double[] back) {
        double[] secondBack = new double[SECOND];

        for (int out = 0; out < ACTIONS; out++) {
            grads.outBias[out] += back[out];

            for (int in = 0; in < SECOND; in++) {
                grads.outWeight[out][in] += back[out] * pass.secondOut()[in];
                secondBack[in] += back[out] * outWeight[out][in];
            }
        }

        double[] firstBack = new double[FIRST];

        for (int out = 0; out < SECOND; out++) {
            double here = secondBack[out] * siluPrime(pass.secondRaw()[out]);

            grads.secondBias[out] += here;

            for (int in = 0; in < FIRST; in++) {
                grads.secondWeight[out][in] += here * pass.firstOut()[in];
                firstBack[in] += here * secondWeight[out][in];
            }
        }

        for (int out = 0; out < FIRST; out++) {
            // THE SCALE THE FORWARD PASS ACTUALLY USED, read from what it
            // wrote down. Deriving it from the stored output instead -- zero
            // means dropped, anything else means kept and scaled -- is right
            // during training and wrong with dropout off, where nothing was
            // scaled at all.
            double here = firstBack[out] * pass.firstScale()[out]
                    * siluPrime(pass.firstRaw()[out]);

            grads.firstBias[out] += here;

            for (int in = 0; in < inputs; in++) {
                grads.firstWeight[out][in] += here * sample[in];
            }
        }
    }

    /** The gradients of one epoch. */
    private final class Grads {

        private final double[][] firstWeight = new double[FIRST][inputs];

        private final double[] firstBias = new double[FIRST];

        private final double[][] secondWeight = new double[SECOND][FIRST];

        private final double[] secondBias = new double[SECOND];

        private final double[][] outWeight = new double[ACTIONS][SECOND];

        private final double[] outBias = new double[ACTIONS];

        private void scale(double by) {
            scaleAll(firstWeight, firstBias, by);
            scaleAll(secondWeight, secondBias, by);
            scaleAll(outWeight, outBias, by);
        }

        private void scaleAll(double[][] weight, double[] bias, double by) {
            for (int out = 0; out < weight.length; out++) {
                for (int in = 0; in < weight[out].length; in++) {
                    weight[out][in] *= by;
                }

                bias[out] *= by;
            }
        }
    }

    /**
     * AdamW, with the decay decoupled the way PyTorch decouples it.
     *
     * <p>Decoupled means the decay is applied to the parameter directly and does
     * NOT go through the moment estimates — which is the whole difference
     * between AdamW and Adam with L2, and it is not a detail: with the decay
     * inside the gradient, a parameter with a small running second moment gets
     * decayed far harder than one with a large one, which is not what anybody
     * asking for weight decay means.</p>
     */
    private static final class Moments {

        private double[][] firstWeightM;
        private double[][] firstWeightV;
        private double[] firstBiasM;
        private double[] firstBiasV;
        private double[][] secondWeightM;
        private double[][] secondWeightV;
        private double[] secondBiasM;
        private double[] secondBiasV;
        private double[][] outWeightM;
        private double[][] outWeightV;
        private double[] outBiasM;
        private double[] outBiasV;

        private int taken;

        private void apply(Mlp net, Grads grads) {
            if (firstWeightM == null) {
                firstWeightM = like(net.firstWeight);
                firstWeightV = like(net.firstWeight);
                firstBiasM = new double[net.firstBias.length];
                firstBiasV = new double[net.firstBias.length];
                secondWeightM = like(net.secondWeight);
                secondWeightV = like(net.secondWeight);
                secondBiasM = new double[net.secondBias.length];
                secondBiasV = new double[net.secondBias.length];
                outWeightM = like(net.outWeight);
                outWeightV = like(net.outWeight);
                outBiasM = new double[net.outBias.length];
                outBiasV = new double[net.outBias.length];
            }

            taken++;

            double firstCorrection = 1 - Math.pow(BETA_ONE, taken);
            double secondCorrection = 1 - Math.pow(BETA_TWO, taken);

            move(net.firstWeight, grads.firstWeight, firstWeightM, firstWeightV,
                    firstCorrection, secondCorrection);
            move(net.firstBias, grads.firstBias, firstBiasM, firstBiasV,
                    firstCorrection, secondCorrection);
            move(net.secondWeight, grads.secondWeight, secondWeightM, secondWeightV,
                    firstCorrection, secondCorrection);
            move(net.secondBias, grads.secondBias, secondBiasM, secondBiasV,
                    firstCorrection, secondCorrection);
            move(net.outWeight, grads.outWeight, outWeightM, outWeightV,
                    firstCorrection, secondCorrection);
            move(net.outBias, grads.outBias, outBiasM, outBiasV,
                    firstCorrection, secondCorrection);
        }

        private static double[][] like(double[][] of) {
            double[][] made = new double[of.length][];

            for (int i = 0; i < of.length; i++) {
                made[i] = new double[of[i].length];
            }

            return made;
        }

        private static void move(double[][] parameter, double[][] gradient,
                                 double[][] first, double[][] second,
                                 double firstCorrection, double secondCorrection) {

            for (int i = 0; i < parameter.length; i++) {
                move(parameter[i], gradient[i], first[i], second[i],
                        firstCorrection, secondCorrection);
            }
        }

        private static void move(double[] parameter, double[] gradient,
                                 double[] first, double[] second,
                                 double firstCorrection, double secondCorrection) {

            for (int i = 0; i < parameter.length; i++) {
                double g = gradient[i];

                first[i] = BETA_ONE * first[i] + (1 - BETA_ONE) * g;
                second[i] = BETA_TWO * second[i] + (1 - BETA_TWO) * g * g;

                double firstHat = first[i] / firstCorrection;
                double secondHat = second[i] / secondCorrection;

                // THE DECAY FIRST, straight on the parameter, then the step.
                parameter[i] -= LEARNING_RATE * WEIGHT_DECAY * parameter[i];
                parameter[i] -= LEARNING_RATE * firstHat
                        / (Math.sqrt(secondHat) + EPSILON);
            }
        }
    }

    // ------------------------------------------------- for a gradient check

    /**
     * @return every parameter, flattened, in a fixed order
     *
     * <p>Exists for one purpose: a gradient check. A backward pass cannot be
     * proven by looking at it, and the only real proof is that every analytic
     * derivative matches a finite difference of the loss — which needs the
     * parameters addressable one at a time.</p>
     */
    double[] parameters() {
        double[] made = new double[count()];
        int at = 0;

        at = flatten(firstWeight, firstBias, made, at);
        at = flatten(secondWeight, secondBias, made, at);
        flatten(outWeight, outBias, made, at);

        return made;
    }

    /** @param values what {@link #parameters()} returned, possibly nudged */
    void parameters(double[] values) {
        int at = 0;

        at = unflatten(firstWeight, firstBias, values, at);
        at = unflatten(secondWeight, secondBias, values, at);
        unflatten(outWeight, outBias, values, at);
    }

    private int count() {
        return FIRST * inputs + FIRST + SECOND * FIRST + SECOND + ACTIONS * SECOND + ACTIONS;
    }

    private static int flatten(double[][] weight, double[] bias, double[] into, int at) {
        for (double[] row : weight) {
            System.arraycopy(row, 0, into, at, row.length);

            at += row.length;
        }

        System.arraycopy(bias, 0, into, at, bias.length);

        return at + bias.length;
    }

    private static int unflatten(double[][] weight, double[] bias, double[] from, int at) {
        for (double[] row : weight) {
            System.arraycopy(from, at, row, 0, row.length);

            at += row.length;
        }

        System.arraycopy(from, at, bias, 0, bias.length);

        return at + bias.length;
    }

    /**
     * The loss and its gradient, over one batch, with dropout OFF.
     *
     * <p>Dropout off because a gradient check compares a derivative against a
     * finite difference of the loss, and a loss that is random between two calls
     * cannot be differenced at all.</p>
     *
     * @param into filled with the gradient, flattened like {@link #parameters()}
     * @return the mean loss
     */
    double lossAndGradient(double[][] x, double[][] y, double[] into) {
        Grads grads = new Grads();
        double total = 0;
        int counted = 0;

        for (int sample = 0; sample < x.length; sample++) {
            Pass pass = forward(x[sample], false);
            double[] back = new double[ACTIONS];

            for (int action = 0; action < ACTIONS; action++) {
                double error = pass.output()[action] - y[sample][action];

                total += Math.abs(error) < DELTA
                        ? 0.5 * error * error
                        : DELTA * (Math.abs(error) - 0.5 * DELTA);

                back[action] = Math.abs(error) < DELTA ? error : Math.signum(error);

                counted++;
            }

            accumulate(grads, x[sample], pass, back);
        }

        double scale = 1.0 / Math.max(1, counted);

        grads.scale(scale);

        int at = 0;

        at = flatten(grads.firstWeight, grads.firstBias, into, at);
        at = flatten(grads.secondWeight, grads.secondBias, into, at);
        flatten(grads.outWeight, grads.outBias, into, at);

        return total * scale;
    }
}
