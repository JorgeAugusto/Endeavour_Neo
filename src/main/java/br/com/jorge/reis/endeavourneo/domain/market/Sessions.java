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
import java.util.NavigableSet;
import java.util.TreeSet;

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
     */
    public static NavigableSet<LocalDate> of(PriceSeries series) {
        return of(series, ZoneId.systemDefault());
    }

    /** @param zone the zone the bars are read in, as everything else reads them */
    public static NavigableSet<LocalDate> of(PriceSeries series, ZoneId zone) {
        NavigableSet<LocalDate> days = new TreeSet<>();

        if (series == null) {
            return days;
        }

        ZoneId at = zone == null ? ZoneId.systemDefault() : zone;
        LocalDate seen = null;

        for (int i = 0; i < series.size(); i++) {
            LocalDate day = Instant.ofEpochMilli(series.timeAt(i)).atZone(at).toLocalDate();

            // Compared with the last one rather than looked up in the set: the
            // bars are in order, so a day changes once per session instead of
            // once per bar, and the set is touched 1.494 times and not 824.881.
            if (!day.equals(seen)) {
                seen = day;

                days.add(day);
            }
        }

        return days;
    }
}
