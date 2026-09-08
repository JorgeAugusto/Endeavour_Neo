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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <p><b>Two inner layers, with different rules.</b> {@code platform} must not
 * know the interface. {@code domain} must not know the interface <i>or Swing or
 * AWT</i> — it is where the backtest engine will live, and an engine that cannot
 * run without a display cannot be run overnight from a script, which is the
 * whole reason for keeping it separate.</p>
 *
 * <p><b>Nothing is exempt: the rule is purely positional.</b> It was not, and
 * this paragraph described the arrangement that came before -- a package {@code
 * app} holding a class {@code Main} that wired the layers together and was
 * allowed to touch all of them. The reorganisation the old text called
 * "planned" has happened: there is no {@code app} and no {@code Main}, the
 * entry point is {@code Launcher} at the root, and {@code isInnerLayer} names
 * two packages and no exception. A file whose job is to be read for the rule
 * was describing a rule that had been replaced.</p>
 */
@DisplayName("Layer boundary")
class LayerBoundaryTest {

    private static final Path SOURCES = Path.of("src", "main", "java");

    private static final String PLATFORM = "br/com/jorge/reis/endeavourneo/platform";

    private static final String DOMAIN = "br/com/jorge/reis/endeavourneo/domain";

    /** What no inner class may import, whichever inner layer it is in. */
    private static final String[] FORBIDDEN = {
            "br.com.jorge.reis.endeavourneo.ui",     // any window, panel or dialog
    };

    /**
     * What the domain may not import on top of that.
     *
     * <p>Swing and AWT and not only our own packages: a domain class importing
     * {@code java.awt.Color} to describe an indicator compiles, works, and
     * quietly makes the engine need a display. Java draws no line here, so the
     * line is drawn here.</p>
     */
    private static final String[] FORBIDDEN_IN_DOMAIN = {
            "javax.swing",
            "java.awt",
    };

    @Test
    @DisplayName("no inner class imports the user interface")
    void innerLayersDoNotImportUserInterface() throws IOException {
        List<String> violations = scan(LayerBoundaryTest::isInnerLayer, FORBIDDEN);

        assertTrue(violations.isEmpty(),
                "an inner class now depends on the user interface, which means it can no "
                        + "longer be used without a screen:\n    "
                        + String.join("\n    ", violations));
    }

    @Test
    @DisplayName("no domain class imports Swing or AWT")
    void domainDoesNotImportToolkit() throws IOException {
        List<String> violations = scan(LayerBoundaryTest::isDomain, FORBIDDEN_IN_DOMAIN);

        assertTrue(violations.isEmpty(),
                "a domain class now needs a graphics toolkit, so the engine can no longer "
                        + "run headless overnight:\n    "
                        + String.join("\n    ", violations));
    }

    /**
     * @return whether the line is prose rather than code
     *
     * <p>A heuristic, and it only needs to be one: a comment naming a class it
     * warns about must not be read as using it, and this project's comments name
     * classes constantly. The cases it misses -- a package name inside a string
     * literal, a block comment whose continuation lines do not start with an
     * asterisk -- would produce a false ALARM, which is read and dismissed, not
     * a false silence, which is not read at all.</p>
     */
    private static boolean isComment(String trimmed) {
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

    @Test
    @DisplayName("the scan itself reads more than imports")
    void theScanHasTeeth() {
        // The guard's own guard. Both tests above pass, and passed for a long
        // time while the scan read only lines beginning with "import " -- so
        // they passed whether or not the rule held. A test of a rule is worth
        // only as much as the reading behind it, and that reading deserves its
        // own assertions.
        String qualified = "        br.com.jorge.reis.endeavourneo.ui.chart.ChartColors.up();";

        assertFalse(isComment(qualified));
        assertTrue(qualified.contains(FORBIDDEN[0]),
                "a fully qualified call is exactly what the old scan walked past");

        // And prose about the rule is not a breach of it.
        assertTrue(isComment("// never import br.com.jorge.reis.endeavourneo.ui here"));
        assertTrue(isComment("* see br.com.jorge.reis.endeavourneo.ui.chart.ChartCanvas"));
        assertTrue(isComment("/* br.com.jorge.reis.endeavourneo.ui */"));
    }

    private static List<String> scan(java.util.function.Predicate<Path> where,
                                     String[] forbidden) throws IOException {
        assumeTrue(Files.isDirectory(SOURCES), "not running from the project root");

        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(where).toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.strip();

                    // EVERY line of code, not only the imports. Reading imports
                    // alone was a guard with no teeth: a fully qualified name in
                    // the middle of a method reaches the same class and needs no
                    // import at all, and THIS REPOSITORY WRITES THAT WAY -- see
                    // any of the br.com.jorge.reis.endeavourneo.platform.Settings
                    // calls dotted through the interface. The rule was being kept
                    // by habit, not by the test that claims to keep it.
                    if (isComment(trimmed)) {
                        continue;
                    }

                    for (String banned : forbidden) {
                        if (trimmed.contains(banned)) {
                            violations.add(SOURCES.relativize(file) + "\n        " + trimmed);
                        }
                    }
                }
            }
        }

        return violations;
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

        try (Stream<Path> files = Files.walk(SOURCES)) {
            assertTrue(files.filter(LayerBoundaryTest::isDomain).count() >= 1,
                    "no domain class was scanned; the rule above asserted over nothing");
        }
    }

    private static boolean isInnerLayer(Path file) {
        return isPlatform(file) || isDomain(file);
    }

    private static boolean isPlatform(Path file) {
        return in(file, PLATFORM);
    }

    private static boolean isDomain(Path file) {
        return in(file, DOMAIN);
    }

    private static boolean in(Path file, String layer) {
        String path = file.toString().replace('\\', '/');

        return path.endsWith(".java") && path.contains(layer);
    }
}
