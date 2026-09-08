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
}
