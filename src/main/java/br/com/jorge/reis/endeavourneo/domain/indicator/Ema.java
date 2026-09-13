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
 * The exponential moving average of the closes.
 *
 * <p>Here rather than with the chart's average for the reason
 * {@link Stochastic} gives: a strategy reads it, and {@code domain} may not
 * import {@code ui}. The chart's {@code MovingAverage} calls this one, so there
 * is a single set of numbers and no chance of the picture and the run
 * disagreeing about which way the trend points.
 *
 * <h2>The seed is the first window's mean</h2>
 *
 * <p>Not the first price, which is what a naive reading of the recurrence
 * suggests. Every platform seeds from the arithmetic average of the first
 * {@code period} closes, and starting from one price instead leaves a visible
 * hook at the left edge — and, worse for a robot, gives the first several
 * hundred bars a value that depends on where the recorte happened to begin.
 *
 * @param period how many bars the average covers
 */
public record Ema(int period) {

    public Ema {
        if (period < 1) {
            throw new IllegalArgumentException("an average over " + period + " bars is not one");
        }
    }

    /**
     * @param bars the series to read
     * @return one value per bar; the warm-up is {@link Double#NaN}
     */
    public double[] over(PriceSeries bars) {
        int size = bars == null ? 0 : bars.size();
        double[] closes = new double[size];

        for (int i = 0; i < size; i++) {
            closes[i] = bars.closeAt(i);
        }

        return over(closes);
    }

    /**
     * @param values any series, not only prices
     * @return one value per point; the warm-up is {@link Double#NaN}
     *
     * <p>The average of a <b>derived</b> series — a rate of change, another
     * average — which is what the NTSL's {@code MediaExp(periodo, serie)}
     * accepts and what a double smoothing like the PMO is made of. Without it
     * every such indicator would have to carry its own copy of the recurrence,
     * and the copies drift.</p>
     *
     * <h2>NaN at the front is warm-up, not a hole</h2>
     *
     * <p>A derived series begins later than the bars do: a rate of change over
     * five has nothing to say on bar two. Those NaN are skipped and the window
     * simply starts where the numbers start — the alternative, feeding a zero
     * in, is what the NTSL original does when its guard fails, and it drags the
     * first hundred values towards zero for no reason anybody would recognise.
     *
     * <p>A NaN in the MIDDLE is a different thing and is not handled: from
     * there on the recurrence has no previous value and the rest comes back
     * NaN. None of the series this is used on have one, and inventing a rule
     * for a case that does not occur would be a rule nobody could check.</p>
     */
    public double[] over(double[] values) {
        int size = values == null ? 0 : values.length;
        double[] made = new double[size];
        double seed = 0.0;
        int counted = 0;

        for (int i = 0; i < size; i++) {
            // NaN and never zero: an average of seventeen has nothing to say at
            // bar three, and zero read as a price is a trend pointing violently
            // down.
            made[i] = Double.NaN;

            if (Double.isNaN(values[i])) {
                continue;
            }

            if (counted < period) {
                seed += values[i];
                counted++;

                if (counted == period) {
                    made[i] = seed / period;
                }

                continue;
            }

            double weight = 2.0 / (period + 1.0);

            made[i] = values[i] * weight + made[i - 1] * (1 - weight);
        }

        return made;
    }
}
