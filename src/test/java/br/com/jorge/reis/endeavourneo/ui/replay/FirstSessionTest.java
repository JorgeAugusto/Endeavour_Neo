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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.TickSource;
import br.com.jorge.reis.endeavourneo.domain.market.TickFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one place the replay waits: before the first bar is played.
 *
 * <p>Everywhere else a session that has not arrived yet draws a synthetic path
 * for a frame, which nobody sees. Here the reader is standing still with a
 * finger over the play button, and starting on invented ticks — then swapping
 * them for the real ones a quarter of a second later — would be a difference
 * nobody could notice and everybody would inherit.</p>
 */
@DisplayName("Waiting for the first session")
class FirstSessionTest {

    private static final LocalDate DAY = LocalDate.of(2021, 1, 4);

    private static void session(Path folder, LocalDate date) throws IOException {
        try (TickFile.Writer writer = new TickFile.Writer(
                TickSource.METATRADER.fileFor(folder, "win", date), date)) {

            for (int i = 0; i < 200; i++) {
                writer.add(9 * 3_600_000 + i * 100, 0, 0, 118_000 + i % 7, 1, 88,
                        TickFile.Writer.mask(false, false, true, true));
            }
        }
    }

    private static void settle(ReplaySession session) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (session.isPreparing() && System.nanoTime() < deadline) {
            Thread.sleep(10);

            // The session hands the news over to the interface thread, so the
            // test has to let that thread run.
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        }
    }

    @Test
    @DisplayName("a day with ticks is not playable until they are in memory")
    void theFirstSessionIsWaitedFor(@TempDir Path folder) throws Exception {
        session(folder, DAY);

        ReplaySession replay = new ReplaySession("winfut-1m", DAY, DAY, 0, folder);

        try {
            assertTrue(replay.isPreparing(), "the replay offered to play before it had the ticks");
            assertTrue(replay.isRecorded(), "the day has an export and was not seen");

            // Play does nothing while it waits, even if something reaches it.
            replay.toggle();

            assertFalse(replay.isPlaying(), "it started playing while still loading");

            settle(replay);

            assertFalse(replay.isPreparing(), "it never stopped waiting");

            replay.toggle();

            assertTrue(replay.isPlaying(), "it would not start once the ticks had arrived");
        } finally {
            replay.stop();
        }
    }

    @Test
    @DisplayName("a day with no export plays at once, with a synthetic path")
    void withoutAnExportThereIsNothingToWaitFor(@TempDir Path folder) throws Exception {
        // Most of the base: one month of ticks against eight years of candles.
        // Waiting for a file that does not exist would be a transport that
        // never enables itself.
        ReplaySession replay = new ReplaySession("winn-1m", DAY, DAY, 0, folder);

        try {
            assertFalse(replay.isPreparing(), "it waited for ticks that were never exported");
            assertFalse(replay.isRecorded());

            replay.toggle();

            assertTrue(replay.isPlaying());
        } finally {
            replay.stop();
        }
    }

    @Test
    @DisplayName("the tick files are named after the MARKET, not after the export")
    void theSessionsBelongToTheMarket() {
        // The ticks of a day are what the exchange printed. Three exports of
        // the WIN read the same win-2021-01-04.bin, because none of them owns
        // those ticks.
        //
        // The first version cut the name at the first dash, which gave
        // "winfut" -- right by accident while the only base with ticks was
        // called that, and wrong the moment the source was renamed to
        // winfull-1m. Every day would then look unexported and the replay
        // would be synthetic for ever, in silence.
        assertEquals("win", ReplaySession.rootOf("winfull-1m"));
        assertEquals("win", ReplaySession.rootOf("winn-1m"));
        assertEquals("win", ReplaySession.rootOf("winfut-1m"));
        assertEquals("", ReplaySession.rootOf(null));
    }

    @Test
    @DisplayName("stopping gives the ticks back to the machine")
    void stoppingReleasesTheSessions(@TempDir Path folder) throws Exception {
        // Three sessions are 340 MB. Holding them after the replay is over is
        // the one place this design could leak.
        session(folder, DAY);

        ReplaySession replay = new ReplaySession("winfut-1m", DAY, DAY, 0, folder);

        settle(replay);

        assertEquals(1, replay.residentTicks(), "the session was never loaded");

        replay.stop();

        // The first draft asserted "isPreparing() || !isPlaying()", which is
        // true of almost any state and proved nothing. What matters is the 340
        // MB, so the test looks at the 340 MB.
        assertEquals(0, replay.residentTicks(),
                "the ticks were still held after the replay was stopped");
    }
}
