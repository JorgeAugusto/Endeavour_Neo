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

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What happens to the charts when a replay ends.
 */
@DisplayName("Replay ending")
class ReplayEndsTest {

    /**
     * A base of its own, and this file used not to have one.
     *
     * <p>It was the only one of the ten in this package with no fixture and no
     * catalog of its own, so the session went looking for "WINFUT" in the
     * reader's real data folder -- and what it found depended on which class had
     * run before it and whether that class had released its own. {@code
     * ReplayBase} documents that leak between classes and says it has happened
     * once already.</p>
     */
    @TempDir
    static Path base;

    @BeforeAll
    static void writeASeries() throws IOException {
        ReplayBase.at(base, LocalDate.of(2026, 9, 2));
    }

    @AfterAll
    static void putTheCatalogBack() {
        ReplayBase.release();
    }

    private static ReplaySession session() {
        return new ReplaySession("WINFUT", LocalDate.of(2026, 9, 2), 0);
    }

    @Test
    @DisplayName("every chart following a session is told when it ends")
    void chartsAreToldItEnded() {
        // Otherwise the window sits on a day that stopped moving, with a title
        // still claiming a replay, and has to be closed to become useful again.
        ReplaySession replay = session();
        AtomicInteger given = new AtomicInteger();

        replay.whenEnded(given::incrementAndGet);
        replay.whenEnded(given::incrementAndGet);

        assertEquals(0, given.get(), "nothing may be handed back while it is still playing");

        replay.stop();

        assertEquals(2, given.get(), "a chart following the session was not told");
    }

    @Test
    @DisplayName("ending twice hands the charts back once")
    void endingIsNotRepeated() {
        // The transport can be closed after the session already finished on its
        // own. A chart handed back twice would be handed data it has since
        // replaced.
        ReplaySession replay = session();
        AtomicInteger given = new AtomicInteger();

        replay.whenEnded(given::incrementAndGet);

        replay.stop();
        replay.stop();

        assertEquals(1, given.get(), "the chart was given back twice");
    }

    @Test
    @DisplayName("watchers stop being called once the session has ended")
    void watchersGoQuiet() {
        ReplaySession replay = session();
        AtomicInteger heard = new AtomicInteger();

        replay.watch(heard::incrementAndGet);
        replay.step(1);

        int before = heard.get();

        replay.stop();
        replay.step(1);

        assertEquals(before, heard.get(), "a chart was repainted by a clock that had stopped");
        assertTrue(before > 0, "the fixture never ticked, so the check proved nothing");
    }
}
