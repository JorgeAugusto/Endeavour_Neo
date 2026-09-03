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
package br.com.jorge.reis.endeavourneo.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * Dependencies point inward, and this test is what makes that structural rather
 * than a matter of discipline.
 *
 * <p>The rule the whole way of working rests on is that <b>the inner layers do
 * not know the user interface</b>. It is what lets the same code run headless
 * from a command-line tool, and breaking it is silent: the code compiles, the
 * window works, and what dies is the possibility of running anything without a
 * screen. Nobody notices until they try.</p>
 *
 * <p><b>Why read the source rather than use reflection.</b> Bytecode loses the
 * imports the compiler inlined — a constant copied from a UI class vanishes from
 * the compiled file. Reading the {@code .java} also catches an import that is
 * not used yet, which is exactly when a warning is most useful.</p>
 *
 * <p><b>The composition root is exempt, and only it.</b> {@code Main} exists to
 * wire the layers together, so it necessarily touches all of them. Every other
 * class in {@code app} is a platform service and must stay clean. Once the
 * planned reorganisation moves the entry point out of {@code app}, this
 * exemption disappears and the rule becomes purely positional.</p>
 */
@DisplayName("Layer boundary")
class LayerBoundaryTest {

    private static final Path SOURCES = Path.of("src", "main", "java");

    private static final String INNER = "br/com/jorge/reis/endeavourneo/platform";

    /** What the inner layer must not import, and why each one is listed. */
    private static final String[] FORBIDDEN = {
            "br.com.jorge.reis.endeavourneo.ui",     // any window, panel or dialog
    };

    @Test
    @DisplayName("no platform class imports the user interface")
    void platformDoesNotImportUserInterface() throws IOException {
        assumeTrue(Files.isDirectory(SOURCES), "not running from the project root");

        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(LayerBoundaryTest::isInnerLayer).toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.strip();

                    if (!trimmed.startsWith("import ")) {
                        continue;
                    }

                    for (String forbidden : FORBIDDEN) {
                        if (trimmed.contains(forbidden)) {
                            violations.add(SOURCES.relativize(file) + "\n        " + trimmed);
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "a platform class now depends on the user interface, which means it can no "
                        + "longer be used without a screen:\n    "
                        + String.join("\n    ", violations));
    }

    @Test
    @DisplayName("the scan actually finds files -- otherwise it would pass on nothing")
    void theScanFindsFiles() throws IOException {
        // Without this check the test above would pass in any scenario where the
        // path was wrong: zero files scanned yields zero violations. That is the
        // classic way an architecture test ends up with no teeth -- it asserts
        // over an empty set and nobody notices.
        assumeTrue(Files.isDirectory(SOURCES), "not running from the project root");

        try (Stream<Path> files = Files.walk(SOURCES)) {
            long found = files.filter(LayerBoundaryTest::isInnerLayer).count();

            assertTrue(found >= 4,
                    "expected the platform classes and found " + found
                            + "; the scan path is probably wrong");
        }
    }

    private static boolean isInnerLayer(Path file) {
        String path = file.toString().replace('\\', '/');

        return path.endsWith(".java") && path.contains(INNER);
    }
}
