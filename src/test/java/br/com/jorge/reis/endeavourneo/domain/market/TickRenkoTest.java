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

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A renko built from ticks, session by session.
 */
@DisplayName("Tick renko")
class TickRenkoTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** A session whose trades walk through the prices given, one a second. */
    private static void session(Path folder, LocalDate date, int... prices) throws IOException {
        try (TickFile.Writer writer = new TickFile.Writer(
                TickSource.METATRADER.fileFor(folder, "winfut", date), date)) {

            for (int i = 0; i < prices.length; i++) {
                writer.add(9 * 3_600_000 + i * 1_000, 0, 0, prices[i], 1, 88,
                        TickFile.Writer.mask(false, false, true, true));
            }
        }
    }

    private static long at(LocalDate date, int second) {
        return date.atStartOfDay(ZONE).toInstant().toEpochMilli()
                + 9 * 3_600_000L + second * 1_000L;
    }

    @Test
    @DisplayName("built day by day, brick for brick the same as one long pass")
    void thePiecesAgreeWithTheWhole(@TempDir Path folder) throws IOException {
        // Prices chosen to lay bricks in both directions and to cross the
        // session boundary mid-run, which is where a lost carry would show.
        int[] monday = {100, 110, 120, 130, 125, 140, 150};
        int[] tuesday = {155, 160, 150, 140, 130, 120, 135};

        session(folder, LocalDate.of(2021, 1, 4), monday);
        session(folder, LocalDate.of(2021, 1, 5), tuesday);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            PriceSeries inPieces = TickRenko.over(new Renko(10, 2), library,
                    List.of(LocalDate.of(2021, 1, 4), LocalDate.of(2021, 1, 5)));

            // The same trades as one series, and one pass of the same renko.
            int[] both = new int[monday.length + tuesday.length];

            System.arraycopy(monday, 0, both, 0, monday.length);
            System.arraycopy(tuesday, 0, both, monday.length, tuesday.length);

            PriceSeries wholeInOne = new Renko(10, 2, true, false).apply(pricesAsBars(both));

            assertEquals(wholeInOne.size(), inPieces.size(),
                    "building it a day at a time laid a different number of bricks");

            for (int i = 0; i < wholeInOne.size(); i++) {
                assertEquals(wholeInOne.openAt(i), inPieces.openAt(i), 1e-9, "open at " + i);
                assertEquals(wholeInOne.closeAt(i), inPieces.closeAt(i), 1e-9, "close at " + i);
                assertEquals(wholeInOne.highAt(i), inPieces.highAt(i), 1e-9, "high at " + i);
                assertEquals(wholeInOne.lowAt(i), inPieces.lowAt(i), 1e-9, "low at " + i);
            }
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("the replay sees only the bricks the clock has reached")
    void nothingFromTheFuture(@TempDir Path folder) throws IOException {
        // The reason addUpTo exists. Folding the whole session in while the
        // replay is halfway through it would put tomorrow's bricks on screen.
        LocalDate day = LocalDate.of(2021, 1, 4);

        session(folder, day, 100, 110, 120, 130, 140, 150, 160);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            TickRenko half = new TickRenko(new Renko(10, 2), library);

            half.addUpTo(day, at(day, 3));

            TickRenko whole = new TickRenko(new Renko(10, 2), library);

            whole.add(day);

            assertTrue(half.size() > 0, "nothing was drawn at all");
            assertTrue(half.size() < whole.size(),
                    "the half-played session drew " + half.size()
                            + " bricks, the same as the whole day");

            // And what it drew is the beginning of what the whole day draws.
            for (int i = 0; i < half.size(); i++) {
                assertEquals(whole.bricks().closeAt(i), half.bricks().closeAt(i), 1e-9,
                        "brick " + i + " is not the one the whole day laid");
            }
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("a session cannot be folded in twice, or out of order")
    void theOrderIsEnforced(@TempDir Path folder) throws IOException {
        // Out of order would put bricks in the wrong sequence AND carry the
        // ruler backwards. Neither is visible in the result: the chart would
        // simply be wrong, quietly.
        session(folder, LocalDate.of(2021, 1, 4), 100, 110, 120);
        session(folder, LocalDate.of(2021, 1, 5), 130, 140);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            TickRenko building = new TickRenko(new Renko(10, 2), library);

            building.add(LocalDate.of(2021, 1, 5));

            assertFalse(building.add(LocalDate.of(2021, 1, 5)), "the same day was folded twice");
            assertThrows(IllegalArgumentException.class,
                    () -> building.add(LocalDate.of(2021, 1, 4)));
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("a session with almost no trades does not reset the ruler")
    void anEmptySessionIsHarmless(@TempDir Path folder) throws IOException {
        // 25/01/2021 has two ticks in the real export. A day like that must not
        // start the next one's bricks over at its own price.
        session(folder, LocalDate.of(2021, 1, 4), 100, 110, 120);
        session(folder, LocalDate.of(2021, 1, 5), 121);
        session(folder, LocalDate.of(2021, 1, 6), 130, 140);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            PriceSeries withGap = TickRenko.over(new Renko(10, 2), library,
                    List.of(LocalDate.of(2021, 1, 4), LocalDate.of(2021, 1, 5),
                            LocalDate.of(2021, 1, 6)));

            for (int i = 1; i < withGap.size(); i++) {
                assertEquals(withGap.closeAt(i - 1), withGap.openAt(i), 1e-9,
                        "brick " + i + " does not start where the one before it closed");
            }
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("only trades become bars; a quote with no trade is not a price")
    void quotesAreNotBars(@TempDir Path folder) throws IOException {
        LocalDate day = LocalDate.of(2021, 1, 4);

        try (TickFile.Writer writer = new TickFile.Writer(
                TickSource.METATRADER.fileFor(folder, "winfut", day), day)) {

            writer.add(9 * 3_600_000, 0, 0, 100, 1, 88,
                    TickFile.Writer.mask(false, false, true, true));
            // A bid moved. Nobody paid it, so it is not a bar.
            writer.add(9 * 3_600_000 + 1_000, 95, 0, 0, 0, 4,
                    TickFile.Writer.mask(true, false, false, false));
            writer.add(9 * 3_600_000 + 2_000, 0, 0, 110, 2, 88,
                    TickFile.Writer.mask(false, false, true, true));
        }

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            TickSeries session = library.load(day);
            TickBars bars = TickBars.of(session);

            assertEquals(3, session.size());
            assertEquals(2, bars.size(), "a quote with no trade became a bar");
            assertEquals(100.0, bars.closeAt(0), 1e-9);
            assertEquals(110.0, bars.closeAt(1), 1e-9);
        } finally {
            library.close();
        }
    }

    /** The same prices as a plain series, one bar each. */
    private static PriceSeries pricesAsBars(int[] prices) {
        return new PriceSeries() {

            @Override
            public int size() {
                return prices.length;
            }

            @Override
            public long timeAt(int index) {
                return index * 1_000L;
            }

            @Override
            public double openAt(int index) {
                return prices[index];
            }

            @Override
            public double highAt(int index) {
                return prices[index];
            }

            @Override
            public double lowAt(int index) {
                return prices[index];
            }

            @Override
            public double closeAt(int index) {
                return prices[index];
            }
        };
    }
}
