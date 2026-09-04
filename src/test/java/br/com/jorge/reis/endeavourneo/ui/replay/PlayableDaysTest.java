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

import br.com.jorge.reis.endeavourneo.domain.market.Aggressor;
import br.com.jorge.reis.endeavourneo.domain.market.TapeFile;
import br.com.jorge.reis.endeavourneo.domain.market.TickSource;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

import java.io.IOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which days a feed can play, and what the calendar does with that.
 */
@DisplayName("Playable days")
class PlayableDaysTest {

    @AfterEach
    void putEverythingBack() {
        ReplayFeed.forget();
        ReplayBase.release();
    }

    private static void tape(Path ticks, LocalDate day) throws IOException {
        try (TapeFile.Writer writer = new TapeFile.Writer(
                TickSource.PROFIT.fileFor(ticks, "win", day), day)) {

            writer.broker(3, "XP");
            writer.add(9 * 3_600_000, 200, 1, 3, 3, Aggressor.BUYER);
        }
    }

    @Test
    @DisplayName("a bar feed offers the days its series holds, and no others")
    void barFeedOffersItsOwnSessions(@TempDir Path folder) throws IOException {
        String series = ReplayBase.at(folder, LocalDate.of(2021, 3, 10));

        NavigableSet<LocalDate> days = ReplayFeed.of(series).sessions();

        assertFalse(days.isEmpty(), "the series offered no day at all");

        // The fixture writes weekdays only. A weekend inside its range is the
        // check that matters: it is a day the calendar shows and the feed
        // cannot play, which is the whole point of asking.
        for (LocalDate day = days.first(); !day.isAfter(days.last()); day = day.plusDays(1)) {
            boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY
                    || day.getDayOfWeek() == DayOfWeek.SUNDAY;

            assertEquals(!weekend, days.contains(day), day + " is offered wrongly");
        }
    }

    @Test
    @DisplayName("a tape feed offers only the sessions that were exported")
    void tapeFeedOffersOnlyWhatWasExported(@TempDir Path folder) throws IOException {
        // Eight sessions against six years is the real shape of this: almost
        // the whole calendar is a day the tape cannot play, and saying so is
        // better than finding out by pressing Requisitar.
        ReplayBase.at(folder, LocalDate.of(2026, 9, 1));

        Path ticks = SeriesCatalog.ticksOf("win");

        tape(ticks, LocalDate.of(2026, 9, 1));
        tape(ticks, LocalDate.of(2026, 9, 3));

        NavigableSet<LocalDate> days = ReplayFeed.of("win", TickSource.PROFIT).sessions();

        assertEquals(new TreeSet<>(java.util.List.of(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3))), days);

        // The second of September is a Wednesday the market traded. It is not
        // offered because this FEED has no tape for it -- which is a different
        // question from whether the market was open, and the right one.
        assertFalse(days.contains(LocalDate.of(2026, 9, 2)),
                "a day with no tape was offered as playable");
    }

    @Test
    @DisplayName("the calendar accepts what it was given, and weekdays when it was given nothing")
    void theCalendarFollowsTheFeed() {
        DatePicker picker = new DatePicker(LocalDate.of(2026, 9, 1));

        // Nothing said yet: weekdays, which is the state before a feed is
        // chosen. Greying the whole calendar then would be a lie about the data
        // rather than a fact about it.
        assertTrue(picker.accepts(LocalDate.of(2026, 9, 2)), "a Wednesday was refused");
        assertFalse(picker.accepts(LocalDate.of(2026, 9, 5)), "a Saturday was accepted");

        picker.setSessions(new TreeSet<>(java.util.List.of(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3))));

        assertTrue(picker.accepts(LocalDate.of(2026, 9, 1)));
        assertFalse(picker.accepts(LocalDate.of(2026, 9, 2)),
                "a weekday with no session was still accepted");

        picker.setSessions(null);

        assertTrue(picker.accepts(LocalDate.of(2026, 9, 2)),
                "clearing the sessions did not go back to weekdays");
    }

    @Test
    @DisplayName("the days are worked out once and remembered")
    void theAnswerIsHeld(@TempDir Path folder) throws IOException {
        // Measured at 39-102 ms for the six-year source. Once is nothing; on
        // every repaint of a calendar it is a stutter, so the same set must
        // come back rather than be walked again.
        String series = ReplayBase.at(folder, LocalDate.of(2021, 3, 10));
        ReplayFeed feed = ReplayFeed.of(series);

        NavigableSet<LocalDate> first = feed.sessions();
        NavigableSet<LocalDate> again = ReplayFeed.of(series).sessions();

        assertTrue(first == again, "the walk was done a second time");

        ReplayFeed.forget();

        assertFalse(first == ReplayFeed.of(series).sessions(),
                "forgetting did not drop what was held");
    }
}
