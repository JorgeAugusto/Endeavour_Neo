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
package br.com.jorge.reis.endeavourneo.ui.series;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who reads the series, and on which thread.
 *
 * <h2>Why the assertion is about a label</h2>
 *
 * <p>A test cannot watch a thread not be used. What it can do is look at the
 * window in the very frame it was built -- inside the same task on the
 * interface thread -- and ask whether the answer is already there. If the days
 * were read in the constructor the range is on screen before that task ends;
 * if they are read behind the window it cannot be, because {@code done()} is
 * posted to the interface thread and this task is still holding it.</p>
 *
 * <p>That is the whole defect, in one look: opening <i>Tools -&gt; Series</i>
 * froze the application while it read the entire series -- 39 MB for six years
 * of one-minute bars -- and froze it again on every move of the combo.</p>
 *
 * <p>It needs no fixture. A series that cannot be read says so, and a series
 * that can says its range; either answer, arriving before the window has
 * finished opening, is the defect. What must NOT be there yet is any answer at
 * all.</p>
 */
@DisplayName("A janela de series le a serie fora da thread da interface")
class SeriesWindowLoadTest {

    private static String reading() {
        return Messages.get("series.reading");
    }

    @Test
    @DisplayName("a janela abre antes de a serie ter sido lida")
    void theWindowOpensBeforeTheSeriesHasBeenRead() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        AtomicReference<SeriesWindow> made = new AtomicReference<>();
        AtomicReference<String> asBuilt = new AtomicReference<>();

        try {
            SwingUtilities.invokeAndWait(() -> {
                SeriesWindow window = new SeriesWindow(null);

                made.set(window);

                // Still inside the task that built it, so nothing posted to
                // this thread can have run.
                asBuilt.set(window.about());
            });

            assertEquals(reading(), asBuilt.get(),
                    "the window already knew about the series before it had finished "
                            + "opening: the read happened on the interface thread, which is "
                            + "the freeze this is about");

            assertNotEquals(reading(), settled(made.get()),
                    "the days never arrived: the window would sit on the waiting text "
                            + "for ever, which is worse than the freeze it replaced");
        } finally {
            SwingUtilities.invokeAndWait(() -> made.get().dispose());
        }
    }

    /** @return what the window says once the reading is over, or the waiting text on timeout */
    private static String settled(SeriesWindow window) throws Exception {
        for (int tries = 0; tries < 200; tries++) {
            AtomicReference<String> now = new AtomicReference<>();

            SwingUtilities.invokeAndWait(() -> now.set(window.about()));

            if (!reading().equals(now.get())) {
                return now.get();
            }

            Thread.sleep(50L);
        }

        return reading();
    }
}
