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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
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

    /** Tag, version, day, count -- what {@code TapeFile} writes before the trades. */
    private static final int HEADER_BYTES = 8 + Integer.BYTES + Integer.BYTES + Long.BYTES;

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

    /** One trade, and nobody named. */
    private static Path nameless(Path folder) throws IOException {
        Path file = folder.resolve("win-2026-09-01.tape");

        try (TapeFile.Writer writer = new TapeFile.Writer(file, LocalDate.of(2026, 9, 1))) {
            writer.add(9 * 3_600_000, 179_385, 500, 85, 262, Aggressor.DIRECT);
        }

        return file;
    }

    @Test
    @DisplayName("um dicionario vazio e escrito; um dicionario ausente e recusado")
    void theEmptyDictionaryIsWrittenAndAMissingOneIsRefused(@TempDir Path folder)
            throws IOException {
        // The two halves of the same defect. The writer skipped the dictionary
        // whenever it was empty, and the reader met a file ending exactly where
        // the trades stop -- which is ALSO what a truncated file looks like, and
        // what a converter that writes five million trades and forgets to name
        // the brokers produces. It read back clean with all thirty-one names
        // gone, in a format whose whole claim is that it drops no column, and
        // the comment guarding that branch said "a session in which nobody
        // traded".
        Path file = nameless(folder);
        TickSeries tape = TapeFile.read(file);

        assertEquals(1, tape.size(), "the trade did not survive an empty dictionary");
        assertNull(tape.brokerName(85), "a name arrived from a session that named nobody");

        // Now the same file with those four bytes gone: the shape the reader
        // used to accept, and the one it must now refuse.
        Path lost = folder.resolve("lost.tape");

        byte[] whole = Files.readAllBytes(file);

        Files.write(lost, Arrays.copyOf(whole, whole.length - Integer.BYTES));

        IOException thrown = assertThrows(IOException.class, () -> TapeFile.read(lost));

        assertTrue(thrown.getMessage().contains("no broker dictionary"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("lost.tape"), thrown.getMessage());
    }

    @Test
    @DisplayName("um agressor zero e recusado na leitura, com o nome do arquivo")
    void aZeroAggressorIsRefusedWhereTheFileIs(@TempDir Path folder) throws IOException {
        // The header promises a byte that is an Aggressor and never zero, and
        // nothing enforced it. A zero became the index -1 inside aggressorAt and
        // threw ArrayIndexOutOfBoundsException -- a RuntimeException, so it went
        // straight through TickLibrary.queue, which catches IOException, and
        // surfaced later on the painting or replay thread with nothing left to
        // say which file it came from.
        Path file = nameless(folder);
        byte[] whole = Files.readAllBytes(file);

        // The aggressor is the last byte of the record: millis, price, quantity,
        // buyer, seller, then this.
        whole[HEADER_BYTES + TapeFile.RECORD_BYTES - 1] = 0;

        Path damaged = folder.resolve("damaged.tape");

        Files.write(damaged, whole);

        IOException thrown = assertThrows(IOException.class, () -> TapeFile.read(damaged));

        assertTrue(thrown.getMessage().contains("damaged.tape"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("aggressor 0"), thrown.getMessage());
    }

    @Test
    @DisplayName("uma conversao recusada nao toca no pregao que ja estava no disco")
    void arefusedConversionLeavesTheGoodSessionAlone(@TempDir Path folder)
            throws IOException {
        // TWO defects that added up, and neither showed on screen.
        //
        // The writer opened the TARGET with TRUNCATE_EXISTING and wrote the
        // header before the first line of the export had been read: the session
        // already on disk -- complete, checked, possibly the only copy -- was
        // gone the moment the converter decided that day had started. And what
        // replaced it looked WHOLE, because the failure path closed the writer,
        // and close rewrites the header with the count of what it managed. The
        // size matched the count, the date read back, and the library listed the
        // day as exported: a session that "ended at 11:00" with nothing saying
        // so.
        //
        // The three refusal tests beside this one stop at assertThrows and never
        // look at the disk, which is why this stood.
        Path ticks = folder.resolve("ticks");

        // A good session, converted and read back, the way an import leaves one.
        ProfitTrades.convert(exportOf(folder, "bom.csv", NEWEST_FIRST), ticks, "win", null);

        Path session = TickSource.PROFIT.fileFor(ticks, "win",
                java.time.LocalDate.of(2026, 9, 1));

        assertTrue(java.nio.file.Files.isRegularFile(session), "the fixture wrote nothing");

        int whole = TapeFile.read(session).size();
        long bytes = java.nio.file.Files.size(session);

        assertEquals(5, whole, "the good session is not the five trades of the fixture");

        // The same day again, from an export that fails IN THE MIDDLE OF
        // WRITING. That distinction is the whole test: an unknown aggressor or
        // an oversize quantity is refused while the CSV is being READ, before a
        // writer for that day exists, so nothing on disk was ever at risk. A
        // trade that goes backwards in time is caught by add(), after the
        // trades before it are already in the file.
        //
        // The export arrives newest first and the converter walks it backwards,
        // so this order reaches add() as 09:00:00, 09:00:02, then 09:00:01.
        List<String> broken = List.of(
                "WINFUT;01/09/2026;09:00:01;3 - XP;179.390;1;85 - BTG;Comprador",
                "WINFUT;01/09/2026;09:00:02;3 - XP;179.395;1;85 - BTG;Comprador",
                "WINFUT;01/09/2026;09:00:00;85 - BTG;179.385;500;262 - MIRAE;Direto");

        assertThrows(IllegalArgumentException.class, () -> ProfitTrades.convert(
                exportOf(folder, "ruim.csv", broken), ticks, "win", null));

        assertEquals(whole, TapeFile.read(session).size(),
                "the refused conversion replaced the good session with a short one");
        assertEquals(bytes, java.nio.file.Files.size(session),
                "the session on disk was rewritten by a conversion that was refused");

        // And nothing was left lying about under the session's own name.
        assertFalse(java.nio.file.Files.exists(
                        session.resolveSibling(session.getFileName() + ".parcial")),
                "the half-written session was left on disk");
    }

    @Test
    @DisplayName("os codigos do agressor sao o FORMATO, e estao escritos aqui")
    void theaggressorCodesAreTheFileFormat() {
        // The byte written on the tape used to be ordinal() + 1: the order the
        // five values are DECLARED in was the file format, and nothing said so.
        // The class javadoc lists them by frequency -- seller first, then buyer
        // -- in a different order from the declarations, so tidying the
        // declarations to match the table, which is the most natural thing in
        // the world to do, would have swapped buyer for seller in every tape
        // ever written.
        //
        // Silent and total: the read goes on accepting the file, because the
        // byte is still inside the range, and the one column of the tape that
        // says DIRECTION starts saying the opposite. Forty-three million trades
        // were already converted when this was found.
        //
        // The numbers are written out here rather than derived, on purpose.
        // Deriving them would agree with whatever the code does, which is what
        // the round trip below already does and why it could never catch this.
        assertEquals(1, Aggressor.BUYER.code());
        assertEquals(2, Aggressor.SELLER.code());
        assertEquals(3, Aggressor.RLP.code());
        assertEquals(4, Aggressor.AUCTION.code());
        assertEquals(5, Aggressor.DIRECT.code());

        for (Aggressor each : Aggressor.values()) {
            assertEquals(each, Aggressor.ofCode(each.code()),
                    each + " does not read back as itself");
        }

        // And a byte that names nothing says so, instead of landing on whichever
        // value happens to sit at that index.
        assertNull(Aggressor.ofCode(0));
        assertNull(Aggressor.ofCode(6));
        assertNull(Aggressor.ofCode(-1));
    }

    @Test
    @DisplayName("um export com DOIS ativos e recusado, e nao gravado como se fosse um")
    void anexportOfTwoInstrumentsIsRefused(@TempDir Path folder) throws IOException {
        // The first column names the instrument, and it was located and never
        // read -- while this file's own javadoc claimed every column of the
        // export is kept. The instrument came from the caller's argument and
        // never from the file.
        //
        // So an export holding two instruments went into ONE folder as if it
        // were all one market. The rows interleave, so the same date is visited
        // again after the writer for it has been finished, and the day loop only
        // compares against the previous date. Nothing downstream could tell,
        // because the column that would say was never stored.
        List<String> mixed = new ArrayList<>(NEWEST_FIRST);

        mixed.add("WDOFUT;01/09/2026;09:00:00;3 - XP Investimentos CCTVM S/A;5.421,5;1;"
                + "85 - BTG Pactual CTVM S.A.;Comprador");

        Path csv = exportOf(folder, "mixed.csv", mixed);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ProfitTrades.convert(csv, folder.resolve("ticks"), "win", null),
                "an export of two instruments was written as if it were one market");

        assertTrue(thrown.getMessage().contains("WINFUT"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("WDOFUT"), thrown.getMessage());
    }
}
