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
package br.com.jorge.reis.endeavourneo.domain.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sessions before the one being played, and how they join it.
 */
@DisplayName("History before a replay")
class HistoryBeforeReplayTest {

    /** {@code count} bars whose close is {@code base + i}. */
    private static PriceSeries bars(int count, double base, long firstMillis) {
        return new PriceSeries() {

            @Override
            public int size() {
                return count;
            }

            @Override
            public long timeAt(int index) {
                return firstMillis + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return base + index;
            }

            @Override
            public double highAt(int index) {
                return base + index;
            }

            @Override
            public double lowAt(int index) {
                return base + index;
            }

            @Override
            public double closeAt(int index) {
                return base + index;
            }
        };
    }

    // ------------------------------------------------------------ joining up

    @Test
    @DisplayName("joined sessions read as one series, in order")
    void joinsInOrder() {
        PriceSeries all = ConcatSeries.of(List.of(
                bars(3, 100, 0L), bars(4, 200, 1_000_000L), bars(2, 300, 2_000_000L)));

        assertEquals(9, all.size());
        assertEquals(100.0, all.closeAt(0));
        assertEquals(200.0, all.closeAt(3), "the second session must start at index 3");
        assertEquals(300.0, all.closeAt(7));
        assertEquals(301.0, all.closeAt(8));
        assertThrows(IndexOutOfBoundsException.class, () -> all.closeAt(9));
    }

    @Test
    @DisplayName("an empty session is dropped, not kept as a boundary nothing lands on")
    void emptyPartsAreDropped() {
        // A market holiday. Kept, it would put a boundary in the offsets that no
        // bar ever falls on.
        PriceSeries all = ConcatSeries.of(List.of(
                bars(2, 100, 0L), PriceSeries.empty(), bars(2, 200, 1_000_000L)));

        assertEquals(4, all.size());
        assertEquals(200.0, all.closeAt(2));
    }

    @Test
    @DisplayName("one session on its own is handed straight back, unwrapped")
    void oneSessionIsItself() {
        PriceSeries only = bars(3, 100, 0L);

        assertSame(only, ConcatSeries.of(List.of(only)));
        assertEquals(0, ConcatSeries.of(List.of()).size());
    }

    // ----------------------------------------------------- history and replay

    @Test
    @DisplayName("history is visible from the start and cannot be hidden")
    void historyIsAlwaysThere() {
        PriceSeries all = ConcatSeries.of(List.of(bars(5, 100, 0L), bars(5, 200, 1_000_000L)));
        ReplaySeries replay = new ReplaySeries(all, 5, 5, null);

        assertEquals(5, replay.size(), "the chart must open with the history drawn");
        assertEquals(104.0, replay.closeAt(4));

        // Rewinding goes back to the session's open, not to an empty screen.
        replay.seek(0);
        assertEquals(5, replay.size());
        replay.seekFraction(0.0);
        assertEquals(5, replay.size());
    }

    @Test
    @DisplayName("the scrubber measures the session, not the history")
    void progressIgnoresHistory() {
        // With nine tenths of the screen being history, a scrubber measured over
        // everything would squeeze the whole replay into the last centimetre.
        PriceSeries all = ConcatSeries.of(List.of(bars(90, 100, 0L), bars(10, 200, 9_000_000L)));
        ReplaySeries replay = new ReplaySeries(all, 90, 90, null);

        assertEquals(0.0, replay.progress());

        replay.seekFraction(0.5);
        assertEquals(95, replay.size());
        assertEquals(0.5, replay.progress());

        replay.seekFraction(1.0);
        assertEquals(1.0, replay.progress());
        assertTrue(replay.finished());
    }

    @Test
    @DisplayName("the session's future is still out of bounds, history or not")
    void theFutureStaysShut() {
        PriceSeries all = ConcatSeries.of(List.of(bars(5, 100, 0L), bars(5, 200, 1_000_000L)));
        ReplaySeries replay = new ReplaySeries(all, 5, 5, null);

        assertThrows(IndexOutOfBoundsException.class, () -> replay.closeAt(5));
    }
}
