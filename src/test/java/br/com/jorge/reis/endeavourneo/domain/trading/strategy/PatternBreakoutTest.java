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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.CandlePattern;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.trading.Backtest;
import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O rompimento do padrão, sobre barras ditadas à mão.
 *
 * <p>O que estes testes defendem são as quatro regras que o original registra e
 * que são fáceis de errar sem que nada quebre: a entrada fica <b>além</b> do
 * extremo e não nele; o stop é o extremo da <b>estrutura</b> e não o da barra
 * do sinal; o alvo é medido da <b>execução</b> e não do nível pedido; e as três
 * barras têm de ser do <b>mesmo pregão</b> — como tem de ser, também, a saída.</p>
 */
@DisplayName("Rompimento de padrao")
class PatternBreakoutTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    /** Um tique do índice: o que separa o gatilho do extremo. */
    private static final double TICK = PatternBreakout.TICK;

    /**
     * Barras ditadas, uma por minuto, a partir das 10:00.
     *
     * <p>{@code dia} diz a que pregão cada barra pertence, e é o único jeito de
     * pôr a virada da noite no meio de sete barras.</p>
     */
    private record Bars(double[][] ohlc, int[] dia) implements PriceSeries {

        @Override
        public int size() {
            return ohlc.length;
        }

        @Override
        public long timeAt(int index) {
            return ZonedDateTime.of(LocalDate.of(2025, 1, 6).plusDays(dia[index]),
                    LocalTime.of(10, 0), SP).toInstant().toEpochMilli()
                    + index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return ohlc[index][0];
        }

        @Override
        public double highAt(int index) {
            return ohlc[index][1];
        }

        @Override
        public double lowAt(int index) {
            return ohlc[index][2];
        }

        @Override
        public double closeAt(int index) {
            return ohlc[index][3];
        }
    }

    private static int[] umPregaoSo(int quantas) {
        return new int[quantas];
    }

    /**
     * Sete barras com um PFR de alta na terceira.
     *
     * <p>A barra do sinal faz a menor mínima das três (80) e fecha em 108, acima
     * do fechamento anterior (100) e da própria abertura (85). O topo dela é
     * 110, então o gatilho é 115 e o stop é 80.</p>
     */
    private static double[][] pfrDeAlta() {
        return new double[][] {
            {100, 110, 95, 105},
            {105, 112, 90, 100},
            {85, 110, 80, 108},     // o PFR: minima 80, topo 110
            {108, 130, 107, 128},   // rompe o topo: a entrada dispara
            {128, 135, 125, 130},
            {130, 132, 60, 70},
            {70, 75, 65, 70},
        };
    }

    private static Result run(double[][] ohlc, int[] dia, CandlePattern.Family forma,
                              double alvo, int validade) {

        PriceSeries bars = new Bars(ohlc, dia);

        return new Backtest(Costs.NONE, 1)
                .run(bars, bars, new PatternBreakout(SP, forma, alvo, validade, 1), null);
    }

    @Test
    @DisplayName("A ENTRADA FICA ALEM DO EXTREMO, e nao nele")
    void theentrySitsBeyondTheExtremeAndNotOnIt() {
        Result result = run(pfrDeAlta(), umPregaoSo(7), CandlePattern.Family.PFR, 1.5, 3);

        assertFalse(result.fills().isEmpty(), "o padrao nao gerou operacao nenhuma");

        Fill entrada = result.fills().get(0);

        // Um tique ALEM do topo. No proprio topo, a ordem executa num toque que
        // nao rompeu coisa nenhuma -- e a tese do padrao e o rompimento dele.
        assertEquals("BuyStop", entrada.verb(), "a primeira execucao nao foi o gatilho de compra");
        assertEquals(110 + TICK, entrada.price(), 1e-9,
                "a entrada nao saiu um tique acima do topo do padrao");
    }

    @Test
    @DisplayName("O STOP E O EXTREMO DA ESTRUTURA, e nao o da barra do sinal")
    void thestopIsTheExtremeOfTheStructure() {
        // Um 1-2-3 de compra: o pivo e a barra do MEIO, que faz a menor minima
        // (85). A barra do sinal tem minima 88 -- usar a dela poria o stop
        // DENTRO da estrutura, onde o padrao ainda nao falhou.
        double[][] ohlc = {
            {100, 110, 95, 105},
            {104, 106, 85, 90},     // o pivo: menor minima das tres
            {92, 110, 88, 103},     // o sinal: confirma fechando em alta
            {103, 130, 102, 128},   // rompe: entra em 115
            {128, 130, 80, 84},     // afunda ate 80, passando do pivo
            {84, 86, 82, 85},
        };

        Result result = run(ohlc, umPregaoSo(6), CandlePattern.Family.ONE_TWO_THREE, 1.5, 3);

        assertEquals(2, result.fills().size(),
                "esperava entrada e saida, veio " + result.fills().size());

        Fill saida = result.fills().get(1);

        assertEquals(85, saida.price(), 1e-9,
                "o stop nao foi a minima da estrutura, foi " + saida.price());
        assertTrue(saida.verb().contains("Stop"), "a saida nao foi por stop: " + saida.verb());
    }

    @Test
    @DisplayName("AS TRES BARRAS TEM DE SER DO MESMO PREGAO")
    void thethreeBarsHaveToBeInOneSession() {
        // O MESMO PFR, com a virada do dia caindo no meio dele. Sem a guarda, a
        // primeira barra de um pregao vira "minima nova" pelo salto da noite, e
        // o padrao passa a ser propriedade do gap.
        int[] virou = {0, 0, 1, 1, 1, 1, 1};

        assertTrue(run(pfrDeAlta(), virou, CandlePattern.Family.PFR, 1.5, 3)
                .fills().isEmpty(),
                "operou um padrao montado sobre a virada do dia");

        // E o mesmo desenho dentro de um pregao so opera -- o que prova que a
        // recusa acima veio da guarda, e nao do desenho.
        assertFalse(run(pfrDeAlta(), umPregaoSo(7), CandlePattern.Family.PFR, 1.5, 3)
                .fills().isEmpty(),
                "o mesmo padrao dentro de um pregao tambem nao operou");
    }

    @Test
    @DisplayName("A SAIDA DO FIM DO DIA EXECUTA NO DIA, e nao na abertura seguinte")
    void theendOfDayExitFillsInsideTheSession() {
        // Entra e o pregao acaba antes de stop ou alvo. ClosePosition e uma
        // ordem A MERCADO, e ordem a mercado executa na ABERTURA DA BARRA
        // SEGUINTE: mandada na ultima barra do dia, ela executaria amanha, e o
        // preco da saida seria o gap da noite. Por isso ela sai uma barra antes.
        double[][] ohlc = {
            {100, 110, 95, 105},
            {105, 112, 90, 100},
            {85, 110, 80, 108},     // o PFR: gatilho 115
            {108, 130, 107, 128},   // entra em 115; penultima do dia
            {128, 132, 126, 130},   // ultima do dia: e aqui que a saida executa
            {200, 210, 195, 205},   // outro pregao, a 70 pontos de distancia
        };

        Result result = run(ohlc, new int[] {0, 0, 0, 0, 0, 1},
                CandlePattern.Family.PFR, 99, 3);

        assertEquals(2, result.fills().size(),
                "esperava entrada e saida, veio " + result.fills().size());

        Fill saida = result.fills().get(1);

        assertEquals(4, saida.bar(), "a saida caiu fora do pregao, na barra " + saida.bar());
        assertEquals(128, saida.price(), 1e-9,
                "a saida nao foi na abertura da ultima barra do dia, foi em " + saida.price());

        assertEquals(0, result.openAtTheEnd(), "sobrou posicao no fim da varredura");
    }

    @Test
    @DisplayName("a entrada expira: um gatilho sem prazo ficaria de pe para sempre")
    void theentryExpires() {
        // O rompimento so acontece quatro barras depois do padrao. Com validade
        // de 1, a ordem ja morreu; com prazo longo, ela pega.
        double[][] ohlc = {
            {100, 110, 95, 105},
            {105, 112, 90, 100},
            {85, 110, 80, 108},     // o PFR: gatilho 115
            {108, 109, 100, 105},   // nao rompe
            {105, 109, 100, 104},
            {104, 108, 100, 103},
            {103, 130, 102, 128},   // so agora rompe
            {128, 132, 126, 130},
        };

        assertTrue(run(ohlc, umPregaoSo(8), CandlePattern.Family.PFR, 1.5, 1)
                .fills().isEmpty(),
                "o gatilho de uma barra atras pegou um rompimento quatro barras depois");

        assertFalse(run(ohlc, umPregaoSo(8), CandlePattern.Family.PFR, 1.5, 10)
                .fills().isEmpty(),
                "com prazo longo o mesmo rompimento tambem nao pegou");
    }

    @Test
    @DisplayName("UM SALTO POR CIMA DO GATILHO NAO ENTRA: o limite e o proprio gatilho")
    void agapOverTheTriggerDoesNotEnter() {
        // A ordem e BuyStop(gatilho, gatilho) -- a forma do NTSL, a mesma que o
        // robo manda no Profit. O limite E o gatilho, entao uma barra que ABRE
        // acima dele nao executa: pagar 200 numa ordem de 115 e justamente o
        // que o limite existe para recusar. O padrao que o mercado salta e um
        // padrao perdido, aqui e la.
        double[][] saltou = {
            {100, 110, 95, 105},
            {105, 112, 90, 100},
            {85, 110, 80, 108},     // PFR: gatilho 115
            {200, 210, 199, 205},   // abre 85 pontos acima do gatilho
            {205, 210, 200, 205},
            {205, 210, 200, 205},
            {205, 210, 200, 205},
        };

        assertTrue(run(saltou, umPregaoSo(7), CandlePattern.Family.PFR, 1.5, 3)
                .fills().isEmpty(),
                "entrou num gatilho que o mercado saltou por cima");

        // E o MESMO padrao, numa barra que sobe atravessando o nivel, entra em
        // 115 -- o que prova que a recusa acima veio do limite, e nao do padrao.
        Result andou = run(pfrDeAlta(), umPregaoSo(7), CandlePattern.Family.PFR, 1.5, 3);

        assertFalse(andou.fills().isEmpty(), "a barra que atravessou o nivel tambem nao entrou");
        assertEquals(110 + TICK, andou.fills().get(0).price(), 1e-9,
                "a entrada da barra que atravessou nao saiu no gatilho");
    }

    @Test
    @DisplayName("O ALVO E O RISCO VEZES A RAZAO PEDIDA, e mudar a razao move o alvo")
    void thetargetIsTheRiskTimesTheRatio() {
        // Gatilho 115, stop 80: o risco e 35. A 2R o alvo e 185, a 1R e 150 --
        // e a mesma barra alcanca os dois, entao o preco da saida so pode ter
        // vindo da conta.
        double[][] ohlc = {
            {100, 110, 95, 105},
            {105, 112, 90, 100},
            {85, 110, 80, 108},     // PFR: gatilho 115, stop 80, risco 35
            {108, 130, 107, 128},   // entra em 115
            {128, 190, 127, 185},   // sobe ate 190: passa por 150 e por 185
            {185, 190, 180, 185},
            {185, 190, 180, 185},
        };

        Result doisR = run(ohlc, umPregaoSo(7), CandlePattern.Family.PFR, 2.0, 3);

        assertEquals(2, doisR.fills().size(),
                "esperava entrada e alvo, veio " + doisR.fills().size());
        assertEquals(185, doisR.fills().get(1).price(), 1e-9,
                "o alvo de 2R nao saiu em 115 + 70, saiu em " + doisR.fills().get(1).price());

        Result umR = run(ohlc, umPregaoSo(7), CandlePattern.Family.PFR, 1.0, 3);

        assertEquals(150, umR.fills().get(1).price(), 1e-9,
                "o alvo de 1R nao saiu em 115 + 35, saiu em " + umR.fills().get(1).price());
    }

    @Test
    @DisplayName("uma forma so e operada por vez")
    void onlyOneShapeIsTradedAtATime() {
        // O mesmo desenho, pedido como inside, nao opera: ele e PFR.
        assertTrue(run(pfrDeAlta(), umPregaoSo(7), CandlePattern.Family.INSIDE, 1.5, 3)
                .fills().isEmpty(),
                "a estrategia operou um PFR quando lhe pediram inside");
    }
}
