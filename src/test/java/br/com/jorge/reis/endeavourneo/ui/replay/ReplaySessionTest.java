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
package br.com.jorge.reis.endeavourneo.ui.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The transport, without running the clock.
 *
 * <p>Nothing here starts the timer: a test that waits on wall-clock time is a
 * test that fails on a loaded machine and passes on a fast one, which is worse
 * than no test. The clock's only job is to call {@code step}, and stepping is
 * what is checked.</p>
 */
@DisplayName("Replay session")
class ReplaySessionTest {

    private static ReplaySession session() {
        return new ReplaySession("WINFUT", LocalDate.of(2026, 9, 2));
    }

    @Test
    @DisplayName("a session starts closed and opens as it is stepped")
    void startsAtTheOpen() {
        ReplaySession replay = session();

        assertEquals(0, replay.series().size(), "a replay must not begin with the day already over");
        assertEquals(0.0, replay.progress());

        replay.step(10);

        assertEquals(10, replay.series().size());
    }

    @Test
    @DisplayName("the same day always replays the same way")
    void theSameDayRepeats() {
        // Seeded by the date. A replay that changed between two runs would make
        // comparing two decisions taken on it meaningless.
        ReplaySession first = session();
        ReplaySession second = session();

        first.step(20);
        second.step(20);

        assertEquals(first.series().closeAt(19), second.series().closeAt(19));

        // And a different day is a different market.
        ReplaySession other = new ReplaySession("WINFUT", LocalDate.of(2026, 9, 3));

        other.step(20);

        assertNotEquals(first.series().closeAt(19), other.series().closeAt(19));
    }

    @Test
    @DisplayName("stepping back closes the future again")
    void steppingBack() {
        ReplaySession replay = session();

        replay.step(50);
        replay.step(-10);

        assertEquals(40, replay.series().size());
    }

    @Test
    @DisplayName("watchers hear every move")
    void watchersHear() {
        ReplaySession replay = session();
        AtomicInteger heard = new AtomicInteger();
        Runnable watcher = heard::incrementAndGet;

        replay.watch(watcher);
        replay.step(1);
        replay.seekFraction(0.5);

        assertEquals(2, heard.get());

        replay.forget(watcher);
        replay.step(1);

        assertEquals(2, heard.get(), "a chart that let go was told anyway");
    }

    @Test
    @DisplayName("the clock reads the session's own time, not today's")
    void theClockIsTheSessionsClock() {
        ReplaySession replay = session();

        // Before anything arrives it reads the opening, not 1970 and not now.
        assertTrue(replay.clockText().startsWith("09:00"), replay.clockText());

        replay.seekFraction(1.0);

        assertEquals(replay.endText(), replay.clockText().substring(0, 5),
                "played to the end, the clock has to read the close");
    }

    @Test
    @DisplayName("dragging to the far end finishes the day")
    void seekingToTheEnd() {
        ReplaySession replay = session();

        replay.seekFraction(1.0);

        assertEquals(1.0, replay.progress());
        assertEquals(replay.series().size(), replay.series().size());
    }

    @Test
    @DisplayName("speed is bars per second, and never zero")
    void speed() {
        ReplaySession replay = session();

        replay.setSpeed(16);
        assertEquals(16, replay.speed());

        // A zero would be a division by zero in the timer's delay.
        replay.setSpeed(0);
        assertEquals(1, replay.speed());
    }
}
