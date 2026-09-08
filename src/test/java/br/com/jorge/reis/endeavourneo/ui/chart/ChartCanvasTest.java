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

import br.com.jorge.reis.endeavourneo.domain.market.RandomWalkSeries;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scale drag, tested as arithmetic rather than as a gesture.
 *
 * <p>Driving real mouse events through a component is slow and flaky. Pulling
 * the calculation out of the listener makes the part that can actually be wrong
 * checkable in microseconds, and leaves the listener with nothing but plumbing.</p>
 */
@DisplayName("Chart canvas")
class ChartCanvasTest {

    @Test
    @DisplayName("dragging UP stretches and dragging DOWN flattens")
    void dragDirection() {
        // The gesture has to match the metaphor: pulling the axis taller makes
        // the chart taller. Inverted, it is the kind of thing every user notices
        // in the first three seconds and nobody can quite name.
        double up = ChartCanvas.stretchForDrag(1.0, -100);
        double down = ChartCanvas.stretchForDrag(1.0, 100);

        assertTrue(up > 1.0, "dragging up should stretch, got " + up);
        assertTrue(down < 1.0, "dragging down should flatten, got " + down);
    }

    @Test
    @DisplayName("the same drag has the same effect whether the scale is small or large")
    void dragIsMultiplicative() {
        // A fixed step per pixel would crawl at one end of the range and jump at
        // the other. Multiplying keeps the feel constant.
        double fromSmall = ChartCanvas.stretchForDrag(0.5, -50) / 0.5;
        double fromLarge = ChartCanvas.stretchForDrag(4.0, -50) / 4.0;

        assertEquals(fromSmall, fromLarge, 1e-9,
                "the same drag produced different ratios at different scales");
    }

    @Test
    @DisplayName("no drag is long enough to flatten the chart into a line or push it off screen")
    void staysWithinLimits() {
        // REACHED, not merely respected. Asked only whether the answer stays
        // on the legal side, a floor raised from 0,1 to 0,9 satisfies every
        // assertion in this class -- the chart would refuse to flatten past
        // ninety per cent, the reader would lose nine tenths of the range, and
        // the suite would stay green. The ceiling was protected only by
        // accident, through the ratio in dragIsMultiplicative.
        assertEquals(0.1, ChartCanvas.stretchForDrag(1.0, 100_000), 1e-12,
                "an enormous downward drag did not reach the floor");
        assertEquals(20.0, ChartCanvas.stretchForDrag(1.0, -100_000), 1e-12,
                "an enormous upward drag did not reach the ceiling");
    }

    @Test
    @DisplayName("the time step is always a round interval, never an arbitrary one")
    void timeStepIsRound() {
        // A label at 14:00 and the next at 14:37 is arithmetic showing through.
        // Every value returned has to be one a person would choose.
        int[] round = {1, 2, 5, 10, 15, 30, 60, 120, 180, 240, 360, 720, 1_440, 2_880, 10_080};

        for (long span : new long[]{5, 37, 120, 400, 1_000, 5_000, 50_000, 900_000}) {
            int step = ChartCanvas.niceTimeStep(span, 8);
            boolean known = false;

            for (int candidate : round) {
                known |= candidate == step;
            }

            assertTrue(known, "span " + span + " produced the odd step " + step);
        }
    }

    @Test
    @DisplayName("a wider chart gets finer steps, never coarser")
    void widerMeansFiner() {
        // More room means more labels fit, so the interval may shrink. If it
        // grew instead, widening the window would remove information.
        long span = 600;

        // STRICTLY finer, and both values pinned. Written as "<=" over two
        // calls, it passed with the wanted-label count IGNORED: both sides then
        // answer the same step, 720 <= 720 holds, and the width of the window
        // stops deciding how many labels appear. The other two tests of this
        // axis stay green under that mutation as well.
        assertEquals(30, ChartCanvas.niceTimeStep(span, 20),
                "ten hours across twenty labels is a label every half hour");
        assertEquals(180, ChartCanvas.niceTimeStep(span, 4),
                "ten hours across four labels is a label every three hours");

        assertTrue(ChartCanvas.niceTimeStep(span, 20) < ChartCanvas.niceTimeStep(span, 4),
                "more labels asked for produced a step no finer");
    }

    @Test
    @DisplayName("an absurd span falls on the largest step instead of overflowing")
    void absurdSpanIsClamped() {
        assertEquals(10_080, ChartCanvas.niceTimeStep(Long.MAX_VALUE / 2, 8),
                "a span of centuries did not clamp to the largest step");
    }

    @Test
    @DisplayName("a drag of zero pixels changes nothing")
    void zeroDragIsIdentity() {
        // Guards the click that is not a drag: pressing on the axis and
        // releasing without moving must not nudge the scale.
        assertEquals(2.5, ChartCanvas.stretchForDrag(2.5, 0), 1e-12,
                "a click with no movement changed the scale");
    }
    @Test
    @DisplayName("escolher de novo o periodo que ja esta na tela nao refaz tudo")
    void choosingThesamePeriodAgainIsNotAChange() {
        // Renko and Timeframe are values and neither overrides equals, and
        // PeriodCatalog.byCode builds a new object on every call -- so the guard
        // compared two different objects, said "this is a change", and paid for
        // a whole refold: the fold, the tick rebuild with its directory
        // listings, and a SwingWorker. Not a wrong answer; all the work again
        // for nothing.
        ChartCanvas canvas = new ChartCanvas();

        canvas.setSeries(new RandomWalkSeries(300, 100.0));
        canvas.setSize(900, 500);
        canvas.setPeriod(Timeframe.ofMinutes(5), "5m", "5m");

        PriceSeries folded = canvas.series();

        // A DIFFERENT OBJECT saying the same thing, which is what the period
        // window hands over.
        canvas.setPeriod(Timeframe.ofMinutes(5), "5m", "5m");

        assertSame(folded, canvas.series(),
                "the same period, in a new object, refolded the whole series");
    }
/**
     * The price format is kept, one per number of decimals.
     *
     * <p>There are seven possible answers — nought to six — and this built a new
     * {@code DecimalFormat} every time it was asked, which is three times a
     * frame: the price axis, the last-price tag and the cursor's label. A
     * {@code DecimalFormat} parses its pattern and builds its symbols on
     * construction, inside the painting loop this file spends comments telling
     * the reader not to allocate in.</p>
     */
    @Test
    @DisplayName("o formato do preco e guardado, um por numero de casas")
    void thepriceFormatIsKept() {
        assertSame(ChartCanvas.formatFor(5.0), ChartCanvas.formatFor(5.0),
                "a new format was built for a step already asked about");

        // The same number of decimals is the same format, whatever the step.
        assertSame(ChartCanvas.formatFor(5.0), ChartCanvas.formatFor(500.0));

        // And a different number of decimals is a different one, or the cache
        // would be handing out the wrong shape.
        assertNotSame(ChartCanvas.formatFor(5.0), ChartCanvas.formatFor(0.05));
    }

    /**
     * The price chart asks each line for ITS stroke, not the indicator for one.
     *
     * <p>{@code Overlay.strokes()} exists so an indicator can dress each of its
     * lines apart, and {@code StudyPane} honours it — so the setting worked in a
     * pane and did nothing on the price chart. Not hypothetical: the Bollinger
     * bands answer three strokes so the middle line can carry the reader's own
     * style and thickness, and the bands are drawn here.</p>
     */
    @Test
    @DisplayName("o grafico de preco pergunta o traco de CADA linha")
    void thepriceChartAsksEachLineForItsStroke() {
        java.util.concurrent.atomic.AtomicInteger asked =
                new java.util.concurrent.atomic.AtomicInteger();

        Overlay twoLines = new Overlay() {

            @Override
            public String nameKey() {
                return "overlay.movingAverage";
            }

            @Override
            public boolean fitsOnPrice() {
                return true;
            }

            @Override
            public java.util.List<Integer> parameters() {
                return java.util.List.of(1);
            }

            @Override
            public java.util.List<java.awt.Color> colours() {
                return java.util.List.of(java.awt.Color.RED, java.awt.Color.BLUE);
            }

            @Override
            public java.util.List<java.awt.Stroke> strokes() {
                asked.incrementAndGet();

                return java.util.List.of(new java.awt.BasicStroke(1f),
                        new java.awt.BasicStroke(4f));
            }

            @Override
            public double[] valueAt(int bar) {
                return new double[]{100.0 + bar, 90.0 + bar};
            }

            @Override
            public void calculate(PriceSeries series) {
                // Nothing to compute.
            }

            @Override
            public boolean isVisible() {
                return true;
            }

            @Override
            public void setVisible(boolean visible) {
                // Always on.
            }
        };

        ChartCanvas canvas = new ChartCanvas();

        canvas.setSeries(new br.com.jorge.reis.endeavourneo.domain.market
                .RandomWalkSeries(60, 100.0));
        canvas.setSize(400, 300);
        canvas.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
        canvas.setOverlays(java.util.List.of(twoLines));

        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(400, 300,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();

        try {
            canvas.paint(g);
        } finally {
            g.dispose();
        }

        assertTrue(asked.get() > 0,
                "the price chart never asked for the strokes, so an indicator that dresses "
                        + "its lines apart is drawn with one stroke for all of them");
    }

    /**
     * The price chart draws an indicator's horizontal lines.
     *
     * <p>{@code Overlay.levels()} is honoured by {@code StudyPane} and was
     * honoured by nothing else -- the mirror of {@code paintUnder}, which the
     * price chart asked for and the pane did not. No indicator that fits on the
     * price declares a level today, so this was the half of the contract that
     * could be dropped without anybody noticing; it is also the half a level on
     * a PRICE would land in, and the levels' own javadoc says they are "part of
     * what it MEANS".</p>
     */
    @Test
    @DisplayName("o grafico de preco desenha os niveis do indicador")
    void thePriceChartDrawsTheIndicatorsLevels() {
        java.awt.Color ink = new java.awt.Color(0x11, 0x99, 0x44);

        PriceSeries walk = new br.com.jorge.reis.endeavourneo.domain.market
                .RandomWalkSeries(60, 100.0);

        // A price the chart is SHOWING. A walk of sixty steps drifts, and a
        // level outside the visible range is drawn off the top of the image --
        // which would fail this test for a reason that is not the one it asks
        // about.
        double at = walk.closeAt(30);

        Overlay levelled = new Overlay() {

            @Override
            public String nameKey() {
                return "overlay.movingAverage";
            }

            @Override
            public boolean fitsOnPrice() {
                return true;
            }

            @Override
            public java.util.List<Integer> parameters() {
                return java.util.List.of(1);
            }

            @Override
            public java.util.List<java.awt.Color> colours() {
                return java.util.List.of(java.awt.Color.RED);
            }

            @Override
            public java.util.List<Overlay.Level> levels() {
                return java.util.List.of(new Overlay.Level(at, ink,
                        new java.awt.BasicStroke(1f)));
            }

            @Override
            public double[] valueAt(int bar) {
                return new double[]{Double.NaN};
            }

            @Override
            public void calculate(PriceSeries series) {
                // Nothing to compute: the level is the point.
            }

            @Override
            public boolean isVisible() {
                return true;
            }

            @Override
            public void setVisible(boolean visible) {
                // Always on.
            }
        };

        ChartCanvas canvas = new ChartCanvas();

        canvas.setSeries(walk);
        canvas.setSize(400, 300);
        canvas.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
        canvas.setOverlays(java.util.List.of(levelled));

        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(400, 300,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();

        try {
            canvas.paint(g);
        } finally {
            g.dispose();
        }

        int found = 0;

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == (ink.getRGB() & 0xFFFFFF)) {
                    found++;
                }
            }
        }

        assertTrue(found > 100,
                "the level was drawn in " + found + " pixels: the price chart never asked "
                        + "for levels(), so a line that is part of what the indicator MEANS "
                        + "is not on the chart it was put on");
    }
}
