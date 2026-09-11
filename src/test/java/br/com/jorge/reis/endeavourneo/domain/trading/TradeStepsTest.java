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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Os giros de uma operação, um a um.
 *
 * <p>Uma entrada e uma saída são a história toda de uma operação que entrou uma
 * vez. De uma que sobe em escada e sai em pedaços, são uma <b>média</b> de
 * muitas histórias — e a média esconde justamente o que interessa olhar: que a
 * primeira parcial ganhou e as duas últimas devolveram.</p>
 */
@DisplayName("Os giros de uma operacao")
class TradeStepsTest {

    /** Preços que o teste dita, uma barra por número. */
    private record Bars(double[] price) implements PriceSeries {

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

    /** Sobe em escada de dois em dois e sai em dois pedaços. */
    private static Result ladder() {
        Bars bars = new Bars(new double[] {100, 100, 110, 120, 130, 140, 140});

        return new Backtest(Costs.NONE, 1).run(bars, (market, desk) -> {
            if (market.bar() == 0 || market.bar() == 1 || market.bar() == 2) {
                desk.buyAtMarket(2);
            }

            if (market.bar() == 4) {
                desk.sellToCoverAtMarket(2);
            }

            if (market.bar() == 5) {
                desk.closePosition();
            }
        });
    }

    @Test
    @DisplayName("CADA EXECUCAO VIRA UM PASSO, na ordem em que aconteceu")
    void everyfillBecomesAstepInTheOrderItHappened() {
        Trade trade = ladder().trades().get(0);
        List<Trade.Step> steps = trade.steps();

        assertEquals(trade.fills().size(), steps.size(),
                "os passos nao batem com as execucoes");

        // A ESCADA FICA LEGIVEL: 2, 4, 6, depois 4 e 0. Uma lista de quantidades
        // nao conta essa historia; a coluna do que ficou aberto conta.
        assertEquals(List.of(2, 4, 6, 4, 0),
                steps.stream().map(Trade.Step::held).toList(),
                "a posicao em aberto depois de cada passo nao bate");

        assertEquals(List.of(true, true, true, false, false),
                steps.stream().map(Trade.Step::opening).toList(),
                "os passos nao souberam quais abriram e quais fecharam");
    }

    @Test
    @DisplayName("uma abertura nao realiza nada, e uma saida realiza contra a media")
    void anopeningRealisesNothingAndAnexitRealisesAgainstTheAverage() {
        List<Trade.Step> steps = ladder().trades().get(0).steps();

        for (Trade.Step step : steps) {
            if (step.opening()) {
                assertEquals(0, step.points(), 0.0,
                        "uma entrada realizou pontos, o que nao existe");
            }
        }

        // Entrou 2 a 100, 2 a 110 e 2 a 120 -- as ordens a mercado executam na
        // ABERTURA SEGUINTE, que e o que faz os precos serem esses e nao os da
        // barra em que foram pedidas. Media 110. Saiu 2 a 140, entao 30 pontos
        // vezes dois contratos.
        //
        // CONTRA A MEDIA DO QUE ESTAVA ABERTO, e nao contra uma entrada
        // escolhida: os contratos nao se distinguem, e casar uma saida com uma
        // entrada precisaria de uma regra -- PEPS? UEPS? -- e a regra mudaria os
        // numeros. E a mesma conta que o motor faz.
        assertEquals(110, steps.get(2).average(), 1e-9, "a media da escada nao e 110");
        assertEquals(60, steps.get(3).points(), 1e-9, "a parcial nao realizou 60 pontos");
        assertEquals(110, steps.get(3).average(), 1e-9,
                "a media mudou quando uma parcial saiu, e uma saida nao muda media");
        assertEquals(120, steps.get(4).points(), 1e-9,
                "a saida final nao levou os quatro contratos que sobraram");
    }

    @Test
    @DisplayName("os passos somam exatamente o bruto da operacao")
    void thestepsAddUpToTheTradesGross() {
        Trade trade = ladder().trades().get(0);

        double somado = 0;

        for (Trade.Step step : trade.steps()) {
            somado += step.points();
        }

        // A prova de que a aritmetica dos passos e a mesma do motor, e nao uma
        // segunda conta parecida que um dia diverge.
        assertEquals(trade.gross(), somado, 1e-9,
                "os passos somam " + somado + " e a operacao diz " + trade.gross());
    }

    @Test
    @DisplayName("GIRADOS NAO E A POSICAO MAXIMA")
    void turnedIsNotThePeakPosition() {
        Result result = ladder();
        Trade trade = result.trades().get(0);

        // A posicao maxima foi seis; girados foram seis tambem, porque esta
        // escada nunca reusou espaco liberado.
        assertEquals(6, trade.contracts(), "a posicao maxima nao foi seis");
        assertEquals(6, trade.turned(), "os girados nao foram seis");
        assertEquals(6, result.contractsTurned(), "a varredura nao contou os seis girados");
    }

    @Test
    @DisplayName("reusar o espaco liberado gira mais contratos que o teto")
    void refillingTheFreedRoomTurnsMoreThanTheCeiling() {
        Bars bars = new Bars(new double[] {100, 100, 110, 120, 110, 120, 130, 130});

        Result result = new Backtest(Costs.NONE, 1).run(bars, (market, desk) -> {
            // Entra 4, sai 2, entra 2, sai 2, entra 2, e fecha.
            if (market.bar() == 0) {
                desk.buyAtMarket(4);
            }

            if (market.bar() == 1 || market.bar() == 3) {
                desk.sellToCoverAtMarket(2);
            }

            if (market.bar() == 2 || market.bar() == 4) {
                desk.buyAtMarket(2);
            }

            if (market.bar() == 6) {
                desk.closePosition();
            }
        });

        Trade trade = result.trades().get(0);

        // ESSA E A DISTINCAO INTEIRA. A especificacao da Range 90 diz em
        // palavras: "contracts_turned nao e a posicao maxima. Se contratos forem
        // encerrados, a capacidade liberada pode ser reutilizada". Vinte de
        // posicao maxima podem ser seiscentos girados -- e e sobre os
        // seiscentos que a corretagem e cobrada.
        assertEquals(4, trade.contracts(), "a posicao maxima nao foi quatro");
        assertEquals(8, trade.turned(),
                "girados deviam ser oito: quatro de entrada e mais dois recuos de dois");
        assertTrue(trade.turned() > trade.contracts(),
                "girados nao passou da posicao maxima, entao este teste nao provou nada");
    }

    @Test
    @DisplayName("a posicao que ficou aberta no fim conta nos girados")
    void thepositionLeftOpenAtTheEndCountsAsTurned() {
        Bars bars = new Bars(new double[] {100, 100, 110});

        Result result = new Backtest(Costs.NONE, 1).run(bars, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(3);
            }
        });

        // Contado pelas execucoes e nao somado das operacoes: esses contratos
        // foram abertos e pagos, tenha alguma coisa os fechado ou nao.
        assertTrue(result.trades().isEmpty(), "a posicao aberta virou operacao");
        assertEquals(3, result.contractsTurned(),
                "os contratos que ficaram abertos sumiram dos girados");
        assertFalse(result.fills().isEmpty(), "nao houve execucao nenhuma");
    }
}
