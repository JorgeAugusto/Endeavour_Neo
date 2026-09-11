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
    public void paint(Graphics2D g, PriceSeries series, Viewport viewport,
                      br.com.jorge.reis.endeavourneo.ui.chart.BarTint tint) {

        // A LINHA NAO TEM BARRA PARA TINGIR. Uma linha de fechamentos e um
        // traco continuo: nao ha onde uma barra comecar e a seguinte acabar, e
        // pintar um pedaco dela de outra cor diria que o padrao durou o trecho
        // inteiro entre dois fechamentos.

        int from = viewport.firstBar();
        int to = Math.min(viewport.lastBar(), series.size());

        if (to - from < 2) {
            return;
        }

        // ONE COLUMN AT A TIME below a pixel per bar, and the allocation was the
        // smaller of the two things wrong with a point per bar: at a whole
        // series this handed the rasteriser a polyline of 825.000 points and
        // took 2.084 ms a repaint, measured. It also threw away two arrays of
        // that length on every frame, 6,6 MB of garbage each.
        //
        // A column gets TWO points, its lowest close and its highest, in the
        // order the market reached them. One point a column would be faster
        // still and would quietly erase every spike narrower than a pixel --
        // and a spike is what a reader zooms out to find. Two points reach the
        // same vertical extent the crowded line reached.
        //
        // Both arrays are now bounded by the plot's WIDTH rather than by the
        // series: a step of one only happens when the bars are at least a pixel
        // wide, and then there are at most `width` of them on screen.
        int step = viewport.barsPerColumn();
        int columns = (to - from + step - 1) / step;

        int[] xs = new int[step == 1 ? columns : columns * 2];
        int[] ys = new int[xs.length];
        int points = 0;

        for (int i = from; i < to; i += step) {
            int stop = Math.min(i + step, to);
            int x = (int) Math.round((viewport.x(i) + viewport.x(stop - 1)) / 2.0);

            if (step == 1) {
                xs[points] = x;
                ys[points] = (int) Math.round(viewport.y(series.closeAt(i)));

                points++;

                continue;
            }

            double lowest = series.closeAt(i);
            double highest = lowest;
            int lowAt = i;
            int highAt = i;

            for (int k = i + 1; k < stop; k++) {
                double close = series.closeAt(k);

                if (close < lowest) {
                    lowest = close;
                    lowAt = k;
                }

                if (close > highest) {
                    highest = close;
                    highAt = k;
                }
            }

            boolean fellFirst = lowAt <= highAt;

            xs[points] = x;
            ys[points] = (int) Math.round(viewport.y(fellFirst ? lowest : highest));

            points++;

            xs[points] = x;
            ys[points] = (int) Math.round(viewport.y(fellFirst ? highest : lowest));

            points++;
        }

        // Antialiasing only here. On candles it blurs the one-pixel body of a
        // doji into grey; on a diagonal line its absence produces visible
        // stair-stepping.
        Object previous = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(ChartColors.foreground());
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawPolyline(xs, ys, points);

        // PUT BACK EVEN WHEN THERE WAS NOTHING THERE. A null hint means the
        // caller had never set one, and leaving antialiasing ON in that case
        // hands the next painter a setting it did not ask for -- the candles
        // draw straight after this on a shared Graphics, and antialiasing blurs
        // the one-pixel body of a doji into grey. Which is the very reason the
        // comment above says it is turned on "only here".
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                previous == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : previous);
    }
}
