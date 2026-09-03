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
package br.com.jorge.reis.endeavourneo.domain.market;

/**
 * Bars in, bars out.
 *
 * <p>Deliberately this weak. The obvious interface would be a <i>grouping</i> —
 * "which source bars belong to this output bar" — and it would be wrong before
 * the second implementation arrives:</p>
 *
 * <ul>
 *   <li><b>Time</b> groups: several one-minute bars become one five-minute bar.</li>
 *   <li><b>Renko</b> does not group. A single minute in which price runs three
 *       brick-heights produces <b>three</b> bricks, and a quiet hour produces
 *       none. Output bars have no fixed relation to input bars at all.</li>
 *   <li><b>Synthetic ticks</b> run the other way entirely: one bar becomes a
 *       path of prices inside it, for replay and for the animated backtest.</li>
 * </ul>
 *
 * <p>Only "a series in, a series out" holds for all three, so that is the whole
 * contract. What each one does inside is its own business.</p>
 *
 * <p>Aggregations compose, and are meant to: one minute → five minutes → renko,
 * or one minute → synthetic ticks → renko. Each step is a series, so the chart
 * and the backtest engine can be handed any point in the chain without knowing
 * how it was built.</p>
 */
@FunctionalInterface
public interface Aggregation {

    /**
     * @param source the bars to read, in chronological order
     * @return the bars produced, also chronological
     *
     * <p>Must not modify the source, and must be safe to call more than once on
     * the same one — a chart redrawing and an engine running are two callers of
     * the same series.</p>
     */
    PriceSeries apply(PriceSeries source);

    /**
     * @return how this scale is named, in a window title and in the period list
     *
     * <p>A default and not a second abstract method, so the interface stays
     * usable as a lambda -- an aggregation written inline for one measurement
     * has nothing to call itself.</p>
     */
    default String label() {
        return toString();
    }

    /** @return an aggregation that changes nothing, for "the scale it is stored in" */
    static Aggregation none() {
        return source -> source == null ? PriceSeries.empty() : source;
    }
}
