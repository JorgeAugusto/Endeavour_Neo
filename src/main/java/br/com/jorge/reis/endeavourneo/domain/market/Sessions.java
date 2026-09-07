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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * Which days a series actually holds.
 *
 * <p>Here, and not beside whoever asks, because three places want the same
 * answer for different reasons: the renko, to refuse building bricks unless
 * every session on screen has ticks; the transport, to grey out the days a feed
 * cannot play; and the segments window, to count what a segment covers. Three
 * copies of one walk is where the third one forgets that a holiday is not a
 * weekend.</p>
 *
 * <h2>Holidays come out for free</h2>
 *
 * <p>Nothing here knows the exchange's calendar, and nothing needs to. A day the
 * market did not trade has no bars, so it is simply absent — which is more
 * accurate than any list of holidays, and never goes out of date.</p>
 *
 * <h2>What it costs</h2>
 *
 * <p>Measured on the six-year source: 824.881 bars walked in 39-102 ms to find
 * 1.494 sessions. Fine once; not fine per repaint. Whoever asks should hold the
 * answer rather than ask again.</p>
 */
public final class Sessions {

    private Sessions() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param series the bars to walk; null is an empty answer, not a fault
     * @return the days it holds, in order
     *
     * <p><b>The market's zone, not the machine's.</b> Every fold in the project
     * reads {@link Timeframe#defaultZone}, which the launcher sets from {@code
     * data.zone}; this fell back to the machine and the five production callers
     * all use this overload -- the two-argument one is called from a test and
     * nowhere else, which is the same trap {@code Timeframe.useZone} documents
     * about itself.</p>
     *
     * <p>East of about UTC+6 the WIN session, 09:00 to 18:25 in Sao Paulo,
     * crosses local midnight: 1.494 sessions become some 2.900 dates. The renko
     * then refuses to build because a session on screen "has no ticks", the
     * transport offers days that never traded, and the summary shows twice the
     * sessions. And the error is CONSISTENT, because the cache keys on the zone
     * -- which makes it harder to notice, not easier.</p>
     */
    public static NavigableSet<LocalDate> of(PriceSeries series) {
        return of(series, Timeframe.defaultZone());
    }

    /**
     * What was answered for a series, and how far into it the answer goes.
     *
     * @param zone the zone it was walked in; another zone is another answer
     * @param upTo the series size when it was walked
     * @param days the sessions found, never handed out directly
     */
    private record Answer(ZoneId zone, int upTo, NavigableSet<LocalDate> days) { }

    /**
     * The answers already worked out, one per series.
     *
     * <p><b>Weak on the series</b>, so a chart that closes takes its entry with
     * it: a strong map here would hold every series ever opened, and each is
     * tens of megabytes.</p>
     *
     * <p>Synchronised on the map alone and never across the walk. Holding a lock
     * while walking 824.881 bars would stop whichever thread asked second for
     * as long as the walk takes, which is the very stall this cache exists to
     * remove — and it is a defect this project already has elsewhere. Two
     * threads racing both walk and both store the same answer, which costs one
     * wasted walk and is correct.</p>
     */
    private static final Map<PriceSeries, Answer> ANSWERED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * @param zone the zone the bars are read in, as everything else reads them
     *
     * <p><b>The answer is remembered.</b> The paragraph above asked whoever
     * calls this to hold the answer rather than ask again, and four callers did
     * not: the renko before rebuilding, the summary tooltip on every mouse move,
     * the transport on every combo change, and the segments window. Asking a
     * caller to remember is asking every future caller to remember, and the
     * fourth one forgets. So it is remembered here.</p>
     *
     * <p><b>A series that GREW is walked only where it grew.</b> The replay
     * appends bars as it plays, and bars are chronological, so the sessions
     * already found stay found and the tail adds to them. A series that SHRANK
     * -- the reader dragged the replay backwards -- is walked again from the
     * start, because dates have to leave the answer and there is no honest way
     * to know which without looking.</p>
     *
     * <p><b>What it assumes:</b> that a series of the same size holds the same
     * bars. True of every series here -- they are built once and appended to --
     * and false of one that rewrote a bar in place, which none does. Stated
     * because it is the assumption that would make this return a stale answer.</p>
     */
    public static NavigableSet<LocalDate> of(PriceSeries series, ZoneId zone) {
        if (series == null) {
            return new TreeSet<>();
        }

        ZoneId at = zone == null ? Timeframe.defaultZone() : zone;
        int size = series.size();
        Answer known = ANSWERED.get(series);

        if (known != null && known.zone().equals(at) && known.upTo() == size) {
            // A COPY. The set is kept, and a caller that sorted, cleared or
            // added to what it got back would be editing every other caller's
            // answer. Copying 1.494 dates is microseconds against the 27 ms the
            // walk costs.
            return new TreeSet<>(known.days());
        }

        boolean append = known != null && known.zone().equals(at) && size > known.upTo();

        NavigableSet<LocalDate> days = append ? new TreeSet<>(known.days()) : new TreeSet<>();
        int from = append ? known.upTo() : 0;
        LocalDate seen = days.isEmpty() ? null : days.last();

        for (int i = from; i < size; i++) {
            LocalDate day = Instant.ofEpochMilli(series.timeAt(i)).atZone(at).toLocalDate();

            // Compared with the last one rather than looked up in the set: the
            // bars are in order, so a day changes once per session instead of
            // once per bar, and the set is touched 1.494 times and not 824.881.
            if (!day.equals(seen)) {
                seen = day;

                days.add(day);
            }
        }

        ANSWERED.put(series, new Answer(at, size, days));

        return new TreeSet<>(days);
    }

    /**
     * Forgets everything remembered.
     *
     * <p>For tests, which build a series, ask, and then want to measure the walk
     * again. Nothing in the application needs it: an entry leaves on its own
     * when its series does.</p>
     */
    public static void forget() {
        ANSWERED.clear();
    }
}
