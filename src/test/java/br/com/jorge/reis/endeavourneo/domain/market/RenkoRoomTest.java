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
package br.com.jorge.reis.endeavourneo.domain.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Quanto espaço uma passada reserva antes de assentar qualquer coisa.
 *
 * <p>A reserva inicial é {@code source.size()}, que equivale a chutar "mais ou
 * menos um tijolo por barra". Isso vale para barras de <b>tempo</b> e é falso
 * por três ordens de grandeza para ticks: o ano dele tem 194.542.465 ticks
 * sintéticos e assenta 154.440 tijolos a 11R. Oito arrays paralelos a 57 bytes
 * o slot pediam onze gigabytes antes do primeiro tijolo, e a passada morria
 * ali.</p>
 *
 * <p>O teto é sobre o CHUTE e não sobre o resultado, e é isso que este arquivo
 * defende: uma passada ainda assenta quantos tijolos o preço mandar, inclusive
 * muito mais do que o teto.</p>
 */
@DisplayName("Espaco de uma passada de renko")
class RenkoRoomTest {

    /** O teto da reserva, repetido aqui de propósito: ver o teste de baixo. */
    private static final int ROOM = 1 << 16;

    /**
     * Uma escada preguiçosa: cada barra é um preço, um tijolo acima da anterior.
     *
     * <p>Preguiçosa porque o ponto é ter uma fonte <b>grande</b> sem que a
     * própria fonte custe memória — que é exatamente a forma de uma série de
     * ticks sintéticos.</p>
     */
    private record Escada(int degraus, double brick) implements PriceSeries {

        @Override
        public int size() {
            return degraus;
        }

        @Override
        public long timeAt(int index) {
            return index * 1_000L;
        }

        private double price(int index) {
            return 100_000 + index * brick;
        }

        @Override
        public double openAt(int index) {
            return price(index);
        }

        @Override
        public double highAt(int index) {
            return price(index);
        }

        @Override
        public double lowAt(int index) {
            return price(index);
        }

        @Override
        public double closeAt(int index) {
            return price(index);
        }
    }

    @Test
    @DisplayName("UMA PASSADA ASSENTA MAIS TIJOLOS QUE O TETO DA RESERVA")
    void apassLaysMoreBricksThanTheRoomItReserved() {
        // Uma escada de um tijolo por degrau, mais alta que o teto: se o teto
        // fosse sobre o RESULTADO e nao sobre o chute, a passada pararia nele.
        int degraus = ROOM + 5_000;

        PriceSeries bricks = Renko.of(50).apply(new Escada(degraus, 50));

        assertTrue(bricks.size() > ROOM,
                "a passada parou no teto da reserva: " + bricks.size());

        // Um tijolo por degrau, menos DOIS. O primeiro degrau so fixa a ancora,
        // e o segundo apenas ALCANCA o nivel seguinte -- e um tijolo fecha
        // quando o preco passa ALEM do nivel, nao quando o toca. So o terceiro
        // degrau fecha o primeiro tijolo.
        assertEquals(degraus - 2, bricks.size(),
                "nao assentou um tijolo por degrau: " + bricks.size());
    }

    @Test
    @DisplayName("uma fonte enorme que quase nao anda assenta quase nada")
    void ahugeSourceThatBarelyMovesLaysAlmostNothing() {
        // A forma de uma serie de ticks: milhoes de barras, poucos tijolos. O
        // numero de tijolos nao tem relacao nenhuma com o tamanho da fonte, que
        // e a razao de o chute precisar de teto.
        PriceSeries bricks = Renko.of(50).apply(new Escada(4_000_000, 0));

        assertEquals(0, bricks.size(), "um preco parado assentou tijolo: " + bricks.size());
    }
}
