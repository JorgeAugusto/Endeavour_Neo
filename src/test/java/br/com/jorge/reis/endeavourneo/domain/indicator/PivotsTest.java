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
package br.com.jorge.reis.endeavourneo.domain.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Topos e fundos, e sobretudo <b>quando</b> eles passam a ser fato.
 *
 * <p>Um pivô de asa 2 acontece numa barra e só é conhecido duas barras depois.
 * Arquivado na barra dele, uma estratégia que anda para a frente age sobre um
 * pivô cuja asa direita ainda está no futuro — o look-ahead mais simples que
 * existe, e invisível num resultado porque toda operação que ele produz parece
 * razoável.</p>
 */
@DisplayName("Topos e fundos")
class PivotsTest {

    /** Barras com máxima e mínima ditadas. */
    private record Bars(double[] high, double[] low) implements PriceSeries {

        @Override
        public int size() {
            return high.length;
        }

        @Override
        public long timeAt(int index) {
            return index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return (high[index] + low[index]) / 2;
        }

        @Override
        public double highAt(int index) {
            return high[index];
        }

        @Override
        public double lowAt(int index) {
            return low[index];
        }

        @Override
        public double closeAt(int index) {
            return (high[index] + low[index]) / 2;
        }
    }

    /** Um V: desce até a barra 4 e sobe de volta. */
    private static PriceSeries umV() {
        return new Bars(
                new double[] {120, 116, 112, 108, 104, 108, 112, 116, 120},
                new double[] {110, 106, 102, 98, 94, 98, 102, 106, 110});
    }

    @Test
    @DisplayName("O FUNDO E ARQUIVADO NA BARRA QUE O APRENDEU, nao na dele")
    void thebottomIsFiledUnderTheBarThatLearnedIt() {
        Pivots.Pivot[] quando = Pivots.standard().confirmedAt(umV());

        // O fundo esta na barra 4. Com asa 2, ele so vira fato na 6.
        for (int bar = 0; bar < 6; bar++) {
            assertNull(quando[bar],
                    "a barra " + bar + " ja sabia de um pivo que ainda nao tinha asa direita");
        }

        Pivots.Pivot fato = quando[6];

        assertNotNull(fato, "o fundo nunca virou fato");
        assertEquals(4, fato.bar(), "o pivo nao esta na barra do fundo");
        assertFalse(fato.top(), "o fundo do V foi lido como topo");
        assertEquals(94, fato.price(), 1e-9, "o preco do fundo nao e a minima dele");
    }

    @Test
    @DisplayName("a asa manda no atraso: asa 3 demora uma barra a mais que asa 2")
    void thewingDecidesTheDelay() {
        assertEquals(6, quandoSoube(new Pivots(2)), "asa 2 nao soube na barra 6");
        assertEquals(7, quandoSoube(new Pivots(3)), "asa 3 nao soube na barra 7");
    }

    private static int quandoSoube(Pivots pivots) {
        Pivots.Pivot[] quando = pivots.confirmedAt(umV());

        for (int bar = 0; bar < quando.length; bar++) {
            if (quando[bar] != null) {
                return bar;
            }
        }

        return -1;
    }

    @Test
    @DisplayName("UM FUNDO CONFIRMADO NAO FOI ROMPIDO, e e o que dispensa um caso")
    void aconfirmedBottomHasNotBeenBroken() {
        // A propriedade que apaga um ramo inteiro da estrategia: como o fundo so
        // e confirmado PORQUE as barras da asa direita nao foram abaixo dele, um
        // stop posto ali -- ou um tique alem -- nao pode ja nascer violado.
        double[] high = {120, 116, 112, 108, 104, 108, 112, 116, 120, 124, 128};
        double[] low = {110, 106, 102, 98, 94, 98, 102, 106, 110, 114, 118};

        PriceSeries bars = new Bars(high, low);
        Pivots.Pivot[] quando = Pivots.standard().confirmedAt(bars);
        int conferidos = 0;

        for (int bar = 0; bar < quando.length; bar++) {
            Pivots.Pivot fato = quando[bar];

            if (fato == null || fato.top()) {
                continue;
            }

            for (int depois = fato.bar() + 1; depois <= bar; depois++) {
                assertTrue(bars.lowAt(depois) >= fato.price(),
                        "a barra " + depois + " furou o fundo da " + fato.bar()
                                + " antes de ele ser confirmado");
            }

            conferidos++;
        }

        assertTrue(conferidos > 0, "nao houve fundo confirmado, entao nada foi conferido");
    }

    @Test
    @DisplayName("as pontas nao tem asa dos dois lados, e por isso nao tem pivo")
    void theedgesHaveNoWingOnBothSidesAndSoNoPivot() {
        Pivots.Pivot[] quando = Pivots.standard().confirmedAt(umV());

        for (Pivots.Pivot fato : quando) {
            if (fato == null) {
                continue;
            }

            assertTrue(fato.bar() >= 2 && fato.bar() <= umV().size() - 3,
                    "um pivo na barra " + fato.bar() + " nao tem as duas asas");
        }
    }

    @Test
    @DisplayName("serie curta demais nao inventa pivo")
    void aseriesTooShortInventsNothing() {
        PriceSeries curta = new Bars(new double[] {10, 12, 11}, new double[] {5, 7, 6});

        for (Pivots.Pivot fato : Pivots.standard().confirmedAt(curta)) {
            assertNull(fato, "achou pivo numa serie de tres barras com asa dois");
        }
    }
}
