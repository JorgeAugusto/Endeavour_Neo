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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.awt.Color;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One average, and everything that can be set about it.
 */
@DisplayName("Moving average")
class MovingAverageTest {

    /** Bars closing at each price given, with highs two above and lows two below. */
    private static PriceSeries bars(double... prices) {
        return new PriceSeries() {

            @Override
            public int size() {
                return prices.length;
            }

            @Override
            public long timeAt(int index) {
                return index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return prices[index];
            }

            @Override
            public double highAt(int index) {
                return prices[index] + 2;
            }

            @Override
            public double lowAt(int index) {
                return prices[index] - 2;
            }

            @Override
            public double closeAt(int index) {
                return prices[index];
            }
        };
    }

    private static MovingAverage over(PriceSeries series, int period) {
        MovingAverage average = new MovingAverage(period);

        average.calculate(series);

        return average;
    }

    @Test
    @DisplayName("the arithmetic average is the mean of the window")
    void arithmetic() {
        MovingAverage average = over(bars(10, 20, 30, 40), 3);

        assertEquals(20.0, average.valueAt(2)[0], 1e-9);
        assertEquals(30.0, average.valueAt(3)[0], 1e-9);
    }

    @Test
    @DisplayName("nothing is drawn until the window is full")
    void noPartialAverage() {
        // A partial average is a different indicator wearing this one's name,
        // and it is wrong exactly where the eye lands first: the left edge.
        MovingAverage average = over(bars(10, 20, 30, 40), 3);

        assertTrue(Double.isNaN(average.valueAt(0)[0]));
        assertTrue(Double.isNaN(average.valueAt(1)[0]));
    }

    @Test
    @DisplayName("the exponential one is seeded with the first full window")
    void exponentialSeed() {
        // Starting from the first price instead leaves a visible hook at the
        // left edge, and every platform seeds it this way.
        MovingAverage average = new MovingAverage(3);

        average.setKind(MovingAverage.Kind.EXPONENTIAL);
        average.calculate(bars(10, 20, 30, 40));

        assertEquals(20.0, average.valueAt(2)[0], 1e-9, "the seed is the arithmetic mean");
        assertEquals(30.0, average.valueAt(3)[0], 1e-9);
    }

    @Test
    @DisplayName("the weighted one leans on the recent bars")
    void weightedLeansRecent() {
        MovingAverage weighted = new MovingAverage(3);

        weighted.setKind(MovingAverage.Kind.WEIGHTED);
        weighted.calculate(bars(10, 20, 60));

        // (60*3 + 20*2 + 10*1) / 6 = 38,33 -- above the plain mean of 30.
        assertTrue(weighted.valueAt(2)[0] > over(bars(10, 20, 60), 3).valueAt(2)[0],
                "a weighted average that does not lean recent is an arithmetic one");
    }

    @Test
    @DisplayName("the shift moves the line sideways")
    void shiftMovesTheLine() {
        MovingAverage average = new MovingAverage(3);

        average.setShift(1);
        average.calculate(bars(10, 20, 30, 40));

        // What was at bar 2 is now read at bar 3.
        assertEquals(20.0, average.valueAt(3)[0], 1e-9);
    }

    @Test
    @DisplayName("the line is never pushed LEFT, which would read bars to the right")
    void theShiftNeverGoesBackwards() {
        // Only the forward shift was ever tested, and the spinner beside it went
        // down to -500. valueAt reads values[bar - shift], so a shift of -2 puts
        // on bar i an average worked out over bars up to i + 2: the chart reading
        // the future, arriving through a control whose lower bound looks like a
        // symmetric range somebody typed rather than a decision anybody made.
        MovingAverage average = new MovingAverage(1);

        average.setShift(-2);

        assertEquals(0, average.shift(), "a negative shift was accepted");

        average.calculate(bars(10, 20, 30, 40));

        // With period 1 the average IS the close, so anything leaking in from
        // the right shows up as a wrong number rather than as a suspicion.
        for (int bar = 0; bar < 4; bar++) {
            assertEquals((bar + 1) * 10.0, average.valueAt(bar)[0], 1e-9,
                    "bar " + bar + " is showing a price from further to the right");
        }
    }

    @Test
    @DisplayName("the source decides which price is averaged")
    void theSourceMatters() {
        PriceSeries series = bars(10, 20, 30);
        MovingAverage high = new MovingAverage(3);

        high.setSource(MovingAverage.Source.HIGH);
        high.calculate(series);

        // Highs are two points above closes, so the average is too.
        assertEquals(over(series, 3).valueAt(2)[0] + 2, high.valueAt(2)[0], 1e-9);
    }

    @Test
    @DisplayName("the automatic colour follows the period, not the order added")
    void automaticColourIsStable() {
        // Adding the same three averages in a different order has to give the
        // same chart.
        assertEquals(new MovingAverage(9).colours(), new MovingAverage(9).colours());
        assertNotEquals(new MovingAverage(9).colours(), new MovingAverage(10).colours());
    }

    @Test
    @DisplayName("appearance survives being written down and read back")
    void appearanceRoundTrip() {
        // What a saved layout stores. Coming back in the right place wearing the
        // wrong colour is a layout that only half worked.
        MovingAverage set = new MovingAverage(9);

        set.setKind(MovingAverage.Kind.EXPONENTIAL);
        set.setSource(MovingAverage.Source.TYPICAL);
        set.setLine(MovingAverage.Line.DASHED);
        set.setThickness(3);
        set.setColour(new Color(0x123456));

        MovingAverage read = new MovingAverage(9);

        read.applyAppearance(set.appearance());

        assertEquals(MovingAverage.Kind.EXPONENTIAL, read.kind());
        assertEquals(MovingAverage.Source.TYPICAL, read.source());
        assertEquals(MovingAverage.Line.DASHED, read.line());
        assertEquals(3, read.thickness());
        assertEquals(new Color(0x123456), read.chosenColour());
    }

    @Test
    @DisplayName("an appearance from a later version loses only what it must")
    void unknownAppearanceIsPartial() {
        // A stored line naming a kind this version does not know must not cost
        // the colour as well -- that would be a second failure caused by the
        // first.
        MovingAverage read = new MovingAverage(9);

        read.applyAppearance("SOMETHING_NEW;CLOSE;DOTTED;2;123456");

        assertEquals(MovingAverage.Kind.ARITHMETIC, read.kind(), "the unknown kind falls back");
        assertEquals(MovingAverage.Line.DOTTED, read.line(), "and the rest still arrives");
        assertEquals(2, read.thickness());
        assertEquals(new Color(0x123456), read.chosenColour());
    }

    @Test
    @DisplayName("automatic colour is remembered as automatic, not as a colour")
    void automaticStaysAutomatic() {
        MovingAverage read = new MovingAverage(9);

        read.applyAppearance(new MovingAverage(9).appearance());

        assertNull(read.chosenColour(),
                "an automatic colour frozen into a fixed one stops following the palette");
    }

    @Test
    @DisplayName("the stroke is the indicator's own, thickness and dash included")
    void theStrokeIsItsOwn() {
        MovingAverage thick = new MovingAverage(9);

        thick.setThickness(5);
        thick.setLine(MovingAverage.Line.DASH_DOT);

        assertTrue(thick.stroke() instanceof java.awt.BasicStroke);
        assertEquals(5f, ((java.awt.BasicStroke) thick.stroke()).getLineWidth(), 1e-6);
        assertNotEquals(null, ((java.awt.BasicStroke) thick.stroke()).getDashArray(),
                "a dashed line with no dash array is a solid line");
    }
}
