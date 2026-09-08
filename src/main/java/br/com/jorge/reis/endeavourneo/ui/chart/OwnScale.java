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

    // indexOfClosed WAS HERE, and it is gone. A public binary search for "which
    // coarse bar had closed at this instant", with no caller in the product and
    // none in the tests -- and its own comment claimed a use it did not have:
    // "this is called per bar per indicator and the chart repaints while the
    // mouse moves". Thirty lines below, smooth said the opposite about the same
    // method, in the same file.
    //
    // It mattered more here than dead code usually does. The javadoc of this
    // class says it is "the one place this rule is written", the rule being
    // that an indicator on a larger scale must not read a bar that has not
    // closed. A second place where the rule is written is a second chance to
    // write it wrong, and this one had no test to catch that.

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
            closed = advance(fine, coarse, closed, i);

            into[i] = closed < 0 ? Double.NaN : slow[closed];
        }
    }

    /**
     * @param fine the chart's own bars
     * @param coarse the same bars folded to the larger scale
     * @param closed where the pointer stood
     * @param i the chart bar being answered for
     * @return the last coarse bar that had CLOSED by that bar's instant
     *
     * <p><b>Written once, and it used to be written twice.</b> These eight
     * lines were identical in {@link #map} and {@link #smooth} -- and they are
     * the arithmetic this class's own javadoc calls the trap the project has
     * already paid for once. Two copies of it are two chances to get the next
     * correction wrong, in the one place that exists so the rule is written
     * down only once.</p>
     *
     * <p>A running pointer and not a search per bar: both series are
     * chronological, so the answer only ever moves forward. A search asked once
     * per bar walks the coarse series 825.000 times over -- 183 ms per
     * indicator, measured.</p>
     *
     * <p>The loop had a second half -- {@code if (closed < 0 && coarse.size() >
     * 1 && coarse.timeAt(1) <= fine.timeAt(i)) closed = 0;} -- that could never
     * run. With {@code closed} at -1 the while asks {@code 0 < size - 1}, which
     * is exactly "size is at least two", and {@code timeAt(closed + 2)} is
     * {@code timeAt(1)}: the same two conditions, already answered. It was
     * written twice, in map and in smooth, which is how nobody noticed.</p>
     */
    private static int advance(PriceSeries fine, PriceSeries coarse, int closed, int i) {
        while (closed + 1 < coarse.size() - 1
                && coarse.timeAt(closed + 2) <= fine.timeAt(i)) {
            closed++;
        }

        return closed;
    }

    /**
     * Slopes the mapped line between closed points.
     *
     * <p>Between the PREVIOUS closed value and the current one, never towards
     * the one still forming. That is what keeps it honest: the line is smoother
     * and still says nothing the market had not already said.</p>
     */
    public static void smooth(PriceSeries fine, PriceSeries coarse, double[] slow, double[] into) {
        // A RUNNING POINTER, the way map does it, and not a binary search per
        // bar. A search is the right answer to "which bar was closed at this
        // instant" asked once; asked once per bar it walks the coarse series
        // 825.000 times over. Measured: recalculating an average on its
        // own scale took 183 ms, on the interface thread, once per indicator in
        // the panel. Both series are chronological, so the answer only ever
        // moves forward.
        int closed = -1;

        for (int i = 0; i < into.length; i++) {
            closed = advance(fine, coarse, closed, i);

            if (closed < 1 || !Double.isFinite(slow[closed]) || !Double.isFinite(slow[closed - 1])) {
                continue;
            }

            // The ramp runs across the bar still FORMING, not across the one
            // that already closed. slow[closed] only became knowable at the
            // instant coarse bar `closed` shut -- which is timeAt(closed + 1),
            // never timeAt(closed) -- so that instant is where the line leaves
            // the previous value and starts walking towards it.
            //
            // Measuring from timeAt(closed) instead put the whole ramp behind
            // every bar that reaches this code: indexOfClosed only answers
            // `closed` once timeAt(closed + 1) has passed, so the fraction was
            // always at or past 1, clamped to 1, and the result was always
            // slow[closed] -- arithmetically identical to map(). The option was
            // on by default in three indicators and had never bent a line.
            long shut = coarse.timeAt(closed + 1);
            // The forming bar has no close yet, so its width is unknown; the
            // one before it is the only honest estimate available.
            long ends = closed + 2 < coarse.size()
                    ? coarse.timeAt(closed + 2)
                    : shut + (shut - coarse.timeAt(closed));
            long span = ends - shut;

            if (span <= 0) {
                continue;
            }

            double along = Math.max(0.0, Math.min(1.0, (fine.timeAt(i) - shut) / (double) span));

            into[i] = slow[closed - 1] + (slow[closed] - slow[closed - 1]) * along;
        }
    }
}
