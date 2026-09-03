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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.awt.Color;
import java.util.List;

/**
 * Something drawn on top of the price: a moving average, a band, a level.
 *
 * <p><b>{@link #valueAt(int)} is the method the rest of the chart is built
 * around.</b> One implementation feeds three things at once — the legend across
 * the top, the crosshair readout, and any future export. Chartsy reached the
 * same shape and calls it {@code getValues(chartFrame, i)}; arriving at it
 * independently is a good sign it is the right cut.</p>
 *
 * <p>An overlay computes once and is asked many times. Recomputing inside
 * {@code valueAt} would run the whole series on every mouse move, which is
 * exactly the sort of thing that makes a chart feel heavy without anything
 * obviously wrong in the code.</p>
 *
 * <p><b>Distinct from a study in its own panel</b> — an RSI or a MACD lives
 * below the price on its own scale, and putting one on the price axis would
 * squash the candles to nothing. That is a separate contract, and it is not
 * this one.</p>
 */
public interface Overlay {

    /** @return the bundle key for the name, e.g. {@code overlay.ema} */
    String nameKey();

    /**
     * @return the parameters, rendered inline in the legend
     *
     * <p>Shown as {@code [17 21 0 8 21]}, because a chart carrying three moving
     * averages is unreadable without knowing which is which — and having to open
     * a dialog to find out breaks the reading.</p>
     */
    List<Integer> parameters();

    /** @return one colour per line this overlay draws, in the same order as the values */
    List<Color> colours();

    /**
     * @param bar an index into the series
     * @return one value per line, or NaN where the overlay has nothing yet
     *
     * <p>NaN rather than zero during the warm-up: a 200-period average has no
     * value at bar 3, and zero would be a claim — plotted, it drags a line from
     * the bottom of the chart to the first real value.</p>
     */
    double[] valueAt(int bar);

    /**
     * Computes everything, once, against this series.
     *
     * @param series the data
     */
    void calculate(PriceSeries series);

    /** @return whether it should be drawn; the show/hide toggle */
    boolean isVisible();

    void setVisible(boolean visible);

    /**
     * @return how the line is drawn: thickness and dash pattern
     *
     * <p>A default, so an indicator that has nothing to say about its own
     * appearance says nothing. One and a bit pixels, solid, rounded -- what
     * every line here was before any of them could be configured.</p>
     */
    default java.awt.Stroke stroke() {
        return new java.awt.BasicStroke(1.4f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND);
    }

    /**
     * @return everything about this indicator that is NOT its shape, as one line
     *
     * <p>Kept apart from {@link #parameters()} because the two are stored
     * differently and answer different questions. The parameters are the numbers
     * that decide what is computed; this is colour, dash and thickness -- what
     * it looks like once computed. A layout stores both, and an indicator that
     * came back in the right place wearing the wrong colour would be a layout
     * that only half worked.</p>
     *
     * <p>Empty by default: an indicator with nothing to remember remembers
     * nothing, and the stored line stays short.</p>
     */
    default String appearance() {
        return "";
    }

    /** @param text whatever {@link #appearance()} produced, possibly from an older version */
    default void applyAppearance(String text) {
        // Nothing to apply.
    }
}
