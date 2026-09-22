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
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** As bandas: a conta do desvio, e de onde ele é medido. */
@DisplayName("Bandas de Bollinger")
class BollingerTest {

    /** Fechamentos escolhidos a dedo, para a conta poder ser feita à mão. */
    private record Closes(double[] price) implements PriceSeries {

        @Override
        public int size() {
            return price.length;
        }

        @Override
        public long timeAt(int index) {
            return index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return price[index];
        }

        @Override
        public double highAt(int index) {
            return price[index];
        }

        @Override
        public double lowAt(int index) {
            return price[index];
        }

        @Override
        public double closeAt(int index) {
            return price[index];
        }
    }

    @Test
    @DisplayName("A CONTA, conferida à mão numa janela de quatro")
    void thearithmeticCheckedByHand() {
        // Quatro fechamentos: 10, 12, 14, 16. Media 13. Desvios: -3,-1,1,3.
        // Soma dos quadrados 9+1+1+9 = 20; dividido por 4 da 5; raiz de 5.
        Closes bars = new Closes(new double[] {10, 12, 14, 16});
        Bollinger.Lines lines = new Bollinger(4, 2).over(bars);

        double spread = Math.sqrt(5);

        assertEquals(13, lines.middle()[3], 1e-12, "a media");
        assertEquals(13 + 2 * spread, lines.upper()[3], 1e-12, "a banda de cima");
        assertEquals(13 - 2 * spread, lines.lower()[3], 1e-12, "a banda de baixo");

        // POPULACAO e nao amostra. Com n-1 o desvio seria raiz de 20/3 =
        // 2,582 em vez de raiz de 5 = 2,236 -- quinze por cento mais largo, o
        // que muda todo nivel que alguem colocar numa banda.
        assertTrue(Math.abs(lines.upper()[3] - (13 + 2 * Math.sqrt(20.0 / 3))) > 0.5,
                "o desvio esta sendo dividido por n-1, e nao por n");
    }

    @Test
    @DisplayName("ANTES DA JANELA FECHAR não há banda nenhuma")
    void beforeTheWindowThereIsNothing() {
        Closes bars = new Closes(new double[] {10, 12, 14, 16});
        Bollinger.Lines lines = new Bollinger(4, 2).over(bars);

        for (int bar = 0; bar < 3; bar++) {
            assertTrue(Double.isNaN(lines.upper()[bar]),
                    "a barra " + bar + " tem banda antes de haver quatro fechamentos");
        }
    }

    @Test
    @DisplayName("O DESVIO É MEDIDO DA LINHA DO MEIO, e não de uma média escondida")
    void thedeviationIsMeasuredFromTheMiddleLine() {
        Closes bars = new Closes(new double[] {10, 12, 14, 16});

        // Uma linha do meio DELIBERADAMENTE longe da media: 20, e nao 13.
        // Se a conta medisse da media, a largura seria a mesma de cima; medindo
        // da linha entregue, ela tem de ser maior, porque os fechamentos estao
        // todos abaixo dela.
        double[] middle = {Double.NaN, Double.NaN, Double.NaN, 20};
        Bollinger.Lines lines = new Bollinger(4, 1).around(middle, bars);

        // Desvios de 20: -10,-8,-6,-4. Quadrados 100+64+36+16 = 216; /4 = 54.
        double spread = Math.sqrt(54);

        assertEquals(20, lines.middle()[3], 1e-12);
        assertEquals(20 + spread, lines.upper()[3], 1e-12,
                "a banda nao saiu da linha que foi entregue");

        assertTrue(spread > Math.sqrt(5),
                "medir de uma linha fora da media tem de dar desvio maior");
    }

    @Test
    @DisplayName("ONDE O PREÇO ESTÁ NA BANDA: -1 embaixo, 0 no meio, +1 em cima")
    void whereThePriceSitsAcrossTheBand() {
        Closes bars = new Closes(new double[] {10, 12, 14, 16});
        Bollinger.Lines lines = new Bollinger(4, 2).over(bars);

        assertEquals(0, lines.placeAt(3, lines.middle()[3]), 1e-12, "no meio");
        assertEquals(1, lines.placeAt(3, lines.upper()[3]), 1e-12, "na de cima");
        assertEquals(-1, lines.placeAt(3, lines.lower()[3]), 1e-12, "na de baixo");
        assertEquals(2, lines.placeAt(3, lines.middle()[3]
                + 2 * (lines.upper()[3] - lines.middle()[3])), 1e-12, "uma largura acima");
    }

    @Test
    @DisplayName("UMA BARRA PARADA não tem largura, e a leitura é zero e não infinito")
    void astillMarketHasNoWidth() {
        Closes bars = new Closes(new double[] {100, 100, 100, 100});
        Bollinger.Lines lines = new Bollinger(4, 2).over(bars);

        assertEquals(100, lines.upper()[3], 1e-12, "sem movimento, as tres linhas coincidem");

        // A DIVISAO POR ZERO ESTA AQUI, e a resposta e zero. Sem a guarda isto
        // devolveria infinito ou NaN, que viajaria para dentro de um estado
        // enviado a um modelo e reapareceria como uma decisao inexplicavel.
        assertEquals(0, lines.placeAt(3, 100), 1e-12);
        assertEquals(0, lines.placeAt(3, 105), 1e-12);
    }
}
