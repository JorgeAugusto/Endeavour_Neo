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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O canal de regressão linear, trazido do Endeavour original.
 *
 * <h2>A conta conferida fora da classe</h2>
 *
 * <p>A convenção destes portes é conferir contra a conta feita à mão, e é o que
 * o primeiro teste faz: cinco fechamentos, mínimos quadrados resolvido no papel,
 * e cada número — inclinação, reta nas duas pontas, sigma dos resíduos e
 * R-quadrado — fixado com o valor exato. Não é o indicador conferindo a si
 * mesmo.</p>
 *
 * <p>Os fechamentos são {@code 10, 12, 13, 15, 20} em x de 0 a 4:</p>
 *
 * <pre>
 *   n=5  Sx=10  Sy=70  Sxy=163  Sxx=30
 *   inclinação = (5·163 − 10·70) / (5·30 − 10²) = 115/50 = 2,3
 *   intercepto = (70 − 2,3·10)/5 = 9,4
 *   reta       = 9,4  11,7  14,0  16,3  18,6
 *   resíduos   = 0,6  0,3  −1,0  −1,3  1,4
 *   Σr² = 5,10   σ = √(5,10/5) = 1,00995049…
 *   média = 14   Σ(y−ȳ)² = 58   R² = 1 − 5,10/58 = 0,91206896…
 * </pre>
 */
@DisplayName("Canal de regressão linear")
class RegressionChannelTest {

    private static final double EXACT = 1e-12;

    /** @return a series of exactly those closes, an hour apart */
    private static PriceSeries closing(double... closes) {
        return new PriceSeries() {

            @Override
            public int size() {
                return closes.length;
            }

            @Override
            public long timeAt(int index) {
                return 1_600_000_000_000L + index * 3_600_000L;
            }

            @Override
            public double openAt(int index) {
                return closes[index];
            }

            @Override
            public double highAt(int index) {
                return closes[index] + 1;
            }

            @Override
            public double lowAt(int index) {
                return closes[index] - 1;
            }

            @Override
            public double closeAt(int index) {
                return closes[index];
            }
        };
    }

    @Test
    @DisplayName("a conta bate com a feita a mao, ate a ultima casa")
    void thearithmeticMatchesTheHandComputation() {
        RegressionChannel channel = new RegressionChannel(5);

        channel.setDeviations(1.0);

        RegressionChannel.Fit fit = channel.fitAt(closing(10, 12, 13, 15, 20), 4);

        assertNotNull(fit, "there were five bars and it asked for five");

        assertEquals(2.3, fit.slope(), EXACT, "the slope");
        assertEquals(9.4, fit.oldest(), EXACT, "the line at the oldest bar of the window");
        assertEquals(18.6, fit.newest(), EXACT, "the line at the anchor");

        assertEquals(Math.sqrt(1.02), fit.above(), EXACT, "one sigma above");
        assertEquals(Math.sqrt(1.02), fit.below(), EXACT, "one sigma below, the same");

        assertEquals(1.0 - 5.10 / 58.0, fit.rSquared(), EXACT, "R squared");

        assertEquals(0, fit.first(), "the window's oldest bar");
        assertEquals(4, fit.anchor(), "the anchor");
    }

    @Test
    @DisplayName("o extremo e assimetrico, e sempre mais largo que o desvio")
    void theExtremeIsWiderAndLopsided() {
        RegressionChannel channel = new RegressionChannel(5);

        channel.setDeviations(1.0);
        channel.setWidth(RegressionChannel.Width.EXTREME);

        RegressionChannel.Fit fit = channel.fitAt(closing(10, 12, 13, 15, 20), 4);

        // Os resíduos são 0,6 0,3 −1,0 −1,3 1,4: o maior para cima é 1,4 e o
        // maior para baixo é 1,3. Os dois lados diferem, que é o ponto.
        assertEquals(1.4, fit.above(), EXACT, "the most extreme residual above");
        assertEquals(1.3, fit.below(), EXACT, "the most extreme residual below");

        assertTrue(fit.above() > Math.sqrt(1.02) && fit.below() > Math.sqrt(1.02),
                "the javadoc claims the extreme is always wider than one sigma, and here it "
                        + "is not: " + fit.above() + " and " + fit.below());
    }

    @Test
    @DisplayName("os desvios multiplicam a largura, e nao a reta")
    void theDeviationsWidenOnlyTheEdges() {
        RegressionChannel channel = new RegressionChannel(5);

        channel.setDeviations(2.0);

        RegressionChannel.Fit fit = channel.fitAt(closing(10, 12, 13, 15, 20), 4);

        assertEquals(2.0 * Math.sqrt(1.02), fit.above(), EXACT, "two sigmas");
        assertEquals(18.6, fit.newest(), EXACT, "the centre moved with the deviations");
    }

    @Test
    @DisplayName("uma reta perfeita da R2 igual a um e bordas de largura zero")
    void aperfectLineExplainsEverything() {
        RegressionChannel channel = new RegressionChannel(4);

        RegressionChannel.Fit fit = channel.fitAt(closing(100, 105, 110, 115), 3);

        assertEquals(5.0, fit.slope(), EXACT, "five points a bar");
        assertEquals(1.0, fit.rSquared(), EXACT, "the line explains all of it");
        assertEquals(0.0, fit.above(), EXACT, "nothing strayed from the line");
    }

    @Test
    @DisplayName("so olha para tras: a barra seguinte nao mexe no ajuste")
    void itonlyLooksBackwards() {
        RegressionChannel channel = new RegressionChannel(4);

        RegressionChannel.Fit before =
                channel.fitAt(closing(100, 105, 110, 115, 200), 3);
        RegressionChannel.Fit alone =
                channel.fitAt(closing(100, 105, 110, 115), 3);

        assertEquals(alone.slope(), before.slope(), EXACT,
                "a bar AFTER the anchor changed the fit, which is the drawing tool's "
                        + "behaviour and the whole reason the indicator was ported instead");
        assertEquals(alone.newest(), before.newest(), EXACT, "and the line with it");
    }

    @Test
    @DisplayName("sem historico bastante nao ha ajuste")
    void withoutEnoughHistoryThereIsNoFit() {
        RegressionChannel channel = new RegressionChannel(10);

        assertNull(channel.fitAt(closing(1, 2, 3), 2), "it fitted ten bars over three");
        assertNull(channel.fitAt(closing(1, 2, 3), 99), "it fitted past the end of the series");
    }

    /**
     * A âncora é a última barra À VISTA.
     *
     * <p>É o que separa este indicador dos outros daqui: os números dele
     * dependem de onde o gráfico está. Rolar para trás reancora o canal, que é
     * o que o {@code DeslocarCandles} da origem fazia por parâmetro.</p>
     */
    @Test
    @DisplayName("a ancora e a ultima barra a vista, e fora da janela e NaN")
    void theAnchorIsTheLastBarInView() {
        RegressionChannel channel = new RegressionChannel(5);

        PriceSeries series = closing(10, 12, 13, 15, 20, 40, 41, 42, 43, 44);

        channel.calculate(series);

        // Antes de pintar não há ajuste nenhum, e o indicador diz isso em vez
        // de inventar uma reta.
        assertTrue(Double.isNaN(channel.valueAt(4)[1]),
                "it answered before anything had been fitted");

        paint(channel, viewportOver(0, 5));

        // A janela é 0..4, e é exatamente o caso da conta à mão.
        assertEquals(18.6, channel.valueAt(4)[1], EXACT, "the centre at the anchor");
        assertEquals(9.4, channel.valueAt(0)[1], EXACT, "the centre at the oldest bar");

        assertTrue(Double.isNaN(channel.valueAt(5)[1]),
                "the line was carried past the bars it was fitted to, which is a forecast "
                        + "and not what this indicator does");

        // E ROLANDO PARA A FRENTE o canal reancora, com outros números.
        paint(channel, viewportOver(5, 5));

        assertEquals(5, channel.fitAt(series, 9).first(), "the window moved with the view");
        assertTrue(Double.isNaN(channel.valueAt(4)[1]),
                "the old window survived the scroll");
        assertTrue(Double.isFinite(channel.valueAt(9)[1]),
                "the new anchor has no value");
    }

    /** @return a viewport over those bars, with a range that holds the prices */
    private static Viewport viewportOver(int first, int count) {
        return Viewport.over(new Rectangle(0, 0, 400, 300), first, count, 0.0, 100.0);
    }

    /** Corre uma pintura, que é onde o ajuste acontece. */
    private static void paint(RegressionChannel channel, Viewport viewport) {
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();

        try {
            channel.paintUnder(g, viewport, viewport.firstBar(),
                    viewport.firstBar() + viewport.barCount());
        } finally {
            g.dispose();
        }
    }

    @Test
    @DisplayName("a aparencia da a volta inteira")
    void theappearanceSurvivesTheRoundTrip() {
        RegressionChannel first = new RegressionChannel(120);

        first.setWidth(RegressionChannel.Width.EXTREME);
        first.setDeviations(1.75);
        first.setLine(MovingAverage.Line.DASHED);
        first.setThickness(3);
        first.setColour(new java.awt.Color(0x11, 0x22, 0x33));
        first.setCentreThickness(2);
        first.setFilled(true);
        first.setOpacity(40);

        RegressionChannel second = new RegressionChannel(120);

        second.applyAppearance(first.appearance());

        assertEquals(first.appearance(), second.appearance(),
                "an indicator restored from a layout is not the one that was stored");
        assertEquals(RegressionChannel.Width.EXTREME, second.width(), "the width criterion");
        assertEquals(1.75, second.deviations(), EXACT, "the deviations");
        assertTrue(second.isFilled(), "the shading");
    }
}
