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

import java.awt.Graphics2D;

/**
 * How the price itself is drawn: candles, a line, bars.
 *
 * <p>The contract is two methods, and that is the point. Chartsy shipped around
 * a hundred indicators as independent modules because the drawing contract was
 * this small; a style that had to know about panels, axes or the frame would be
 * a style nobody writes a second one of.</p>
 *
 * <p><b>A style draws price and nothing else.</b> No grid, no axis, no
 * crosshair, no legend — those belong to layers that do not change when the
 * style does. Mixing them is how a chart component reaches 1.500 lines and
 * stops accepting new styles, which is exactly where the previous project's
 * canvas ended up.</p>
 *
 * <p>Everything a style needs comes through the {@link Viewport}: it never does
 * its own price-to-pixel arithmetic.</p>
 */
public interface ChartStyle {

    /**
     * @return the bundle key for this style's name, e.g. {@code chart.candle}
     *
     * <p>A key rather than the text, so a style is translated like everything
     * else and does not have to know which language is on.</p>
     */
    String nameKey();

    /**
     * Draws the price.
     *
     * @param g where to draw; already clipped to the viewport's bounds
     * @param series the data
     * @param viewport the mapping and the visible range
     */
    void paint(Graphics2D g, PriceSeries series, Viewport viewport);
}
