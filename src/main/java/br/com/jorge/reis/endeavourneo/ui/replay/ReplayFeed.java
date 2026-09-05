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
package br.com.jorge.reis.endeavourneo.ui.replay;

import br.com.jorge.reis.endeavourneo.domain.market.TickLibrary;
import br.com.jorge.reis.endeavourneo.domain.market.TickSource;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

import java.time.LocalDate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One thing the replay can play, named the way the tree names it.
 *
 * <h2>Why this replaced two combo boxes</h2>
 *
 * <p>The transport used to ask twice: which series, and which tick source. The
 * pair left the only question that matters unanswered — WHAT IS PLAYING. Picking
 * {@code winfull-1m} and {@code Profit} meant "minute bars out of the candle
 * file, animated inside by the tape where it exists, and by an invented walk
 * where it does not", and nothing on screen said any of that. The reader could
 * watch a whole session without knowing whether the movement inside each bar had
 * ever happened.</p>
 *
 * <p>One list, and the choice is the answer. Pick the minutes and minutes are
 * what plays. Pick a tape and the tape is what plays — every bar on screen
 * folded from the trades that printed.</p>
 *
 * <h2>Ticks are never mixed with candles</h2>
 *
 * <p>A tick feed builds the days BEFORE the session from its ticks too, not from
 * the candle file. That is the same rule the renko already follows, and for the
 * same measured reason: bricks laid from candles and bricks laid from ticks
 * differ by 5% to 23%, so a chart holding both changes density halfway across
 * and looks like the market did it.</p>
 *
 * <p>It costs something, and the cost is honest: a tick feed can only reach back
 * as far as the sessions that were exported.</p>
 *
 * @param instrument the market, as {@link SeriesCatalog#groupOf} gives it
 * @param series the bar series to play, or null for a tick feed
 * @param source the export to play, or null for a bar feed
 */
public record ReplayFeed(String instrument, String series, TickSource source) {

    public ReplayFeed {
        if ((series == null) == (source == null)) {
            throw new IllegalArgumentException(
                    "a feed is either a bar series or a tick source, never both and never neither");
        }
    }

    /** @return a feed that plays that bar series */
    public static ReplayFeed of(String series) {
        return new ReplayFeed(SeriesCatalog.groupOf(series), series, null);
    }

    /** @return a feed that plays that market's ticks, from that export */
    public static ReplayFeed of(String instrument, TickSource source) {
        return new ReplayFeed(instrument, null, source);
    }

    public boolean isTicks() {
        return source != null;
    }

    /**
     * @return everything on disk that can be replayed, bars first
     *
     * <p>Bars first because they are what a session is usually watched at, and
     * because the tick feeds are the short ones: a handful of exported days
     * against years of minutes.</p>
     */
    public static List<ReplayFeed> available() {
        List<ReplayFeed> feeds = new ArrayList<>();
        List<String> markets = new ArrayList<>();

        for (String each : SeriesCatalog.names()) {
            feeds.add(of(each));

            String market = SeriesCatalog.groupOf(each);

            if (!markets.contains(market)) {
                markets.add(market);
            }
        }

        for (String market : markets) {
            for (TickSource source : TickSource.values()) {
                TickLibrary library =
                        new TickLibrary(SeriesCatalog.ticksOf(market), market, source);

                try {
                    // Offered only where something was exported. An entry that
                    // could never play is not a choice, it is a dead end with a
                    // name.
                    if (!library.exported().isEmpty()) {
                        feeds.add(of(market, source));
                    }
                } finally {
                    library.close();
                }
            }
        }

        return feeds;
    }

    /**
     * Which days each feed can play, worked out once.
     *
     * <p>Measured: walking the six-year source for its 1.494 sessions costs
     * 39-102 ms, and listing an export's sessions costs about 50. Once, that is
     * nothing; on every repaint of a calendar it is a stutter. So it is
     * remembered, and {@link #warm} fills it at startup off the interface
     * thread so the first calendar opens instantly.</p>
     */
    private static final Map<String, NavigableSet<LocalDate>> KNOWN = new ConcurrentHashMap<>();

    /**
     * @return the days this feed has something to play
     *
     * <p>Holidays fall out for free: a day the market did not trade has no bars
     * and no ticks, so it is simply absent. More accurate than any list of
     * holidays, and it never goes out of date.</p>
     */
    public NavigableSet<LocalDate> sessions() {
        return KNOWN.computeIfAbsent(saved(), key -> {
            if (isTicks()) {
                TickLibrary library =
                        new TickLibrary(SeriesCatalog.ticksOf(instrument), instrument, source);

                try {
                    return new TreeSet<>(library.exported());
                } finally {
                    library.close();
                }
            }

            try {
                return br.com.jorge.reis.endeavourneo.domain.market.Sessions.of(
                        SeriesCatalog.open(series).orElse(null));
            } catch (java.io.IOException e) {
                // A series that will not read has no playable days, which the
                // calendar shows as everything greyed. Better than a calendar
                // that offers days nothing can play.
                return new TreeSet<>();
            }
        });
    }

    /**
     * Works out every feed's days, for the calendar to be instant later.
     *
     * <p><b>Never on the interface thread.</b> Call it from a background job at
     * startup: it opens each series and walks it, which is a tenth of a second
     * each and would be a visible stall if it happened when a combo changed.</p>
     *
     * @return how many feeds were worked out
     */
    public static int warm() {
        int done = 0;

        for (ReplayFeed each : available()) {
            each.sessions();

            done++;
        }

        return done;
    }

    /** Drops what is remembered, for when the series on disk change. */
    public static void forget() {
        KNOWN.clear();
    }

    /**
     * @return how the transport names this feed
     *
     * <p>Market, then scale, then — for ticks — which export, which is the
     * order the tree already reads in.</p>
     */
    public String label() {
        String market = Messages.orElse("navigator.group." + instrument, instrument);

        if (isTicks()) {
            return market + "  ·  " + Messages.get("navigator.ticks")
                    + "  ·  " + Messages.orElse(
                            "navigator.tickSource." + source.key(), source.key());
        }

        String scale = SeriesCatalog.scaleOf(series);

        return market + "  ·  "
                + (scale.isEmpty() ? series
                        : Messages.orElse("navigator.scale." + scale, scale) + "  ·  "
                                + series);
    }

    /**
     * @return this feed as one line of settings text
     *
     * <p>So the transport can open where it was left. The two kinds are told
     * apart by the prefix rather than by guessing from the rest: a market can be
     * called anything, including something that looks like a series name.</p>
     */
    public String saved() {
        return isTicks() ? "ticks:" + instrument + ":" + source.name() : "series:" + series;
    }

    /** @return the feed that text names, or null if nothing on disk matches */
    public static ReplayFeed read(String text) {
        if (text == null) {
            return null;
        }

        for (ReplayFeed each : available()) {
            if (each.saved().equals(text)) {
                return each;
            }
        }

        return null;
    }

    @Override
    public String toString() {
        return label();
    }
}
