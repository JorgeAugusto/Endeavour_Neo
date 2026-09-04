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
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
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

    private static final byte[] MAGIC = "ENDVTICK".getBytes(StandardCharsets.US_ASCII);

    private static final int VERSION = 1;

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
        } catch (IOException e) {
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
     * @return the session's date if the file is one of that source's, else null
     *
     * <p>Here rather than in each source because the first twenty-four bytes
     * are the SAME in every kind of session file this program writes -- tag,
     * version, day, count -- and only the tag differs. A source that invented
     * its own header would be a source whose files could not be listed by the
     * one piece of code that lists sessions.</p>
     */
    public static LocalDate sessionOf(Path file, String tag) {
        if (!Files.isRegularFile(file)) {
            return null;
        }

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            byte[] wanted = tag.getBytes(StandardCharsets.US_ASCII);

            return LocalDate.ofEpochDay(header(channel, file, wanted).epochDay);
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

        private final LocalDate date;

        private final FileChannel channel;

        private final ByteBuffer buffer =
                ByteBuffer.allocate(CHUNK * RECORD_BYTES).order(ByteOrder.BIG_ENDIAN);

        private long count;

        private int lastMillis = -1;

        public Writer(Path file, LocalDate date) throws IOException {
            this.file = file;
            this.date = date;

            Files.createDirectories(file.toAbsolutePath().getParent());

            this.channel = FileChannel.open(file, StandardOpenOption.CREATE,
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
            try {
                flush();

                channel.position(0);
                writeHeader(count);
            } finally {
                channel.close();
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
        return header(channel, file, MAGIC);
    }

    private static Header header(FileChannel channel, Path file, byte[] tag) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);

        fill(channel, head, file);
        head.flip();

        byte[] magic = new byte[MAGIC.length];

        head.get(magic);

        if (!Arrays.equals(magic, tag)) {
            throw new IOException(file + ": not an Endeavour "
                    + new String(tag, StandardCharsets.US_ASCII) + " file");
        }

        int version = head.getInt();

        if (version != VERSION) {
            throw new IOException(file + ": version " + version + " is not one this reads");
        }

        int epochDay = head.getInt();
        long count = head.getLong();

        if (count < 0 || count > Integer.MAX_VALUE) {
            throw new IOException(file + ": " + count + " is not a number of ticks");
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
            this.midnight = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
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
