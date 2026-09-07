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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.Renko;
import br.com.jorge.reis.endeavourneo.domain.market.TickLibrary;
import br.com.jorge.reis.endeavourneo.domain.market.TickRenko;
import br.com.jorge.reis.endeavourneo.domain.market.TickSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the chart does with the tick library it is growing from.
 *
 * <h2>Two tests that read the source, replaced by two that read the object</h2>
 *
 * <p>The first asked whether the words {@code stopGrowing();} appeared within
 * 600 characters BEFORE the assignment {@code growingFrom = library;} -- which
 * says nothing about being on the same path, and which {@code indexOf} would go
 * on answering about the FIRST assignment for ever, so a second one added
 * elsewhere would never be looked at. The library it was about holds a reading
 * thread and up to three sessions of ticks, 340 MB, for the life of the
 * application.</p>
 *
 * <p>The second matched three strings inside a 2.600-character slice of {@code
 * extendBricks}. None of them said WHEN the calls happen or what is done with
 * the answer, and one of them pinned the text of a condition -- which then
 * passes for any body at all inside it.</p>
 *
 * <p>Both are now one door each, and the assertion is about what comes out.</p>
 */
@DisplayName("A biblioteca que o grafico faz crescer")
class GrowingLibraryTest {

    private static final LocalDate DAY = LocalDate.of(2021, 1, 4);

    @Test
    @DisplayName("trocar a biblioteca FECHA a que estava")
    void swappingTheLibraryClosesTheOldOne(@TempDir Path folder) {
        ChartCanvas canvas = new ChartCanvas();

        TickLibrary first = new TickLibrary(folder, "win", TickSource.METATRADER);
        TickLibrary second = new TickLibrary(folder, "win", TickSource.METATRADER);

        try {
            canvas.growFrom(new TickRenko(new Renko(55, 2), first), first);

            assertSame(first, canvas.growingLibrary(), "the first library never went in");

            canvas.growFrom(new TickRenko(new Renko(55, 2), second), second);

            assertTrue(first.isClosed(),
                    "the earlier library was dropped on the floor: a reading thread and "
                            + "up to three sessions of ticks, 340 MB, held for the life of "
                            + "the application");
            assertFalse(second.isClosed(), "the library that just went in was closed");
            assertSame(second, canvas.growingLibrary(), "the second library never went in");
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    @DisplayName("o replay pede o pregao de hoje E o de amanha, antes de precisar")
    void thereplayAsksForTodayAndTomorrow(@TempDir Path folder) throws Exception {
        // TickRenko.advance calls load(), which reads the session from disk when
        // it is not resident -- ninety megabytes, on the interface thread, at
        // the instant the replay crosses midnight. Asking on the loader thread,
        // ahead of the clock, is what keeps that off the interface thread.
        //
        // Measured on a REAL library, because TickLibrary is final and because
        // what matters is not that request() was called -- it is that both
        // sessions end up in memory without anyone having waited for them.
        //
        // AND THAT IS WHY IT IS WRITTEN THIS WAY. The report that asked for this
        // test asked for a different one: that request(day.plusDays(1)) is
        // called, "so midnight blocks" without it. It does not. TickLibrary
        // .request already queues the day, the one after and the one before, so
        // dropping the second call leaves both sessions resident and the
        // property intact -- a test written to that sentence would have been
        // guarding a line that does not carry the guarantee, which is the same
        // mistake as reading the source, one level up.
        session(folder, DAY);
        session(folder, DAY.plusDays(1));

        TickLibrary library = new TickLibrary(
                folder.resolve("win").resolve("ticks"), "win", TickSource.METATRADER);

        try {
            ChartCanvas.requestAround(library, DAY);

            assertEquals(List.of(DAY, DAY.plusDays(1)), resident(library),
                    "the replay did not bring in today AND tomorrow: at midnight the "
                            + "session is read on the interface thread instead");
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("e nao estoura quando nao ha biblioteca nenhuma")
    void itasksNothingWhenThereIsNoLibrary() {
        // A chart with no replay attached reaches this on every frame.
        ChartCanvas.requestAround(null, DAY);
    }

    /** @return the days the library has actually got in memory, in order */
    private static List<LocalDate> resident(TickLibrary library) throws InterruptedException {
        for (int tries = 0; tries < 200 && library.residentCount() < 2; tries++) {
            Thread.sleep(25L);
        }

        List<LocalDate> days = new ArrayList<>(library.residentDays());

        java.util.Collections.sort(days);

        return days;
    }

    /** One session of ticks, enough to be read back. */
    private static void session(Path folder, LocalDate day) throws java.io.IOException {
        Path file = TickSource.METATRADER
                .fileFor(folder.resolve("win").resolve("ticks"), "win", day);

        try (br.com.jorge.reis.endeavourneo.domain.market.TickFile.Writer writer =
                     new br.com.jorge.reis.endeavourneo.domain.market.TickFile.Writer(
                             file, day)) {

            for (int i = 0; i < 200; i++) {
                writer.add(9 * 3_600_000 + i * 1_000, 0, 0, 100_000 + i, 1, 88,
                        br.com.jorge.reis.endeavourneo.domain.market.TickFile.Writer
                                .mask(false, false, true, true));
            }
        }
    }
}
