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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.Aggressor;
import br.com.jorge.reis.endeavourneo.domain.market.TapeFile;
import br.com.jorge.reis.endeavourneo.domain.market.TickFile;
import br.com.jorge.reis.endeavourneo.domain.market.TickLibrary;
import br.com.jorge.reis.endeavourneo.domain.market.TickSeries;
import br.com.jorge.reis.endeavourneo.domain.market.TickSource;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

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

        ReplayBase.at(folder.resolve("data"), DAY);

        try {
            ReplaySession playing = new ReplaySession(
                    ReplayFeed.of("win", TickSource.PROFIT), DAY, DAY, 0, ticks);

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
    @Test
    @DisplayName("a tick feed builds its bars from the trades, not from the candle file")
    void aTickFeedIsMadeOfTicks(@TempDir Path folder) throws Exception {
        // The promise the single list makes. Choosing the tape must mean the
        // bars on screen ARE the tape -- otherwise the reader picks "Profit"
        // and still watches candles from a file, which is the confusion the
        // two combo boxes created in the first place.
        //
        // The candle fixture walks around 100.000 and the tape prints at 200,
        // so which one built the bars cannot be mistaken.
        Path ticks = folder.resolve("ticks");

        tape(ticks);

        String series = ReplayBase.at(folder.resolve("data"), DAY);

        try {
            ReplaySession playing = new ReplaySession(
                    ReplayFeed.of("win", TickSource.PROFIT), DAY, DAY, 0, ticks);

            try {
                // Stepped first: the replay hides what has not played yet, which
                // is the point of it.
                playing.step(1);

                assertEquals(200, playing.series().closeAt(0),
                        "the bars came from the candle file, not from the tape");
                assertFalse(playing.isEmpty(), "the tape session produced no bars at all");
            } finally {
                playing.stop();
            }

            // And the bar feed of the same day is the candle file, untouched by
            // the tape sitting right beside it.
            ReplaySession bars = new ReplaySession(
                    ReplayFeed.of(series), DAY, DAY, 0, ticks);

            try {
                bars.step(1);

                assertTrue(bars.series().closeAt(0) > 1_000,
                        "the bar feed picked up the tape's prices");
            } finally {
                bars.stop();
            }
        } finally {
            ReplayBase.release();
        }
    }

    @Test
    @DisplayName("only feeds that have something to play are offered")
    void emptyFeedsAreNotOffered(@TempDir Path folder) throws Exception {
        // A source with no export is not a choice, it is a dead end with a
        // name. The tree makes the same call for the same reason.
        String series = ReplayBase.at(folder, DAY);

        try {
            tape(SeriesCatalog.ticksOf("win"));

            java.util.List<ReplayFeed> feeds = ReplayFeed.available();

            assertTrue(feeds.stream().anyMatch(each -> series.equals(each.series())),
                    "the bar series is not offered: " + feeds);
            assertTrue(feeds.stream().anyMatch(each -> each.source() == TickSource.PROFIT),
                    "the exported tape is not offered: " + feeds);
            assertFalse(feeds.stream().anyMatch(each -> each.source() == TickSource.METATRADER),
                    "a source with nothing exported was offered: " + feeds);
        } finally {
            ReplayBase.release();
        }
    }

    @Test
    @DisplayName("a feed is a series or a source, never both and never neither")
    void aFeedIsOneOrTheOther() {
        // The record enforces it, because the whole value of the type is that
        // holding one answers "what is playing" outright.
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayFeed("win", "winfull-1m", TickSource.PROFIT));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayFeed("win", null, null));
    }

    @Test
    @DisplayName("the chosen feed survives being written down and read back")
    void aFeedRoundTrips(@TempDir Path folder) throws Exception {
        // How the transport opens where it was left. Told apart by the prefix
        // rather than guessed from the rest: a market may be called anything,
        // including something shaped like a series name.
        ReplayBase.at(folder, DAY);

        try {
            tape(SeriesCatalog.ticksOf("win"));

            ReplayFeed tapeFeed = ReplayFeed.of("win", TickSource.PROFIT);

            assertEquals(tapeFeed, ReplayFeed.read(tapeFeed.saved()));

            ReplayFeed barFeed = ReplayFeed.of("winfull-1m");

            assertEquals(barFeed, ReplayFeed.read(barFeed.saved()));

            // And a feed that is no longer on disk comes back as nothing rather
            // than as something that cannot play.
            assertNull(ReplayFeed.read("ticks:win:METATRADER"));
            assertNull(ReplayFeed.read("series:que-nao-existe-1m"));
        } finally {
            ReplayBase.release();
        }
    }
}
