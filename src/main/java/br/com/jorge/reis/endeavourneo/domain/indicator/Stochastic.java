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
 * The slow stochastic: where the close sits in the recent range.
 *
 * <p>The raw ratio — <b>fast %K</b> — is where this bar's close falls between
 * the lowest low and the highest high of the period, as a percentage. It is too
 * jumpy to read, so it is smoothed once into <b>slow %K</b> and that is smoothed
 * again into the <b>signal</b>, %D.
 *
 * <h2>Why this is in the domain and not with the chart</h2>
 *
 * <p>Because a strategy reads it. It was written inside the chart's study, where
 * the engine cannot reach it — {@code domain} may not import {@code ui} — and a
 * robot that filters on a stochastic would have needed a second implementation
 * of it. Two implementations of one indicator drift, and the drift is invisible:
 * the chart says the market was oversold and the run says it was not, and both
 * pictures look right on their own.
 *
 * <p>So the arithmetic lives here and the study calls it. What stayed up there
 * is everything about drawing: colours, line widths, the levels, which scale to
 * read at.
 *
 * <h2>A window where price never moved</h2>
 *
 * <p>The highest high can equal the lowest low — a quiet minute on a thin
 * instrument — and then the ratio divides by zero. The previous value is carried
 * instead of writing 50 or 100: there was no range to be at the top or the
 * bottom of, so the honest answer is that nothing changed.
 *
 * @param period  how many bars the range is measured over
 * @param average how many points each smoothing covers
 */
public record Stochastic(int period, int average) {

    /** The period his robots and the chart both start from. */
    public static final int PERIOD = 8;

    /** And the smoothing. */
    public static final int AVERAGE = 3;

    /**
     * How a line is smoothed.
     *
     * <p>The same three the chart's moving average offers, named the same way
     * so the interface can map one onto the other by name. It is repeated here
     * rather than imported because the arrow only points one way: the domain
     * cannot see {@code ui}, and it must not learn to.</p>
     */
    public enum Smoothing {
        ARITHMETIC, EXPONENTIAL, WEIGHTED
    }

    /**
     * @param slow   %K, the ratio smoothed once
     * @param signal %D, that smoothed again
     *
     * <p>Both carry {@link Double#NaN} through the warm-up, and NaN is not a
     * placeholder for zero: a period of eight has nothing to say at bar three,
     * and zero would be a claim — plotted, it drags the line along the floor;
     * compared against a level, it reads as maximally oversold.</p>
     */
    public record Lines(double[] slow, double[] signal) {

        public Lines {
            slow = slow == null ? new double[0] : slow.clone();
            signal = signal == null ? new double[0] : signal.clone();
        }

        @Override
        public double[] slow() {
            return slow.clone();
        }

        @Override
        public double[] signal() {
            return signal.clone();
        }
    }

    public Stochastic {
        if (period < 1) {
            throw new IllegalArgumentException("a range over " + period + " bars is not a range");
        }

        if (average < 1) {
            throw new IllegalArgumentException("a smoothing over " + average + " points is none");
        }
    }

    /** @return the stochastic his chart is born with: eight and three */
    public static Stochastic standard() {
        return new Stochastic(PERIOD, AVERAGE);
    }

    /**
     * @param bars the series to read
     * @return both lines, one value per bar
     */
    public Lines over(PriceSeries bars) {
        return over(bars, Smoothing.ARITHMETIC);
    }

    /**
     * @param bars how the two lines are smoothed
     * @param how  which smoothing
     * @return both lines, one value per bar
     */
    public Lines over(PriceSeries bars, Smoothing how) {
        int size = bars == null ? 0 : bars.size();
        double[] fast = new double[size];
        double carried = 50.0;

        for (int i = 0; i < size; i++) {
            if (i < period - 1) {
                fast[i] = Double.NaN;

                continue;
            }

            double lowest = bars.lowAt(i);
            double highest = bars.highAt(i);

            for (int back = i - period + 1; back <= i; back++) {
                lowest = Math.min(lowest, bars.lowAt(back));
                highest = Math.max(highest, bars.highAt(back));
            }

            double span = highest - lowest;

            // See the class note: no range means no answer, so the last one
            // stands rather than a number being invented for it.
            carried = span <= 0.0 ? carried
                    : 100.0 * (bars.closeAt(i) - lowest) / span;
            fast[i] = carried;
        }

        double[] slow = new double[size];
        double[] signal = new double[size];

        smooth(fast, slow, how);
        smooth(slow, signal, how);

        return new Lines(slow, signal);
    }

    /**
     * Averages one line into another, over {@link #average()} points.
     *
     * <p>Warm-up stays NaN and is not counted: an average of three that met two
     * numbers is an average of two wearing the wrong name.</p>
     *
     * <p>A RING OF PRIMITIVES, not a {@code List<Double>}. This runs twice per
     * calculation, over every bar; on the real series that was 1,65 million
     * {@code Double} objects boxed and thrown away, plus a {@code remove(0)}
     * shifting the list each time. Measured before the change: 319 ms to
     * recalculate one stochastic against 5 ms for a moving average over the same
     * bars, and this method was the whole of the difference.</p>
     */
    private void smooth(double[] from, double[] into, Smoothing how) {
        int span = Math.max(1, average);
        double[] window = new double[span];
        int held = 0;
        int next = 0;
        double sum = 0.0;
        double previous = Double.NaN;
        double weight = 2.0 / (average + 1.0);

        for (int i = 0; i < from.length; i++) {
            if (Double.isNaN(from[i])) {
                into[i] = Double.NaN;
                held = 0;
                next = 0;
                sum = 0.0;
                previous = Double.NaN;

                continue;
            }

            if (held == span) {
                // Full: the oldest is where the next one goes.
                sum -= window[next];
            } else {
                held++;
            }

            window[next] = from[i];
            sum += from[i];
            next = (next + 1) % span;

            if (held < average) {
                into[i] = Double.NaN;

                continue;
            }

            if (how == Smoothing.EXPONENTIAL) {
                // AFTER the window, not before it, and seeded with that
                // window's arithmetic mean. Seeded from the first finite point
                // instead, the line begins `average` bars early with a hook at
                // the left edge -- and the signal, being the smoothing of this
                // one, inherits the hook.
                previous = Double.isNaN(previous) ? sum / average
                        : from[i] * weight + previous * (1.0 - weight);
                into[i] = previous;

                continue;
            }

            if (how == Smoothing.WEIGHTED) {
                double total = 0.0;
                double divisor = 0.0;

                // Oldest first, which when the ring is full is where the next
                // write would land. The weight rises with age towards the
                // present.
                for (int at = 0; at < held; at++) {
                    total += window[(next + at) % span] * (at + 1);
                    divisor += at + 1;
                }

                into[i] = total / divisor;

                continue;
            }

            into[i] = sum / average;
        }
    }
}
