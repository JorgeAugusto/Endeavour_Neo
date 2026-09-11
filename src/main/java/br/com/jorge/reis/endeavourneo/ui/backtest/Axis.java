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
package br.com.jorge.reis.endeavourneo.ui.backtest;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

/**
 * Moves a bar number from one series onto another, by the clock.
 *
 * <h2>Why a run has two axes at all</h2>
 *
 * <p>A backtest <b>decides</b> on the chart's bars and <b>executes</b> on
 * something finer. A fill therefore carries the number of the bar it executed
 * on — which over a tick path is one of four and a half million, while the
 * chart beside it is drawing two thousand candles. Same instant, two numbers,
 * and drawing one on the other puts every mark of a tick run in the first pixel
 * of the chart.</p>
 *
 * <p>The clock is what the two have in common, so the clock is what this
 * translates through. Both series are in time order, so the answer is a binary
 * search and nothing is held: no map of four and a half million entries, and no
 * cost at all when the two series are the same one.</p>
 */
final class Axis {

    /** The axis of a run whose two series are the same: every number is itself. */
    static final Axis SAME = new Axis(null, null);

    private final PriceSeries from;

    private final PriceSeries to;

    private Axis(PriceSeries from, PriceSeries to) {
        this.from = from;
        this.to = to;
    }

    /**
     * @param from the series the numbers come in
     * @param to   the series they are wanted in
     * @return the translation, or {@link #SAME} when there is nothing to do
     */
    static Axis of(PriceSeries from, PriceSeries to) {
        // THE SAME OBJECT IS THE COMMON CASE, not an optimisation: in OHLC mode
        // the run decides and executes on one series, and a binary search per
        // mark per repaint to answer "itself" would be work done to change
        // nothing.
        return from == null || to == null || from == to || to.size() == 0
                ? SAME : new Axis(from, to);
    }

    /**
     * @param bar a bar of the {@code from} series
     * @return the bar of {@code to} that holds the same instant, clamped to its
     *         ends — a number outside the destination cannot be drawn, and
     *         clamping puts the mark at the edge the reader is looking towards
     *         rather than dropping it
     */
    int map(int bar) {
        if (from == null || bar < 0) {
            return bar;
        }

        if (bar >= from.size()) {
            return to.size() - 1;
        }

        long when = from.timeAt(bar);
        int low = 0;
        int high = to.size() - 1;

        // The LAST bar at or before that instant. A tick at 10:03:40 belongs to
        // the five-minute candle that opened at 10:00, not to the one after it.
        while (low < high) {
            int middle = (low + high + 1) >>> 1;

            if (to.timeAt(middle) <= when) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }

        return low;
    }
}
