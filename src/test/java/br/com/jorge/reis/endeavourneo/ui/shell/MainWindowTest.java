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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import br.com.jorge.reis.endeavourneo.platform.JobService;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

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
    @DisplayName("the same series can be opened several times, with distinct titles")
    void opensSeveralChartsOfOneSeries() throws Exception {
        // A terminal shows the same instrument at several timeframes at once,
        // and two charts of one series side by side is how zoom levels get
        // compared. Fronting the existing window instead -- one editor per
        // file, the semantics of an IDE tab -- is wrong for a chart.
        //
        // The titles have to differ: two windows both called winn-1m cannot be
        // told apart in the Window menu, and their stored geometry would
        // collide, so moving one would move the other on the next launch.
        // The names asked for are NOT the names asserted. A name that is not a
        // base on this machine opens the default one and is titled after it, so
        // writing the expected titles out by hand made this test pass or fail
        // according to which files happened to be in the reader's data folder
        // -- which it did, the day the folder came to hold one base instead of
        // three. What the test is actually about is that four opens give four
        // charts with four distinct titles.
        onEdt(window -> {
            try {
                List<String> opened = List.of(
                        window.open("winn-1m"),
                        window.open("winn-1m"),
                        window.open("winfut-1m"),
                        window.open("winn-1m"));

                assertEquals(4, window.openCharts().size(),
                        "opening the same series again did not produce a second chart");
                assertEquals(opened, window.openCharts(),
                        "the charts are not the ones that were opened, or not in that order");
                assertEquals(4, opened.stream().distinct().count(),
                        "two windows share a title: they cannot be told apart in the Window "
                                + "menu, and their stored geometry would collide -- moving one "
                                + "would move the other on the next launch");
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
                // The title it ENDED UP with, not the one asked for. A name
                // that is not a base opens the default base and is titled after
                // it, so a machine that has the data and one that does not
                // would otherwise disagree about this test.
                String title = window.open("Chart");

                assertFalse(window.isFloating(title), "it should open docked");

                window.toggleChartMode(title);
                assertTrue(window.isFloating(title), "toggling did not set it free");

                window.toggleChartMode(title);
                assertFalse(window.isFloating(title), "toggling back did not dock it");
            } finally {
                window.closeCharts();
            }
        });
    }

    @Test
    @DisplayName("tiling covers the whole desktop with no window overlapping another")
    void tilingLeavesNoGapsAndNoOverlap() {
        // Two failures live here and both are silent: cells that overlap hide
        // one window behind another, and cells that fall short leave a strip of
        // empty desktop that reads as a misalignment.
        for (int count = 1; count <= 9; count++) {
            java.awt.Rectangle[] cells = new java.awt.Rectangle[count];
            long area = 0;

            for (int i = 0; i < count; i++) {
                cells[i] = MainWindow.tileBounds(i, count, 1000, 600);
                area += (long) cells[i].width * cells[i].height;
            }

            for (int i = 0; i < count; i++) {
                for (int j = i + 1; j < count; j++) {
                    assertFalse(cells[i].intersects(cells[j]),
                            count + " windows: cell " + i + " overlaps cell " + j);
                }
            }

            assertEquals(1000L * 600L, area,
                    count + " windows did not cover the desktop exactly");
        }
    }

    @Test
    @DisplayName("cells stay roughly square instead of becoming letterbox strips")
    void cellsStayRoughlySquare() {
        // Four windows in one row would give each a strip 250 by 600 -- unusable
        // for a chart. The square root is what keeps them close to square.
        java.awt.Rectangle cell = MainWindow.tileBounds(0, 4, 1000, 600);

        assertEquals(500, cell.width, "four windows should make two columns");
        assertEquals(300, cell.height, "four windows should make two rows");
    }

    @Test
    @DisplayName("every chart open at closing time comes back, not just the first")
    void everyChartComesBack() throws Exception {
        // Reported from use: two charts open, close the application, and it
        // reopens with one. The cause is a loop eating its own list --
        // restoring walks the remembered keys, and opening each chart rewrites
        // those very keys with only what is open so far. By the second turn of
        // the loop the entry it was about to read is gone.
        onEdt(window -> {
            try {
                String first = window.open("winn-1m");
                String second = window.open("winn-1m");

                assertEquals(2, window.openCharts().size(), "two charts did not open");
                assertNotEquals(first, second, "the second chart took the first one's name");

                // The application's own exit, not an approximation of it:
                // writing the list and freezing it are one step, and a test
                // that did only the first was testing a sequence nothing runs.
                window.prepareToLeave();
                window.closeCharts();

                assertEquals(0, window.openCharts().size());

                window.restoreCharts();

                assertEquals(2, window.openCharts().size(),
                        "restoring brought back " + window.openCharts()
                                + " instead of both charts");
            } finally {
                window.closeCharts();
            }
        });
    }

    @Test
    @DisplayName("uma janela fechada nao volta no proximo arranque")
    void aClosedChartStaysClosed() throws Exception {
        // Reported: close one chart, close the program, and the chart is back.
        // The list of what to reopen has to be pruned when a chart closes, not
        // only when the program does.
        onEdt(window -> {
            String first = window.open(SeriesCatalog.defaultName());
            String second = window.open(SeriesCatalog.defaultName());

            assertEquals(2, window.openCharts().size(), "two charts did not open");

            // Through the frame's own close, which is what the reader clicks --
            // not through the map, which would prove nothing about the wiring.
            window.chartNamed(second).close();

            assertEquals(1, window.openCharts().size(), "the chart did not close");

            List<String> saved = br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
                    .keysStartingWith("chart.open.").stream()
                    .filter(k -> k.endsWith(".series"))
                    .toList();

            assertEquals(1, saved.size(),
                    "the workspace still lists " + saved.size() + " charts to reopen");

            window.closeCharts();
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
