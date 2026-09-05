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
package br.com.jorge.reis.endeavourneo.ui.chart;

import br.com.jorge.reis.endeavourneo.domain.market.Aggregation;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import br.com.jorge.reis.endeavourneo.ui.chart.PeriodCatalog;

/**
 * Drawing an indicator computed on a LARGER scale than the chart it sits on.
 *
 * <p><b>The one place this rule is written.</b> It lives here rather than in
 * each indicator because it is the trap this project has already paid for once:
 * the obvious mapping takes, for each bar on screen, the coarse bar that
 * <i>contains</i> it — and that bar is made partly of the future. A fifteen
 * minute average read at 09:05 would know what happened by 09:14. The rule is
 * therefore <b>the last CLOSED coarse bar</b>, and a second copy of it in
 * another indicator is a second chance to get it wrong. See {@code
 * OwnPeriodTest}, which states it as a property.</p>
 *
 * <p><b>Shared by overlays and by studies</b>, which is why it sits here and
 * not inside either. The rule is about reading a coarser scale without reading
 * the future, and that is true of a moving average drawn on the price and of a
 * stochastic drawn under it in equal measure.</p>
 *
 * <p>Interpolation only slopes the line between points that are already
 * closed, so it makes the line smoother without letting it know anything
 * sooner.</p>
 */
public final class OwnScale {

    private OwnScale() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param code a period code, as {@code PeriodCatalog} spells it
     * @return how to build that scale, or null when the code names none
     *
     * <p>Null rather than an exception: a layout written by a later version can
     * name a scale this one does not build, and the caller's answer to that is
     * to fall back to the chart's own scale — a smaller wrong than an
     * indicator listed but invisible.</p>
     */
    public static Aggregation of(String code) {
        PeriodCatalog.Choice choice = PeriodCatalog.byCode(code);

        return choice == null ? null : choice.aggregation();
    }

    /**
     * @param fine the chart's own bars
     * @param coarse the same bars folded to the larger scale
     * @param bar an index into {@code fine}
     * @return the index in {@code coarse} of the last bar that had CLOSED by
     *         then, or -1 when none had
     */
    public static int indexOfClosed(PriceSeries fine, PriceSeries coarse, int bar) {
        long when = fine.timeAt(bar);
        int low = 0;
        int high = coarse.size() - 1;
        int found = -1;

        // A coarse bar is closed once the NEXT one has begun. Binary search on
        // that condition rather than a scan, because this is called per bar per
        // indicator and the chart repaints while the mouse moves.
        while (low <= high) {
            int middle = (low + high) >>> 1;

            if (middle + 1 < coarse.size() && coarse.timeAt(middle + 1) <= when) {
                found = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }

        return found;
    }

    /**
     * Spreads a value computed per coarse bar across the chart's bars.
     *
     * @param slow one value per coarse bar
     * @param into one value per chart bar; filled with NaN before the first close
     */
    public static void map(PriceSeries fine, PriceSeries coarse, double[] slow, double[] into) {
        if (coarse.size() == 0) {
            java.util.Arrays.fill(into, Double.NaN);

            return;
        }

        int closed = -1;

        for (int i = 0; i < into.length; i++) {
            // Walk forward while the NEXT coarse bar has already begun -- which
            // is what makes the one before it closed. A running pointer rather
            // than a search per bar: this walks each series once.
            while (closed + 1 < coarse.size() - 1
                    && coarse.timeAt(closed + 2) <= fine.timeAt(i)) {
                closed++;
            }

            if (closed < 0 && coarse.size() > 1 && coarse.timeAt(1) <= fine.timeAt(i)) {
                closed = 0;
            }

            into[i] = closed < 0 ? Double.NaN : slow[closed];
        }
    }

    /**
     * Slopes the mapped line between closed points.
     *
     * <p>Between the PREVIOUS closed value and the current one, never towards
     * the one still forming. That is what keeps it honest: the line is smoother
     * and still says nothing the market had not already said.</p>
     */
    public static void smooth(PriceSeries fine, PriceSeries coarse, double[] slow, double[] into) {
        for (int i = 0; i < into.length; i++) {
            int closed = indexOfClosed(fine, coarse, i);

            if (closed < 1 || !Double.isFinite(slow[closed]) || !Double.isFinite(slow[closed - 1])) {
                continue;
            }

            long from = coarse.timeAt(closed);
            long to = closed + 1 < coarse.size() ? coarse.timeAt(closed + 1) : from;
            long span = to - from;

            if (span <= 0) {
                continue;
            }

            double along = Math.max(0.0, Math.min(1.0, (fine.timeAt(i) - from) / (double) span));

            into[i] = slow[closed - 1] + (slow[closed] - slow[closed - 1]) * along;
        }
    }
}
