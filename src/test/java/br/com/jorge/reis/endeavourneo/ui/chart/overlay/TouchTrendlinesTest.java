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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * As LTs de máximos toques, conferidas contra contas feitas no papel.
 *
 * <h2>Duas armações, e por quê</h2>
 *
 * <p>A primeira é a mesma serra de vinte e uma barras do canal por toques:
 * picos exatamente sobre {@code y = 200 − 2x} nas ímpares de 1 a 19, vales
 * exatamente sobre {@code y = 160 − 2x} nas pares de 2 a 18. Serve para o
 * caminho inteiro — série, pivôs, reta — sem gráfico nenhum no meio.</p>
 *
 * <p>A segunda é uma lista de pivôs escrita à mão. A escada de tolerância só
 * aparece quando a amplitude é grande o bastante para o piso de cinco pontos
 * não engolir os primeiros degraus, e desenhar uma série que produza pivôs em
 * lugares exatos seria trabalho para esconder a conta. Com os pivôs na mão a
 * conta fica visível: quatro pontos, três candidatas, e o degrau em que cada
 * uma passa a contar.</p>
 */
@DisplayName("LTs de máximos toques")
class TouchTrendlinesTest {

    private static final double EXACT = 1e-9;

    /**
     * @param movedTop qual pico sai da linha, ou −1 para nenhum
     * @param by quanto ele desce -- negativo sobe
     * @return a serra de vinte e uma barras
     */
    private static PriceSeries saw(int movedTop, double by) {
        int count = 21;
        double[] highs = new double[count];
        double[] lows = new double[count];

        for (int i = 0; i < count; i++) {
            if (i % 2 == 1) {
                highs[i] = 200 - 2.0 * i - (i == movedTop ? by : 0.0);
                lows[i] = 195 - 2.0 * i;
            } else {
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

    /** @return o indicador sobre a serra, já com os pivôs achados */
    private static List<TopsAndBottoms.Pivot> pivotsOf(TouchTrendlines lines,
            PriceSeries series) {

        return lines.pivotsFor(series, series.size() - 1);
    }

    private static TopsAndBottoms.Pivot top(int bar, double price) {
        return new TopsAndBottoms.Pivot(bar, true, price);
    }

    private static TopsAndBottoms.Pivot bottom(int bar, double price) {
        return new TopsAndBottoms.Pivot(bar, false, price);
    }

    // ------------------------------------------------------------- a serra

    @Test
    @DisplayName("a LTB nasce no topo mais antigo da janela e passa pelos dez")
    void resistanceOnTheSaw() {
        TouchTrendlines lines = new TouchTrendlines(90);
        TouchTrendlines.Trend found = lines.fitTo(pivotsOf(lines, saw()), true, 0);

        assertNotNull(found, "nao achou a LT de baixa numa serra perfeita");
        assertEquals(1, found.anchor(), "a ancora nao e o topo mais antigo");
        assertEquals(19, found.destination(), "a ponta nao e o ultimo topo");
        assertEquals(-2.0, found.slope(), EXACT, "a inclinacao dos topos");
        assertEquals(10, found.touches(), "faltou topo encostado");
        assertEquals(2, found.percent(), "subiu a escada sem precisar");
    }

    @Test
    @DisplayName("a LTA faz o mesmo com os nove fundos")
    void supportOnTheSaw() {
        TouchTrendlines lines = new TouchTrendlines(90);
        TouchTrendlines.Trend found = lines.fitTo(pivotsOf(lines, saw()), false, 0);

        assertNotNull(found, "nao achou a LT de alta");
        assertEquals(2, found.anchor(), "a ancora nao e o fundo mais antigo");
        assertEquals(18, found.destination(), "a ponta nao e o ultimo fundo");
        assertEquals(-2.0, found.slope(), EXACT, "a inclinacao dos fundos");
        assertEquals(9, found.touches(), "faltou fundo encostado");
    }

    @Test
    @DisplayName("a janela corta: com quinze barras a ancora anda para frente")
    void theWindowCuts() {
        TouchTrendlines lines = new TouchTrendlines(15);
        TouchTrendlines.Trend found = lines.fitTo(pivotsOf(lines, saw()), true, 0);

        assertNotNull(found, "a janela curta apagou a LT");

        // A ponta e a barra 19, entao a janela comeca em 19 - 15 + 1 = 5. Os
        // topos anteriores a isso nao existem para esta reta.
        assertEquals(5, found.anchor(), "a janela nao cortou os topos antigos");
        assertEquals(8, found.touches(), "contou topo de fora da janela");
    }

    @Test
    @DisplayName("um topo solitario nao impede a LT: ele so nao conta")
    void theOutlierIsSimplyNotCounted() {
        TouchTrendlines lines = new TouchTrendlines(90);
        TouchTrendlines.Trend found = lines.fitTo(pivotsOf(lines, saw(9, -30)), true, 0);

        // E O CONTRARIO DO CANAL. La um pico trinta pontos fora deixa o canal
        // sem resposta, porque a borda de cima tem de conter todos os topos.
        // Aqui a reta nao encerra nada: o pico fica de fora da contagem e a
        // reta continua sendo a mesma dos outros nove.
        assertNotNull(found, "o pico solitario apagou a LT");
        assertEquals(1, found.anchor(), "a ancora mudou por causa do pico");
        assertEquals(-2.0, found.slope(), EXACT, "a inclinacao mudou por causa do pico");
        assertEquals(9, found.touches(), "o pico entrou na contagem");

        for (TopsAndBottoms.Pivot each : found.resting()) {
            assertFalse(each.bar() == 9, "o pico de fora aparece entre os toques");
        }
    }

    @Test
    @DisplayName("a memoria cai em cima da reta quando o ultimo giro so confirmou")
    void theMemoryAgrees() {
        TouchTrendlines lines = new TouchTrendlines(90);
        List<TopsAndBottoms.Pivot> pivots = pivotsOf(lines, saw());

        TouchTrendlines.Trend now = lines.fitTo(pivots, true, 0);
        TouchTrendlines.Trend before = lines.fitTo(pivots, true, 1);

        assertNotNull(before, "nao achou o estado anterior");
        assertEquals(17, before.destination(), "a memoria nao recuou um giro");
        assertEquals(now.slope(), before.slope(), EXACT, "a memoria mudou de inclinacao");
        assertEquals(now.priceAt(19), before.priceAt(19), EXACT,
                "as duas retas deviam coincidir nesta serra");
    }

    @Test
    @DisplayName("menos de dois giros do tipo nao desenha reta nenhuma")
    void oneTurnIsNotALine() {
        TouchTrendlines lines = new TouchTrendlines(90);

        assertNull(lines.fitTo(List.of(top(0, 100), bottom(5, 90)), true, 0),
                "desenhou uma reta com um topo so");
    }

    // ------------------------------------------------- a escada de tolerancia

    /**
     * Quatro topos, amplitude 600, e as contas de cada degrau.
     *
     * <pre>
     *   candidata      2% (12)   4% (24)   6% (36)
     *   ancora  0       2 toq.    2 toq.    3 toq., erro 30
     *   ancora 10       2 toq.    2 toq.    3 toq., erro 25
     *   ancora 20       2 toq.    2 toq.    2 toq.
     * </pre>
     */
    private static List<TopsAndBottoms.Pivot> ladder() {
        return List.of(top(0, 1000), top(10, 830), top(20, 640), top(30, 400));
    }

    @Test
    @DisplayName("a escada sobe ate a reta ter um terceiro toque")
    void theLadderClimbs() {
        TouchTrendlines lines = new TouchTrendlines(90);
        TouchTrendlines.Trend found = lines.fitTo(ladder(), true, 0);

        assertNotNull(found, "nao achou reta nenhuma");
        assertEquals(6, found.percent(), "parou no degrau errado da escada");
        assertEquals(3, found.touches(), "aceitou uma reta de duas pontas e nada mais");
    }

    @Test
    @DisplayName("empate em toques decide pelo erro menor, nao pela ancora mais velha")
    void theErrorBreaksTheTie() {
        TouchTrendlines lines = new TouchTrendlines(90);
        TouchTrendlines.Trend found = lines.fitTo(ladder(), true, 0);

        // As duas candidatas tem tres toques no degrau de 6%. A da barra 0 erra
        // 30; a da barra 10 erra 25. A ancora mais velha so decide DEPOIS do
        // erro, entao a resposta e a barra 10.
        assertEquals(10, found.anchor(), "escolheu a ancora mais velha antes do erro");
        assertEquals(25.0, found.error(), EXACT, "o erro da vencedora");
    }

    @Test
    @DisplayName("no teto a busca entrega o que achou, e diz com que tolerancia")
    void theCeilingGivesUpHonestly() {
        TouchTrendlines lines = new TouchTrendlines(90);

        // O ponto do meio esta 150 fora de qualquer reta possivel, e o teto da
        // escada e 10% de 600 = 60. Nenhum degrau acha um terceiro toque.
        TouchTrendlines.Trend found = lines.fitTo(
                List.of(top(0, 1000), top(15, 850), top(30, 400)), true, 0);

        assertNotNull(found, "o teto devia entregar a melhor que achou");
        assertEquals(10, found.percent(), "nao foi ate o teto da escada");
        assertEquals(2, found.touches(), "achou um toque que nao existe");
    }

    @Test
    @DisplayName("o pivo do lado errado conta: a LT nao encerra nada")
    void thePiercingPivotStillTouches() {
        TouchTrendlines lines = new TouchTrendlines(90);

        // O fundo da barra 15 esta CINCO PONTOS ABAIXO da reta, isto e, do lado
        // de fora de um suporte. A distancia e absoluta, entao ele conta -- e e
        // isso que deixa o rompimento visivel em vez de mover a reta para
        // baixo dele.
        TouchTrendlines.Trend found = lines.fitTo(
                List.of(bottom(0, 400), bottom(10, 600), bottom(15, 695), bottom(20, 800)),
                false, 0);

        assertNotNull(found, "nao achou a LT de alta");
        assertEquals(4, found.touches(), "o fundo furado ficou de fora da contagem");
        assertEquals(0, found.anchor(), "a ancora nao e o fundo mais antigo");

        boolean pierced = false;

        for (TopsAndBottoms.Pivot each : found.resting()) {
            pierced = pierced || each.bar() == 15;
        }

        assertTrue(pierced, "o fundo furado devia estar entre os toques");
    }

    @Test
    @DisplayName("o piso de cinco pontos segura a tolerancia numa janela parada")
    void theFloorHoldsTheTolerance() {
        TouchTrendlines lines = new TouchTrendlines(90);

        // Amplitude de dez pontos: 2% dela e 0,2, menos que um tick. Sem o piso
        // de cinco a escada subiria ate 10% para achar o mesmo terceiro toque
        // que o piso ja acha no primeiro degrau.
        TouchTrendlines.Trend found = lines.fitTo(
                List.of(top(0, 100), top(10, 96), top(20, 90)), true, 0);

        assertNotNull(found, "nao achou a reta da janela parada");
        // O CINCO ESCRITO A MAO, e nao a constante: com a constante o teste
        // compara o produto com ele mesmo e passa com o piso valendo zero.
        assertEquals(5.0, found.tolerance(), EXACT, "a tolerancia nao caiu no piso");
        assertEquals(2, found.percent(), "subiu a escada porque o piso nao valeu");
        assertEquals(3, found.touches(), "o terceiro toque sumiu");
    }
}
