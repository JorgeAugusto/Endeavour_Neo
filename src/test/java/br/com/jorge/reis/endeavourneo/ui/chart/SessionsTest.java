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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where the previous session's close is found, and what happens when there
 * isn't one.
 */
@DisplayName("Sessions")
class SessionsTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    /** Three days of three bars each: closes 10..18, one per bar. */
    private static PriceSeries threeDays() {
        long[] times = new long[9];
        double[] closes = new double[9];

        for (int day = 0; day < 3; day++) {
            for (int bar = 0; bar < 3; bar++) {
                int i = day * 3 + bar;

                times[i] = LocalDateTime.of(2026, 9, 1 + day, 10 + bar, 0)
                        .atZone(ZONE).toInstant().toEpochMilli();
                closes[i] = 10 + i;
            }
        }

        return series(times, closes);
    }

    private static PriceSeries series(long[] times, double[] closes) {
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
                return closes[index];
            }

            @Override
            public double highAt(int index) {
                return closes[index];
            }

            @Override
            public double lowAt(int index) {
                return closes[index];
            }

            @Override
            public double closeAt(int index) {
                return closes[index];
            }
        };
    }

    @Test
    @DisplayName("the previous close is the LAST bar of the day before, not the first")
    void takesTheLastBarOfThePreviousDay() {
        // Day 2 runs over bars 3..5. Its reference is bar 2 (close 12), the last
        // of day 1 -- taking bar 0 would compare against a price nine hours stale.
        assertEquals(12.0, Sessions.previousDayClose(threeDays(), 4, ZONE));
    }

    @Test
    @DisplayName("every bar of a day shares the same reference")
    void allBarsOfADayAgree() {
        PriceSeries series = threeDays();

        assertEquals(Sessions.previousDayClose(series, 3, ZONE),
                Sessions.previousDayClose(series, 5, ZONE),
                "two bars of the same session disagreed about where it opened from");
    }

    @Test
    @DisplayName("the first day of the series has no previous close")
    void firstDayHasNoReference() {
        // NaN and not zero: zero would be drawn as a price and read as one.
        assertTrue(Double.isNaN(Sessions.previousDayClose(threeDays(), 1, ZONE)));
        assertTrue(Double.isNaN(Sessions.changeOnDay(
                series(new long[]{0L}, new double[]{10.0}), ZONE)));
    }

    @Test
    @DisplayName("the change on the day is the last bar against the day before")
    void changeOnDay() {
        // Last bar closes 18; the previous day closed 15. Three points on 15.
        assertEquals(20.0, Sessions.changeOnDay(threeDays(), ZONE), 1e-9);
    }

    @Test
    @DisplayName("nothing to compare against gives nothing, never zero")
    void missingReferenceIsNotZero() {
        assertTrue(Double.isNaN(Sessions.percentChange(0.0, 10.0)),
                "dividing by a zero reference must not answer");
        assertTrue(Double.isNaN(Sessions.percentChange(Double.NaN, 10.0)));
        assertEquals("", Sessions.formatChange(Double.NaN),
                "an unknown change has to render as nothing at all");
    }

    @Test
    @DisplayName("the sign is always written, including the plus")
    void alwaysSigned() {
        // A bare "3,04%" beside an instrument name reads as a quantity rather
        // than as a move.
        assertTrue(Sessions.formatChange(3.04).startsWith("+"));
        assertTrue(Sessions.formatChange(-3.04).startsWith("-"));
    }

    @Test
    @DisplayName("an empty or absent series answers nothing")
    void emptySeries() {
        assertTrue(Double.isNaN(Sessions.changeOnDay(PriceSeries.empty(), ZONE)));
        assertTrue(Double.isNaN(Sessions.changeOnDay(null, ZONE)));
        assertTrue(Double.isNaN(Sessions.previousDayClose(threeDays(), 99, ZONE)));
    }
}
