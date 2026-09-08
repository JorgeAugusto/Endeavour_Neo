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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One session of the tape on disk, losslessly.
 *
 * <p>Every column of Profit's Times &amp; Trades export is kept but ONE: the
 * time, the price, the size, both brokers and who crossed. The test that proves
 * it writes a session, reads it back, prints it as the original text and
 * compares — anything dropped shows up as a difference.</p>
 *
 * <p>The one left out is <b>Ativo</b>, the instrument's own ticker. A tape file
 * is one instrument for one session and takes its name from the folder it is
 * written into, so the column would be the same word on every row of every file.
 * It is not stored, and this paragraph used to say it was — which mattered,
 * because the column was also not READ: an export holding two instruments went
 * into one folder as if it were all one market, and nothing afterwards could
 * tell. The converter refuses that now.</p>
 *
 * <pre>
 * header   ENDVTAPE   8 bytes, ASCII
 *          version    int, 1
 *          epochDay   int, which session this is
 *          count      long, how many trades follow
 * trade    millis     int, since midnight
 *          price      int
 *          quantity   short, unsigned
 *          buyer      short, unsigned, the broker's code
 *          seller     short, unsigned
 *          aggressor  byte, an {@link Aggressor} and never zero
 * brokers  count      int, always written, zero included
 *          code       short, unsigned
 *          length     short, the name's bytes
 *          name       UTF-8
 * </pre>
 *
 * <p><b>The count is written even when it is zero</b>, which is what separates
 * "this session named no brokers" from "this file lost its dictionary". It used
 * to be skipped on an empty dictionary, and the reader met a file ending exactly
 * where the trades stop -- the same shape either way. A converter that wrote
 * five million trades and forgot to name the brokers produced a file that read
 * back clean with every name gone, in a format whose whole point is to drop no
 * column.</p>
 *
 * <h2>Why the brokers are at the END</h2>
 *
 * <p>So the writer can stream. Which brokers appear is only known once the
 * whole session has been read, and a dictionary at the front would mean holding
 * five million trades in memory to write the first byte. At the back it needs
 * no offset in the header either: the trades are fixed width, so the dictionary
 * begins exactly where they stop.</p>
 *
 * <h2>Why the names are not on the rows</h2>
 *
 * <p>Thirty-one brokers, five million trades. The names on the rows would be a
 * hundred megabytes of the same thirty-one strings, and the code alone is what
 * anything actually groups by.</p>
 *
 * <h2>Why two bytes for the size and the codes</h2>
 *
 * <p>Measured over the eight sessions of 04/09/2026: the largest single trade
 * is 15.000 contracts and the highest broker code is 7035, so 65.535 leaves
 * room to spare. But a maximum observed is not a limit, and a field that
 * saturates in silence is the kind of loss this whole format exists to avoid —
 * so anything larger is REFUSED rather than truncated. Two bytes instead of
 * four across three fields is six bytes a trade, which is thirty megabytes a
 * session.</p>
 */
public final class TapeFile {

    private static final byte[] MAGIC = "ENDVTAPE".getBytes(StandardCharsets.US_ASCII);

    private static final int VERSION = 1;

    /**
     * The aggressors, held once.
     *
     * <p>{@code values()} clones its array on every call, and this is asked per
     * trade over sessions of five million.</p>
     */
    private static final int HEADER_BYTES = 8 + Integer.BYTES + Integer.BYTES + Long.BYTES;

    static final int RECORD_BYTES = 2 * Integer.BYTES + 3 * Short.BYTES + 1;

    private static final int CHUNK = 16_384;

    /** The largest a size or a broker code may be before this refuses. */
    static final int LARGEST = 65_535;

    private TapeFile() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @return whether the file is a tape of ours, without reading the trades */
    public static boolean isTape(Path file) {
        // THIS file's version, not TickFile's. The two are 1 today, so the
        // old call worked by coincidence -- and the day the tape goes to 2,
        // every valid tape would answer false here and the Profit source
        // would vanish from the list with no error anywhere.
        return TickFile.sessionOf(file, "ENDVTAPE", VERSION) != null;
    }

    /**
     * @param file a file written by {@link Writer}
     * @return every trade in it, with the brokers named
     * @throws IOException if the file is missing, truncated or not one of ours
     */
    public static TickSeries read(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer head = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);

            fill(channel, head, file);
            head.flip();

            byte[] magic = new byte[MAGIC.length];

            head.get(magic);

            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException(file + ": not an Endeavour tape file");
            }

            int version = head.getInt();

            if (version != VERSION) {
                throw new IOException(file + ": version " + version + " is not one this reads");
            }

            int epochDay = head.getInt();
            long promised = head.getLong();

            if (promised < 0 || promised > Integer.MAX_VALUE) {
                throw new IOException(file + ": " + promised + " is not a number of trades");
            }

            int size = (int) promised;
            long brokersAt = (long) HEADER_BYTES + (long) size * RECORD_BYTES;

            if (channel.size() < brokersAt) {
                throw new IOException(file + ": the header promises " + size
                        + " trades, which needs " + brokersAt + " bytes, and the file has "
                        + channel.size());
            }

            int[] millis = new int[size];
            int[] price = new int[size];
            int[] quantity = new int[size];
            int[] buyer = new int[size];
            int[] seller = new int[size];
            byte[] aggressor = new byte[size];

            ByteBuffer buffer = ByteBuffer.allocate(CHUNK * RECORD_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);

            for (int at = 0; at < size; ) {
                int wanted = Math.min(CHUNK, size - at);

                buffer.clear();
                buffer.limit(wanted * RECORD_BYTES);
                fill(channel, buffer, file);
                buffer.flip();

                for (int i = 0; i < wanted; i++, at++) {
                    millis[at] = buffer.getInt();
                    price[at] = buffer.getInt();
                    quantity[at] = buffer.getShort() & 0xFFFF;
                    buyer[at] = buffer.getShort() & 0xFFFF;
                    seller[at] = buffer.getShort() & 0xFFFF;
                    aggressor[at] = buffer.get();

                    // Checked HERE, where the file is still in hand. The header
                    // promises a byte that is an Aggressor and never zero, and
                    // nothing enforced it: a zero -- another version, a converter
                    // with a bug, a damaged sector -- became the index -1 and
                    // threw ArrayIndexOutOfBoundsException out of aggressorAt,
                    // which is a RuntimeException, so it walked straight through
                    // TickLibrary.queue (that catches IOException) and blew up
                    // later on the painting or replay thread, far from the file
                    // that caused it. Refusing the file on read, with its name
                    // in the message, is what MarketFile and TickFile do.
                    if (Aggressor.ofCode(aggressor[at]) == null) {
                        throw new IOException(file + ": trade " + at + " says aggressor "
                                + aggressor[at] + ", and no aggressor has that code");
                    }
                }
            }

            return new Session(LocalDate.ofEpochDay(epochDay), millis, price, quantity,
                    buyer, seller, aggressor, brokers(channel, file, brokersAt));
        }
    }

    private static Map<Integer, String> brokers(FileChannel channel, Path file, long at)
            throws IOException {
        Map<Integer, String> names = new LinkedHashMap<>();

        if (channel.size() == at) {
            // NOT "a session in which nobody traded", which is what this said
            // and what it was reached by only in a writer that no longer
            // exists: the count is written even when it is zero, so a
            // well-formed file always has four bytes here. Ending at the last
            // trade means the dictionary never got written -- a truncated file,
            // or one from a converter with this bug -- and reading on would
            // hand back a session whose thirty-one brokers are all null.
            throw new IOException(file + ": the trades end at " + at
                    + " and the file ends with them: no broker dictionary");
        }

        channel.position(at);

        ByteBuffer count = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);

        fill(channel, count, file);
        count.flip();

        int entries = count.getInt();

        if (entries < 0 || entries > LARGEST) {
            throw new IOException(file + ": " + entries + " is not a number of brokers");
        }

        for (int i = 0; i < entries; i++) {
            ByteBuffer pair = ByteBuffer.allocate(2 * Short.BYTES).order(ByteOrder.BIG_ENDIAN);

            fill(channel, pair, file);
            pair.flip();

            int code = pair.getShort() & 0xFFFF;
            int length = pair.getShort() & 0xFFFF;
            ByteBuffer name = ByteBuffer.allocate(length);

            fill(channel, name, file);

            names.put(code, new String(name.array(), StandardCharsets.UTF_8));
        }

        return names;
    }

    private static void fill(FileChannel channel, ByteBuffer buffer, Path file)
            throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException(file + ": ends in the middle of a record");
            }
        }
    }

    /**
     * Writes one session, trade by trade.
     *
     * <p>Streaming, like the tick writer, and for the same reason: the source
     * is half a gigabyte of text per session and the eventual purchase is years
     * of them.</p>
     */
    public static final class Writer implements AutoCloseable {

        private final Path file;

        /**
         * Where the bytes go until the session is whole.
         *
         * <p><b>The file on disk is not touched until there is a whole session to
         * put there.</b> This used to open the target itself with {@code
         * TRUNCATE_EXISTING} and write the header before the first line of the
         * export had been read -- so the session already on disk, complete and
         * checked and possibly the only copy, was gone the moment the converter
         * decided that day had started. If the next line of the export was the one
         * that made it refuse everything, the good day no longer existed.</p>
         *
         * <p>And what was left in its place LOOKED whole: close() rewrites the
         * header with the count of what it managed to write, so the file is
         * internally consistent -- the size matches the count, sessionOf answers
         * with the date, the library lists the day as exported. Nothing told the
         * program or the reader that the session ended where the error was.</p>
         *
         * <p>Same discipline the count already used -- do not commit until the
         * answer is known -- one level up.</p>
         */
        private final Path working;

        /** Whether the caller gave up on this session; see {@link #discard}. */
        private boolean abandoned;

        private final LocalDate date;

        private final FileChannel channel;

        private final ByteBuffer buffer =
                ByteBuffer.allocate(CHUNK * RECORD_BYTES).order(ByteOrder.BIG_ENDIAN);

        /** Insertion-ordered, so a file written twice comes out byte for byte the same. */
        private final Map<Integer, String> brokers = new LinkedHashMap<>();

        private long count;

        private int lastMillis = -1;

        public Writer(Path file, LocalDate date) throws IOException {
            this.file = file;
            this.date = date;
            this.working = file.resolveSibling(file.getFileName() + ".parcial");

            Files.createDirectories(file.toAbsolutePath().getParent());

            this.channel = FileChannel.open(working, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            writeHeader(0);
        }

        /**
         * @param millis since midnight; may repeat, and does — the export gives
         *               only the second, and up to 8.423 trades share one
         * @throws IllegalArgumentException if a field is out of range or the
         *                                  clock runs backwards
         */
        public void add(int millis, int price, int quantity,
                int buyer, int seller, Aggressor aggressor) throws IOException {
            if (millis < lastMillis) {
                // The export arrives newest first, so a converter that forgot
                // to reverse it would trip here rather than write a session
                // that plays backwards. Which is the point: within one second
                // the file's ORDER is the only record of what came first, so
                // getting it wrong is a loss nothing downstream could detect.
                throw new IllegalArgumentException(file + ": " + millis
                        + " comes before " + lastMillis + ", so the tape is out of order");
            }

            if (quantity < 1 || quantity > LARGEST) {
                throw new IllegalArgumentException(file + ": " + quantity
                        + " contracts does not fit the two bytes this writes");
            }

            check(buyer, "buyer");
            check(seller, "seller");

            if (aggressor == null) {
                throw new IllegalArgumentException(file + ": a trade with no aggressor");
            }

            lastMillis = millis;

            if (buffer.remaining() < RECORD_BYTES) {
                flush();
            }

            buffer.putInt(millis);
            buffer.putInt(price);
            buffer.putShort((short) quantity);
            buffer.putShort((short) buyer);
            buffer.putShort((short) seller);
            buffer.put((byte) aggressor.code());

            count++;
        }

        private void check(int code, String side) {
            if (code < 0 || code > LARGEST) {
                throw new IllegalArgumentException(file + ": " + code
                        + " is not a broker code this writes (" + side + ")");
            }
        }

        /** Names a broker, so the dictionary can be written on close. */
        public void broker(int code, String name) {
            check(code, "dictionary");

            if (name != null) {
                brokers.putIfAbsent(code, name);
            }
        }

        @Override
        public void close() throws IOException {
            if (abandoned || !channel.isOpen()) {
                return;
            }

            try {
                flush();
                writeBrokers();

                channel.position(0);

                writeHeader(count);
            } finally {
                channel.close();
            }

            commit(working, file);
        }

        /**
         * Throws the half-written session away, leaving what was on disk alone.
         *
         * <p>For the failure path, and the converters call it from the {@code
         * finally} that is only reached when something threw. Closing there
         * instead would commit whatever had been written so far, which is the
         * whole defect this pair exists to prevent.</p>
         */
        public void discard() throws IOException {
            abandoned = true;

            channel.close();

            Files.deleteIfExists(working);
        }

        /**
         * Puts the finished session where it belongs, in one step.
         *
         * <p>Atomic where the file system offers it, which is what makes "the old
         * session, or the new one, and never half of either" true even if the
         * machine goes down in the middle. Where it does not, a plain replace is
         * still far better than writing in place: the window in which neither file
         * is whole is a rename instead of a whole conversion.</p>
         */
        private static void commit(Path from, Path to) throws IOException {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }


        /**
         * Writes the dictionary, <b>even when it is empty</b>.
         *
         * <p>Returning early on an empty one made "no brokers were named" and
         * "the dictionary was never written" the same file. Four bytes of zero
         * cost nothing and tell the reader which of the two it is holding.</p>
         */
        private void writeBrokers() throws IOException {
            ByteBuffer out = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);

            out.putInt(brokers.size());
            out.flip();

            write(out);

            for (Map.Entry<Integer, String> each : brokers.entrySet()) {
                byte[] name = each.getValue().getBytes(StandardCharsets.UTF_8);
                ByteBuffer entry = ByteBuffer.allocate(2 * Short.BYTES + name.length)
                        .order(ByteOrder.BIG_ENDIAN);

                entry.putShort((short) (int) each.getKey());
                entry.putShort((short) name.length);
                entry.put(name);
                entry.flip();

                write(entry);
            }
        }

        private void flush() throws IOException {
            buffer.flip();
            write(buffer);
            buffer.clear();
        }

        private void write(ByteBuffer out) throws IOException {
            while (out.hasRemaining()) {
                channel.write(out);
            }
        }

        private void writeHeader(long trades) throws IOException {
            ByteBuffer head = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);

            head.put(MAGIC);
            head.putInt(VERSION);
            head.putInt((int) date.toEpochDay());
            head.putLong(trades);
            head.flip();

            write(head);
        }
    }

    /** What {@link #read} hands back. */
    private static final class Session implements TickSeries {

        private final LocalDate date;

        private final int[] millis;

        private final int[] price;

        private final int[] quantity;

        private final int[] buyer;

        private final int[] seller;

        private final byte[] aggressor;

        private final Map<Integer, String> brokers;

        private final long midnight;

        Session(LocalDate date, int[] millis, int[] price, int[] quantity,
                int[] buyer, int[] seller, byte[] aggressor, Map<Integer, String> brokers) {
            this.date = date;
            this.millis = millis;
            this.price = price;
            this.quantity = quantity;
            this.buyer = buyer;
            this.seller = seller;
            this.aggressor = aggressor;
            this.brokers = brokers;

            // The EXCHANGE's zone, and read now rather than at class load. The
            // file stores a day and milliseconds since its midnight, with no
            // zone in it; the machine's zone is not that midnight unless the
            // machine happens to be set to the market. See TickFile, which had
            // the same defect, and Timeframe.useZone, which is where the one
            // answer is set.
            this.midnight =
                    date.atStartOfDay(Timeframe.defaultZone()).toInstant().toEpochMilli();
        }

        @Override
        public int size() {
            return millis.length;
        }

        @Override
        public LocalDate date() {
            return date;
        }

        @Override
        public int millisAt(int index) {
            return millis[index];
        }

        @Override
        public long timeAt(int index) {
            return midnight + millis[index];
        }

        // The tape has no quotes. Not a gap in the file: the export does not
        // carry them, and saying "no" is the difference between a series that
        // cannot answer and one that answers zero.

        @Override
        public boolean hasBid(int index) {
            return false;
        }

        @Override
        public int bidAt(int index) {
            throw new IllegalStateException("the tape has no bid");
        }

        @Override
        public boolean hasAsk(int index) {
            return false;
        }

        @Override
        public int askAt(int index) {
            throw new IllegalStateException("the tape has no ask");
        }

        // Every row of the tape IS a trade, which is the one place it is
        // simpler than the tick export: there is no row to filter out, and no
        // way to mistake a quote for a print at price zero.

        @Override
        public boolean hasLast(int index) {
            return true;
        }

        @Override
        public int lastAt(int index) {
            return price[index];
        }

        @Override
        public boolean hasVolume(int index) {
            return true;
        }

        @Override
        public int volumeAt(int index) {
            return quantity[index];
        }

        @Override
        public int flagsAt(int index) {
            return 0;
        }

        @Override
        public boolean hasBuyer(int index) {
            return true;
        }

        @Override
        public int buyerAt(int index) {
            return buyer[index];
        }

        @Override
        public boolean hasSeller(int index) {
            return true;
        }

        @Override
        public int sellerAt(int index) {
            return seller[index];
        }

        @Override
        public boolean hasAggressor(int index) {
            return true;
        }

        /**
         * @return who crossed the spread on that trade
         *
         * <p>Through {@code Aggressor.ofCode} and not {@code Aggressor.values()},
         * which clones the array on every call: {@code TapeFileTest} alone walks
         * a whole session asking this, and a real session is five million
         * trades. The table it reads is built once, inside the enum, and never
         * handed out.</p>
         *
         * <p>Null is unreachable here because {@link TapeFile#read} refuses any
         * file whose aggressor byte names nothing -- see there.</p>
         */
        @Override
        public Aggressor aggressorAt(int index) {
            return Aggressor.ofCode(aggressor[index]);
        }

        @Override
        public String brokerName(int code) {
            return brokers.get(code);
        }
    }
}
