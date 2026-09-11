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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartStyle;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drawing a series that is far wider than the screen.
 *
 * <p>A whole series zoomed out puts hundreds of bars in every column of pixels.
 * Drawing each one separately paints the same column over and over: measured on
 * 825.000 bars at 900 pixels wide, one candle repaint cost <b>1.049 ms</b> and
 * one line repaint <b>2.084 ms</b>, against 6 ms for the price scan. The cost was
 * never the reading — it was the drawing.</p>
 *
 * <p>So the styles step by column. The danger in doing that is losing what the
 * column contained, and these tests are about the picture staying the same: a
 * spike one bar wide is exactly what somebody zooms out to find, and taking one
 * close per column would erase it while making the chart look faster.</p>
 */
@DisplayName("Drawing wider than the screen")
class DecimationTest {

    private static final Rectangle PLOT = new Rectangle(0, 0, 300, 200);

    /**
     * A flat series with one spike up and one spike down.
     *
     * <p>Flat everywhere else so the two spikes ARE the extremes: anything that
     * loses them changes the top or the bottom of the painted picture, which is
     * a thing a test can see.</p>
     */
    private static PriceSeries withSpikes(int count, int upAt, int downAt) {
        return new PriceSeries() {

            @Override
            public int size() {
                return count;
            }

            @Override
            public long timeAt(int index) {
                return 1_756_000_000_000L + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return 100.0;
            }

            @Override
            public double highAt(int index) {
                return index == upAt ? 180.0 : 101.0;
            }

            @Override
            public double lowAt(int index) {
                return index == downAt ? 20.0 : 99.0;
            }

            @Override
            public double closeAt(int index) {
                return index == upAt ? 180.0 : index == downAt ? 20.0 : 100.0;
            }
        };
    }

    /** @return the topmost and bottommost rows that got any ink, or null */
    private static int[] inkedRows(BufferedImage image) {
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);

                    break;
                }
            }
        }

        return top > bottom ? null : new int[]{top, bottom};
    }

    private static BufferedImage painted(ChartStyle style, PriceSeries series, Viewport viewport) {
        BufferedImage image = new BufferedImage(PLOT.width, PLOT.height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        style.paint(g, series, viewport, br.com.jorge.reis.endeavourneo.ui.chart.BarTint.NONE);
        g.dispose();

        return image;
    }

    @Test
    @DisplayName("a spike one bar wide survives being zoomed out to the whole series")
    void spikesSurviveTheZoomOut() {
        // Fifty thousand bars in three hundred pixels: one hundred and sixty-six
        // bars a column. A style that took one close per column would drop both
        // spikes with a chance of 165 in 166, and the chart would look calm and
        // be wrong.
        int bars = 50_000;
        PriceSeries series = withSpikes(bars, 20_001, 31_007);
        Viewport viewport = Viewport.of(series, PLOT, 0, bars);

        assertTrue(viewport.barsPerColumn() > 1,
                "the fixture is wrong: at this zoom the bars still fit one per column");

        for (ChartStyle style : new ChartStyle[]{new CandleStyle(), new LineStyle()}) {
            int[] rows = inkedRows(painted(style, series, viewport));

            assertTrue(rows != null, style.nameKey() + " drew nothing at all");

            // The spikes ARE the extremes of the viewport, so the ink has to
            // reach the rows they map to. One pixel of slack for the rounding
            // every style does on its way to an integer row.
            assertTrue(Math.abs(rows[0] - Math.round(viewport.y(180.0))) <= 1,
                    style.nameKey() + " lost the spike UP: the highest ink is at row "
                            + rows[0] + ", the spike belongs at "
                            + Math.round(viewport.y(180.0)));
            assertTrue(Math.abs(rows[1] - Math.round(viewport.y(20.0))) <= 1,
                    style.nameKey() + " lost the spike DOWN: the lowest ink is at row "
                            + rows[1] + ", the spike belongs at "
                            + Math.round(viewport.y(20.0)));
        }
    }

    @Test
    @DisplayName("with room for every bar, nothing is grouped and nothing changes")
    void oneBarPerColumnIsLeftAlone() {
        // The guarantee that this is one way of drawing and not two. At a step of
        // one, CandleStyle's column centre is (x(i) + x(i)) / 2, which IS x(i),
        // and LineStyle takes its old branch: the pixels are the ones the
        // per-bar code produced, not an approximation of them.
        PriceSeries series = withSpikes(120, 40, 80);

        assertEquals(1, Viewport.of(series, PLOT, 0, 120).barsPerColumn(),
                "120 bars in 300 pixels were grouped, and they have room not to be");
        assertEquals(1, Viewport.of(series, PLOT, 0, 300).barsPerColumn(),
                "exactly one pixel per bar is still one bar per column");
        assertEquals(2, Viewport.of(series, PLOT, 0, 600).barsPerColumn(),
                "600 bars in 300 pixels is two a column");
    }

    @Test
    @DisplayName("no ink lands outside the plot, however many bars are crowded in")
    void everythingStaysInsideThePlot() {
        // Column arithmetic is where an off-by-one puts a line in the axis strip
        // or one pixel past the right edge, and neither is visible until it is
        // beside something else.
        for (int bars : new int[]{301, 900, 5_000, 50_000}) {
            PriceSeries series = withSpikes(bars, bars / 3, bars / 2);
            Viewport viewport = Viewport.of(series, PLOT, 0, bars);

            for (ChartStyle style : new ChartStyle[]{new CandleStyle(), new LineStyle()}) {
                BufferedImage image = painted(style, series, viewport);
                int[] rows = inkedRows(image);

                assertTrue(rows != null, style.nameKey() + " drew nothing for " + bars + " bars");
                assertTrue(rows[0] >= 0 && rows[1] < PLOT.height,
                        style.nameKey() + " drew outside the plot with " + bars + " bars");
            }
        }
    }

    @Test
    @DisplayName("the step never reaches zero, and never asks for more bars than there are")
    void theStepIsAlwaysUsable() {
        // A step of zero is an endless loop inside a paint, which is the whole
        // application stopped. Worth an assertion of its own rather than trust
        // in the ceiling arithmetic.
        PriceSeries series = withSpikes(10, 3, 7);

        for (int count : new int[]{1, 2, 10, 299, 300, 301, 1_000, 1_000_000}) {
            int step = Viewport.of(series, PLOT, 0, count).barsPerColumn();

            assertTrue(step >= 1, "a step of " + step + " for " + count + " bars");
            assertTrue(step <= count, "step " + step + " asks for more than the " + count
                    + " bars on screen");
        }
    }
}
