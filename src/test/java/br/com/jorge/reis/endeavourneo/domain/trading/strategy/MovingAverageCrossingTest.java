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
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.Average;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.trading.Backtest;
import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;
import br.com.jorge.reis.endeavourneo.domain.trading.Trade;
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

    // --------------------------------------------- o que a estrategia mostra

    @Test
    @DisplayName("a curva desenhada tem um valor por barra, e nada antes de existir")
    void theCurveHasOneValuePerBarAndNothingBeforeItExisted() {
        Closes serie = rampThenFall(10, 40, 60);
        MovingAverageCrossing estrategia = new MovingAverageCrossing(3, 8, 1);

        new Backtest(Costs.NONE, 1).run(serie, estrategia);

        java.util.Map<String, double[]> curvas = estrategia.curves();

        assertEquals(2, curvas.size(), "o cruzamento nao mostrou as duas medias");
        assertTrue(curvas.containsKey("EMA 3") && curvas.containsKey("EMA 8"),
                "as medias nao vieram com o periodo no nome: " + curvas.keySet());

        for (java.util.Map.Entry<String, double[]> cada : curvas.entrySet()) {
            assertEquals(serie.size(), cada.getValue().length,
                    cada.getKey() + " nao tem um valor por barra");

            // A ULTIMA BARRA TAMBEM. Um laco que grava ate a penultima tem o
            // tamanho certo e a ponta vazia, e a ponta e onde o leitor olha
            // primeiro -- a operacao mais recente.
            assertTrue(!Double.isNaN(cada.getValue()[serie.size() - 1]),
                    cada.getKey() + " nao tem valor na ULTIMA barra");
        }
    }

    @Test
    @DisplayName("A ENTRADA CAI NA BARRA EM QUE A LINHA DESENHADA CRUZOU")
    void theEntryLandsOnTheBarWhereTheDrawnLineCrossed() {
        Closes serie = rampThenFall(10, 40, 60);
        MovingAverageCrossing estrategia = new MovingAverageCrossing(3, 8, 1);

        Result result = new Backtest(Costs.NONE, 1).run(serie, estrategia);

        double[] rapida = estrategia.curves().get("EMA 3");
        double[] lenta = estrategia.curves().get("EMA 8");

        assertTrue(result.count() > 0, "a serie de teste nao produziu operacao nenhuma");

        for (Trade trade : result.trades()) {
            // A estrategia decide no FECHAMENTO de uma barra e a ordem executa
            // na ABERTURA da seguinte. Entao a barra que decidiu esta operacao
            // e a anterior a que ela abriu.
            int decidiu = trade.openedAt() - 1;

            if (decidiu < 1) {
                continue;
            }

            boolean agora = rapida[decidiu] > lenta[decidiu];
            boolean antes = rapida[decidiu - 1] > lenta[decidiu - 1];

            // E' ISTO QUE ELE QUER OLHAR NA TELA. Se a linha desenhada fosse
            // uma media recalculada pelo grafico -- sem o mesmo inicio, sem a
            // mesma escala -- ela cruzaria na barra VIZINHA, e a seta pareceria
            // ter saido do lugar. A linha e a que decidiu, entao o cruzamento
            // cai debaixo da seta.
            assertTrue(antes != agora,
                    "a operacao abriu na barra " + trade.openedAt()
                            + ", mas as medias desenhadas nao cruzaram na " + decidiu);
        }
    }

    @Test
    @DisplayName("uma rodada nova nao herda a curva da anterior")
    void afreshRunDoesNotInheritTheLastOnesCurve() {
        MovingAverageCrossing estrategia = new MovingAverageCrossing(3, 8, 1);
        Backtest backtest = new Backtest(Costs.NONE, 1);

        backtest.run(rampThenFall(10, 60, 60), estrategia);

        int longa = estrategia.curves().get("EMA 3").length;

        backtest.run(rampThenFall(5, 10, 5), estrategia);

        int curta = estrategia.curves().get("EMA 3").length;

        assertEquals(130, longa, "a primeira rodada nao mediu o que devia");
        assertEquals(20, curta, "a curva ficou do tamanho da rodada ANTERIOR");
    }

    // ----------------------------------------------------- a media simples

    @Test
    @DisplayName("a media simples e a media mesmo, conferida a mao")
    void thesimpleAverageIsTheMeanAndTheMeanChecksOut() {
        // Fechamentos 100, 105, 110, 115, ... (sobe 5 por barra a partir da 1).
        Closes serie = rampThenFall(1, 40, 0);
        MovingAverageCrossing estrategia =
                new MovingAverageCrossing(3, 8, 1, Average.SIMPLE);

        new Backtest(Costs.NONE, 1).run(serie, estrategia);

        double[] rapida = estrategia.curves().get("SMA 3");

        assertNotNull(rapida, "a curva nao veio com o nome da media simples");

        int barra = 20;
        double mao = (serie.closeAt(barra) + serie.closeAt(barra - 1)
                + serie.closeAt(barra - 2)) / 3;

        assertEquals(mao, rapida[barra], 1e-9,
                "a media de tres nao e a media dos tres ultimos fechamentos");
    }

    @Test
    @DisplayName("a media simples NAO existe antes de a janela encher")
    void thesimpleAverageDoesNotExistBeforeItsWindowIsFull() {
        Closes serie = rampThenFall(1, 40, 0);
        MovingAverageCrossing estrategia =
                new MovingAverageCrossing(3, 8, 1, Average.SIMPLE);

        new Backtest(Costs.NONE, 1).run(serie, estrategia);

        double[] lenta = estrategia.curves().get("SMA 8");

        // Uma media de tres fechamentos chamada "a media de oito" anda como
        // outro indicador e cruza noutro lugar. NaN quebra a linha, que e o
        // jeito de dizer "aqui ela ainda nao existia".
        assertTrue(Double.isNaN(lenta[6]), "a media de oito ja existia na barra 6");
        assertTrue(!Double.isNaN(lenta[7]), "a media de oito nao existia na barra 7");
    }

    @Test
    @DisplayName("nao se opera enquanto a media nao existe")
    void nothingIsTradedWhileTheAverageDoesNotExist() {
        Closes serie = rampThenFall(1, 40, 0);

        Result result = new Backtest(Costs.NONE, 1)
                .run(serie, new MovingAverageCrossing(3, 8, 1, Average.SIMPLE));

        // Comparar com NaN e falso dos dois lados, o que se le como "a rapida
        // esta ABAIXO" e dispara uma venda na primeira barra que tem historico.
        for (Fill fill : result.fills()) {
            assertTrue(fill.bar() > 8,
                    "operou na barra " + fill.bar() + ", antes de a media de oito existir");
        }
    }

    @Test
    @DisplayName("simples e exponencial cruzam em barras DIFERENTES")
    void thetwoKindsCrossOnDifferentBars() {
        Closes serie = rampThenFall(10, 40, 60);
        Backtest backtest = new Backtest(Costs.NONE, 1);

        Result exponencial = backtest.run(serie, new MovingAverageCrossing(3, 8, 1, Average.EXPONENTIAL));
        Result simples = backtest.run(serie, new MovingAverageCrossing(3, 8, 1, Average.SIMPLE));

        assertTrue(exponencial.count() > 0 && simples.count() > 0,
                "a serie de teste nao fez os dois operarem");

        // Se dessem a mesma lista de execucoes, a escolha do tipo seria
        // enfeite -- e o terceiro argumento das medias do NTSL, que decide se
        // duas implementacoes batem, nao teria razao de existir.
        assertTrue(!exponencial.fills().equals(simples.fills()),
                "os dois tipos produziram exatamente as mesmas execucoes");
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
