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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O canal por toques, conferido contra um desenho feito no papel.
 *
 * <h2>A serra que os testes usam</h2>
 *
 * <p>Vinte e uma barras, alternando pico e vale, com os picos EXATAMENTE sobre
 * {@code y = 200 − 2x} e os vales exatamente sobre {@code y = 160 − 2x}. Ou
 * seja: um canal descendente de inclinação −2 e largura 40, com todos os pivôs
 * encostados nas bordas — a resposta é conhecida antes de o algoritmo rodar.</p>
 *
 * <pre>
 *   barra    0     1     2     3    ...    18    19    20
 *   pico          198         194   ...          162
 *   vale    160         156         ...   124          120
 * </pre>
 *
 * <p>A perna do zigzag é 1, e o fractal não pode olhar a barra 0 (sem vizinha à
 * esquerda) nem a 20 (sem vizinha à direita), então os pivôs são <b>10 topos</b>
 * nas ímpares de 1 a 19 e <b>9 fundos</b> nas pares de 2 a 18 — dezenove ao
 * todo, e a amplitude deles é 198 − 124 = <b>74</b>. Com a tolerância em 5%, a
 * banda é de <b>3,7</b>: um número que os testes usam para escolher de que lado
 * do limiar empurrar um pivô.</p>
 */
@DisplayName("Canal por toques")
class TouchChannelTest {

    private static final double EXACT = 1e-9;

    /** A amplitude dos pivôs da serra: 198 no topo da barra 1, 124 no fundo da 18. */
    private static final double AMPLITUDE = 74.0;

    /**
     * @param movedTop qual pico sai da linha, ou −1 para nenhum
     * @param by quanto ele desce
     * @return a serra de vinte e uma barras
     */
    private static PriceSeries saw(int movedTop, double by) {
        int count = 21;
        double[] highs = new double[count];
        double[] lows = new double[count];

        for (int i = 0; i < count; i++) {
            if (i % 2 == 1) {
                // Pico: a máxima está na linha de cima, e a mínima dele fica
                // acima da linha de baixo para não virar fundo.
                highs[i] = 200 - 2.0 * i - (i == movedTop ? by : 0.0);
                lows[i] = 195 - 2.0 * i;
            } else {
                // Vale: a mínima está na linha de baixo, e a máxima dele fica
                // abaixo das máximas vizinhas para não virar topo.
                lows[i] = 160 - 2.0 * i;
                highs[i] = 165 - 2.0 * i;
            }
        }

        return new PriceSeries() {

            @Override
            public int size() {
                return count;
            }

            @Override
            public long timeAt(int index) {
                return 1_600_000_000_000L + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return lows[index];
            }

            @Override
            public double highAt(int index) {
                return highs[index];
            }

            @Override
            public double lowAt(int index) {
                return lows[index];
            }

            @Override
            public double closeAt(int index) {
                return highs[index];
            }
        };
    }

    private static PriceSeries saw() {
        return saw(-1, 0);
    }

    private static TouchChannel channel() {
        TouchChannel channel = new TouchChannel(90);

        channel.setWing(1);

        return channel;
    }

    @Test
    @DisplayName("acha o canal que esta desenhado na serie, com os numeros exatos")
    void itfindsTheChannelThatIsThere() {
        TouchChannel.Fit fit = channel().fitAt(saw(), 20);

        assertNotNull(fit, "no channel was found in a series that is one");

        assertEquals(-2.0, fit.slope(), EXACT, "the slope");
        assertEquals(40.0, fit.width(), EXACT, "the distance between the edges");

        // As bordas, lidas nas duas pontas da janela.
        assertEquals(200.0, fit.upperAt(0), EXACT, "the upper edge at bar 0");
        assertEquals(160.0, fit.lowerAt(0), EXACT, "the lower edge at bar 0");
        assertEquals(162.0, fit.upperAt(19), EXACT, "the upper edge at bar 19");

        assertEquals(10, fit.touchesAbove(), "every top rests on the upper edge");
        assertEquals(9, fit.touchesBelow(), "every bottom rests on the lower edge");
    }

    /**
     * A tolerância vem da amplitude dos pivôs, e não da largura do canal.
     *
     * <p>É a armadilha que a classe descreve: uma tolerância proporcional à
     * largura faz alargar valer a pena — canal mais largo, banda mais larga,
     * mais pivôs dentro, pontuação melhor — e a busca caminha para um canal do
     * tamanho da tela. Aqui a banda é 5% de 74, ou seja 3,7, e este teste
     * empurra um pico para os dois lados desse limiar.</p>
     */
    @Test
    @DisplayName("a banda e 5% da amplitude dos pivos, e o limiar esta onde deveria")
    void thebandComesFromTheSwingAndNotFromTheChannel() {
        double band = AMPLITUDE * TouchChannel.TOLERANCE / 100.0;

        assertEquals(3.7, band, EXACT, "the fixture's numbers moved; the rest of this test "
                + "chose which side of 3,7 to push a pivot to");

        // Dentro da banda: continua contando.
        TouchChannel.Fit near = channel().fitAt(saw(9, 3.0), 20);

        assertEquals(10, near.touchesAbove(),
                "a top three points off the edge stopped counting, and the band is 3,7");

        // Fora dela: para de contar, e a borda NAO se mexe -- ela e o pico mais
        // alto, e esse continua onde estava.
        TouchChannel.Fit far = channel().fitAt(saw(9, 5.0), 20);

        assertEquals(9, far.touchesAbove(),
                "a top five points off the edge went on counting, and the band is 3,7");
        assertEquals(-2.0, far.slope(), EXACT, "one top off the line changed the slope");
        assertEquals(200.0, far.upperAt(0), EXACT,
                "the upper edge followed the top that left it, instead of staying on the "
                        + "highest one");
    }

    /**
     * Nenhum topo fica acima da borda de cima — e se isso não puder ser
     * conseguido, não sai canal nenhum.
     *
     * <p>A primeira versão do javadoc desta classe dizia que a busca de
     * inclinação "absorve" o pico solitário. Este teste mostrou que nem sempre:
     * um topo trinta pontos acima de todos fica sozinho na borda dele, e não há
     * inclinação que o faça dividir essa borda com um segundo topo enquanto
     * dois fundos ainda dividem a outra. O indicador então não desenha — que é
     * melhor do que desenhar um espeto com uma reta embaixo e chamar o par de
     * canal.</p>
     */
    @Test
    @DisplayName("nenhum topo fica acima da borda, e um espeto pode nao dar canal")
    void notopSitsAboveTheUpperEdge() {
        TouchChannel.Fit fit = channel().fitAt(saw(), 20);

        for (TopsAndBottoms.Pivot each : TopsAndBottoms.alternating(
                TopsAndBottoms.candidates(saw(), 1, TopsAndBottoms.Ties.LAST, 21))) {

            double edge = each.top() ? fit.upperAt(each.bar()) : fit.lowerAt(each.bar());

            assertTrue(each.top() ? each.price() <= edge + EXACT
                            : each.price() >= edge - EXACT,
                    "the pivot at bar " + each.bar() + " is outside the channel it is "
                            + "supposed to define: " + each.price() + " against an edge at "
                            + edge);
        }

        assertNull(channel().fitAt(saw(9, -30.0), 20),
                "a single spike thirty points above every other top produced a channel "
                        + "anyway, which means one of its edges is resting on that spike "
                        + "alone");
    }

    @Test
    @DisplayName("duas bordas com pelo menos dois toques cada, senao nao e canal")
    void bothEdgesNeedTwoTouchesEach() {
        TouchChannel.Fit fit = channel().fitAt(saw(), 20);

        assertTrue(fit.touchesAbove() >= 2 && fit.touchesBelow() >= 2, "the fixture's own");

        // Uma serie que so sobe: o fractal nao acha topo nenhum confirmado com
        // vizinhos dos dois lados, entao nao ha o que apoiar.
        PriceSeries rising = new PriceSeries() {

            @Override
            public int size() {
                return 30;
            }

            @Override
            public long timeAt(int index) {
                return 1_600_000_000_000L + index * 60_000L;
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

        assertNull(channel().fitAt(rising, 29),
                "a series with no turns produced a channel, which means the two-touch floor "
                        + "is not being applied");
    }

    @Test
    @DisplayName("a janela anda com a ancora, e fora dela nao ha linha")
    void thewindowFollowsTheAnchor() {
        TouchChannel narrow = new TouchChannel(10);

        narrow.setWing(1);

        TouchChannel.Fit fit = narrow.fitAt(saw(), 20);

        assertNotNull(fit, "ten bars hold five turns, which is enough for a channel");
        assertEquals(11, fit.first(), "the window did not start ten bars before the anchor");
        assertEquals(20, fit.anchor(), "the anchor moved");
    }

    @Test
    @DisplayName("sem serie, sem ancora valida, sem canal")
    void withoutAnythingToFitThereIsNone() {
        assertNull(channel().fitAt(null, 5), "it fitted a channel to nothing");
        assertNull(channel().fitAt(saw(), 0), "one bar is not a channel");
        assertNull(channel().fitAt(saw(), 999), "it fitted past the end of the series");
    }

    @Test
    @DisplayName("valueAt devolve as duas bordas, e NaN fora da janela")
    void valueatGivesTheTwoEdges() {
        TouchChannel channel = channel();
        PriceSeries series = saw();

        channel.calculate(series);

        assertTrue(Double.isNaN(channel.valueAt(10)[0]),
                "it answered before anything had been fitted");

        // A pintura e onde o ajuste acontece; aqui basta o mesmo caminho.
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(400, 300,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();

        try {
            channel.paintUnder(g, br.com.jorge.reis.endeavourneo.ui.chart.Viewport.over(
                    new java.awt.Rectangle(0, 0, 400, 300), 0, 21, 100.0, 220.0), 0, 21);
        } finally {
            g.dispose();
        }

        assertEquals(200.0, channel.valueAt(0)[0], EXACT, "the upper edge at bar 0");
        assertEquals(160.0, channel.valueAt(0)[1], EXACT, "the lower edge there");
        assertEquals(2, channel.valueAt(0).length, "a channel is two lines");

        assertTrue(Double.isNaN(channel.valueAt(999)[0]),
                "the channel was carried past the bars it rests on, which is a forecast");
    }

    @Test
    @DisplayName("a aparencia da a volta inteira")
    void theappearanceSurvivesTheRoundTrip() {
        TouchChannel first = new TouchChannel(120);

        first.setTolerance(9);
        first.setTies(TopsAndBottoms.Ties.STRICT);
        first.setLine(MovingAverage.Line.DASHED);
        first.setThickness(3);
        first.setUpperColour(new java.awt.Color(0x11, 0x22, 0x33));
        first.setLowerColour(new java.awt.Color(0x44, 0x55, 0x66));
        first.setFilled(true);
        first.setOpacity(35);

        TouchChannel second = new TouchChannel(120);

        second.applyAppearance(first.appearance());

        assertEquals(first.appearance(), second.appearance(),
                "an indicator restored from a layout is not the one that was stored");
        assertEquals(9, second.tolerance(), "the tolerance");
        assertEquals(TopsAndBottoms.Ties.STRICT, second.ties(), "the tie rule");
        assertTrue(second.isFilled(), "the shading");
    }

    @Test
    @DisplayName("os limites dos ajustes seguram")
    void thesettingsAreHeldInRange() {
        TouchChannel channel = new TouchChannel(5);

        assertEquals(10, channel.period(), "the floor of the window");

        channel.setPeriod(9_999);
        assertEquals(TouchChannel.MOST_BARS, channel.period(), "its ceiling");

        channel.setTolerance(0);
        assertEquals(1, channel.tolerance(), "the floor of the tolerance");

        channel.setTolerance(99);
        assertEquals(25, channel.tolerance(), "its ceiling");
    }
}
