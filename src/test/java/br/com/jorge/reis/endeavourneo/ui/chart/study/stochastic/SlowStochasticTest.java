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
package br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where the close sits inside the range of the last few bars.
 */
@DisplayName("Estocastico lento")
class SlowStochasticTest {

    /** Bars of {high, low, close}, one a minute. */
    private static PriceSeries bars(double[]... rows) {
        return new PriceSeries() {

            @Override
            public int size() {
                return rows.length;
            }

            @Override
            public long timeAt(int index) {
                return 1_756_000_000_000L + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return rows[index][2];
            }

            @Override
            public double highAt(int index) {
                return rows[index][0];
            }

            @Override
            public double lowAt(int index) {
                return rows[index][1];
            }

            @Override
            public double closeAt(int index) {
                return rows[index][2];
            }
        };
    }

    /** A rising staircase: every bar higher than the last, closing at its own high. */
    private static PriceSeries climbing(int count) {
        double[][] rows = new double[count][];

        for (int i = 0; i < count; i++) {
            rows[i] = new double[]{100 + i, 99 + i, 100 + i};
        }

        return bars(rows);
    }

    @Test
    @DisplayName("o padrao e 8 e 3")
    void theDefaultIsEightAndThree() {
        SlowStochastic study = new SlowStochastic();

        assertEquals(8, study.period());
        assertEquals(3, study.average());
        assertTrue(study.showsAverage(), "a media vem ligada");
        assertTrue(study.showsLevels(), "as linhas de compra e venda vem ligadas");
        assertEquals(20.0, study.buyLevel());
        assertEquals(80.0, study.sellLevel());
    }

    @Test
    @DisplayName("fechando na maxima da janela, o indicador vai a cem")
    void closingAtTheTopIsAHundred() {
        SlowStochastic study = new SlowStochastic(4, 2);

        study.calculate(climbing(20));

        // Every close is the highest high seen, so the raw ratio is a hundred
        // from the moment the window fills -- and an average of a hundred is a
        // hundred.
        assertEquals(100.0, study.valueAt(19)[0], 1e-9);
        assertEquals(100.0, study.valueAt(19)[1], 1e-9);
    }

    @Test
    @DisplayName("a aquecida fica NaN, nunca zero")
    void theWarmUpIsAbsent() {
        SlowStochastic study = new SlowStochastic(8, 3);

        study.calculate(climbing(30));

        // Eight bars for the ratio, then three for the first average, then
        // three more for the second: the second line cannot exist before bar
        // eleven, and a zero there would drag it along the floor.
        assertTrue(Double.isNaN(study.valueAt(0)[0]), "bar 0 claimed a value");
        assertTrue(Double.isNaN(study.valueAt(6)[0]), "bar 6 claimed a value");
        assertTrue(Double.isNaN(study.valueAt(9)[1]), "the average claimed a value too early");
        assertFalse(Double.isNaN(study.valueAt(29)[1]), "the average never arrived");
    }

    @Test
    @DisplayName("uma janela sem movimento carrega o valor anterior")
    void aFlatWindowCarriesTheLastValue() {
        // Ten bars climbing, then ten where price does not move at all. The
        // ratio would divide by zero; zero or a hundred would both be a claim
        // about a range that does not exist.
        double[][] rows = new double[20][];

        for (int i = 0; i < 10; i++) {
            rows[i] = new double[]{100 + i, 99 + i, 100 + i};
        }

        for (int i = 10; i < 20; i++) {
            rows[i] = new double[]{109, 109, 109};
        }

        SlowStochastic study = new SlowStochastic(4, 2);

        study.calculate(bars(rows));

        double last = study.valueAt(19)[0];

        assertFalse(Double.isNaN(last), "a flat stretch wiped the indicator out");
        assertEquals(100.0, last, 1e-9,
                "the value before the flat stretch was a hundred and should have been kept");
    }

    @Test
    @DisplayName("nunca sai de zero a cem")
    void itNeverLeavesItsOwnScale() {
        double[][] rows = new double[200][];
        java.util.Random dice = new java.util.Random(7);

        for (int i = 0; i < rows.length; i++) {
            double middle = 100 + dice.nextGaussian() * 5;
            double half = Math.abs(dice.nextGaussian()) + 0.5;

            rows[i] = new double[]{middle + half, middle - half,
                    middle - half + dice.nextDouble() * 2 * half};
        }

        SlowStochastic study = new SlowStochastic(8, 3);

        study.calculate(bars(rows));

        for (int i = 0; i < rows.length; i++) {
            for (double each : study.valueAt(i)) {
                if (Double.isNaN(each)) {
                    continue;
                }

                assertTrue(each >= -1e-9 && each <= 100 + 1e-9,
                        "bar " + i + " left the scale at " + each);
            }
        }

        assertEquals(0.0, study.bounds()[0]);
        assertEquals(100.0, study.bounds()[1]);
    }

    @Test
    @DisplayName("a segunda linha atrasa em relacao a primeira")
    void theAverageLags() {
        // A climb then a fall. On the way down the average has to be ABOVE the
        // indicator -- that is what makes a crossing mean anything.
        double[][] rows = new double[40][];

        for (int i = 0; i < 20; i++) {
            rows[i] = new double[]{100 + i, 99 + i, 100 + i};
        }

        for (int i = 20; i < 40; i++) {
            double top = 120 - (i - 20);

            rows[i] = new double[]{top, top - 1, top - 1};
        }

        SlowStochastic study = new SlowStochastic(4, 3);

        study.calculate(bars(rows));

        // ALL THE WAY DOWN, and the other way on the way up. Asked at the
        // last bar alone, a mutation that swapped the two lines only on the
        // rise passed -- and it is the CROSSING that makes the indicator mean
        // anything, which one point cannot show.
        for (int i = 30; i < 40; i++) {
            assertTrue(study.valueAt(i)[1] > study.valueAt(i)[0],
                    "bar " + i + ": the average did not lag on the way down");
        }

        // AND NOT "the other way on the way up", which this fixture cannot
        // show: the rise closes at the high of every bar, so %K is a hundred
        // once it is warm and the average catches up to a hundred with it. The
        // two lines are EQUAL there, and an assertion about their order would
        // have been satisfied by any mutation at all -- which is exactly the
        // fault being corrected here, moved one line down.
        //
        // What the rise does say is that both lines get there.
        assertEquals(100.0, study.valueAt(19)[0], 1e-9);
        assertEquals(100.0, study.valueAt(19)[1], 1e-9,
                "the average never caught up on a rise that closed at every high");
    }

    @Test
    @DisplayName("sem a media, so uma linha sai")
    void withoutTheAverageThereIsOneLine() {
        SlowStochastic study = new SlowStochastic(4, 2);

        study.setShowsAverage(false);
        study.calculate(climbing(20));

        assertEquals(1, study.valueAt(19).length);
        assertEquals(1, study.colours().size());
        assertEquals(1, study.strokes().size());

        // The PARAMETERS keep both, because a layout stores them to rebuild
        // this indicator and a list that shrank here would lose the average's
        // period the moment its line was hidden. Only the drawing shrinks.
        assertEquals(2, study.parameters().size());
    }

    @Test
    @DisplayName("sem as linhas de compra e venda, nenhum nivel sai")
    void levelsCanBeTurnedOff() {
        SlowStochastic study = new SlowStochastic();

        assertEquals(2, study.levels().size());

        study.setShowsLevels(false);

        assertTrue(study.levels().isEmpty());
    }

    @Test
    @DisplayName("uma serie vazia nao explode")
    void nothingIsStillSomething() {
        SlowStochastic study = new SlowStochastic();

        study.calculate(PriceSeries.empty());

        assertTrue(Double.isNaN(study.valueAt(0)[0]));
    }

    @Test
    @DisplayName("a exponencial aquece igual a aritmetica, e comeca na media da primeira janela")
    void theExponentialWarmsUpToo() {
        // The exponential branch used to sit at the TOP of the loop, before the
        // window bookkeeping: it wrote a value on the very first finite point,
        // seeded from that one number. So the slow line began `average` bars
        // earlier than the javadoc over the method says it does, with a hook at
        // the left edge -- and the signal line, which is this smoothing applied
        // twice, inherited the hook. A reader comparing with the reference
        // product sees two lines that do not match at the start.
        //
        // Three behaviours for one idea lived in this project: this one, the
        // arithmetic branch beside it (which waits), and
        // MovingAverage.exponential, whose own comment says the seed is the
        // first window and not the first price. They agree now.
        SlowStochastic exponential = new SlowStochastic(4, 3);
        SlowStochastic arithmetic = new SlowStochastic(4, 3);

        exponential.setKind(MovingAverage.Kind.EXPONENTIAL);

        PriceSeries bars = climbing(20);

        exponential.calculate(bars);
        arithmetic.calculate(bars);

        for (int i = 0; i < bars.size(); i++) {
            assertEquals(Double.isNaN(arithmetic.valueAt(i)[0]),
                    Double.isNaN(exponential.valueAt(i)[0]),
                    "bar " + i + ": the two kinds start at different bars");
            assertEquals(Double.isNaN(arithmetic.valueAt(i)[1]),
                    Double.isNaN(exponential.valueAt(i)[1]),
                    "bar " + i + ": the two signal lines start at different bars");
        }

        // And on this staircase every raw ratio is a hundred once the window
        // fills, so the first exponential value -- the mean of its first window
        // -- is a hundred, exactly as the arithmetic one is. Seeded from a
        // single point it would be a hundred too; what the loop above catches is
        // WHERE it appears, which is the whole defect.
        assertEquals(100.0, exponential.valueAt(19)[0], 1e-9);
        assertEquals(100.0, exponential.valueAt(19)[1], 1e-9);
    }
/**
     * The fifteen fields, all of them, all different from their defaults.
     *
     * <p>The longest positional format in the project was the one nobody
     * tested: fifteen fields separated by semicolons, and the RSI, the bands
     * and the moving average each had a round trip while this had none. A field
     * inserted in the middle reinterprets every layout already saved, and
     * nothing would have said so.</p>
     *
     * <p>Every value set here differs from the default, which is what makes the
     * test able to fail. A round trip written with defaults passes with
     * {@code applyAppearance} doing nothing at all -- and the point of this one
     * is that it did catch something: {@code appearance()} wrote the buy
     * colour, {@code applyAppearance} forced the sell colour to equal it, and
     * the second colour was lost on every save. There is one colour now, and
     * the format kept the field it always had.</p>
     */
    @Test
    @DisplayName("a aparencia da a volta inteira, nos quinze campos")
    void theAppearanceRoundTrip() {
        SlowStochastic written = new SlowStochastic(21, 5);

        written.setKind(MovingAverage.Kind.EXPONENTIAL);
        written.setLine(MovingAverage.Line.DOTTED);
        written.setColour(new java.awt.Color(0x123456));
        written.setWidth(2.5f);

        written.setAverageLine(MovingAverage.Line.DASHED);
        written.setAverageColour(new java.awt.Color(0x654321));
        written.setAverageWidth(3.5f);

        written.setLevelLine(MovingAverage.Line.SOLID);
        written.setLevelColour(new java.awt.Color(0xABCDEF));
        written.setLevelWidth(4.5f);

        written.setShowsAverage(false);
        written.setShowsLevels(false);
        written.setBuyLevel(15.0);
        written.setSellLevel(85.0);
        written.setOwnPeriod("5m");

        SlowStochastic read = new SlowStochastic(21, 5);

        read.applyAppearance(written.appearance());

        assertEquals(MovingAverage.Kind.EXPONENTIAL, read.kind());
        assertEquals(MovingAverage.Line.DOTTED, read.line());
        assertEquals(new java.awt.Color(0x123456), read.colour());
        assertEquals(2.5f, read.width());

        assertEquals(MovingAverage.Line.DASHED, read.averageLine());
        assertEquals(new java.awt.Color(0x654321), read.averageColour());
        assertEquals(3.5f, read.averageWidth());

        assertEquals(MovingAverage.Line.SOLID, read.levelLine());
        assertEquals(new java.awt.Color(0xABCDEF), read.levelColour());
        assertEquals(4.5f, read.levelWidth());

        assertFalse(read.showsAverage(), "the average came back switched on");
        assertFalse(read.showsLevels(), "the levels came back switched on");
        assertEquals(15.0, read.buyLevel());
        assertEquals(85.0, read.sellLevel());
        assertEquals("5m", read.ownPeriod(), "the scale is what tells two of them apart");
    }

    /**
     * And the two levels wear the one colour, on the screen.
     *
     * <p>The round trip above would still pass with the drawing reading some
     * other field, so this asks the thing that is actually drawn: both levels
     * come out of {@code levels()} in the colour that was set, and the sell
     * level is the one that used to be lost.</p>
     */
    @Test
    @DisplayName("os dois niveis saem na cor que foi escolhida")
    void bothLevelsWearTheChosenColour() {
        SlowStochastic study = new SlowStochastic();

        study.setLevelColour(new java.awt.Color(0xABCDEF));

        assertEquals(2, study.levels().size(), "a stochastic draws two levels");

        for (br.com.jorge.reis.endeavourneo.ui.chart.Overlay.Level each : study.levels()) {
            assertEquals(new java.awt.Color(0xABCDEF), each.colour(),
                    "the level at " + each.at() + " is drawn in another colour");
        }
    }
}
