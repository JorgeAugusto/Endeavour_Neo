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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ticks written and read back, with nothing lost on the way.
 */
@DisplayName("Tick file")
class TickFileTest {

    /** Rows taken from the real export, including the awkward ones. */
    private static final List<String> ROWS = List.of(
            // The opening row really does say zero, for everything. It is not
            // an absent value, and this file has to keep the difference.
            "2021.01.04\t05:30:23.262\t0\t0\t0\t0.00000000\t0",
            // Bid and ask, no trade.
            "2021.01.04\t09:00:00.436\t131000\t112050\t\t\t6",
            // A trade, no quote. This is 93% of the file.
            "2021.01.04\t10:05:43.807\t\t\t118450\t1.00000000\t88",
            "2021.01.04\t10:05:43.835\t\t\t118455\t5.00000000\t56",
            // Two ticks in the same millisecond, which happens constantly.
            "2021.01.04\t10:05:43.898\t\t\t118450\t17.00000000\t88",
            "2021.01.04\t10:05:43.898\t\t\t118450\t3.00000000\t88",
            // A bid on its own.
            "2021.01.04\t19:30:00.217\t\t115010\t\t\t4",
            // The last row of a session: everything at once.
            "2021.01.04\t19:30:00.220\t114950\t115010\t115000\t2.00000000\t26");

    private static final String HEADER =
            "<DATE>\t<TIME>\t<BID>\t<ASK>\t<LAST>\t<VOLUME>\t<FLAGS>";

    private static Path exportOf(Path folder, String name, List<String> rows) throws IOException {
        Path csv = folder.resolve(name);
        StringBuilder text = new StringBuilder(HEADER).append("\r\n");

        for (String row : rows) {
            text.append(row).append("\r\n");
        }

        Files.write(csv, text.toString().getBytes(StandardCharsets.US_ASCII));

        return csv;
    }

    /**
     * @return the session written back out in the export's own format
     *
     * <p>This is the whole point of the test. If any column, any absent field
     * or any flag were dropped on the way in, the text that comes out of here
     * cannot match the text that went in.</p>
     */
    private static List<String> asExported(TickSeries session) {
        List<String> rows = new ArrayList<>(session.size());

        for (int i = 0; i < session.size(); i++) {
            int millis = session.millisAt(i);

            rows.add(String.format("%s\t%02d:%02d:%02d.%03d\t%s\t%s\t%s\t%s\t%d",
                    session.date().toString().replace('-', '.'),
                    millis / 3_600_000, millis / 60_000 % 60, millis / 1_000 % 60, millis % 1_000,
                    session.hasBid(i) ? String.valueOf(session.bidAt(i)) : "",
                    session.hasAsk(i) ? String.valueOf(session.askAt(i)) : "",
                    session.hasLast(i) ? String.valueOf(session.lastAt(i)) : "",
                    session.hasVolume(i)
                            ? String.format("%d.00000000", session.volumeAt(i)) : "",
                    session.flagsAt(i)));
        }

        return rows;
    }

    @Test
    @DisplayName("every column of the export survives the round trip, absences included")
    void nothingIsLost(@TempDir Path folder) throws IOException {
        Path csv = exportOf(folder, "WINFUT.csv", ROWS);

        List<MetaTraderTicks.Session> written =
                MetaTraderTicks.convert(csv, folder.resolve("ticks"), "winfut", null);

        assertEquals(1, written.size(), "one session in, one file out");
        assertEquals(ROWS.size(), written.get(0).ticks());

        TickSeries session = TickFile.read(written.get(0).file());

        assertEquals(ROWS, asExported(session),
                "a column, an absent field or a flag was lost between the two formats");
    }

    @Test
    @DisplayName("a zero the exchange sent is not the same as a field it left empty")
    void zeroIsNotAbsent(@TempDir Path folder) throws IOException {
        // The difference this file exists to keep. A book rebuilt from a series
        // that confused the two would show the bid collapsing to zero millions
        // of times a day.
        Path csv = exportOf(folder, "WINFUT.csv", ROWS);

        List<MetaTraderTicks.Session> written =
                MetaTraderTicks.convert(csv, folder.resolve("ticks"), "winfut", null);
        TickSeries session = TickFile.read(written.get(0).file());

        assertTrue(session.hasBid(0), "the opening row states a bid of zero, and it was dropped");
        assertEquals(0, session.bidAt(0));

        assertFalse(session.hasBid(2), "a row with no bid came back claiming one");
        assertTrue(session.hasLast(2));
        assertEquals(118_450, session.lastAt(2));
    }

    @Test
    @DisplayName("one file per session, named by its date")
    void oneFilePerSession(@TempDir Path folder) throws IOException {
        List<String> twoDays = new ArrayList<>(ROWS);

        twoDays.add("2021.01.05\t09:00:00.100\t\t\t118000\t2.00000000\t88");
        twoDays.add("2021.01.05\t09:00:00.200\t\t\t118005\t1.00000000\t56");

        Path csv = exportOf(folder, "WINFUT.csv", twoDays);
        Path ticks = folder.resolve("ticks");

        List<MetaTraderTicks.Session> written =
                MetaTraderTicks.convert(csv, ticks, "winfut", null);

        assertEquals(2, written.size());
        assertEquals(LocalDate.of(2021, 1, 4), written.get(0).date());
        assertEquals(LocalDate.of(2021, 1, 5), written.get(1).date());
        assertEquals(8, written.get(0).ticks());
        assertEquals(2, written.get(1).ticks());

        // Written literally, not through fileFor. This is the one place that
        // states the layout, and asserting it with the same expression that
        // builds it would agree with any layout at all.
        assertTrue(Files.isRegularFile(
                ticks.resolve("2021").resolve("01").resolve("winfut-2021-01-04.bin")),
                "the session is not under its year and month");
        assertTrue(Files.isRegularFile(
                ticks.resolve("2021").resolve("01").resolve("winfut-2021-01-05.bin")));

        assertEquals(LocalDate.of(2021, 1, 5), TickFile.dateOf(
                ticks.resolve("2021").resolve("01").resolve("winfut-2021-01-05.bin")));

        // The date stays in the name as well as in the path, so a file that
        // gets moved by hand still says which session it is.
        assertEquals(ticks.resolve("2021").resolve("01").resolve("winfut-2021-01-05.bin"),
                MetaTraderTicks.fileFor(ticks, "winfut", LocalDate.of(2021, 1, 5)));

        // A month is a folder, not a prefix: December must not land beside
        // January because both start with a "1".
        assertEquals(ticks.resolve("2021").resolve("12").resolve("winfut-2021-12-23.bin"),
                MetaTraderTicks.fileFor(ticks, "winfut", LocalDate.of(2021, 12, 23)));
    }

    @Test
    @DisplayName("the clock never runs backwards inside a session")
    void timeOnlyMovesForward(@TempDir Path folder) throws IOException {
        // Measured over the 87 million rows of January 2021: not once. If it
        // ever does happen, a replay would jump backwards without a word, so
        // this refuses instead.
        Path file = folder.resolve("winfut-2021-01-04.bin");

        try (TickFile.Writer writer = new TickFile.Writer(file, LocalDate.of(2021, 1, 4))) {
            writer.add(1_000, 0, 0, 118_450, 1, 88,
                    TickFile.Writer.mask(false, false, true, true));

            assertThrows(IllegalArgumentException.class, () -> writer.add(999, 0, 0,
                    118_450, 1, 88, TickFile.Writer.mask(false, false, true, true)));
        }
    }

    @Test
    @DisplayName("a file that is not ours is refused, not read as prices")
    void anotherFileIsRefused(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("notes.bin");

        Files.writeString(file, "these are words, and they are long enough to have a header");

        assertFalse(TickFile.isTicks(file));
        assertThrows(IOException.class, () -> TickFile.read(file));
    }

    @Test
    @DisplayName("a candle file and a tick file are not mistaken for each other")
    void theTwoFormatsDoNotOverlap(@TempDir Path folder) throws IOException {
        // Both live in the data folder and both end in .bin. Telling them apart
        // by their first eight bytes rather than by their name means a renamed
        // file is still read correctly, or refused.
        Path file = folder.resolve("winfut-2021-01-04.bin");

        try (TickFile.Writer writer = new TickFile.Writer(file, LocalDate.of(2021, 1, 4))) {
            writer.add(1_000, 0, 0, 118_450, 1, 88,
                    TickFile.Writer.mask(false, false, true, true));
        }

        assertTrue(TickFile.isTicks(file));
        assertFalse(MarketFile.isSeries(file), "a tick file was offered as a candle series");
    }

    @Test
    @DisplayName("an empty session is a session, not an error")
    void anEmptySessionIsAllowed(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("winfut-2021-01-25.bin");

        try (TickFile.Writer ignored = new TickFile.Writer(file, LocalDate.of(2021, 1, 25))) {
            // The 25th of January 2021 has two ticks in the real export, and a
            // holiday could have none at all.
        }

        assertEquals(0, TickFile.read(file).size());
        assertEquals(LocalDate.of(2021, 1, 25), TickFile.dateOf(file));
    }
}
