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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The price-to-pixel arithmetic, which is where chart defects live and the one
 * part of a chart that can be checked without a screen.
 */
@DisplayName("Viewport")
class ViewportTest {

    private static final Rectangle AREA = new Rectangle(0, 0, 400, 200);

    @Test
    @DisplayName("the price scale comes from the VISIBLE bars, not the whole series")
    void scaleUsesVisibleBarsOnly() {
        // The bug this prevents: zoom into a quiet stretch and the chart draws a
        // flat line, because a spike from years ago is still setting the scale.
        // The movement is there; it got squashed.
        PriceSeries series = new Fake(new double[]{100, 101, 100, 101, 5_000});

        Viewport zoomed = Viewport.of(series, AREA, 0, 4);

        assertTrue(zoomed.highestPrice() < 200.0,
                "the spike outside the visible range is still setting the scale: max is "
                        + zoomed.highestPrice());

        Viewport whole = Viewport.of(series, AREA, 0, 5);

        assertTrue(whole.highestPrice() > 5_000.0,
                "with the spike visible it must be inside the range");
    }

    @Test
    @DisplayName("x is the bar's CENTRE, and reading it back gives the same bar")
    void barAndPixelRoundTrip() {
        PriceSeries series = new Fake(new double[]{10, 11, 12, 13, 14, 15, 16, 17});
        Viewport viewport = Viewport.of(series, AREA, 2, 4);

        for (int bar = 2; bar < 6; bar++) {
            assertEquals(bar, viewport.barAt(viewport.x(bar)),
                    "the centre of bar " + bar + " maps back to a different bar");
        }

        assertTrue(viewport.x(2) > AREA.x, "the first visible bar is glued to the left edge");
        assertTrue(viewport.x(5) < AREA.x + AREA.width,
                "the last visible bar is glued to the right edge");
    }

    @Test
    @DisplayName("price and pixel round-trip, and higher price means smaller y")
    void priceAndPixelRoundTrip() {
        PriceSeries series = new Fake(new double[]{100, 110, 90, 105});
        Viewport viewport = Viewport.of(series, AREA, 0, 4);

        for (double price : new double[]{95.0, 100.0, 105.0}) {
            assertEquals(price, viewport.priceAt(viewport.y(price)), 1e-6,
                    "converting the price to a pixel and back changed it");
        }

        assertTrue(viewport.y(110.0) < viewport.y(90.0),
                "the screen counts downwards: a higher price must have a SMALLER y");
    }

    @Test
    @DisplayName("a flat stretch does not divide by zero")
    void flatSeriesIsSafe() {
        // Every bar identical: the span is zero and every division below would
        // produce infinity or NaN, which paints as nothing at all.
        PriceSeries series = new Fake(new double[]{100, 100, 100});
        Viewport viewport = Viewport.of(series, AREA, 0, 3);

        assertTrue(viewport.highestPrice() > viewport.lowestPrice(),
                "a flat series produced a zero span");
        assertTrue(Double.isFinite(viewport.y(100.0)), "y came out non-finite: " + viewport.y(100.0));
    }

    @Test
    @DisplayName("asking for more bars than exist is clamped instead of exploding")
    void clampsOutOfRange() {
        PriceSeries series = new Fake(new double[]{1, 2, 3});
        Viewport viewport = Viewport.of(series, AREA, 0, 999);

        assertEquals(3, viewport.barCount(), "it claimed more visible bars than the series has");
        assertEquals(0, viewport.barAt(-50.0), "a pixel left of the chart must clamp to the first bar");
        assertEquals(2, viewport.barAt(9_999.0), "a pixel right of the chart must clamp to the last");
    }

    /** A series built from closing prices, with a small range around each. */
    private record Fake(double[] closes) implements PriceSeries {

        @Override
        public int size() {
            return closes.length;
        }

        @Override
        public long timeAt(int index) {
            return index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return closes[index];
        }

        @Override
        public double highAt(int index) {
            return closes[index] + 1.0;
        }

        @Override
        public double lowAt(int index) {
            return closes[index] - 1.0;
        }

        @Override
        public double closeAt(int index) {
            return closes[index];
        }
    }
}
