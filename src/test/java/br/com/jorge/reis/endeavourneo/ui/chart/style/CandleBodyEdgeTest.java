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

import br.com.jorge.reis.endeavourneo.ui.chart.ChartColors;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a candle's body ends, counted in pixels on a painted image.
 *
 * <p>Seen on a renko chart: a thread of one pixel hanging below every brick,
 * including the falling ones, whose tail by the rules can only be on top. It was
 * the wick. A line is drawn with both ends included and a rectangle stops one
 * short of its own bottom, so on a bar whose low IS its close the last row of
 * the wick had nothing over it.</p>
 *
 * <p>These read the painted pixels rather than the arithmetic, because the
 * defect lived in the gap between the two.</p>
 */
@DisplayName("Candle body edges")
class CandleBodyEdgeTest {

    private static final int WIDTH = 90;

    private static final int HEIGHT = 120;

    /** Three bar-slots across the image, so the middle one is the subject. */
    private static final int BARS = 3;

    private final boolean hollowBefore = ChartPreferences.hollowCandles();

    @AfterEach
    void restorePreference() {
        ChartPreferences.setHollowCandles(hollowBefore);
    }

    /** Bars as {open, high, low, close}. */
    private static PriceSeries series(double[]... bars) {
        return new PriceSeries() {

            @Override
            public int size() {
                return bars.length;
            }

            @Override
            public long timeAt(int index) {
                return 1_756_000_000_000L + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return bars[index][0];
            }

            @Override
            public double highAt(int index) {
                return bars[index][1];
            }

            @Override
            public double lowAt(int index) {
                return bars[index][2];
            }

            @Override
            public double closeAt(int index) {
                return bars[index][3];
            }
        };
    }

    /** @return the image with the whole series painted on a background of its own */
    private static BufferedImage paint(PriceSeries series) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        g.setColor(ChartColors.background());
        g.fillRect(0, 0, WIDTH, HEIGHT);

        Rectangle bounds = new Rectangle(0, 0, WIDTH, HEIGHT);

        new CandleStyle().paint(g, series, Viewport.of(series, bounds, 0, BARS));
        g.dispose();

        return image;
    }

    /** @return how many pixels of the bar's own colour sit on that row, in bar 1's slot */
    private static int inkOn(BufferedImage image, int row, int colour) {
        int slot = WIDTH / BARS;
        int count = 0;

        for (int x = slot; x < 2 * slot; x++) {
            if (image.getRGB(x, row) == colour) {
                count++;
            }
        }

        return count;
    }

    private static int firstInkedRow(BufferedImage image, int colour) {
        for (int y = 0; y < HEIGHT; y++) {
            if (inkOn(image, y, colour) > 0) {
                return y;
            }
        }

        return -1;
    }

    private static int lastInkedRow(BufferedImage image, int colour) {
        for (int y = HEIGHT - 1; y >= 0; y--) {
            if (inkOn(image, y, colour) > 0) {
                return y;
            }
        }

        return -1;
    }

    /** A tall first bar, so the price scale is wider than the bar under test. */
    private static double[] scaleSetter() {
        return new double[]{60, 140, 60, 140};
    }

    private static double[] filler() {
        return new double[]{100, 100, 100, 100};
    }

    @Test
    @DisplayName("a brick with no tail is a rectangle: its bottom edge is as wide as its top")
    void noThreadBelowATaillessBody() {
        // A renko brick: high IS the close, low IS the open. Nothing hangs
        // below it, so the bottom row has to be the body's own edge -- the full
        // width -- and not the single pixel of an uncovered wick.
        for (boolean hollow : new boolean[]{false, true}) {
            ChartPreferences.setHollowCandles(hollow);

            int colour = ChartColors.up().getRGB();
            BufferedImage image = paint(series(
                    scaleSetter(),
                    new double[]{100, 110, 100, 110},
                    filler()));

            int top = firstInkedRow(image, colour);
            int bottom = lastInkedRow(image, colour);

            assertTrue(top >= 0 && bottom > top, "the candle was not drawn at all");
            assertEquals(inkOn(image, top, colour), inkOn(image, bottom, colour),
                    "hollow=" + hollow + ": the bottom row is " + inkOn(image, bottom, colour)
                            + " pixels wide against " + inkOn(image, top, colour)
                            + " on top, so a thread of wick hangs below the body");
        }
    }

    @Test
    @DisplayName("a real tail is still drawn, and still one pixel wide")
    void aRealTailSurvives() {
        // The other half of the fix: covering the wick's last row must not cost
        // the tails that mean something. This bar was fought ten points down.
        ChartPreferences.setHollowCandles(false);

        int colour = ChartColors.up().getRGB();
        BufferedImage image = paint(series(
                scaleSetter(),
                new double[]{100, 110, 90, 110},
                filler()));

        int bottom = lastInkedRow(image, colour);
        int top = firstInkedRow(image, colour);

        assertEquals(1, inkOn(image, bottom, colour),
                "the tail lost its own width and became part of the body");
        assertTrue(bottom - top > 10, "the tail was not drawn at all");
    }

    @Test
    @DisplayName("a doji still shows: one row, the full width of a body")
    void theDojiSurvives() {
        // Asked while the fix was being written, and worth pinning: on a minute
        // chart a bar whose open equals its close is common, and it has no
        // height at all. It is drawn as a single row, and it must stay a body --
        // the full width -- not thin out into a wick.
        ChartPreferences.setHollowCandles(true);

        int colour = ChartColors.up().getRGB();
        BufferedImage image = paint(series(
                scaleSetter(),
                new double[]{100, 100, 100, 100},
                new double[]{60, 60, 60, 60}));

        int top = firstInkedRow(image, colour);
        int bottom = lastInkedRow(image, colour);

        assertTrue(top >= 0, "the doji vanished");
        assertEquals(top, bottom, "the doji grew a height it does not have");
        assertTrue(inkOn(image, top, colour) > 1,
                "the doji thinned to " + inkOn(image, top, colour) + " pixel");
    }
}
