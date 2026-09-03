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
package br.com.jorge.reis.endeavourneo.ui.chart.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An average computed on a larger scale than the chart it is drawn on.
 *
 * <p>This is where multi-timeframe indicators lie, and this project has paid for
 * it once already. The obvious mapping takes, for each bar on screen, the coarse
 * bar that CONTAINS it — and that bar is made partly of the future.</p>
 */
@DisplayName("Average on its own period")
class OwnPeriodTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** Fifteen one-minute bars from 09:00, closing at 1, 2, 3 ... 15. */
    private static PriceSeries minutes() {
        return new PriceSeries() {

            @Override
            public int size() {
                return 15;
            }

            @Override
            public long timeAt(int index) {
                return LocalDateTime.of(2026, 9, 2, 9, 0).plusMinutes(index)
                        .atZone(ZONE).toInstant().toEpochMilli();
            }

            @Override
            public double openAt(int index) {
                return index + 1;
            }

            @Override
            public double highAt(int index) {
                return index + 1;
            }

            @Override
            public double lowAt(int index) {
                return index + 1;
            }

            @Override
            public double closeAt(int index) {
                return index + 1;
            }
        };
    }

    private static MovingAverage onFiveMinutes() {
        MovingAverage average = new MovingAverage(1);

        average.setOwnPeriod("5m");
        average.setInterpolated(false);
        average.calculate(minutes());

        return average;
    }

    @Test
    @DisplayName("nothing is drawn before the first coarse bar has closed")
    void nothingBeforeTheFirstClose() {
        MovingAverage average = onFiveMinutes();

        for (int bar = 0; bar < 5; bar++) {
            assertTrue(Double.isNaN(average.valueAt(bar)[0]),
                    "bar " + bar + " drew a value from a five-minute bar still forming");
        }
    }

    @Test
    @DisplayName("the value is the LAST CLOSED coarse bar, never the one forming")
    void neverTheBarStillForming() {
        // The whole point. At 09:10 the 09:10-09:14 bar has not happened; its
        // close is 15, and using it would mean the line knew at 09:10 what the
        // next five minutes would do. The right answer is 10 -- the 09:05 bar,
        // which closed exactly at 09:10.
        MovingAverage average = onFiveMinutes();

        assertEquals(5.0, average.valueAt(5)[0], 1e-9,
                "at 09:05 only the 09:00 bar has closed");
        assertEquals(5.0, average.valueAt(9)[0], 1e-9,
                "at 09:09 the 09:05 bar is still forming");
        assertEquals(10.0, average.valueAt(10)[0], 1e-9,
                "at 09:10 the line read a bar that had not finished");
        assertEquals(10.0, average.valueAt(14)[0], 1e-9,
                "the last bar cannot know its own five-minute close");
    }

    @Test
    @DisplayName("no value on screen comes from the future, at any bar")
    void nothingComesFromTheFuture() {
        // Said as a property rather than as three numbers: every value drawn has
        // to be one the market had already produced at that moment.
        MovingAverage average = onFiveMinutes();
        PriceSeries series = minutes();

        for (int bar = 0; bar < series.size(); bar++) {
            double value = average.valueAt(bar)[0];

            if (Double.isFinite(value)) {
                assertTrue(value <= series.closeAt(bar),
                        "bar " + bar + " drew " + value + ", which the market had not reached");
            }
        }
    }

    @Test
    @DisplayName("interpolation slopes between closed points and adds nothing new")
    void interpolationStaysBehind() {
        MovingAverage sloped = new MovingAverage(1);

        sloped.setOwnPeriod("5m");
        sloped.setInterpolated(true);
        sloped.calculate(minutes());

        PriceSeries series = minutes();

        // Smoother, and still never ahead of the market.
        for (int bar = 0; bar < series.size(); bar++) {
            double value = sloped.valueAt(bar)[0];

            if (Double.isFinite(value)) {
                assertTrue(value <= series.closeAt(bar),
                        "interpolation reached bar " + bar + " with " + value);
            }
        }
    }

    @Test
    @DisplayName("an unknown period falls back to the chart's own")
    void unknownPeriodFallsBack() {
        // A layout written by a later version can name a scale this one does not
        // build. Drawing on the chart's scale is a smaller wrong than drawing
        // nothing and leaving an indicator listed but invisible.
        MovingAverage average = new MovingAverage(3);

        average.setOwnPeriod("nonsense");
        average.calculate(minutes());

        assertEquals(2.0, average.valueAt(2)[0], 1e-9);
    }

    @Test
    @DisplayName("the chosen period is remembered")
    void theChoiceIsRemembered() {
        MovingAverage set = new MovingAverage(9);

        set.setOwnPeriod("15m");
        set.setInterpolated(false);

        MovingAverage read = new MovingAverage(9);

        read.applyAppearance(set.appearance());

        assertEquals("15m", read.ownPeriod());
        assertFalse(read.isInterpolated());
    }
}
