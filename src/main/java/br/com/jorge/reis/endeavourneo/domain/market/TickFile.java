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

import java.io.EOFException;
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

/**
 * One session of ticks on disk, losslessly.
 *
 * <p><b>Nothing the exchange said is dropped.</b> Every column of the
 * MetaTrader export is kept, including the rows that carry only a quote and no
 * trade, so the book can be rebuilt from this file alone. The test that proves
 * it writes a file, reads it back, prints it as the original text and compares
 * — anything discarded shows up as a difference.</p>
 *
 * <pre>
 * header   ENDVTICK   8 bytes, ASCII
 *          version    int, 1
 *          epochDay   int, which session this is
 *          count      long, how many ticks follow
 * record   millis     int, since midnight
 *          bid        int
 *          ask        int
 *          last       int
 *          volume     int
 *          flags      byte, the exchange's own
 *          present    byte, which of the four numbers were actually sent
 * </pre>
 *
 * <p><b>Why a byte of presence rather than a sentinel.</b> Zero is a real value
 * here: the first row of a session states a bid of zero and an ask of zero.
 * Using zero, or -1, to mean "absent" would erase the difference between what
 * the exchange said and what it did not say — and a book rebuilt from that
 * would show the bid collapsing millions of times a day.</p>
 *
 * <p><b>Why fixed-size records.</b> The rows that only quote are 3,4% of the
 * file, so a variable encoding would save about a third. It would also cost
 * random access, and this file is read whole, once, by a replay that then wants
 * to jump about inside it. Half the CSV's size at 22 bytes a row is enough.</p>
 *
 * <p><b>Why one file per session.</b> Measured on January 2021: 4,4 million
 * ticks a day, 96 MB in memory. A month in one file would be 2 GB, and the
 * replay never needs more than the days it is playing.</p>
 */
public final class TickFile {

    /**
     * The eight bytes at the head of a MetaTrader session, and the version it
     * is written with.
     *
     * <p><b>Package-visible because {@link TickSource} names them, and used to
     * repeat them.</b> The enum carried its own {@code "ENDVTICK"} and its own
     * {@code 1}, and the comment in {@code header} explains what the second
     * copy costs: the scanner passes the enum's version to the reader, so
     * bumping the one here and not the one there makes every valid session of
     * this source answer "not one of ours" -- and it vanishes from the list
     * with no error anywhere. That comment describes the defect being possible;
     * it went on being possible one level up.</p>
     *
     * <p>The format owns them, not the enum: the enum is a list of sources and
     * this class is what a session of this source IS.</p>
     */
    static final String TAG = "ENDVTICK";

    static final int VERSION = 1;

    private static final byte[] MAGIC = TAG.getBytes(StandardCharsets.US_ASCII);

    private static final int HEADER_BYTES = 8 + Integer.BYTES + Integer.BYTES + Long.BYTES;

    static final int RECORD_BYTES = 5 * Integer.BYTES + 2;

    private static final int CHUNK = 16_384;

    /** Bits of the presence byte. */
    private static final int HAS_BID = 1;

    private static final int HAS_ASK = 2;

    private static final int HAS_LAST = 4;

    private static final int HAS_VOLUME = 8;

    private TickFile() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @return whether the file is one of ours, without reading the ticks */
    public static boolean isTicks(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            header(channel, file);

            return true;
        } catch (NotOurs e) {
            // Somebody else's file. The ordinary answer, and worth no words.
            return false;
        } catch (IOException e) {
            // A file of OURS that would not read. It answers false like the
            // line above and says so: see NotOurs.
            System.err.println(file + ": could not be read, so it is not being offered ("
                    + e + ")");

            return false;
        }
    }

    /** @return the session's date, without reading the ticks */
    public static LocalDate dateOf(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return LocalDate.ofEpochDay(header(channel, file).epochDay);
        }
    }

    /**
     * @param file any file
     * @param tag the eight ASCII bytes a source stamps its sessions with
     * @param version the version THAT source writes, which is not this one's
     * @return the session's date if the file is one of that source's, else null
     *
     * <p>Here rather than in each source because the first twenty-four bytes
     * are the SAME in every kind of session file this program writes -- tag,
     * version, day, count -- and only the tag differs. A source that invented
     * its own header would be a source whose files could not be listed by the
     * one piece of code that lists sessions.</p>
     */
    public static LocalDate sessionOf(Path file, String tag, int version) {
        if (!Files.isRegularFile(file)) {
            return null;
        }

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            byte[] wanted = tag.getBytes(StandardCharsets.US_ASCII);

            return LocalDate.ofEpochDay(header(channel, file, wanted, version).epochDay);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * @param file a file written by {@link Writer}
     * @return every tick in it
     * @throws IOException if the file is missing, truncated or not one of ours
     */
    public static TickSeries read(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            Header head = header(channel, file);

            long expected = (long) HEADER_BYTES + (long) head.count * RECORD_BYTES;

            if (channel.size() != expected) {
                throw new IOException(file + ": the header promises " + head.count
                        + " ticks, which is " + expected + " bytes, and the file has "
                        + channel.size());
            }

            int size = head.count;
            int[] millis = new int[size];
            int[] bid = new int[size];
            int[] ask = new int[size];
            int[] last = new int[size];
            int[] volume = new int[size];
            byte[] flags = new byte[size];
            byte[] present = new byte[size];

            ByteBuffer buffer = ByteBuffer.allocate(CHUNK * RECORD_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);

            int at = 0;

            while (at < size) {
                int wanted = Math.min(CHUNK, size - at);

                buffer.clear();
                buffer.limit(wanted * RECORD_BYTES);
                fill(channel, buffer, file);
                buffer.flip();

                for (int i = 0; i < wanted; i++, at++) {
                    millis[at] = buffer.getInt();
                    bid[at] = buffer.getInt();
                    ask[at] = buffer.getInt();
                    last[at] = buffer.getInt();
                    volume[at] = buffer.getInt();
                    flags[at] = buffer.get();
                    present[at] = buffer.get();
                }
            }

            return new Session(LocalDate.ofEpochDay(head.epochDay),
                    millis, bid, ask, last, volume, flags, present);
        }
    }

    /**
     * Writes one session, tick by tick.
     *
     * <p>Streaming rather than "hand me the whole session": the source is a
     * four gigabyte text file, and holding a month of it in memory to write it
     * out again would need more heap than the machine has to spare.</p>
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

        private long count;

        private int lastMillis = -1;

        public Writer(Path file, LocalDate date) throws IOException {
            this.file = file;
            this.date = date;
            this.working = file.resolveSibling(file.getFileName() + ".parcial");

            Files.createDirectories(file.toAbsolutePath().getParent());

            this.channel = FileChannel.open(working, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            // A header with a count of zero, rewritten on close once the real
            // count is known. Writing the count first would mean counting the
            // ticks in a separate pass over four gigabytes.
            writeHeader(0);
        }

        /**
         * @param has a presence mask; use {@link #mask}
         * @throws IllegalArgumentException if the tick goes backwards in time
         */
        public void add(int millis, int bid, int ask, int last, int volume, int flags, int has)
                throws IOException {
            if (millis < lastMillis) {
                // Measured over January 2021: this never happens, not once in
                // 87 million rows. If it ever does, the file is not what this
                // program thinks it is, and a replay that plays it would jump
                // backwards without saying so.
                throw new IllegalArgumentException(file + ": tick at " + millis
                        + " ms comes after one at " + lastMillis + " ms");
            }

            lastMillis = millis;

            if (buffer.remaining() < RECORD_BYTES) {
                flush();
            }

            buffer.putInt(millis);
            buffer.putInt(bid);
            buffer.putInt(ask);
            buffer.putInt(last);
            buffer.putInt(volume);
            buffer.put((byte) flags);
            buffer.put((byte) has);

            count++;
        }

        /** @return the presence mask for the four numbers */
        public static int mask(boolean bid, boolean ask, boolean last, boolean volume) {
            return (bid ? HAS_BID : 0) | (ask ? HAS_ASK : 0)
                    | (last ? HAS_LAST : 0) | (volume ? HAS_VOLUME : 0);
        }

        public long count() {
            return count;
        }

        @Override
        public void close() throws IOException {
            if (abandoned || !channel.isOpen()) {
                return;
            }

            try {
                flush();

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


        private void flush() throws IOException {
            buffer.flip();

            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }

            buffer.clear();
        }

        private void writeHeader(long ticks) throws IOException {
            ByteBuffer head = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);

            head.put(MAGIC);
            head.putInt(VERSION);
            head.putInt((int) date.toEpochDay());
            head.putLong(ticks);
            head.flip();

            while (head.hasRemaining()) {
                channel.write(head);
            }
        }
    }

    private record Header(int epochDay, int count) { }

    private static Header header(FileChannel channel, Path file) throws IOException {
        return header(channel, file, MAGIC, VERSION);
    }

    private static Header header(FileChannel channel, Path file, byte[] tag,
            int wantedVersion) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);

        fill(channel, head, file);
        head.flip();

        // THE TAG'S length, not this file's. The two are eight bytes today, so
        // it worked; a third source with a mark of another size would read the
        // wrong number of bytes and compare them against the right ones.
        byte[] magic = new byte[tag.length];

        head.get(magic);

        if (!Arrays.equals(magic, tag)) {
            throw new NotOurs(file + ": not an Endeavour "
                    + new String(tag, StandardCharsets.US_ASCII) + " file");
        }

        int version = head.getInt();

        // The version the CALLER writes, which used to be this file's own
        // constant. The two happen to be 1 today, so it worked by coincidence:
        // the day the tape goes to version 2, isTape would answer false for
        // every valid tape and the Profit source would vanish from the list
        // with no error anywhere. The javadoc of sessionOf justifies the shared
        // header by "the first twenty-four bytes are the same" -- which is true
        // of the LAYOUT, and says nothing about the value of the version.
        if (version != wantedVersion) {
            throw new NotOurs(file + ": version " + version + " is not one this reads");
        }

        int epochDay = head.getInt();
        long count = head.getLong();

        if (count < 0 || count > Integer.MAX_VALUE) {
            throw new NotOurs(file + ": " + count + " is not a number of ticks");
        }

        return new Header(epochDay, (int) count);
    }

    private static void fill(FileChannel channel, ByteBuffer buffer, Path file)
            throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new EOFException(file + ": the file ends in the middle of a tick");
            }
        }
    }

    /** The session in memory: one array per column, which is 22 bytes a tick. */
    private static final class Session implements TickSeries {

        private final LocalDate date;

        private final int[] millis;

        private final int[] bid;

        private final int[] ask;

        private final int[] last;

        private final int[] volume;

        private final byte[] flags;

        private final byte[] present;

        private final long midnight;

        Session(LocalDate date, int[] millis, int[] bid, int[] ask,
                int[] last, int[] volume, byte[] flags, byte[] present) {
            this.date = date;
            this.millis = millis;
            this.bid = bid;
            this.ask = ask;
            this.last = last;
            this.volume = volume;
            this.flags = flags;
            this.present = present;

            // Worked out once. Doing it per tick would call the calendar 4,4
            // million times to produce the same number.
            //
            // THE EXCHANGE'S ZONE, not the machine's. The file stores a day and
            // a count of milliseconds since ITS midnight -- local wall time with
            // no zone in it -- so whoever reads it decides which midnight that
            // was. Reading it in the machine's zone put every tick of a session
            // hours away from the bars of the same session on any machine not
            // set to the market, and nothing said so: RecordedTicks simply found
            // no ticks in the bar's window and drew the synthetic walk.
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

        @Override
        public boolean hasBid(int index) {
            return (present[index] & HAS_BID) != 0;
        }

        @Override
        public int bidAt(int index) {
            return bid[index];
        }

        @Override
        public boolean hasAsk(int index) {
            return (present[index] & HAS_ASK) != 0;
        }

        @Override
        public int askAt(int index) {
            return ask[index];
        }

        @Override
        public boolean hasLast(int index) {
            return (present[index] & HAS_LAST) != 0;
        }

        @Override
        public int lastAt(int index) {
            return last[index];
        }

        @Override
        public boolean hasVolume(int index) {
            return (present[index] & HAS_VOLUME) != 0;
        }

        @Override
        public int volumeAt(int index) {
            return volume[index];
        }

        @Override
        public int flagsAt(int index) {
            return flags[index] & 0xFF;
        }
    }
}
