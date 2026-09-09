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
package br.com.jorge.reis.endeavourneo.ui.chart.study.rsi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The numbers the RSI produces, against values worked out by hand.
 *
 * <p>An indicator whose arithmetic is only checked against itself is an
 * indicator that will agree with its own mistake for ever. The first case here
 * is computed on paper and written into the test; the rest are properties that
 * have to hold whatever the closes are.</p>
 */
@DisplayName("IFR (RSI)")
class RelativeStrengthTest {

    private static final double EXACT = 1e-9;

    @org.junit.jupiter.api.Test
    @DisplayName("on its own scale it reads the last CLOSED coarse bar, never the one forming")
    void onItsOwnScaleItDoesNotReadTheFuture() {
        // The RSI's own-scale CALCULATION had no test at all. setOwnPeriod
        // appeared once in this file, inside a round trip of the appearance
        // text -- which proves the setting survives being written down, and
        // nothing whatever about what the indicator then draws.
        //
        // The moving average has this test, in OwnPeriodTest, and it is the trap
        // this project has already paid for once: the obvious mapping takes the
        // coarse bar CONTAINING each bar, and that bar is partly the future.
        //
        // AND THIS TEST USED TO PASS WITH THE RSI DRAWING NOTHING. It ran on a
        // rising line of fifteen closes: the first five bars were REQUIRED to be
        // NaN, and the other two assertions compared two bars against each
        // other -- and assertEquals(double, double, delta) starts by asking
        // Double.valueOf(a).equals(Double.valueOf(b)), which is TRUE for NaN
        // against NaN. Every assertion was satisfied by an all-NaN result. The
        // product could return early on the own-scale path and the RSI would
        // simply vanish from the screen the moment a reader chose a scale of its
        // own, in silence, with this the only test of that path and green.
        //
        // It is fixed the way its sibling already does it -- BollingerBandsTest
        // asserts a finite band before it compares anything -- and then further:
        // one value is worked out by hand, so the test knows what the number IS
        // and not only that two of them agree.
        //
        // The fixture is deliberate. Closes are held CONSTANT inside each
        // five-minute window, so the coarse closes are exactly the numbers named
        // here, and nothing depends on how the minutes inside a window run.
        double[] window = {100, 110, 120, 100, 101, 102, 103, 104};
        double[] closes = new double[window.length * 5];

        for (int i = 0; i < closes.length; i++) {
            closes[i] = window[i / 5];
        }

        PriceSeries minutes = bars(closes);

        RelativeStrength rsi = new RelativeStrength(2);

        rsi.setOwnPeriod("5m");
br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.setInterpolateOwnScale(false);
        rsi.calculate(minutes);

        // Nothing before the first five-minute bar has closed.
        for (int bar = 0; bar < 5; bar++) {
            assertTrue(Double.isNaN(at(rsi, bar)),
                    "bar " + bar + " drew a value from a five-minute bar still forming");
        }

        // THE VALUE, worked out on paper. Bars 15 to 19 read the coarse bar that
        // closed at minute 14, the third one: 100, 110, 120, two rises and no
        // fall. Wilder's reading with nothing falling is a hundred by
        // definition, and the seed window is those two rises.
        //
        // This is also, by itself, the proof that it does not read the future:
        // the coarse bar CONTAINING minute 15 closes at 100 after a fall of
        // twenty, and its reading is 33,3 -- so a mapping that took the
        // containing bar could not land on a hundred here.
        assertEquals(100.0, at(rsi, 15), EXACT,
                "the RSI drew nothing at all on its own scale, or drew the wrong number: "
                        + "three rising five-minute closes read a hundred");

        // Constant across the whole of one coarse bar: minute 19 knows nothing
        // that minute 15 did not, because nothing closed in between. A mapping
        // that took the containing bar would move here, because that bar grows.
        assertEquals(at(rsi, 15), at(rsi, 19), EXACT,
                "the value moved inside a coarse bar that had not closed");

        // And it DOES move when one closes. Without this the two assertions
        // above are also satisfied by an indicator frozen at its first value.
        assertNotEquals(at(rsi, 19), at(rsi, 20),
                "the value did not change when a five-minute bar closed with a fall of "
                        + "twenty in it: the indicator is not reading the coarse bars at all");

        assertTrue(Double.isFinite(at(rsi, closes.length - 1)),
                "the last bar drew nothing, so the RSI disappears on its own scale");
    }

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

    private static RelativeStrength over(PriceSeries series, int period,
                                         RelativeStrength.Smoothing how) {
        RelativeStrength rsi = new RelativeStrength(period);

        rsi.setSmoothing(how);
        rsi.calculate(series);

        return rsi;
    }

    private static double at(RelativeStrength rsi, int bar) {
        return rsi.valueAt(bar)[0];
    }

    // --------------------------------------------------------- worked by hand

    @Test
    @DisplayName("o primeiro valor bate com a conta feita a mao")
    void theFirstValueByHand() {
        // Closes 10, 11, 10.5, 12 with a period of three.
        //   changes: +1, -0.5, +1.5
        //   average rise = (1 + 0 + 1.5) / 3 = 0.833...
        //   average fall = (0 + 0.5 + 0) / 3 = 0.1666...
        //   ratio 5, so 100 - 100/6 = 83.333...
        RelativeStrength rsi = over(bars(10, 11, 10.5, 12), 3,
                RelativeStrength.Smoothing.CLASSIC);

        assertEquals(100.0 - 100.0 / 6.0, at(rsi, 3), EXACT);
    }

    @Test
    @DisplayName("o segundo valor separa classico de simples")
    void theSecondValueTellsThemApart() {
        // One more close, 11, so the fourth change is -1.
        //
        //   Classic: rise = (0.8333 * 2 + 0) / 3 = 0.5555...
        //            fall = (0.1666 * 2 + 1) / 3 = 0.4444...
        //            ratio 1.25, so 100 - 100/2.25 = 55.555...
        //
        //   Simple:  rise = (0 + 1.5 + 0) / 3 = 0.5
        //            fall = (0.5 + 0 + 1) / 3 = 0.5
        //            ratio 1, so 50 exactly.
        PriceSeries series = bars(10, 11, 10.5, 12, 11);

        assertEquals(100.0 - 100.0 / 2.25,
                at(over(series, 3, RelativeStrength.Smoothing.CLASSIC), 4), EXACT);
        assertEquals(50.0,
                at(over(series, 3, RelativeStrength.Smoothing.SIMPLE), 4), EXACT);
    }

    // ---------------------------------------------------------- the two ends

    @Test
    @DisplayName("so subidas dao cem, so quedas dao zero")
    void theEnds() {
        assertEquals(100.0, at(over(bars(1, 2, 3, 4, 5, 6), 3,
                RelativeStrength.Smoothing.CLASSIC), 5), EXACT,
                "nothing fell, and a hundred is the definition working");
        assertEquals(0.0, at(over(bars(6, 5, 4, 3, 2, 1), 3,
                RelativeStrength.Smoothing.CLASSIC), 5), EXACT);
    }

    @Test
    @DisplayName("uma janela parada carrega o valor anterior")
    void aWindowThatDidNotMove() {
        // Nothing moved at all: nought would say "everything fell" and a
        // hundred "everything rose", and neither happened.
        RelativeStrength rsi = over(bars(5, 5, 5, 5, 5, 5), 3,
                RelativeStrength.Smoothing.CLASSIC);

        assertEquals(50.0, at(rsi, 3), EXACT, "with no previous reading, fifty");
        assertEquals(50.0, at(rsi, 5), EXACT);
    }

    @Test
    @DisplayName("subidas e quedas iguais dao cinquenta")
    void balanced() {
        // An EVEN window over an alternating series. With three bars the
        // window always holds two of one and one of the other, and the answer
        // is a third or two thirds -- correct, and not what this is asking.
        assertEquals(50.0, at(over(bars(10, 11, 10, 11, 10, 11, 10), 4,
                RelativeStrength.Smoothing.SIMPLE), 6), EXACT);
    }

    // ----------------------------------------------------------- the shapes

    @Test
    @DisplayName("as barras antes da primeira janela sao desconhecidas")
    void beforeTheFirstWindow() {
        RelativeStrength rsi = over(bars(10, 11, 10.5, 12, 11), 3,
                RelativeStrength.Smoothing.CLASSIC);

        for (int bar = 0; bar < 3; bar++) {
            assertTrue(Double.isNaN(at(rsi, bar)),
                    "bar " + bar + " was given a value before there was a window");
        }

        assertFalse(Double.isNaN(at(rsi, 3)), "the first full window has no value");
    }

    @Test
    @DisplayName("serie curta demais nao inventa valores")
    void tooShort() {
        RelativeStrength rsi = over(bars(10, 11, 12), 9,
                RelativeStrength.Smoothing.CLASSIC);

        for (int bar = 0; bar < 3; bar++) {
            assertTrue(Double.isNaN(at(rsi, bar)));
        }

        assertTrue(Double.isNaN(at(rsi, 99)), "a bar that does not exist got a number");
    }

    @Test
    @DisplayName("o simples se mexe quando a barra sai da janela; o classico nao")
    void whatLeavesTheWindow() {
        // One big rise, then a slow steady drift down. The falls have to be
        // there: with a rise and nothing else, both averages reach nought
        // together and the flat-window rule answers instead of the window.
        //
        //   closes  10  20  19  18  17  16  15  14
        //   changes    +10  -1  -1  -1  -1  -1  -1
        //
        // At bar four the +10 has left a three-bar window, so for the simple
        // average there is nothing rising left at all: nought. The classic one
        // still carries a fraction of it and is barely down from where it was.
        PriceSeries series = bars(10, 20, 19, 18, 17, 16, 15, 14);

        RelativeStrength simple = over(series, 3, RelativeStrength.Smoothing.SIMPLE);
        RelativeStrength classic = over(series, 3, RelativeStrength.Smoothing.CLASSIC);

        assertEquals(0.0, at(simple, 4), EXACT,
                "the big rise left the window and the simple average did not notice");
        assertEquals(100.0 - 700.0 / 27.0, at(classic, 4), EXACT,
                "the classic average has no window, so nothing ever leaves it");
    }

    // ---------------------------------------------------------- what it says

    @Test
    @DisplayName("ele se declara de painel, e de zero a cem")
    void whatItDeclares() {
        RelativeStrength rsi = new RelativeStrength(9);

        assertFalse(rsi.fitsOnPrice());
        assertEquals(0.0, rsi.bounds()[0]);
        assertEquals(100.0, rsi.bounds()[1]);
        assertEquals(List.of(9), rsi.parameters());
        assertEquals(1, rsi.colours().size(), "one line, as the reference product draws it");
    }

    @Test
    @DisplayName("a aparencia da a volta inteira")
    void theAppearanceRoundTrip() {
        RelativeStrength written = new RelativeStrength(21);

        written.setSmoothing(RelativeStrength.Smoothing.SIMPLE);
        written.setColour(new java.awt.Color(0x123456));
        written.setWidth(2.5f);

        written.setOwnPeriod("5m");

        RelativeStrength read = new RelativeStrength(21);

        read.applyAppearance(written.appearance());

        assertEquals(RelativeStrength.Smoothing.SIMPLE, read.smoothing());
        assertEquals(new java.awt.Color(0x123456), read.colour());
        assertEquals(2.5f, read.width());

        assertEquals("5m", read.ownPeriod(), "the scale is what tells two of them apart");
    }

    @Test
    @DisplayName("sem escala propria, o rotulo nao inventa uma")
    void noScaleOfItsOwn() {
        RelativeStrength read = new RelativeStrength(9);

        read.applyAppearance(new RelativeStrength(9).appearance());

        assertEquals(null, read.ownPeriod(),
                "it follows the chart, and saying so on every row is how a legend stops being read");
    }

    @Test
    @DisplayName("o tipo muda o desenho, nao so o nome")
    void theTypeMatters() {
        PriceSeries series = bars(10, 12, 11, 14, 13, 16, 12, 18, 15, 20, 14, 22);

        assertNotEquals(at(over(series, 5, RelativeStrength.Smoothing.CLASSIC), 11),
                at(over(series, 5, RelativeStrength.Smoothing.SIMPLE), 11),
                "the two kinds produced the same line, so one of them is not implemented");
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
