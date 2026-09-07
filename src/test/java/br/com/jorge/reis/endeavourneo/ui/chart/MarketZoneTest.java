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
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.Aggressor;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.TapeFile;
import br.com.jorge.reis.endeavourneo.domain.market.TickLibrary;
import br.com.jorge.reis.endeavourneo.domain.market.TickSource;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The interface asking what day it is, in the market's calendar.
 *
 * <h2>Why the existing tests could not catch this</h2>
 *
 * <p>Every test under {@code ui} fixes {@code ZONE = ZoneId.systemDefault()} —
 * the same expression the product read. Two copies of one mistake agree, so no
 * test in that package could ever fail because of a zone, while the domain tests
 * change the zone for real.</p>
 *
 * <p>The launcher sets the market's calendar from {@code data.zone}, and the
 * author's machine is in it, so the whole class of defect is invisible where it
 * is written and real everywhere else.</p>
 */
@DisplayName("O fuso do mercado, visto da interface")
class MarketZoneTest {

    /**
     * Far enough east that the session falls on ANOTHER DATE entirely.
     *
     * <p>Fifteen hours ahead of Sao Paulo: 09:00 there is midnight here, so a
     * morning session is wholly the following date and the two calendars name
     * DISJOINT days. That is the shape this needs.</p>
     *
     * <p>The first draft used Brisbane, ten hours east, where the session merely
     * straddles local midnight — so the machine's single day is a SUBSET of the
     * market's two, the broken guard finds it in the exported set, and the test
     * passes with the defect in place. It did, and breaking the product on
     * purpose is what showed it.</p>
     */
    private static final ZoneId FAR = ZoneId.of("Pacific/Auckland");

    /** One session of bars, 09:00 to 12:00 in Sao Paulo on that date. */
    private static PriceSeries session(LocalDate day) {
        long open = day.atStartOfDay(ZoneId.of("America/Sao_Paulo"))
                .toInstant().toEpochMilli() + 9 * 3_600_000L;

        return new PriceSeries() {

            @Override
            public int size() {
                return 180;
            }

            @Override
            public long timeAt(int index) {
                return open + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return 179_000 + index;
            }

            @Override
            public double highAt(int index) {
                return 179_000 + index;
            }

            @Override
            public double lowAt(int index) {
                return 179_000 + index;
            }

            @Override
            public double closeAt(int index) {
                return 179_000 + index;
            }
        };
    }

    private static void tape(Path ticks, LocalDate day) throws IOException {
        try (TapeFile.Writer writer = new TapeFile.Writer(
                TickSource.PROFIT.fileFor(ticks, "win", day), day)) {

            writer.broker(3, "XP");
            writer.add(9 * 3_600_000, 179_385, 1, 3, 3, Aggressor.BUYER);
        }
    }

    @Test
    @DisplayName("a guarda do renko e o calendario concordam sobre que dias a serie cobre")
    void theGuardAndTheCalendarAgreeOnTheDays(@TempDir Path folder) throws IOException {
        // RenkoSource.allows walks the series asking what day each bar is on, and
        // RenkoSource.sessionsIn asks the same question four methods below
        // through Sessions.of. One of them used the machine's calendar and the
        // other the market's, so they answered differently about the same bars --
        // and allows is the guard that decides whether the tick renko may exist
        // at all.
        ZoneId was = Timeframe.defaultZone();

        try {
            Timeframe.useZone(FAR);

            LocalDate here = LocalDate.of(2026, 9, 1);
            PriceSeries bars = session(here);
            List<LocalDate> days = RenkoSource.sessionsIn(bars);

            // ANOTHER DATE, not merely another split of the same one: fifteen
            // hours east, a morning in Sao Paulo is wholly the next day. If the
            // market's days included the machine's, a guard reading the wrong
            // calendar would still find its day in the exported set and this
            // would pass with the defect in place.
            assertEquals(List.of(here.plusDays(1)), days,
                    "the market calendar did not name a different date: " + days);

            // Exactly the days the calendar named, and nothing else on disk.
            Path ticks = folder.resolve("win").resolve("ticks");

            for (LocalDate day : days) {
                tape(ticks, day);
            }

            try (TickLibrary library = new TickLibrary(ticks, "win", TickSource.PROFIT)) {
                assertTrue(RenkoSource.allows(bars, library, false),
                        "every day the calendar named has ticks, and the guard "
                                + "still refused: it is asking a different calendar");
            }
        } finally {
            Timeframe.useZone(was);
        }
    }
}
