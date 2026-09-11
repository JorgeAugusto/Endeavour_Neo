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
package br.com.jorge.reis.endeavourneo.domain.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Desk;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O quadro: o que se deriva de uma rodada.
 *
 * <p>Nada aqui é medido — tudo sai do que o motor já registrou. O que estes
 * testes defendem é a <b>aritmética</b>, e sobretudo a do <b>acerto de
 * empate</b>, que é o número que diz "isto perde" sem saber nada sobre o
 * tamanho da conta.</p>
 */
@DisplayName("O quadro de uma rodada")
class MetricsTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    @BeforeEach
    void useTheExchangesZone() {
        Timeframe.useZone(SP);
    }

    /** Barras de cinco minutos a partir de 01/09/2020, 10:00. */
    private record Bars(int count, double step) implements PriceSeries {

        @Override
        public int size() {
            return count;
        }

        @Override
        public long timeAt(int index) {
            return ZonedDateTime.of(LocalDate.of(2020, 9, 1), LocalTime.of(10, 0), SP)
                    .plusMinutes(5L * index).toInstant().toEpochMilli();
        }

        @Override
        public double openAt(int index) {
            return 100_000 + step * index;
        }

        @Override
        public double highAt(int index) {
            return openAt(index) + 50;
        }

        @Override
        public double lowAt(int index) {
            return openAt(index) - 50;
        }

        @Override
        public double closeAt(int index) {
            return openAt(index) + step;
        }
    }

    // ------------------------------------------------------ acerto de empate

    /** Uma operação que rendeu {@code gross} e pagou {@code cost}. */
    private static Trade trade(double gross, double cost) {
        return new Trade(0, 1, br.com.jorge.reis.endeavourneo.domain.trading.order.Side.BUY,
                1, 1, gross, cost, java.util.List.of());
    }

    private static Result of(Trade... trades) {
        return new Result(java.util.List.of(trades), java.util.List.of(), 0,
                Costs.MEASURED, 0, 0, new double[0], 0);
    }

    @Test
    @DisplayName("o acerto de empate sai da conta, e a conta e conferivel a mao")
    void theBreakEvenHitRateIsTheFormulaAndTheFormulaChecksOut() {
        // Ganho medio 100, perda media 100, custo medio 10.
        //   p·100 − (1−p)·100 − 10 = 0   ->   p = 110 / 200 = 55%
        Metrics metrics = Metrics.of(
                of(trade(100, 10), trade(100, 10), trade(-100, 10), trade(-100, 10)), null);

        assertEquals(100, metrics.averageWin(), 1e-9, "o ganho medio nao e 100");
        assertEquals(100, metrics.averageLoss(), 1e-9, "a perda media nao e 100");
        assertEquals(0.55, metrics.breakEvenHitRate(), 1e-9, "o acerto de empate nao bate a conta");
    }

    @Test
    @DisplayName("sem custo nenhum, o empate e so a proporcao entre ganho e perda")
    void withNoCostTheBreakEvenIsJustTheRatio() {
        Metrics metrics = Metrics.of(
                new Result(java.util.List.of(trade(300, 0), trade(-100, 0)),
                        java.util.List.of(), 0, Costs.NONE, 0, 0, new double[0], 0),
                null);

        // Ganha 300 quando ganha, perde 100 quando perde: basta acertar 1 em 4.
        assertEquals(0.25, metrics.breakEvenHitRate(), 1e-9, "o empate sem custo nao e a razao");
    }

    @Test
    @DisplayName("a vantagem e negativa quando se acerta menos do que era preciso")
    void theEdgeIsNegativeWhenTheHitRateFallsShort() {
        Metrics metrics = Metrics.of(
                of(trade(100, 10), trade(-100, 10), trade(-100, 10), trade(-100, 10)), null);

        assertTrue(metrics.hitRate() < metrics.breakEvenHitRate(),
                "a serie de teste nao acerta menos do que precisa");
        assertTrue(metrics.edge() < 0, "a vantagem nao ficou negativa: " + metrics.edge());
    }

    @Test
    @DisplayName("a maior sequencia de perdas conta seguidas, nao o total")
    void theLongestLosingRunCountsInARow() {
        Metrics metrics = Metrics.of(
                of(trade(-1, 0), trade(-1, 0), trade(50, 0), trade(-1, 0), trade(-1, 0),
                        trade(-1, 0), trade(50, 0), trade(-1, 0)),
                null);

        assertEquals(3, metrics.longestLosingRun(), "contou o total de perdas, e nao a sequencia");
    }

    // ----------------------------------------------------------- sobre a serie

    @Test
    @DisplayName("comprar e segurar e a primeira abertura ate o ultimo fechamento")
    void buyAndHoldIsTheFirstOpenToTheLastClose() {
        Bars bars = new Bars(100, 10);
        Metrics metrics = Metrics.of(Result.empty(), bars);

        assertEquals(bars.closeAt(99) - bars.openAt(0), metrics.buyAndHold(), 1e-9,
                "a referencia nao e a diferenca entre as duas pontas");
    }

    @Test
    @DisplayName("operacoes por PREGAO, e nao por dia de calendario")
    void tradesAreCountedPerSessionNotPerCalendarDay() {
        // 100 barras de 5m = 500 minutos, tudo dentro do MESMO pregao.
        Metrics metrics = Metrics.of(of(trade(1, 0), trade(1, 0), trade(1, 0)), new Bars(100, 1));

        // Dividir por dias de calendario daria o mesmo aqui, mas a serie real
        // pula fins de semana e feriados -- e dividir por eles adularia toda
        // estrategia em um terco.
        assertEquals(3, metrics.tradesPerSession(), 1e-9,
                "as tres operacoes de um pregao nao deram tres por pregao");
    }

    // -------------------------------------------------------------- as curvas

    /** Compra na barra 1 e nunca sai: a posicao fica aberta ate o fim. */
    private static Result held() {
        return new Backtest(Costs.NONE, 1).run(new Bars(40, 10), (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(1);
            }
        });
    }

    @Test
    @DisplayName("o patrimonio anda com a posicao aberta; o saldo fica parado")
    void worthMovesWhileTheBalanceStandsStill() {
        Result result = held();

        double[] worth = result.worth();
        double[] balance = result.balancePerBar();

        assertEquals(40, worth.length, "faltou patrimonio por barra");
        assertEquals(40, balance.length, "faltou saldo por barra");

        // Nenhuma operacao fechou: o saldo e zero do comeco ao fim, e o
        // patrimonio subiu junto com o preco. E' exatamente o buraco que a
        // curva de saldo esconde.
        assertEquals(0, balance[39], 1e-9, "o saldo andou sem nenhuma operacao ter fechado");
        assertTrue(worth[39] > 0, "o patrimonio nao acompanhou a posicao aberta");
        assertTrue(worth[39] > worth[10], "o patrimonio nao andou com o preco");
    }

    @Test
    @DisplayName("patrimonio menos saldo, no fim, e exatamente a posicao aberta")
    void worthMinusBalanceIsTheOpenPosition() {
        Result result = held();

        double[] worth = result.worth();
        double[] balance = result.balancePerBar();

        // A conferencia que amarra as duas curvas ao motor: a diferenca entre
        // elas no fim tem de ser o que a posicao aberta valia.
        assertEquals(result.openResultAtTheEnd(), worth[39] - balance[39], 1e-9,
                "a diferenca entre as curvas nao e a posicao aberta");
    }

    @Test
    @DisplayName("o saldo por barra e uma escada que termina no liquido")
    void theBalanceIsAStaircaseEndingAtTheNet() {
        Result result = new Backtest(Costs.MEASURED, 1).run(new Bars(40, 10), (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(1);
            } else if (market.bar() == 10) {
                desk.sellToCoverAtMarket(1);
            }
        });

        double[] balance = result.balancePerBar();

        assertEquals(1, result.count(), "a serie de teste nao fechou uma operacao");
        assertEquals(0, balance[5], 1e-9, "o saldo andou antes de a operacao fechar");
        assertEquals(result.net(), balance[39], 1e-9, "a escada nao termina no liquido");

        for (int bar = 1; bar < balance.length; bar++) {
            assertTrue(balance[bar] == balance[bar - 1]
                            || bar == result.trades().get(0).closedAt(),
                    "o saldo mudou na barra " + bar + ", em que nada fechou");
        }
    }

    @Test
    @DisplayName("o custo por barra e acumulado e termina no custo total")
    void theCostCurveAccumulatesAndEndsAtTheTotal() {
        Result result = new Backtest(Costs.MEASURED, 1).run(new Bars(40, 10), (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(2);
            } else if (market.bar() == 10) {
                desk.sellToCoverAtMarket(2);
            }
        });

        double[] cost = result.costPerBar();

        assertEquals(0, cost[0], 1e-9, "cobrou custo antes de qualquer execucao");
        assertEquals(result.cost(), cost[39], 1e-9, "a curva de custo nao fecha com o total");

        for (int bar = 1; bar < cost.length; bar++) {
            assertTrue(cost[bar] >= cost[bar - 1] - 1e-9, "o custo acumulado caiu na barra " + bar);
        }
    }

    @Test
    @DisplayName("exposicao e a fracao de barras que terminaram posicionadas")
    void exposureIsTheFractionOfBarsThatEndedHolding() {
        Result flat = new Backtest(Costs.NONE, 1).run(new Bars(40, 10), (market, desk) -> { });

        assertEquals(0, flat.exposure(), 0.0, "ficou exposto sem nunca operar");

        Result result = new Backtest(Costs.NONE, 1).run(new Bars(40, 10), (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(1);
            } else if (market.bar() == 19) {
                desk.sellToCoverAtMarket(1);
            }
        });

        // Entra na abertura da 1 e sai na abertura da 20: barras 1..19 terminam
        // posicionadas, 19 de 40.
        assertEquals(19 / 40.0, result.exposure(), 1e-9,
                "a exposicao nao conta as barras que terminaram posicionadas");
    }

    @Test
    @DisplayName("rodada vazia nao estoura em nada do quadro")
    void anEmptyRunBreaksNothing() {
        Metrics metrics = Metrics.of(Result.empty(), null);

        assertEquals(0, metrics.trades(), "inventou operacoes");
        assertTrue(Double.isNaN(metrics.hitRate()), "inventou um acerto sem operacoes");
        assertTrue(Double.isNaN(metrics.barsHeld()), "inventou uma duracao sem operacoes");
        assertEquals(0, Result.empty().worth().length, "inventou patrimonio sem barras");
    }

    /** Sem isto o compilador reclama do import de Desk, usado só pelas lambdas. */
    @Test
    @DisplayName("a mesa continua sendo o que a estrategia chama")
    void theDeskIsWhatAStrategyTalksTo() {
        assertEquals(2, new Desk(2).lot(), "a mesa esqueceu o lote");
    }
}
