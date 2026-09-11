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
 * The linear regression channel: a straight line through the last window, and
 * edges a number of deviations either side of it.
 *
 * <p>Here rather than with the chart's channel for the reason {@link Stochastic}
 * gives: a strategy reads it, and {@code domain} may not import {@code ui}.
 *
 * <h2>The levels are measured against the TREND, not against a mean</h2>
 *
 * <p>The residuals are distances from a line that already carries the slope, so
 * "two deviations above" on a falling channel is a price that is stretched
 * relative to a market the model already knows is falling. That is the whole
 * reason a channel is not a Bollinger band.
 *
 * <h2>Divided by n, not by n−1</h2>
 *
 * <p>The window <b>is</b> the thing being described, not a sample drawn from
 * something larger. The same choice the chart's channel makes, and the same
 * reason; keeping them different would make the level a strategy trades at a
 * different price from the level the chart drew.
 *
 * @param period how many bars the line is fitted over
 */
public record Regression(int period) {

    /** The larger of the two he asked for, and the chart's own default. */
    public static final int LONG = 90;

    /** And the shorter. */
    public static final int SHORT = 45;

    public Regression {
        if (period < 2) {
            throw new IllegalArgumentException(
                    "a line through " + period + " points is not a fit");
        }
    }

    /**
     * One fit, at one bar.
     *
     * @param line  the line's value AT that bar — the newest end of the window
     * @param sigma one deviation's worth; a level multiplies this
     * @param slope price per bar, positive when it rises
     */
    public record Fit(double line, double sigma, double slope) {

        /** A window that is not there yet: every reading NaN, and no side. */
        public static final Fit NONE = new Fit(Double.NaN, Double.NaN, Double.NaN);

        /**
         * @param factor how many deviations, signed: positive is above the line
         * @return the price of that level at this bar
         */
        public double at(double factor) {
            return line + factor * sigma;
        }

        /** @return +1 rising, −1 falling, 0 when flat or not yet known */
        public int direction() {
            if (Double.isNaN(slope) || slope == 0.0) {
                return 0;
            }

            return slope > 0 ? 1 : -1;
        }

        public boolean known() {
            return !Double.isNaN(line) && !Double.isNaN(sigma);
        }
    }

    /**
     * @param bars the series to read
     * @return one fit per bar; the warm-up is {@link Fit#NONE}
     */
    public Fit[] over(PriceSeries bars) {
        int size = bars == null ? 0 : bars.size();
        Fit[] made = new Fit[size];

        for (int bar = 0; bar < size; bar++) {
            made[bar] = at(bars, bar);
        }

        return made;
    }

    /**
     * @param bars   the series
     * @param anchor the newest bar of the window
     * @return the fit ending at that bar, or {@link Fit#NONE} before the window
     *         is full
     */
    public Fit at(PriceSeries bars, int anchor) {
        if (bars == null || anchor < period - 1 || anchor >= bars.size()) {
            return Fit.NONE;
        }

        int first = anchor - period + 1;

        // X GROWS WITH TIME: nought at the oldest bar of the window and
        // period-1 at the anchor. That is what makes the slope come out with
        // the right sign -- positive when it rises -- without being turned
        // round afterwards.
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXy = 0.0;
        double sumSquaredX = 0.0;

        for (int i = 0; i < period; i++) {
            double y = bars.closeAt(first + i);

            sumX += i;
            sumY += y;
            sumXy += i * y;
            sumSquaredX += (double) i * i;
        }

        double denominator = period * sumSquaredX - sumX * sumX;
        double gradient = denominator == 0.0
                ? 0.0 : (period * sumXy - sumX * sumY) / denominator;

        double intercept = (sumY - gradient * sumX) / period;
        double residualSum = 0.0;

        for (int i = 0; i < period; i++) {
            double residual = bars.closeAt(first + i) - (intercept + gradient * i);

            residualSum += residual * residual;
        }

        return new Fit(intercept + gradient * (period - 1),
                Math.sqrt(residualSum / period), gradient);
    }
}
