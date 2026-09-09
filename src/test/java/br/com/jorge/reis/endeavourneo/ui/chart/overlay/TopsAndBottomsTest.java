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
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Topos e fundos — o zigzag alternado, conferido à mão.
 *
 * <h2>O trecho que os testes usam</h2>
 *
 * <p>Onze barras, com as máximas e as mínimas escolhidas para que os dois
 * passos possam ser contados no papel. Perna 1, então o fractal é de três
 * candles e a barra {@code i} é candidata quando bate as duas vizinhas:</p>
 *
 * <pre>
 *   barra    0    1    2    3    4    5    6    7    8    9   10
 *   máxima  10   14   12   16   13   11   18   15   17   19   16
 *   mínima   8    9    7   11    6    5   12   10   13   14   11
 *
 *   topos     :  1 (14)  3 (16)  6 (18)  9 (19)      -- e a 8? 17 < 19, mas
 *                                                       17 > 15 e 17 < 19: não
 *   fundos    :  2 (7)   5 (5)   7 (10)
 * </pre>
 *
 * <p>Brutos em ordem: T1 F2 T3 F5 T6 F7 T9. Já alternam, o que é de
 * propósito — o teste da alternância usa um trecho onde NÃO alternam, para que
 * o passo 2 tenha o que fazer.</p>
 */
@DisplayName("Topos e fundos")
class TopsAndBottomsTest {

    private static final double EXACT = 1e-12;

    private static final double[] HIGHS = {10, 14, 12, 16, 13, 11, 18, 15, 17, 19, 16};

    private static final double[] LOWS = {8, 9, 7, 11, 6, 5, 12, 10, 13, 14, 11};

    /** @return a series with exactly those highs and lows, a minute apart */
    private static PriceSeries bars(double[] highs, double[] lows) {
        return new PriceSeries() {

            @Override
            public int size() {
                return highs.length;
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

    private static String shape(List<TopsAndBottoms.Pivot> pivots) {
        StringBuilder text = new StringBuilder();

        for (TopsAndBottoms.Pivot each : pivots) {
            text.append(each.top() ? 'T' : 'F').append(each.bar()).append(' ');
        }

        return text.toString().trim();
    }

    // -------------------------------------------------- passo 1: o candidato

    @Test
    @DisplayName("o fractal acha o que a conta a mao acha")
    void thefractalFindsWhatTheHandCountFinds() {
        List<TopsAndBottoms.Pivot> raw = TopsAndBottoms.candidates(
                bars(HIGHS, LOWS), 1, TopsAndBottoms.Ties.STRICT);

        assertEquals("T1 F2 T3 F5 T6 F7 T9", shape(raw),
                "the fractal did not find the pivots counted by hand");

        assertEquals(14.0, raw.get(0).price(), EXACT, "a top carries the HIGH of its bar");
        assertEquals(7.0, raw.get(1).price(), EXACT, "a bottom carries the LOW of its bar");
    }

    /**
     * A defasagem é o método, e é a única coisa que ela pode ser.
     *
     * <p>A candidata da barra {@code i} só se conhece na barra {@code i+N}, e
     * uma implementação que olhasse as barras seguintes antes de elas fecharem
     * saberia o futuro. Aqui o laço para {@code wing} barras antes do fim, e o
     * teste tranca isso: a última barra da série nunca é candidata.</p>
     */
    @Test
    @DisplayName("nada e afirmado sobre as ultimas barras da perna")
    void nothingIsSaidAboutTheLastBarsOfTheWing() {
        // Uma serie que sobe sempre: a ultima barra e a maxima de todas, e
        // ainda assim nao pode ser topo -- nao ha barra depois dela.
        double[] rising = {1, 2, 3, 4, 5};

        List<TopsAndBottoms.Pivot> raw = TopsAndBottoms.candidates(
                bars(rising, rising), 1, TopsAndBottoms.Ties.STRICT);

        for (TopsAndBottoms.Pivot each : raw) {
            assertTrue(each.bar() <= rising.length - 1 - 1,
                    "bar " + each.bar() + " was called a pivot with no wing to its right: "
                            + "the confirmation looked at bars that had not closed");
        }

        // E com perna 2 a fronteira anda junto.
        for (TopsAndBottoms.Pivot each : TopsAndBottoms.candidates(
                bars(rising, rising), 2, TopsAndBottoms.Ties.STRICT)) {

            assertTrue(each.bar() <= rising.length - 1 - 2,
                    "the wing grew and the boundary did not follow it");
        }
    }

    @Test
    @DisplayName("sozinho, o passo 1 nao alterna")
    void ontheirOwnTheCandidatesDoNotAlternate() {
        // Dois topos seguidos sem fundo confirmado entre eles: 1 e 3 sao topos,
        // e a 2 nao e fundo porque a minima dela nao bate as duas vizinhas.
        double[] highs = {10, 14, 12, 16, 11};
        double[] lows = {1, 2, 3, 4, 0};

        List<TopsAndBottoms.Pivot> raw = TopsAndBottoms.candidates(
                bars(highs, lows), 1, TopsAndBottoms.Ties.STRICT);

        assertEquals("T1 T3", shape(raw),
                "the fixture no longer shows two tops in a row, so the test below proves "
                        + "nothing about the reduction");
    }

    // ------------------------------------------------ passo 2: a alternancia

    @Test
    @DisplayName("dois topos seguidos viram um: o mais alto")
    void twoTopsInARowBecomeTheHigher() {
        List<TopsAndBottoms.Pivot> raw = List.of(
                new TopsAndBottoms.Pivot(1, true, 14),
                new TopsAndBottoms.Pivot(3, true, 16),
                new TopsAndBottoms.Pivot(5, false, 5),
                new TopsAndBottoms.Pivot(7, false, 3),
                new TopsAndBottoms.Pivot(9, true, 20));

        assertEquals("T3 F7 T9", shape(TopsAndBottoms.alternating(raw)),
                "the reduction did not keep the most extreme of each run");
    }

    @Test
    @DisplayName("o menos extremo nao desloca o pivo")
    void theLessExtremeOneDoesNotMoveThePivot() {
        List<TopsAndBottoms.Pivot> raw = List.of(
                new TopsAndBottoms.Pivot(1, true, 16),
                new TopsAndBottoms.Pivot(3, true, 14),
                new TopsAndBottoms.Pivot(5, false, 5));

        assertEquals("T1 F5", shape(TopsAndBottoms.alternating(raw)),
                "a lower top replaced a higher one, so the leg slid forward for a reason "
                        + "the price never gave");
    }

    @Test
    @DisplayName("um topo igual ao pivo tambem nao o desloca")
    void atopLevelWithThePivotDoesNotMoveItEither() {
        List<TopsAndBottoms.Pivot> raw = List.of(
                new TopsAndBottoms.Pivot(1, true, 16),
                new TopsAndBottoms.Pivot(3, true, 16),
                new TopsAndBottoms.Pivot(5, false, 5));

        assertEquals("T1 F5", shape(TopsAndBottoms.alternating(raw)),
                "a top level with the pivot is not more extreme than it, and moving the "
                        + "pivot onto it slides the end of the leg");
    }

    // ----------------------------------------------------- as duas regras de empate

    /**
     * O empate: a divergência medida entre as duas implementações.
     *
     * <p>Um platô de máximas iguais. Pela regra estrita não há topo nenhum —
     * nenhuma das barras é a maior sozinha. Pela regra do produto de
     * referência, a primeira do platô leva.</p>
     */
    @Test
    @DisplayName("no plato, estrito nao acha topo e o do profit acha o ULTIMO")
    void onaPlateauTheTwoRulesPart() {
        double[] highs = {10, 15, 15, 15, 11};
        double[] lows = {1, 2, 3, 4, 0};

        assertEquals("", shape(TopsAndBottoms.candidates(
                        bars(highs, lows), 1, TopsAndBottoms.Ties.STRICT)),
                "the strict rule found a top on a plateau, where no single bar is the "
                        + "highest");

        assertEquals("T3", shape(TopsAndBottoms.candidates(
                        bars(highs, lows), 1, TopsAndBottoms.Ties.LAST)),
                "the reference product's rule marks the CLOSING bar of a plateau -- a bar "
                        + "may tie with its past and must beat its future, and only the last "
                        + "one beats the bar after it");
    }

    @Test
    @DisplayName("no fundo o empate vale igual, do lado de baixo")
    void thesameHoldsForABottom() {
        double[] highs = {10, 9, 8, 7, 11};
        double[] lows = {5, 2, 2, 2, 6};

        assertEquals("", shape(TopsAndBottoms.candidates(
                bars(highs, lows), 1, TopsAndBottoms.Ties.STRICT)), "the strict rule");

        assertEquals("F3", shape(TopsAndBottoms.candidates(
                bars(highs, lows), 1, TopsAndBottoms.Ties.LAST)), "the other one");
    }

    // ------------------------------------------------------------ o desenho

    @Test
    @DisplayName("a perna e uma reta de um pivo ao outro")
    void thelegRunsStraightFromOnePivotToTheNext() {
        TopsAndBottoms pivots = new TopsAndBottoms(1);

        pivots.calculate(bars(HIGHS, LOWS));

        // T1 em 14, F2 em 7: a perna cobre uma barra so, entao as duas pontas
        // sao os proprios pivos.
        assertEquals(14.0, pivots.valueAt(1)[0], EXACT, "the top");
        assertEquals(7.0, pivots.valueAt(2)[0], EXACT, "the bottom after it");

        // F2 (7) ate T3 (16): tambem uma barra. F5 (5) ate T6 (18): idem.
        assertEquals(16.0, pivots.valueAt(3)[0], EXACT, "the next top");

        // T3 (16) ate F5 (5) cobre DUAS barras, entao a 4 fica no meio.
        assertEquals(10.5, pivots.valueAt(4)[0], EXACT,
                "the leg does not run straight between its two ends");
    }

    @Test
    @DisplayName("antes do primeiro pivo e depois do ultimo nao ha linha")
    void beforeTheFirstAndAfterTheLastThereIsNoLine() {
        TopsAndBottoms pivots = new TopsAndBottoms(1);

        pivots.calculate(bars(HIGHS, LOWS));

        assertTrue(Double.isNaN(pivots.valueAt(0)[0]),
                "it drew a leg before the first pivot");

        // O ultimo pivo e T9. A barra 10 nao tem nada confirmado depois dela,
        // e ligar ate ela desenharia uma perna rumo a um pivo que ainda nao
        // aconteceu -- que e o que a defasagem da perna significa.
        assertTrue(Double.isNaN(pivots.valueAt(10)[0]),
                "the line ran on past the last confirmed pivot, towards a turn that has "
                        + "not happened");
    }

    @Test
    @DisplayName("fora da serie nao estoura")
    void outsideTheSeriesItDoesNotThrow() {
        TopsAndBottoms pivots = new TopsAndBottoms(1);

        pivots.calculate(bars(HIGHS, LOWS));

        assertTrue(Double.isNaN(pivots.valueAt(-1)[0]), "before the series");
        assertTrue(Double.isNaN(pivots.valueAt(9_999)[0]), "past the end of it");
    }

    // ------------------------------------------------------- a escala propria

    /**
     * @param count quantas barras de um minuto
     * @return uma serie de minutos com um extremo plantado em cada hora
     *
     * <p>A máxima sobe cinco a cada minuto, e o minuto 30 de cada hora leva um
     * pico de mais 500 — então a máxima da hora é sempre a do minuto 30 dela, e
     * o teste sabe onde o vértice tem de cair.</p>
     */
    private static PriceSeries minutesWithHourlyPeaks(int count) {
        return minutesWithHourlyPeaks(count, 0.0);
    }

    /**
     * @param tail quanto somar às máximas da ÚLTIMA hora, que está em formação
     *
     * <p>É com isto que se pergunta se a hora incompleta foi lida: mexer nela
     * não pode mudar nada do que já está desenhado.</p>
     */
    private static PriceSeries minutesWithHourlyPeaks(int count, double tail) {
        double[] highs = new double[count];
        double[] lows = new double[count];

        for (int i = 0; i < count; i++) {
            // TRES ESCALAS DE PROPOSITO, e cada uma responde por um teste:
            //
            //  - a hora sobe e desce alternada (+300 nas impares), senao a
            //    serie dobrada sobe sempre e nao tem giro NENHUM para achar;
            //  - o minuto oscila (+-3), senao a escala do minuto acha
            //    exatamente os mesmos pivos da hora e compara-los nao compara
            //    nada;
            //  - e o extremo de cada hora fica plantado no minuto 30 (maxima)
            //    e no 45 (minima), com folga de uma ordem sobre os dois
            //    anteriores, para o teste saber em que barra o vertice tem de
            //    cair.
            double base = 100 + ((i / 60) % 2 == 0 ? 0 : 300) + i * 0.05;

            boolean lastHour = i / 60 == (count - 1) / 60;

            highs[i] = base + (i % 2 == 0 ? 3 : 0) + (i % 60 == 30 ? 60 : 0)
                    + (lastHour ? tail : 0.0);
            lows[i] = base - (i % 2 == 0 ? 3 : 0) - (i % 60 == 45 ? 60 : 0);
        }

        return new PriceSeries() {

            @Override
            public int size() {
                return count;
            }

            @Override
            public long timeAt(int index) {
                // Comeca numa hora cheia, para as horas dobradas caírem certas.
                return 1_600_000_000_000L / 3_600_000L * 3_600_000L + index * 60_000L;
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

    /**
     * O vértice cai na barra que FEZ o extremo.
     *
     * <p>Pôr o vértice no começo ou no fim da barra dobrada desenharia um giro
     * num preço que nenhuma barra dali negociou. A máxima da hora aconteceu num
     * minuto específico, e é nele que o olho do leitor vai.</p>
     */
    @Test
    @DisplayName("na escala propria o vertice cai na barra que fez o extremo")
    void onitsOwnScaleTheVertexLandsOnTheBarThatMadeIt() {
        TopsAndBottoms pivots = new TopsAndBottoms(1);

        pivots.setOwnPeriod("1h");

        PriceSeries minutes = minutesWithHourlyPeaks(60 * 6);

        pivots.calculate(minutes);

        assertFalse(pivots.pivots().isEmpty(),
                "the hourly zigzag found no turn at all in six hours");

        for (TopsAndBottoms.Pivot each : pivots.pivots()) {
            int minute = each.bar() % 60;

            assertTrue(minute == 30 || minute == 45,
                    "a vertex landed on minute " + minute + " of its hour, where no extreme "
                            + "of that hour happened: the pivot was put at the folded bar's "
                            + "edge instead of at the bar that made it");

            double made = each.top() ? minutes.highAt(each.bar()) : minutes.lowAt(each.bar());

            assertEquals(made, each.price(), EXACT,
                    "the pivot's price is not the price of the bar it was put on");
        }
    }

    /**
     * A última barra dobrada fica de fora, e é para isso que este cuidado
     * existe.
     *
     * <p>Uma barra de uma hora lida aos três minutos tem a máxima de três
     * minutos. Se ela entrar na conta, um giro aparece na tela antes de o
     * mercado o ter feito — e não basta descartar pivôs achados nela, porque a
     * JANELA da barra anterior também a lê.</p>
     */
    @Test
    @DisplayName("mexer na hora em formacao nao muda nada do que ja esta desenhado")
    void thestillFormingHourSaysNothing() {
        TopsAndBottoms quiet = new TopsAndBottoms(1);
        TopsAndBottoms spiked = new TopsAndBottoms(1);

        quiet.setOwnPeriod("1h");
        spiked.setOwnPeriod("1h");

        // Seis horas cheias mais quarenta minutos da setima. Nos dois casos a
        // setima esta em formacao; num deles ela dispara mil pontos.
        //
        // NADA pode mudar. Tudo o que esta na tela foi decidido antes dela, e
        // a maxima que ela tem ate agora nao e a maxima dela -- o preco ainda
        // pode subir mais dentro da hora. Se um pivo depende dessa barra, ele
        // apareceu antes de o mercado o ter feito.
        //
        // E nao basta descartar pivos ACHADOS nela: a janela da hora anterior
        // tambem a le, e e por ai que o futuro entra.
        quiet.calculate(minutesWithHourlyPeaks(60 * 6 + 40));
        spiked.calculate(minutesWithHourlyPeaks(60 * 6 + 40, 1_000));

        assertEquals(shape(quiet.pivots()), shape(spiked.pivots()),
                "a thousand points inside the hour that has not closed changed the pivots, "
                        + "so that hour is being read");
    }

    @Test
    @DisplayName("uma escala que esta versao nao conhece cai na do grafico")
    void anunknownScaleFallsBackToTheChartsOwn() {
        TopsAndBottoms chart = new TopsAndBottoms(1);
        TopsAndBottoms unknown = new TopsAndBottoms(1);

        unknown.setOwnPeriod("nao-existe-esta-escala");

        chart.calculate(bars(HIGHS, LOWS));
        unknown.calculate(bars(HIGHS, LOWS));

        assertEquals(shape(chart.pivots()), shape(unknown.pivots()),
                "a scale this version cannot build left the indicator drawing nothing, "
                        + "which is worse than drawing it on the chart's own bars");
    }

    @Test
    @DisplayName("a escala propria muda o que se ve")
    void theownScaleChangesWhatIsDrawn() {
        TopsAndBottoms chart = new TopsAndBottoms(1);
        TopsAndBottoms hourly = new TopsAndBottoms(1);

        hourly.setOwnPeriod("1h");

        PriceSeries minutes = minutesWithHourlyPeaks(60 * 6);

        chart.calculate(minutes);
        hourly.calculate(minutes);

        assertNotEquals(shape(chart.pivots()), shape(hourly.pivots()),
                "the hourly zigzag came out the same as the minute one, so the scale was "
                        + "asked for and not used");

        assertTrue(hourly.pivots().size() < chart.pivots().size(),
                "an hourly zigzag over six hours has more turns than a minute one, which "
                        + "cannot be: " + hourly.pivots().size() + " against "
                        + chart.pivots().size());
    }

    @Test
    @DisplayName("a aparencia da a volta inteira")
    void theappearanceSurvivesTheRoundTrip() {
        TopsAndBottoms first = new TopsAndBottoms(7);

        first.setTies(TopsAndBottoms.Ties.LAST);
        first.setLine(MovingAverage.Line.DASH_DOT);
        first.setThickness(4);
        first.setColour(new java.awt.Color(0x11, 0x22, 0x33));

        TopsAndBottoms second = new TopsAndBottoms(7);

        second.applyAppearance(first.appearance());

        assertEquals(first.appearance(), second.appearance(),
                "an indicator restored from a layout is not the one that was stored");
        assertEquals(TopsAndBottoms.Ties.LAST, second.ties(), "the tie rule");
        assertEquals(4, second.thickness(), "the thickness");
    }

    @Test
    @DisplayName("a perna e um parametro, e o teto e respeitado")
    void thewingIsAParameterAndItsCeilingHolds() {
        assertEquals(List.of(1), new TopsAndBottoms(1).parameters(), "the wing is the label");
        assertEquals(TopsAndBottoms.MOST_WING, new TopsAndBottoms(9_999).wing(), "the ceiling");
        assertEquals(1, new TopsAndBottoms(0).wing(), "the floor");

        assertTrue(new TopsAndBottoms(1).pivots().isEmpty(),
                "an indicator that has not been calculated yet claims to have pivots");
    }
}
