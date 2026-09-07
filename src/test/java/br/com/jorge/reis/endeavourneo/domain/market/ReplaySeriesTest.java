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

    /** One minute in a hundred has no recorded ticks, and none are invented. */
    private static TickPath missingAt(int without) {
        return (bars, index) -> index == without ? null : new double[]{index, index, index};
    }

    @Test
    @DisplayName("a minute with no ticks appears WHOLE, and the replay keeps going")
    void aMinuteWithoutTicksDoesNotFreeze() {
        // The freeze, written as the test that was missing. A minute with no
        // recorded ticks and synthetic ones turned off gave startForming nothing
        // to hand out, and the clock loop answered by zeroing what it owed and
        // returning -- so nothing ever moved again. The transport went on calling
        // this twenty-five times a second with the play icon lit, and only Stop
        // got out.
        //
        // startForming's own comment says what should happen instead: the bar
        // appears whole. That is what is asserted here.
        ReplaySeries replay = new ReplaySeries(day(), 0, 2, missingAt(4));

        int before = replay.size();

        // Ten bars' worth of market time: enough to walk past the gap and reach
        // the end of the session whatever the path length is.
        for (int frame = 0; frame < 200; frame++) {
            replay.advanceMarketTime(60_000L);
        }

        assertTrue(replay.size() > before,
                "the replay did not advance a single bar past the minute without ticks");
        assertTrue(replay.finished(),
                "the replay never reached the end of the session: it is still stuck");
    }

    @Test
    @DisplayName("the minute without ticks is still the minute the session recorded")
    void theWholeBarIsTheRealBar() {
        // Appearing whole must not mean appearing invented. The bar that arrives
        // is the one the session holds, which is the honest picture of what is
        // known about a minute nobody recorded.
        ReplaySeries replay = new ReplaySeries(day(), 0, 4, missingAt(4));

        for (int frame = 0; frame < 200; frame++) {
            replay.advanceMarketTime(60_000L);
        }

        assertEquals(day().closeAt(4), replay.closeAt(4), 1e-9,
                "the bar with no ticks came out with a price the session never had");
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
    @Test
    @DisplayName("o relogio anda DENTRO da barra, nao so entre barras")
    void theClockMovesInsideTheBar() {
        // What froze the tick renko. A bar carries the time its bucket STARTS,
        // so a clock that only read bar times sat still for a whole minute and
        // jumped -- and the renko, asking what time it was, got the same answer
        // sixty seconds running and had nothing new to lay.
        ReplaySeries live = new ReplaySeries(day(), 0,
                (day, index) -> new double[]{100, 101, 102, 103, 104, 105, 106, 107});

        // A quarter of a bar at a time, twice, staying inside the first one --
        // a whole bar would finish it and the clock would move to the next,
        // which it should, and which is not what this is about.
        live.advanceMarketTime(15_000);

        long started = live.clock();

        live.advanceMarketTime(15_000);

        long later = live.clock();

        assertTrue(later > started,
                "the clock did not move inside the bar: " + started + " then " + later);
        assertTrue(later - started < 60_000,
                "a quarter of a bar moved the clock a whole bar: " + (later - started));
    }
}
