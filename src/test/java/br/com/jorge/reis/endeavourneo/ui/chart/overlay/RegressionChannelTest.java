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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    @DisplayName("um nivel vale para os dois lados, e nao mexe na reta")
    void alevelMirrorsItselfAndLeavesTheCentreAlone() {
        RegressionChannel channel = new RegressionChannel(5);

        channel.setDeviationLevels(java.util.List.of(new RegressionChannel.Level(2.0)));
        channel.calculate(closing(10, 12, 13, 15, 20));

        paint(channel, viewportOver(0, 5));

        double[] here = channel.valueAt(4);

        // [centro, superior, inferior], que e a ordem que colours() promete.
        assertEquals(3, here.length, "a centre and one pair is three lines");
        assertEquals(18.6, here[0], EXACT, "the centre moved with the level");
        assertEquals(18.6 + 2.0 * Math.sqrt(1.02), here[1], EXACT, "two sigmas above");
        assertEquals(18.6 - 2.0 * Math.sqrt(1.02), here[2], EXACT, "two sigmas below");

        // O NUMERO E UM SO: a distancia acima e a de abaixo saem do mesmo 2,
        // que e o que foi pedido -- "sempre colocando apenas um valor tipo 2,
        // e ele replica para -2 tbm".
        assertEquals(here[1] - here[0], here[0] - here[2], EXACT,
                "one number gave two different distances");
    }

    @Test
    @DisplayName("varios niveis saem ordenados, e cada um com o seu traco")
    void severalLevelsComeOutInOrder() {
        RegressionChannel channel = new RegressionChannel(5);

        // Fora de ordem de proposito: quem desenha conta com o mais largo por
        // ultimo, porque e entre ele que o preenchimento vai.
        channel.setDeviationLevels(java.util.List.of(
                new RegressionChannel.Level(3.0, java.awt.Color.RED,
                        MovingAverage.Line.DASHED, 2),
                new RegressionChannel.Level(1.0, java.awt.Color.BLUE,
                        MovingAverage.Line.SOLID, 1)));

        assertEquals(1.0, channel.deviationLevels().get(0).factor(), EXACT, "the narrow one");
        assertEquals(3.0, channel.deviationLevels().get(1).factor(), EXACT, "the wide one");

        assertEquals(5, channel.colours().size(), "a centre and two pairs is five lines");
        assertEquals(5, channel.strokes().size(), "and five pens");

        assertEquals(java.awt.Color.BLUE, channel.colours().get(1), "the narrow pair, above");
        assertEquals(java.awt.Color.BLUE, channel.colours().get(2), "the narrow pair, below");
        assertEquals(java.awt.Color.RED, channel.colours().get(3), "the wide pair, above");
        assertEquals(java.awt.Color.RED, channel.colours().get(4), "the wide pair, below");

        assertNotEquals(channel.strokes().get(1), channel.strokes().get(3),
                "both pairs are drawn with the same pen, so the per-level style does nothing");
    }

    @Test
    @DisplayName("a reta escondida some sem encurtar a lista")
    void thehiddenCentreKeepsItsPlace() {
        RegressionChannel channel = new RegressionChannel(5);

        channel.calculate(closing(10, 12, 13, 15, 20));
        channel.setCentreShown(false);

        paint(channel, viewportOver(0, 5));

        double[] here = channel.valueAt(4);

        assertEquals(3, here.length,
                "the list got shorter, so the legend pairs every colour with the wrong line");
        assertTrue(Double.isNaN(here[0]), "the centre was drawn while hidden");
        assertTrue(Double.isFinite(here[1]), "the edges went with it");
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
        assertTrue(Double.isNaN(channel.valueAt(4)[0]),
                "it answered before anything had been fitted");

        paint(channel, viewportOver(0, 5));

        // A janela é 0..4, e é exatamente o caso da conta à mão.
        assertEquals(18.6, channel.valueAt(4)[0], EXACT, "the centre at the anchor");
        assertEquals(9.4, channel.valueAt(0)[0], EXACT, "the centre at the oldest bar");

        assertTrue(Double.isNaN(channel.valueAt(5)[0]),
                "the line was carried past the bars it was fitted to, which is a forecast "
                        + "and not what this indicator does");

        // E ROLANDO PARA A FRENTE o canal reancora, com outros números.
        paint(channel, viewportOver(5, 5));

        assertEquals(5, channel.fitAt(series, 9).first(), "the window moved with the view");
        assertTrue(Double.isNaN(channel.valueAt(4)[0]),
                "the old window survived the scroll");
        assertTrue(Double.isFinite(channel.valueAt(9)[0]),
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
    @DisplayName("a aparencia da a volta inteira, niveis inclusive")
    void theappearanceSurvivesTheRoundTrip() {
        RegressionChannel first = new RegressionChannel(120);

        first.setWidth(RegressionChannel.Width.EXTREME);
        first.setCentreShown(false);
        first.setCentreLine(MovingAverage.Line.DASHED);
        first.setCentreThickness(3);
        first.setColour(new java.awt.Color(0x11, 0x22, 0x33));
        first.setFilled(true);
        first.setOpacity(40);
        first.setFilledByDirection(true);
        first.setRisingFill(java.awt.Color.GREEN);
        first.setRisingOpacity(35);
        first.setFallingFill(java.awt.Color.RED);
        first.setFallingOpacity(15);
        first.setOwnPeriod("5m");

        first.setDeviationLevels(java.util.List.of(
                new RegressionChannel.Level(1.0, java.awt.Color.RED,
                        MovingAverage.Line.DASHED, 2),
                new RegressionChannel.Level(2.5, null, MovingAverage.Line.SOLID, 4)));

        RegressionChannel second = new RegressionChannel(120);

        second.applyAppearance(first.appearance());

        assertEquals(first.appearance(), second.appearance(),
                "an indicator restored from a layout is not the one that was stored");

        assertEquals(2, second.deviationLevels().size(), "the levels did not survive");
        assertEquals(1.0, second.deviationLevels().get(0).factor(), EXACT, "the first level");
        assertEquals(java.awt.Color.RED, second.deviationLevels().get(0).colour(), "its colour");
        assertEquals(4, second.deviationLevels().get(1).thickness(), "the second's thickness");
        assertNull(second.deviationLevels().get(1).colour(), "its colour is still automatic");

        assertEquals("5m", second.ownPeriod(), "the scale of its own");

        assertFalse(second.isCentreShown(), "the hidden centre");

        assertTrue(second.isFilledByDirection(), "the directional shading");
        assertEquals(java.awt.Color.GREEN, second.risingFill(), "the rising colour");
        assertEquals(35, second.risingOpacity(), "its transparency");
        assertEquals(java.awt.Color.RED, second.fallingFill(), "the falling colour");
        assertEquals(15, second.fallingOpacity(), "and its own");
    }

    @Test
    @DisplayName("a aparencia nao carrega os separadores do arquivo de layout")
    void theappearanceCannotBreakTheLayoutFile() {
        RegressionChannel channel = new RegressionChannel(90);

        channel.setDeviationLevels(java.util.List.of(new RegressionChannel.Level(1.5),
                new RegressionChannel.Level(2.0), new RegressionChannel.Level(3.0)));

        // A barra vertical separa os campos de uma linha do layout e a virgula
        // separa os parametros. Qualquer um dos dois aqui dentro parte a linha.
        assertFalse(channel.appearance().contains("|"),
                "the appearance carries the layout's own field separator: "
                        + channel.appearance());
        assertFalse(channel.appearance().contains(","),
                "the appearance carries the parameter separator: " + channel.appearance());
    }

    // --------------------------------------- o preenchimento por direcao

    /** @return a channel shaded green when it rises and red when it falls */
    private static RegressionChannel painted() {
        RegressionChannel channel = new RegressionChannel(4);

        channel.setFilledByDirection(true);
        channel.setRisingFill(java.awt.Color.GREEN);
        channel.setRisingOpacity(40);
        channel.setFallingFill(java.awt.Color.RED);
        channel.setFallingOpacity(20);

        return channel;
    }

    @Test
    @DisplayName("apontando para cima o canal fica da cor de alta")
    void pointingUpItTakesTheRisingColour() {
        RegressionChannel channel = painted();

        channel.calculate(closing(100, 105, 110, 115));

        paint(channel, viewportOver(0, 4));

        assertTrue(channel.slope() > 0, "the fixture does not rise, so this proves nothing");

        java.awt.Color wash = channel.shading();

        assertEquals(java.awt.Color.GREEN.getRGB() & 0xFFFFFF, wash.getRGB() & 0xFFFFFF,
                "a channel pointing up was not shaded with the rising colour");
        assertEquals(Math.round(255 * 40 / 100f), wash.getAlpha(),
                "the rising side has a transparency of its own and it was not used");
    }

    @Test
    @DisplayName("apontando para baixo ele fica da cor de baixa, com a transparencia dela")
    void pointingDownItTakesTheFallingColour() {
        RegressionChannel channel = painted();

        channel.calculate(closing(115, 110, 105, 100));

        paint(channel, viewportOver(0, 4));

        assertTrue(channel.slope() < 0, "the fixture does not fall, so this proves nothing");

        java.awt.Color wash = channel.shading();

        assertEquals(java.awt.Color.RED.getRGB() & 0xFFFFFF, wash.getRGB() & 0xFFFFFF,
                "a channel pointing down was not shaded with the falling colour");
        assertEquals(Math.round(255 * 20 / 100f), wash.getAlpha(),
                "the two sides were shaded with the same transparency, so one of the two "
                        + "sliders does nothing");
    }

    @Test
    @DisplayName("por direcao substitui o preenchimento de uma cor so")
    void bydirectionReplacesThePlainFill() {
        RegressionChannel channel = painted();

        // O de uma cor só, ligado e com outra cor: se os dois valessem, o
        // reader veria um deles e não saberia qual.
        channel.setFilled(true);
        channel.setFillColour(java.awt.Color.BLUE);
        channel.setOpacity(90);

        channel.calculate(closing(100, 105, 110, 115));

        paint(channel, viewportOver(0, 4));

        assertEquals(java.awt.Color.GREEN.getRGB() & 0xFFFFFF,
                channel.shading().getRGB() & 0xFFFFFF,
                "the plain fill won over the directional one");
    }

    @Test
    @DisplayName("desligado, volta a ser o de uma cor so")
    void offitGoesBackToOneColour() {
        RegressionChannel channel = painted();

        channel.setFilledByDirection(false);
        channel.setFilled(true);
        channel.setFillColour(java.awt.Color.BLUE);
        channel.setOpacity(90);

        channel.calculate(closing(100, 105, 110, 115));

        paint(channel, viewportOver(0, 4));

        assertEquals(java.awt.Color.BLUE.getRGB() & 0xFFFFFF,
                channel.shading().getRGB() & 0xFFFFFF, "the plain fill did not come back");
    }

    @Test
    @DisplayName("por direcao dispensa a outra caixa")
    void bydirectionNeedsNoOtherBox() {
        RegressionChannel channel = painted();

        // setFilled continua FALSO. Pedir a cor por direcao e depois ter de
        // marcar uma segunda caixa noutra aba para algo aparecer e uma
        // armadilha, e das que so o leitor que cai nela encontra.
        channel.calculate(closing(100, 105, 110, 115));

        paint(channel, viewportOver(0, 4));

        assertNotNull(channel.shading(),
                "nothing was shaded: asking for a colour by direction and getting no shading "
                        + "until a second box is ticked somewhere else is a trap");
    }

    @Test
    @DisplayName("sem nivel nenhum nao ha o que preencher")
    void withoutALevelThereIsNothingToFill() {
        RegressionChannel channel = painted();

        channel.setDeviationLevels(java.util.List.of());
        channel.calculate(closing(100, 105, 110, 115));

        paint(channel, viewportOver(0, 4));

        assertNull(channel.shading(),
                "it shaded between edges that are not drawn, which is a band with no sides");
    }

    // ------------------------------------------------- a escala propria

    /**
     * Trinta barras de um minuto, e as de cinco NAO ficam numa reta.
     *
     * <p><b>É esta a parte que importa, e a primeira versão errou.</b> A série
     * subia um por minuto, então qualquer janela de três barras grandes dava a
     * mesma reta — e a prova de dentes saiu verde: ancorar na barra que ainda
     * estava se formando, que é exatamente o defeito que estes testes existem
     * para pegar, não mudava número nenhum. Uma fixture que é uma reta não
     * consegue ver uma janela deslocada.</p>
     *
     * <p>Com o degrau, os fechamentos de cinco minutos são
     * {@code 10, 20, 30, 70, 80, 90} — e cada janela de três dá uma reta
     * diferente.</p>
     */
    private static PriceSeries minutes() {
        return new PriceSeries() {

            @Override
            public int size() {
                return 30;
            }

            @Override
            public long timeAt(int index) {
                return java.time.LocalDateTime.of(2026, 9, 2, 9, 0).plusMinutes(index)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            }

            @Override
            public double openAt(int index) {
                return closeAt(index);
            }

            @Override
            public double highAt(int index) {
                return closeAt(index);
            }

            @Override
            public double lowAt(int index) {
                return closeAt(index);
            }

            @Override
            public double closeAt(int index) {
                return (index + 1) * 2.0 + (index < 15 ? 0.0 : 30.0);
            }
        };
    }

    /** @return a channel of three five-minute bars, painted over the whole series */
    private static RegressionChannel onFiveMinutes(boolean sloped) {
        RegressionChannel channel = new RegressionChannel(3);

        channel.setOwnPeriod("5m");
br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.setInterpolateOwnScale(sloped);
        channel.calculate(minutes());

        paint(channel, viewportOver(0, 30));

        return channel;
    }

    /**
     * Nada é desenhado antes de a primeira barra grande ter FECHADO.
     *
     * <p>É a armadilha que o {@code OwnScale} existe para escrever uma vez só:
     * a barra de cinco minutos que contém 09:03 só fecha às 09:05, e usá-la
     * antes disso é ler o futuro.</p>
     */
    @Test
    @DisplayName("na escala propria, nada aparece antes de a barra grande fechar")
    void onitsOwnScaleNothingComesBeforeTheClose() {
        RegressionChannel channel = onFiveMinutes(false);

        for (int bar = 0; bar < 10; bar++) {
            assertTrue(Double.isNaN(channel.valueAt(bar)[0]),
                    "bar " + bar + " drew a channel fitted over five-minute bars that had "
                            + "not all closed yet, which is reading the future");
        }
    }

    /**
     * O ajuste é o das barras grandes, e ancorado na última FECHADA.
     *
     * <p>Conta à mão. Os fechamentos de cinco minutos são
     * {@code 10, 20, 30, 70, 80, 90}, e às 09:29 a última barra grande fechada
     * é a quinta — a sexta só fecha às 09:30. A janela de três termina nela:
     * {@code 30, 70, 80} em x de 0 a 2.</p>
     *
     * <pre>
     *   Sx=3  Sy=180  Sxy=230  Sxx=5
     *   inclinação = (3·230 − 3·180) / (3·5 − 3²) = 150/6 = 25
     *   intercepto = (180 − 25·3)/3 = 35
     *   reta       = 35 (barra 2)  60 (barra 3)  85 (barra 4)
     * </pre>
     *
     * <p>Ancorando na barra que ainda se forma — o defeito — a janela vira
     * {@code 70, 80, 90}, a inclinação vira 10 e a reta na barra 3 vira 70.</p>
     */
    @Test
    @DisplayName("o ajuste e o das barras grandes, ancorado na ultima FECHADA")
    void onitsOwnScaleTheFitIsTheCoarseOne() {
        RegressionChannel channel = onFiveMinutes(false);

        assertEquals(25.0, channel.slope(), EXACT,
                "not the slope of the window that ends at the last CLOSED five-minute bar: "
                        + "either the fold did not happen, or the anchor is the bar still "
                        + "forming");

        assertEquals(60.0, channel.valueAt(24)[0], EXACT,
                "bar 24 is 09:24, and the last five-minute bar closed by then is the fourth, "
                        + "where the fitted line reads 60");
    }

    @Test
    @DisplayName("trocar a escala com o grafico aberto vale no quadro seguinte")
    void changingTheScaleTakesEffectAtOnce() {
        RegressionChannel channel = new RegressionChannel(3);

        channel.calculate(minutes());

        paint(channel, viewportOver(0, 30));

        // Nas barras do gráfico: os três últimos fechamentos são 86, 88, 90.
        assertEquals(2.0, channel.slope(), EXACT, "two points a minute");

        // Sem outro calculate: é o caso real -- o diálogo troca a escala e o
        // gráfico repinta.
        channel.setOwnPeriod("5m");

        paint(channel, viewportOver(0, 30));

        assertEquals(25.0, channel.slope(), EXACT,
                "the scale was chosen with the chart already open and nothing changed until "
                        + "the next series arrived");
    }

    /**
     * Devolve o ajuste ao padrão.
     *
     * <p>É um ajuste do gráfico, e portanto estático: um teste que o liga e não
     * o desliga muda o resultado do teste seguinte, e de uma classe que nem
     * sabe que ele existe. A suíte inteira roda numa JVM só.</p>
     */
    @org.junit.jupiter.api.AfterEach
    void putTheSettingBack() {
        br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.setInterpolateOwnScale(false);
    }
}
