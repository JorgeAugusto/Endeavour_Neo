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

import br.com.jorge.reis.endeavourneo.domain.market.MarketFile;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * A base for the replay tests to play.
 *
 * <p>Here because the replay stopped inventing its candles. It used to answer
 * every date with a random walk, so a test could ask for any day and get bars;
 * now it reads the source, and a test that supplies no source is replaying
 * nothing. Which is correct, and which is why these tests need a base of their
 * own rather than the reader's.</p>
 */
final class ReplayBase {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /**
     * Minutes in a session: 09:00 to 18:24, which is what the source has.
     *
     * <p>Not a round number chosen for convenience. A test that asserts the
     * clock reads the close is asserting against THIS, and a fixture with a
     * session of its own invention would make that test about the fixture.</p>
     */
    private static final int MINUTES = 565;

    private ReplayBase() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * Writes a base of weekday sessions and points {@link SeriesCatalog} at it.
     *
     * @param folder where to write; a temporary directory
     * @param around a date the range should contain
     * @return the base's name, for the session to ask for
     */
    static String at(Path folder, LocalDate around) throws IOException {
        List<Long> times = new ArrayList<>();
        LocalDate day = around.minusDays(20);

        for (int i = 0; i < 40; i++, day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY
                    || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }

            long open = LocalDateTime.of(day, LocalTime.of(9, 0))
                    .atZone(ZONE).toInstant().toEpochMilli();

            for (int minute = 0; minute < MINUTES; minute++) {
                times.add(open + minute * 60_000L);
            }
        }

        long[] stamps = new long[times.size()];

        for (int i = 0; i < stamps.length; i++) {
            stamps[i] = times.get(i);
        }

        SeriesCatalog.useFolderForTest(folder);

        // Through fileOf: the folders are the tree now, and one rule says
        // where a series goes. A fixture with a path of its own would go on
        // passing the day that rule moved.
        Path file = SeriesCatalog.fileOf("winfull-1m");

        Files.createDirectories(file.getParent());
        MarketFile.write(file, walk(stamps), 1);
        SeriesCatalog.forget();

        // AND the feed's own map of playable days, which is static and which the
        // tests using this fixture only clear on the way OUT. The first test of a
        // class therefore inherited whatever the class before it left -- and what
        // it left could be the days of the reader's REAL series, reached through
        // the catalogue's fallback search. It surfaced the moment the suite
        // stopped sharing the reader's settings: a fixture of plain weekdays was
        // being matched against a real September, which is missing Independence
        // Day because the market was shut.
        //
        // Cleared here, because here is where a test says "this is the base now".
        ReplayFeed.forget();

        return "winfull-1m";
    }

    /** Puts everything back, so the next test does not inherit this folder. */
    static void release() {
        SeriesCatalog.useFolderForTest(null);
        SeriesCatalog.forget();
    }

    /** A gentle zig-zag, so bars differ from one another without being noise. */
    private static PriceSeries walk(long[] times) {
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
                return 100_000 + (index % 50) * 5;
            }

            @Override
            public double highAt(int index) {
                return openAt(index) + 30;
            }

            @Override
            public double lowAt(int index) {
                return openAt(index) - 30;
            }

            @Override
            public double closeAt(int index) {
                return openAt(index) + 10;
            }

            @Override
            public double volumeAt(int index) {
                return 100;
            }
        };
    }
}
