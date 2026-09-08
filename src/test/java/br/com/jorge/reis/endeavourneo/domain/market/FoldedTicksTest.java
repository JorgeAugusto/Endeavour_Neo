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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An export of ticks, read as a series.
 *
 * <p>It used to be that a tick export could only be replayed, never opened —
 * which made the most complete data in the program the only data that could not
 * be looked at. These are the properties that make it a series like any other.</p>
 */
@DisplayName("Ticks dobrados")
class FoldedTicksTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /**
     * A session of {@code minutes} minutes, four trades a minute.
     *
     * <p>The price walks so that each minute has a range: a fold that lost the
     * high or the low would come out flat and be caught.</p>
     */
    private static void session(Path folder, LocalDate date, int minutes, int from)
            throws IOException {
        try (TickFile.Writer writer = new TickFile.Writer(
                TickSource.METATRADER.fileFor(folder, "win", date), date)) {

            for (int minute = 0; minute < minutes; minute++) {
                int base = from + minute * 10;
                int[] walk = {base, base + 6, base - 4, base + 2};

                for (int i = 0; i < walk.length; i++) {
                    writer.add(9 * 3_600_000 + minute * 60_000 + i * 10_000,
                            0, 0, walk[i], 1, 88,
                            TickFile.Writer.mask(false, false, true, true));
                }
            }
        }
    }

    @Test
    @DisplayName("um pregao de ticks vira candles de um minuto, com maxima e minima")
    void oneSessionBecomesMinutes(@TempDir Path folder) throws IOException {
        // Not the trades themselves: five million bars where five hundred
        // belong is not a scale a chart can draw, and Timeframe.apply takes a
        // shortcut at one minute and would hand them straight back -- thirteen
        // thousand trades inside the first minute of screen, and the reader
        // seeing a flat line. That happened, and folding is what fixed it.
        LocalDate day = LocalDate.of(2021, 1, 4);

        session(folder, day, 5, 118_000);

        PriceSeries bars = FoldedTicks.day(folder, "win", TickSource.METATRADER, day, ZONE);

        assertEquals(5, bars.size(), "four trades a minute did not fold into minutes");

        // The minute keeps what the trades did inside it, which is the whole
        // point of reading the export rather than the candle file.
        assertEquals(118_000, bars.openAt(0), 1e-9);
        assertEquals(118_006, bars.highAt(0), 1e-9);
        assertEquals(117_996, bars.lowAt(0), 1e-9);
        assertEquals(118_002, bars.closeAt(0), 1e-9);
    }

    @Test
    @DisplayName("um dia que nao foi exportado vem vazio, nunca de outro lugar")
    void aDayThatWasNotExportedIsEmpty(@TempDir Path folder) throws IOException {
        // A chart of the ticks shows ticks, and where there are none it shows
        // nothing. Falling back to the candle file would put two different
        // measurements of the same hours in one window.
        //
        // THE CANDLE FILE HAS TO BE THERE, or this cannot see the thing it
        // forbids. The folder used to be an empty @TempDir: no ticks and no
        // candles either, so a fallback to the candles would have found nothing
        // to fall back TO and the test would have passed with the fallback
        // switched on.
        MarketFile.write(folder.resolve("win-1m.bin"),
                bars(LocalDate.of(2021, 1, 4), 4, 118_000), 1);

        assertEquals(0, FoldedTicks.day(folder, "win", TickSource.METATRADER,
                LocalDate.of(2021, 1, 4), ZONE).size(),
                "the tick chart fell back to the candle file sitting beside it");
    }

    /** @return bars of that day, one a minute, so a candle file can be written */
    private static PriceSeries bars(LocalDate day, int howMany, double from) {
        long open = day.atStartOfDay(ZONE).toInstant().toEpochMilli() + 9 * 3_600_000L;

        return new PriceSeries() {

            @Override
            public int size() {
                return howMany;
            }

            @Override
            public long timeAt(int index) {
                return open + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return from + index;
            }

            @Override
            public double highAt(int index) {
                return from + index + 5;
            }

            @Override
            public double lowAt(int index) {
                return from + index - 5;
            }

            @Override
            public double closeAt(int index) {
                return from + index + 2;
            }
        };
    }

    @Test
    @DisplayName("os pregoes viram uma serie so, em ordem")
    void theSessionsBecomeOneSeries(@TempDir Path folder) throws IOException {
        LocalDate monday = LocalDate.of(2021, 1, 4);
        LocalDate tuesday = LocalDate.of(2021, 1, 5);

        session(folder, monday, 4, 118_000);
        session(folder, tuesday, 3, 119_000);

        PriceSeries all = FoldedTicks.all(folder, "win", TickSource.METATRADER, ZONE);

        assertEquals(7, all.size(), "the sessions did not come back as one series");
        assertEquals(118_000, all.openAt(0), 1e-9);
        assertEquals(119_000, all.openAt(4), 1e-9, "Tuesday did not follow Monday");

        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.timeAt(i) > all.timeAt(i - 1),
                    "bar " + i + " goes back in time");
        }
    }

    @Test
    @DisplayName("so os pregoes pedidos, na ordem pedida")
    void onlyTheSessionsAsked(@TempDir Path folder) throws IOException {
        LocalDate monday = LocalDate.of(2021, 1, 4);
        LocalDate tuesday = LocalDate.of(2021, 1, 5);

        session(folder, monday, 4, 118_000);
        session(folder, tuesday, 3, 119_000);

        PriceSeries only = FoldedTicks.over(folder, "win", TickSource.METATRADER,
                List.of(tuesday), ZONE);

        assertEquals(3, only.size());

        // WHICH day, and not just how many bars. Counting alone cannot tell
        // Tuesday's three from any other three.
        assertEquals(119_000, only.openAt(0), 1e-9, "these are Monday's bars");

        // AND THE ORDER, which the name of this test promised and a list of one
        // element cannot exercise. over() is how the segments window opens a
        // chosen stretch of sessions, and the order decides where each bar
        // lands on the axis -- sorting the list inside it would pass a test
        // that only ever asks for one day.
        PriceSeries backwards = FoldedTicks.over(folder, "win", TickSource.METATRADER,
                List.of(tuesday, monday), ZONE);

        assertEquals(7, backwards.size());
        assertEquals(119_000, backwards.openAt(0), 1e-9,
                "the list was asked for Tuesday first and came back Monday first");
    }

    @Test
    @DisplayName("um pregao exportado e ilegivel nao some calado")
    void anUnreadableSessionIsNotSilent(@TempDir Path folder) throws IOException {
        // Two answers used to be one. A day that was never exported comes back
        // empty and that is the truth; a day that WAS exported and will not read
        // came back empty too, so a corrupt session vanished from the middle of
        // a chart with no gap and no word -- swallowing exactly the refusals
        // TapeFile and TickFile spend their guards to produce.
        LocalDate day = LocalDate.of(2021, 1, 4);

        session(folder, day, 3, 118_000);

        Path file = TickSource.METATRADER.fileFor(folder, "win", day);
        byte[] whole = java.nio.file.Files.readAllBytes(file);

        // The header survives -- so the file still says it is that session --
        // and the body is cut in the middle of a tick.
        java.nio.file.Files.write(file, java.util.Arrays.copyOf(whole, whole.length - 7));

        java.io.PrintStream was = System.err;
        java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();

        try {
            System.setErr(new java.io.PrintStream(said, true,
                    java.nio.charset.StandardCharsets.UTF_8));

            assertEquals(0, FoldedTicks.day(folder, "win", TickSource.METATRADER,
                    day, ZONE).size(), "a truncated session came back with bars");
        } finally {
            System.setErr(was);
        }

        String message = said.toString(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(message.contains(file.getFileName().toString()),
                "the file that will not read was not named: " + message);
        assertTrue(!message.isBlank(),
                "an exported session was dropped from the chart without a word");
    }
/**
     * The session is folded once and kept, so the second opening is free.
     *
     * <p>Reading an export is seconds — 8,1 s for 1.838 MB of MetaTrader — and
     * it produces half a megabyte of bars. The proof that the second read comes
     * from the cache and not from the ticks is direct: the ticks are REPLACED
     * with a different session, keeping the file's modification time, and the
     * answer has to be the old bars. Anything that read the ticks again would
     * answer the new ones.</p>
     */
    @Test
    @DisplayName("o pregao e dobrado uma vez, e a segunda abertura vem do que ficou guardado")
    void thesessionIsFoldedOnceAndKept(@TempDir Path folder) throws IOException {
        LocalDate day = LocalDate.of(2021, 1, 4);

        session(folder, day, 3, 100_000);

        PriceSeries first = FoldedTicks.day(folder, "win", TickSource.METATRADER, day, ZONE);

        assertEquals(3, first.size(), "the fixture did not fold to three minutes");

        Path ticks = TickSource.METATRADER.fileFor(folder, "win", day);
        Path cache = FoldedTicks.cacheFor(ticks);

        assertTrue(java.nio.file.Files.isRegularFile(cache),
                "nothing was kept, so every opening pays the seconds again");

        // The ticks are rewritten with OTHER prices, and the stamp is put back
        // as it was: only something that read the ticks again can see the
        // difference.
        java.nio.file.attribute.FileTime was = java.nio.file.Files.getLastModifiedTime(ticks);

        java.nio.file.Files.delete(ticks);
        session(folder, day, 3, 200_000);
        java.nio.file.Files.setLastModifiedTime(ticks, was);

        PriceSeries again = FoldedTicks.day(folder, "win", TickSource.METATRADER, day, ZONE);

        assertEquals(first.closeAt(0), again.closeAt(0), 1e-9,
                "the ticks were read a second time, so nothing was saved");
    }

    /**
     * And a session imported again is folded again.
     *
     * <p>The other half, and the reason the cache is per session: there is no
     * set to keep in agreement. A day that was imported again carries a new
     * modification time, the two stamps stop matching, and that day — only that
     * day — is folded once more.</p>
     */
    @Test
    @DisplayName("um pregao importado de novo e dobrado de novo")
    void asessionImportedAgainIsFoldedAgain(@TempDir Path folder) throws IOException {
        LocalDate day = LocalDate.of(2021, 1, 4);

        session(folder, day, 3, 100_000);

        PriceSeries first = FoldedTicks.day(folder, "win", TickSource.METATRADER, day, ZONE);

        Path ticks = TickSource.METATRADER.fileFor(folder, "win", day);

        java.nio.file.Files.delete(ticks);
        session(folder, day, 3, 200_000);

        // The import gives the file a stamp of its own, which is what the cache
        // was made against and no longer matches.
        java.nio.file.Files.setLastModifiedTime(ticks,
                java.nio.file.attribute.FileTime.fromMillis(
                        java.nio.file.Files.getLastModifiedTime(ticks).toMillis() + 5_000L));

        PriceSeries again = FoldedTicks.day(folder, "win", TickSource.METATRADER, day, ZONE);

        assertEquals(100_000.0, first.openAt(0), 1e-9, "the fixture is not what it says");
        assertEquals(200_000.0, again.openAt(0), 1e-9,
                "the reimported session came back from a cache made before it");
    }

    /**
     * A cache that will not read is a cache that is not there.
     *
     * <p>It is a derived file: the answer to one that is damaged is to fold
     * again and write it over, never to refuse the chart.</p>
     */
    @Test
    @DisplayName("um cache estragado nao derruba a abertura: dobra de novo e reescreve")
    void adamagedCacheIsFoldedAgain(@TempDir Path folder) throws IOException {
        LocalDate day = LocalDate.of(2021, 1, 4);

        session(folder, day, 3, 100_000);

        FoldedTicks.day(folder, "win", TickSource.METATRADER, day, ZONE);

        Path ticks = TickSource.METATRADER.fileFor(folder, "win", day);
        Path cache = FoldedTicks.cacheFor(ticks);

        java.nio.file.Files.write(cache, "isto nao e um arquivo de barras".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.setLastModifiedTime(cache,
                java.nio.file.Files.getLastModifiedTime(ticks));

        PriceSeries again = FoldedTicks.day(folder, "win", TickSource.METATRADER, day, ZONE);

        assertEquals(3, again.size(), "a damaged cache took the session down with it");
        assertTrue(MarketFile.isSeries(cache), "the damaged cache was not written over");
    }

    /**
     * The cache is not offered as a session.
     *
     * <p>It sits in the same directory as the ticks and its name begins the same
     * way. A listing that took it for a session would offer the reader a day
     * that plays nothing.</p>
     */
    @Test
    @DisplayName("o cache nao aparece na lista de pregoes")
    void thecacheIsNotListedAsAsession(@TempDir Path folder) throws IOException {
        LocalDate day = LocalDate.of(2021, 1, 4);

        session(folder, day, 3, 100_000);
        FoldedTicks.day(folder, "win", TickSource.METATRADER, day, ZONE);

        try (TickLibrary library = new TickLibrary(folder, "win", TickSource.METATRADER)) {
            assertEquals(List.of(day), library.exported(),
                    "the folded file was offered as a session of its own");
        }
    }
}
