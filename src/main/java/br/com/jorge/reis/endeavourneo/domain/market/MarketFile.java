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
import java.util.Arrays;

/**
 * Reads the base as it is already on disk, written by the first Endeavour.
 *
 * <p>The format is not invented here and must not drift: the same files are read
 * by the program that produced every measurement this project has recorded. It
 * is a header and then fixed-size records, big-endian:</p>
 *
 * <pre>
 * header   ENDVCNDL   8 bytes, ASCII
 *          version    int, 1
 *          minutes    int, the scale each record covers
 *          count      long, how many records follow
 * record   time       long, the bar's OPENING instant, epoch milliseconds
 *          open       double
 *          high       double
 *          low        double
 *          close      double
 *          volume     double
 * </pre>
 *
 * <p><b>Read whole, into arrays.</b> The base is 693 thousand minutes and the
 * chart scrolls through it; reading a bar at a time from the disk would make
 * every repaint wait on the file system. Thirty-three megabytes of primitives is
 * the cheaper side of that trade by a wide margin.</p>
 *
 * <p><b>A wrong file is refused, not guessed at.</b> Without the check on the
 * eight magic bytes any file at all would be read as prices, and the chart would
 * draw whatever the bytes happened to spell. Silence there would be the worst
 * kind of defect: numbers on screen that no one can tell are wrong.</p>
 */
public final class MarketFile {

    /** What the first Endeavour stamps on the file. */
    private static final byte[] MAGIC = "ENDVCNDL".getBytes(StandardCharsets.US_ASCII);

    private static final int VERSION = 1;

    private static final int HEADER_BYTES = 8 + Integer.BYTES + Integer.BYTES + Long.BYTES;

    private static final int RECORD_BYTES = Long.BYTES + 5 * Double.BYTES;

    /** How many records to pull from the disk at a time. */
    private static final int CHUNK = 8_192;

    private MarketFile() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @return the scale, in minutes, the file says its records cover */
    public static int minutesOf(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return header(channel, file).minutes;
        }
    }

    /** @return how many bars the file holds, without reading any of them */
    public static int countIn(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return header(channel, file).count;
        }
    }

    /**
     * @param file a file written by the first Endeavour
     * @return every bar in it
     * @throws IOException if the file is missing, truncated or not one of ours
     */
    public static PriceSeries read(Path file) throws IOException {
        return read(file, 0, Integer.MAX_VALUE);
    }

    /**
     * @param file a file written by the first Endeavour
     * @param from the first bar wanted, counted from the start of the file
     * @param wantedCount how many to read; more than there are reads to the end
     * @return those bars
     * @throws IOException if the file is missing, truncated or not one of ours
     *
     * <p><b>A slice, because the records are fixed.</b> Each is a long and five
     * doubles, so bar {@code i} begins at {@code HEADER_BYTES + i * RECORD_BYTES}
     * and there is nothing to search for. Six years of one-minute bars is 39 MB
     * and the reader is almost always looking at a month of it; loading the file
     * to draw a screen is the sort of cost that never shows up as a defect and
     * is paid on every open.</p>
     */
    public static PriceSeries read(Path file, int from, int wantedCount) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            Header head = header(channel, file);

            long expected = (long) HEADER_BYTES + (long) head.count * RECORD_BYTES;

            if (channel.size() != expected) {
                // Said before reading rather than discovered halfway through, so
                // the message can name both numbers. A file cut short by a
                // failed copy is the likely cause, and it is worth saying so
                // instead of drawing the part that survived.
                throw new IOException(file + ": the header promises " + head.count
                        + " bars, which is " + expected + " bytes, and the file has "
                        + channel.size());
            }

            int first = Math.max(0, Math.min(from, head.count));
            int size = (int) Math.max(0, Math.min((long) wantedCount, head.count - first));

            channel.position(HEADER_BYTES + (long) first * RECORD_BYTES);

            long[] times = new long[size];
            double[] opens = new double[size];
            double[] highs = new double[size];
            double[] lows = new double[size];
            double[] closes = new double[size];
            double[] volumes = new double[size];

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
                    times[at] = buffer.getLong();
                    opens[at] = buffer.getDouble();
                    highs[at] = buffer.getDouble();
                    lows[at] = buffer.getDouble();
                    closes[at] = buffer.getDouble();
                    volumes[at] = buffer.getDouble();
                }
            }

            return new ArraySeries(times, opens, highs, lows, closes, volumes);
        }
    }

    /**
     * Writes a series in the format the first Endeavour reads.
     *
     * @param minutes the scale each bar covers, as the header declares it
     *
     * <p>Here so a base built from two others can be saved and read back by
     * both programs. Nothing else in this application writes a base: the raw
     * exports are produced elsewhere and this only ever reads them.</p>
     */
    public static void write(Path file, PriceSeries series, int minutes) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer head = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);

            head.put(MAGIC);
            head.putInt(VERSION);
            head.putInt(minutes);
            head.putLong(series.size());
            head.flip();

            drain(channel, head);

            ByteBuffer buffer = ByteBuffer.allocate(CHUNK * RECORD_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);

            for (int i = 0; i < series.size(); i++) {
                if (buffer.remaining() < RECORD_BYTES) {
                    buffer.flip();
                    drain(channel, buffer);
                    buffer.clear();
                }

                buffer.putLong(series.timeAt(i));
                buffer.putDouble(series.openAt(i));
                buffer.putDouble(series.highAt(i));
                buffer.putDouble(series.lowAt(i));
                buffer.putDouble(series.closeAt(i));
                buffer.putDouble(series.volumeAt(i));
            }

            buffer.flip();
            drain(channel, buffer);
        }
    }

    private static void drain(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /** @return whether the file is one of ours, without reading the bars */
    public static boolean isSeries(Path file) {
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

    private record Header(int minutes, int count) { }

    private static Header header(FileChannel channel, Path file) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);

        fill(channel, head, file);
        head.flip();

        byte[] magic = new byte[MAGIC.length];

        head.get(magic);

        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException(file + ": not an Endeavour series file");
        }

        int version = head.getInt();

        if (version != VERSION) {
            throw new IOException(file + ": version " + version + " is not one this reads");
        }

        int minutes = head.getInt();
        long count = head.getLong();

        if (count < 0 || count > Integer.MAX_VALUE) {
            throw new IOException(file + ": " + count + " is not a number of bars");
        }

        return new Header(minutes, (int) count);
    }

    private static void fill(FileChannel channel, ByteBuffer buffer, Path file)
            throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new EOFException(file + ": the file ends in the middle of a bar");
            }
        }
    }
}
