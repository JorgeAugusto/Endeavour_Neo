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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.RandomWalkSeries;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The indicator's own scale reaches the reader on the PRICE chart too.
 *
 * <p>{@code Overlay.ownPeriod()} says in its own javadoc what it is for: two
 * indicators of the same shape, one on the chart's bars and one on five
 * minutes, are "two identical rows over two different lines". The study pane
 * put it on screen and nothing else did — so on the price chart, where the
 * moving average and the Bollinger bands live and where both offer the setting,
 * the two rows stayed identical. The setting was offered, stored, honoured in
 * the arithmetic, and invisible.</p>
 */
class OwnScaleShowsTest {

    /** An indicator that records which name the caller asked it for. */
    private static final class Named implements Overlay {

        private final String scale;

        private final AtomicInteger titles = new AtomicInteger();

        private final AtomicInteger labels = new AtomicInteger();

        Named(String scale) {
            this.scale = scale;
        }

        @Override
        public String nameKey() {
            return "overlay.movingAverage";
        }

        @Override
        public List<Integer> parameters() {
            return List.of(20);
        }

        @Override
        public String ownPeriod() {
            return scale;
        }

        @Override
        public String label() {
            labels.incrementAndGet();

            return Overlay.super.label();
        }

        @Override
        public String title() {
            titles.incrementAndGet();

            return Overlay.super.title();
        }

        @Override
        public boolean fitsOnPrice() {
            return true;
        }

        @Override
        public List<Color> colours() {
            return List.of(Color.RED);
        }

        @Override
        public double[] valueAt(int bar) {
            return new double[]{100.0};
        }

        @Override
        public void calculate(PriceSeries series) {
            // Nothing to compute: the number is fixed and the name is the point.
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

    @Test
    @DisplayName("o nome carrega a escala, e sem escala nao muda nada")
    void theNameCarriesTheScale() {
        assertEquals(new Named(null).title(), new Named(null).label(),
                "an indicator that follows the chart has nothing to add to its name");

        assertNotEquals(new Named(null).title(), new Named("5m").title(),
                "two averages of period twenty, one on the chart's bars and one on five "
                        + "minutes, still read the same");

        assertTrue(new Named("5m").title().contains("5m"),
                "the scale is not in the name: " + new Named("5m").title());
    }

    @Test
    @DisplayName("a legenda do grafico de preco pergunta o nome com a escala")
    void thePriceLegendAsksForTheNameWithTheScale() {
        Named average = new Named("5m");

        ChartCanvas canvas = new ChartCanvas();

        canvas.setSeries(new RandomWalkSeries(60, 100.0));
        canvas.setSize(400, 300);
        canvas.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 11));
        canvas.setOverlays(List.of(average));

        OverlayLegend legend = new OverlayLegend(canvas, "overlay.legend");

        legend.setSize(240, 80);
        legend.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 11));

        BufferedImage image = new BufferedImage(240, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        try {
            legend.paint(g);
        } finally {
            g.dispose();
        }

        assertTrue(average.titles.get() > 0,
                "the legend asked for label() and not title(), so an indicator on its own "
                        + "scale is written exactly like one on the chart's");
    }

    @Test
    @DisplayName("o menu de remover diz de qual dos dois se trata")
    void theRemoveMenuSaysWhichOfTheTwo() {
        Named average = new Named("5m");

        ChartCanvas canvas = new ChartCanvas();

        canvas.setSeries(new RandomWalkSeries(60, 100.0));
        canvas.setOverlays(List.of(average));

        String found = itemsOf(canvas.buildContextMenu());

        assertTrue(found.contains("5m"),
                "the remove menu names the indicator without its scale, so two averages of "
                        + "period twenty are two identical entries: " + found);
    }

    /** @return every menu entry's text, from the whole tree of it */
    private static String itemsOf(javax.swing.JPopupMenu menu) {
        StringBuilder text = new StringBuilder();

        for (java.awt.Component each : menu.getComponents()) {
            gather(each, text);
        }

        return text.toString();
    }

    private static void gather(java.awt.Component from, StringBuilder into) {
        if (from instanceof javax.swing.JMenu branch) {
            for (int i = 0; i < branch.getItemCount(); i++) {
                gather(branch.getItem(i), into);
            }
        } else if (from instanceof javax.swing.JMenuItem leaf) {
            into.append(leaf.getText()).append('\n');
        }
    }
}
