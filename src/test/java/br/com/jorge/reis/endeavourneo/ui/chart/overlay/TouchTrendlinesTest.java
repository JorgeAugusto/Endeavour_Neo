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

    // ------------------------------------------------------ os modos de ancora

    @Test
    @DisplayName("no extremo a ancora e o topo mais alto, e a reta pode nao tocar em nada")
    void theExtremeAnchorsOnTheHigh() {
        TouchTrendlines lines = new TouchTrendlines(90);

        lines.setAnchoring(TouchTrendlines.Anchoring.EXTREME);

        TouchTrendlines.Trend found = lines.fitTo(pivotsOf(lines, saw(9, -30)), true, 0);

        assertNotNull(found, "nao achou a reta do extremo");
        assertEquals(9, found.anchor(), "nao ancorou no topo mais alto da janela");
        assertEquals(-5.0, found.slope(), EXACT, "a inclinacao do pico ate a ponta");

        // A SERRA CAI DOIS POR BARRA e a reta do pico cai cinco: ela passa por
        // cima de todos os topos do meio. Dois toques sao as duas pontas dela
        // mesma -- e no modo 1 esse mesmo desenho da dez.
        assertEquals(2, found.touches(), "a reta do pico encostou em algum topo do meio");
        assertEquals(2, found.percent(), "a escada subiu num modo que nao busca nada");
    }

    // ------------------------------------ a ancora no cruzamento do preco

    /**
     * Sete giros e três cruzamentos do preço com a média.
     *
     * <pre>
     *   barra   10   20   30   40   50   60   70
     *   tipo     T    F    T    F    T    F    T
     *   preço  106   92  104   90  108   96  110
     *
     *   cruzou em 25, 45 e 65
     * </pre>
     *
     * <p>O cruzamento de 65 <b>não serve</b> para os modos 3 e 4: o fundo mais
     * novo que existe é o da barra 60, anterior a ele, e uma LTA com a ponta
     * antes da âncora correria para trás. Serve o de 45.</p>
     */
    private static List<TopsAndBottoms.Pivot> sevenTurns() {
        return List.of(top(10, 106), bottom(20, 92), top(30, 104), bottom(40, 90),
                top(50, 108), bottom(60, 96), top(70, 110));
    }

    /** Os mesmos sete, mais um fundo e um topo novos -- o tempo passando. */
    private static List<TopsAndBottoms.Pivot> nineTurns() {
        List<TopsAndBottoms.Pivot> more = new java.util.ArrayList<>(sevenTurns());

        more.add(bottom(80, 98));
        more.add(top(90, 112));

        return List.copyOf(more);
    }

    private static final int[] THREE_CROSSINGS = {25, 45, 65};

    private static TouchTrendlines onTheCrossing(TouchTrendlines.Crossing side) {
        TouchTrendlines lines = new TouchTrendlines(90);

        lines.setAnchoring(TouchTrendlines.Anchoring.FAST_AVERAGE);
        lines.setCrossing(side);

        return lines;
    }

    @Test
    @DisplayName("DEPOIS: a ancora e o primeiro giro depois do cruzamento, a ponta e o mais novo")
    void theAnchorFromTheCrossingWithALiveFarEnd() {
        TouchTrendlines lines = onTheCrossing(TouchTrendlines.Crossing.AFTER);
        TouchTrendlines.Trend found = lines.fitTo(sevenTurns(), true, 0, THREE_CROSSINGS);

        assertNotNull(found, "nao achou a LTB");

        // O cruzamento aproveitavel e o de 45: a ancora e o primeiro topo depois
        // dele, e a ponta e o topo MAIS NOVO que existe, nao o vizinho.
        assertEquals(50, found.anchor(), "a ancora nao e o primeiro topo depois do cruzamento");
        assertEquals(70, found.destination(), "a ponta nao e o topo mais novo");
    }

    @Test
    @DisplayName("logo depois do cruzamento ha um giro so, e um ponto nao e reta")
    void oneTurnAfterTheCrossingDrawsNothing() {
        TouchTrendlines lines = onTheCrossing(TouchTrendlines.Crossing.AFTER);

        // O unico fundo depois do cruzamento de 45 e o da barra 60, que e
        // tambem o mais novo: ancora e ponta na mesma barra. E o vao sem reta.
        assertNull(lines.fitTo(sevenTurns(), false, 0, THREE_CROSSINGS),
                "desenhou uma LTA de um ponto");
    }

    @Test
    @DisplayName("a ponta anda: dois giros novos movem as pontas e nao as ancoras")
    void theFarEndWalks() {
        TouchTrendlines lines = onTheCrossing(TouchTrendlines.Crossing.AFTER);
        int[] two = {25, 45};

        TouchTrendlines.Trend resistance = lines.fitTo(nineTurns(), true, 0, two);
        TouchTrendlines.Trend support = lines.fitTo(nineTurns(), false, 0, two);

        assertNotNull(resistance, "a LTB sumiu com giros novos");
        assertNotNull(support, "a LTA devia ter nascido com o segundo fundo");

        // AS MESMAS ANCORAS de antes -- 50 e 60 -- e as pontas nos giros novos.
        // E isso que o modo faz: a ancora fica, a ponta anda.
        assertEquals(50, resistance.anchor(), "a ancora da LTB andou");
        assertEquals(90, resistance.destination(), "a ponta da LTB nao seguiu o topo novo");
        assertEquals(60, support.anchor(), "a ancora da LTA andou");
        assertEquals(80, support.destination(), "a ponta da LTA nao seguiu o fundo novo");
    }

    @Test
    @DisplayName("ANTES: as duas retas existem desde o primeiro giro, e sao mais longas")
    void theAnchorBeforeTheCrossing() {
        TouchTrendlines lines = onTheCrossing(TouchTrendlines.Crossing.BEFORE);

        TouchTrendlines.Trend resistance = lines.fitTo(sevenTurns(), true, 0, THREE_CROSSINGS);
        TouchTrendlines.Trend support = lines.fitTo(sevenTurns(), false, 0, THREE_CROSSINGS);

        assertNotNull(resistance, "nao achou a LTB");
        assertNotNull(support, "nao achou a LTA");

        // A ancora vem de ANTES do cruzamento, entao nao existe o vao: as duas
        // retas ja tem duas pontas no primeiro giro do movimento novo.
        assertEquals(30, resistance.anchor(), "a LTB nao pegou o topo de antes");
        assertEquals(70, resistance.destination(), "a ponta da LTB nao e a mais nova");
        assertEquals(40, support.anchor(), "a LTA nao pegou o fundo de antes");
        assertEquals(60, support.destination(), "a ponta da LTA nao e a mais nova");
    }

    @Test
    @DisplayName("um cruzamento sem giro novo dos dois tipos e pulado")
    void aCrossingWithoutANewerTurnIsSkipped() {
        TouchTrendlines lines = onTheCrossing(TouchTrendlines.Crossing.BEFORE);

        // O de 65 tem topo novo depois (a barra 70) mas nao tem fundo: o mais
        // novo e o da 60. Sozinho, ele nao serve, e nao ha outro.
        assertNull(lines.fitTo(sevenTurns(), true, 0, new int[]{65}),
                "usou um cruzamento que deixaria a LTA correndo para tras");
        assertNull(lines.fitTo(sevenTurns(), true, 0, new int[0]),
                "desenhou sem cruzamento nenhum");
    }

    @Test
    @DisplayName("a memoria recua a ponta um giro, e a ancora fica")
    void theMemoryMovesTheFarEndBack() {
        TouchTrendlines lines = onTheCrossing(TouchTrendlines.Crossing.BEFORE);
        TouchTrendlines.Trend before = lines.fitTo(sevenTurns(), true, 1, THREE_CROSSINGS);

        assertNotNull(before, "nao achou a memoria");
        assertEquals(30, before.anchor(), "a memoria mudou de ancora");
        assertEquals(50, before.destination(), "a memoria nao recuou a ponta um topo");
    }

    @Test
    @DisplayName("o modo 4 e o mesmo mecanismo, so a media e outra")
    void theSlowModeSharesTheRule() {
        TouchTrendlines lines = new TouchTrendlines(90);

        lines.setAnchoring(TouchTrendlines.Anchoring.SLOW_AVERAGE);
        lines.setCrossing(TouchTrendlines.Crossing.AFTER);

        TouchTrendlines.Trend found = lines.fitTo(sevenTurns(), true, 0, THREE_CROSSINGS);

        assertNotNull(found, "o modo da media longa nao achou reta");
        assertEquals(50, found.anchor(), "o modo 4 escolheu outra ancora");
        assertEquals(70, found.destination(), "o modo 4 escolheu outra ponta");
    }

    // ---------------------------------- os giros do ultimo cruzamento

    private static TouchTrendlines bracketing() {
        TouchTrendlines lines = new TouchTrendlines(90);

        lines.setAnchoring(TouchTrendlines.Anchoring.LAST_CROSSING);

        return lines;
    }

    @Test
    @DisplayName("as duas retas ligam os giros que cercam o mesmo cruzamento")
    void bothLinesBracketTheSameCrossing() {
        TouchTrendlines lines = bracketing();

        TouchTrendlines.Trend resistance = lines.fitTo(sevenTurns(), true, 0, THREE_CROSSINGS);
        TouchTrendlines.Trend support = lines.fitTo(sevenTurns(), false, 0, THREE_CROSSINGS);

        assertNotNull(resistance, "nao achou a LTB");
        assertNotNull(support, "nao achou a LTA");

        // AQUI A PONTA E PRESA: o giro logo depois do cruzamento, e nao o mais
        // novo -- e a unica diferenca entre este modo e o 3.
        assertEquals(30, resistance.anchor(), "a LTB nao pegou o topo de antes");
        assertEquals(50, resistance.destination(), "a LTB nao pegou o topo de depois");
        assertEquals(40, support.anchor(), "a LTA nao pegou o fundo de antes");
        assertEquals(60, support.destination(), "a LTA nao pegou o fundo de depois");

        assertEquals(2, resistance.touches(), "contou toque que nao existe");
        assertEquals(2, resistance.percent(), "a escada subiu num modo sem busca");
    }

    @Test
    @DisplayName("a ponta presa nao anda quando nascem giros novos")
    void theBracketedFarEndStaysPut() {
        TouchTrendlines lines = bracketing();
        int[] two = {25, 45};

        TouchTrendlines.Trend found = lines.fitTo(nineTurns(), true, 0, two);

        assertNotNull(found, "nao achou a LTB");

        // Os mesmos giros novos que no modo 3 levaram a ponta para a barra 90
        // nao mexem nesta: ela continua no topo logo depois do cruzamento.
        assertEquals(30, found.anchor(), "a ancora andou");
        assertEquals(50, found.destination(), "a ponta presa andou");
    }

    @Test
    @DisplayName("o cruzamento novo demais e pulado: ainda nao tem giro dos dois lados")
    void anUnusableCrossingIsSteppedOver() {
        TouchTrendlines lines = bracketing();
        TouchTrendlines.Trend resistance = lines.fitTo(sevenTurns(), true, 0, THREE_CROSSINGS);

        // O cruzamento de 65 tem topo dos dois lados (50 e 70) e serviria para a
        // LTB sozinha. Nao serve para o par, porque nao ha fundo depois dele --
        // e as duas retas tem de responder pelo MESMO cruzamento.
        assertEquals(30, resistance.anchor(), "a LTB usou um cruzamento que a LTA nao pode usar");
        assertEquals(50, resistance.destination(), "a LTB usou um cruzamento so dela");
    }

    @Test
    @DisplayName("a memoria recua um cruzamento inteiro")
    void theMemoryStepsBackOneCrossing() {
        TouchTrendlines lines = bracketing();
        TouchTrendlines.Trend before = lines.fitTo(sevenTurns(), true, 1, THREE_CROSSINGS);

        assertNotNull(before, "nao achou o cruzamento anterior");
        assertEquals(10, before.anchor(), "a memoria nao recuou ate o cruzamento de 25");
        assertEquals(30, before.destination(), "a memoria nao recuou ate o cruzamento de 25");
    }

    @Test
    @DisplayName("sem cruzamento aproveitavel nao se desenha nada")
    void noUsableCrossingDrawsNothing() {
        TouchTrendlines lines = bracketing();

        assertNull(lines.fitTo(sevenTurns(), true, 0, new int[0]),
                "desenhou sem cruzamento nenhum");
        assertNull(lines.fitTo(sevenTurns(), true, 0, new int[]{65}),
                "desenhou em cima do cruzamento que nao serve");
    }
}
