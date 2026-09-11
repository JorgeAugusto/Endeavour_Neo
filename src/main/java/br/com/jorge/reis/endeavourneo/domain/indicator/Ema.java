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
        double[] made = new double[size];
        double seed = 0.0;

        for (int i = 0; i < size; i++) {
            if (i < period - 1) {
                seed += bars.closeAt(i);
                // NaN and never zero: an average of seventeen has nothing to say
                // at bar three, and zero read as a price is a trend pointing
                // violently down.
                made[i] = Double.NaN;
            } else if (i == period - 1) {
                seed += bars.closeAt(i);
                made[i] = seed / period;
            } else {
                double weight = 2.0 / (period + 1.0);

                made[i] = bars.closeAt(i) * weight + made[i - 1] * (1 - weight);
            }
        }

        return made;
    }
}
