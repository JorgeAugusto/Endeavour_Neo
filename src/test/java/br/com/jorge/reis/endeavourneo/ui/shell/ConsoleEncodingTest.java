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
package br.com.jorge.reis.endeavourneo.ui.shell;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import br.com.jorge.reis.endeavourneo.platform.JobService;

import java.awt.GraphicsEnvironment;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Accents that go through standard output and come out the other side.
 *
 * <h2>Why this had to be wrong, and why nobody saw it</h2>
 *
 * <p>The redirection builds its {@code PrintStream} with UTF-8, so it hands the
 * stream underneath one BYTE at a time. That stream collected them as {@code
 * (char) b}, which is Latin-1 decoding: every character outside ASCII arrived as
 * two wrong ones, and {@code ã} -- 0xC3 0xA3 -- came out as {@code Ã£}.</p>
 *
 * <p>The interface is in Portuguese and full of accents, and the messages
 * written straight through {@code console.write} were fine, because they never
 * touch the byte stream. Only what came through {@code System.out} was mojibake
 * -- which is the half nobody writes on purpose, so it was the half nobody
 * looked at.</p>
 */
@DisplayName("Acento pela saida padrao")
class ConsoleEncodingTest {

    /** Two-byte, three-byte, and one that only exists as a pair of them. */
    private static final String ACCENTED = "não é sessão · média — ç";

    @Test
    @DisplayName("um acento escrito em System.out chega inteiro ao console")
    void anaccentSurvivesStandardOutput() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        try (JobService jobs = new JobService()) {
            SwingUtilities.invokeAndWait(() -> {
                MainWindow window = new MainWindow("test", jobs);

                try {
                    window.getConsole().captureStandardOutput();

                    System.out.println(ACCENTED);

                    assertTrue(window.getConsole().contents().contains(ACCENTED),
                            "the console shows mojibake for anything with an accent: the "
                                    + "bytes are UTF-8 and they were being read one at a "
                                    + "time as Latin-1");
                } finally {
                    Console.releaseStandardOutput();

                    window.dispose();
                }
            });
        }
    }
}
