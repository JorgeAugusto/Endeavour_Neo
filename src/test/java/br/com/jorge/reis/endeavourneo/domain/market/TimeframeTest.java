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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Folding minutes into larger bars.
 *
 * <p>Most of what is checked here is the time zone, because that is where this
 * goes wrong — and goes wrong quietly. Both failures below were measured on the
 * previous project before anybody suspected them.</p>
 */
@DisplayName("Timeframe")
class TimeframeTest {

    /** Three hours behind UTC, and no daylight saving since 2019. */
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    private final List<long[]> stamps = new ArrayList<>();

    private final List<double[]> values = new ArrayList<>();

    /** Adds one minute bar at that local time, with those prices. */
    private void bar(LocalDateTime at, double open, double high, double low,
                     double close, double volume) {
        stamps.add(new long[]{at.atZone(SAO_PAULO).toInstant().toEpochMilli()});
        values.add(new double[]{open, high, low, close, volume});
    }

    private PriceSeries series() {
        return new PriceSeries() {

            @Override
            public int size() {
                return stamps.size();
            }

            @Override
            public long timeAt(int index) {
                return stamps.get(index)[0];
            }

            @Override
            public double openAt(int index) {
                return values.get(index)[0];
            }

            @Override
            public double highAt(int index) {
                return values.get(index)[1];
            }

            @Override
            public double lowAt(int index) {
                return values.get(index)[2];
            }

            @Override
            public double closeAt(int index) {
                return values.get(index)[3];
            }

            @Override
            public double volumeAt(int index) {
                return values.get(index)[4];
            }
        };
    }

    private static LocalDate dayOf(PriceSeries series, int index) {
        return Instant.ofEpochMilli(series.timeAt(index)).atZone(SAO_PAULO).toLocalDate();
    }

    // ------------------------------------------------------------ o essencial

    @Test
    @DisplayName("five one-minute bars become one, with the right four prices")
    void foldsFiveMinutes() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 2, 9, 0);

        bar(start,               100, 104,  99, 103, 10);
        bar(start.plusMinutes(1), 103, 108, 102, 105, 20);
        bar(start.plusMinutes(2), 105, 106,  95, 101, 30);
        bar(start.plusMinutes(3), 101, 107, 100, 106, 40);
        bar(start.plusMinutes(4), 106, 109, 104, 107, 50);
        bar(start.plusMinutes(5), 107, 110, 106, 109, 60);

        PriceSeries five = Timeframe.FIVE_MINUTES.apply(series(), SAO_PAULO);

        assertEquals(2, five.size(), "the sixth minute belongs to the next bar");
        assertEquals(100.0, five.openAt(0), "the open is the FIRST minute's open");
        assertEquals(109.0, five.highAt(0), "the high is the highest of the five");
        assertEquals(95.0,  five.lowAt(0),  "the low is the lowest of the five");
        assertEquals(107.0, five.closeAt(0), "the close is the LAST minute's close");
        assertEquals(150.0, five.volumeAt(0), "volume adds up");
    }

    @Test
    @DisplayName("the bar carries the time it opened, not the time it closed")
    void carriesTheOpeningTime() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 2, 9, 0);

        for (int i = 0; i < 5; i++) {
            bar(start.plusMinutes(i), 100, 100, 100, 100, 1);
        }

        assertEquals(start.atZone(SAO_PAULO).toInstant().toEpochMilli(),
                Timeframe.FIVE_MINUTES.apply(series(), SAO_PAULO).timeAt(0),
                "a 09:00 bar covering 09:00–09:04 must be stamped 09:00");
    }

    @Test
    @DisplayName("a first bar with no volume does not erase the volume of the rest")
    void oneBarWithoutVolumeDoesNotEraseTheOthers() {
        // The decision used to be taken from bar ZERO alone: if the first bar of
        // the whole series carried no volume, every summed volume in the result
        // was replaced by NaN. One auction bar at the front, or one quote-only
        // row, and the volume of six years went away -- silently, because NaN
        // draws as nothing and nothing looks like a series that has no volume.
        bar(LocalDateTime.of(2026, 9, 2, 9, 0), 100, 101, 99, 100, Double.NaN);
        bar(LocalDateTime.of(2026, 9, 2, 9, 1), 100, 102, 99, 101, 7);
        bar(LocalDateTime.of(2026, 9, 2, 9, 2), 101, 103, 100, 102, 5);

        PriceSeries five = Timeframe.FIVE_MINUTES.apply(series(), SAO_PAULO);

        assertEquals(12.0, five.volumeAt(0), 1e-9,
                "the volume that WAS measured was thrown away because the first bar "
                        + "had none");
    }

    @Test
    @DisplayName("a bar nobody measured says so, instead of claiming zero")
    void anUnmeasuredBarIsNotZero() {
        // The other half, and the reason the decision is per BUCKET: zero is a
        // measurement -- "nothing traded" -- and it is a different statement from
        // "we do not know". A five-minute bar built only from rows that carried
        // no volume knows nothing about volume.
        bar(LocalDateTime.of(2026, 9, 2, 9, 0), 100, 101, 99, 100, Double.NaN);
        bar(LocalDateTime.of(2026, 9, 2, 9, 1), 100, 102, 99, 101, Double.NaN);
        bar(LocalDateTime.of(2026, 9, 2, 9, 5), 101, 103, 100, 102, 9);

        PriceSeries five = Timeframe.FIVE_MINUTES.apply(series(), SAO_PAULO);

        assertEquals(2, five.size());
        assertTrue(Double.isNaN(five.volumeAt(0)),
                "a bar built from rows that carried no volume came out claiming zero");
        assertEquals(9.0, five.volumeAt(1), 1e-9,
                "and the next bar, which WAS measured, has to keep its number");
    }

    @Test
    @DisplayName("the stamp is the SLOT's start, even when the bars are not aligned to it")
    void theStampIsTheSlotAndNotTheFirstBar() {
        // The test above cannot see this, and that is the point of adding one.
        // Its bars begin exactly at 09:00, already on the five-minute grid, so
        // "the slot's start" and "the first bar's own time" are the same number
        // and any implementation that returned either passes.
        //
        // The defect this guards is documented in startOf: a session folded from
        // TICKS came out with every candle stamped up to fifty-nine seconds
        // late, and nothing lined up with the minute base. Bars that start at
        // 09:02 tell the two answers apart.
        LocalDateTime start = LocalDateTime.of(2026, 9, 2, 9, 2);

        for (int i = 0; i < 5; i++) {
            bar(start.plusMinutes(i), 100, 100, 100, 100, 1);
        }

        PriceSeries five = Timeframe.FIVE_MINUTES.apply(series(), SAO_PAULO);

        assertEquals(LocalDateTime.of(2026, 9, 2, 9, 0)
                        .atZone(SAO_PAULO).toInstant().toEpochMilli(),
                five.timeAt(0),
                "the bar was stamped when its first minute arrived, not when its slot began");
    }

    @Test
    @DisplayName("the chosen zone reaches the fold that takes no zone at all")
    void theChosenZoneReachesTheOneArgumentFold() {
        // The gap this closes. Aggregation declares only apply(source), so every
        // caller in the application takes that overload; apply(source, zone) was
        // called from THIS FILE and from nowhere else. The tests here pinned
        // America/Sao_Paulo and passed, while the application folded in the
        // machine's zone and no setting could have changed it.
        //
        // 22:30 in São Paulo is 01:30 the next day in UTC, so the two zones put
        // this bar on different dates -- which is exactly what a daily fold is
        // deciding, and exactly what nothing could reach.
        bar(LocalDateTime.of(2026, 9, 2, 22, 30), 100, 101, 99, 100, 1);

        ZoneId was = Timeframe.defaultZone();

        try {
            Timeframe.useZone(SAO_PAULO);

            LocalDate here = Instant.ofEpochMilli(Timeframe.DAILY.apply(series()).timeAt(0))
                    .atZone(SAO_PAULO).toLocalDate();

            Timeframe.useZone(ZoneId.of("UTC"));

            LocalDate utc = Instant.ofEpochMilli(Timeframe.DAILY.apply(series()).timeAt(0))
                    .atZone(ZoneId.of("UTC")).toLocalDate();

            assertNotEquals(here, utc,
                    "the one-argument fold ignored the chosen zone, so declaring a market "
                            + "zone reaches nothing the reader sees");
            assertEquals(LocalDate.of(2026, 9, 2), here);
            assertEquals(LocalDate.of(2026, 9, 3), utc);
        } finally {
            Timeframe.useZone(was);
        }
    }

    @Test
    @DisplayName("a month folds to ONE bar, and every September is not one bucket")
    void monthlyFoldsAMonth() {
        // MONTHLY did not appear anywhere in this file. Its bucket is
        // year * 12 + month precisely because a month number alone would put
        // every September of every year in the same bar, and that reasoning was
        // written in a comment with nothing checking it.
        bar(LocalDateTime.of(2025, 9, 2, 10, 0), 100, 106, 98, 105, 1);
        bar(LocalDateTime.of(2025, 9, 30, 10, 0), 105, 108, 99, 107, 1);
        bar(LocalDateTime.of(2025, 10, 1, 10, 0), 107, 110, 100, 109, 1);
        bar(LocalDateTime.of(2026, 9, 1, 10, 0), 200, 210, 190, 205, 1);

        PriceSeries monthly = Timeframe.MONTHLY.apply(series(), SAO_PAULO);

        assertEquals(3, monthly.size(),
                "September 2025, October 2025 and September 2026 are three months");
        assertEquals(100.0, monthly.openAt(0), "the month opens where its first bar opened");
        assertEquals(107.0, monthly.closeAt(0), "the month closes where its last bar closed");
        assertEquals(108.0, monthly.highAt(0));
        assertEquals(98.0, monthly.lowAt(0));

        // The one the comment is about: a year apart is not the same bucket.
        assertNotEquals(dayOf(monthly, 0).getYear(), dayOf(monthly, 2).getYear(),
                "September 2025 and September 2026 were folded into one bar");
    }

    // ----------------------------------------------------- as duas armadilhas

    @Test
    @DisplayName("a daily bar carries ITS OWN day, not the day before")
    void dailyKeepsItsOwnDate() {
        // The failure this exists for: Brazil is three hours behind UTC, so an
        // 18:00 local bar is 21:00 UTC. Bucketing on the epoch splits a session
        // across two "days" and the daily bar comes out dated the day before.
        LocalDate day = LocalDate.of(2026, 9, 2);

        bar(day.atTime(9, 0),  100, 101,  99, 100, 1);
        bar(day.atTime(13, 0), 100, 105,  98, 104, 1);
        bar(day.atTime(18, 0), 104, 106, 103, 105, 1);
        bar(day.plusDays(1).atTime(9, 0), 105, 107, 104, 106, 1);

        PriceSeries daily = Timeframe.DAILY.apply(series(), SAO_PAULO);

        assertEquals(2, daily.size(), "a whole session must fold into ONE daily bar");
        assertEquals(day, dayOf(daily, 0), "the daily bar is dated the day before its own session");
        assertEquals(100.0, daily.openAt(0));
        assertEquals(106.0, daily.highAt(0));
        assertEquals(98.0, daily.lowAt(0));
        assertEquals(105.0, daily.closeAt(0));
    }

    @Test
    @DisplayName("a week starts on Monday, not on Thursday")
    void weekStartsOnMonday() {
        // The failure this exists for: epochDay / 7 groups weeks from a
        // Thursday, because 1970-01-01 was one. Nothing complains; the weekly
        // chart simply shows the wrong weeks.
        LocalDate monday = LocalDate.of(2026, 8, 31);

        assertEquals(DayOfWeek.MONDAY, monday.getDayOfWeek(), "the fixture is wrong");

        // Monday to Sunday, and then the NEXT Monday: seven bars would all be
        // one week and would prove nothing about where the week turns.
        for (int i = 0; i < 8; i++) {
            bar(monday.plusDays(i).atTime(10, 0), 100 + i, 100 + i, 100 + i, 100 + i, 1);
        }

        PriceSeries weekly = Timeframe.WEEKLY.apply(series(), SAO_PAULO);

        assertEquals(2, weekly.size(), "Monday to Sunday is one week and the next Monday another");
        assertEquals(monday, dayOf(weekly, 0));
        assertEquals(106.0, weekly.closeAt(0), "Sunday belongs to the week that began Monday");
        assertEquals(monday.plusDays(7), dayOf(weekly, 1));
    }

    @Test
    @DisplayName("the same clock time on two days is not the same bar")
    void sameTimeDifferentDays() {
        // A slot number computed without the date would put Monday 09:00 and
        // Tuesday 09:00 in one bucket, and quietly weld the sessions together.
        bar(LocalDateTime.of(2026, 9, 1, 9, 0), 100, 100, 100, 100, 1);
        bar(LocalDateTime.of(2026, 9, 2, 9, 0), 200, 200, 200, 200, 1);

        assertEquals(2, Timeframe.FIVE_MINUTES.apply(series(), SAO_PAULO).size());
    }

    @Test
    @DisplayName("the buckets of two zones disagree, which is why the zone is asked for")
    void theZoneMatters() {
        long noon = LocalDateTime.of(2026, 9, 2, 22, 30)
                .atZone(SAO_PAULO).toInstant().toEpochMilli();

        // 22:30 in São Paulo is 01:30 the next day in UTC. If the zone made no
        // difference, this class would not need to take one.
        assertNotEquals(Timeframe.DAILY.bucketOf(noon, SAO_PAULO),
                Timeframe.DAILY.bucketOf(noon, ZoneId.of("UTC")));
    }

    /** Ten in the morning on a given day, in the market's zone. */
    private static long morningOf(java.time.LocalDate day) {
        return day.atTime(10, 0).atZone(SAO_PAULO).toInstant().toEpochMilli();
    }

    @Test
    @DisplayName("a scale of several days folds several days, and does not collapse to one")
    void aScaleAboveADayDoesNotBecomeDaily() {
        // The hole this test fills is not a weak assertion -- it is that nothing
        // in this file ever folded anything above thirty minutes. ofMinutes does
        // not appear once, and MOST_MINUTES lets a reader type up to thirty days.
        //
        // The arithmetic underneath divides the minute OF THE DAY, which never
        // reaches 1440. Every scale above a day therefore divided to zero and
        // bucketed by whole days -- a three-day bar was a daily bar, and the only
        // sign of it was a chart with three times as many bars as asked for.
        Timeframe threeDays = Timeframe.ofMinutes(3 * 24 * 60);

        java.time.LocalDate base = java.time.LocalDate.of(2026, 9, 7);
        int sharedWithTheNextDay = 0;

        for (int day = 0; day < 30; day++) {
            java.time.LocalDate today = base.plusDays(day);

            long mine = threeDays.bucketOf(morningOf(today), SAO_PAULO);

            if (mine == threeDays.bucketOf(morningOf(today.plusDays(1)), SAO_PAULO)) {
                sharedWithTheNextDay++;
            }

            // Whatever the alignment, three days apart is always a new bar.
            assertNotEquals(mine, threeDays.bucketOf(morningOf(today.plusDays(3)), SAO_PAULO),
                    today + " and three days later fell in the same bar");
        }

        // Said without depending on where the groups happen to start: over
        // thirty days, two consecutive days have to share a bar most of the
        // time. Collapsed to daily, this counter is zero.
        assertEquals(20, sharedWithTheNextDay,
                "consecutive days shared a bar " + sharedWithTheNextDay
                        + " times out of 30; a three-day scale folded like D1");
    }

    @Test
    @DisplayName("uma barra de varios dias carrega a ABERTURA, nao a meia-noite")
    void amultiDayBarCarriesTheOpen() {
        // The javadoc of startOf says it out loud: "a day, week or month bucket
        // begins at midnight, and midnight is not a moment this market existed;
        // the first bar's time is the session's open". Its guard covered D1, W1
        // and M1 -- and not a scale of several days written in MINUTES, which is
        // what a reader types. The minute of the day never reaches 4320, so the
        // slot came out zero and the bar was stamped with local midnight.
        //
        // Written against a folded series and not against startOf, because the
        // stamp is what the time axis reads and what a search by instant has to
        // match.
        java.time.LocalDate monday = java.time.LocalDate.of(2026, 9, 7);
        long open = morningOf(monday);

        PriceSeries folded = Timeframe.ofMinutes(3 * 24 * 60).apply(
                oneBarPerDayFrom(monday, 10), SAO_PAULO);

        assertTrue(folded.size() > 0, "the fixture folded to nothing");
        assertEquals(open, folded.timeAt(0),
                "the bar was stamped with midnight, which is not a moment this market "
                        + "existed and not an instant the minute series can be searched for");
    }

    @Test
    @DisplayName("uma escala de sete dias corta onde a W1 corta, nao numa quinta de 1970")
    void asevenDayScaleCutsWhereTheWeekCuts() {
        // epochDay / 7 groups from a Thursday, because 1970-01-01 was one. The
        // javadoc of this class names that trap in so many words, three
        // paragraphs before promising that buckets come "from local calendar
        // fields in the exchange's zone, never from the epoch" -- and the line
        // for scales above a day was epochDay / N.
        //
        // Typing 10080 therefore gave a week cut three days away from the one W1
        // draws, out of the same object, with nothing saying so.
        Timeframe sevenDays = Timeframe.ofMinutes(7 * 24 * 60);

        java.time.LocalDate monday = java.time.LocalDate.of(2026, 9, 7);

        assertEquals(java.time.DayOfWeek.MONDAY, monday.getDayOfWeek(),
                "the fixture does not start on a Monday");

        // Monday to Sunday is one bar.
        for (int day = 1; day < 7; day++) {
            assertEquals(sevenDays.bucketOf(morningOf(monday), SAO_PAULO),
                    sevenDays.bucketOf(morningOf(monday.plusDays(day)), SAO_PAULO),
                    monday.plusDays(day) + " fell outside the week that started on "
                            + monday);
        }

        // And the day before, and the day after, are not.
        assertNotEquals(sevenDays.bucketOf(morningOf(monday), SAO_PAULO),
                sevenDays.bucketOf(morningOf(monday.minusDays(1)), SAO_PAULO),
                "the Sunday before was folded into the week that starts on Monday");
        assertNotEquals(sevenDays.bucketOf(morningOf(monday), SAO_PAULO),
                sevenDays.bucketOf(morningOf(monday.plusDays(7)), SAO_PAULO),
                "the next Monday was folded into the previous week");

        // Which is exactly what W1 does, and the point is that the two agree.
        assertEquals(Timeframe.WEEKLY.bucketOf(morningOf(monday), SAO_PAULO)
                        == Timeframe.WEEKLY.bucketOf(morningOf(monday.plusDays(6)), SAO_PAULO),
                sevenDays.bucketOf(morningOf(monday), SAO_PAULO)
                        == sevenDays.bucketOf(morningOf(monday.plusDays(6)), SAO_PAULO),
                "seven days typed in minutes and W1 disagree about what a week is");
    }

    /** One bar a day at that hour, thirty of them, so a multi-day fold has something to fold. */
    private static PriceSeries oneBarPerDayFrom(java.time.LocalDate first, int hour) {
        long[] times = new long[30];

        for (int day = 0; day < times.length; day++) {
            times[day] = first.plusDays(day).atTime(hour, 0)
                    .atZone(SAO_PAULO).toInstant().toEpochMilli();
        }

        return new PriceSeries() {

            @Override
            public int size() {
                return times.length;
            }

            @Override
            public long timeAt(int index) {
                return times[index];
            }

            @Override
            public double openAt(int index) {
                return 100 + index;
            }

            @Override
            public double highAt(int index) {
                return 101 + index;
            }

            @Override
            public double lowAt(int index) {
                return 99 + index;
            }

            @Override
            public double closeAt(int index) {
                return 100 + index;
            }
        };
    }

    @Test
    @DisplayName("the hour scale folds hours, not each day whole")
    void theHourScaleFoldsHours() {
        // ONE_HOUR is declared and was never folded by this file either. It is
        // the one scale between the thirty minutes that was tested and the day
        // that has its own branch.
        long nine = LocalDateTime.of(2026, 9, 2, 9, 5).atZone(SAO_PAULO).toInstant().toEpochMilli();
        long alsoNine = LocalDateTime.of(2026, 9, 2, 9, 55)
                .atZone(SAO_PAULO).toInstant().toEpochMilli();
        long ten = LocalDateTime.of(2026, 9, 2, 10, 5).atZone(SAO_PAULO).toInstant().toEpochMilli();

        assertEquals(Timeframe.ONE_HOUR.bucketOf(nine, SAO_PAULO),
                Timeframe.ONE_HOUR.bucketOf(alsoNine, SAO_PAULO));
        assertNotEquals(Timeframe.ONE_HOUR.bucketOf(alsoNine, SAO_PAULO),
                Timeframe.ONE_HOUR.bucketOf(ten, SAO_PAULO));
    }

    // ---------------------------------------------------------------- bordas

    @Test
    @DisplayName("an incomplete last bar is kept")
    void keepsTheIncompleteTail() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 2, 9, 0);

        bar(start,               100, 100, 100, 100, 1);
        bar(start.plusMinutes(5), 200, 200, 200, 200, 1);
        bar(start.plusMinutes(6), 300, 300, 300, 300, 1);

        PriceSeries five = Timeframe.FIVE_MINUTES.apply(series(), SAO_PAULO);

        assertEquals(2, five.size(), "the unfinished period is still the last bar there is");
        assertEquals(300.0, five.closeAt(1));
    }

    @Test
    @DisplayName("a series with no volume stays without volume, and does not claim zero")
    void absentVolumeStaysAbsent() {
        // "There was no trading" and "we do not know" are different statements,
        // and a summed zero would make the second look like the first.
        bar(LocalDateTime.of(2026, 9, 2, 9, 0), 100, 100, 100, 100, Double.NaN);
        bar(LocalDateTime.of(2026, 9, 2, 9, 1), 100, 100, 100, 100, Double.NaN);

        assertTrue(Double.isNaN(Timeframe.FIVE_MINUTES.apply(series(), SAO_PAULO).volumeAt(0)));
    }

    @Test
    @DisplayName("one minute hands the series straight back")
    void oneMinuteChangesNothing() {
        bar(LocalDateTime.of(2026, 9, 2, 9, 0), 100, 100, 100, 100, 1);

        PriceSeries source = series();

        assertSame(source, Timeframe.ONE_MINUTE.apply(source, SAO_PAULO));
    }

    @Test
    @DisplayName("an empty series folds to an empty series")
    void emptyFoldsToEmpty() {
        assertEquals(0, Timeframe.DAILY.apply(PriceSeries.empty(), SAO_PAULO).size());
        assertEquals(0, Timeframe.DAILY.apply(null, SAO_PAULO).size());
    }

    @Test
    @DisplayName("a gap between sessions does not merge them")
    void gapsDoNotMerge() {
        // Friday's last bar and Monday's first are minutes apart in index and
        // days apart in time. An aggregation counting bars instead of reading
        // clocks would weld them.
        bar(LocalDateTime.of(2026, 9, 4, 17, 55), 100, 100, 100, 100, 1);
        bar(LocalDateTime.of(2026, 9, 7, 9, 0),   200, 200, 200, 200, 1);

        assertEquals(2, Timeframe.THIRTY_MINUTES.apply(series(), SAO_PAULO).size());
    }
}
