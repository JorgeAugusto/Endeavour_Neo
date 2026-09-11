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

/**
 * Indicador de escala maior lido sobre a série menor.
 *
 * <p>O caminho óbvio olha o futuro, e é a armadilha que este projeto já pagou
 * uma vez: às 10:01 o candle de cinco minutos das 10:00 ainda tem quatro
 * minutos de negócio pela frente, e o fechamento dele — que é o número de que o
 * indicador é feito — é um preço que ninguém viu.</p>
 */
@DisplayName("Ultimo candle fechado")
class LastClosedTest {

    /** Minutos a partir de 10:00, com hora em milissegundos. */
    private record Minutos(int quantos) implements PriceSeries {

        @Override
        public int size() {
            return quantos;
        }

        @Override
        public long timeAt(int index) {
            return index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return index;
        }

        @Override
        public double highAt(int index) {
            return index;
        }

        @Override
        public double lowAt(int index) {
            return index;
        }

        @Override
        public double closeAt(int index) {
            return index;
        }
    }

    /** Os mesmos minutos de cinco em cinco: o candle k comeca no minuto 5k. */
    private record Cincos(int quantos) implements PriceSeries {

        @Override
        public int size() {
            return quantos;
        }

        @Override
        public long timeAt(int index) {
            return index * 5L * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return index;
        }

        @Override
        public double highAt(int index) {
            return index;
        }

        @Override
        public double lowAt(int index) {
            return index;
        }

        @Override
        public double closeAt(int index) {
            return index;
        }
    }

    @Test
    @DisplayName("O CANDLE DE 5m SO VALE DEPOIS DE FECHAR, e vale ate o proximo fechar")
    void thefiveMinuteBarOnlyCountsOnceItHasClosed() {
        // Valor = o indice do candle de cinco minutos, para a resposta ser lida
        // direto: "qual candle estava valendo neste minuto".
        double[] valor = {10, 11, 12, 13};

        double[] espalhado = LastClosed.spread(new Minutos(20), new Cincos(4), valor);

        // Minutos 0 a 4 sao o PRIMEIRO candle de cinco, que ainda esta se
        // formando: nao ha candle fechado nenhum para trás, entao NaN.
        for (int minuto = 0; minuto < 5; minuto++) {
            assertTrue(Double.isNaN(espalhado[minuto]),
                    "o minuto " + minuto + " leu um candle que ainda nao fechou: "
                            + espalhado[minuto]);
        }

        // Do minuto 5 ao 9 o primeiro candle ja fechou e o segundo esta em
        // formacao: vale o PRIMEIRO, o tempo todo.
        for (int minuto = 5; minuto < 10; minuto++) {
            assertEquals(10, espalhado[minuto], 1e-9,
                    "o minuto " + minuto + " nao leu o primeiro candle fechado");
        }

        for (int minuto = 10; minuto < 15; minuto++) {
            assertEquals(11, espalhado[minuto], 1e-9,
                    "o minuto " + minuto + " nao leu o segundo candle fechado");
        }

        // E NUNCA o de indice 3: ele e o candle em que os minutos 15..19 estao
        // dentro, e ele so fecharia no minuto 20.
        for (int minuto = 15; minuto < 20; minuto++) {
            assertEquals(12, espalhado[minuto], 1e-9,
                    "o minuto " + minuto + " leu o candle em que ele esta dentro");
        }
    }

    @Test
    @DisplayName("sem candle grande nenhum a resposta e NaN, e nao zero")
    void withNocoarseBarTheAnswerIsNaNandNotZero() {
        double[] espalhado = LastClosed.spread(new Minutos(3), new Cincos(0), new double[0]);

        assertEquals(3, espalhado.length, "devolveu um valor por minuto?");

        for (double each : espalhado) {
            // Zero lido como preco e uma tendencia apontando violentamente para
            // baixo; NaN compara falso dos dois lados, que e o que "nao sei"
            // deve fazer.
            assertTrue(Double.isNaN(each), "respondeu " + each + " sem ter o que responder");
        }
    }
}
