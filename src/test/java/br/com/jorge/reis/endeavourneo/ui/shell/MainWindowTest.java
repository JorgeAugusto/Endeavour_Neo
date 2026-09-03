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
            assertNotNull(window.getEditors(), "the editor area is missing");
            assertNotNull(window.getConsole(), "the console is missing");
            assertNotNull(window.getStatus(), "the status bar is missing");
            assertNotNull(window.getJMenuBar(), "the menu bar is missing");

            assertTrue(window.getJMenuBar().getMenuCount() >= 2, "the menu bar came up empty");
        });
    }

    @Test
    @DisplayName("opening the same name twice fronts the tab instead of duplicating it")
    void doesNotDuplicateTabs() throws Exception {
        // The behaviour of every IDE, and a source of irritation when absent:
        // double-clicking the same navigator entry should not fill the tab bar
        // with identical tabs.
        onEdt(window -> {
            window.open("Report");
            window.open("Balance");
            window.open("Report");

            assertEquals(2, window.getEditors().getTabCount(), "the same tab was opened twice");
            assertEquals("Report",
                    window.getEditors().getTitleAt(window.getEditors().getSelectedIndex()),
                    "reopening should bring the existing tab to the front");
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
