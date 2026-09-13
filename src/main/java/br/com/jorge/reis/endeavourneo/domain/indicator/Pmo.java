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
package br.com.jorge.reis.endeavourneo.domain.indicator;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

/**
 * The Price Momentum Oscillator, ported from {@code JorgeReis_TNO_PMO.src}.
 *
 * <p>Carl Swenlin's oscillator, and the chain is short: the rate of change of
 * the close, smoothed exponentially <b>twice</b> — first over
 * {@link #first()} and then over {@link #second()} — is the main line; the
 * signal is an exponential average of that line. It swings around zero, so it
 * belongs in a panel of its own and not over the candles.
 *
 * <pre>
 *   roc   = (close − close[n]) / close[n] × 100 × scale
 *   line  = EMA(second, EMA(first, roc))
 *   sinal = EMA(signal, line)
 * </pre>
 *
 * <p>The scale is Swenlin's ten: the raw percentages of a minute of the WIN are
 * hundredths, and an oscillator drawn in hundredths is a flat line. It multiplies
 * the rate of change and nothing else, so it never changes <b>where</b> a
 * crossing happens — only how tall the picture is.
 *
 * <h2>Why this is in the domain and not with the chart</h2>
 *
 * <p>The same reason {@link Stochastic} gives: a strategy may want to read it,
 * and {@code domain} may not import {@code ui}. Everything about drawing — the
 * colours, the dot at the crossing, which bars get painted — stays up there.
 *
 * <h2>What the original does that this does NOT</h2>
 *
 * <p>The NTSL guards its rate of change with {@code if Close[pRoc] > 0} and
 * writes <b>zero</b> when the guard fails, which on the opening bars means a
 * real zero fed into the averages. Here those bars are {@link Double#NaN} and
 * the averages begin where the numbers begin — see {@link Ema#over(double[])}.
 * It is the same complaint the original's own header makes about its earlier
 * hand-rolled EMA: a transient at the start of the day that is an artefact of
 * the warm-up and not a fact about the market.
 *
 * <p>And the divergence is causal here. See {@link #divergences}.
 *
 * @param change  the rate of change's period, in bars
 * @param first   the first smoothing, Swenlin's {@code Length1}
 * @param second  the second, {@code Length2}
 * @param signal  the average of the line, {@code SignalLen}
 * @param scale   what the rate of change is multiplied by
 */
public record Pmo(int change, int first, int second, int signal, double scale) {

    /** {@code PeriodoROC}: one bar. */
    public static final int CHANGE = 1;

    /** {@code SuavizacaoPMO1} / Length1. */
    public static final int FIRST = 35;

    /** {@code SuavizacaoPMO2} / Length2. */
    public static final int SECOND = 20;

    /** {@code PeriodoSinal} / SignalLen. */
    public static final int SIGNAL = 10;

    /** {@code EscalaPMO}: Swenlin's ten. */
    public static final double SCALE = 10;

    /** {@code PeriodoDesvio}: the window the exhaustion and the bands read. */
    public static final int DEVIATION = 20;

    /** {@code MultExaust1..3}: how many deviations each step of stretch is. */
    public static final double[] STRETCH = {1.0, 2.0, 3.0};

    /**
     * @param line   the main line
     * @param signal its exponential average
     *
     * <p>Both carry {@link Double#NaN} through the warm-up, which for the line
     * is long: a rate of change, then thirty-five, then twenty. Zero is not
     * available as a stand-in — this oscillator is READ against zero, so a
     * warm-up written as zero is a warm-up sitting exactly on the level that
     * decides which way it leans.</p>
     */
    public record Lines(double[] line, double[] signal) {

        public Lines {
            line = line == null ? new double[0] : line.clone();
            signal = signal == null ? new double[0] : signal.clone();
        }

        @Override
        public double[] line() {
            return line.clone();
        }

        @Override
        public double[] signal() {
            return signal.clone();
        }
    }

    public Pmo {
        if (change < 1) {
            throw new IllegalArgumentException(
                    "a rate of change over " + change + " bars is not one");
        }

        if (first < 1 || second < 1 || signal < 1) {
            throw new IllegalArgumentException("a smoothing over nothing is not a smoothing");
        }

        if (!(scale > 0)) {
            throw new IllegalArgumentException("a scale of " + scale + " flattens the oscillator");
        }
    }

    /** @return the PMO his indicator is born with: 1, 35, 20, 10 and ten. */
    public static Pmo standard() {
        return new Pmo(CHANGE, FIRST, SECOND, SIGNAL, SCALE);
    }

    /**
     * @param bars the series to read
     * @return the rate of change of the close, already multiplied by the scale
     *
     * <p>Per cent, not a ratio, and the bars before there is a close to compare
     * against are NaN.</p>
     */
    public double[] rateOfChange(PriceSeries bars) {
        int size = bars == null ? 0 : bars.size();
        double[] made = new double[size];

        for (int i = 0; i < size; i++) {
            double before = i >= change ? bars.closeAt(i - change) : Double.NaN;

            // A close of zero would divide by zero. It cannot happen on an
            // instrument, and the original guards it anyway; so does this.
            made[i] = Double.isNaN(before) || before == 0
                    ? Double.NaN
                    : (bars.closeAt(i) - before) / before * 100 * scale;
        }

        return made;
    }

    /**
     * @param bars the series to read
     * @return both lines, one value per bar
     */
    public Lines over(PriceSeries bars) {
        double[] roc = rateOfChange(bars);
        double[] once = new Ema(first).over(roc);
        double[] line = new Ema(second).over(once);

        return new Lines(line, new Ema(signal).over(line));
    }

    /**
     * @param line   the main line
     * @param window how many points the deviation covers
     * @return the standard deviation of the line, one value per bar
     *
     * <p>Divided by {@code n} and not by {@code n − 1}: the window is the whole
     * of what is being described and not a sample drawn from something larger,
     * which is the same choice {@link Regression} makes and for the same
     * reason.</p>
     */
    public static double[] deviation(double[] line, int window) {
        int size = line == null ? 0 : line.length;
        double[] made = new double[size];

        for (int i = 0; i < size; i++) {
            made[i] = Double.NaN;

            if (i + 1 < window) {
                continue;
            }

            double sum = 0;
            double squares = 0;
            boolean whole = true;

            for (int back = 0; back < window; back++) {
                double each = line[i - back];

                if (Double.isNaN(each)) {
                    whole = false;

                    break;
                }

                sum += each;
                squares += each * each;
            }

            if (!whole) {
                continue;
            }

            double mean = sum / window;

            made[i] = Math.sqrt(Math.max(0, squares / window - mean * mean));
        }

        return made;
    }

    /**
     * @param lines the two lines
     * @return {@code +1} where the line crossed up through the signal,
     *         {@code -1} where it crossed down, {@code 0} everywhere else
     *
     * <p>The crossing is the CONFIRMATION, and it is the dot the original
     * draws.</p>
     */
    public static int[] crossings(Lines lines) {
        double[] line = lines == null ? new double[0] : lines.line();
        double[] signal = lines == null ? new double[0] : lines.signal();
        int[] made = new int[line.length];

        for (int i = 1; i < line.length; i++) {
            if (anyNaN(line[i], line[i - 1], signal[i], signal[i - 1])) {
                continue;
            }

            if (line[i] > signal[i] && line[i - 1] <= signal[i - 1]) {
                made[i] = 1;
            } else if (line[i] < signal[i] && line[i - 1] >= signal[i - 1]) {
                made[i] = -1;
            }
        }

        return made;
    }

    /**
     * @param lines the two lines
     * @return {@code +1} where the line turned up, {@code -1} where it turned
     *         down, {@code 0} everywhere else
     *
     * <p>The original's {@code UsarInclinacao}: the ALERT rather than the
     * confirmation. The line turning is a thing that happens before it crosses
     * — always, since the crossing needs the line to be moving towards the
     * signal first — so this comes earlier and, for the same reason, more
     * often and more wrongly.</p>
     */
    public static int[] turns(Lines lines) {
        double[] line = lines == null ? new double[0] : lines.line();
        int[] made = new int[line.length];

        for (int i = 2; i < line.length; i++) {
            if (anyNaN(line[i], line[i - 1], line[i - 2])) {
                continue;
            }

            if (line[i] > line[i - 1] && line[i - 1] <= line[i - 2]) {
                made[i] = 1;
            } else if (line[i] < line[i - 1] && line[i - 1] >= line[i - 2]) {
                made[i] = -1;
            }
        }

        return made;
    }

    /**
     * @param line      the main line
     * @param deviation its standard deviation, from {@link #deviation}
     * @return how stretched each bar is: {@code 0} for none, and {@code ±1},
     *         {@code ±2}, {@code ±3} for the three steps, positive when the
     *         line is stretched UP
     *
     * <p>Three steps and not a continuous number because the original paints
     * three colours per side, and a step is what a colour is.</p>
     */
    public static int[] exhaustion(double[] line, double[] deviation) {
        int size = line == null ? 0 : line.length;
        int[] made = new int[size];

        for (int i = 0; i < size; i++) {
            if (deviation == null || i >= deviation.length
                    || anyNaN(line[i], deviation[i]) || deviation[i] <= 0) {
                continue;
            }

            for (int step = STRETCH.length - 1; step >= 0; step--) {
                double far = STRETCH[step] * deviation[i];

                if (line[i] >= far) {
                    made[i] = step + 1;

                    break;
                }

                if (line[i] <= -far) {
                    made[i] = -(step + 1);

                    break;
                }
            }
        }

        return made;
    }

    /**
     * @param bars  the series the pivots are read from
     * @param line  the main line
     * @param wing  how many bars either side a pivot needs
     * @return {@code +1} where a bullish divergence became a fact, {@code -1}
     *         for a bearish one, {@code 0} everywhere else
     *
     * <p>Price made a lower low and the oscillator did not: the fall is running
     * out of momentum. Mirrored for the top.
     *
     * <h2>This one is CAUSAL, and the original is not</h2>
     *
     * <p>The NTSL detects its pivot at {@code [PeriodoPivo]} — the middle of a
     * window that reaches {@code PeriodoPivo} bars into the future — and
     * therefore repaints: the bar lights up some candles after the fact, and on
     * a live chart it can light up and go out again. Its own header says so and
     * says not to automate on it.
     *
     * <p>Here the divergence is filed under the bar that CONFIRMED it, through
     * {@link Pivots#confirmedAt}, so reading this array at bar <i>i</i> tells
     * you what was knowable at bar <i>i</i> and nothing more. Drawn beside the
     * original the marks therefore sit {@code wing} bars to the RIGHT of where
     * the Profit puts them. That is not a discrepancy to be fixed — it is the
     * look-ahead, made visible.</p>
     */
    public static int[] divergences(PriceSeries bars, double[] line, int wing) {
        int size = bars == null ? 0 : bars.size();
        int[] made = new int[size];

        if (line == null || line.length < size) {
            return made;
        }

        Pivots.Pivot[] confirmed = new Pivots(Math.max(1, wing)).confirmedAt(bars);
        double topPrice = Double.NaN;
        double topLine = Double.NaN;
        double bottomPrice = Double.NaN;
        double bottomLine = Double.NaN;

        for (int i = 0; i < size && i < confirmed.length; i++) {
            Pivots.Pivot pivot = confirmed[i];

            if (pivot == null || Double.isNaN(line[pivot.bar()])) {
                continue;
            }

            double here = line[pivot.bar()];

            if (pivot.top()) {
                // A HIGHER-OR-EQUAL TOP with a LOWER oscillator: the push that
                // made the new high was weaker than the one before it.
                if (!Double.isNaN(topPrice) && pivot.price() >= topPrice && here < topLine) {
                    made[i] = -1;
                }

                topPrice = pivot.price();
                topLine = here;
            } else {
                if (!Double.isNaN(bottomPrice) && pivot.price() <= bottomPrice && here > bottomLine) {
                    made[i] = 1;
                }

                bottomPrice = pivot.price();
                bottomLine = here;
            }
        }

        return made;
    }

    private static boolean anyNaN(double... values) {
        for (double each : values) {
            if (Double.isNaN(each)) {
                return true;
            }
        }

        return false;
    }
}
