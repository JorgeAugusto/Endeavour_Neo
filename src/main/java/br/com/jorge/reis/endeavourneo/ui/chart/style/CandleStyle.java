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

import br.com.jorge.reis.endeavourneo.ui.chart.ChartColors;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartStyle;
import br.com.jorge.reis.endeavourneo.ui.chart.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.BasicStroke;
import java.awt.Graphics2D;

/**
 * Japanese candles: a body from open to close, a wick from low to high.
 *
 * <p>Three details decide whether this looks professional or homemade, and all
 * three are about very small candles.</p>
 */
public final class CandleStyle implements ChartStyle {

    /** Share of the bar's width the body occupies; the rest is the gap. */
    private static final double BODY_SHARE = 0.72;

    /** Below this width in pixels, bodies stop being drawn and only wicks remain. */
    private static final double MINIMUM_BODY_WIDTH = 3.0;

    @Override
    public String nameKey() {
        return "chart.style.candle";
    }

    @Override
    public void paint(Graphics2D g, PriceSeries series, Viewport viewport) {
        double width = viewport.barWidth();
        double bodyWidth = Math.max(1.0, Math.floor(width * BODY_SHARE));
        boolean bodies = width >= MINIMUM_BODY_WIDTH;

        g.setStroke(new BasicStroke(1.0f));

        for (int i = viewport.firstBar(); i < viewport.lastBar() && i < series.size(); i++) {
            double open = series.openAt(i);
            double close = series.closeAt(i);
            boolean rising = close >= open;

            g.setColor(rising ? ChartColors.up() : ChartColors.down());

            double centre = viewport.x(i);
            int x = (int) Math.round(centre);

            // The wick is drawn first and full length, so the body covers its
            // middle. Drawing it after would leave a line across every body.
            g.drawLine(x, (int) Math.round(viewport.y(series.highAt(i))),
                    x, (int) Math.round(viewport.y(series.lowAt(i))));

            if (!bodies) {
                // Zoomed far out the bodies would be thinner than a pixel and
                // would just thicken the wick into a smear. Wicks alone still
                // show the range, which is what is legible at this scale.
                continue;
            }

            double top = viewport.y(Math.max(open, close));
            double bottom = viewport.y(Math.min(open, close));

            int left = (int) Math.round(centre - bodyWidth / 2.0);
            int height = (int) Math.round(bottom - top);

            // A doji -- open equal to close -- has zero height and would vanish
            // entirely. One pixel is the honest minimum: the bar exists.
            g.fillRect(left, (int) Math.round(top), (int) bodyWidth, Math.max(1, height));
        }
    }
}
