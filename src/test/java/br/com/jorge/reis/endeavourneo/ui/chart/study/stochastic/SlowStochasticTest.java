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

        assertTrue(study.valueAt(39)[1] > study.valueAt(39)[0],
                "the average did not lag on the way down");
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
        assertEquals(1, study.parameters().size(), "the legend still shows the average's period");
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
}
