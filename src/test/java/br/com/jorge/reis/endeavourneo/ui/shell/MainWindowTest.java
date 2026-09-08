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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * A settings and workspace pair of this file's own.
     *
     * <p>Opening a chart or a window WRITES: the workspace remembers which
     * charts were open, where they were and what they were showing. The home
     * those files live under is redirected by a property set in one place only,
     * the surefire plugin -- and the house runs the suite with javac and a
     * runner instead, where that property is absent and these tests rewrote the
     * reader's own list of open charts on every run.</p>
     *
     * <p>Per test, and put back afterwards, so nothing here can be read by the
     * next file either: MainWindowTest counts the charts it remembers, and a
     * count is a property any other test could change.</p>
     */
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path store;

    @org.junit.jupiter.api.BeforeEach
    void useAStoreOfOurOwn() {
        br.com.jorge.reis.endeavourneo.platform.Settings.useForTest(store);
    }

    @org.junit.jupiter.api.AfterEach
    void putTheStoreBack() {
        br.com.jorge.reis.endeavourneo.platform.Settings.stopUsingTestStore();
    }

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

    /** Ten one-minute bars, enough for a header that promises more than a truncation leaves. */
    private static br.com.jorge.reis.endeavourneo.domain.market.PriceSeries tenBars() {
        return new br.com.jorge.reis.endeavourneo.domain.market.PriceSeries() {

            @Override
            public int size() {
                return 10;
            }

            @Override
            public long timeAt(int index) {
                return 1_756_000_000_000L + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return 100_000 + index;
            }

            @Override
            public double highAt(int index) {
                return 100_010 + index;
            }

            @Override
            public double lowAt(int index) {
                return 99_990 + index;
            }

            @Override
            public double closeAt(int index) {
                return 100_005 + index;
            }
        };
    }

    @Test
    @DisplayName("a series that will not read draws NOTHING, never invented prices")
    void anUnreadableSeriesDrawsNothing(@org.junit.jupiter.api.io.TempDir
                                        java.nio.file.Path folder) throws Exception {
        // The one thing a chart must never do, and seriesFor's own javadoc says
        // so: "prices that are not the market's, drawn without a word". The
        // catch for a file that will not read fell straight through to the
        // synthetic walk, so a corrupt file became two thousand invented prices
        // under the instrument's name. The message went to the console, where it
        // scrolls away; the chart stayed, looking like a market.
        // The folder the catalogue will look in, asked rather than guessed: a
        // series lives under its GROUP, and the group of winbroken-1m is
        // winbroken, not win. Guessing win put the file where nothing looked.
        java.nio.file.Path where = folder.resolve("winbroken").resolve("1m");

        java.nio.file.Files.createDirectories(where);

        java.nio.file.Path file = where.resolve("winbroken-1m.bin");

        // TRUNCATED, not garbage. Garbage fails the header check, and then
        // SeriesCatalog.has says no and the window falls back to the default
        // series without ever reaching the branch under test -- which is what
        // the first draft of this fixture did.
        //
        // A file whose header is intact and whose body is short is the real
        // failure: a write interrupted, a bad disk. has() says yes, because it
        // reads only the header; read() then throws, because the header
        // promises more bars than the file holds.
        br.com.jorge.reis.endeavourneo.domain.market.MarketFile.write(file, tenBars(), 1);

        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.WRITE)) {
            channel.truncate(32);
        }

        java.nio.file.Path was = SeriesCatalog.folder();

        SeriesCatalog.useFolderForTest(folder);

        try {
            // AFTER pointing the catalogue at the temporary folder, which the
            // first draft did not: has() was asking the real data folder about a
            // name that only exists here, and answering no for the wrong reason.
            assertTrue(SeriesCatalog.has("winbroken-1m"),
                    "the fixture is wrong: the catalogue refuses this file outright, so the "
                            + "branch under test is never reached");

            onEdt(window -> {
                try {
                    String title = window.open("winbroken-1m");

                    assertNotNull(title, "the chart did not open at all");

                    int drawn = window.chartNamed(title).canvas().series().size();

                    assertEquals(0, drawn,
                            "a file that will not read drew " + drawn + " prices the market "
                                    + "never traded");
                } finally {
                    window.closeCharts();
                }
            });
        } finally {
            SeriesCatalog.useFolderForTest(was);
        }
    }

    @Test
    @DisplayName("a chart opened for a series that is gone carries the name it really opened")
    void aMissingSeriesDoesNotKeepItsName() throws Exception {
        // The window falls back to the default series when the one asked for is
        // not on disk -- a layout from another machine, a file moved. The title
        // then has to be the name of what actually opened, because the title is
        // what rememberCharts writes down: keep the dead name and the workspace
        // asks for it again on every launch, forever.
        //
        // The line that decides this read
        // uniqueTitle(SeriesCatalog.has(name) ? series : series) -- a ternary
        // whose two branches were the same expression, so it always kept the
        // asked-for name. Nothing could catch that but opening a name that is
        // not there.
        onEdt(window -> {
            try {
                String dead = "no-such-series-1m";
                String title = window.open(dead);

                assertNotNull(title, "nothing opened at all");
                assertFalse(title.startsWith(dead),
                        "the chart kept the name of a series that does not exist: " + title);
                assertTrue(title.startsWith(SeriesCatalog.defaultName()),
                        "expected a chart of " + SeriesCatalog.defaultName() + ", got " + title);
            } finally {
                window.closeCharts();
            }
        });
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

                // The application's own exit, and now literally so: leave() is
                // the method both the X and the File menu call. Retyping the
                // steps here -- prepareToLeave then closeCharts -- was still an
                // approximation, and it hid a real defect for as long as it
                // stood: the File menu was NOT running prepareToLeave, so
                // leaving that way emptied the remembered list chart by chart.
                // A test that retypes a sequence passes while the application
                // takes a different route.
                window.leave();

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

    @Test
    @DisplayName("uma fonte de ticks trancada nao abre, como qualquer serie trancada")
    void alockedTickSourceIsRefused() throws Exception {
        // The guard was has(asked) AND segmentsOnly(asked), and a tick key --
        // win/ticks/profit -- is a name, not a file: nothing ever looks for it
        // on disk, so has() answered false and took the whole conjunction with
        // it. The lock was offered for a tick source, the box stayed ticked,
        // and the export opened whole anyway.
        //
        // No fixture: the refusal happens on the key, before anything is read.
        // A tick source that does not exist is refused for the same reason a
        // real one is, and that is the point -- the guard is about what the
        // reader asked for, not about what is on disk.
        String key = br.com.jorge.reis.endeavourneo.ui.series.Segmentable
                .keyOfTicks("win", br.com.jorge.reis.endeavourneo.domain.market
                        .TickSource.PROFIT);

        onEdt(window -> {
            try {
                br.com.jorge.reis.endeavourneo.platform.Segmentation
                        .setSegmentsOnly(key, true);

                assertNull(window.open(key),
                        "the locked export opened whole: years of searching and years of "
                                + "testing in one window, which is exactly what the box "
                                + "promises to prevent");
            } finally {
                br.com.jorge.reis.endeavourneo.platform.Segmentation
                        .setSegmentsOnly(key, false);

                window.closeCharts();
            }
        });
    }

    /**
     * @param test what to do with a freshly built window, on the interface thread
     *
     * <p><b>The queue is drained between building and testing.</b> The
     * constructor posts restoreCharts for later, and this used to build, test
     * and dispose inside ONE task -- so the posted restore ran afterwards, over
     * a window that had already been disposed: charts opened into a desktop
     * with no size, and rememberCharts wrote the list of open charts again,
     * after the test that owned it had finished. Which test that landed in
     * depended on the order of the queue.</p>
     *
     * <p>Drained BEFORE the body rather than after: the restore is part of
     * opening a window, and a test looking at a window should be looking at one
     * that has finished opening.</p>
     */
    private static void onEdt(Consumer<MainWindow> test) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        try (JobService jobs = new JobService()) {
            java.util.concurrent.atomic.AtomicReference<MainWindow> made =
                    new java.util.concurrent.atomic.AtomicReference<>();

            SwingUtilities.invokeAndWait(() -> made.set(new MainWindow("test", jobs)));
            SwingUtilities.invokeAndWait(() -> { });

            try {
                SwingUtilities.invokeAndWait(() -> test.accept(made.get()));
            } finally {
                SwingUtilities.invokeAndWait(() -> made.get().dispose());
            }
        }
    }

    @Test
    @DisplayName("um segmento de um export le SO os pregoes dele")
    void asegmentOfAnExportReadsOnlyItsOwnSessions(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        // FoldedTicks.all lists every exported session and folds every one of
        // them, and only afterwards was the segment applied -- so asking for a
        // week of the Profit tape read all 691 MB of it and threw away the rest.
        // FoldedTicks.over takes the list of days and was sitting there unused.
        //
        // The irony is worth keeping: the candle path was corrected for exactly
        // this hours earlier, and the tick path was written after that with the
        // same defect the other way round.
        SeriesCatalog.useFolderForTest(folder);

        java.nio.file.Path ticks = SeriesCatalog.ticksOf("win");

        for (int day = 1; day <= 5; day++) {
            session(ticks, java.time.LocalDate.of(2026, 9, day));
        }

        try {
            java.util.List<java.time.LocalDate> everything = MainWindow.daysOf("win",
                    br.com.jorge.reis.endeavourneo.domain.market.TickSource.PROFIT, null);

            assertEquals(5, everything.size(),
                    "the fixture does not hold five sessions: " + everything);

            br.com.jorge.reis.endeavourneo.domain.market.Segment week =
                    new br.com.jorge.reis.endeavourneo.domain.market.Segment("meio",
                            java.time.LocalDate.of(2026, 9, 2),
                            java.time.LocalDate.of(2026, 9, 3));

            assertEquals(java.util.List.of(java.time.LocalDate.of(2026, 9, 2),
                            java.time.LocalDate.of(2026, 9, 3)),
                    MainWindow.daysOf("win",
                            br.com.jorge.reis.endeavourneo.domain.market.TickSource.PROFIT,
                            week),
                    "a segment of two sessions asked the disk for more than two");

            // An open-ended segment runs to the end of what was exported.
            br.com.jorge.reis.endeavourneo.domain.market.Segment onwards =
                    br.com.jorge.reis.endeavourneo.domain.market.Segment.from("adiante",
                            java.time.LocalDate.of(2026, 9, 4));

            assertEquals(2, MainWindow.daysOf("win",
                    br.com.jorge.reis.endeavourneo.domain.market.TickSource.PROFIT,
                    onwards).size(), "an open-ended segment did not run to the end");
        } finally {
            SeriesCatalog.useFolderForTest(null);
        }
    }

    /** One session of tape, enough to be listed. */
    private static void session(java.nio.file.Path ticks, java.time.LocalDate day)
            throws java.io.IOException {
        try (br.com.jorge.reis.endeavourneo.domain.market.TapeFile.Writer writer =
                     new br.com.jorge.reis.endeavourneo.domain.market.TapeFile.Writer(
                             br.com.jorge.reis.endeavourneo.domain.market.TickSource.PROFIT
                                     .fileFor(ticks, "win", day), day)) {

            writer.broker(3, "XP");
            writer.add(9 * 3_600_000, 200, 1, 3, 3,
                    br.com.jorge.reis.endeavourneo.domain.market.Aggressor.BUYER);
        }
    }
/**
     * @param bar the footer
     * @return every non-blank label text in it, at any depth
     */
    private static java.util.List<String> saidIn(java.awt.Container bar) {
        java.util.List<String> found = new java.util.ArrayList<>();

        for (java.awt.Component each : bar.getComponents()) {
            if (each instanceof javax.swing.JLabel label && !label.getText().isBlank()) {
                found.add(label.getText());
            } else if (each instanceof java.awt.Container inner) {
                found.addAll(saidIn(inner));
            }
        }

        return found;
    }

    /**
     * The footer stops naming a chart once there is no chart.
     *
     * <p>{@code report} keeps the name when the pointer merely LEAVES a chart,
     * and says why: it is still the one being looked at, and blanking it would
     * make the bar flicker at every crossing of the axis. Closing is the other
     * case, and nothing said so -- with every chart shut the footer went on
     * reading a name, a price and a mode, describing a window that no longer
     * exists. {@code StatusBar.noChart} was written for this and had no caller
     * but a test.</p>
     */
    @Test
    @DisplayName("fechado o ultimo grafico, o rodape para de nomear um que nao existe")
    void thefooterStopsNamingAchartThatIsGone() throws Exception {
        onEdt(window -> {
            window.open("One");

            // What report() writes when the pointer is over a chart. Driving a
            // real pointer would be testing Swing's event queue; the state under
            // test is the footer's, and this is the state it would be left in.
            window.getStatus().chart("WINFUT-FULL  ·  5m", "05/09 03:47   100,42", "Medindo");

            assertTrue(saidIn(window.getStatus()).contains("WINFUT-FULL  ·  5m"),
                    "the fixture did not put the chart's name on the bar at all");

            window.closeCharts();

            assertFalse(saidIn(window.getStatus()).contains("WINFUT-FULL  ·  5m"),
                    "the footer is still naming a chart that was closed: "
                            + saidIn(window.getStatus()));
        });
    }
}
