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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tape: written, read back, and nothing dropped on the way.
 */
@DisplayName("Tape file")
class TapeFileTest {

    private static final Charset ENCODING = Charset.forName("windows-1252");

    private static final String HEAD = "Ativo;Data;Hora;Comprador;Preço;Quantidade;"
            + "Vendedor;Tipo";

    /**
     * A session as the export gives it: NEWEST FIRST.
     *
     * <p>Written in that order on purpose. A fixture in chronological order
     * would let a converter that never reverses anything pass every test
     * here.</p>
     */
    private static final List<String> NEWEST_FIRST = List.of(
            "WINFUT;01/09/2026;18:31:24;85 - BTG Pactual CTVM S.A.;182.390;6;"
                    + "85 - BTG Pactual CTVM S.A.;Leilão",
            "WINFUT;01/09/2026;10:15:03;3 - XP Investimentos CCTVM S/A;182.100;3.503;"
                    + "7035 - Corretora Única – Invest’s;Vendedor",
            "WINFUT;01/09/2026;09:00:01;262 - MIRAE ASSET;179.395;2;"
                    + "3 - XP Investimentos CCTVM S/A;RLP",
            "WINFUT;01/09/2026;09:00:01;3 - XP Investimentos CCTVM S/A;179.390;1;"
                    + "85 - BTG Pactual CTVM S.A.;Comprador",
            "WINFUT;01/09/2026;09:00:00;85 - BTG Pactual CTVM S.A.;179.385;500;"
                    + "262 - MIRAE ASSET;Direto");

    private static Path exportOf(Path folder, String name, List<String> rows)
            throws IOException {
        List<String> lines = new ArrayList<>();

        lines.add(HEAD);
        lines.addAll(rows);

        Path csv = folder.resolve(name);

        Files.write(csv, String.join("\r\n", lines).getBytes(ENCODING));

        return csv;
    }

    /** @return the session written back out as the export's own text */
    private static List<String> asExport(TickSeries tape) {
        List<String> lines = new ArrayList<>();

        for (int i = tape.size() - 1; i >= 0; i--) {
            int millis = tape.millisAt(i);

            lines.add(String.join(";",
                    "WINFUT",
                    tape.date().format(java.time.format.DateTimeFormatter.ofPattern(
                            "dd/MM/yyyy")),
                    String.format("%02d:%02d:%02d", millis / 3_600_000,
                            millis / 60_000 % 60, millis / 1_000 % 60),
                    tape.buyerAt(i) + " - " + tape.brokerName(tape.buyerAt(i)),
                    grouped(tape.lastAt(i)),
                    grouped(tape.volumeAt(i)),
                    tape.sellerAt(i) + " - " + tape.brokerName(tape.sellerAt(i)),
                    tape.aggressorAt(i).said()));
        }

        return lines;
    }

    private static String grouped(int value) {
        return value < 1000 ? String.valueOf(value)
                : grouped(value / 1000) + "." + String.format("%03d", value % 1000);
    }

    @Test
    @DisplayName("the export goes in and comes back out, to the character")
    void nothingIsLost(@TempDir Path folder) throws IOException {
        // The whole claim of the format in one assertion. Every column of the
        // export is reconstructed from the file alone -- time, price, size,
        // both brokers with their names, and who crossed. Anything dropped
        // shows up here as a difference.
        Path csv = exportOf(folder, "WINFUT_F_0_Trade_01-09-2026.csv", NEWEST_FIRST);

        List<ProfitTrades.Session> written =
                ProfitTrades.convert(csv, folder.resolve("ticks"), "win", null);

        assertEquals(1, written.size());
        assertEquals(LocalDate.of(2026, 9, 1), written.get(0).date());
        assertEquals(5, written.get(0).trades());
        assertEquals(4012, written.get(0).contracts(), "6 + 3503 + 2 + 1 + 500");

        assertEquals(NEWEST_FIRST, asExport(TapeFile.read(written.get(0).file())));
    }

    @Test
    @DisplayName("the file comes out oldest first, and ties keep the export's order")
    void theTapeIsTurnedAround(@TempDir Path folder) throws IOException {
        // The subtle half. Reversing is easy to see; the ties are not. Two of
        // the fixture's trades share 09:00:01, and within one second the
        // export's ORDER is the only record of which came first -- the clock
        // cannot say, and the real sessions put up to 8.423 trades in one
        // second. Sorting by time would shuffle them beyond recovery.
        Path csv = exportOf(folder, "trades.csv", NEWEST_FIRST);

        TickSeries tape = TapeFile.read(ProfitTrades
                .convert(csv, folder.resolve("ticks"), "win", null).get(0).file());

        for (int i = 1; i < tape.size(); i++) {
            assertTrue(tape.millisAt(i) >= tape.millisAt(i - 1),
                    "the tape plays backwards at " + i);
        }

        assertEquals(179_385, tape.lastAt(0), "the oldest trade is not first");
        assertEquals(182_390, tape.lastAt(tape.size() - 1), "the newest trade is not last");

        // The two that tie. In the export the MIRAE print comes before the XP
        // one; reversed, XP is first. Same second, opposite order, and only the
        // file's order says so.
        assertEquals(tape.millisAt(1), tape.millisAt(2), "the fixture lost its tie");
        assertEquals(179_390, tape.lastAt(1), "the tie came back in the wrong order");
        assertEquals(179_395, tape.lastAt(2), "the tie came back in the wrong order");
    }

    @Test
    @DisplayName("the dot groups thousands, in the price and in the size alike")
    void theDotIsNotADecimalPoint(@TempDir Path folder) throws IOException {
        // Read as a decimal, 182.100 is a hundred and eighty-two -- plausible
        // for something, and wrong by a factor of a thousand. Same for 3.503
        // contracts, which is where it would be missed.
        Path csv = exportOf(folder, "trades.csv", NEWEST_FIRST);

        TickSeries tape = TapeFile.read(ProfitTrades
                .convert(csv, folder.resolve("ticks"), "win", null).get(0).file());

        assertEquals(182_100, tape.lastAt(3));
        assertEquals(3_503, tape.volumeAt(3));
    }

    @Test
    @DisplayName("the brokers keep their accents, and are kept once")
    void theBrokersSurvive(@TempDir Path folder) throws IOException {
        // Windows-1252 in, UTF-8 out. A name mangled here would be mangled in
        // every report built on the tape, and nobody would know where it went.
        Path csv = exportOf(folder, "trades.csv", NEWEST_FIRST);
        Path file = ProfitTrades.convert(csv, folder.resolve("ticks"), "win", null)
                .get(0).file();
        TickSeries tape = TapeFile.read(file);

        // The accents pin windows-1252 specifically, not merely "some
        // eight-bit encoding". The en dash and the apostrophe live at 0x96 and
        // 0x92, which is the one range where windows-1252 and ISO-8859-1
        // disagree -- without them, latin-1 passes every assertion here and the
        // day a broker name carries one it comes back as a control character.
        assertEquals("Corretora Única – Invest’s", tape.brokerName(7035));
        assertEquals("BTG Pactual CTVM S.A.", tape.brokerName(85));
        assertEquals("XP Investimentos CCTVM S/A", tape.brokerName(3));

        // Four distinct brokers over five trades, each name held once at the
        // end of the file rather than on every row.
        long records = 24L + 5L * TapeFile.RECORD_BYTES;
        long names = Files.size(file) - records;

        assertTrue(names > 0 && names < 200,
                "the dictionary is " + names + " bytes, which is not four names held once");
    }

    @Test
    @DisplayName("the source encoding is what the accents need")
    void theBuildReadsThisFileRight() {
        // Pins the build, not the code. Aggressor spells the word with a real
        // accent in its own source; if javac ever reads these files as anything
        // but UTF-8 the word silently stops matching the export and every
        // auction print is refused.
        assertSame(Aggressor.AUCTION, Aggressor.of("Leilão"));
        assertSame(Aggressor.RLP, Aggressor.of("RLP"));
    }

    @Test
    @DisplayName("a word nobody knows stops the conversion")
    void anUnknownAggressorIsRefused(@TempDir Path folder) throws IOException {
        // Filing a sixth kind of print under "unknown" would make the one
        // column that says DIRECTION the one nobody could trust. Refusing costs
        // a rerun; guessing costs every number built on it afterwards.
        Path csv = exportOf(folder, "trades.csv", List.of(
                "WINFUT;01/09/2026;09:00:00;3 - XP;179.385;1;85 - BTG;Sabe-se la"));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ProfitTrades.convert(csv, folder.resolve("ticks"), "win", null));

        assertTrue(thrown.getMessage().contains("Sabe-se la"), thrown.getMessage());
    }

    @Test
    @DisplayName("a trade too big for the field is refused, never truncated")
    void anOversizeTradeIsRefused(@TempDir Path folder) throws IOException {
        // The largest trade measured is 15.000 contracts, and two bytes hold
        // 65.535. A maximum observed is not a limit, though, and a field that
        // saturates in silence is exactly the loss this format exists to
        // prevent -- so it says so instead.
        Path csv = exportOf(folder, "trades.csv", List.of(
                "WINFUT;01/09/2026;09:00:00;3 - XP;179.385;70.000;85 - BTG;Comprador"));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ProfitTrades.convert(csv, folder.resolve("ticks"), "win", null));

        assertTrue(thrown.getMessage().contains("70000"), thrown.getMessage());
    }

    @Test
    @DisplayName("a counterparty with no code stops the conversion")
    void anUnnamedBrokerIsRefused(@TempDir Path folder) throws IOException {
        Path csv = exportOf(folder, "trades.csv", List.of(
                "WINFUT;01/09/2026;09:00:00;Alguem;179.385;1;85 - BTG;Comprador"));

        assertThrows(IllegalArgumentException.class,
                () -> ProfitTrades.convert(csv, folder.resolve("ticks"), "win", null));
    }

    @Test
    @DisplayName("one export holding two sessions becomes two files")
    void eachSessionGetsItsOwnFile(@TempDir Path folder) throws IOException {
        List<String> twoDays = new ArrayList<>();

        twoDays.add("WINFUT;02/09/2026;09:00:01;3 - XP;182.100;1;85 - BTG;Comprador");
        twoDays.add("WINFUT;02/09/2026;09:00:00;3 - XP;182.050;2;85 - BTG;Vendedor");
        twoDays.addAll(NEWEST_FIRST);

        Path ticks = folder.resolve("ticks");
        List<ProfitTrades.Session> written =
                ProfitTrades.convert(exportOf(folder, "trades.csv", twoDays), ticks, "win", null);

        assertEquals(2, written.size());
        assertEquals(LocalDate.of(2026, 9, 1), written.get(0).date(), "not oldest first");
        assertEquals(LocalDate.of(2026, 9, 2), written.get(1).date());

        assertEquals(ticks.resolve("profit").resolve("2026").resolve("09")
                .resolve("win-2026-09-02.tape"), written.get(1).file());

        assertTrue(TapeFile.isTape(written.get(1).file()));
        assertEquals(2, TapeFile.read(written.get(1).file()).size());
    }

    @Test
    @DisplayName("the tape says it has no quotes rather than answering zero")
    void theTapeDeclinesTheBook(@TempDir Path folder) throws IOException {
        // The seam between the two formats. Anything reading a series asks
        // whether it HAS the field; a tape that answered zero for the bid would
        // put a book on screen that never existed.
        Path csv = exportOf(folder, "trades.csv", NEWEST_FIRST);

        TickSeries tape = TapeFile.read(ProfitTrades
                .convert(csv, folder.resolve("ticks"), "win", null).get(0).file());

        assertFalse(tape.hasBid(0));
        assertFalse(tape.hasAsk(0));

        // And every row of the tape is a print, which is where it is simpler
        // than the tick export: no quote-only row to filter out, and no way to
        // mistake one for a trade at price zero.
        for (int i = 0; i < tape.size(); i++) {
            assertTrue(tape.hasLast(i));
            assertTrue(tape.lastAt(i) > 0);
            assertTrue(tape.hasAggressor(i));
        }
    }
}
