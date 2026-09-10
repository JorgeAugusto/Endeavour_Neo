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
package br.com.jorge.reis.endeavourneo.domain.trading.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.trading.Backtest;
import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Side;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O cruzamento de médias — a estratégia de referência.
 *
 * <p>Ela não é candidata a nada; é o que toda outra tem de bater, e é o que
 * prova que o motor roda sobre dado de verdade e não só sobre fixture.</p>
 */
@DisplayName("Cruzamento de medias")
class MovingAverageCrossingTest {

    /** Uma série de fechamentos; abertura igual ao fechamento anterior. */
    private record Closes(double[] close) implements PriceSeries {

        @Override
        public int size() {
            return close.length;
        }

        @Override
        public long timeAt(int index) {
            return 1_600_000_000_000L + index * 300_000L;
        }

        @Override
        public double openAt(int index) {
            return index == 0 ? close[0] : close[index - 1];
        }

        @Override
        public double highAt(int index) {
            return Math.max(openAt(index), close[index]);
        }

        @Override
        public double lowAt(int index) {
            return Math.min(openAt(index), close[index]);
        }

        @Override
        public double closeAt(int index) {
            return close[index];
        }
    }

    /** Sobe {@code up} barras, depois desce {@code down}, a partir de 100. */
    private static Closes rampThenFall(int flat, int up, int down) {
        double[] c = new double[flat + up + down];
        double price = 100;

        for (int i = 0; i < c.length; i++) {
            if (i >= flat && i < flat + up) {
                price += 5;
            } else if (i >= flat + up) {
                price -= 5;
            }

            c[i] = price;
        }

        return new Closes(c);
    }

    @Test
    @DisplayName("compra quando a rapida cruza a lenta para cima")
    void itBuysWhenTheFastCrossesAbove() {
        Result result = new Backtest(Costs.NONE, 1)
                .run(rampThenFall(10, 60, 0), new MovingAverageCrossing(3, 8, 1));

        assertTrue(result.fills().size() >= 1, "nao operou numa subida limpa");
        assertEquals(Side.BUY, result.fills().get(0).side(), "a primeira ordem nao foi de compra");
        assertEquals("BuyAtMarket", result.fills().get(0).verb(), "nao entrou a mercado");
    }

    @Test
    @DisplayName("a virada e DUAS ordens: fecha e abre")
    void theTurnIsTwoOrdersNotOne() {
        Result result = new Backtest(Costs.NONE, 1)
                .run(rampThenFall(10, 40, 60), new MovingAverageCrossing(3, 8, 1));

        // ReversePosition existe na linguagem e nao e usada aqui: os robos dele
        // invertem em duas ordens, e o benchmark faz o que eles fazem.
        assertTrue(result.fills().stream().noneMatch(f -> f.verb().equals("ReversePosition")),
                "a estrategia inverteu numa ordem so");

        assertTrue(result.fills().stream().anyMatch(f -> f.verb().equals("ClosePosition")),
                "a virada nao fechou a posicao antes de abrir a outra");
    }

    @Test
    @DisplayName("nao opera antes de as medias terem se separado")
    void itDoesNotTradeBeforeTheAveragesHaveSeparated() {
        Result result = new Backtest(Costs.NONE, 1)
                .run(rampThenFall(0, 60, 0), new MovingAverageCrossing(3, 8, 1));

        // Semeadas do mesmo primeiro fechamento, as duas comecam iguais: a
        // primeira barra que anda leria como cruzamento se nada segurasse.
        for (Fill fill : result.fills()) {
            assertTrue(fill.bar() > 8, "operou na barra " + fill.bar() + ", antes da media lenta existir");
        }
    }

    @Test
    @DisplayName("start() zera a memoria: duas rodadas dao o mesmo")
    void startClearsTheMemorySoTwoRunsAgree() {
        Closes serie = rampThenFall(10, 40, 60);
        MovingAverageCrossing estrategia = new MovingAverageCrossing(3, 8, 1);
        Backtest backtest = new Backtest(Costs.NONE, 1);

        Result primeira = backtest.run(serie, estrategia);
        Result segunda = backtest.run(serie, estrategia);

        // A MESMA instancia, duas vezes. Sem start(), as medias da primeira
        // rodada sobreviveriam e a segunda seria outra estrategia.
        assertEquals(primeira.fills(), segunda.fills(), "a segunda rodada operou diferente");
        assertEquals(primeira.net(), segunda.net(), 0.0, "a segunda rodada deu outro resultado");
    }

    @Test
    @DisplayName("a rapida tem de ser mais rapida")
    void theFastOneHasToBeFaster() {
        assertThrows(IllegalArgumentException.class, () -> new MovingAverageCrossing(34, 17, 1));
        assertThrows(IllegalArgumentException.class, () -> new MovingAverageCrossing(17, 17, 1));
        assertThrows(IllegalArgumentException.class, () -> new MovingAverageCrossing(17, 34, 0));
    }

    @Test
    @DisplayName("as operacoes ladrilham a serie: sempre no mercado")
    void theTradesTileTheSeries() {
        Closes serie = rampThenFall(10, 40, 60);

        Result result = new Backtest(Costs.NONE, 1).run(serie, new MovingAverageCrossing(3, 8, 1));

        // A estrategia nunca fica de fora depois da primeira entrada: cada
        // operacao termina na barra em que a seguinte comeca. E' a checagem
        // que pega buraco no ladrilho -- operacao perdida, ou contada duas
        // vezes.
        for (int i = 1; i < result.trades().size(); i++) {
            assertEquals(result.trades().get(i - 1).closedAt(), result.trades().get(i).openedAt(),
                    "sobrou buraco entre a operacao " + (i - 1) + " e a " + i);
        }
    }
}
