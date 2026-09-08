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
package br.com.jorge.reis.endeavourneo;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether the suite is quietly running with a large part of itself switched off.
 *
 * <p>Nine files ask {@code assumeFalse(GraphicsEnvironment.isHeadless())}, and
 * an assumption that does not hold SKIPS the test -- which reports as green.
 * Between them they carry the window tests that guard defects the use of the
 * program reported: every chart open at closing time comes back, a closed window
 * does not return on the next launch, a series that will not read draws nothing,
 * and a chart opened for a series that is gone carries the name it really
 * opened. Plus the listener leak and the single-series-window rule.</p>
 *
 * <p>Today the project is built with a display, so none of that is switched off.
 * The headless path is a declared goal, though -- the layer boundary exists,
 * says its own test, because it "is what lets the same code run headless" --
 * and on the day it arrives those tests would disappear without a word.</p>
 *
 * <p>So this one FAILS instead of skipping. Somebody who deliberately runs
 * without a display says so with {@code -Dendeavour.allowHeadless=true} and
 * knows what they are giving up; somebody who ends up headless by accident, on
 * a build agent or over ssh, is told.</p>
 */
@DisplayName("Guarda do modo sem tela")
class HeadlessGuardTest {

    @Test
    @DisplayName("rodar sem tela e uma escolha declarada, nao um silencio")
    void headlessIsAChoiceSomebodyMade() {
        if (Boolean.getBoolean("endeavour.allowHeadless")) {
            return;
        }

        assertFalse(GraphicsEnvironment.isHeadless(),
                "the suite is running without a display, so every test that opens a "
                        + "window is being SKIPPED -- and a skipped test reports green. "
                        + "Run with a display, or say -Dendeavour.allowHeadless=true to "
                        + "accept that those are not being checked");
    }

    @Test
    @DisplayName("a guarda cobre todo assumeFalse de headless que existir")
    void everyHeadlessAssumptionIsCoveredHere() throws IOException {
        // The guard above is worth exactly as much as the list of places it
        // speaks for. Counted from the source, so a tenth file added next year
        // is either covered by this or shows up here as a surprise.
        List<String> found = new ArrayList<>();

        try (Stream<Path> tree = Files.walk(Path.of("src", "test", "java"))) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains("isHeadless()")) {
                    found.add(file.getFileName().toString());
                }
            }
        }

        assertFalse(found.size() < 2,
                "only " + found + " asks about a display, so either the sweep is broken "
                        + "or the assumptions moved somewhere this does not speak for");
    }
}
