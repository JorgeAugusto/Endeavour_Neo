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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Measurement")
class MeasurementTest {

    @Test
    @DisplayName("candles are counted inclusively, matching what is on screen")
    void barsAreInclusive() {
        // Dragging across six candles must report six. Reporting the gap between
        // indices would say five and start an argument with the picture, which
        // the picture wins.
        Measurement m = Measurement.between(minuteSeries(20), 4, 100.0, 9, 110.0);

        assertEquals(6, m.bars(), "a drag from bar 4 to bar 9 covers six candles");
    }

    @Test
    @DisplayName("bars and elapsed time are separate answers, and a gap proves it")
    void barsAndTimeDisagreeAcrossAGap() {
        // Six bars inside a session cover ninety minutes. The same six across a
        // night cover eighteen hours. A ruler that reported only one would let
        // the reader infer the other and be wrong by the size of the break --
        // and any projection drawn from it wrong by the same amount.
        PriceSeries withGap = new PriceSeries() {

            @Override
            public int size() {
                return 6;
            }

            @Override
            public long timeAt(int index) {
                // Three bars, then a fifteen-hour break, then three more.
                long minutes = index < 3 ? index * 15L : 15L * 3 + 900L + (index - 3) * 15L;

                return minutes * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return 100.0;
            }

            @Override
            public double highAt(int index) {
                return 100.0;
            }

            @Override
            public double lowAt(int index) {
                return 100.0;
            }

            @Override
            public double closeAt(int index) {
                return 100.0;
            }
        };

        Measurement m = Measurement.between(withGap, 0, 100.0, 5, 100.0);

        assertEquals(6, m.bars(), "six candles were spanned");
        assertTrue(m.elapsed() / 60_000L > 900L,
                "the elapsed time must include the break, not just the bars");
    }

    @Test
    @DisplayName("dragging backwards measures the same distance, with the sign of the drag")
    void backwardsDragIsSymmetric() {
        PriceSeries series = minuteSeries(20);

        Measurement forward = Measurement.between(series, 2, 100.0, 8, 110.0);
        Measurement backward = Measurement.between(series, 8, 110.0, 2, 100.0);

        assertEquals(forward.bars(), backward.bars(), "the span cannot depend on direction");
        assertEquals(forward.elapsed(), backward.elapsed(), "nor can the elapsed time");
        assertEquals(-forward.difference(), backward.difference(), 1e-9,
                "the price difference must follow the drag, and only flip sign");
    }

    @Test
    @DisplayName("a start price of zero gives NaN rather than infinity")
    void zeroStartIsNotInfinite() {
        // Infinity formats as a symbol nobody expects in a price box, so the
        // percentage row is omitted instead.
        Measurement m = Measurement.between(minuteSeries(10), 0, 0.0, 5, 10.0);

        assertTrue(Double.isNaN(m.percent()), "a move from zero has no percentage");
    }

    @Test
    @DisplayName("measuring outside the series returns nothing instead of guessing")
    void outsideTheSeriesIsNull() {
        assertNull(Measurement.between(minuteSeries(10), -1, 100.0, 5, 100.0),
                "a negative bar must not produce a measurement");
        assertNull(Measurement.between(minuteSeries(10), 2, 100.0, 99, 100.0),
                "a bar past the end must not produce a measurement");
    }

    private static PriceSeries minuteSeries(int bars) {
        return new PriceSeries() {

            @Override
            public int size() {
                return bars;
            }

            @Override
            public long timeAt(int index) {
                return index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return 100.0;
            }

            @Override
            public double highAt(int index) {
                return 101.0;
            }

            @Override
            public double lowAt(int index) {
                return 99.0;
            }

            @Override
            public double closeAt(int index) {
                return 100.0;
            }
        };
    }
}
