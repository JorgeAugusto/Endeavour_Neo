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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Three sessions in memory, whatever the period covers.
 */
@DisplayName("Tick library")
class TickLibraryTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** A session of a few trades, a second apart from 09:00. */
    private static void session(Path folder, LocalDate date, int from) throws IOException {
        try (TickFile.Writer writer = new TickFile.Writer(
                TickSource.METATRADER.fileFor(folder, "winfut", date), date)) {

            for (int i = 0; i < 5; i++) {
                writer.add(9 * 3_600_000 + i * 1_000,
                        0, 0, from + i, 1, 88,
                        TickFile.Writer.mask(false, false, true, true));
            }
        }
    }

    /** Waits for the loader to settle, or gives up. */
    private static void settle(TickLibrary library, int wanted) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (library.residentCount() < wanted && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    @DisplayName("never more than three sessions are held, however long the period")
    void neverMoreThanThree(@TempDir Path folder) throws Exception {
        // The property the whole design exists for: the cost is the same for a
        // replay of three days and one of three years.
        // Days 3 and 16 exist too, so every day that gets requested has both
        // neighbours to load. Without them the first and last iterations wait
        // for a session that was never written, and the test takes five
        // seconds to prove nothing.
        for (int day = 3; day <= 16; day++) {
            session(folder, LocalDate.of(2021, 1, day), 118_000 + day);
        }

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            for (int day = 4; day <= 15; day++) {
                library.request(LocalDate.of(2021, 1, day));

                settle(library, TickLibrary.RESIDENT);

                // THREE, written out, not TickLibrary.RESIDENT. Comparing
                // against the constant under test is a tautology: the first
                // draft did that, and it passed happily with the ceiling
                // raised to a hundred.
                assertTrue(library.residentCount() <= 3,
                        "day " + day + " left " + library.residentCount() + " sessions in memory");
            }
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("what is kept is the day playing and its two neighbours")
    void theNeighboursAreTheOnesKept(@TempDir Path folder) throws Exception {
        // And not the three used most recently. A replay walking forwards
        // touches yesterday, today and tomorrow in an order that would make a
        // least-recently-used cache throw away tomorrow just before reaching it.
        for (int day = 4; day <= 8; day++) {
            session(folder, LocalDate.of(2021, 1, day), 118_000 + day);
        }

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            library.request(LocalDate.of(2021, 1, 6));

            settle(library, TickLibrary.RESIDENT);

            assertEquals(List.of(LocalDate.of(2021, 1, 5), LocalDate.of(2021, 1, 6),
                            LocalDate.of(2021, 1, 7)).stream().sorted().toList(),
                    library.residentDays().stream().sorted().toList(),
                    "the three kept are not the day and its neighbours");
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("what is dropped is the day furthest away, not the one used longest ago")
    void theFurthestIsDropped(@TempDir Path folder) throws Exception {
        // Where the two rules actually differ, which the neighbours test does
        // not reach: after playing the 6th and moving to the 7th, memory holds
        // 5, 6 and 7 and the 8th arrives. By distance the 5th goes and {6,7,8}
        // stays -- the day just played, the one playing, the one next. By least
        // recently used the 6th would go instead, throwing away the day the
        // reader is most likely to drag back into.
        for (int day = 4; day <= 9; day++) {
            session(folder, LocalDate.of(2021, 1, day), 118_000 + day);
        }

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            library.request(LocalDate.of(2021, 1, 6));
            settle(library, 3);

            library.request(LocalDate.of(2021, 1, 7));
            settle(library, 3);

            // Give the loader a moment to finish the 8th and evict.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

            while (!library.residentDays().contains(LocalDate.of(2021, 1, 8))
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertEquals(List.of(LocalDate.of(2021, 1, 6), LocalDate.of(2021, 1, 7),
                            LocalDate.of(2021, 1, 8)),
                    library.residentDays().stream().sorted().toList(),
                    "the session dropped was not the one furthest from the day playing");
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("asking never blocks and never reads the disk")
    void askingDoesNotBlock(@TempDir Path folder) throws Exception {
        // Called while the chart is painting. Null means "not yet", and the
        // caller draws a synthetic path for that one frame.
        session(folder, LocalDate.of(2021, 1, 4), 118_000);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            assertNull(library.at(LocalDate.of(2021, 1, 4)),
                    "at() read the disk instead of answering with what it had");
            assertTrue(library.has(LocalDate.of(2021, 1, 4)));

            library.request(LocalDate.of(2021, 1, 4));

            settle(library, 1);

            assertNotNull(library.at(LocalDate.of(2021, 1, 4)));
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("a day that was never exported is absent, not an error")
    void anAbsentSessionIsNotAFailure(@TempDir Path folder) throws Exception {
        session(folder, LocalDate.of(2021, 1, 4), 118_000);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            assertFalse(library.has(LocalDate.of(2021, 6, 1)));
            assertNull(library.load(LocalDate.of(2021, 6, 1)));

            library.request(LocalDate.of(2021, 6, 1));

            Thread.sleep(50);

            assertNull(library.at(LocalDate.of(2021, 6, 1)));
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("the sessions on disk are listed by date")
    void theExportIsListed(@TempDir Path folder) throws Exception {
        session(folder, LocalDate.of(2021, 1, 6), 118_006);
        session(folder, LocalDate.of(2021, 1, 4), 118_004);
        session(folder, LocalDate.of(2021, 1, 5), 118_005);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            assertEquals(List.of(LocalDate.of(2021, 1, 4), LocalDate.of(2021, 1, 5),
                    LocalDate.of(2021, 1, 6)), library.exported());
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("a bar is drawn from the ticks that happened inside it")
    void theBarTakesItsOwnTicks(@TempDir Path folder) throws Exception {
        LocalDate date = LocalDate.of(2021, 1, 4);

        try (TickFile.Writer writer = new TickFile.Writer(
                TickSource.METATRADER.fileFor(folder, "winfut", date), date)) {

            // Two minutes of trades: 118000..118002 in the first, 118010..118011
            // in the second. A bar must take its own and not its neighbour's.
            writer.add(9 * 3_600_000, 0, 0, 118_000, 1, 88, trade());
            writer.add(9 * 3_600_000 + 10_000, 0, 0, 118_001, 1, 88, trade());
            writer.add(9 * 3_600_000 + 20_000, 0, 0, 118_002, 1, 88, trade());
            writer.add(9 * 3_600_000 + 60_000, 0, 0, 118_010, 1, 88, trade());
            writer.add(9 * 3_600_000 + 70_000, 0, 0, 118_011, 1, 88, trade());
        }

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            library.request(date);
            settle(library, 1);

            RecordedTicks ticks = new RecordedTicks(library, null);
            PriceSeries bars = twoMinutesFrom(date);

            assertTrue(ticks.isRecorded(bars, 0));
            assertArrayEquals(new double[]{118_000, 118_001, 118_002},
                    ticks.pathFor(bars, 0), 1e-9,
                    "the first bar took ticks that belong to the second");
            assertArrayEquals(new double[]{118_010, 118_011},
                    ticks.pathFor(bars, 1), 1e-9);
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("without ticks the bar falls back, and says it was not recorded")
    void withoutTicksItFallsBack(@TempDir Path folder) throws Exception {
        LocalDate date = LocalDate.of(2021, 1, 4);
        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            AtomicInteger asked = new AtomicInteger();
            TickPath fallback = (series, index) -> {
                asked.incrementAndGet();

                return new double[]{1, 2, 3};
            };

            RecordedTicks ticks = new RecordedTicks(library, fallback);
            PriceSeries bars = twoMinutesFrom(date);

            assertFalse(ticks.isRecorded(bars, 0));
            assertArrayEquals(new double[]{1, 2, 3}, ticks.pathFor(bars, 0), 1e-9);
            assertEquals(1, asked.get(), "the fallback was not used");
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("refusing the fallback draws nothing rather than a guess")
    void theFallbackCanBeRefused(@TempDir Path folder) throws Exception {
        // For a reader who would rather see no path than an invented one.
        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            RecordedTicks ticks = new RecordedTicks(library, null);

            assertNull(ticks.pathFor(twoMinutesFrom(LocalDate.of(2021, 1, 4)), 0));
        } finally {
            library.close();
        }
    }

    private static int trade() {
        return TickFile.Writer.mask(false, false, true, true);
    }

    /** Two one-minute bars from 09:00 on that date. */
    private static PriceSeries twoMinutesFrom(LocalDate date) {
        long open = date.atStartOfDay(ZONE).toInstant().toEpochMilli() + 9 * 3_600_000L;

        return new PriceSeries() {

            @Override
            public int size() {
                return 2;
            }

            @Override
            public long timeAt(int index) {
                return open + index * 60_000L;
            }

            // The candle AGREES with the ticks of its own minute here, so
            // the tests about the window stay about the window: pathFor brackets
            // the trades with the bar's open and close, and a fixture that
            // disagreed would add those two prices to every expectation.
            // theEndsAreTheBarsOwn is where the disagreement is on purpose.
            @Override
            public double openAt(int index) {
                return index == 0 ? 118_000 : 118_010;
            }

            @Override
            public double highAt(int index) {
                return index == 0 ? 118_002 : 118_011;
            }

            @Override
            public double lowAt(int index) {
                return index == 0 ? 118_000 : 118_010;
            }

            @Override
            public double closeAt(int index) {
                return index == 0 ? 118_002 : 118_011;
            }
        };
    }
    @Test
    @DisplayName("a session filed in the wrong month is not offered")
    void aMisplacedSessionIsNotListed(@TempDir Path folder) throws IOException {
        // Otherwise the tree would show a day the replay cannot open: the
        // listing walks the folders and the lookup computes the path, and the
        // two would be answering different questions. One offered session is
        // one playable session.
        session(folder, LocalDate.of(2021, 1, 4), 118_000);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        assertEquals(List.of(LocalDate.of(2021, 1, 4)), library.exported());

        Path where = TickSource.METATRADER.fileFor(folder, "winfut", LocalDate.of(2021, 1, 4));
        Path wrong = folder.resolve("2021").resolve("02").resolve(where.getFileName());

        Files.createDirectories(wrong.getParent());
        Files.move(where, wrong);

        assertTrue(library.exported().isEmpty(),
                "a session under the wrong month was offered as playable");
        assertFalse(library.has(LocalDate.of(2021, 1, 4)));
    }

    @Test
    @DisplayName("o caminho comeca na abertura da barra e termina no fechamento dela")
    void theEndsAreTheBarsOwn(@TempDir Path folder) throws Exception {
        // The contract TickPath publishes -- "opening price first and closing
        // price last" -- which SyntheticTicks honoured and this did not. The
        // candles are folded from the minute base and the ticks come from a
        // separate export: two sources with no reason to agree on the ends of a
        // minute. The forming bar therefore animated up to the last recorded
        // trade and SNAPPED to the stored close the instant it completed, once a
        // bar, all session, on exactly the days whose ticks we have.
        LocalDate date = LocalDate.of(2021, 1, 4);

        session(folder, date, 118_000);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            library.request(date);
            settle(library, 1);

            RecordedTicks ticks = new RecordedTicks(library, null);

            // Deliberately at odds with the trades, which run 118_000..118_004.
            PriceSeries bars = oneMinuteAt(date, ZONE, 117_900, 118_100);
            double[] path = ticks.pathFor(bars, 0);

            assertEquals(117_900, path[0], 1e-9,
                    "the path did not open at the bar's open");
            assertEquals(118_100, path[path.length - 1], 1e-9,
                    "the path did not close at the bar's close");

            // And no trade was thrown away to make room: five recorded, seven
            // in the path. Overwriting the ends would lose two real prices, and
            // either of them can be the bar's high or its low.
            assertEquals(7, path.length, java.util.Arrays.toString(path));
            assertEquals(118_000, path[1], 1e-9);
            assertEquals(118_004, path[5], 1e-9);
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("os ticks e as barras leem a MESMA meia-noite")
    void theTicksAndTheBarsShareOneZone(@TempDir Path folder) throws Exception {
        // A tick file stores a day and a count of milliseconds since ITS
        // midnight -- local wall time with no zone in it -- so whoever reads it
        // decides which midnight that was. RecordedTicks was handed a zone and
        // used it to pick the FILE; the session inside computed its midnight in
        // the machine's zone regardless. On any machine not set to the market
        // the two disagreed by the offset between them, the bar's window caught
        // no ticks at all, and every bar fell through to the synthetic walk --
        // in silence, because "no ticks for this bar" is a normal answer.
        //
        // Fourteen hours ahead of anywhere in Brazil, so the two readings are
        // not merely different instants but different days.
        ZoneId was = Timeframe.defaultZone();
        ZoneId far = ZoneId.of("Pacific/Kiritimati");
        LocalDate date = LocalDate.of(2021, 1, 4);

        session(folder, date, 118_000);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            Timeframe.useZone(far);

            library.request(date);
            settle(library, 1);

            RecordedTicks ticks = new RecordedTicks(library, null);
            PriceSeries bars = oneMinuteAt(date, far, 118_000, 118_004);

            assertTrue(ticks.isRecorded(bars, 0), "the session was not even found");
            assertNotNull(ticks.pathFor(bars, 0),
                    "the session was found and none of its ticks fell inside the bar: "
                            + "the file and the bars are reading two different midnights");
        } finally {
            Timeframe.useZone(was);
            library.close();
        }
    }

    /** One bar of a minute, 09:00 on that date in that zone. */
    private static PriceSeries oneMinuteAt(LocalDate date, ZoneId zone,
            double open, double close) {
        long at = date.atStartOfDay(zone).toInstant().toEpochMilli() + 9 * 3_600_000L;

        return new PriceSeries() {

            @Override
            public int size() {
                return 1;
            }

            @Override
            public long timeAt(int index) {
                return at;
            }

            @Override
            public double openAt(int index) {
                return open;
            }

            @Override
            public double highAt(int index) {
                return Math.max(open, close);
            }

            @Override
            public double lowAt(int index) {
                return Math.min(open, close);
            }

            @Override
            public double closeAt(int index) {
                return close;
            }
        };
    }

    @Test
    @DisplayName("uma varredura que falha PELO MEIO guarda o que ja tinha achado")
    void aFailedScanKeepsWhatItFound(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path folder) throws IOException {
        // A subdirectory with no permission, a circular link, a network volume
        // that dropped. None of them means "nothing was exported", which is what
        // an empty list says -- and the walk is LAZY, so the failure arrives
        // after some sessions have already been listed.
        //
        // THIS USED TO READ THE SOURCE, and matched three substrings inside it.
        // Collections.emptyList(), new ArrayList<>(), or List.of( ) with a space
        // all put the defect straight back and passed all three. The walk is
        // handed in now, so the halfway failure can simply be arranged.
        // The two good ones are WRITTEN: a session is recognised by reading its
        // header, not by the name of the file, so a path that leads nowhere is
        // not a session and would have made this pass for the wrong reason.
        java.time.LocalDate first = java.time.LocalDate.of(2021, 1, 4);
        java.time.LocalDate second = java.time.LocalDate.of(2021, 1, 5);

        session(folder, first, 100_000);
        session(folder, second, 100_500);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {

            java.util.concurrent.atomic.AtomicInteger seen =
                    new java.util.concurrent.atomic.AtomicInteger();

            java.util.stream.Stream<java.nio.file.Path> walk = java.util.stream.Stream
                    .of(library.fileFor(first), library.fileFor(second),
                            library.fileFor(java.time.LocalDate.of(2021, 1, 6)))
                    .map(each -> {
                        if (seen.incrementAndGet() == 3) {
                            throw new java.io.UncheckedIOException(
                                    new IOException("the volume went away"));
                        }

                        return each;
                    });

            assertEquals(java.util.List.of(first, second), library.daysIn(walk),
                    "a walk that failed halfway threw away the sessions it had already "
                            + "found: the tree shows fewer playable days than exist, and "
                            + "nothing says why");
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("e a falha do meio e UncheckedIOException, que o catch antigo nunca via")
    void themidwalkFailureIsUnchecked(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path folder) {
        // Files.walk is lazy, and the stream wraps what the traversal throws in
        // UncheckedIOException -- a RuntimeException, never an IOException. The
        // catch that sat below the walk could only ever see the failure to START
        // it, and the case its own comment described went straight past it, out
        // of exported(), and took the tree build with it.
        //
        // Asserted as "does not throw", which is the whole of it: before, this
        // came out of the method.
        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            java.util.stream.Stream<java.nio.file.Path> walk = java.util.stream.Stream
                    .<java.nio.file.Path>generate(() -> {
                        throw new java.io.UncheckedIOException(
                                new IOException("permission denied"));
                    });

            assertTrue(library.daysIn(walk).isEmpty(),
                    "a walk that failed before finding anything did not answer empty");
        } finally {
            library.close();
        }
    }
}
