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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Profit's Times &amp; Trades export, turned into one {@link TapeFile} a session.
 *
 * <pre>
 * Ativo;Data;Hora;Comprador;Preço;Quantidade;Vendedor;Tipo
 * WINFUT;01/09/2026;18:31:24;85 - BTG Pactual CTVM S.A.;182.390;6;85 - BTG ...;Leilão
 * </pre>
 *
 * <h2>Three things about this export that will bite</h2>
 *
 * <p><b>It arrives newest first.</b> The first line of the file is the last
 * trade of the session. And the clock only goes to the second, with up to 8.423
 * trades sharing one — so within a second the file's ORDER is the only record
 * of what happened first, and it has to be turned around rather than sorted.
 * Sorting by time would shuffle those 8.423 into an order nobody can recover.
 * This is why the session is held in memory and written backwards, instead of
 * streaming the way the tick converter does.</p>
 *
 * <p><b>It is not UTF-8.</b> Windows-1252, so "Preço" and "Leilão" arrive as
 * bytes no UTF-8 reader will accept.</p>
 *
 * <p><b>The dot is a thousands separator, in the price AND in the size.</b>
 * {@code 182.390} is a hundred and eighty-two thousand points, and
 * {@code 3.503} is three thousand five hundred and three contracts. Reading
 * either as a decimal gives a number that looks plausible and is wrong by a
 * factor of a thousand.</p>
 *
 * <h2>What it refuses</h2>
 *
 * <p>A word in the aggressor column that is not one of the five known ones, a
 * trade too large for the two bytes the format writes, a broker field with no
 * code. All of them stop the conversion rather than being filed under
 * "unknown": a value nobody can trust is worse than a conversion that has to be
 * run again.</p>
 */
public final class ProfitTrades {

    /** What the export is written in. Not UTF-8, and it will not be. */
    private static final Charset ENCODING = Charset.forName("windows-1252");

    private static final int BUFFER = 1 << 20;

    private ProfitTrades() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** One session written, for the caller to report. */
    public record Session(LocalDate date, Path file, int trades, long contracts) { }

    /**
     * @param csv the export, one file, however many sessions it holds
     * @param folder where the tick sessions live, as
     *               {@link br.com.jorge.reis.endeavourneo.platform.SeriesCatalog#ticksOf}
     *               gives it
     * @param instrument what the market is called here, e.g. {@code win}
     * @param progress told as each session is finished, or null
     * @return the sessions written, oldest first
     */
    public static List<Session> convert(Path csv, Path folder, String instrument,
            Consumer<Session> progress) throws IOException {
        Rows rows = read(csv);
        List<Session> written = new ArrayList<>();

        TapeFile.Writer writer = null;
        LocalDate open = null;
        int trades = 0;
        long contracts = 0;

        try {
            // Backwards, because the export is backwards. The oldest trade of
            // the session is the last line of the file.
            for (int i = rows.count - 1; i >= 0; i--) {
                LocalDate date = LocalDate.ofEpochDay(rows.day[i]);

                if (!date.equals(open)) {
                    if (writer != null) {
                        finish(written, progress, open, writer,
                                TickSource.PROFIT.fileFor(folder, instrument, open),
                                trades, contracts);
                    }

                    open = date;
                    trades = 0;
                    contracts = 0;
                    writer = new TapeFile.Writer(
                            TickSource.PROFIT.fileFor(folder, instrument, date), date);
                }

                int buyer = rows.buyer[i] & 0xFFFF;
                int seller = rows.seller[i] & 0xFFFF;

                writer.broker(buyer, rows.names.get(buyer));
                writer.broker(seller, rows.names.get(seller));
                writer.add(rows.millis[i], rows.price[i], rows.quantity[i] & 0xFFFF,
                        buyer, seller, Aggressor.values()[rows.aggressor[i] - 1]);

                trades++;
                contracts += rows.quantity[i] & 0xFFFF;
            }

            if (writer != null) {
                finish(written, progress, open, writer,
                        TickSource.PROFIT.fileFor(folder, instrument, open), trades, contracts);

                writer = null;
            }
        } finally {
            // Only reached when something threw: the normal path closed it
            // above. Without this a failed conversion leaves a file open and a
            // header still claiming zero trades.
            if (writer != null) {
                writer.close();
            }
        }

        return written;
    }

    private static void finish(List<Session> written, Consumer<Session> progress,
            LocalDate date, TapeFile.Writer writer, Path file, int trades, long contracts)
            throws IOException {
        writer.close();

        Session done = new Session(date, file, trades, contracts);

        written.add(done);

        if (progress != null) {
            progress.accept(done);
        }
    }

    /**
     * The whole export in primitive arrays.
     *
     * <p>Fifteen bytes a trade, so a session is around eighty megabytes and the
     * largest file measured is ninety-four. Objects would be five times that
     * and the reversal needs all of it at once.</p>
     */
    private static final class Rows {

        private int count;

        private int[] day = new int[1 << 20];

        private int[] millis = new int[1 << 20];

        private int[] price = new int[1 << 20];

        private short[] quantity = new short[1 << 20];

        private short[] buyer = new short[1 << 20];

        private short[] seller = new short[1 << 20];

        private byte[] aggressor = new byte[1 << 20];

        private final Map<Integer, String> names = new HashMap<>();

        void room() {
            if (count < day.length) {
                return;
            }

            int bigger = day.length * 2;

            day = Arrays.copyOf(day, bigger);
            millis = Arrays.copyOf(millis, bigger);
            price = Arrays.copyOf(price, bigger);
            quantity = Arrays.copyOf(quantity, bigger);
            buyer = Arrays.copyOf(buyer, bigger);
            seller = Arrays.copyOf(seller, bigger);
            aggressor = Arrays.copyOf(aggressor, bigger);
        }
    }

    private static Rows read(Path csv) throws IOException {
        Rows rows = new Rows();

        try (InputStream in = new BufferedInputStream(Files.newInputStream(csv), BUFFER)) {
            byte[] chunk = new byte[BUFFER];
            byte[] row = new byte[512];
            int inRow = 0;
            long lines = 0;
            int read;

            while ((read = in.read(chunk)) > 0) {
                for (int i = 0; i < read; i++) {
                    byte at = chunk[i];

                    if (at != '\n') {
                        if (at != '\r' && inRow < row.length) {
                            row[inRow++] = at;
                        }

                        continue;
                    }

                    if (lines++ > 0 && inRow > 0) {
                        parse(csv, rows, row, inRow, lines);
                    }

                    inRow = 0;
                }
            }

            // Some exports end without a newline, so the last row is still here.
            if (lines > 0 && inRow > 0) {
                parse(csv, rows, row, inRow, ++lines);
            }
        }

        return rows;
    }

    private static void parse(Path csv, Rows rows, byte[] row, int length, long line) {
        int[] ends = new int[8];
        int fields = 0;

        for (int i = 0; i < length && fields < 8; i++) {
            if (row[i] == ';') {
                ends[fields++] = i;
            }
        }

        if (fields != 7) {
            throw new IllegalArgumentException(csv + ", line " + line + ": expected eight "
                    + "columns and found " + (fields + 1));
        }

        int date = ends[0] + 1;
        int time = ends[1] + 1;

        rows.room();

        rows.day[rows.count] = (int) LocalDate.of(
                number(csv, row, date + 6, date + 10, line),
                number(csv, row, date + 3, date + 5, line),
                number(csv, row, date, date + 2, line)).toEpochDay();

        rows.millis[rows.count] = number(csv, row, time, time + 2, line) * 3_600_000
                + number(csv, row, time + 3, time + 5, line) * 60_000
                + number(csv, row, time + 6, time + 8, line) * 1_000;

        rows.price[rows.count] = grouped(csv, row, ends[3] + 1, ends[4], line);

        int quantity = grouped(csv, row, ends[4] + 1, ends[5], line);

        if (quantity < 1 || quantity > TapeFile.LARGEST) {
            throw new IllegalArgumentException(csv + ", line " + line + ": " + quantity
                    + " contracts does not fit the two bytes the tape writes");
        }

        rows.quantity[rows.count] = (short) quantity;
        rows.buyer[rows.count] = (short) broker(csv, rows, row, ends[2] + 1, ends[3], line);
        rows.seller[rows.count] = (short) broker(csv, rows, row, ends[5] + 1, ends[6], line);

        String word = new String(row, ends[6] + 1, length - ends[6] - 1, ENCODING);
        Aggressor aggressor = Aggressor.of(word);

        if (aggressor == null) {
            throw new IllegalArgumentException(csv + ", line " + line + ": \"" + word
                    + "\" is not an aggressor this knows, and guessing would make the one "
                    + "column that says direction the one nobody could trust");
        }

        rows.aggressor[rows.count] = (byte) (aggressor.ordinal() + 1);
        rows.count++;
    }

    /**
     * @return the broker's code, remembering its name the first time it appears
     *
     * <p>The name is decoded once per code rather than once per trade. Five
     * million trades and thirty-one brokers: decoding every one would be five
     * million strings thrown away.</p>
     */
    private static int broker(Path csv, Rows rows, byte[] row, int from, int to, long line) {
        int dash = -1;

        for (int i = from; i < to - 1; i++) {
            if (row[i] == ' ' && row[i + 1] == '-') {
                dash = i;

                break;
            }
        }

        if (dash < 0) {
            throw new IllegalArgumentException(csv + ", line " + line + ": \""
                    + new String(row, from, to - from, ENCODING)
                    + "\" has no broker code, and a trade with an unnamed counterparty is "
                    + "the one thing this format is for");
        }

        int code = number(csv, row, from, dash, line);

        if (code > TapeFile.LARGEST) {
            throw new IllegalArgumentException(csv + ", line " + line + ": broker code "
                    + code + " does not fit the two bytes the tape writes");
        }

        // Copied because the lambda needs it final. It runs at once, so the
        // reused row buffer is still this trade's when the name is decoded.
        final int name = dash + 3;

        rows.names.computeIfAbsent(code, key -> new String(row, name, to - name, ENCODING));

        return code;
    }

    /** @return the number, ignoring the dots that group its thousands */
    private static int grouped(Path csv, byte[] row, int from, int to, long line) {
        int value = 0;

        for (int i = from; i < to; i++) {
            byte at = row[i];

            if (at == '.') {
                continue;
            }

            if (at < '0' || at > '9') {
                throw new IllegalArgumentException(csv + ", line " + line + ": \""
                        + new String(row, from, to - from, ENCODING) + "\" is not a number");
            }

            value = value * 10 + (at - '0');
        }

        return value;
    }

    private static int number(Path csv, byte[] row, int from, int to, long line) {
        int value = 0;

        for (int i = from; i < to; i++) {
            byte at = row[i];

            if (at < '0' || at > '9') {
                throw new IllegalArgumentException(csv + ", line " + line
                        + ": expected digits at " + from + " and found \"" + (char) at + "\"");
            }

            value = value * 10 + (at - '0');
        }

        return value;
    }
}
