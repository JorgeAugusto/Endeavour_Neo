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
@DisplayName("Bases")
class BasesTest {

    @AfterEach
    void stopPointingAtTheTemporaryFolder() {
        Bases.useFolder(null);
        Bases.forget();
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

        Bases.useFolder(folder);

        assertEquals(List.of("winfut-1m", "winn-1m"), Bases.names(),
                "a file that is not a base must not be offered as one");
    }

    @Test
    @DisplayName("a base is read once and handed out again")
    void aBaseIsReadOnce(@TempDir Path folder) throws IOException {
        // Several windows on one instrument is the ordinary case, and reading
        // thirty megabytes for each of them is not.
        base(folder, "winn-1m", 136_000);
        Bases.useFolder(folder);

        PriceSeries first = Bases.open("winn-1m").orElseThrow();
        PriceSeries again = Bases.open("winn-1m").orElseThrow();

        assertSame(first, again, "the base was read a second time");
        assertEquals(136_000.0, first.closeAt(0), 1e-9);
    }

    @Test
    @DisplayName("asking for a base that is not there is an answer, not a failure")
    void anAbsentBaseIsEmpty(@TempDir Path folder) throws IOException {
        base(folder, "winn-1m", 136_000);
        Bases.useFolder(folder);

        Optional<PriceSeries> missing = Bases.open("does-not-exist");

        assertTrue(missing.isEmpty());
        assertFalse(Bases.has("does-not-exist"));
        assertTrue(Bases.has("winn-1m"));
    }

    @Test
    @DisplayName("the default is the search base when it is there")
    void theDefaultIsTheSearchBase(@TempDir Path folder) throws IOException {
        base(folder, "btcusdt-1m", 60_000);
        base(folder, "winn-1m", 136_000);
        Bases.useFolder(folder);

        assertEquals("winn-1m", Bases.defaultName(),
                "the same default the other program uses, so both show the same prices");
    }

    @Test
    @DisplayName("with no base at all, the default still names something")
    void theDefaultSurvivesAnEmptyFolder(@TempDir Path folder) {
        Bases.useFolder(folder);

        assertTrue(Bases.names().isEmpty());
        assertEquals("winn-1m", Bases.defaultName());
        assertFalse(Bases.has("winn-1m"));
    }
}
