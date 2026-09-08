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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;
import br.com.jorge.reis.endeavourneo.platform.Segmentation;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Looking is not editing.
 *
 * <p>The window saved on the way out and on every move of the series combo,
 * whether or not anything had been touched — and what it wrote back was what
 * {@code Segmentation.of} had handed it, which drops any entry with a date it
 * cannot read. Since saving clears the series's keys before writing, the cycle
 * read → drop → write back turned a loss that was only a reading into a loss on
 * disk.</p>
 *
 * <p>Opening <i>Tools → Series</i>, looking, and closing was enough. And the
 * workspace is meant to be edited by hand — that is the stated reason it is
 * plain text — so a badly typed date is the ordinary case here, not a strange
 * one.</p>
 */
@DisplayName("A janela de series so grava o que foi editado")
class SeriesWindowSaveTest {

    private static final String SERIES = "winfut-1m";

    @TempDir
    Path data;

    @TempDir
    Path settings;

    @AfterEach
    void putEverythingBack() {
        Segmentation.stopUsingTestStore();
        SeriesCatalog.useFolderForTest(null);
        SeriesCatalog.stopUsingTestSettings();
        SeriesCatalog.forget();
    }

    /** A base of one bar, so the catalog has a series to offer. */
    private void base() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(24 + 48).order(ByteOrder.BIG_ENDIAN);

        buffer.put("ENDVCNDL".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(1);
        buffer.putInt(1);
        buffer.putLong(1);
        buffer.putLong(1_756_000_000_000L);
        buffer.putDouble(104_000);
        buffer.putDouble(104_000);
        buffer.putDouble(104_000);
        buffer.putDouble(104_000);
        buffer.putDouble(10);

        SeriesCatalog.useSettingsForTest(settings.resolve("settings.properties"));
        SeriesCatalog.useFolderForTest(data);

        Path file = SeriesCatalog.fileOf(SERIES);

        Files.createDirectories(file.getParent());
        Files.write(file, buffer.array());
    }

    /**
     * @return the workspace file, holding one segmentation with a bad date in it
     *
     * <p>Written as text and read back through {@code useForTest}, because what
     * is being reproduced is a file somebody edited in an editor. Going through
     * {@code Segmentation.set} could not produce this state at all: the date
     * would have had to be a {@code LocalDate} to get in.</p>
     */
    private Path workspaceWithAbadDate() throws IOException {
        Path file = data.resolve("workspace.properties");

        Files.write(file, List.of(
                "segments." + SERIES + ".0.name=busca",
                "segments." + SERIES + ".0.from=2020-09-01",
                "segments." + SERIES + ".0.to=2023-12-31",
                "segments." + SERIES + ".1.name=validacao",
                "segments." + SERIES + ".1.from=01/01/2024",
                "segments." + SERIES + ".2.name=teste",
                "segments." + SERIES + ".2.from=2025-01-01"));

        Segmentation.useForTest(file);

        return file;
    }

    @Test
    @DisplayName("abrir, olhar e fechar nao apaga o segmento que a leitura nao entendeu")
    void lookingDoesNotDeleteWhatTheReadingCouldNotParse() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        base();

        Path file = workspaceWithAbadDate();

        AtomicReference<SeriesWindow> made = new AtomicReference<>();

        try {
            SwingUtilities.invokeAndWait(() -> made.set(new SeriesWindow(null)));

            // Closed the way a reader closes it. dispose() fires windowClosed;
            // what the window listens for, and what saves, is windowClosing.
            SwingUtilities.invokeAndWait(() -> made.get().dispatchEvent(
                    new java.awt.event.WindowEvent(made.get(),
                            java.awt.event.WindowEvent.WINDOW_CLOSING)));

            List<String> after = Files.readAllLines(file);

            assertTrue(after.contains("segments." + SERIES + ".1.from=01/01/2024"),
                    "closing the window without touching anything deleted the segment whose "
                            + "date it could not read: " + after);

            assertTrue(after.contains("segments." + SERIES + ".1.name=validacao"),
                    "half of that segment survived, which is worse than either answer: "
                            + after);
        } finally {
            SwingUtilities.invokeAndWait(() -> made.get().dispose());
        }
    }

    /**
     * With no series at all, nothing is written under the name "null".
     *
     * <p>{@code String.valueOf} turns an empty combo into the four-letter
     * string, which is not null and so passed every guard: on a machine with no
     * data folder -- a state the navigator treats explicitly, with its own
     * "no series" leaf -- this wrote {@code segments.null.*} into the reader's
     * workspace, where they stayed for good, and the window headed itself with
     * the count of a series called "null".</p>
     */
    @Test
    @DisplayName("sem serie nenhuma, nada e gravado sob a chave \"null\"")
    void withNoSeriesNothingIsWrittenUnderNull() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        // An empty data folder: the catalog finds nothing, so the combo has
        // nothing to offer.
        SeriesCatalog.useSettingsForTest(settings.resolve("settings.properties"));
        SeriesCatalog.useFolderForTest(data.resolve("vazia"));

        Path file = data.resolve("workspace.properties");

        Files.write(file, List.of("# nada aqui"));
        Segmentation.useForTest(file);

        AtomicReference<SeriesWindow> made = new AtomicReference<>();

        try {
            SwingUtilities.invokeAndWait(() -> made.set(new SeriesWindow(null)));

            // THE KEY ITSELF, because the guard on saving would hide the
            // defect: nothing was edited, so nothing is written either way,
            // and the file below would stay clean with the four-letter
            // string right there in the field.
            assertNull(made.get().editingSeries(),
                    "with no series at all the window is editing one called \"null\"");

            SwingUtilities.invokeAndWait(() -> made.get().dispatchEvent(
                    new java.awt.event.WindowEvent(made.get(),
                            java.awt.event.WindowEvent.WINDOW_CLOSING)));

            for (String line : Files.readAllLines(file)) {
                assertTrue(!line.startsWith("segments.null."),
                        "a series called \"null\" was written into the workspace: " + line);
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> made.get().dispose());
        }
    }

    /**
     * Closed with the X, the window is gone and not merely hidden.
     *
     * <p>{@code JDialog} defaults to {@code HIDE_ON_CLOSE}, so every window shut
     * that way stayed in {@code Window.getWindows()} for the life of the
     * program -- and applying a theme walks every window there, calling
     * {@code updateComponentTreeUI} on each. A long session made changing the
     * theme progressively slower, over dozens of invisible windows carrying
     * their whole component trees.</p>
     */
    @Test
    @DisplayName("fechada no X, a janela e descartada e nao apenas escondida")
    void closingWithTheCrossDisposesIt() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        base();
        workspaceWithAbadDate();

        AtomicReference<SeriesWindow> made = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            made.set(new SeriesWindow(null));

            // Given a peer without being put on screen. isDisplayable is
            // false until there is one, and "gone" is exactly the absence
            // of a peer -- so without this the assertion below would hold
            // for a window that was never built.
            made.get().pack();
        });

        assertTrue(made.get().isDisplayable(), "the window was never built");

        SwingUtilities.invokeAndWait(() -> made.get().dispatchEvent(
                new java.awt.event.WindowEvent(made.get(),
                        java.awt.event.WindowEvent.WINDOW_CLOSING)));

        assertTrue(!made.get().isDisplayable(),
                "the window was only hidden: it stays in Window.getWindows() for good, "
                        + "and every change of theme walks it again");
    }
}
