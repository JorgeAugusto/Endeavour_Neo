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
    @DisplayName("a data lembrada de outro feed nao sobrevive a abertura do transporte")
    void arememberedDateFromAnotherFeedDoesNotSurvive(@TempDir Path folder)
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                java.awt.GraphicsEnvironment.isHeadless(), "no graphics environment");

        // followFeed exists to move the pickers onto a day the chosen feed
        // actually has -- its javadoc says why: "switching from six years of
        // minutes to a tape of eight sessions leaves both pickers holding a day
        // that feed has never heard of". In the constructor it ran BEFORE the
        // remembered dates were restored, and those three lines then wrote over
        // its answer without asking the feed anything.
        //
        // Reopening the transport on a tape of two sessions with a date
        // remembered from 2021: the calendar came up all grey and the field held
        // a day that feed has never had. Nothing downstream corrected it -- the
        // range fitter only fits the end to the start, and requestDay checks
        // "unreadable" and "end before start" and nothing else.
        ReplayBase.at(folder, LocalDate.of(2026, 9, 1));

        Path ticks = SeriesCatalog.ticksOf("win");

        tape(ticks, LocalDate.of(2026, 9, 1));
        tape(ticks, LocalDate.of(2026, 9, 3));

        ReplayFeed tape = ReplayFeed.of("win", TickSource.PROFIT);

        var workspace = br.com.jorge.reis.endeavourneo.platform.Settings.workspace();

        workspace.put("replay.feed", tape.saved());
        workspace.put("replay.from", "2021-01-04");
        workspace.put("replay.to", "2021-01-04");

        ReplayPanel[] panel = new ReplayPanel[1];

        javax.swing.SwingUtilities.invokeAndWait(() -> panel[0] = new ReplayPanel());

        try {
            java.util.List<DatePicker> pickers = pickersIn(panel[0]);

            assertTrue(pickers.size() >= 2,
                    "the fixture is wrong: the transport has " + pickers.size()
                            + " date pickers");

            LocalDate showing = pickers.get(0).date();

            assertTrue(tape.sessions().contains(showing),
                    "the transport opened holding " + showing + ", which this tape has "
                            + "never had: the calendar is all grey and the button is lit "
                            + "and dead, with nothing saying why");
        } finally {
            javax.swing.SwingUtilities.invokeAndWait(panel[0]::release);

            workspace.remove("replay.feed");
            workspace.remove("replay.from");
            workspace.remove("replay.to");
        }
    }

    private static java.util.List<DatePicker> pickersIn(java.awt.Container where) {
        java.util.List<DatePicker> found = new java.util.ArrayList<>();

        for (java.awt.Component each : where.getComponents()) {
            if (each instanceof DatePicker picker) {
                found.add(picker);
            } else if (each instanceof java.awt.Container inside) {
                found.addAll(pickersIn(inside));
            }
        }

        return found;
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
