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
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.indicator.Ema;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A média que o gráfico desenha e a que o robô lê são a mesma conta.
 *
 * <p>O estocástico foi resolvido mudando de lugar — a conta desceu para o
 * domínio e o estudo passou a chamá-la. A média exponencial <b>não</b>: a do
 * gráfico faz a média de qualquer preço da barra (fechamento, abertura, máxima,
 * mediana, típica) e a do domínio só do fechamento, então uma não substitui a
 * outra sem generalizar a de baixo.</p>
 *
 * <p>Enquanto forem duas, este teste é o que impede as duas de divergirem sem
 * ninguém notar. Duas implementações de uma ideia derivam, e a deriva aqui é
 * invisível: o gráfico mostra a tendência apontando para um lado e a rodada
 * opera para o outro, cada um parecendo certo sozinho.</p>
 */
@DisplayName("A EMA do dominio e a do grafico")
class EmaAgreesTest {

    /** Um passeio determinístico, para a comparação não depender de sorte. */
    private record Passeio(int quantas) implements PriceSeries {

        @Override
        public int size() {
            return quantas;
        }

        @Override
        public long timeAt(int index) {
            return index * 60_000L;
        }

        private double price(int index) {
            // Determinístico e sem repetir: uma serie constante faria as duas
            // concordarem por nao haver o que discordar.
            return 100_000 + Math.sin(index / 7.0) * 300 + index * 1.5;
        }

        @Override
        public double openAt(int index) {
            return price(index);
        }

        @Override
        public double highAt(int index) {
            return price(index) + 20;
        }

        @Override
        public double lowAt(int index) {
            return price(index) - 20;
        }

        @Override
        public double closeAt(int index) {
            return price(index);
        }
    }

    @Test
    @DisplayName("AS DUAS DAO O MESMO NUMERO, barra por barra")
    void bothgiveTheSameNumberBarForBar() {
        PriceSeries bars = new Passeio(400);

        MovingAverage doGrafico = new MovingAverage();

        doGrafico.setPeriod(17);
        doGrafico.setKind(MovingAverage.Kind.EXPONENTIAL);
        doGrafico.setSource(MovingAverage.Source.CLOSE);
        doGrafico.calculate(bars);

        double[] doDominio = new Ema(17).over(bars);
        double[] desenhada = new double[doDominio.length];

        for (int bar = 0; bar < desenhada.length; bar++) {
            desenhada[bar] = doGrafico.valueAt(bar)[0];
        }

        int comparadas = 0;

        for (int bar = 0; bar < desenhada.length; bar++) {
            if (Double.isNaN(desenhada[bar])) {
                assertTrue(Double.isNaN(doDominio[bar]),
                        "a barra " + bar + " aquece no grafico e nao no dominio");

                continue;
            }

            assertEquals(desenhada[bar], doDominio[bar], 1e-9,
                    "a barra " + bar + " saiu diferente");
            comparadas++;
        }

        assertTrue(comparadas > 300,
                "quase tudo era aquecimento, entao a comparacao nao vale: " + comparadas);
    }
}
