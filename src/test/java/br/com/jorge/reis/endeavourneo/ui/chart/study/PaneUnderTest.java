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

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartCanvas;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A pane draws what its indicators put UNDER their lines.
 *
 * <p>{@code Overlay.paintUnder} was asked for by the price chart and by nothing
 * else. The insert dialog never disables the "new pane" choice, so a Bollinger
 * can be put in a pane of its own — and there it lost its shading, silently.
 * The mirror of the same omission on the other side: the price chart never
 * asked for {@code levels()}.</p>
 *
 * <p>And the viewport it is handed has to be the PANE's. The one the chart uses
 * maps prices; a stochastic's twenty and eighty run through it land far under
 * the floor of the window, so calling the method with the wrong frame would
 * look like honouring it and draw nothing anyone can see.</p>
 */
class PaneUnderTest {

    private static final int HEIGHT = 120;

    /** A minute series with a range nothing like nought-to-a-hundred. */
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
                return 120_000.0 + index;
            }

            @Override
            public double highAt(int index) {
                return 120_005.0 + index;
            }

            @Override
            public double lowAt(int index) {
                return 119_995.0 + index;
            }

            @Override
            public double closeAt(int index) {
                return 120_002.0 + index;
            }
        };
    }

    @Test
    @DisplayName("o painel pinta o que o indicador poe embaixo, na escala DELE")
    void thePaneDrawsWhatTheIndicatorPutsUnderneath() {
        AtomicInteger asked = new AtomicInteger();
        AtomicReference<double[]> seen = new AtomicReference<>();

        Overlay shaded = new Overlay() {

            @Override
            public String nameKey() {
                return "overlay.stoch";
            }

            @Override
            public List<Integer> parameters() {
                return List.of(14);
            }

            @Override
            public List<Color> colours() {
                return List.of(Color.BLUE);
            }

            @Override
            public double[] valueAt(int bar) {
                return new double[]{20.0 + (bar % 60)};
            }

            @Override
            public void paintUnder(Graphics2D g, Viewport viewport, int from, int to) {
                asked.incrementAndGet();
                seen.set(new double[]{viewport.y(20.0), viewport.y(80.0)});
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

            @Override
            public boolean fitsOnPrice() {
                return false;
            }
        };

        ChartCanvas canvas = new ChartCanvas();

        canvas.setSeries(minutes(200));
        canvas.setSize(400, 300);

        StudyPane pane = new StudyPane(canvas, shaded, () -> { });

        pane.setSize(400, HEIGHT);
        pane.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 11));

        BufferedImage image = new BufferedImage(400, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        try {
            pane.paint(g);
        } finally {
            g.dispose();
        }

        assertTrue(asked.get() > 0,
                "the pane never asked the indicator to paint what goes under its lines, so "
                        + "a Bollinger placed in a pane of its own loses its shading");

        double[] band = seen.get();

        assertTrue(band[1] < band[0],
                "a higher value came out lower down: " + band[1] + " for eighty against "
                        + band[0] + " for twenty");

        assertTrue(band[0] > 0 && band[0] <= HEIGHT && band[1] >= 0 && band[1] < HEIGHT,
                "the indicator's own numbers landed outside the pane -- " + band[1] + " to "
                        + band[0] + " in a pane " + HEIGHT + " pixels tall -- so the viewport "
                        + "it was handed maps prices, not the pane's own scale");
    }
}
