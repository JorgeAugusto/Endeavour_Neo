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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which bar the price legend reads.
 *
 * <p>The class had six hundred lines and no test at all, and that is how it came
 * to print a dash where every value should be. It asked the canvas for the bar
 * under the pointer and used the answer as it came — and that answer is −1
 * whenever the pointer is not over the canvas. The legend sits ABOVE the canvas,
 * so the pointer crosses it going in and coming out: for a good part of the time
 * the reader spends looking at the chart, every row read nothing.</p>
 *
 * <p>The fall-back it needed already existed, used by the study panes below and
 * documented for exactly this: {@code lastVisibleBar}, "what a legend reads when
 * the mouse is away".</p>
 */
@DisplayName("Overlay legend")
class OverlayLegendTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** One-minute bars from a fixed morning, closing at 1, 2, 3 ... */
    private static PriceSeries minutes(int count) {
        long start = LocalDateTime.of(2026, 9, 2, 9, 0).atZone(ZONE).toInstant().toEpochMilli();

        return new PriceSeries() {

            @Override
            public int size() {
                return count;
            }

            @Override
            public long timeAt(int index) {
                return start + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return index + 1;
            }

            @Override
            public double highAt(int index) {
                return index + 1;
            }

            @Override
            public double lowAt(int index) {
                return index + 1;
            }

            @Override
            public double closeAt(int index) {
                return index + 1;
            }
        };
    }

    private static ChartCanvas showing(int bars) {
        ChartCanvas canvas = new ChartCanvas();

        canvas.setSeries(minutes(bars));
        canvas.setSize(900, 500);

        return canvas;
    }

    @Test
    @DisplayName("with the pointer away, the legend reads the last bar on screen")
    void awayFromTheChartItReadsTheLastBar() {
        // The defect, stated as the test that was missing. No mouse has touched
        // this canvas, so hoveredBar() is -1; a legend that passes that straight
        // through prints a dash on every row.
        ChartCanvas canvas = showing(120);
        OverlayLegend legend = new OverlayLegend(canvas, "test.legend");

        assertEquals(-1, canvas.hoveredBar(),
                "the fixture is wrong: something moved the pointer onto the canvas");
        assertTrue(legend.readAt() >= 0,
                "the legend read bar " + legend.readAt() + ", which is not a bar");
        assertEquals(canvas.lastVisibleBar(), legend.readAt(),
                "away from the chart the legend has to read the last bar on screen");
    }

    @Test
    @DisplayName("the bar it reads is always one the series actually has")
    void itNeverReadsPastTheSeries() {
        // A property rather than one number, and the one that matters to a caller:
        // whatever the series and whatever the pointer is doing, valueAt(readAt())
        // must be a question the series can answer.
        for (int bars : new int[]{1, 2, 37, 500, 100_000}) {
            ChartCanvas canvas = showing(bars);
            OverlayLegend legend = new OverlayLegend(canvas, "test.legend");

            int bar = legend.readAt();

            assertTrue(bar >= 0 && bar < bars,
                    "with " + bars + " bars the legend read bar " + bar);
        }
    }
}
