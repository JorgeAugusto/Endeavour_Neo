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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.awt.Color;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bands, checked against numbers worked out by hand.
 */
@DisplayName("Bollinger bands")
class BollingerBandsTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** A series whose closes are the numbers given, one minute apart. */
    private static PriceSeries closes(double... values) {
        return new PriceSeries() {

            @Override
            public int size() {
                return values.length;
            }

            @Override
            public long timeAt(int index) {
                return LocalDateTime.of(2026, 9, 2, 9, 0).plusMinutes(index)
                        .atZone(ZONE).toInstant().toEpochMilli();
            }

            @Override
            public double openAt(int index) {
                return values[index];
            }

            @Override
            public double highAt(int index) {
                return values[index];
            }

            @Override
            public double lowAt(int index) {
                return values[index];
            }

            @Override
            public double closeAt(int index) {
                return values[index];
            }
        };
    }

    @Test
    @DisplayName("the bands sit a whole number of deviations from the middle")
    void theBandsAreWhereTheArithmeticSays() {
        // Four closes: 2, 4, 4, 6. Mean 4. Deviations -2, 0, 0, 2, so the
        // squares are 4, 0, 0, 4 and the POPULATION deviation is sqrt(8/4) =
        // sqrt(2). Chosen so the answer is a number that can be checked by
        // hand, and so that the sample deviation -- sqrt(8/3) = 1,633 -- is
        // clearly different from it.
        BollingerBands bands = new BollingerBands(4);

        bands.calculate(closes(2, 4, 4, 6));

        double[] row = bands.valueAt(3);
        double expected = Math.sqrt(2.0);

        assertEquals(4.0, row[1], 1e-9, "the middle is not the mean of the window");
        assertEquals(4.0 + 2 * expected, row[0], 1e-9);
        assertEquals(4.0 - 2 * expected, row[2], 1e-9);

        // And not the sample deviation, which is the other convention.
        assertFalse(Math.abs(row[0] - (4.0 + 2 * Math.sqrt(8.0 / 3.0))) < 1e-9,
                "the bands were built from the sample deviation, not the population one");
    }

    @Test
    @DisplayName("the two deviations are separate settings")
    void eachSideHasItsOwnWidth() {
        BollingerBands bands = new BollingerBands(4);

        bands.setUpperDeviations(2);
        bands.setLowerDeviations(1);
        bands.calculate(closes(2, 4, 4, 6));

        double[] row = bands.valueAt(3);
        double expected = Math.sqrt(2.0);

        assertEquals(4.0 + 2 * expected, row[0], 1e-9);
        assertEquals(4.0 - 1 * expected, row[2], 1e-9,
                "the lower band ignored its own setting and used the upper one");
    }

    @Test
    @DisplayName("nothing is drawn until the window is full")
    void noPartialBands() {
        // A partial window is at its widest on the left edge, which is exactly
        // where it would be read as a real burst of volatility.
        BollingerBands bands = new BollingerBands(4);

        bands.calculate(closes(2, 4, 4, 6));

        for (int bar = 0; bar < 3; bar++) {
            double[] row = bands.valueAt(bar);

            assertTrue(Double.isNaN(row[0]) && Double.isNaN(row[2]),
                    "bar " + bar + " drew a band over a window that was not full yet");
        }
    }

    @Test
    @DisplayName("the middle band is exactly the moving average of the same settings")
    void theMiddleIsTheAverage() {
        // Both are on the chart, and a middle that is nearly the average is
        // worse than one that is obviously not: the reader sees two lines where
        // there should be one and cannot tell which is wrong.
        PriceSeries series = closes(10, 12, 11, 15, 14, 13, 17, 16);

        BollingerBands bands = new BollingerBands(4);
        MovingAverage average = new MovingAverage(4);

        bands.setKind(MovingAverage.Kind.EXPONENTIAL);
        average.setKind(MovingAverage.Kind.EXPONENTIAL);

        bands.calculate(series);
        average.calculate(series);

        for (int bar = 0; bar < series.size(); bar++) {
            double mine = bands.valueAt(bar)[1];
            double theirs = average.valueAt(bar)[0];

            if (Double.isFinite(theirs) && bar >= 3) {
                assertEquals(theirs, mine, 1e-9, "the middle drifted from the average at " + bar);
            }
        }
    }

    @Test
    @DisplayName("hiding the middle leaves the bands alone")
    void hidingTheMiddleKeepsTheBands() {
        BollingerBands bands = new BollingerBands(4);

        bands.calculate(closes(2, 4, 4, 6));

        double[] shown = bands.valueAt(3);

        bands.setMiddleShown(false);

        double[] hidden = bands.valueAt(3);

        assertTrue(Double.isNaN(hidden[1]), "the middle is still drawn");
        assertEquals(shown[0], hidden[0], 1e-9, "hiding the middle moved the upper band");
        assertEquals(shown[2], hidden[2], 1e-9, "hiding the middle moved the lower band");
    }

    @Test
    @DisplayName("on its own scale, no band knows anything the market had not said")
    void ownScaleDoesNotReadTheFuture() {
        // The trap this project already paid for once: the obvious mapping
        // takes the coarse bar CONTAINING each bar, and that bar is partly the
        // future. Said here as a property over rising prices -- an upper band
        // built from a five-minute bar that has not closed would sit above
        // anything the market had reached.
        double[] rising = new double[30];

        for (int i = 0; i < rising.length; i++) {
            rising[i] = 100 + i;
        }

        PriceSeries series = closes(rising);
        BollingerBands bands = new BollingerBands(3);

        bands.setOwnPeriod("5m");
br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.setInterpolateOwnScale(false);
        bands.calculate(series);

        for (int bar = 0; bar < series.size(); bar++) {
            double middle = bands.valueAt(bar)[1];

            if (Double.isFinite(middle)) {
                assertTrue(middle <= series.closeAt(bar),
                        "bar " + bar + " drew a middle of " + middle
                                + ", which the market had not reached");
            }
        }

        // TEETH, and the ceiling above has none on its own. Over prices that
        // only rise, the honest middle and a middle that read the coarse bar
        // still forming are BOTH below the current close -- the assertion is
        // true either way, and was true for as long as it stood alone.
        //
        // What separates them is movement. Between two closes inside one
        // five-minute bar nothing has closed, so nothing new can have arrived
        // and the value must not move. A mapping that took the CONTAINING bar
        // moves here, because the containing bar grows with every minute.
        for (int bar = 5; bar < series.size() - 1; bar++) {
            if (bar % 5 == 4) {
                // A coarse bar closes between these two; moving is correct here.
                continue;
            }

            assertEquals(bands.valueAt(bar)[1], bands.valueAt(bar + 1)[1], 1e-9,
                    "the middle moved between bar " + bar + " and " + (bar + 1)
                            + ", inside a five-minute bar that had not closed");
        }

        // And something was drawn at all: a fixture that produced NaN everywhere
        // would satisfy both loops by never entering them.
        assertTrue(Double.isFinite(bands.valueAt(series.size() - 1)[1]),
                "no band was drawn at all, so neither loop above asserted anything");
    }

    @Test
    @DisplayName("every setting survives being written down and read back")
    void settingsAreRemembered() {
        BollingerBands set = new BollingerBands(20);

        set.setUpperDeviations(2.5);
        set.setLowerDeviations(1.5);
        set.setMiddleShown(false);
        set.setKind(MovingAverage.Kind.WEIGHTED);
        set.setSource(MovingAverage.Source.TYPICAL);
        set.setLine(MovingAverage.Line.DASHED);
        set.setThickness(3);
        set.setColour(new Color(0x123456));
        set.setMiddleLine(MovingAverage.Line.DOTTED);
        set.setMiddleThickness(2);
        set.setMiddleColour(new Color(0x654321));
        set.setFilled(true);
        set.setFillColour(new Color(0xABCDEF));
        set.setOpacity(35);
        set.setOwnPeriod("15m");


        BollingerBands read = new BollingerBands(20);

        read.applyAppearance(set.appearance());

        assertEquals(2.5, read.upperDeviations(), 1e-9);
        assertEquals(1.5, read.lowerDeviations(), 1e-9);
        assertFalse(read.isMiddleShown());
        assertEquals(MovingAverage.Kind.WEIGHTED, read.kind());
        assertEquals(MovingAverage.Source.TYPICAL, read.source());
        assertEquals(MovingAverage.Line.DASHED, read.line());
        assertEquals(3, read.thickness());
        assertEquals(new Color(0x123456), read.chosenColour());
        assertEquals(MovingAverage.Line.DOTTED, read.middleLine());
        assertEquals(2, read.middleThickness());
        assertEquals(new Color(0x654321), read.chosenMiddleColour());
        assertTrue(read.isFilled());
        assertEquals(new Color(0xABCDEF), read.chosenFillColour());
        assertEquals(35, read.opacity());
        assertEquals("15m", read.ownPeriod());

    }

    @Test
    @DisplayName("a layout from an older version keeps the settings it never had")
    void anOlderLayoutIsNotDestroyed() {
        // The reason the format is by name and not by position: a file written
        // before a setting existed must leave that setting at its default, not
        // shift every other one along by a field.
        BollingerBands bands = new BollingerBands(20);

        bands.setOpacity(35);
        bands.applyAppearance("kind=ARITHMETIC;upper=3.0");

        assertEquals(3.0, bands.upperDeviations(), 1e-9);
        assertEquals(35, bands.opacity(), "a setting absent from the file was reset");
        assertEquals(2.0, bands.lowerDeviations(), 1e-9);
    }

    @Test
    @DisplayName("a deviation that would turn the bands inside out is refused")
    void anImpossibleDeviationIsClamped() {
        // Not reachable from the dialog, and entirely reachable from a
        // hand-edited layout: negative would put the upper band under the lower
        // one, and NaN would erase both with no message at all.
        BollingerBands bands = new BollingerBands(4);

        bands.setUpperDeviations(-1);
        bands.setLowerDeviations(Double.NaN);
        bands.calculate(closes(2, 4, 4, 6));

        double[] row = bands.valueAt(3);

        // EQUAL, not merely not-below. clampDeviation answers zero for a
        // negative, so the upper band sits exactly on the middle -- and ">=" is
        // satisfied by that as well as by any other clamp, so it did not say
        // which one happened.
        assertEquals(row[1], row[0], 1e-9,
                "a negative deviation was clamped to something other than nought");
        assertTrue(Double.isFinite(row[2]), "the lower band became NaN and vanished");

        // And the ceiling, which nothing tested at all. Ten standard deviations
        // is already a band nothing ever touches; a thousand is a chart with two
        // lines off the top and bottom of the pane and no way back but the file.
        bands.setUpperDeviations(1_000);

        assertEquals(10.0, bands.upperDeviations(), 1e-9,
                "the deviation has no ceiling");
    }
    @Test
    @DisplayName("the band centres on the average of the same price, whatever the source is")
    void bothSidesReadOneRule() {
        // The rule "which price of the bar is this Source" used to be written
        // twice, letter for letter: once in MovingAverage and once here, both
        // reading the same source() and both ending in `default ->`. A seventh
        // source added tomorrow would have made the average use it and the band
        // fall through to the close -- a band centred on a line that is not the
        // one drawn, which is exactly what the javadoc of basis says this class
        // exists to prevent.
        //
        // Asserted over EVERY constant, because the defect is about the one
        // nobody wrote a case for.
        PriceSeries series = ohlc();

        for (MovingAverage.Source source : MovingAverage.Source.values()) {
            BollingerBands bands = new BollingerBands(3);

            bands.setSource(source);
            bands.calculate(series);

            MovingAverage average = new MovingAverage(3);

            average.setSource(source);
            average.calculate(series);

            for (int bar = 0; bar < series.size(); bar++) {
                double[] row = bands.valueAt(bar);
                double line = average.valueAt(bar)[0];

                if (Double.isNaN(line)) {
                    continue;
                }

                assertEquals(line, row[1], 1e-9,
                        source + ", bar " + bar + ": the band is centred on a price the "
                                + "average is not drawing");

                // The WIDTH, which is where the copy lived: the centre comes
                // from a MovingAverage of its own, so it never went through the
                // duplicated switch. The deviation did, and a deviation
                // measured on one price around a centre made of another is a
                // band that belongs to neither.
                double sum = 0.0;

                for (int back = bar - 2; back <= bar; back++) {
                    double difference = source.of(series, back) - row[1];

                    sum += difference * difference;
                }

                assertEquals(2 * Math.sqrt(sum / 3), row[0] - row[1], 1e-9,
                        source + ", bar " + bar + ": the band is as wide as the spread of "
                                + "some other price around this centre");
            }
        }
    }

    /**
     * A series whose four prices all differ, so a source that is read wrongly shows.
     *
     * <p>{@code closes(...)} above answers the same number to all four, which
     * would make every source agree and this test pass on any rule at all.</p>
     */
    private static PriceSeries ohlc() {
        return new PriceSeries() {

            @Override
            public int size() {
                return 12;
            }

            @Override
            public long timeAt(int index) {
                return LocalDateTime.of(2026, 9, 2, 9, 0).plusMinutes(index)
                        .atZone(ZONE).toInstant().toEpochMilli();
            }

            @Override
            public double openAt(int index) {
                return 100 + index;
            }

            @Override
            public double highAt(int index) {
                return 107 + index * 2;
            }

            @Override
            public double lowAt(int index) {
                return 93 + index / 2.0;
            }

            @Override
            public double closeAt(int index) {
                return 101 + index * 3;
            }
        };
    }
    @Test
    @DisplayName("a linha do meio e desenhada com o estilo e a espessura dela")
    void themiddleIsDrawnWithItsOwnStyle() {
        // The class carries middleLine and middleThickness, the dialog offers
        // both, and neither reached the screen: only stroke() was ever asked,
        // and that one answers for the BANDS. Two settings a reader could change
        // with nothing changing.
        BollingerBands bands = new BollingerBands(20);

        bands.setLine(MovingAverage.Line.SOLID);
        bands.setThickness(1);
        bands.setMiddleLine(MovingAverage.Line.DASHED);
        bands.setMiddleThickness(3);

        java.util.List<java.awt.Stroke> strokes = bands.strokes();

        assertEquals(3, strokes.size(),
                "one stroke per line, or the list pairs the middle's with the lower band");
        assertNotEquals(strokes.get(0), strokes.get(1),
                "the middle is drawn with the bands' stroke, so its own settings do nothing");
        assertEquals(strokes.get(0), strokes.get(2),
                "the two bands stopped sharing a stroke");
    }

    /**
     * Devolve o ajuste ao padrão.
     *
     * <p>É um ajuste do gráfico, e portanto estático: um teste que o liga e não
     * o desliga muda o resultado do teste seguinte, e de uma classe que nem
     * sabe que ele existe. A suíte inteira roda numa JVM só.</p>
     */
    @org.junit.jupiter.api.AfterEach
    void putTheSettingBack() {
        br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.setInterpolateOwnScale(false);
    }
}
