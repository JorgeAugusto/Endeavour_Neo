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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.TickSource;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;
import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tree of bases, grouped by instrument and labelled by role.
 */
@DisplayName("Navigator tree")
class NavigatorTreeTest {

    @AfterEach
    void stopPointingAtTheTemporaryFolder() {
        SeriesCatalog.useFolderForTest(null);
    }

    private static void base(Path folder, String name) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(24 + 48).order(ByteOrder.BIG_ENDIAN);

        buffer.put("ENDVCNDL".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(1);
        buffer.putInt(1);
        buffer.putLong(1);
        buffer.putLong(1_756_000_000_000L);

        for (int i = 0; i < 4; i++) {
            buffer.putDouble(136_000);
        }

        buffer.putDouble(10);

        SeriesCatalog.useFolderForTest(folder);

        Path file = under(folder, SeriesCatalog.fileOf(name));

        Files.createDirectories(file.getParent());
        Files.write(file, buffer.array());
    }

    /**
     * @return that path, having checked it is inside the test's own folder
     *
     * <p>A guard bought at a price. A fixture that asked the catalog where a
     * file goes, without having told it where to look, wrote a
     * twenty-four-byte header over a ninety-megabyte session of real ticks --
     * and the test that did it failed for an unrelated-looking reason, so the
     * damage was found by accident. Paths in these fixtures come from the
     * catalog, which is right; this makes sure the catalog is pointing at the
     * temporary folder when they do.</p>
     */
    static Path under(Path folder, Path file) {
        if (!file.toAbsolutePath().startsWith(folder.toAbsolutePath())) {
            throw new IllegalStateException("this fixture was about to write to " + file
                    + ", which is outside " + folder
                    + " -- the catalog is not pointing at the test's folder");
        }

        return file;
    }

    /** Every leaf under the tree, with its label and what it would open. */
    /**
     * The tree reads segments, so a test of the tree has to say which ones.
     *
     * <p>Without this it reads whatever is saved on the machine running the
     * suite, and a series that happens to have two segments there stops being a
     * leaf -- which is exactly how this test started failing.</p>
     */
    @org.junit.jupiter.api.BeforeEach
    void isolateSegments(@TempDir Path store) {
        br.com.jorge.reis.endeavourneo.platform.Segmentation.useForTest(
                store.resolve("workspace.properties"));
    }

    @org.junit.jupiter.api.AfterEach
    void giveTheWorkspaceBack() {
        br.com.jorge.reis.endeavourneo.platform.Segmentation.stopUsingTestStore();
    }

    @Test
    @DisplayName("os segmentos ficam embaixo da serie e abrem sozinhos")
    void segmentsHangUnderTheirSeries(@TempDir Path folder) throws IOException {
        base(folder, "winfull-1m");
        SeriesCatalog.useFolderForTest(folder);

        br.com.jorge.reis.endeavourneo.platform.Segmentation.set("winfull-1m", List.of(
                new br.com.jorge.reis.endeavourneo.domain.market.Segment("treino",
                        java.time.LocalDate.of(2020, 9, 1),
                        java.time.LocalDate.of(2023, 12, 29))));

        List<String[]> found = leaves(Navigator.treeModel());
        String[] segment = found.stream()
                .filter(each -> "winfull-1m#treino".equals(each[1]))
                .findFirst()
                .orElse(null);

        assertNotNull(segment, "the segment is not in the tree");
        assertTrue(segment[0].startsWith("treino"),
                "the segment does not say its own name: " + segment[0]);
    }

    @Test
    @DisplayName("uma serie trancada nao abre inteira, mas lista os segmentos")
    void aLockedSeriesOpensNothingWhole(@TempDir Path folder) throws IOException {
        base(folder, "winfull-1m");
        SeriesCatalog.useFolderForTest(folder);

        br.com.jorge.reis.endeavourneo.platform.Segmentation.set("winfull-1m", List.of(
                new br.com.jorge.reis.endeavourneo.domain.market.Segment("teste",
                        java.time.LocalDate.of(2025, 1, 2),
                        java.time.LocalDate.of(2026, 9, 1))));
        br.com.jorge.reis.endeavourneo.platform.Segmentation
                .setSegmentsOnly("winfull-1m", true);

        List<String[]> found = leaves(Navigator.treeModel());

        assertTrue(found.stream().noneMatch(each -> "winfull-1m".equals(each[1])),
                "the locked series can still be opened whole");
        assertTrue(found.stream().anyMatch(each -> "winfull-1m#teste".equals(each[1])),
                "the lock took the segments with it");
    }

    @Test
    @DisplayName("o nome de tela sai do catalogo, para a arvore e o titulo dizerem o mesmo")
    void oneNameForBothPlaces() {
        // The bug this fixes: the tree said WINFUT-FULL and the chart's own
        // title bar said winfull-1m#Estudos, in the same window.
        assertEquals("WINFUT-FULL", SeriesCatalog.displayOf("winfull-1m"));
        assertEquals("", SeriesCatalog.displayOf(null));
        assertEquals("", SeriesCatalog.displayOf("  "));

        // A market with nothing to tell it from keeps just the market's name.
        assertEquals("ouro", SeriesCatalog.displayOf("ouro-1m"));
    }

    private static List<String[]> leaves(TreeModel model) {
        List<String[]> found = new ArrayList<>();

        walk((DefaultMutableTreeNode) model.getRoot(), found);

        return found;
    }

    private static void walk(DefaultMutableTreeNode node, List<String[]> into) {
        if (node.isLeaf()) {
            into.add(new String[]{String.valueOf(node.getUserObject()),
                    Navigator.nameOf(node)});

            return;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            walk((DefaultMutableTreeNode) node.getChildAt(i), into);
        }
    }

    @Test
    @DisplayName("the label is the market's name and the file name still opens it")
    void theLabelIsNotTheName(@TempDir Path folder) throws IOException {
        // The label says WINFUT-FULL, because the file already sits under
        // WINFUT and under "1 minuto" and repeating either is saying it three
        // times. A double click still asks for the FILE. Showing a name by
        // renaming the node would break opening it, silently.
        base(folder, "winfull-1m");
        SeriesCatalog.useFolderForTest(folder);

        List<String[]> leaves = leaves(Navigator.treeModel());
        String[] winn = leaves.stream()
                .filter(each -> "winfull-1m".equals(each[1]))
                .findFirst()
                .orElse(null);

        assertNotNull(winn, "winfull-1m is not in the tree; leaves were " + leaves.size());
        assertEquals("WINFUT-FULL", winn[0]);
        assertEquals("winfull-1m", winn[1], "opening this leaf would ask for the label");

        // And NOT the role. Every series is a source until a second one
        // arrives, and a word that is on every line is a word nobody reads.
        assertFalse(winn[0].contains(Messages.orElse("navigator.role.source", "source")),
                "the label repeats what is true of every line: " + winn[0]);
    }

    @Test
    @DisplayName("bases of one instrument sit together, under a heading")
    void oneInstrumentOneGroup(@TempDir Path folder) throws IOException {
        base(folder, "winn-1m");
        base(folder, "winfut-1m");
        base(folder, "btcusdt-1m");
        SeriesCatalog.useFolderForTest(folder);

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) Navigator.treeModel().getRoot();
        DefaultMutableTreeNode series = (DefaultMutableTreeNode) root.getChildAt(0);

        int win = 0;
        int bitcoin = 0;

        for (int i = 0; i < series.getChildCount(); i++) {
            DefaultMutableTreeNode group = (DefaultMutableTreeNode) series.getChildAt(i);
            String heading = String.valueOf(group.getUserObject());

            // Counted by descending, not by getChildCount. The scales sit
            // between the instrument and its files, so the instrument's own
            // children are headings; a count of them would say "1" for the two
            // WIN bases and pass for the wrong reason.
            if (heading.equals(Messages.orElse("navigator.group.win", "win"))) {
                win = under(group);
            } else if (heading.equals(Messages.orElse("navigator.group.btcusdt", "btcusdt"))) {
                bitcoin = under(group);
            }
        }

        assertEquals(2, win, "the two WIN bases were not put together");
        assertEquals(1, bitcoin);
    }

    /** @return how many leaves hang anywhere below that node */
    private static int under(DefaultMutableTreeNode node) {
        List<String[]> found = new ArrayList<>();

        walk(node, found);

        return found.size();
    }

    @Test
    @DisplayName("the scale sits between the instrument and its files")
    void scaleIsTheMiddleLevel(@TempDir Path folder) throws IOException {
        // What the reader asked for: WIN, and beneath it the resolutions. Two
        // series of one market at one scale share a heading; one at another
        // scale gets its own.
        //
        // The 1s file here is a FIXTURE, not a claim. There is no second scale
        // on disk today and there will not be for a while -- only 1m and the
        // ticks -- and the tree shows only the scales that exist, so nothing
        // invents a "1 second" heading over an empty folder. It takes two
        // scales to prove nesting and ordering at all, and inventing the one we
        // already know is coming beats inventing one nobody will ever write.
        base(folder, "winfull-1m");
        base(folder, "winn-1m");
        base(folder, "winfull-1s");
        SeriesCatalog.useFolderForTest(folder);

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) Navigator.treeModel().getRoot();
        DefaultMutableTreeNode series = (DefaultMutableTreeNode) root.getChildAt(0);
        DefaultMutableTreeNode win = (DefaultMutableTreeNode) series.getChildAt(0);

        assertEquals(2, win.getChildCount(), "WIN did not come back with two scales");

        DefaultMutableTreeNode minutes = (DefaultMutableTreeNode) win.getChildAt(0);
        DefaultMutableTreeNode seconds = (DefaultMutableTreeNode) win.getChildAt(1);

        // Coarsest first. Reading down the tree is zooming in, and the reverse
        // would make the everyday scale the one that has to be hunted for.
        assertEquals(Messages.orElse("navigator.scale.1m", "1m"),
                String.valueOf(minutes.getUserObject()));
        assertEquals(Messages.orElse("navigator.scale.1s", "1s"),
                String.valueOf(seconds.getUserObject()));

        assertEquals(2, minutes.getChildCount(), "the two minute series were split up");
        assertEquals(1, seconds.getChildCount());
        assertEquals("winfull-1s",
                Navigator.nameOf((DefaultMutableTreeNode) seconds.getChildAt(0)),
                "the leaf under a scale stopped being openable");
    }

    @Test
    @DisplayName("a base with no role listed is still shown, by its own name")
    void anUnknownBaseIsNotShouted(@TempDir Path folder) throws IOException {
        // A base added yesterday has no translated heading and no role. That is
        // not a defect, and rendering it as !navigator.group.ouro! would make
        // the tree unreadable for a file that is perfectly fine.
        base(folder, "ouro-1m");
        SeriesCatalog.useFolderForTest(folder);

        List<String[]> leaves = leaves(Navigator.treeModel());
        String[] gold = leaves.stream()
                .filter(each -> "ouro-1m".equals(each[1]))
                .findFirst()
                .orElse(null);

        assertNotNull(gold, "the base is not in the tree");
        assertFalse(gold[0].contains("!"),
                "an unknown base was decorated with a missing key: " + gold[0]);
        assertEquals("ouro", gold[0],
                "with nothing to tell it from, a base keeps its market's name");

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) Navigator.treeModel().getRoot();
        DefaultMutableTreeNode series = (DefaultMutableTreeNode) root.getChildAt(0);
        DefaultMutableTreeNode group = (DefaultMutableTreeNode) series.getChildAt(0);

        assertEquals("ouro", String.valueOf(group.getUserObject()),
                "the heading shows a missing key instead of the instrument");
    }
    /** A session header of that source: tag, version, day, count. */
    private static void tickSession(Path folder, String instrument,
            java.time.LocalDate day, TickSource source) throws IOException {
        // Built from the folder in hand, NOT from the catalog. Asking the
        // catalog reads global state, and that read failed about one run in
        // four -- three times it was caught here about to write over ninety
        // megabytes of real exported ticks. The cause of the intermittence was
        // never found; removing the question was cheaper than answering it.
        Path file = under(folder,
                source.fileFor(folder.resolve(instrument).resolve("ticks"), instrument, day));
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);

        header.put(source.tag().getBytes(StandardCharsets.US_ASCII));
        header.putInt(1);
        header.putInt((int) day.toEpochDay());
        header.putLong(0);

        Files.createDirectories(file.getParent());
        Files.write(file, header.array());
    }

    @Test
    @DisplayName("the ticks say which export they came from")
    void bothTickSourcesAreNamed(@TempDir Path folder) throws IOException {
        // The two hold different things -- MetaTrader has the bid and the ask,
        // the Profit tape has both brokers and the aggressor -- so which one a
        // day came from changes what can be asked of it. Listed together as
        // "ticks" it would be the one thing worth knowing that the tree hides.
        tickSession(folder, "win", java.time.LocalDate.of(2021, 1, 4), TickSource.METATRADER);
        tickSession(folder, "win", java.time.LocalDate.of(2026, 9, 1), TickSource.PROFIT);
        tickSession(folder, "win", java.time.LocalDate.of(2026, 9, 2), TickSource.PROFIT);

        // Asked with the folder spelled out. Going through the whole tree meant
        // asking "where is the catalog pointing right now", which is not this
        // test's question and has no stable answer in a suite that also opens
        // real windows.
        DefaultMutableTreeNode node =
                Navigator.tickSessions(folder.resolve("win").resolve("ticks"), "win");

        assertNotNull(node, "no tick session was listed at all");

        List<String> lines = new ArrayList<>();

        for (int i = 0; i < node.getChildCount(); i++) {
            lines.add(String.valueOf(
                    ((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject()));
        }

        assertEquals(2, lines.size(), "the sources were not listed apart: " + lines);

        String metatrader = lines.stream()
                .filter(each -> each.startsWith(
                        Messages.orElse("navigator.tickSource.metatrader", "metatrader")))
                .findFirst().orElse(null);
        String profit = lines.stream()
                .filter(each -> each.startsWith(
                        Messages.orElse("navigator.tickSource.profit", "profit")))
                .findFirst().orElse(null);

        assertNotNull(metatrader, "the MetaTrader sessions are not named: " + lines);
        assertNotNull(profit, "the Profit sessions are not named: " + lines);

        // And each counts only its own. A library that read both extensions
        // would say "three sessions" twice and be wrong twice.
        assertTrue(metatrader.contains("1") && metatrader.contains("2021-01-04"), metatrader);
        assertTrue(profit.contains("2") && profit.contains("2026-09-01"), profit);
    }

    @Test
    @DisplayName("a source with nothing exported is not listed as empty")
    void anEmptySourceIsSilent(@TempDir Path folder) throws IOException {
        // There is no tape in this folder, and a permanent "Profit: 0" under
        // every instrument would be a standing reminder of nothing.
        //
        // Asked with the folder spelled out rather than through the catalog.
        // The version that read the catalog failed about one run in four --
        // it was really asking "where is the catalog pointing right now",
        // which is not this test's question and not a question with a stable
        // answer in a suite that also opens real windows.
        SeriesCatalog.useFolderForTest(folder);

        Path ticks = folder.resolve("win").resolve("ticks");

        tickSession(folder, "win", java.time.LocalDate.of(2021, 1, 4), TickSource.METATRADER);

        DefaultMutableTreeNode node = Navigator.tickSessions(ticks, "win");

        assertNotNull(node, "the exported MetaTrader session was not listed at all");

        List<String> lines = new ArrayList<>();

        for (int i = 0; i < node.getChildCount(); i++) {
            lines.add(String.valueOf(
                    ((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject()));
        }

        assertEquals(1, lines.size(), "an empty source was listed: " + lines);
        assertTrue(lines.get(0).startsWith(
                        Messages.orElse("navigator.tickSource.metatrader", "metatrader")),
                lines.get(0));
    }

    @Test
    @DisplayName("o guarda recusa qualquer caminho fora da pasta do teste")
    void theGuardRefusesToLeaveTheTemporaryFolder(@TempDir Path folder) {
        // Tested directly, because that is what it is: one check, called by
        // every fixture before it writes. Reaching it through a fixture stopped
        // being possible once the fixtures were made not to ask the catalog --
        // which is the fix, not a reason to leave the net untested.
        //
        // It earned its place. Three times it caught a fixture about to write a
        // twenty-four-byte header over ninety megabytes of real exported ticks,
        // because a read of global state failed about one run in four. The
        // cause of that intermittence was never found.
        Path outside = Path.of("C:", "dados", "win", "ticks", "win-2021-01-04.bin");

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> under(folder, outside));

        assertTrue(thrown.getMessage().contains("outside"), thrown.getMessage());

        // And it lets through what is inside, or every fixture would fail.
        Path inside = folder.resolve("win").resolve("1m").resolve("winfull-1m.bin");

        assertEquals(inside, under(folder, inside));
    }

    @Test
    @DisplayName("uma fonte de ticks ABRE, como qualquer outra serie")
    void aTickSourceOpens(@TempDir Path folder) throws IOException {
        // It used to be a leaf that opened nothing, and the comment guarding it
        // said "a tick session is what a chart is REPLAYED from, not a chart".
        // That was wrong: an export holds every print of every session it
        // covers, which is MORE than the candle file holds, and the rule made
        // the most complete data in the program the only data that could not be
        // looked at.
        SeriesCatalog.useFolderForTest(folder);

        tickSession(folder, "win", java.time.LocalDate.of(2021, 1, 4), TickSource.METATRADER);

        DefaultMutableTreeNode node =
                Navigator.tickSessions(folder.resolve("win").resolve("ticks"), "win");

        assertNotNull(node, "the session was not listed at all");
        assertEquals(1, node.getChildCount());

        String opens = Navigator.nameOf((DefaultMutableTreeNode) node.getChildAt(0));

        assertNotNull(opens, "the tick source still opens nothing");
        assertEquals(br.com.jorge.reis.endeavourneo.ui.series.Segmentable
                .keyOfTicks("win", TickSource.METATRADER), opens,
                "it opens something other than its own export");
    }
}
