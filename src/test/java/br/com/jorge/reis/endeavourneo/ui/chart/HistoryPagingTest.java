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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reaching the history the window left behind.
 *
 * <p>A chart loads the most recent bars and not the file — six years of minutes
 * is 39 MB to draw a screen showing a month. What is left behind has to arrive
 * before the reader can tell it is missing, and there are two ways of getting
 * that wrong: asking too late, and moving the chart while answering.</p>
 *
 * <p><b>The warm-up is this same mechanism.</b> An average of two hundred draws
 * nothing for its first two hundred bars, and at the loaded start those bars are
 * in the file, simply unread. Fetching before the reader arrives pushes that gap
 * off the screen; it is not a second feature.</p>
 */
@DisplayName("Paging history in")
class HistoryPagingTest {

    /** Bars every minute, closing at {@code first}, {@code first + 1} ... */
    private static PriceSeries bars(int count, int first) {
        return new PriceSeries() {

            @Override
            public int size() {
                return count;
            }

            @Override
            public long timeAt(int index) {
                return (first + index) * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return first + index;
            }

            @Override
            public double highAt(int index) {
                return first + index;
            }

            @Override
            public double lowAt(int index) {
                return first + index;
            }

            @Override
            public double closeAt(int index) {
                return first + index;
            }
        };
    }

    private static ChartCanvas showing(PriceSeries series) {
        ChartCanvas canvas = new ChartCanvas();

        canvas.setSeries(series);
        canvas.setSize(900, 500);

        return canvas;
    }

    @Test
    @DisplayName("nothing is asked for while the reader is at the recent end")
    void theEndAsksForNothing() {
        // Where a chart opens. Asking here would read the file on every open,
        // which is the cost the window exists to avoid.
        AtomicInteger asked = new AtomicInteger();
        ChartCanvas canvas = showing(bars(50_000, 100_000));

        canvas.setHistoryBehind(100_000, asked::incrementAndGet);
        canvas.goToEnd();

        assertEquals(0, asked.get(), "history was fetched without the reader going near it");
    }

    @Test
    @DisplayName("nearing the loaded start asks for more, once and not once per move")
    void nearingTheStartAsksOnce() {
        AtomicInteger asked = new AtomicInteger();
        ChartCanvas canvas = showing(bars(50_000, 100_000));

        canvas.setHistoryBehind(100_000, asked::incrementAndGet);

        // All the way to the left, the long way: every step goes through the
        // same clamp, which is where the asking lives.
        for (int at = 45_000; at >= 0; at -= 1_000) {
            canvas.scrollTo(at);
        }

        assertEquals(1, asked.get(),
                "the chart asked " + asked.get() + " times while being scrolled to the "
                        + "start; a reader dragging would have queued that many reads");
    }

    @Test
    @DisplayName("with nothing behind it, the start is just the start")
    void noHistoryMeansNoAsking() {
        // The true beginning of the file. An average with nothing to average is
        // honestly blank there, and asking would be asking for bars that never
        // existed.
        AtomicInteger asked = new AtomicInteger();
        ChartCanvas canvas = showing(bars(50_000, 0));

        canvas.setHistoryBehind(0, asked::incrementAndGet);

        for (int at = 45_000; at >= 0; at -= 1_000) {
            canvas.scrollTo(at);
        }

        assertEquals(0, asked.get(), "the chart asked for history that is not there");
    }

    @Test
    @DisplayName("older bars arrive without moving what the reader is looking at")
    void growingDoesNotMoveTheView() {
        // The other way to get this wrong. The bars go in at the FRONT, so every
        // index shifts by however many arrived; leaving firstBar alone would jump
        // the reader a hundred thousand bars forward at the moment they scrolled
        // back, which reads as the chart fighting them.
        ChartCanvas canvas = showing(bars(50_000, 100_000));

        canvas.setHistoryBehind(100_000, () -> { });
        canvas.scrollTo(500);

        double leftEdge = canvas.series().closeAt(canvas.firstVisibleBar());

        canvas.growHistory(bars(150_000, 0), 100_000);

        assertEquals(leftEdge,
                canvas.series().closeAt(canvas.firstVisibleBar()), 1e-9,
                "the bar on the left of the screen changed when older bars arrived");
        assertEquals(0, canvas.historyBehind(),
                "the chart still thinks there is history it already has");
    }

    @Test
    @DisplayName("after growing, coming near the new start asks again")
    void askingResumesAfterGrowing() {
        // The guard against asking twice must not become a guard against ever
        // asking again: history arrives one block at a time, and the reader can
        // walk back through several.
        AtomicInteger asked = new AtomicInteger();
        ChartCanvas canvas = showing(bars(50_000, 100_000));

        canvas.setHistoryBehind(100_000, asked::incrementAndGet);
        canvas.scrollTo(500);

        assertEquals(1, asked.get());

        canvas.growHistory(bars(100_000, 50_000), 50_000);
        canvas.setHistoryBehind(50_000, asked::incrementAndGet);

        for (int at = 45_000; at >= 0; at -= 1_000) {
            canvas.scrollTo(at);
        }

        assertTrue(asked.get() > 1, "the chart asked once and then never again");
    }

    @Test
    @DisplayName("what arrives is the same market, continued")
    void theOlderBarsJoinOnWithoutAGap() {
        // Growing replaces the series, and a replacement that does not line up
        // would put a step in the chart at the join -- visible as a gap the
        // market never made.
        ChartCanvas canvas = showing(bars(50_000, 100_000));

        canvas.setHistoryBehind(100_000, () -> { });
        canvas.growHistory(bars(150_000, 0), 100_000);

        PriceSeries shown = canvas.series();

        assertEquals(150_000, shown.size());

        for (int bar = 1; bar < shown.size(); bar += 7_919) {
            assertTrue(shown.timeAt(bar) > shown.timeAt(bar - 1),
                    "bar " + bar + " is stamped before the one in front of it");
        }

        assertFalse(Double.isNaN(shown.closeAt(0)), "the joined series begins with nothing");
    }
}
