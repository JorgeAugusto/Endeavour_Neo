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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import br.com.jorge.reis.endeavourneo.platform.JobService;

import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The application shell")
class MainWindowTest {

    @Test
    @DisplayName("builds the four regions: navigator, editors, console and status bar")
    void buildsEveryRegion() throws Exception {
        onEdt(window -> {
            assertNotNull(window.getDesktop(), "the desktop area is missing");
            assertNotNull(window.getConsole(), "the console is missing");
            assertNotNull(window.getStatus(), "the status bar is missing");
            assertNotNull(window.getJMenuBar(), "the menu bar is missing");

            assertTrue(window.getJMenuBar().getMenuCount() >= 2, "the menu bar came up empty");
        });
    }

    @Test
    @DisplayName("opening the same name twice fronts the window instead of duplicating it")
    void doesNotDuplicateWindows() throws Exception {
        // Charts live in windows so several can sit on several monitors. But
        // double-clicking the same navigator entry must still front the one
        // already open rather than stack a second identical window on top --
        // which is worse than duplicate tabs were, because the copy hides the
        // original completely.
        onEdt(window -> {
            try {
                window.open("Report");
                window.open("Balance");
                window.open("Report");

                assertEquals(List.of("Report", "Balance"), window.openCharts(),
                        "the same chart was opened twice");
            } finally {
                window.closeCharts();
            }
        });
    }

    @Test
    @DisplayName("closing the application takes the chart windows down with it")
    void closingTakesChartsDown() throws Exception {
        // Without this the main window can exit while five charts stay on
        // screen, orphaned, with no way left to close them but the task manager.
        onEdt(window -> {
            window.open("One");
            window.open("Two");

            assertEquals(2, window.openCharts().size(), "the charts did not open");

            window.closeCharts();

            assertTrue(window.openCharts().isEmpty(), "chart windows survived the close");
        });
    }

    @Test
    @DisplayName("no label falls back to the !key! marker, so every string is translated")
    void everyStringIsTranslated() throws Exception {
        // Messages.get returns !key! for a missing entry rather than an empty
        // string, precisely so a forgotten translation is visible. This test
        // walks the menu bar looking for that marker: it fails when a key is
        // added to the code and forgotten in the bundle.
        onEdt(window -> {
            var bar = window.getJMenuBar();

            for (int i = 0; i < bar.getMenuCount(); i++) {
                var menu = bar.getMenu(i);

                assertFalse(menu.getText().startsWith("!"),
                        "untranslated menu: " + menu.getText());

                for (int j = 0; j < menu.getItemCount(); j++) {
                    var entry = menu.getItem(j);

                    if (entry != null) {
                        assertFalse(entry.getText().startsWith("!"),
                                "untranslated item: " + entry.getText());
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("a chart switches between docked and floating, keeping the same canvas")
    void switchesBetweenDockedAndFloating() throws Exception {
        // The whole point of the design: the canvas does not know where it
        // lives, so changing mode is re-parenting one component. If the canvas
        // were recreated, the zoom, the style and the vertical factor would be
        // lost on every move -- and that loss is invisible in code review.
        onEdt(window -> {
            try {
                window.open("Chart");

                assertFalse(window.isFloating("Chart"), "it should open docked");

                window.toggleChartMode("Chart");
                assertTrue(window.isFloating("Chart"), "toggling did not set it free");

                window.toggleChartMode("Chart");
                assertFalse(window.isFloating("Chart"), "toggling back did not dock it");
            } finally {
                window.closeCharts();
            }
        });
    }

    private static void onEdt(Consumer<MainWindow> test) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        try (JobService jobs = new JobService()) {
            SwingUtilities.invokeAndWait(() -> {
                MainWindow window = new MainWindow("test", jobs);

                try {
                    test.accept(window);
                } finally {
                    window.dispose();
                }
            });
        }
    }
}
