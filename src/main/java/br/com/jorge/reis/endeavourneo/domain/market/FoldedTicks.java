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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * An export of ticks, read as bars.
 *
 * <h2>Why a tick export is a series like any other</h2>
 *
 * <p>It holds every print of every session it covers, which is <b>more</b> than
 * the candle file holds and not less. Treating it as something only a replay
 * could open made the most complete data in the program the only data that
 * could not be looked at.</p>
 *
 * <h2>Minute candles, and why that is not a loss</h2>
 *
 * <p>At one minute there is nothing to argue about: a minute folded from the
 * trades <b>is</b> that minute. The 8%-to-27% disagreement that makes candles
 * and ticks incomparable is a renko effect and comes from reading a bar as "the
 * high, then the low" — a bar is not read that way here. What a chart of the
 * ticks gains over a chart of the candle file is that every bar came from a
 * print of this instrument, with no stitching between exports.</p>
 *
 * <p>Handing the trades over unfolded is not the alternative: five million bars
 * where five hundred belong is not a scale a chart can draw, and {@link
 * Timeframe#apply} takes a shortcut at one minute and would hand them straight
 * back — thirteen thousand trades drawn inside the first minute of screen, and
 * the reader seeing a flat line. That happened, and it is what folding fixed.</p>
 *
 * <h2>What it costs, measured</h2>
 *
 * <pre>
 *                                                       first    again
 * MetaTrader   20 sessions   1.838 MB read   10.766 bars   16,6 s   0,09 s
 * Profit        9 sessions     691 MB read    5.074 bars    8,8 s   0,03 s
 * </pre>
 *
 * <p>Measured on the exports in the data folder, September 2026. The first
 * column is the fold; the second is the same call once the sessions are
 * kept -- see below. An earlier measurement of the same files, in the audit
 * report, read 8,1 s and 4,4 s; the ratio is what the decision rests on, and
 * a repeat of the cold run gave 16,9 s, so it is not the filesystem cache
 * warming up.</p>
 *
 * <p>So it is <b>seconds, and it must not be on the interface thread</b>. The
 * result is tiny — ten thousand bars is half a megabyte — and the ticks that
 * produced it are garbage before the next session is read: one session is held
 * at a time, never the whole export.</p>
 *
 * <p>Read straight from the files and NOT through {@link TickLibrary}, for that
 * same reason. The library caches, and its cache is the one thing that would
 * keep alive exactly what this exists to let go.</p>
 *
 * <h2>And kept, so the second opening is free</h2>
 *
 * <p>The seconds above are paid to produce half a megabyte. Each session is
 * folded once and the RESULT is written beside the ticks, as
 * {@code winfut-2021-01-04.1m.folded} -- an ordinary {@link MarketFile}, which
 * is the format the rest of the program already reads.</p>
 *
 * <p><b>Per session, and that is what makes the invalidation easy.</b> One file
 * in, one file out: a session that was never folded has no cache, and a session
 * that was imported again has a different one. There is no set to keep in
 * agreement and nothing to invalidate when a new day arrives -- the new day is
 * simply the one that is not cached. Reading a week that already has six of its
 * days folded costs the seventh.</p>
 *
 * <p><b>How it knows the cache is current: the two stamps are EQUAL.</b> After
 * writing, the cache is given the session's own modification time, so the
 * question is not "is the cache newer" -- which a file restored from a backup
 * with its times preserved would answer wrongly -- but "was it made from THIS
 * version of this file". A re-import changes the session's stamp and the cache
 * stops matching on the same instant.</p>
 *
 * <p>It is a derived artefact and it says so by being derived: deleting the
 * {@code .folded} files costs the seconds again and nothing else. Nothing in
 * the program lists them -- {@link TickLibrary#exported} filters by the
 * source's own extension, so a cache is never mistaken for a session.</p>
 */
public final class FoldedTicks {

    private FoldedTicks() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param folder the instrument's {@code ticks} directory
     * @param instrument the market the files are named after
     * @param source which export to read
     * @param day the session
     * @param zone the calendar that decides where a minute begins
     * @return that session as one-minute candles, or empty when it was not
     *         exported
     *
     * <p>Empty rather than an exception, and empty rather than bars from
     * somewhere else: a chart of the ticks shows ticks, and where there are none
     * it shows nothing.</p>
     *
     * <p><b>A file that IS there and will not read is a different answer.</b> It
     * comes back empty too -- the caller has one series to draw and no way to
     * show a hole -- but it says so, because a corrupt session vanishing from
     * the middle of a chart with nothing said is the failure this format spends
     * its refusals to avoid.</p>
     */
    public static PriceSeries day(Path folder, String instrument, TickSource source,
            LocalDate day, ZoneId zone) {
        Path file = source.fileFor(folder, instrument, day);

        if (!day.equals(source.sessionIn(file))) {
            return PriceSeries.empty();
        }

        Path cache = cacheFor(file);
        PriceSeries kept = cached(cache, file);

        if (kept != null) {
            return kept;
        }

        try {
            PriceSeries folded = Timeframe.ONE_MINUTE.fold(TickBars.of(source.read(file)), zone);

            keep(cache, folded, file);

            return folded;
        } catch (IOException e) {
            // NOT THE SAME as the day above. That one was never exported and an
            // empty series is the truth; this one WAS exported and will not
            // read, and answering with the same silence takes a corrupt session
            // out of the middle of a chart with no gap and no word -- and
            // swallows exactly the refusals TapeFile and TickFile were built to
            // produce, each of which names the file and what is wrong with it.
            System.err.println(file + ": exported and unreadable, so it is missing"
                    + " from the chart (" + e + ")");

            return PriceSeries.empty();
        }
    }

    /**
     * @param session a tick session's own file
     * @return where its folded form is kept
     *
     * <p>Beside it, and not under a folder of its own: the cache belongs to that
     * one file, and a reader clearing out a month should take the folded form
     * with it without having to know there is one.</p>
     *
     * <p>The extension is neither source's, on purpose. {@code sessionIn} looks
     * at the suffix before it opens anything, so a cache is skipped by the
     * listing without costing a read.</p>
     */
    static Path cacheFor(Path session) {
        return session.resolveSibling(session.getFileName() + ".1m.folded");
    }

    /**
     * @param cache where the folded session would be
     * @param session the ticks it would have come from
     * @return those bars, or null when there is nothing to trust
     *
     * <p>Equal stamps, not a newer cache. See the class comment: "newer" is
     * answered wrongly by a session restored from a backup with its times
     * preserved, and equality is answered wrongly by nothing short of somebody
     * forging the stamp.</p>
     */
    private static PriceSeries cached(Path cache, Path session) {
        try {
            if (!Files.isRegularFile(cache)
                    || !Files.getLastModifiedTime(cache)
                            .equals(Files.getLastModifiedTime(session))) {

                return null;
            }

            return MarketFile.read(cache);
        } catch (IOException e) {
            // A cache that will not read is a cache that is not there. It is
            // written again below, and saying anything here would be a message
            // about a file the reader never asked for.
            return null;
        }
    }

    /**
     * Writes the folded session beside the ticks, and stamps it with theirs.
     *
     * <p>A failure here is not a failure of the opening: the bars are in hand
     * and the chart is drawn either way. It is said out loud all the same,
     * because a cache that silently never writes is seconds paid again on every
     * open with nothing to show why.</p>
     *
     * <p>Two threads folding the same session at once write the same bytes --
     * the fold is deterministic -- so the worst an overlap can produce is a file
     * of the wrong length, which {@code MarketFile.read} refuses and the next
     * open replaces.</p>
     */
    private static void keep(Path cache, PriceSeries bars, Path session) {
        try {
            MarketFile.write(cache, bars, 1);
            Files.setLastModifiedTime(cache, Files.getLastModifiedTime(session));
        } catch (IOException e) {
            System.err.println(cache + ": the folded session could not be kept, so it will"
                    + " be folded again next time (" + e + ")");
        }
    }

    /**
     * @param days the sessions to read, in order
     * @return them as one series of one-minute candles
     *
     * <p><b>Seconds.</b> See the class comment for the measurement; the caller
     * is responsible for not doing this on the interface thread.</p>
     */
    public static PriceSeries over(Path folder, String instrument, TickSource source,
            List<LocalDate> days, ZoneId zone) {
        List<PriceSeries> parts = new ArrayList<>();

        for (LocalDate each : days) {
            PriceSeries session = day(folder, instrument, source, each, zone);

            if (session.size() > 0) {
                parts.add(session);
            }
        }

        return parts.isEmpty() ? PriceSeries.empty() : ConcatSeries.of(parts);
    }

    /**
     * @return everything that export holds, session by session
     *
     * <p>The listing is a directory read and costs nothing; the sessions are
     * what cost.</p>
     */
    public static PriceSeries all(Path folder, String instrument, TickSource source,
            ZoneId zone) {
        List<LocalDate> days;

        try (TickLibrary library = new TickLibrary(folder, instrument, source)) {
            days = library.exported();
        }

        return over(folder, instrument, source, days, zone);
    }
}
