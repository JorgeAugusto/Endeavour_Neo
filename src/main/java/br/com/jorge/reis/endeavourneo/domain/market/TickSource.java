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

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Where a session of ticks came from, and what that means it holds.
 *
 * <h2>Two sources, two formats, on purpose</h2>
 *
 * <p>These are not one measurement sampled twice. One is a stream of QUOTES —
 * the top of the book, moving whether or not anybody trades. The other is the
 * TAPE — every trade that printed, with who was on each side and who crossed
 * the spread. Each holds what the other does not:</p>
 *
 * <table>
 *   <caption>What each source knows</caption>
 *   <tr><th></th><th>MetaTrader</th><th>Profit</th></tr>
 *   <tr><td>bid and ask</td><td>yes</td><td><b>no</b></td></tr>
 *   <tr><td>rows with a quote and no trade</td><td>yes</td><td><b>no</b></td></tr>
 *   <tr><td>who bought, who sold</td><td><b>no</b></td><td>yes</td></tr>
 *   <tr><td>who was the aggressor</td><td><b>no</b></td><td>yes</td></tr>
 *   <tr><td>resolution</td><td>millisecond</td><td>second</td></tr>
 * </table>
 *
 * <p><b>So neither converts into the other without losing something</b>, which
 * is the whole reason there are two. Folding them into one record would mean
 * every trade on the tape carrying eight dead bytes of bid and ask — forty-six
 * megabytes of zeroes in a single session — and, worse, a reader that could no
 * longer tell what it had in its hands.</p>
 *
 * <p>What they share is time and a traded price, and that is exactly what
 * {@link TickSeries} asks for. A renko, a bar, a replay never asks which source
 * it is reading; anything that needs the difference asks the series whether it
 * {@code has} the field.</p>
 *
 * <h2>The header is the same in both</h2>
 *
 * <p>Tag, version, day, count — twenty-four bytes, and only the tag differs.
 * That is what lets one piece of code list the sessions of either.</p>
 */
public enum TickSource {

    /** MetaTrader's tick export: bid, ask, last, volume, to the millisecond. */
    METATRADER("ENDVTICK", "bin", 1),

    /**
     * Profit's Times &amp; Trades: every print, with both brokers and the
     * aggressor, to the second.
     *
     * <p>The format is decided and not yet written. Until a session of it
     * exists on disk this source simply lists nothing, which is the truth: no
     * tape has been exported.</p>
     */
    PROFIT("ENDVTAPE", "tape", 1);

    private final String tag;

    private final String suffix;

    /**
     * The version THIS source writes into its header.
     *
     * <p>Carried per source, and it used not to be: the shared header reader
     * compared against the version of the MetaTrader file, and the two happen
     * to be 1 today. The day the tape goes to 2, every valid tape would be read
     * as "not one of ours" and the Profit source would vanish from the list
     * with no error anywhere. The header LAYOUT is shared; the version in it is
     * each source's own.</p>
     */
    private final int version;

    TickSource(String tag, String suffix, int version) {
        this.tag = tag;
        this.suffix = suffix;
        this.version = version;
    }

    /** @return the version this source stamps its sessions with */
    public int version() {
        return version;
    }

    /** @return the eight ASCII bytes at the head of this source's sessions */
    public String tag() {
        return tag;
    }

    /** @return the file extension, without the dot */
    public String suffix() {
        return suffix;
    }

    /** @return the key under which the interface names this source */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Where one session of this source lives.
     *
     * <p>{@code <win>/ticks/metatrader/2021/01/win-2021-01-04.bin}, and
     * {@code <win>/ticks/profit/2026/09/win-2026-09-01.tape} -- a folder per
     * source and a different extension — so both sources can hold the same session of
     * the same instrument without one standing on the other. They do not
     * overlap today, and the day somebody exports 2021 from Profit they would;
     * a path that collided would silently keep whichever was written last.</p>
     *
     * <p><b>Computed, never searched</b>, and this is the only place that turns
     * a date into a path. A file that is not where its date says is a file this
     * program does not have.</p>
     */
    public Path fileFor(Path folder, String instrument, LocalDate date) {
        return folder
                .resolve(key())
                .resolve(String.format("%04d", date.getYear()))
                .resolve(String.format("%02d", date.getMonthValue()))
                .resolve(instrument + "-" + date + "." + suffix);
    }

    /**
     * @return every trade or tick in that session
     * @throws java.io.IOException if the file is missing, truncated or not ours
     *
     * <p>A switch and not an {@code if}, so a third source cannot be added
     * without saying how it is read: the compiler refuses an incomplete one.
     * The two formats share nothing below this line and everything above
     * it.</p>
     */
    public TickSeries read(Path file) throws java.io.IOException {
        return switch (this) {
            case METATRADER -> TickFile.read(file);
            case PROFIT -> TapeFile.read(file);
        };
    }

    /**
     * @return the session that file holds, or null if it is not one of this
     *         source's
     */
    public LocalDate sessionIn(Path file) {
        return file.getFileName().toString().endsWith("." + suffix)
                ? TickFile.sessionOf(file, tag, version) : null;
    }
}
