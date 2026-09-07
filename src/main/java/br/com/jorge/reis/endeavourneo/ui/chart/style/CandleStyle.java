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
import br.com.jorge.reis.endeavourneo.domain.market.Untraded;

import br.com.jorge.reis.endeavourneo.ui.chart.ChartColors;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartStyle;
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

    /**
     * @return whether rising bodies are drawn hollow
     *
     * <p>Hollow is the older convention and the one Profit uses. It reads
     * differently rather than merely looking different: with hollow bodies the
     * <b>ink</b> on the screen marks the falling bars, so a downward stretch is
     * visibly darker. Filled-both-ways relies on colour alone, which is exactly
     * what a colour-blind reader does not have.</p>
     *
     * <p>Read at paint time rather than fixed at construction, so the setting
     * reaches charts already open. Building the style with a flag would leave
     * every window drawn the way it was when it opened, and the checkbox would
     * only take effect on the next chart.</p>
     */
    private boolean hollow() {
        return br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.hollowCandles();
    }

    public CandleStyle() {
        // Nothing to keep: whether bodies are hollow is a setting, read when the
        // candle is drawn.
    }

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

        // ONE COLUMN AT A TIME when the bars are thinner than a pixel. Below one
        // pixel per bar every bar in a column lands on the same x, so their
        // wicks already drew as a single line from the column's lowest low to
        // its highest high -- the picture is the same, reached with one call
        // instead of hundreds. Measured on 825.000 bars: 1.049 ms a repaint.
        //
        // Above one pixel the step is 1 and every line below runs exactly as it
        // did, which is what keeps this from being a second way of drawing.
        int step = viewport.barsPerColumn();
        int end = Math.min(viewport.lastBar(), series.size());

        for (int i = viewport.firstBar(); i < end; i += step) {
            int stop = Math.min(i + step, end);

            double open = series.openAt(i);
            double close = series.closeAt(stop - 1);
            double highest = series.highAt(i);
            double lowest = series.lowAt(i);

            // A brick laid over a gap: nobody traded anywhere inside it, so it
            // gets neither colour. Only renko ever answers yes -- see Untraded
            // -- and the point of the grey is that the FIRST brick that was
            // really traded can be picked out of a run of them.
            //
            // A COLUMN is a gap only when every bar in it is one. Grey for a
            // column holding a single traded bar would say the market stood
            // still where it did not.
            boolean gap = Untraded.at(series, i);

            for (int k = i + 1; k < stop; k++) {
                highest = Math.max(highest, series.highAt(k));
                lowest = Math.min(lowest, series.lowAt(k));
                gap &= Untraded.at(series, k);
            }

            boolean rising = close >= open;

            g.setColor(gap ? ChartColors.untraded()
                    : rising ? ChartColors.up() : ChartColors.down());

            // The centre of the COLUMN, which for a step of one is the centre of
            // the bar and the same number as before.
            double centre = (viewport.x(i) + viewport.x(stop - 1)) / 2.0;
            int x = (int) Math.round(centre);

            // The wick is drawn first and full length, so the body covers its
            // middle. Drawing it after would leave a line across every body.
            g.drawLine(x, (int) Math.round(viewport.y(highest)),
                    x, (int) Math.round(viewport.y(lowest)));

            if (!bodies) {
                // Zoomed far out the bodies would be thinner than a pixel and
                // would just thicken the wick into a smear. Wicks alone still
                // show the range, which is what is legible at this scale.
                continue;
            }

            double top = viewport.y(Math.max(open, close));
            double bottom = viewport.y(Math.min(open, close));

            int left = (int) Math.round(centre - bodyWidth / 2.0);
            int first = (int) Math.round(top);

            // Both rows INCLUSIVE, which is where the +1 comes from. A line is
            // drawn with both of its ends and a rectangle stops one row short of
            // its own bottom, so rounding the HEIGHT left the wick's last row
            // uncovered. On a renko brick, whose low is its own close, that was
            // a thread of one pixel hanging below every brick -- below the
            // falling ones too, which by the rules have no tail down there.
            //
            // A doji -- open equal to close -- comes out of this as a single
            // row, which is the honest minimum: the bar exists. The max only
            // guards the case where the two roundings disagree.
            int drawHeight = Math.max(1, (int) Math.round(bottom) - first + 1);

            if (hollow() && rising && !gap && drawHeight > 2) {
                // Solid when it is a gap, whichever way it points. Hollow says
                // "this one rose"; a gap brick did not rise, and outlining it
                // would also make it the faintest thing on screen exactly where
                // the reader is counting bricks.
                // Outlined, with the background showing through. Below three
                // pixels the outline and the fill are the same thing, so a tiny
                // body stays solid rather than becoming an invisible ring.
                g.setColor(ChartColors.background());
                g.fillRect(left, first, (int) bodyWidth, drawHeight);

                g.setColor(ChartColors.up());
                g.drawRect(left, first, (int) bodyWidth - 1, drawHeight - 1);
            } else {
                g.fillRect(left, first, (int) bodyWidth, drawHeight);
            }
        }
    }
}
