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

import java.util.Arrays;

/**
 * Bollinger bands: a middle line with a band of deviations each side.
 *
 * <h2>The deviation is measured around the MIDDLE LINE, not around a mean</h2>
 *
 * <p>Both conventions exist and platforms disagree. This takes the
 * self-consistent one, which is also what the chart's own overlay takes: "two
 * deviations from the middle band" means two deviations from <em>that</em>
 * line, and not from some other line the reader cannot see. The two answers are
 * the same only when the middle is a simple mean — and differ, sometimes a
 * lot, when it is exponential and pulls away from it.
 *
 * <h2>Why the middle comes in from outside</h2>
 *
 * <p>{@link #around} is handed the middle line rather than computing one. That
 * is what lets the chart and the engine share this arithmetic without sharing a
 * class they cannot both see: the chart passes the moving average it is already
 * drawing, so its bands sit on the line the reader has on screen, and anything
 * else passes {@link #simpleMean}. One implementation of the deviation, which
 * is the part that is easy to get subtly wrong.
 *
 * <p><b>Still pending:</b> {@code ui.chart.overlay.BollingerBands} has its own
 * copy of this arithmetic and has NOT yet been made to delegate here. Until it
 * does, there are two implementations and they can drift — the exact failure
 * that moving {@code Rsi} into this package was meant to end. Said out loud
 * rather than left to be discovered.
 *
 * @param period      how many bars the deviation is measured over
 * @param deviations  how many of them the band sits out at
 */
public record Bollinger(int period, double deviations) {

    public static final int PERIOD = 20;

    public static final double DEVIATIONS = 2;

    /** The three lines, one value per bar. */
    public record Lines(double[] middle, double[] upper, double[] lower) {

        /**
         * @param bar   which bar
         * @param price where the market is
         * @return where that price sits across the band: {@code -1} on the
         *         lower, {@code 0} on the middle, {@code +1} on the upper
         *
         * <p>The reading worth carrying, and the one a band is usually
         * consulted for. {@link Double#NaN} while the band does not exist, and
         * zero when it has collapsed to no width at all — which happens on a
         * bar that never moved, and where "how far across" has no answer.</p>
         */
        public double placeAt(int bar, double price) {
            if (bar < 0 || bar >= middle.length || Double.isNaN(middle[bar])) {
                return Double.NaN;
            }

            double half = upper[bar] - middle[bar];

            return half == 0 ? 0 : (price - middle[bar]) / half;
        }
    }

    public Bollinger {
        if (period < 1) {
            throw new IllegalArgumentException("a band over " + period + " bars is not one");
        }
    }

    public static Bollinger standard() {
        return new Bollinger(PERIOD, DEVIATIONS);
    }

    /**
     * @param bars the series
     * @return the bands around a simple mean of the closes
     */
    public Lines over(PriceSeries bars) {
        return around(simpleMean(bars, period), bars);
    }

    /**
     * @param middle the line the bands are measured around, one value per bar
     * @param bars   the series the deviation is taken from
     * @return the three lines
     */
    public Lines around(double[] middle, PriceSeries bars) {
        int size = bars == null ? 0 : bars.size();
        double[] upper = new double[size];
        double[] lower = new double[size];

        Arrays.fill(upper, Double.NaN);
        Arrays.fill(lower, Double.NaN);

        for (int bar = 0; bar < size; bar++) {
            if (bar + 1 < period || bar >= middle.length || Double.isNaN(middle[bar])) {
                continue;
            }

            double squared = 0;

            for (int back = 0; back < period; back++) {
                double apart = bars.closeAt(bar - back) - middle[bar];

                squared += apart * apart;
            }

            // POPULATION and not sample: the window IS the population being
            // described, and every platform that draws these divides by n.
            double spread = Math.sqrt(squared / period);

            upper[bar] = middle[bar] + deviations * spread;
            lower[bar] = middle[bar] - deviations * spread;
        }

        return new Lines(middle, upper, lower);
    }

    /**
     * @param bars   the series
     * @param period the window
     * @return the plain mean of the closes, {@link Double#NaN} until it exists
     */
    public static double[] simpleMean(PriceSeries bars, int period) {
        int size = bars == null ? 0 : bars.size();
        double[] made = new double[size];

        Arrays.fill(made, Double.NaN);

        double running = 0;

        for (int bar = 0; bar < size; bar++) {
            running += bars.closeAt(bar);

            if (bar >= period) {
                running -= bars.closeAt(bar - period);
            }

            if (bar + 1 >= period) {
                made[bar] = running / period;
            }
        }

        return made;
    }
}
