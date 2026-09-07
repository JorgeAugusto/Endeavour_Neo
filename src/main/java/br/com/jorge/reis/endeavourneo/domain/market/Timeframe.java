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
 * scale, and this is what gets it there. Anything from one minute to one month:
 * the named constants below are the ones worth listing, and
 * {@link #ofMinutes(int)} builds the rest.</p>
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
public final class Timeframe implements Aggregation {

    /** Marks a bucket that is a calendar unit rather than a count of minutes. */
    private static final int DAY = 0;

    private static final int WEEK = -1;

    private static final int MONTH = -2;

    /** A month of trading minutes: the longest a minute-count may be asked for. */
    public static final int MOST_MINUTES = 43_200;

    /** Minutes in a day. The line between slot arithmetic and whole-day counting. */
    private static final int DAY_MINUTES = 1_440;

    public static final Timeframe ONE_MINUTE = new Timeframe("1m", 1);

    public static final Timeframe FIVE_MINUTES = new Timeframe("5m", 5);

    public static final Timeframe FIFTEEN_MINUTES = new Timeframe("15m", 15);

    public static final Timeframe THIRTY_MINUTES = new Timeframe("30m", 30);

    public static final Timeframe ONE_HOUR = new Timeframe("1h", 60);

    public static final Timeframe DAILY = new Timeframe("D1", DAY);

    public static final Timeframe WEEKLY = new Timeframe("W1", WEEK);

    public static final Timeframe MONTHLY = new Timeframe("M1", MONTH);

    private final String label;

    /** Minutes per bar, or one of DAY, WEEK, MONTH. */
    private final int minutes;

    private Timeframe(String label, int minutes) {
        this.label = label;
        this.minutes = minutes;
    }

    /**
     * @param count how many minutes one bar covers, from 1 to {@link #MOST_MINUTES}
     * @return that scale, or null when the count is outside what is offered
     *
     * <p>A class and no longer an enum precisely for this. Seven minutes is a
     * perfectly good scale and the enum could not express it: the reader typed
     * <code>7</code> and was offered renko and nothing else. The named constants
     * are still here because they are the ones worth putting in a list.</p>
     */
    public static Timeframe ofMinutes(int count) {
        if (count < 1 || count > MOST_MINUTES) {
            return null;
        }

        for (Timeframe known : common()) {
            if (known.minutes == count) {
                return known;
            }
        }

        return new Timeframe(count + "m", count);
    }

    /** @return the scales worth listing before anything is typed */
    public static java.util.List<Timeframe> common() {
        return java.util.List.of(ONE_MINUTE, FIVE_MINUTES, FIFTEEN_MINUTES,
                THIRTY_MINUTES, ONE_HOUR, DAILY, WEEKLY, MONTHLY);
    }

    /** @return minutes per bar, or 0 for a day, -1 for a week, -2 for a month */
    public int minutes() {
        return minutes;
    }

    @Override
    public String label() {
        return label;
    }

    /**
     * The zone every fold uses when it is not handed one.
     *
     * <p><b>Settable, because the parameter on its own reached nothing.</b> The
     * Aggregation interface declares only {@code apply(source)}, so all six
     * callers in the application -- the chart twice, and the four indicators
     * that read a larger scale -- take the one-argument overload and land here.
     * The two-argument one was called from a test and from nowhere else: the
     * test proved a zone the application never used, and declaring a market zone
     * could not have reached a single indicator.</p>
     *
     * <p>Pushed in rather than read out. This is the domain, and it does not go
     * asking the settings for anything; whoever knows the reader's choice calls
     * {@link #useZone} at startup.</p>
     */
    private static volatile ZoneId zone = ZoneId.systemDefault();

    /** @return the zone this aggregation uses when nothing else is said */
    public static ZoneId defaultZone() {
        return zone;
    }

    /**
     * @param chosen the market's zone, or null to follow the machine
     *
     * <p>The market is in São Paulo whatever the machine is set to, and a
     * machine in another zone folds the day across the wrong boundary --
     * silently, because a daily bar looks like a daily bar either way.</p>
     */
    public static void useZone(ZoneId chosen) {
        zone = chosen == null ? ZoneId.systemDefault() : chosen;
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

        if (minutes == 1) {
            // Not an optimisation -- a correctness point. Folding one-minute
            // bars into one-minute buckets would still work, but only if the
            // stored bars really are aligned to the minute. Handing the source
            // back makes no claim about that.
            return source;
        }

        return fold(source, zone);
    }

    /**
     * @return the source folded into this scale, always as a copy
     *
     * <p>What {@link #apply} does, minus the shortcut it takes at one minute.
     * Two callers need the fold to happen even there.</p>
     *
     * <p>One is correctness: a series of TICKS is not a series of minute bars,
     * so handing it back unfolded would leave five million bars where five
     * hundred belong.</p>
     *
     * <p>The other is memory, and it is why this says COPY out loud. Tick bars
     * are a view over the session that made them, so anything holding them
     * holds its ninety megabytes. The copy is what lets the ticks go.</p>
     */
    public PriceSeries fold(PriceSeries source, ZoneId zone) {
        if (source == null || source.size() == 0) {
            return PriceSeries.empty();
        }

        ZoneId at = zone == null ? defaultZone() : zone;
        int total = source.size();

        long[] times = new long[total];
        double[] opens = new double[total];
        double[] highs = new double[total];
        double[] lows = new double[total];
        double[] closes = new double[total];
        double[] volumes = new double[total];

        // Whether anything was measured for the bar being built. Per BUCKET, and
        // not once for the whole series: the rule below -- that zero is a
        // measurement and "we do not know" is not the same statement -- is about
        // one output bar, so it has to be decided one output bar at a time.
        boolean measured = false;

        int out = -1;
        long currentBucket = Long.MIN_VALUE;

        for (int i = 0; i < total; i++) {
            long bucket = bucketOf(source.timeAt(i), at);

            if (bucket != currentBucket) {
                if (out >= 0 && !measured) {
                    volumes[out] = Double.NaN;
                }

                currentBucket = bucket;
                out++;
                measured = false;

                times[out] = startOf(source.timeAt(i), at);
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

            if (Double.isFinite(volume)) {
                volumes[out] += volume;
                measured = true;
            }
        }

        int kept = out + 1;

        // The last bucket, which the loop never closes.
        if (out >= 0 && !measured) {
            volumes[out] = Double.NaN;
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
     * @return when the bucket holding that instant begins
     *
     * <p>What a candle's timestamp means: 09:00, not 09:00:59 because that is
     * when the first trade of the minute happened. It used to be the source
     * bar's own time, which is the same thing whenever the source is already
     * aligned to this scale — and quietly is not when it comes from TICKS. A
     * session folded from trades came out with every candle stamped up to 59
     * seconds late, and nothing lined up with the minute base.</p>
     *
     * <p><b>Only for scales inside a day.</b> A day, week or month bucket
     * begins at midnight, and midnight is not a moment this market existed; the
     * first bar's time is the session's open, which is what a daily candle
     * should carry. So those keep it.</p>
     */
    private long startOf(long millis, ZoneId zone) {
        if (minutes <= 0) {
            return millis;
        }

        ZonedDateTime local = Instant.ofEpochMilli(millis).atZone(zone);
        long sinceMidnight = local.toLocalTime().toSecondOfDay() / 60L;
        long slot = sinceMidnight / minutes * minutes;

        return local.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                + slot * 60_000L;
    }

    /**
     * @return a key that is equal for two bars of the same output bar
     *
     * <p>Built from local calendar fields, never from the epoch — that is the
     * whole point of this class. See the two failures described above.</p>
     *
     * <p>This paragraph sat above {@code startOf} instead, in front of that
     * method's own javadoc. Java keeps the last one, so it documented nothing and
     * this method had none at all — and it is the method that decides where a day
     * ends.</p>
     */
    long bucketOf(long millis, ZoneId zone) {
        ZonedDateTime local = Instant.ofEpochMilli(millis).atZone(zone);

        if (minutes == DAY) {
            return local.toLocalDate().toEpochDay();
        }

        if (minutes == MONTH) {
            // Year and month together: a month number on its own would put every
            // September of every year in one bar.
            return local.getYear() * 12L + local.getMonthValue();
        }

        if (minutes == WEEK) {
            // Back to the local Monday. Not epochDay / 7, which starts weeks on
            // a Thursday because 1970-01-01 was one.
            return local.toLocalDate()
                    .minusDays(local.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue())
                    .toEpochDay();
        }

        if (minutes > DAY_MINUTES) {
            // Whole days from here up, because the arithmetic below divides the
            // minute OF THE DAY and that never reaches 1440. Every scale above a
            // day therefore divided to zero and bucketed one day at a time: a
            // three-day bar was a daily bar, and the only sign of it was a chart
            // carrying three times the bars that were asked for. MOST_MINUTES
            // lets a reader type up to thirty days, so this is reachable by
            // typing, not only by a layout from elsewhere.
            //
            // A scale that is not a whole number of days rounds DOWN to one --
            // 2000 minutes is a day and a bit, and there is no honest way to
            // draw the bit.
            return local.toLocalDate().toEpochDay() / Math.max(1, minutes / DAY_MINUTES);
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
