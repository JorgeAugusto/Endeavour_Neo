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
package br.com.jorge.reis.endeavourneo.ui.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.MetaTraderTicks;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.TickFile;
import br.com.jorge.reis.endeavourneo.domain.market.TickLibrary;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When a renko is allowed to be drawn.
 */
@DisplayName("Renko source")
class RenkoSourceTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static void session(Path folder, LocalDate date) throws IOException {
        try (TickFile.Writer writer = new TickFile.Writer(
                MetaTraderTicks.fileFor(folder, "win", date), date)) {

            writer.add(9 * 3_600_000, 0, 0, 118_000, 1, 88,
                    TickFile.Writer.mask(false, false, true, true));
        }
    }

    /** One bar an hour, from 09:00, over the days given. */
    private static PriceSeries over(LocalDate... days) {
        long[] times = new long[days.length * 3];
        int at = 0;

        for (LocalDate day : days) {
            long open = day.atStartOfDay(ZONE).toInstant().toEpochMilli() + 9 * 3_600_000L;

            for (int hour = 0; hour < 3; hour++) {
                times[at++] = open + hour * 3_600_000L;
            }
        }

        return new PriceSeries() {

            @Override
            public int size() {
                return times.length;
            }

            @Override
            public long timeAt(int index) {
                return times[index];
            }

            @Override
            public double openAt(int index) {
                return 118_000;
            }

            @Override
            public double highAt(int index) {
                return 118_010;
            }

            @Override
            public double lowAt(int index) {
                return 117_990;
            }

            @Override
            public double closeAt(int index) {
                return 118_005;
            }
        };
    }

    @Test
    @DisplayName("while missing ticks may be filled in, renko is always offered")
    void fillingInAllowsAnything(@TempDir Path folder) {
        // The default, and it has to be: one month of the base has ticks and
        // eight years do not.
        TickLibrary library = new TickLibrary(folder, "win");

        try {
            assertTrue(RenkoSource.allows(over(LocalDate.of(2024, 5, 6)), library, true));
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("with filling in turned off, a day with no export refuses renko")
    void withoutTicksItIsRefused(@TempDir Path folder) {
        TickLibrary library = new TickLibrary(folder, "win");

        try {
            assertFalse(RenkoSource.allows(over(LocalDate.of(2024, 5, 6)), library, false),
                    "a renko was offered for a day with no ticks to build it from");
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("with filling in off, every session on screen must have ticks -- not just one")
    void oneSessionShortIsStillRefused(@TempDir Path folder) throws IOException {
        // The case that matters. A renko built partly from ticks and partly
        // from candles would change density halfway across and look like the
        // market did it: measured, the two differ by 8% to 27%.
        session(folder, LocalDate.of(2021, 1, 4));
        session(folder, LocalDate.of(2021, 1, 5));

        TickLibrary library = new TickLibrary(folder, "win");

        try {
            assertTrue(RenkoSource.allows(
                    over(LocalDate.of(2021, 1, 4), LocalDate.of(2021, 1, 5)), library, false),
                    "both days are exported and it was still refused");

            assertFalse(RenkoSource.allows(
                    over(LocalDate.of(2021, 1, 4), LocalDate.of(2021, 1, 6)), library, false),
                    "the 6th has no ticks, and the renko was offered anyway");
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("the sessions are found by market, not by which export is on screen")
    void theSessionsBelongToTheMarket() {
        // win-2021-01-04.bin is read by a chart of winfull, of winn or of
        // winfut alike: the ticks of a day belong to the exchange, not to the
        // export that stitched them.
        assertEquals("win", RenkoSource.rootOf("winfull-1m"));
        assertEquals("win", RenkoSource.rootOf("winn-1m"));
        assertEquals("win", RenkoSource.rootOf("winfut-1m"));
        assertEquals("", RenkoSource.rootOf(null));
    }

    @Test
    @DisplayName("an empty chart refuses rather than claiming it has everything")
    void nothingOnScreenIsNotEverything(@TempDir Path folder) {
        // A series of no bars trivially has ticks for every session it shows,
        // which is true and useless. Refusing is the answer that cannot be read
        // as "yes, this is built from ticks".
        TickLibrary library = new TickLibrary(folder, "win");

        try {
            assertFalse(RenkoSource.allows(over(), library, false));
            assertFalse(RenkoSource.allows(null, library, false));
        } finally {
            library.close();
        }
    }
}
