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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A session played out one bar at a time.
 *
 * <p>Nearly all of this is about one property: <b>what has not arrived cannot be
 * read</b>. If it could, the replay would be a drawing convention rather than a
 * simulation, and the first accidental look at the future would be silent.</p>
 */
@DisplayName("Replay series")
class ReplaySeriesTest {

    /** Ten bars, closing at 0..9, one per minute. */
    private static PriceSeries day() {
        return new PriceSeries() {

            @Override
            public int size() {
                return 10;
            }

            @Override
            public long timeAt(int index) {
                return 1_756_000_000_000L + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return index;
            }

            @Override
            public double highAt(int index) {
                return index;
            }

            @Override
            public double lowAt(int index) {
                return index;
            }

            @Override
            public double closeAt(int index) {
                return index;
            }
        };
    }

    @Test
    @DisplayName("a bar that has not arrived cannot be read")
    void theFutureIsOutOfBounds() {
        // The whole point. "Not drawn" would leave every indicator, measurement
        // and strategy still able to read ahead, and nothing would say so.
        ReplaySeries replay = new ReplaySeries(day(), 3);

        assertEquals(3, replay.size());
        assertEquals(2.0, replay.closeAt(2));

        IndexOutOfBoundsException thrown =
                assertThrows(IndexOutOfBoundsException.class, () -> replay.closeAt(3));

        assertTrue(thrown.getMessage().contains("has not happened yet"), thrown.getMessage());
    }

    @Test
    @DisplayName("advancing reveals more, and says how many it managed")
    void advanceReports() {
        ReplaySeries replay = ReplaySeries.of(day());

        assertEquals(4, replay.advance(4));
        assertEquals(4, replay.size());

        // At the end it reveals fewer than asked, which is how the clock knows
        // to stop rather than leaving play lit on a session that has closed.
        assertEquals(6, replay.advance(100));
        assertEquals(0, replay.advance(1));
        assertTrue(replay.finished());
    }

    @Test
    @DisplayName("the scrubber can go backwards, and the future closes again")
    void seekingBackHidesItAgain() {
        ReplaySeries replay = new ReplaySeries(day(), 8);

        replay.seek(2);

        assertEquals(2, replay.size());
        assertFalse(replay.finished());
        assertThrows(IndexOutOfBoundsException.class, () -> replay.closeAt(2));
    }

    @Test
    @DisplayName("seeking by fraction lands on the ends exactly")
    void fractions() {
        ReplaySeries replay = ReplaySeries.of(day());

        replay.seekFraction(0.0);
        assertEquals(0, replay.size());

        replay.seekFraction(0.5);
        assertEquals(5, replay.size());

        replay.seekFraction(1.0);
        assertEquals(10, replay.size(), "dragging to the far end must reach the close");

        // Out of range and nonsense are clamped rather than thrown: a scrubber
        // dragged past its own end is a normal gesture.
        replay.seekFraction(2.0);
        assertEquals(10, replay.size());
        replay.seekFraction(Double.NaN);
        assertEquals(10, replay.size());
    }

    @Test
    @DisplayName("the clock reads the opening time before anything arrives")
    void clockBeforeTheOpen() {
        PriceSeries day = day();
        ReplaySeries replay = ReplaySeries.of(day);

        // Not zero, which would render as 1970 while the reader is still
        // deciding whether to press play.
        assertEquals(day.timeAt(0), replay.clock());

        replay.advance(3);

        assertEquals(day.timeAt(2), replay.clock(), "the clock is the last bar that arrived");
    }

    @Test
    @DisplayName("an empty day is finished before it starts")
    void emptyDay() {
        ReplaySeries replay = ReplaySeries.of(PriceSeries.empty());

        assertEquals(0, replay.total());
        assertTrue(replay.finished());
        assertEquals(0L, replay.clock());
        assertEquals(0, replay.advance(5));
    }

    @Test
    @DisplayName("a count outside the day is clamped, not accepted")
    void clamped() {
        assertEquals(10, new ReplaySeries(day(), 999).size());
        assertEquals(0, new ReplaySeries(day(), -5).size());
    }
}
