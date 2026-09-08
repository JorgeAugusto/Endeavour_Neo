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

    /**
     * A settings and workspace pair of this file's own.
     *
     * <p>Opening a chart or a window WRITES: the workspace remembers which
     * charts were open, where they were and what they were showing. The home
     * those files live under is redirected by a property set in one place only,
     * the surefire plugin -- and the house runs the suite with javac and a
     * runner instead, where that property is absent and these tests rewrote the
     * reader's own list of open charts on every run.</p>
     *
     * <p>Per test, and put back afterwards, so nothing here can be read by the
     * next file either: MainWindowTest counts the charts it remembers, and a
     * count is a property any other test could change.</p>
     */
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path store;

    @org.junit.jupiter.api.BeforeEach
    void useAStoreOfOurOwn() {
        br.com.jorge.reis.endeavourneo.platform.Settings.useForTest(store);
    }

    @org.junit.jupiter.api.AfterEach
    void putTheStoreBack() {
        br.com.jorge.reis.endeavourneo.platform.Settings.stopUsingTestStore();
    }

    /**
     * The base these tests play.
     *
     * <p>Needed since the replay stopped inventing its candles: it reads the
     * source now, and a test with no source replays nothing.</p>
     */
    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path folder;

    private static String base;

    @org.junit.jupiter.api.BeforeAll
    static void writeTheBase() throws java.io.IOException {
        base = ReplayBase.at(folder, LocalDate.of(2026, 9, 2));
    }

    @org.junit.jupiter.api.AfterAll
    static void putTheFolderBack() {
        ReplayBase.release();
    }

    /** No history, so the assertions below are about the session alone. */
    private static ReplaySession session() {
        return new ReplaySession(base, LocalDate.of(2026, 9, 2), 0);
    }

    @Test
    @DisplayName("a new session starts at the speed the transport is showing, not at 1x")
    void aNewSessionAdoptsTheChosenSpeed() throws Exception {
        // Every session after the first used to crawl at 1x while the control
        // beside it read 60. Two things line up to cause it and neither is wrong
        // on its own: the combo is filled from the workspace BEFORE its listeners
        // are attached, so restoring the reader's choice fires no event; and a
        // session is constructed with no speed argument, so it is born at 1x. The
        // only code that ever called setSpeed was that listener, which fires only
        // when the reader changes the selection by hand -- so the reader's fix
        // for the symptom was the one action that made it work.
        br.com.jorge.reis.endeavourneo.platform.Settings workspace =
                br.com.jorge.reis.endeavourneo.platform.Settings.workspace();
        String before = workspace.get("replay.speed", null);

        try {
            workspace.put("replay.speed", "60");

            ReplaySession fresh = session();

            assertEquals(1, fresh.speed(), "the premise of this test: a session is born at 1x");

            ReplayPanel[] panel = new ReplayPanel[1];

            javax.swing.SwingUtilities.invokeAndWait(() -> panel[0] = new ReplayPanel());

            assertEquals(60, panel[0].chosenSpeed(),
                    "the transport did not restore the remembered speed");

            javax.swing.SwingUtilities.invokeAndWait(() -> panel[0].adopt(fresh));

            assertEquals(60, fresh.speed(),
                    "the session was left at 1x while the transport said 60");
        } finally {
            if (before == null) {
                workspace.remove("replay.speed");
            } else {
                workspace.put("replay.speed", before);
            }
        }
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
        ReplaySession other = new ReplaySession(base, LocalDate.of(2026, 9, 3), 0);

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

        // The line here used to be
        //
        //   assertEquals(replay.series().size(), replay.series().size());
        //
        // -- the same expression on both sides, which is true of every possible
        // implementation of seekFraction, including one that does nothing. What
        // "finishes the day" means is that the whole session is revealed and
        // nothing is left forming, and that is what is asserted now.
        int atTheEnd = replay.series().size();

        replay.seekFraction(0.0);

        assertTrue(atTheEnd > replay.series().size(),
                "the far end revealed no more of the day than the near end did");

        replay.seekFraction(1.0);

        assertEquals(atTheEnd, replay.series().size(), "seeking is not repeatable");

        // Nothing further to reveal: stepping past the end must not grow it.
        replay.step(50);

        assertEquals(atTheEnd, replay.series().size(),
                "the day was finished and stepping revealed more of it");
    }

    @Test
    @DisplayName("speed is bars per second, and never zero")
    void speed() {
        ReplaySession replay = session();

        replay.setSpeed(16);
        assertEquals(16, replay.speed());

        replay.setSpeed(0);
        assertEquals(1, replay.speed(), "a speed of zero would never advance");

        // Capped where the animation stops showing everything: a bar is about
        // thirty-one prices, one every 1,93 s of market time, so fifty times is
        // one price per 40 ms frame. Faster skips prices, and the bar starts
        // forming in jumps again -- which is what the ticks exist to prevent.
        replay.setSpeed(1000);
        assertEquals(ReplaySession.FASTEST, replay.speed());

        for (int offered : ReplaySession.SPEEDS) {
            assertTrue(offered <= ReplaySession.FASTEST,
                    "the list offers " + offered + ", past the cap");
        }
    }
}
