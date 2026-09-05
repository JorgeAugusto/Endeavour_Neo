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
package br.com.jorge.reis.endeavourneo.ui.chart.study;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartCanvas;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartLayout;
import br.com.jorge.reis.endeavourneo.ui.chart.RandomWalkSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic.SlowStochastic;

import java.awt.Color;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Several indicators sharing one pane, and which of them are allowed to.
 *
 * <p>Two readings make a pane worth sharing, and they are the two the rule
 * says yes to: the same indicator at several scales, and different indicators
 * whose range is fixed and equal. Everything else has to be refused, because
 * two measurements in different units share an axis only by one of them being
 * flattened against an edge.</p>
 */
@DisplayName("Varios indicadores num painel")
class PaneSharingTest {

    /**
     * An indicator that is only what the rule reads: a name and a range.
     *
     * <p>A double rather than a real indicator because the rule is about
     * names and ranges, and the project has exactly one study to build a case
     * out of — a test that could only compare stochastics to stochastics would
     * not reach the half of the rule that matters.</p>
     */
    private record Fake(String nameKey, double[] bounds) implements Study {

        @Override
        public List<Integer> parameters() {
            return List.of(1);
        }

        @Override
        public List<Color> colours() {
            return List.of(Color.RED);
        }

        @Override
        public double[] valueAt(int bar) {
            return new double[] {50.0};
        }

        @Override
        public void calculate(PriceSeries series) {
            // Nothing to compute; the value is the same everywhere.
        }

        @Override
        public boolean isVisible() {
            return true;
        }

        @Override
        public void setVisible(boolean visible) {
            // Always on.
        }
    }

    private static final double[] OSCILLATOR = {0, 100};

    private ChartCanvas canvas;

    private StudyStack stack;

    @BeforeEach
    void setUp() {
        canvas = new ChartCanvas();
        canvas.setSeries(new RandomWalkSeries(300, 100.0));

        stack = new StudyStack(canvas);
        stack.setSize(900, 500);
    }

    // ------------------------------------------------------------- the rule

    @Test
    @DisplayName("o mesmo indicador em escalas diferentes cabe junto")
    void theSameIndicatorAtAnotherScale() {
        SlowStochastic onTheChart = new SlowStochastic(8, 3);
        SlowStochastic onFiveMinutes = new SlowStochastic(8, 3);

        onFiveMinutes.setOwnPeriod("5m");

        assertTrue(StudyStack.fits(List.of(onTheChart), onFiveMinutes),
                "reading one scale against another is what a shared pane is for");
    }

    @Test
    @DisplayName("indicadores diferentes de faixa fixa igual cabem juntos")
    void differentIndicatorsOnTheSameFixedRange() {
        assertTrue(StudyStack.fits(
                List.of(new Fake("study.stochastic", OSCILLATOR)),
                new Fake("study.rsi", OSCILLATOR)),
                "nought to a hundred is nought to a hundred whoever is saying it");
    }

    @Test
    @DisplayName("faixas fixas diferentes nao cabem")
    void differentFixedRanges() {
        assertFalse(StudyStack.fits(
                List.of(new Fake("study.stochastic", OSCILLATOR)),
                new Fake("study.other", new double[] {-1, 1})));
    }

    @Test
    @DisplayName("dois indicadores diferentes sem faixa fixa nao cabem")
    void twoFittedIndicators() {
        // Their ranges might agree today and disagree tomorrow, and a rule
        // that depends on the bars on screen changes when the reader scrolls.
        assertFalse(StudyStack.fits(
                List.of(new Fake("study.macd", null)),
                new Fake("study.other", null)));
    }

    @Test
    @DisplayName("um indicador tem que caber em TODOS os que ja estao la")
    void everyoneAlreadyInside() {
        List<Study> present = List.of(
                new Fake("study.stochastic", OSCILLATOR),
                new Fake("study.macd", null));

        assertFalse(StudyStack.fits(present, new Fake("study.rsi", OSCILLATOR)),
                "it fitted the first one and was let in past the second");
    }

    @Test
    @DisplayName("o painel vazio aceita qualquer um")
    void anEmptyPane() {
        assertTrue(StudyStack.fits(List.of(), new Fake("study.macd", null)));
        assertFalse(StudyStack.fits(List.of(), null));
    }

    // ------------------------------------------------------------- the pane

    @Test
    @DisplayName("empilhar tres no mesmo painel deixa um painel so")
    void threeInOnePane() {
        StudyPane pane = stack.show(new SlowStochastic(8, 3));

        assertTrue(stack.addTo(pane, new SlowStochastic(21, 5)));
        assertTrue(stack.addTo(pane, new SlowStochastic(14, 3)));

        assertEquals(1, stack.panes().size(), "stacking opened new panes instead");
        assertEquals(3, pane.studies().size());
        assertEquals(List.of(8, 21, 14),
                pane.studies().stream().map(each -> each.parameters().get(0)).toList());
    }

    @Test
    @DisplayName("quem nao cabe e recusado e nao entra")
    void oneThatDoesNotFit() {
        StudyPane pane = stack.show(new SlowStochastic(8, 3));

        assertFalse(stack.addTo(pane, new Fake("study.price", new double[] {187000, 189000})));
        assertEquals(1, pane.studies().size(), "it was refused and went in anyway");
    }

    @Test
    @DisplayName("tirar um deixa os outros; tirar o ultimo fecha o painel")
    void droppingThem() {
        StudyPane pane = stack.show(new SlowStochastic(8, 3));
        SlowStochastic second = new SlowStochastic(21, 5);

        stack.addTo(pane, second);
        pane.drop(second);

        assertEquals(1, pane.studies().size());
        assertEquals(1, stack.panes().size(), "the pane went away with one still in it");

        pane.drop(pane.studies().get(0));

        assertTrue(stack.panes().isEmpty(),
                "the pane stayed on screen with nothing left to draw in it");
    }

    @Test
    @DisplayName("os tres voltam juntos, e no mesmo painel, pelo layout")
    void theyComeBackTogether() {
        StudyPane pane = stack.show(new SlowStochastic(8, 3));

        stack.addTo(pane, new SlowStochastic(21, 5));
        stack.addTo(pane, new SlowStochastic(14, 3));
        stack.show(new SlowStochastic(9, 3));

        List<ChartLayout.Pane> stored = stack.remembered();

        assertEquals(2, stored.size());
        assertEquals(3, stored.get(0).entries().size(),
                "the pane was written as one indicator and lost the other two");

        stack.restore(stored);

        assertEquals(2, stack.panes().size());
        assertEquals(List.of(8, 21, 14), stack.panes().get(0).studies().stream()
                .map(each -> each.parameters().get(0)).toList());
        assertEquals(1, stack.panes().get(1).studies().size());
    }

    @Test
    @DisplayName("recalcular alcanca todos, nao so o primeiro")
    void recalculatingReachesAll() {
        StudyPane pane = stack.show(new SlowStochastic(8, 3));
        SlowStochastic second = new SlowStochastic(21, 5);

        stack.addTo(pane, second);

        canvas.setSeries(new RandomWalkSeries(400, 200.0));
        stack.recalculate();

        // A study still holding the values of the series before would draw a
        // shape that never happened, at bars that are not the ones it was
        // measured on. The last bar of the new series is the one to ask about:
        // the old one did not have it.
        assertFalse(Double.isNaN(second.valueAt(399)[0]),
                "the second indicator was never recomputed against the new series");
    }
}
