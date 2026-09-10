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

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A projeção da perna que cortou a média, conferida contra somas no papel.
 *
 * <h2>A armação</h2>
 *
 * <p>Sete giros à mão e uma média constante em 100, para que de que lado cada
 * giro está seja legível de bater o olho:</p>
 *
 * <pre>
 *   barra   10   20   30   40   50   60   70
 *   tipo     T    F    T    F    T    F    T
 *   preço  106   92  104   90  108   96  110
 *   lado     +    -    +    -    +    -    +
 * </pre>
 *
 * <p>Toda perna aqui cruza, que é o que um mercado de lado faz -- e é por isso
 * que o indicador mostra só duas.</p>
 */
@DisplayName("Projeção do zigzag")
class ZigzagProjectionTest {

    private static final double EXACT = 1e-9;

    private static TopsAndBottoms.Pivot top(int bar, double price) {
        return new TopsAndBottoms.Pivot(bar, true, price);
    }

    private static TopsAndBottoms.Pivot bottom(int bar, double price) {
        return new TopsAndBottoms.Pivot(bar, false, price);
    }

    private static List<TopsAndBottoms.Pivot> sevenTurns() {
        return List.of(top(10, 106), bottom(20, 92), top(30, 104), bottom(40, 90),
                top(50, 108), bottom(60, 96), top(70, 110));
    }

    /** @return uma média constante, longa o bastante para todos os giros */
    private static double[] flat() {
        double[] made = new double[120];

        java.util.Arrays.fill(made, 100.0);

        return made;
    }

    private static ZigzagProjection measuring(ZigzagProjection.Measure how) {
        ZigzagProjection projection = new ZigzagProjection(17);

        projection.setMeasure(how);

        return projection;
    }

    // ------------------------------------------------------------ as duas pernas

    @Test
    @DisplayName("pega a ultima perna que cortou pra cima e a ultima que cortou pra baixo")
    void theTwoMostRecentCrossingLegs() {
        ZigzagProjection.Legs legs =
                measuring(ZigzagProjection.Measure.ORIGIN).legsIn(sevenTurns(), flat());

        assertNotNull(legs.rising(), "nao achou perna cortando pra cima");
        assertNotNull(legs.falling(), "nao achou perna cortando pra baixo");

        // A de cima e 60 -> 70, a de baixo e 50 -> 60. As linhas comecam no giro
        // que TERMINOU cada perna.
        assertEquals(70, legs.rising().start(), "a perna de alta nao e a ultima");
        assertEquals(14.0, legs.rising().size(), EXACT, "o tamanho da perna de alta");
        assertEquals(60, legs.falling().start(), "a perna de baixa nao e a ultima");
        assertEquals(-12.0, legs.falling().size(), EXACT, "o tamanho da perna de baixa");
    }

    @Test
    @DisplayName("uma perna com as duas pontas do mesmo lado nao cortou nada")
    void aLegThatNeverTouchedTheAverageIsSkipped() {
        // O fundo da barra 60 sobe para 102, ACIMA da media: a perna 60 -> 70
        // deixa de cruzar, e a ultima que cruza pra cima passa a ser 40 -> 50.
        List<TopsAndBottoms.Pivot> above = List.of(top(10, 106), bottom(20, 92),
                top(30, 104), bottom(40, 90), top(50, 108), bottom(60, 102), top(70, 110));

        ZigzagProjection.Legs legs =
                measuring(ZigzagProjection.Measure.ORIGIN).legsIn(above, flat());

        assertNotNull(legs.rising(), "nao achou perna cortando pra cima");
        assertEquals(50, legs.rising().start(), "contou uma perna que nao cruzou a media");
        assertEquals(18.0, legs.rising().size(), EXACT, "o tamanho da perna 40 -> 50");
    }

    @Test
    @DisplayName("sem media nao ha o que projetar")
    void withoutAnAverageThereIsNothing() {
        ZigzagProjection.Legs legs =
                measuring(ZigzagProjection.Measure.ORIGIN).legsIn(sevenTurns(), null);

        assertNull(legs.rising(), "projetou sem media");
        assertNull(legs.falling(), "projetou sem media");
    }

    @Test
    @DisplayName("com buraco na media podem sair dois cruzamentos seguidos do mesmo lado")
    void twoCrossingsTheSameWayWithAGapInTheAverage() {
        // Numa escala maior a media e NaN ate o primeiro candle grosso fechar,
        // e as pernas que caem nesse buraco sao puladas. Ai a alternancia
        // normal -- sobe, desce, sobe -- se quebra, e DOIS cruzamentos pra cima
        // podem ficar seguidos. Vale o mais novo.
        double[] holed = flat();

        holed[30] = Double.NaN;
        holed[40] = Double.NaN;

        List<TopsAndBottoms.Pivot> turns = List.of(bottom(10, 90), top(20, 110),
                bottom(30, 95), top(40, 105), bottom(50, 92), top(60, 108));

        ZigzagProjection.Legs legs =
                measuring(ZigzagProjection.Measure.ORIGIN).legsIn(turns, holed);

        assertNotNull(legs.rising(), "nao achou perna cortando pra cima");
        assertEquals(60, legs.rising().start(), "pegou o cruzamento mais velho");
        assertNull(legs.falling(), "nao ha perna cortando pra baixo nesta armacao");
    }

    // ----------------------------------------------------------------- a escada

    @Test
    @DisplayName("da origem: 50% e o meio da perna e 100% cai no giro que a terminou")
    void theLadderFromTheOrigin() {
        ZigzagProjection.Legs legs =
                measuring(ZigzagProjection.Measure.ORIGIN).legsIn(sevenTurns(), flat());
        double[] levels = legs.rising().levels();

        // A perna vai de 96 a 110, entao 14 pontos: 96+7, 96+14, 96+21, 96+28.
        assertEquals(103.0, levels[0], EXACT, "o 50% da origem");
        assertEquals(110.0, levels[1], EXACT, "o 100% devia cair no proprio giro");
        assertEquals(117.0, levels[2], EXACT, "o 150% da origem");
        assertEquals(124.0, levels[3], EXACT, "o 200% da origem");
    }

    @Test
    @DisplayName("da ponta: a mesma escada, deslocada de um degrau")
    void theLadderFromTheEnd() {
        ZigzagProjection.Legs origin =
                measuring(ZigzagProjection.Measure.ORIGIN).legsIn(sevenTurns(), flat());
        ZigzagProjection.Legs end =
                measuring(ZigzagProjection.Measure.END).legsIn(sevenTurns(), flat());

        double[] fromEnd = end.rising().levels();

        assertEquals(117.0, fromEnd[0], EXACT, "o 50% da ponta");
        assertEquals(124.0, fromEnd[1], EXACT, "o 100% da ponta");
        assertEquals(131.0, fromEnd[2], EXACT, "o 150% da ponta");
        assertEquals(138.0, fromEnd[3], EXACT, "o 200% da ponta");

        // A RELACAO E O PONTO: o 150% da origem e o 50% da ponta, e o 200% da
        // origem e o 100% da ponta. As duas leituras sao a mesma escada com o
        // primeiro degrau em lugares diferentes.
        assertEquals(origin.rising().levels()[2], fromEnd[0], EXACT,
                "o 150% da origem devia ser o 50% da ponta");
        assertEquals(origin.rising().levels()[3], fromEnd[1], EXACT,
                "o 200% da origem devia ser o 100% da ponta");
    }

    @Test
    @DisplayName("a perna de baixa desce: os niveis ficam abaixo dela")
    void theFallingLegGoesDown() {
        ZigzagProjection.Legs legs =
                measuring(ZigzagProjection.Measure.ORIGIN).legsIn(sevenTurns(), flat());
        double[] levels = legs.falling().levels();

        // De 108 a 96, doze pontos para baixo: 108-6, 108-12, 108-18, 108-24.
        assertEquals(102.0, levels[0], EXACT, "o 50% da perna de baixa");
        assertEquals(96.0, levels[1], EXACT, "o 100% devia cair no proprio fundo");
        assertEquals(90.0, levels[2], EXACT, "o 150% da perna de baixa");
        assertEquals(84.0, levels[3], EXACT, "o 200% da perna de baixa");
    }

    // --------------------------------------------------------- o caminho inteiro

    /** @return trinta barras subindo e descendo de dez em dez */
    private static PriceSeries triangle() {
        int count = 30;
        double[] closes = new double[count];

        for (int i = 0; i < count; i++) {
            int step = i % 10;

            closes[i] = 100 + (step <= 5 ? step * 2 : (10 - step) * 2);
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
                return closes[index];
            }

            @Override
            public double highAt(int index) {
                return closes[index] + 0.5;
            }

            @Override
            public double lowAt(int index) {
                return closes[index] - 0.5;
            }

            @Override
            public double closeAt(int index) {
                return closes[index];
            }
        };
    }

    @Test
    @DisplayName("da serie ate as linhas: o nivel comeca no giro e nao antes")
    void theLevelsStartAtTheirTurn() {
        ZigzagProjection projection = new ZigzagProjection(5);

        projection.setWing(1);
        projection.calculate(triangle());

        ZigzagProjection.Leg rising = projection.rising();

        assertNotNull(rising, "nao achou perna nenhuma numa serra que cruza a media");

        // AS ASSERCOES NAO ADIVINHAM QUAL PERNA foi escolhida: elas leem a que
        // o indicador achou e conferem o desenho contra ela. Assim o teste vale
        // sem que o valor da EMA precise ser calculado a mao aqui.
        double[] atTheTurn = projection.valueAt(rising.start());
        double[] justBefore = projection.valueAt(rising.start() - 1);

        for (int i = 0; i < ZigzagProjection.FACTORS.length; i++) {
            assertEquals(rising.levels()[i], atTheTurn[i], EXACT,
                    "a linha nao vale o nivel na barra do giro");
            assertTrue(Double.isNaN(justBefore[i]),
                    "a linha existia antes do giro que a criou");
        }
    }

    @Test
    @DisplayName("sao oito linhas: quatro da perna de alta e quatro da de baixa")
    void eightLinesInAll() {
        ZigzagProjection projection = new ZigzagProjection(5);

        projection.setWing(1);
        projection.calculate(triangle());

        assertEquals(8, projection.valueAt(29).length, "o numero de linhas mudou");
        assertEquals(8, projection.colours().size(), "faltou cor para alguma linha");
        assertEquals(8, projection.strokes().size(), "faltou caneta para alguma linha");
    }
}
