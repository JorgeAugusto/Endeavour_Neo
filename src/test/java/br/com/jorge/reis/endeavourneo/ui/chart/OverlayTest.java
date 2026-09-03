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

import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Overlay")
class OverlayTest {

    @Test
    @DisplayName("there is no value before the average has seen its own period")
    void warmUpIsNaN() {
        // An exponential average produces a number from the very first bar, but
        // that number is mostly the seed. Plotting it draws a line converging
        // out of nowhere, which reads as signal and is not. NaN says "nothing
        // yet" and the canvas breaks the line instead of inventing one.
        MovingAverage ema = new MovingAverage(10);

        ema.calculate(flat(30, 100.0));

        for (int bar = 0; bar < 9; bar++) {
            assertTrue(Double.isNaN(ema.valueAt(bar)[0]),
                    "bar " + bar + " reported a value before the period was complete");
        }

        assertFalse(Double.isNaN(ema.valueAt(9)[0]),
                "the average should exist once it has seen ten bars");
    }

    @Test
    @DisplayName("on a flat series the average equals the price")
    void flatSeriesGivesTheSamePrice() {
        // The one case with an answer that can be checked by hand. If this is
        // wrong, the weighting is wrong, and every other value is too -- while
        // still looking plausible on screen.
        MovingAverage ema = new MovingAverage(5);

        ema.calculate(flat(50, 137.5));

        assertEquals(137.5, ema.valueAt(49)[0], 1e-9,
                "a constant price must average to itself");
    }

    @Test
    @DisplayName("one value per configured period, in the same order")
    void oneValuePerPeriod() {
        // The legend pairs values with colours by position, and one indicator is
        // now one line. This assertion used to ask for three, from the version
        // where a single indicator drew several averages -- which is exactly the
        // design that made them share one set of settings.
        MovingAverage ema = new MovingAverage(3);

        ema.calculate(flat(60, 100.0));

        assertEquals(1, ema.valueAt(59).length, "one indicator draws one line");
        assertEquals(1, ema.colours().size(), "colours do not match the lines");
        assertEquals(java.util.List.of(3), ema.parameters(),
                "the parameters shown in the legend are not the ones configured");
    }

    @Test
    @DisplayName("asking outside the series gives NaN instead of throwing")
    void outsideTheSeriesIsNaN() {
        // The legend asks for the hovered bar, and that is -1 when the mouse is
        // off the chart. Throwing there would break painting rather than the
        // overlay.
        MovingAverage ema = new MovingAverage(5);

        ema.calculate(flat(10, 100.0));

        assertTrue(Double.isNaN(ema.valueAt(-1)[0]), "a negative index should be NaN");
        assertTrue(Double.isNaN(ema.valueAt(999)[0]), "an index past the end should be NaN");
    }

    @Test
    @DisplayName("hiding an overlay leaves it calculated, ready to come back")
    void hidingKeepsTheValues() {
        MovingAverage ema = new MovingAverage(5);

        ema.calculate(flat(20, 100.0));

        double before = ema.valueAt(19)[0];

        ema.setVisible(false);

        assertEquals(before, ema.valueAt(19)[0], 1e-9,
                "hiding discarded the values, so showing again would need a recalculation");
    }

    /** A series at one constant price, where the right answer is known. */
    private static PriceSeries flat(int bars, double price) {
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
                return price;
            }

            @Override
            public double highAt(int index) {
                return price;
            }

            @Override
            public double lowAt(int index) {
                return price;
            }

            @Override
            public double closeAt(int index) {
                return price;
            }
        };
    }
}
