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
package br.com.jorge.reis.endeavourneo.domain.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading the base the first Endeavour wrote.
 *
 * <p>These pin a format this project does not own. The files on disk hold every
 * measurement the work so far rests on, and they are read by the other program
 * too — so a change here that "fixes" the layout does not fix anything, it
 * stops reading the base. The bytes are therefore written out by hand in these
 * tests, not through the reader, so the test would notice.</p>
 */
@DisplayName("Market file")
class MarketFileTest {

    /** Written by hand, the way the first Endeavour writes it. */
    private static void write(Path file, String magic, int version, int minutes,
                              long count, double[]... bars) throws IOException {
        ByteBuffer buffer = ByteBuffer
                .allocate(24 + bars.length * 48)
                .order(ByteOrder.BIG_ENDIAN);

        buffer.put(magic.getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(version);
        buffer.putInt(minutes);
        buffer.putLong(count);

        for (double[] bar : bars) {
            buffer.putLong((long) bar[0]);

            for (int i = 1; i < 6; i++) {
                buffer.putDouble(bar[i]);
            }
        }

        Files.write(file, buffer.array());
    }

    /** {time, open, high, low, close, volume} */
    private static double[] bar(long time, double open, double high,
                                double low, double close, double volume) {
        return new double[]{time, open, high, low, close, volume};
    }

    @Test
    @DisplayName("a file written the way the first Endeavour writes it is read back")
    void theFormatIsRead(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("winn-1m.bin");

        write(file, "ENDVCNDL", 1, 1, 2,
                bar(1_756_000_000_000L, 136_000, 136_100, 135_950, 136_050, 1_200),
                bar(1_756_000_060_000L, 136_050, 136_075, 135_800, 135_825, 900));

        PriceSeries series = MarketFile.read(file);

        assertEquals(2, series.size());
        assertEquals(1_756_000_000_000L, series.timeAt(0));
        assertEquals(136_000.0, series.openAt(0), 1e-9);
        assertEquals(136_100.0, series.highAt(0), 1e-9);
        assertEquals(135_950.0, series.lowAt(0), 1e-9);
        assertEquals(136_050.0, series.closeAt(0), 1e-9);
        assertEquals(1_200.0, series.volumeAt(0), 1e-9);

        // The second bar, so an error in the record size would not pass.
        assertEquals(1_756_000_060_000L, series.timeAt(1));
        assertEquals(135_825.0, series.closeAt(1), 1e-9);

        assertEquals(1, MarketFile.minutesOf(file));
    }

    @Test
    @DisplayName("a file that is not ours is refused, not drawn")
    void anotherFileIsRefused(@TempDir Path folder) throws IOException {
        // Without this check any file at all reads as prices, and the chart
        // draws whatever the bytes spell. Numbers on screen that nobody can
        // tell are wrong is the worst outcome available here.
        Path file = folder.resolve("notes.txt");

        Files.writeString(file, "these are words, not candles, and they are long enough");

        assertThrows(IOException.class, () -> MarketFile.read(file));
        assertFalse(MarketFile.isSeries(file));
    }

    @Test
    @DisplayName("a version this does not know is refused")
    void anotherVersionIsRefused(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("later.bin");

        write(file, "ENDVCNDL", 2, 1, 1,
                bar(1_756_000_000_000L, 1, 1, 1, 1, 1));

        assertThrows(IOException.class, () -> MarketFile.read(file));
    }

    @Test
    @DisplayName("a file cut short is refused rather than read half way")
    void aTruncatedFileIsRefused(@TempDir Path folder) throws IOException {
        // A failed copy is the likely cause, and half a base drawn without a
        // word would be taken for the whole of it.
        Path file = folder.resolve("cut.bin");

        write(file, "ENDVCNDL", 1, 1, 3,
                bar(1_756_000_000_000L, 1, 1, 1, 1, 1),
                bar(1_756_000_060_000L, 1, 1, 1, 1, 1));

        IOException thrown = assertThrows(IOException.class, () -> MarketFile.read(file));

        assertTrue(thrown.getMessage().contains("3"),
                "the message should say how many bars were promised: " + thrown.getMessage());
    }

    @Test
    @DisplayName("an empty series is a series, not an error")
    void anEmptyFileIsAllowed(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("empty.bin");

        write(file, "ENDVCNDL", 1, 1, 0);

        assertEquals(0, MarketFile.read(file).size());
        assertTrue(MarketFile.isSeries(file));
    }
}
