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
package br.com.jorge.reis.endeavourneo.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Finding the bases and reading one without reading it twice.
 */
@DisplayName("SeriesCatalog")
class SeriesCatalogTest {

    @AfterEach
    void stopPointingAtTheTemporaryFolder() {
        SeriesCatalog.useFolderForTest(null);
        SeriesCatalog.forget();
    }

    /** A base of one bar, written the way the first Endeavour writes it. */
    private static void base(Path folder, String name, double close) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(24 + 48).order(ByteOrder.BIG_ENDIAN);

        buffer.put("ENDVCNDL".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(1);
        buffer.putInt(1);
        buffer.putLong(1);
        buffer.putLong(1_756_000_000_000L);
        buffer.putDouble(close);
        buffer.putDouble(close);
        buffer.putDouble(close);
        buffer.putDouble(close);
        buffer.putDouble(10);

        Files.write(folder.resolve(name + ".bin"), buffer.array());
    }

    @Test
    @DisplayName("the bases in the folder are listed by name, sorted")
    void basesAreListed(@TempDir Path folder) throws IOException {
        base(folder, "winn-1m", 136_000);
        base(folder, "winfut-1m", 104_000);
        Files.writeString(folder.resolve("readme.txt"), "not a base");
        Files.write(folder.resolve("broken.bin"), "not a base either, but named like one"
                .getBytes(StandardCharsets.UTF_8));

        SeriesCatalog.useFolderForTest(folder);

        assertEquals(List.of("winfut-1m", "winn-1m"), SeriesCatalog.names(),
                "a file that is not a base must not be offered as one");
    }

    @Test
    @DisplayName("a base is read once and handed out again")
    void aBaseIsReadOnce(@TempDir Path folder) throws IOException {
        // Several windows on one instrument is the ordinary case, and reading
        // thirty megabytes for each of them is not.
        base(folder, "winn-1m", 136_000);
        SeriesCatalog.useFolderForTest(folder);

        PriceSeries first = SeriesCatalog.open("winn-1m").orElseThrow();
        PriceSeries again = SeriesCatalog.open("winn-1m").orElseThrow();

        assertSame(first, again, "the base was read a second time");
        assertEquals(136_000.0, first.closeAt(0), 1e-9);
    }

    @Test
    @DisplayName("asking for a base that is not there is an answer, not a failure")
    void anAbsentBaseIsEmpty(@TempDir Path folder) throws IOException {
        base(folder, "winn-1m", 136_000);
        SeriesCatalog.useFolderForTest(folder);

        Optional<PriceSeries> missing = SeriesCatalog.open("does-not-exist");

        assertTrue(missing.isEmpty());
        assertFalse(SeriesCatalog.has("does-not-exist"));
        assertTrue(SeriesCatalog.has("winn-1m"));
    }

    @Test
    @DisplayName("a retired base is not offered, and its file is left alone")
    void aRetiredBaseIsHiddenNotDeleted(@TempDir Path folder) throws IOException {
        // win-1m is the WIN adjusted by ratio: in it a point was worth R$ 0,20
        // in 2026 and R$ 0,12 in 2022, so the older years came out inflated by
        // up to 67%. Offering it beside the raw ones is how a measurement gets
        // taken on the wrong base by accident.
        base(folder, "winn-1m", 136_000);
        base(folder, "win-1m", 130_000);
        SeriesCatalog.useFolderForTest(folder);

        assertEquals(List.of("winn-1m"), SeriesCatalog.names(),
                "the retired base was offered in the listing");

        assertTrue(Files.isRegularFile(folder.resolve("win-1m.bin")),
                "the file was deleted; it was only meant to be hidden");

        // Asked for by name it still opens, so a workspace that remembers it is
        // not silently handed a different instrument.
        assertTrue(SeriesCatalog.has("win-1m"));
        assertEquals(130_000.0, SeriesCatalog.open("win-1m").orElseThrow().closeAt(0), 1e-9);
    }

    @Test
    @DisplayName("the default is the source, when it is there")
    void theDefaultIsTheSource(@TempDir Path folder) throws IOException {
        // The one series checked against the reference product, with the
        // search-and-test boundary inside it as segments. Opening a raw export
        // by default would put a chart on screen that nobody vetted.
        base(folder, "btcusdt-1m", 60_000);
        base(folder, "winn-1m", 136_000);
        base(folder, "winfull-1m", 136_000);
        SeriesCatalog.useFolderForTest(folder);

        assertEquals("winfull-1m", SeriesCatalog.defaultName());
    }

    @Test
    @DisplayName("with no base at all, the default still names something")
    void theDefaultSurvivesAnEmptyFolder(@TempDir Path folder) {
        SeriesCatalog.useFolderForTest(folder);

        assertTrue(SeriesCatalog.names().isEmpty());
        assertEquals("winfull-1m", SeriesCatalog.defaultName());
        assertFalse(SeriesCatalog.has("winfull-1m"));
    }
}
