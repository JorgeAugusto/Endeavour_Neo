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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.Aggressor;
import br.com.jorge.reis.endeavourneo.domain.market.TapeFile;
import br.com.jorge.reis.endeavourneo.domain.market.TickFile;
import br.com.jorge.reis.endeavourneo.domain.market.TickLibrary;
import br.com.jorge.reis.endeavourneo.domain.market.TickSeries;
import br.com.jorge.reis.endeavourneo.domain.market.TickSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Choosing which export the replay plays.
 *
 * <p>Both sources can hold the same session of the same market. What decides
 * which one is played is the reader, and this is where that choice is proved to
 * reach the ticks rather than stopping at the combo box.</p>
 */
@DisplayName("Tick source choice")
class TickSourceChoiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    /** The MetaTrader export of that session: quotes and prints at 100. */
    private static void quotes(Path ticks) throws IOException {
        try (TickFile.Writer writer = new TickFile.Writer(
                TickSource.METATRADER.fileFor(ticks, "win", DAY), DAY)) {

            for (int i = 0; i < 5; i++) {
                writer.add(9 * 3_600_000 + i * 1_000, 99, 101, 100, 1, 88,
                        TickFile.Writer.mask(true, true, true, true));
            }
        }
    }

    /** The Profit tape of the SAME session: prints at 200, with counterparties. */
    private static void tape(Path ticks) throws IOException {
        try (TapeFile.Writer writer = new TapeFile.Writer(
                TickSource.PROFIT.fileFor(ticks, "win", DAY), DAY)) {

            writer.broker(85, "BTG Pactual CTVM S.A.");
            writer.broker(3, "XP Investimentos CCTVM S/A");

            for (int i = 0; i < 7; i++) {
                writer.add(9 * 3_600_000 + i * 1_000, 200, 2, 85, 3, Aggressor.BUYER);
            }
        }
    }

    @Test
    @DisplayName("the same day, two sources, and the chosen one is what plays")
    void theChoiceReachesTheTicks(@TempDir Path ticks) throws IOException {
        // Deliberately the same instrument and the same date in both files,
        // with prices that cannot be confused. A library that read the wrong
        // one would still find a session and still play -- it would just be
        // playing another market's worth of numbers, and nothing on screen
        // would say so.
        quotes(ticks);
        tape(ticks);

        TickLibrary metatrader = new TickLibrary(ticks, "win", TickSource.METATRADER);
        TickLibrary profit = new TickLibrary(ticks, "win", TickSource.PROFIT);

        try {
            TickSeries fromQuotes = metatrader.load(DAY);
            TickSeries fromTape = profit.load(DAY);

            assertNotNull(fromQuotes, "the MetaTrader session was not found");
            assertNotNull(fromTape, "the Profit session was not found");

            assertEquals(5, fromQuotes.size());
            assertEquals(7, fromTape.size());
            assertEquals(100, fromQuotes.lastAt(0));
            assertEquals(200, fromTape.lastAt(0), "the tape was read through the tick reader");

            // And each declines what it does not have, which is the seam the
            // whole two-format decision rests on.
            assertTrue(fromQuotes.hasBid(0));
            assertFalse(fromQuotes.hasBuyer(0));

            assertFalse(fromTape.hasBid(0));
            assertTrue(fromTape.hasBuyer(0));
            assertEquals("BTG Pactual CTVM S.A.", fromTape.brokerName(fromTape.buyerAt(0)));
            assertEquals(Aggressor.BUYER, fromTape.aggressorAt(0));
        } finally {
            metatrader.close();
            profit.close();
        }
    }

    @Test
    @DisplayName("a source lists and loads only its own sessions")
    void oneSourceDoesNotSeeTheOther(@TempDir Path ticks) throws IOException {
        // Only the tape exists. The MetaTrader library must not find it -- if
        // it did, the extension would be decoration and the reader's choice
        // would mean nothing.
        tape(ticks);

        TickLibrary metatrader = new TickLibrary(ticks, "win", TickSource.METATRADER);
        TickLibrary profit = new TickLibrary(ticks, "win", TickSource.PROFIT);

        try {
            assertTrue(metatrader.exported().isEmpty(),
                    "the tick library listed a tape as one of its own");
            assertFalse(metatrader.has(DAY));
            assertEquals(java.util.List.of(DAY), profit.exported());
            assertTrue(profit.has(DAY));
        } finally {
            metatrader.close();
            profit.close();
        }
    }

    @Test
    @DisplayName("the session plays the source it was handed")
    void theSessionPlaysWhatItWasGiven(@TempDir Path folder) throws Exception {
        // Through the replay itself, not the library, so the parameter is
        // proved to be carried all the way down rather than accepted and
        // dropped.
        Path ticks = folder.resolve("ticks");

        quotes(ticks);
        tape(ticks);

        String series = ReplayBase.at(folder.resolve("data"), DAY);

        try {
            ReplaySession playing = new ReplaySession(series, DAY, DAY, 0, ticks,
                    TickSource.PROFIT);

            try {
                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

                while (playing.isPreparing() && System.nanoTime() < deadline) {
                    Thread.sleep(10);

                    javax.swing.SwingUtilities.invokeAndWait(() -> { });
                }

                TickSeries held = playing.residentAt(DAY);

                assertNotNull(held, "the replay loaded no ticks for the day it was given");
                assertEquals(200, held.lastAt(0),
                        "the replay played the MetaTrader export after being handed the tape");
            } finally {
                playing.stop();
            }
        } finally {
            ReplayBase.release();
        }
    }
}
