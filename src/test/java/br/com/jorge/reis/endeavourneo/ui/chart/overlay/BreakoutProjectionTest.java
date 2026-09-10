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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A projeção por rompimento, conferida contra a regra lida do .src.
 *
 * <h2>A armação</h2>
 *
 * <pre>
 *   barra   10   20   30   40   50
 *   tipo     F    T    F    T    F
 *   preço  100  200  150  260  210
 *                     C    B    A
 * </pre>
 *
 * <p>Com a perna 2, o fundo da barra 50 só é conhecido a partir da barra 52 --
 * é o &ldquo;Periodo+1&rdquo; do original, e sem ele o replay armaria num topo
 * que o mercado ainda não tinha feito.</p>
 *
 * <p>Da estrutura saem duas pernas: |A−B| = 50 (o recuo) e |B−C| = 110 (o
 * impulso). Vence a MAIOR, então 110, e os alvos saem de 260.</p>
 */
@DisplayName("Projeção por rompimento")
class BreakoutProjectionTest {

    private static final double EXACT = 1e-9;

    private static TopsAndBottoms.Pivot top(int bar, double price) {
        return new TopsAndBottoms.Pivot(bar, true, price);
    }

    private static TopsAndBottoms.Pivot bottom(int bar, double price) {
        return new TopsAndBottoms.Pivot(bar, false, price);
    }

    private static List<TopsAndBottoms.Pivot> structure() {
        return List.of(bottom(10, 100), top(20, 200), bottom(30, 150),
                top(40, 260), bottom(50, 210));
    }

    /**
     * @param count quantas barras
     * @param path pares barra/fechamento; entre eles o fechamento se mantém
     * @return uma série em que só o fechamento importa
     */
    private static PriceSeries closes(int count, double... path) {
        double[] made = new double[count];
        double now = path.length > 1 ? path[1] : 0;
        int next = 0;

        for (int i = 0; i < count; i++) {
            while (next + 1 < path.length && i >= (int) path[next]) {
                now = path[next + 1];
                next += 2;
            }

            made[i] = now;
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
                return made[index];
            }

            @Override
            public double highAt(int index) {
                return made[index];
            }

            @Override
            public double lowAt(int index) {
                return made[index];
            }

            @Override
            public double closeAt(int index) {
                return made[index];
            }
        };
    }

    private static BreakoutProjection indicator() {
        return new BreakoutProjection(2);
    }

    // ---------------------------------------------------------------- o disparo

    @Test
    @DisplayName("arma por PROXIMIDADE, cinquenta pontos antes de romper")
    void itArmsBeforeTheBreak() {
        BreakoutProjection projection = indicator();

        // O preco sobe a 215: nao rompeu os 260, mas 215 + 50 passa deles.
        projection.replay(closes(60, 0, 100, 52, 215), structure(), 0);

        BreakoutProjection.Shot shot = projection.rising();

        assertNotNull(shot, "nao armou com o preco a cinquenta pontos do topo");
        assertEquals(40, shot.at(), "a base nao e o topo sendo rompido");
        assertEquals(260.0, shot.base(), EXACT, "o preco da base");
        assertEquals(210.0, shot.stop(), EXACT, "o stop nao e o fundo mais recente");
    }

    @Test
    @DisplayName("com proximidade zero ele espera o rompimento de verdade")
    void withoutProximityItWaits() {
        BreakoutProjection projection = indicator();

        projection.setProximity(0);
        projection.replay(closes(60, 0, 100, 52, 215), structure(), 0);

        assertNull(projection.rising(), "armou sem o preco chegar no topo");
    }

    @Test
    @DisplayName("o giro so conta wing barras depois: o fundo da 50 nao existe na 51")
    void aPivotIsOnlyKnownAfterItsWing() {
        BreakoutProjection projection = indicator();

        // A serie para na barra 51. O fundo da 50 so fecha na 52, entao o giro
        // mais novo ainda e o TOPO da 40 -- e sem um fundo mais recente que ele
        // a estrutura de alta nem existe.
        //
        // O FECHAMENTO FICA BAIXO ate a penultima barra, e isso e essencial: na
        // primeira versao deste teste ele valia 215 desde a barra zero, o que
        // armou um setup ANTERIOR (base no topo da barra 20) e o teste falhou
        // apontando para o produto quando o errado era a armacao.
        projection.replay(closes(52, 0, 100, 51, 215), structure(), 0);

        assertNull(projection.rising(), "usou um giro que ainda nao tinha fechado");

        projection.replay(closes(53, 0, 100, 51, 215), structure(), 0);

        assertNotNull(projection.rising(), "uma barra depois o giro ja devia valer");
    }

    // ------------------------------------------------------------------ a perna

    @Test
    @DisplayName("vence a MAIOR das duas pernas, e aqui e o impulso")
    void theLargerOfTheTwoLegsWins() {
        BreakoutProjection projection = indicator();

        projection.replay(closes(60, 0, 100, 52, 215), structure(), 0);

        BreakoutProjection.Shot shot = projection.rising();

        // |A-B| = 50 e |B-C| = 110. Vence 110.
        assertEquals(110.0, shot.leg(), EXACT, "pegou a perna menor");
        assertEquals(370.0, shot.targets()[3], EXACT, "o 100% = 260 + 110");
        assertEquals(480.0, shot.targets()[5], EXACT, "o 200% = 260 + 220");
    }

    @Test
    @DisplayName("quando o recuo e maior que o impulso, e ele que e projetado")
    void sometimesThePullbackIsTheLargerLeg() {
        BreakoutProjection projection = indicator();

        // C sobe para 230: o impulso vira 30 e o recuo continua 50.
        List<TopsAndBottoms.Pivot> shallow = List.of(bottom(10, 100), top(20, 200),
                bottom(30, 230), top(40, 260), bottom(50, 210));

        projection.replay(closes(60, 0, 100, 52, 215), shallow, 0);

        assertEquals(50.0, projection.rising().leg(), EXACT, "nao pegou o recuo");
    }

    @Test
    @DisplayName("perna menor que o minimo nao vira projecao nenhuma")
    void aLegUnderTheMinimumDrawsNothing() {
        BreakoutProjection projection = indicator();

        // As duas pernas dao 20 pontos, e o minimo e 45.
        List<TopsAndBottoms.Pivot> tiny = List.of(bottom(10, 100), top(20, 200),
                bottom(30, 240), top(40, 260), bottom(50, 240));

        projection.replay(closes(60, 0, 100, 52, 245), tiny, 0);

        assertNull(projection.rising(), "projetou uma perna de vinte pontos");
    }

    // ------------------------------------------------------------- a memoria

    @Test
    @DisplayName("o stop apaga a projecao, e so vale depois da barra da base")
    void theStopClearsIt() {
        BreakoutProjection projection = indicator();

        // Sobe a 215 (arma), depois cai a 205 -- abaixo do stop de 210.
        projection.replay(closes(70, 0, 100, 52, 215, 60, 205), structure(), 0);

        assertNull(projection.rising(), "a projecao sobreviveu ao stop");
    }

    @Test
    @DisplayName("pagar o 200% encerra a projecao e QUEIMA o topo")
    void payingTwoHundredBurnsTheTop() {
        BreakoutProjection projection = indicator();

        // Arma, vai a 480 (o 200%), e depois VOLTA para perto do mesmo topo.
        // Sem a queima ela rearmaria no mesmo lugar.
        projection.replay(closes(90, 0, 100, 52, 215, 60, 480, 70, 215), structure(), 0);

        assertNull(projection.rising(), "rearmou no topo que ja pagou 200%");
    }

    @Test
    @DisplayName("antes de pagar o 200% ela continua viva, mesmo longe da base")
    void itSurvivesWhileNeitherHappens() {
        BreakoutProjection projection = indicator();

        // Vai a 470: passou do 161% (437) e nao chegou ao 200% (480).
        projection.replay(closes(90, 0, 100, 52, 215, 60, 470), structure(), 0);

        BreakoutProjection.Shot shot = projection.rising();

        assertNotNull(shot, "a projecao sumiu sem stop e sem 200%");
        assertEquals(260.0, shot.base(), EXACT, "a base mudou sozinha");
    }

    // ------------------------------------------------------------- o outro lado

    @Test
    @DisplayName("a baixa e o espelho: base no fundo, stop no topo, alvos para baixo")
    void theShortSideIsTheMirror() {
        BreakoutProjection projection = indicator();

        // Estrutura de baixa: o giro mais novo e um TOPO, e o fundo da 40 e o
        // nivel a ser rompido para baixo.
        List<TopsAndBottoms.Pivot> down = List.of(top(10, 400), bottom(20, 300),
                top(30, 350), bottom(40, 240), top(50, 290));

        projection.replay(closes(60, 0, 400, 52, 285), down, 0);

        BreakoutProjection.Shot shot = projection.falling();

        assertNotNull(shot, "nao armou a baixa");
        assertEquals(240.0, shot.base(), EXACT, "a base nao e o fundo rompido");
        assertEquals(290.0, shot.stop(), EXACT, "o stop nao e o topo mais recente");
        assertEquals(110.0, shot.leg(), EXACT, "a perna |B-C| = 350 - 240");
        assertEquals(130.0, shot.targets()[3], EXACT, "o 100% desce: 240 - 110");
    }

    @Test
    @DisplayName("os dois lados coexistem, cada um com o seu proprio estado")
    void bothSidesLiveTogether() {
        BreakoutProjection projection = indicator();

        // Uma estrutura em que a alta ja armou e depois nasce um topo novo, que
        // arma a baixa sem tocar na alta.
        List<TopsAndBottoms.Pivot> both = List.of(bottom(10, 100), top(20, 200),
                bottom(30, 150), top(40, 260), bottom(50, 210), top(60, 255));

        projection.replay(closes(80, 0, 100, 52, 215, 64, 214), both, 0);

        assertNotNull(projection.rising(), "a alta sumiu quando a baixa apareceu");
        assertNotNull(projection.falling(), "a baixa nao nasceu");
    }
}
