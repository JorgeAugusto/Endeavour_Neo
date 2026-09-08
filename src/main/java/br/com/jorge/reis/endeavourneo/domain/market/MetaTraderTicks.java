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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Turns a MetaTrader tick export into one {@link TickFile} per session.
 *
 * <p>The export is tab separated, with a header line and CRLF endings:</p>
 *
 * <pre>
 * &lt;DATE&gt; &lt;TIME&gt; &lt;BID&gt; &lt;ASK&gt; &lt;LAST&gt; &lt;VOLUME&gt; &lt;FLAGS&gt;
 * 2021.01.04  09:00:00.436  131000  112050              6
 * 2021.01.05  10:05:43.807                118450  1.00000000  88
 * </pre>
 *
 * <p><b>Parsed by hand, from bytes.</b> Not because it is clever but because
 * the file is four gigabytes and 87 million rows: splitting each row into seven
 * strings allocates 600 million objects to read numbers that are already there
 * in the bytes. Measured: 5,0 million rows a second this way, which is the
 * disk's own speed.</p>
 *
 * <p><b>An empty field is not a zero.</b> The export writes nothing at all
 * where the exchange said nothing, and writes {@code 0} where it really said
 * zero — the first row of a session does exactly that. The two are kept apart
 * all the way to the file; see {@link TickSeries}.</p>
 */
public final class MetaTraderTicks {

    /** The longest row seen in January 2021 is 60 bytes; this is room to spare. */
    private static final int LONGEST_ROW = 512;

    private static final int BUFFER = 1 << 20;

    private MetaTraderTicks() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** What one session's conversion produced. */
    public record Session(LocalDate date, Path file, long ticks) { }

    /**
     * @param csv the MetaTrader export
     * @param folder where the session files go
     * @param instrument names the files, as {@code <instrument>-<date>.bin}
     * @param progress told after each session; may be null
     * @return one entry per session written, in the order they appear
     */
    public static List<Session> convert(Path csv, Path folder, String instrument,
                                        Consumer<Session> progress) throws IOException {
        List<Session> written = new ArrayList<>();
        byte[] chunk = new byte[BUFFER];
        byte[] row = new byte[LONGEST_ROW];
        int inRow = 0;
        long rows = 0;

        // The session being written, and where. Held together because closing
        // one means recording what it produced, and doing that in two places is
        // how the last session ends up counted twice or not at all.
        LocalDate open = null;
        TickFile.Writer writer = null;

        // Where each field begins. One array for the whole file, filled again
        // for every row -- see the note at the call below.
        int[] starts = new int[8];

        try (InputStream in = new BufferedInputStream(Files.newInputStream(csv), BUFFER)) {
            int read;

            while ((read = in.read(chunk)) > 0) {
                for (int i = 0; i < read; i++) {
                    byte b = chunk[i];

                    if (b != '\n') {
                        if (b != '\r' && inRow < row.length) {
                            row[inRow++] = b;
                        }

                        continue;
                    }

                    // The first line names the columns.
                    if (rows++ > 0 && inRow > 0) {
                        LocalDate date = dateOf(row);

                        if (!date.equals(open)) {
                            finish(written, progress, open, writer,
                                    folder, instrument);

                            open = date;
                            writer = new TickFile.Writer(TickSource.METATRADER.fileFor(folder, instrument, date), date);
                        }

        // REUSED, not allocated per line. "Found once for the row" was true
        // about the searching and misleading about the cost: this was one int[8]
        // for every one of the 87 million rows, in a class whose javadoc says it
        // is written by hand from bytes precisely so that those rows do not
        // allocate 600 million objects. The row buffer beside it is reused for
        // the same reason; this one was not.
                        write(writer, row, inRow, starts);
                    }

                    inRow = 0;
                }
            }

            // Some exports end without a newline, so the last row is still here.
            if (rows > 0 && inRow > 0) {
                LocalDate date = dateOf(row);

                if (!date.equals(open)) {
                    finish(written, progress, open, writer, folder, instrument);

                    open = date;
                    writer = new TickFile.Writer(TickSource.METATRADER.fileFor(folder, instrument, date), date);
                }

                write(writer, row, inRow, starts);
            }

            finish(written, progress, open, writer, folder, instrument);

            writer = null;
        } catch (IOException | RuntimeException e) {
            // DISCARDED, not closed. Closing here would COMMIT whatever had been
            // written so far -- close() rewrites the header with the count it
            // managed -- and the result is a short session that looks whole. A
            // refused conversion would replace a good session with a day that
            // ends where the error was, and nothing would say so.
            //
            // AND CAUGHT RATHER THAN `finally`, so the cleanup cannot replace
            // the reason. An exception thrown from a finally block REPLACES the
            // one that was propagating, and the one propagating is the only
            // thing that says why the conversion failed. Anything that goes
            // wrong while throwing the half-written session away is attached to
            // it instead -- which is what try-with-resources does, and what a
            // hand-written cleanup has to do on purpose.
            if (writer != null) {
                try {
                    writer.discard();
                } catch (IOException failed) {
                    e.addSuppressed(failed);
                }
            }

            throw e;
        }

        return written;
    }

    /** Closes one session and records what it produced. */
    private static void finish(List<Session> written, Consumer<Session> progress,
                               LocalDate date, TickFile.Writer writer,
                               Path folder, String instrument) throws IOException {
        if (writer == null) {
            return;
        }

        long ticks = writer.count();

        writer.close();

        Session done = new Session(date, TickSource.METATRADER.fileFor(folder, instrument, date), ticks);

        written.add(done);

        if (progress != null) {
            progress.accept(done);
        }
    }

    private static LocalDate dateOf(byte[] row) {
        // yyyy.MM.dd, always ten bytes.
        return LocalDate.of(number(row, 0, 4), number(row, 5, 7), number(row, 8, 10));
    }

    private static void write(TickFile.Writer writer, byte[] row, int length,
            int[] starts) throws IOException {
        int millis = number(row, 11, 13) * 3_600_000
                + number(row, 14, 16) * 60_000
                + number(row, 17, 19) * 1_000
                + number(row, 20, 23);

        int fields = 0;

        starts[0] = 0;

        for (int i = 0; i < length && fields < 7; i++) {
            if (row[i] == '\t') {
                starts[++fields] = i + 1;
            }
        }

        if (fields < 6) {
            // Fewer than seven columns: not a row this understands. Refusing is
            // right -- a row half read is a tick with numbers in the wrong
            // places, which nothing downstream could detect.
            throw new IOException("a row with " + (fields + 1) + " columns: "
                    + new String(row, 0, length, java.nio.charset.StandardCharsets.US_ASCII));
        }

        int bid = whole(row, starts[2], starts[3] - 1, length);
        int ask = whole(row, starts[3], starts[4] - 1, length);
        int last = whole(row, starts[4], starts[5] - 1, length);
        int volume = whole(row, starts[5], starts[6] - 1, length);
        int flags = whole(row, starts[6], length, length);

        writer.add(millis,
                Math.max(bid, 0), Math.max(ask, 0), Math.max(last, 0), Math.max(volume, 0),
                Math.max(flags, 0),
                TickFile.Writer.mask(bid >= 0, ask >= 0, last >= 0, volume >= 0));
    }

    /**
     * @return the whole part of the number, or -1 when the field is empty
     * @throws IOException if the field is not a number
     *
     * <p>The whole part only, and that is measured rather than assumed: over
     * the 87 million rows of January 2021 not one price or volume has a
     * fraction. The export still writes volumes as {@code 2.00000000}, so the
     * fraction has to be skipped, not refused.</p>
     *
     * <p><b>A byte that is not a digit is REFUSED, and it used to be skipped.</b>
     * {@code 1x2} came out as 12, {@code -5} as 5, {@code 1 2} as 12 -- a price
     * with rubbish in the middle entered the base as a plausible wrong number
     * with nothing to detect it. Forty lines above, the column count refuses for
     * exactly this reason, in words: "a row half read is a tick with numbers in
     * the wrong places, which nothing downstream could detect". The same file
     * stated the policy and then did the opposite one field down.</p>
     *
     * <p>The other converter of this family already refuses, naming the file and
     * the line. This one now does the same, and the cost is one comparison that
     * was being made anyway.</p>
     */
    private static int whole(byte[] row, int from, int to, int length) throws IOException {
        if (to <= from) {
            return -1;
        }

        int value = 0;

        for (int i = from; i < to; i++) {
            byte b = row[i];

            if (b == '.') {
                break;
            }

            if (b < '0' || b > '9') {
                throw new IOException("\"" + new String(row, from, to - from,
                        java.nio.charset.StandardCharsets.US_ASCII)
                        + "\" is not a number, in: " + new String(row, 0, length,
                        java.nio.charset.StandardCharsets.US_ASCII));
            }

            value = value * 10 + (b - '0');
        }

        return value;
    }

    private static int number(byte[] row, int from, int to) {
        int value = 0;

        for (int i = from; i < to && i < row.length; i++) {
            byte b = row[i];

            if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
            }
        }

        return value;
    }
}
