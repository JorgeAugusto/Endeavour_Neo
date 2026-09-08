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
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartCanvas;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.study.rsi.RelativeStrength;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A study measured on the bars the chart is DRAWING.
 *
 * <h2>The two series a chart holds</h2>
 *
 * <p>{@code source()} is the bars as stored; {@code series()} is those bars with
 * the chosen scale applied, and it is what the viewport indexes. A study
 * computed against the first and drawn at the indices of the second is showing,
 * under candle <i>i</i>, the value it had at raw bar <i>i</i> — another instant
 * entirely.</p>
 *
 * <p>On five minutes over one, that is roughly five times further back. And it
 * goes the other way too: on a fine renko the bricks can outnumber the minutes,
 * so brick <i>i</i> happened BEFORE minute <i>i</i> and the pane shows a value
 * the market had not produced yet — reading the future through the back door.</p>
 *
 * <p>Every existing study test drew at the storage scale, where the two series
 * are the same object and nothing can tell them apart. That is why this one
 * changes the scale before it measures.</p>
 */
@DisplayName("Estudo na escala desenhada")
class StudyScaleTest {

    /** Bars of one minute, walking so a study has something to measure. */
    private static PriceSeries minutes(int count) {
        return new PriceSeries() {

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
                return 100 + (index % 17);
            }

            @Override
            public double highAt(int index) {
                return 104 + (index % 17);
            }

            @Override
            public double lowAt(int index) {
                return 97 + (index % 17);
            }

            @Override
            public double closeAt(int index) {
                return 101 + (index % 17);
            }
        };
    }

    @Test
    @DisplayName("numa escala diferente da de armazenamento, o estudo mede o que esta na tela")
    void theStudyMeasuresTheDrawnBars() {
        ChartCanvas canvas = new ChartCanvas();

        canvas.setSeries(minutes(600));

        // FIVE MINUTES, which is the scale of analysis of this house and the
        // one that makes the two series different objects of different lengths.
        canvas.setPeriod(Timeframe.ofMinutes(5), "5m", "5m");

        assertTrue(canvas.series().size() < canvas.source().size(),
                "the fold changed nothing, so this fixture proves nothing");

        StudyStack stack = new StudyStack(canvas);
        RelativeStrength study = new RelativeStrength();

        stack.show(study);

        // The same study, worked out fresh on what is actually on screen. If the
        // stack measured the drawn bars, the two agree bar for bar; if it
        // measured the stored ones, they disagree wherever the two series do --
        // which is everywhere past the warm-up.
        RelativeStrength reference = new RelativeStrength();

        reference.calculate(canvas.series());

        int compared = 0;

        for (int bar = 0; bar < canvas.series().size(); bar++) {
            double right = reference.valueAt(bar)[0];

            if (Double.isNaN(right)) {
                continue;
            }

            compared++;

            assertEquals(right, study.valueAt(bar)[0], 1e-9,
                    "bar " + bar + ": the pane is drawing the value the study had "
                            + "at the stored bar of that index, which is another instant");
        }

        assertTrue(compared > 50,
                "only " + compared + " bars were comparable: the fixture is too short");
    }
    @Test
    @DisplayName("a bar is asked for the same number of times whatever the line count")
    void theLineCountDoesNotMultiplyTheAsking() {
        // Every implementation of valueAt builds its answer, and the drawing
        // loop used to walk the bars INSIDE the lines -- so a stochastic, which
        // draws two, built two arrays per visible bar on every repaint, and a
        // repaint happens on every movement of the mouse.
        //
        // Asserted as a comparison rather than against a number: the pane also
        // asks legitimately in two other places -- the scale it fits to, and
        // the reading under the cursor -- and how many bars it shows depends on
        // its size. What must not happen is the count growing with the LINES.
        int one = mostPerBar(1);
        int four = mostPerBar(4);

        assertTrue(one > 0, "the study was never drawn, so this proves nothing");
        assertEquals(one, four,
                "a four-line study is asked " + four + " times for the same bar where a "
                        + "one-line study is asked " + one + ": the line count is doing "
                        + "the multiplying");
    }

    /** How often the busiest bar was asked for, painting a study of that many lines. */
    private static int mostPerBar(int lines) {
        java.util.List<Integer> asked = new java.util.ArrayList<>();

        ChartCanvas canvas = new ChartCanvas();

        canvas.setSeries(minutes(200));

        StudyPane pane = new StudyPane(canvas, ofLines(lines, asked), () -> { });

        pane.setSize(400, 120);
        pane.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 11));

        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                400, 120, java.awt.image.BufferedImage.TYPE_INT_ARGB);

        java.awt.Graphics2D g = image.createGraphics();

        try {
            pane.paint(g);
        } finally {
            g.dispose();
        }

        java.util.Map<Integer, Integer> times = new java.util.HashMap<>();

        for (int bar : asked) {
            times.merge(bar, 1, Integer::sum);
        }

        int most = 0;

        for (int count : times.values()) {
            most = Math.max(most, count);
        }

        return most;
    }

    /** A study of however many lines, recording which bars it is asked about. */
    private static Overlay ofLines(int lines, java.util.List<Integer> asked) {
        return new Overlay() {

            @Override
            public String nameKey() {
                return "overlay.stoch";
            }

            @Override
            public java.util.List<Integer> parameters() {
                return java.util.List.of(14);
            }

            @Override
            public java.util.List<java.awt.Color> colours() {
                java.util.List<java.awt.Color> found = new java.util.ArrayList<>();

                for (int line = 0; line < lines; line++) {
                    found.add(new java.awt.Color(40 * line + 20, 90, 160));
                }

                return found;
            }

            @Override
            public double[] valueAt(int bar) {
                asked.add(bar);

                double[] row = new double[lines];

                for (int line = 0; line < lines; line++) {
                    row[line] = 20 + (bar + 10 * line) % 60;
                }

                return row;
            }

            @Override
            public void calculate(PriceSeries series) {
                // Nothing: the values are fixed, and what is counted is the asking.
            }

            @Override
            public boolean isVisible() {
                return true;
            }

            @Override
            public void setVisible(boolean visible) {
                // Nothing: it is always drawn.
            }

            @Override
            public boolean fitsOnPrice() {
                return false;
            }
        };
    }
}
