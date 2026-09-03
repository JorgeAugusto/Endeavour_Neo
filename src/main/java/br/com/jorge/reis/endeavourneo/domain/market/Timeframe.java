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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The scale bars are looked at: five minutes, a day, a week.
 *
 * <p>One minute is the storage format; every measurement is made at some other
 * scale, and this is what gets it there.</p>
 *
 * <h2>Everything here turns on the time zone</h2>
 *
 * <p>The tempting implementation buckets by dividing the epoch: bar time over
 * five minutes, or over a day, or over a week. It is fast, it is one line, and
 * it is wrong in a way nobody sees until they read a date:</p>
 *
 * <ul>
 *   <li><b>Weeks would run Thursday to Wednesday.</b> Epoch day zero was a
 *       Thursday, so {@code epochDay / 7} groups weeks from Thursday.</li>
 *   <li><b>Daily bars would carry the previous day's date.</b> Brazil is three
 *       hours behind UTC, so an 18:00 local bar is 21:00 UTC — but a 22:00 local
 *       bar is 01:00 UTC of the <i>next</i> day, and the session gets split
 *       across two "days" that belong to neither.</li>
 * </ul>
 *
 * <p>Both were measured on the previous project rather than reasoned about, and
 * both are avoided here the same way: <b>buckets are computed from local
 * calendar fields in the exchange's zone</b>, never from the epoch.</p>
 *
 * <h2>Two conventions worth stating</h2>
 *
 * <p><b>An aggregate bar carries the time of its first source bar</b> — the
 * moment the period opened. The stored minutes are labelled that way too, so a
 * five-minute bar at 09:00 covers 09:00 to 09:04 and the chart's axis needs no
 * special case. Using the closing time instead is also defensible and is what
 * ta4j does; mixing the two is what is not.</p>
 *
 * <p><b>The final bar may be incomplete</b> and is kept anyway. It is the last
 * bar there is; nothing comes after it to look ahead to, and dropping it would
 * silently shorten every series by up to one period.</p>
 */
public enum Timeframe implements Aggregation {

    ONE_MINUTE("1m", 1),
    FIVE_MINUTES("5m", 5),
    FIFTEEN_MINUTES("15m", 15),
    THIRTY_MINUTES("30m", 30),
    ONE_HOUR("1h", 60),
    DAILY("D1", 0),
    WEEKLY("W1", -1);

    private final String label;

    /** Minutes per bar; 0 means a calendar day and -1 a calendar week. */
    private final int minutes;

    Timeframe(String label, int minutes) {
        this.label = label;
        this.minutes = minutes;
    }

    public String label() {
        return label;
    }

    /** @return the zone this aggregation uses when nothing else is said */
    public static ZoneId defaultZone() {
        return ZoneId.systemDefault();
    }

    @Override
    public PriceSeries apply(PriceSeries source) {
        return apply(source, defaultZone());
    }

    /**
     * @param source the bars to fold, chronological
     * @param zone the zone whose calendar decides where a day and a week begin
     * @return the folded bars
     */
    public PriceSeries apply(PriceSeries source, ZoneId zone) {
        if (source == null || source.size() == 0) {
            return PriceSeries.empty();
        }

        if (this == ONE_MINUTE) {
            // Not an optimisation -- a correctness point. Folding one-minute
            // bars into one-minute buckets would still work, but only if the
            // stored bars really are aligned to the minute. Handing the source
            // back makes no claim about that.
            return source;
        }

        ZoneId at = zone == null ? defaultZone() : zone;
        int total = source.size();

        long[] times = new long[total];
        double[] opens = new double[total];
        double[] highs = new double[total];
        double[] lows = new double[total];
        double[] closes = new double[total];
        double[] volumes = new double[total];

        int out = -1;
        long currentBucket = Long.MIN_VALUE;

        for (int i = 0; i < total; i++) {
            long bucket = bucketOf(source.timeAt(i), at);

            if (bucket != currentBucket) {
                currentBucket = bucket;
                out++;

                times[out] = source.timeAt(i);
                opens[out] = source.openAt(i);
                highs[out] = source.highAt(i);
                lows[out] = source.lowAt(i);
                volumes[out] = 0.0;
            } else {
                highs[out] = Math.max(highs[out], source.highAt(i));
                lows[out] = Math.min(lows[out], source.lowAt(i));
            }

            closes[out] = source.closeAt(i);

            double volume = source.volumeAt(i);

            // Only finite volumes are added. A series with no volume at all
            // therefore ends at zero, not NaN -- see below.
            if (Double.isFinite(volume)) {
                volumes[out] += volume;
            }
        }

        int kept = out + 1;

        // A series that carries no volume must not come out claiming zero: zero
        // is a measurement, and "there was no trading" is a different statement
        // from "we do not know".
        if (!Double.isFinite(source.volumeAt(0))) {
            java.util.Arrays.fill(volumes, 0, kept, Double.NaN);
        }

        return new ArraySeries(
                java.util.Arrays.copyOf(times, kept),
                java.util.Arrays.copyOf(opens, kept),
                java.util.Arrays.copyOf(highs, kept),
                java.util.Arrays.copyOf(lows, kept),
                java.util.Arrays.copyOf(closes, kept),
                java.util.Arrays.copyOf(volumes, kept));
    }

    /**
     * @return a key that is equal for two bars of the same output bar
     *
     * <p>Built from local calendar fields, never from the epoch — that is the
     * whole point of this class. See the two failures described above.</p>
     */
    long bucketOf(long millis, ZoneId zone) {
        ZonedDateTime local = Instant.ofEpochMilli(millis).atZone(zone);

        if (this == DAILY) {
            return local.toLocalDate().toEpochDay();
        }

        if (this == WEEKLY) {
            // Back to the local Monday. Not epochDay / 7, which starts weeks on
            // a Thursday because 1970-01-01 was one.
            return local.toLocalDate()
                    .minusDays(local.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue())
                    .toEpochDay();
        }

        // Day first, then the slot within the day: a slot number on its own
        // would put 09:00 on Monday and 09:00 on Tuesday in the same bucket.
        int minuteOfDay = local.getHour() * 60 + local.getMinute();

        return local.toLocalDate().toEpochDay() * 1_440L + (minuteOfDay / minutes) * (long) minutes;
    }

    @Override
    public String toString() {
        return label;
    }
}
