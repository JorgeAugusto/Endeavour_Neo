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
package br.com.jorge.reis.endeavourneo.ui.chart.style;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import br.com.jorge.reis.endeavourneo.ui.chart.ChartColors;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartStyle;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * A single line through the closing prices.
 *
 * <p>Worth having next to candles rather than instead of them: over hundreds of
 * bars the candle bodies stop being separable and the shape of the move is what
 * remains readable. It is also the honest style for anything that has no open,
 * high and low -- an equity curve, an indicator, a spread.</p>
 */
public final class LineStyle implements ChartStyle {

    @Override
    public String nameKey() {
        return "chart.style.line";
    }

    @Override
    public void paint(Graphics2D g, PriceSeries series, Viewport viewport) {
        int from = viewport.firstBar();
        int to = Math.min(viewport.lastBar(), series.size());

        if (to - from < 2) {
            return;
        }

        int[] xs = new int[to - from];
        int[] ys = new int[to - from];

        for (int i = from; i < to; i++) {
            xs[i - from] = (int) Math.round(viewport.x(i));
            ys[i - from] = (int) Math.round(viewport.y(series.closeAt(i)));
        }

        // Antialiasing only here. On candles it blurs the one-pixel body of a
        // doji into grey; on a diagonal line its absence produces visible
        // stair-stepping.
        Object previous = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(ChartColors.foreground());
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawPolyline(xs, ys, xs.length);

        if (previous != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previous);
        }
    }
}
