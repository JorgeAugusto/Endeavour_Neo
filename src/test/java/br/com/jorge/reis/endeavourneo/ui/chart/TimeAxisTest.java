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
package br.com.jorge.reis.endeavourneo.ui.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.awt.Rectangle;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the time axis is talking about.
 *
 * <p>Reported from a daily chart of eight years: the top row wrote a clock
 * label for every bar — 09:00, 09:03, 09:02, the minute each session happened
 * to open, which says nothing — and the band below, one narrow day per bar, had
 * no room for a single label and came out blank. The reference product puts day
 * numbers on top and the month underneath, and it is right.</p>
 */
@DisplayName("Time axis")
class TimeAxisTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** Bars every {@code apart} minutes from a fixed morning. */
    private static PriceSeries every(int minutes, int count) {
        long start = LocalDateTime.of(2020, 3, 2, 9, 0).atZone(ZONE).toInstant().toEpochMilli();

        return new PriceSeries() {

            @Override
            public int size() {
                return count;
            }

            @Override
            public long timeAt(int index) {
                return start + (long) index * minutes * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return 100_000;
            }

            @Override
            public double highAt(int index) {
                return 100_100;
            }

            @Override
            public double lowAt(int index) {
                return 99_900;
            }

            @Override
            public double closeAt(int index) {
                return 100_050;
            }
        };
    }

    private static ChartCanvas showing(PriceSeries series) {
        ChartCanvas canvas = new ChartCanvas();

        canvas.setSeries(series);
        canvas.setSize(900, 500);

        return canvas;
    }

    @Test
    @DisplayName("a daily chart is labelled with dates, not with the minute each session opened")
    void aDailyChartTalksAboutDays() {
        // A year of daily bars. Every bar is a new day, which is exactly the
        // case that produced a row of meaningless clock labels.
        ChartCanvas canvas = showing(every(24 * 60, 250));

        assertTrue(canvas.axisSpeaksInDaysFor(Viewport.of(canvas.series(),
                        new Rectangle(0, 0, 900, 400), 0, 250)),
                "a chart of 250 days still thinks it is talking about hours");
    }

    @Test
    @DisplayName("a chart of one session is labelled with the clock")
    void oneSessionTalksAboutHours() {
        // Nine hours of one-minute bars. Dates here would be one date repeated
        // across the whole axis, which is worse than useless.
        ChartCanvas canvas = showing(every(1, 540));

        assertFalse(canvas.axisSpeaksInDaysFor(Viewport.of(canvas.series(),
                        new Rectangle(0, 0, 900, 400), 0, 540)),
                "a single session was labelled with dates");
    }

    @Test
    @DisplayName("what decides is the span on screen, not the size of a bar")
    void theSpanDecidesNotThePeriod() {
        // The same one-minute bars, zoomed out to three months. The period has
        // not changed and the answer has: this is what makes the rule work for
        // renko too, whose bars have no length to ask about.
        ChartCanvas canvas = showing(every(1, 130_000));

        assertFalse(canvas.axisSpeaksInDaysFor(Viewport.of(canvas.series(),
                        new Rectangle(0, 0, 900, 400), 0, 300)),
                "five hours on screen were labelled with dates");
        assertTrue(canvas.axisSpeaksInDaysFor(Viewport.of(canvas.series(),
                        new Rectangle(0, 0, 900, 400), 0, 130_000)),
                "three months on screen were labelled with the clock");
    }

    private static ZonedDateTime at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE);
    }

    @Test
    @DisplayName("the weekly label falls on a Monday, not on a Thursday")
    void theWeekBeginsOnMonday() {
        // The whole reason axisBucket exists as a method. It used to be
        // millis / (step * 60_000) inline in the paint, which at the weekly step
        // is epochDay / 7 -- and 1970-01-01 was a Thursday, so the axis drew its
        // weeks from Thursday to Wednesday. Timeframe.bucketOf forbids exactly
        // this, in a comment, and the day band beneath the axis already used the
        // zone: the two disagreed inside one repaint.
        int week = 10_080;

        // 2026-09-07 is a Monday. Sunday belongs to the week that began before
        // it; Monday opens a new one.
        assertEquals(ChartCanvas.axisBucket(at(2026, 9, 2, 12, 0), week),
                ChartCanvas.axisBucket(at(2026, 9, 6, 23, 59), week),
                "Wednesday and the Sunday after it fell in different weeks");
        assertNotEquals(ChartCanvas.axisBucket(at(2026, 9, 6, 23, 59), week),
                ChartCanvas.axisBucket(at(2026, 9, 7, 0, 1), week),
                "Sunday night and Monday morning fell in the same week");

        // And the failure the old arithmetic produced, stated directly: Wednesday
        // and the Thursday after it are ONE week, not two.
        assertEquals(ChartCanvas.axisBucket(at(2026, 9, 2, 12, 0), week),
                ChartCanvas.axisBucket(at(2026, 9, 3, 12, 0), week),
                "the week still breaks on a Thursday");
    }

    @Test
    @DisplayName("a step below a day divides the local day, not the epoch")
    void theDayIsDividedLocally() {
        // Two bars either side of local midnight have to land in different
        // buckets whatever the machine's offset from UTC is -- which is the part
        // dividing raw millis got wrong everywhere but Greenwich.
        assertNotEquals(ChartCanvas.axisBucket(at(2026, 9, 2, 23, 59), 60),
                ChartCanvas.axisBucket(at(2026, 9, 3, 0, 1), 60),
                "the last hour of one day and the first of the next were one bucket");

        // Within the hour, one bucket; across it, two.
        assertEquals(ChartCanvas.axisBucket(at(2026, 9, 2, 14, 5), 60),
                ChartCanvas.axisBucket(at(2026, 9, 2, 14, 55), 60));
        assertNotEquals(ChartCanvas.axisBucket(at(2026, 9, 2, 14, 55), 60),
                ChartCanvas.axisBucket(at(2026, 9, 2, 15, 5), 60));
    }

    @Test
    @DisplayName("the bucket never goes backwards as time goes forward")
    void bucketsAdvanceWithTime() {
        // A property rather than three numbers: whatever the step, a later bar
        // can never be given a smaller bucket, or the axis would draw a label at
        // every bar for the rest of the chart.
        for (int step : new int[]{1, 5, 15, 60, 240, 720, 1_440, 2_880, 10_080}) {
            long previous = Long.MIN_VALUE;

            for (int day = 1; day <= 40; day++) {
                for (int hour : new int[]{0, 6, 13, 23}) {
                    long bucket = ChartCanvas.axisBucket(at(2026, 9, 1, hour, 0).plusDays(day), step);

                    assertTrue(bucket >= previous,
                            "step " + step + " went backwards at day " + day + " hour " + hour);

                    previous = bucket;
                }
            }
        }
    }
}
