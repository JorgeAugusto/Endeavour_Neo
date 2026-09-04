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
    @Test
    @DisplayName("the scale is read from the name, and it is the FIRST part")
    void theScaleComesFromTheName() {
        assertEquals("1m", SeriesCatalog.scaleOf("winfull-1m"));
        assertEquals("1s", SeriesCatalog.scaleOf("winfull-1s"));

        // The one that made this worth writing down. A year of minutes: taking
        // the last part would file it under a scale of "one year", which is a
        // recorte, not a scale. And 1y is not a scale code at all, so a reader
        // that scanned for the last MATCH would still get it wrong the day
        // somebody writes winfull-1m-2d.
        assertEquals("1m", SeriesCatalog.scaleOf("btcusdt-1m-1y"));
    }

    @Test
    @DisplayName("a name that says no scale gets none, rather than a guess")
    void anUnnamedScaleStaysEmpty() {
        // Empty hangs the series straight off its instrument in the tree. A
        // guess would put a heading there that nothing on disk agrees with.
        assertEquals("", SeriesCatalog.scaleOf("winfull"));
        assertEquals("", SeriesCatalog.scaleOf("win-diario"));
        assertEquals("", SeriesCatalog.scaleOf(null));
    }

    @Test
    @DisplayName("scales sort coarsest first, with the ticks last")
    void coarsestFirst() {
        List<String> scales = new java.util.ArrayList<>(
                List.of(SeriesCatalog.TICKS, "1s", "1d", "", "5m", "1m", "1h"));

        scales.sort(SeriesCatalog.coarsestFirst());

        // Reading down is zooming in, which is the order the reader listed them
        // in. The unreadable one goes after even the ticks: it is not finer
        // than a tick, it is unknown, and last is where unknown belongs.
        assertEquals(List.of("1d", "1h", "5m", "1m", "1s", SeriesCatalog.TICKS, ""), scales);
    }

    @Test
    @DisplayName("a minute is sixty seconds and a tick is none")
    void secondsPerBar() {
        assertEquals(1, SeriesCatalog.secondsOf("1s"));
        assertEquals(300, SeriesCatalog.secondsOf("5m"));
        assertEquals(3_600, SeriesCatalog.secondsOf("1h"));
        assertEquals(86_400, SeriesCatalog.secondsOf("1d"));
        assertEquals(0, SeriesCatalog.secondsOf(SeriesCatalog.TICKS));
        assertEquals(-1, SeriesCatalog.secondsOf("1y"));
    }
    @Test
    @DisplayName("a new scale of a known market is not a new market")
    void aScaleIsNotAMarket() {
        // What the tree caught. The markets used to be stated by whole file
        // name, which held only while every market had one scale: winfull-1s
        // fell out of the map, derived "winfull" from its own prefix, and
        // appeared as a second WIN beside the first -- with the same label, so
        // it read as a duplicate rather than as a bug.
        assertEquals("win", SeriesCatalog.groupOf("winfull-1m"));
        assertEquals("win", SeriesCatalog.groupOf("winfull-1s"));
        assertEquals("win", SeriesCatalog.groupOf("winn-1m"));
        assertEquals("win", SeriesCatalog.groupOf("winfut-1m"));

        // And the three exports still do not become three markets, which is
        // the older mistake this map exists to prevent.
        assertEquals(SeriesCatalog.groupOf("winn-1m"), SeriesCatalog.groupOf("winfut-1m"));

        // A market nobody stated is still its own prefix.
        assertEquals("ouro", SeriesCatalog.groupOf("ouro-1m"));
    }
}
