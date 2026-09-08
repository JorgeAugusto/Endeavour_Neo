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
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the transport lets go of, and when.
 *
 * <p>Three leaks that all look the same from outside: something registered and
 * never unregistered, something cached and never dropped, something added on
 * every call where it should be added once. None of them shows on screen until
 * it does.</p>
 */
@DisplayName("Limpeza do replay")
class ReplayHousekeepingTest {

    /** One session of tape, so a day exists on disk for that market. */
    private static void tape(Path ticks, LocalDate day) throws IOException {
        try (br.com.jorge.reis.endeavourneo.domain.market.TapeFile.Writer writer =
                     new br.com.jorge.reis.endeavourneo.domain.market.TapeFile.Writer(
                             br.com.jorge.reis.endeavourneo.domain.market.TickSource.PROFIT
                                     .fileFor(ticks, "win", day), day)) {

            writer.broker(3, "XP");
            writer.add(9 * 3_600_000, 200, 1, 3, 3,
                    br.com.jorge.reis.endeavourneo.domain.market.Aggressor.BUYER);
        }
    }

    @AfterEach
    void putEverythingBack() {
        ReplayFeed.forget();
        ReplayBase.release();
        SeriesCatalog.useFolderForTest(null);
    }

    @Test
    @DisplayName("um grafico que se soltou sai da lista de avisar-quando-acabar")
    void aChartThatLetGoIsNotToldTheSessionEnded(@TempDir Path folder) throws IOException {
        // The watchers had a way out -- forget -- and the endings did not. A
        // chart that closed, or had the replay detached, stayed on the list
        // until the session itself stopped, holding a window that is gone and a
        // callback that will run into it.
        String series = ReplayBase.at(folder, LocalDate.of(2021, 3, 10));
        ReplaySession session = new ReplaySession(series, LocalDate.of(2021, 3, 10));

        try {
            AtomicInteger told = new AtomicInteger();
            Runnable ending = told::incrementAndGet;

            session.whenEnded(ending);
            session.forgetEnding(ending);
            session.stop();

            assertEquals(0, told.get(),
                    "the session told a chart that had already let go");
        } finally {
            session.stop();
        }
    }

    @Test
    @DisplayName("importar um pregao novo aparece no calendario sem reiniciar")
    void anImportedSessionShowsUpInTheCalendar(@TempDir Path folder) throws IOException {
        // The playable-days cache says in its own javadoc that it is dropped
        // "for when the series on disk change" -- and that moment called it from
        // nowhere in src/main at all, only from a test. Importing a session,
        // exporting a tape or rebuilding a series left the calendar showing
        // yesterday for the life of the application.
        SeriesCatalog.useFolderForTest(folder);

        Path ticks = SeriesCatalog.ticksOf("win");

        tape(ticks, LocalDate.of(2026, 9, 1));

        // Warmed the way the launcher warms it, which is also what registers
        // the calendar to follow the catalog.
        ReplayFeed.warm();

        int before = ReplayFeed.of("win",
                br.com.jorge.reis.endeavourneo.domain.market.TickSource.PROFIT)
                .sessions().size();

        assertEquals(1, before, "the fixture did not offer its one session");

        // A day arrives on disk, the way importing a tape puts one there.
        tape(ticks, LocalDate.of(2026, 9, 2));

        SeriesCatalog.forget();

        assertEquals(2, ReplayFeed.of("win",
                        br.com.jorge.reis.endeavourneo.domain.market.TickSource.PROFIT)
                        .sessions().size(),
                "the calendar did not notice a session that arrived on disk");
    }

    @Test
    @DisplayName("trocar o ouvinte do calendario nao empilha outro")
    void changingTheListenerDoesNotStackAnother() {
        // onChange replaced the runnable AND added another document listener, so
        // the change was reported as many times as onChange had ever been
        // called: twice after the second call, three times after the third.
        // Counted against a picker whose listener was set ONCE. setText fires
        // remove-then-insert, so any picker reports the change twice; what is
        // being pinned is that calling onChange again does not multiply that.
        AtomicInteger once = new AtomicInteger();
        DatePicker settled = new DatePicker(LocalDate.of(2021, 3, 10));

        settled.onChange(once::incrementAndGet);
        settled.field().setText("11/03/2021");

        AtomicInteger thrice = new AtomicInteger();
        DatePicker swapped = new DatePicker(LocalDate.of(2021, 3, 10));

        swapped.onChange(() -> { });
        swapped.onChange(() -> { });
        swapped.onChange(thrice::incrementAndGet);
        swapped.field().setText("11/03/2021");

        // Or nought equals nought and this says nothing. The break it is meant
        // to catch -- onChange keeping the Runnable and never registering the
        // DocumentListener, so typing a date does nothing at all -- leaves both
        // counters at zero.
        assertTrue(once.get() > 0, "the fixture never reported a change at all");

        assertEquals(once.get(), thrice.get(),
                "setting the listener three times reported the change "
                        + thrice.get() + " times against " + once.get());
    }
}
