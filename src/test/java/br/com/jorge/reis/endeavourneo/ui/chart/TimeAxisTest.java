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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.awt.Rectangle;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
}
