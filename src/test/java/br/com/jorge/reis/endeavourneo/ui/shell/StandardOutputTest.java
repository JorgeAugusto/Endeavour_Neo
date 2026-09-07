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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import br.com.jorge.reis.endeavourneo.platform.JobService;

import java.awt.GraphicsEnvironment;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where {@code System.out} goes after the window is built again.
 *
 * <h2>Why this is worth a test of its own</h2>
 *
 * <p>Changing language throws the whole window away and makes a new one. The
 * redirection of standard output was set up once, at start-up, and never again:
 * from the first change onwards every {@code printStackTrace} and every {@code
 * System.err.println} in the program was written into the text area of a window
 * that had been disposed.</p>
 *
 * <p>Nothing could report that, because in a windowed application standard
 * output has nowhere else to go -- so the failure of the thing that reports
 * failures is the one failure with no reporter. And the same reference held the
 * dead window alive: {@code System.out} is a root of the JVM, and it reached
 * the console, its text area, every parent up to the frame, and every chart
 * open in it.</p>
 *
 * <h2>The one rule this test obeys</h2>
 *
 * <p>It touches {@code System.out}, which belongs to the whole machine and to
 * every other test in the run. It therefore puts it back, in a {@code finally},
 * exactly as it found it.</p>
 */
@DisplayName("A saida padrao segue a janela")
class StandardOutputTest {

    /** Distinctive enough that finding it in a console cannot be a coincidence. */
    private static final String MARK = "L1-1-marca-da-saida-padrao";

    @Test
    @DisplayName("refazer a janela leva a saida padrao junto")
    void relaunchTakesStandardOutputWithIt() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        try (JobService jobs = new JobService()) {
            SwingUtilities.invokeAndWait(() -> {
                MainWindow first = new MainWindow("test", jobs);
                MainWindow second = null;

                try {
                    first.getConsole().captureStandardOutput();

                    second = first.relaunch();

                    // On the interface thread, so the console writes straight
                    // through instead of being posted.
                    System.out.println(MARK);

                    assertTrue(second.getConsole().contents().contains(MARK),
                            "standard output did not follow the window: after the first "
                                    + "change of language the application is deaf to its own "
                                    + "output for the rest of the session");

                    assertFalse(first.getConsole().contents().contains(MARK),
                            "the output still goes to the console of the window that was "
                                    + "disposed, which also keeps that whole window -- charts "
                                    + "and series included -- alive through a root of the JVM");
                } finally {
                    Console.releaseStandardOutput();

                    if (second != null) {
                        second.closeCharts();
                        second.dispose();
                    }

                    first.dispose();
                }
            });
        }
    }
}
