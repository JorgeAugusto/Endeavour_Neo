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
package br.com.jorge.reis.endeavourneo.ui.chart.overlay;

import br.com.jorge.reis.endeavourneo.domain.market.Aggregation;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences;
import br.com.jorge.reis.endeavourneo.ui.chart.OwnScale;

import java.util.ArrayList;
import java.util.List;

/**
 * An average an indicator READS rather than draws, and where the price crosses
 * it.
 *
 * <p>Two indicators now decide what to draw from an average that is never drawn
 * itself: the trendlines anchor on where the price last crossed one, and the
 * projection measures the zigzag leg that crossed it. Both need the same three
 * things -- an exponential average, the option of computing it on a coarser
 * scale, and the list of bars where the closes cross it -- and the second copy
 * of that is what this class exists to prevent.</p>
 *
 * <p><b>{@link MovingAverage} is not reused for it on purpose.</b> That is an
 * overlay: it carries a colour, a stroke, a shift, a source, visibility, and it
 * publishes itself to the legend. An indicator that only wants the numbers
 * would be dragging all of that behind it, and would have to keep a hidden
 * overlay alive to ask a question about a series.</p>
 */
final class Averages {

    private Averages() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @return an exponential average of the closes, one value per bar */
    static double[] exponential(PriceSeries series, int period) {
        double[] made = new double[series.size()];
        double weight = 2.0 / (period + 1);
        double now = series.size() == 0 ? 0.0 : series.closeAt(0);

        for (int i = 0; i < made.length; i++) {
            now = i == 0 ? now : now + weight * (series.closeAt(i) - now);
            made[i] = now;
        }

        return made;
    }

    /**
     * @param scale a period code, or null to follow the chart's own scale
     * @return the average laid over the chart's bars, or null when there is none
     *
     * <p>The folding is {@link OwnScale}'s, which owns the rule that an
     * indicator on a larger scale reads the last CLOSED coarse bar. Written a
     * second time here it would be a second chance to write it wrong.</p>
     *
     * <p>A scale this version does not know falls back to the chart's own. That
     * is the same answer {@code OwnScale.of} was built to allow: a layout
     * written by a later version naming a scale this one cannot build should
     * lose the scale, not the indicator.</p>
     */
    static double[] over(PriceSeries series, int period, String scale) {
        if (series == null || series.size() == 0) {
            return null;
        }

        Aggregation folded = scale == null || scale.isBlank() ? null : OwnScale.of(scale);

        if (folded == null) {
            return exponential(series, period);
        }

        PriceSeries coarse = folded.apply(series);

        if (coarse.size() == 0) {
            return null;
        }

        double[] slow = exponential(coarse, period);
        double[] into = new double[series.size()];

        if (ChartPreferences.interpolateOwnScale()) {
            OwnScale.smooth(series, coarse, slow, into);
        } else {
            OwnScale.map(series, coarse, slow, into);
        }

        return into;
    }

    /**
     * @return the bars at which the closes cross the average
     *
     * <p>A crossing is between two bars, and the NEWER of the two is the one
     * reported: it is the first bar that closed on the new side, which is the
     * first bar a reader could have known about it.</p>
     */
    static int[] crossings(PriceSeries series, double[] average) {
        List<Integer> found = new ArrayList<>();

        for (int i = 1; i < series.size() && i < average.length; i++) {
            double was = series.closeAt(i - 1) - average[i - 1];
            double now = series.closeAt(i) - average[i];

            if (Double.isFinite(was) && Double.isFinite(now) && (was > 0) != (now > 0)) {
                found.add(i);
            }
        }

        int[] bars = new int[found.size()];

        for (int i = 0; i < bars.length; i++) {
            bars[i] = found.get(i);
        }

        return bars;
    }
}
