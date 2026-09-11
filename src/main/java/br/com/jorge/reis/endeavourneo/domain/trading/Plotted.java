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
package br.com.jorge.reis.endeavourneo.domain.trading;

import java.util.Map;

/**
 * A strategy that can show its working.
 *
 * <p>Implemented by a strategy that computes something worth looking at — the
 * two averages of a crossing, a band, a channel — so the chart can draw it
 * beside the operations and the reader can see <b>why</b> each entry happened
 * where it did.</p>
 *
 * <h2>What the strategy USED, not what the chart can recompute</h2>
 *
 * <p>The chart already knows how to draw a moving average, and putting one on
 * the chart beside the trades would look like the same thing. It is not. The
 * chart's average is seeded its own way, over its own scale, from its own
 * source; the strategy's was seeded from the first close of the run and stepped
 * once per bar it was shown. Two averages that agree to four decimal places
 * will still disagree about which bar the crossing fell on — and <b>which bar
 * the crossing fell on is the entire question</b>.</p>
 *
 * <p>So these are the numbers the strategy decided from, recorded as it
 * decided. When a mark on the chart looks wrong, the line under it is the one
 * that put it there, and the disagreement is real rather than an artefact of
 * drawing the indicator twice.</p>
 *
 * <h2>Why this is not part of Strategy</h2>
 *
 * <p>Most strategies have nothing to show, and a method on the contract that
 * almost everyone answers with an empty map is a method that gets answered
 * carelessly. A separate interface means a strategy either shows its working or
 * says nothing about it.</p>
 */
public interface Plotted {

    /**
     * @return one value per bar of the run, by name, in drawing order
     *
     * <p>Names reach the reader, in the chart's legend. An array shorter than
     * the series is drawn as far as it goes; {@link Double#NaN} breaks the line,
     * which is how a value that does not exist yet is said.</p>
     */
    Map<String, double[]> curves();
}
