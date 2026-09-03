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
