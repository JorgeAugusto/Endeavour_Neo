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
package br.com.jorge.reis.endeavourneo.ui.chart.study;

import br.com.jorge.reis.endeavourneo.ui.chart.ChartCanvas;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartColors;
import br.com.jorge.reis.endeavourneo.ui.chart.Forms;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.PeriodCatalog;
import br.com.jorge.reis.endeavourneo.ui.chart.PeriodDialog;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.Color;
import java.awt.Stroke;
import java.util.List;

/**
 * An indicator that lives in its own panel, under the price, on its own scale.
 *
 * <h2>Why it is not an {@link Overlay}</h2>
 *
 * <p>An overlay is drawn on the price axis, and that only works for something
 * measured in price — an average, a band. A stochastic runs from zero to a
 * hundred; put on a chart of the mini index it would be a flat line along the
 * bottom, and putting the index on a zero-to-hundred axis would flatten the
 * candles instead. The two cannot share an axis, so they do not share a
 * panel.</p>
 *
 * <p>Everything else they DO share, which is why this extends that: the values,
 * the legend, the colours, the own-scale rule, the appearance line a layout
 * stores. A study is an overlay that brought its own axis.</p>
 *
 * <h2>What a panel needs beyond an overlay</h2>
 *
 * <p>Three things, and no more: how tall its world is, what horizontal lines
 * belong to it, and one stroke per line rather than one for the lot. Anything
 * else the panel needs it can ask the overlay half of this.</p>
 */
public interface Study extends Overlay {

    /**
     * @return the fixed vertical range as {@code {low, high}}, or null to fit
     *         whatever the values happen to be
     *
     * <p>Fixed for anything bounded by its own definition — a stochastic is
     * zero to a hundred whatever the market does, and letting the panel rescale
     * to the visible values would make eighty look like the top of the world on
     * a quiet afternoon. Null for the ones that are not bounded, a MACD being
     * the obvious one.</p>
     */
    double[] bounds();

    /**
     * @return the horizontal lines that belong to this study, if any
     *
     * <p>Part of the study and not of the panel, because they are part of what
     * the indicator MEANS: twenty and eighty are where a stochastic says
     * something, and a panel that drew its own grid instead would be drawing
     * lines that mean nothing.</p>
     */
    default List<Level> levels() {
        return List.of();
    }

    /**
     * @return one stroke per line, in the same order as {@link #valueAt}
     *
     * <p>{@link Overlay#stroke()} gives one for the whole indicator, which is
     * right when every line of it is the same kind of thing. A study whose
     * second line is a smoothing of its first wants to say so by drawing them
     * differently, and one stroke cannot.</p>
     */
    default List<Stroke> strokes() {
        return List.of(stroke());
    }

    /**
     * A horizontal line at a fixed value.
     *
     * @param at where it sits on the study's own scale
     * @param colour how it is drawn
     * @param stroke thickness and dash
     */
    record Level(double at, Color colour, Stroke stroke) { }
}
