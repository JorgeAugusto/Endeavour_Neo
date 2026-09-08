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
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.domain.market.RandomWalkSeries;
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
    private record Fake(String nameKey, double[] bounds) implements Overlay {

        @Override
        public boolean fitsOnPrice() {
            return false;
        }

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
        // TWO ARRAYS, not the same one twice. Both sides used to hand over this
        // file's own constant, so the rule passed with the comparison mutated
        // from "same two numbers" to "same object" -- and that mutation is not
        // academic: RelativeStrength.bounds and SlowStochastic.bounds each
        // return a NEW double[]{0, 100} on every call, so the real pair named in
        // the rule's own javadoc would have been refused.
        assertTrue(StudyStack.fits(
                List.of(new Fake("study.stochastic", new double[]{0, 100})),
                new Fake("study.rsi", new double[]{0, 100})),
                "nought to a hundred is nought to a hundred whoever is saying it");
    }

    @Test
    @DisplayName("o par que a regra nomeia: IFR e estocastico dividem painel")
    void therealPairTheRuleNames() {
        // The two the javadoc of the rule names, and the ones a reader actually
        // puts together. Fakes can agree by accident; these two agree because
        // both are oscillators bounded at nought and a hundred.
        assertTrue(StudyStack.fits(
                List.of(new SlowStochastic(8, 3)),
                new br.com.jorge.reis.endeavourneo.ui.chart.study.rsi.RelativeStrength(14)),
                "the RSI and the stochastic, which the rule exists for, were kept apart");
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
        List<Overlay> present = List.of(
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
    void droppingThem() throws Exception {
        StudyPane pane = stack.show(new SlowStochastic(8, 3));
        SlowStochastic second = new SlowStochastic(21, 5);

        stack.addTo(pane, second);
        pane.drop(second);

        assertEquals(1, pane.studies().size());
        assertEquals(1, stack.panes().size(), "the pane went away with one still in it");

        pane.drop(pane.studies().get(0));

        // Drained, because closing is POSTED now and not done inline: the pane
        // is taken out of the container after the gesture that asked for it has
        // finished being delivered, the way reordering already did. What is
        // asserted is unchanged -- the pane goes away -- one event later.
        javax.swing.SwingUtilities.invokeAndWait(() -> { });

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
    @DisplayName("esconder um estudo devolve a escala do painel")
    void ahiddenStudyStopsStretchingTheScale() {
        // Four loops walked the pane's list of studies and exactly ONE of them
        // asked whether the study was visible. So hiding a line hid the line and
        // nothing else: the study went on stretching the vertical scale, went on
        // drawing its levels across it, and went on dictating the numbers up the
        // right edge. Hiding an indicator that deforms the scale did not give
        // the scale back -- which is most of the reason to hide one.
        //
        // TWO AVERAGES, because those are the ones that fit themselves to the
        // data: a stochastic and an RSI both run nought to a hundred by
        // definition, so hiding either of them could not change the scale, and
        // addTo refuses anything else beside them on purpose. Two moving
        // averages of the same rising line have different ranges -- the fast one
        // reaches higher, because the slow one lags -- so the top of the pane is
        // the fast one's, and hiding it has to bring the top down.
        canvas.setSeries(rising(400));

        StudyPane pane = stack.show(
                new br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage(5));

        assertTrue(stack.addTo(pane,
                        new br.com.jorge.reis.endeavourneo.ui.chart.overlay
                                .MovingAverage(50)),
                "the second average was refused, so the pane holds one study and "
                        + "nothing below is being tested");

        stack.recalculate();

        // The recalculation runs off the interface thread now, on one queue, so
        // reading its result means waiting for that queue. What is asserted
        // below is unchanged.
        StudyStack.awaitRecalculations();

        double[] both = pane.range(canvas.plotViewport());

        pane.studies().get(0).setVisible(false);

        double[] slowOnly = pane.range(canvas.plotViewport());

        assertTrue(slowOnly[1] < both[1],
                "hiding the study that reaches highest did not bring the top of the pane "
                        + "down: the line disappeared and its effect on the scale did not, "
                        + "which is most of the reason to hide one. " + both[1] + " -> "
                        + slowOnly[1]);
    }

    /** A line that only goes up, so a fast average always sits above a slow one. */
    private static br.com.jorge.reis.endeavourneo.domain.market.PriceSeries rising(int count) {
        return new br.com.jorge.reis.endeavourneo.domain.market.PriceSeries() {

            @Override
            public int size() {
                return count;
            }

            @Override
            public long timeAt(int index) {
                return 1_756_000_000_000L + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return 100 + index;
            }

            @Override
            public double highAt(int index) {
                return 100 + index;
            }

            @Override
            public double lowAt(int index) {
                return 100 + index;
            }

            @Override
            public double closeAt(int index) {
                return 100 + index;
            }
        };
    }

    @Test
    @DisplayName("um estudo escondido volta escondido")
    void ahiddenStudyComesBackHidden() {
        // The third field of a layout Entry is whether the eye is open, and the
        // way OUT wrote true whatever the study said, while the way BACK never
        // read it at all. The price overlays' own path has always carried it --
        // two answers to "how does an Entry become an Overlay", and the panes'
        // one was wrong at both ends.
        //
        // Latent today, because only the price legend has an eye to click. It is
        // not dead: paintLines already asks isVisible(), so the expensive half of
        // the rule is written and waiting. The day the eye reaches the pane
        // header, hiding a study and reopening the chart would bring it back lit
        // -- and the defect would look like it belonged to the new feature.
        StudyPane pane = stack.show(new SlowStochastic(8, 3));

        stack.addTo(pane, new SlowStochastic(21, 5));

        pane.studies().get(0).setVisible(false);

        stack.restore(stack.remembered());

        assertFalse(stack.panes().get(0).studies().get(0).isVisible(),
                "the study came back with its eye open: hiding one and reopening the "
                        + "chart undoes the hiding");
        assertTrue(stack.panes().get(0).studies().get(1).isVisible(),
                "the one that was showing came back hidden");
    }

    @Test
    @DisplayName("recalcular alcanca todos, nao so o primeiro")
    void recalculatingReachesAll() {
        StudyPane pane = stack.show(new SlowStochastic(8, 3));
        SlowStochastic second = new SlowStochastic(21, 5);

        stack.addTo(pane, second);

        canvas.setSeries(new RandomWalkSeries(400, 200.0));
        stack.recalculate();

        // Off the interface thread now: waited for, not slept on -- the barrier
        // is a task at the back of the same single queue.
        StudyStack.awaitRecalculations();

        // A study still holding the values of the series before would draw a
        // shape that never happened, at bars that are not the ones it was
        // measured on. The last bar of the new series is the one to ask about:
        // the old one did not have it.
        assertFalse(Double.isNaN(second.valueAt(399)[0]),
                "the second indicator was never recomputed against the new series");
    }
    @Test
    @DisplayName("o recalculo nao roda na thread da interface")
    void theRecalculationIsNotOnTheInterfaceThread() throws Exception {
        // The cost is written down by the code itself: OwnScale records 183 ms
        // to recalculate one average on its own scale and SlowStochastic 319 ms
        // for one stochastic. Three studies is close to a second of a frozen
        // window with nothing on screen saying anything is happening -- and it
        // ran on a period change, which is one keystroke.
        List<String> threads = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        StudyPane pane = stack.show(new Recording(threads));

        stack.addTo(pane, new Recording(threads));

        threads.clear();

        javax.swing.SwingUtilities.invokeAndWait(() -> stack.recalculate());

        StudyStack.awaitRecalculations();

        assertFalse(threads.isEmpty(), "nothing was recalculated, so this proves nothing");

        for (String on : threads) {
            assertFalse(on.startsWith("AWT-EventQueue"),
                    "a study was recalculated on the interface thread (" + on + "), "
                            + "which is the freeze this was moved off it to stop");
        }
    }

    @Test
    @DisplayName("quem le durante um recalculo ve a linha anterior inteira, nunca meia")
    void theValuesArePublishedWhole() throws Exception {
        // The precondition for running any of that off the interface thread.
        // The four implementations used to assign the empty array to the field
        // and fill it IN PLACE, so a repaint landing in the middle read an
        // array half full of zeros.
        //
        // Not a race to be won by timing: the series itself blocks the
        // recalculation at a known bar and holds it there while this thread
        // reads. What comes out has to be the whole previous line.
        canvas.setSeries(rising(600));

        SlowStochastic study = new SlowStochastic(14, 3);

        stack.show(study);
        StudyStack.awaitRecalculations();

        double[] before = new double[600];

        for (int bar = 0; bar < before.length; bar++) {
            before[bar] = study.valueAt(bar)[0];
        }

        assertFalse(Double.isNaN(before[599]),
                "the first pass computed nothing, so there is no previous line to see");

        java.util.concurrent.CountDownLatch reached = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        canvas.setSeries(heldAt(rising(600), 300, reached, release));

        javax.swing.SwingUtilities.invokeAndWait(() -> stack.recalculate());

        assertTrue(reached.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "the recalculation never reached the bar it was to be held at");

        try {
            for (int bar = 0; bar < before.length; bar++) {
                assertEquals(before[bar], study.valueAt(bar)[0], 0.0,
                        "bar " + bar + ": read while the recalculation was halfway "
                                + "through, and it is neither the old value nor a value "
                                + "the indicator ever computed");
            }
        } finally {
            release.countDown();
        }

        StudyStack.awaitRecalculations();
    }

    /**
     * The same bars, but stopping the reader dead at one of them.
     *
     * <p>Counts down {@code reached} the first time that bar's close is asked
     * for, and does not answer until {@code release}. That turns "a repaint
     * landing in the middle of a recalculation" from something to be provoked
     * by timing into something that simply happens.</p>
     */
    private static PriceSeries heldAt(PriceSeries bars, int at,
            java.util.concurrent.CountDownLatch reached,
            java.util.concurrent.CountDownLatch release) {

        return new PriceSeries() {

            @Override
            public int size() {
                return bars.size();
            }

            @Override
            public long timeAt(int index) {
                return bars.timeAt(index);
            }

            @Override
            public double openAt(int index) {
                return bars.openAt(index);
            }

            @Override
            public double highAt(int index) {
                return bars.highAt(index);
            }

            @Override
            public double lowAt(int index) {
                return bars.lowAt(index);
            }

            @Override
            public double closeAt(int index) {
                if (index == at && reached.getCount() > 0) {
                    reached.countDown();

                    try {
                        release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                return bars.closeAt(index);
            }
        };
    }

    /** An indicator that writes down which thread recalculated it. */
    private record Recording(List<String> threads) implements Overlay {

        @Override
        public String nameKey() {
            return "study.rsi";
        }

        @Override
        public boolean fitsOnPrice() {
            return false;
        }

        @Override
        public double[] bounds() {
            return new double[] {0, 100};
        }

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
            threads.add(Thread.currentThread().getName());
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
}
