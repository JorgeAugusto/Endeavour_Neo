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

import java.awt.Rectangle;

/**
 * The mapping between bars and prices on one side and pixels on the other.
 *
 * <p>Everything a chart draws goes through here, and that is deliberate: the
 * conversion is where chart bugs live. A candle one pixel off, a line that does
 * not touch its own high, a crosshair reporting the neighbouring bar — all the
 * same defect, arithmetic scattered across a dozen paint methods, each doing it
 * slightly differently.</p>
 *
 * <p><b>The price scale comes from the VISIBLE bars, never the whole series.</b>
 * Scaling to the full range would make any zoom into a quiet stretch draw a
 * flat line across the middle of the screen — the movement is still there, it
 * just got squashed by a spike from three years ago.</p>
 *
 * <p>Immutable: panning or zooming produces a new viewport. That removes a whole
 * class of bug where half a paint pass used the old scale and half the new one,
 * and it makes the arithmetic testable without a window.</p>
 */
public final class Viewport {

    /** Share of the height left empty above and below, so nothing touches the edge. */
    private static final double PADDING = 0.06;

    private final Rectangle bounds;

    private final int firstBar;

    private final int barCount;

    private final double lowestPrice;

    private final double highestPrice;

    private Viewport(Rectangle bounds, int firstBar, int barCount,
                     double lowestPrice, double highestPrice) {
        this.bounds = new Rectangle(bounds);
        this.firstBar = firstBar;
        this.barCount = barCount;
        this.lowestPrice = lowestPrice;
        this.highestPrice = highestPrice;
    }

    /**
     * @param series the data, not null
     * @param bounds the drawing area in pixels
     * @param firstBar index of the leftmost visible bar
     * @param barCount how many bars fit; at least one
     * @return a viewport scaled to those bars
     */
    public static Viewport of(PriceSeries series, Rectangle bounds, int firstBar, int barCount) {
        int first = Math.max(0, Math.min(firstBar, Math.max(0, series.size() - 1)));
        int count = Math.max(1, Math.min(barCount, series.size() - first));

        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;

        for (int i = first; i < first + count && i < series.size(); i++) {
            low = Math.min(low, series.lowAt(i));
            high = Math.max(high, series.highAt(i));
        }

        if (!(high > low)) {
            // A flat stretch, or no bars at all. Inventing a range keeps every
            // division below safe; a zero span would put everything on one line
            // or divide by zero, depending on the order of operations.
            double centre = Double.isFinite(low) ? low : 0.0;

            low = centre - 1.0;
            high = centre + 1.0;
        }

        double margin = (high - low) * PADDING;

        return new Viewport(bounds, first, count, low - margin, high + margin);
    }

    /** @return the leftmost visible bar */
    public int firstBar() {
        return firstBar;
    }

    /** @return how many bars are visible */
    public int barCount() {
        return barCount;
    }

    /** @return one past the last visible bar */
    public int lastBar() {
        return firstBar + barCount;
    }

    public Rectangle bounds() {
        return new Rectangle(bounds);
    }

    public double lowestPrice() {
        return lowestPrice;
    }

    public double highestPrice() {
        return highestPrice;
    }

    /** @return the horizontal space one bar owns, in pixels; may be under 1 */
    public double barWidth() {
        return (double) bounds.width / barCount;
    }

    /**
     * @param index a bar index
     * @return the x of the bar's CENTRE
     *
     * <p>The centre, not the left edge: a candle is drawn symmetrically around
     * it and a line joins centres. Returning the edge would make every style
     * add half a bar itself, and one of them would forget.</p>
     */
    public double x(int index) {
        return bounds.x + (index - firstBar + 0.5) * barWidth();
    }

    /**
     * @param x a horizontal pixel
     * @return the bar under it, clamped to the visible range
     *
     * <p>Clamped rather than throwing: this answers "what is under the mouse",
     * and the mouse goes past the edge all the time.</p>
     */
    public int barAt(double x) {
        int index = firstBar + (int) Math.floor((x - bounds.x) / barWidth());

        return Math.max(firstBar, Math.min(index, lastBar() - 1));
    }

    /**
     * @param price a price
     * @return the vertical pixel for it
     *
     * <p>Higher price, smaller y — the screen counts downwards and the chart
     * does not.</p>
     */
    public double y(double price) {
        double span = highestPrice - lowestPrice;

        return bounds.y + bounds.height * (1.0 - (price - lowestPrice) / span);
    }

    /**
     * @param y a vertical pixel
     * @return the price there
     */
    public double priceAt(double y) {
        double span = highestPrice - lowestPrice;

        return lowestPrice + span * (1.0 - (y - bounds.y) / bounds.height);
    }
}
