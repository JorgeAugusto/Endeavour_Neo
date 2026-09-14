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

import br.com.jorge.reis.endeavourneo.domain.indicator.Rsi;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.trading.Backtest;
import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Side;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O IFR2 do modelo do TradingView: IFR(2) baixo, filtro de média, saída na máxima. */
@DisplayName("IFR 2 operado")
class RsiSnapbackTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    /** A média do teste, curta o bastante para caber numa fixture de 400 barras. */
    private static final int TREND = 100;

    private static final double OVERSOLD = RsiSnapback.OVERSOLD;

    private static final int EXIT_BARS = RsiSnapback.EXIT_BARS;

    /**
     * O PAVIO, e por que ele é largo.
     *
     * <p>O alvo é a máxima das duas últimas barras, e com pavio estreito essa
     * máxima fica <b>abaixo</b> da abertura da barra seguinte numa alta: a ordem
     * casa na abertura e não no limite, e o teste da saída deixa de ter opinião
     * sobre qual máxima foi pedida. Com trezentos pontos o limite é que manda, e
     * trocar dois períodos por um muda o preço executado.</p>
     */
    private static final double WICK = 300;

    /**
     * Uma série de barras, com abertura e fechamento no mesmo preço.
     *
     * <p>Abertura igual ao fechamento é deliberado: a entrada é a mercado e
     * executa na abertura da barra seguinte, então o preço de entrada fica
     * previsível e a conta do alvo pode ser feita à mão.</p>
     */
    private record Bars(double[] price) implements PriceSeries {

        @Override
        public int size() {
            return price.length;
        }

        @Override
        public long timeAt(int index) {
            return ZonedDateTime.of(LocalDate.of(2025, 1, 6), LocalTime.of(9, 0), SP)
                    .toInstant().toEpochMilli() + index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return price[index];
        }

        @Override
        public double highAt(int index) {
            return price[index] + WICK;
        }

        @Override
        public double lowAt(int index) {
            return price[index] - WICK;
        }

        @Override
        public double closeAt(int index) {
            return price[index];
        }
    }

    /**
     * A serra: vinte barras de alta e DUAS de queda funda, repetidas.
     *
     * <p>Os dois números são o que o IFR de dois períodos exige, e não gosto
     * arbitrário. Com suavização de Wilder em dois períodos, depois de uma
     * sequência de altas de {@code R} e duas quedas de {@code F} o índice vale
     * {@code R/(3F)} — então uma queda do tamanho da alta dá IFR perto de 25, e
     * não perto de zero. Só com {@code F} bem maior que {@code 3R} o índice
     * desce abaixo de dez, que é o nível do modelo. Daí 200 contra 900.</p>
     *
     * <p>E vinte barras de alta, não cinco, porque a média de cem precisa ficar
     * bem abaixo do fundo para o filtro deixar passar — é a inclinação que
     * sustenta o fundo acima da média, exatamente como duzentos dias sustentam
     * uma queda de dois dias no gráfico diário.</p>
     */
    private static Bars saw(int size) {
        double[] made = new double[size];
        double at = 100_000;

        for (int i = 0; i < size; i++) {
            at += i % 22 < 20 ? 200 : -900;
            made[i] = at;
        }

        return new Bars(made);
    }

    /** A serra, e depois a ladeira: a partir de {@code from} só cai. */
    private static Bars sawThenCrash(int until, int fall) {
        Bars rising = saw(until);
        double[] made = new double[until + fall];

        System.arraycopy(rising.price(), 0, made, 0, until);

        for (int i = 0; i < fall; i++) {
            made[until + i] = made[until - 1] - 200.0 * (i + 1);
        }

        return new Bars(made);
    }

    /**
     * O degrau: uma subida longa, um mergulho de duas barras, e entao uma barra
     * de BAIXA com a posicao ja aberta.
     *
     * <p>Essa barra de baixa e a razao de a fixture existir, e a serra nao a
     * tem. Na serra a cobertura sempre e enviada numa barra de alta, onde a
     * maxima da barra atual ja e a maior das duas ultimas -- entao olhar uma
     * barra ou duas da exatamente o mesmo numero, e o teste da saida nao
     * consegue ter opiniao sobre quantas foram olhadas. Aqui a barra anterior e
     * a mais alta, e as duas leituras divergem em trezentos pontos.</p>
     *
     * <p>Os numeros: sobe 200 por barra ate a 199, cai 1.200 duas vezes -- o IFR
     * de dois periodos desce a 5 e o fechamento ainda esta muito acima da media
     * de cem --, a 202 cai mais 300 com a posicao aberta, e a 203 sobe 400, o
     * bastante para alcancar a maxima da 201 e nao a da 202.</p>
     */
    private static Bars step() {
        double[] made = new double[220];

        for (int i = 0; i < 200; i++) {
            made[i] = 100_000 + 200.0 * i;
        }

        made[200] = made[199] - 1_200;
        made[201] = made[200] - 1_200;
        made[202] = made[201] - 300;
        made[203] = made[201] + 100;

        for (int i = 204; i < made.length; i++) {
            made[i] = made[203] + 200.0 * (i - 203);
        }

        return new Bars(made);
    }

    /** Só ladeira abaixo, do primeiro ao último preço. */
    private static Bars downhill(int size) {
        double[] made = new double[size];

        for (int i = 0; i < size; i++) {
            made[i] = 100_000 - 100.0 * i;
        }

        return new Bars(made);
    }

    // --------------------------------------------------------------- as contas

    /**
     * O IFR, contado por FORA da estratégia.
     *
     * <p>{@link Rsi} é outra classe, com teste próprio; perguntar a ela é ler um
     * segundo relógio, e não o mesmo relógio duas vezes.</p>
     */
    private static double[] index(PriceSeries bars) {
        return new Rsi(Rsi.SHORT, Rsi.Smoothing.CLASSIC).over(bars);
    }

    /**
     * A média simples, refeita aqui à mão.
     *
     * <p>DE PROPÓSITO, e é o ponto mais fácil de errar num teste destes: pedir a
     * média à própria estratégia faria o oráculo andar junto com o produto, e
     * trocar a média simples por exponencial passaria despercebido porque os
     * dois lados da comparação teriam mudado igual.</p>
     */
    private static double[] average(PriceSeries bars, int period) {
        double[] made = new double[bars.size()];

        for (int bar = 0; bar < bars.size(); bar++) {
            if (bar < period - 1) {
                made[bar] = Double.NaN;

                continue;
            }

            double total = 0;

            for (int back = 0; back < period; back++) {
                total += bars.closeAt(bar - back);
            }

            made[bar] = total / period;
        }

        return made;
    }

    private static Result run(PriceSeries bars) {
        return new Backtest(Costs.NONE, 1)
                .run(bars, new RsiSnapback(Rsi.SHORT, OVERSOLD, TREND, EXIT_BARS, 1));
    }

    private static List<Fill> openings(Result result) {
        List<Fill> made = new ArrayList<>();

        for (Fill fill : result.fills()) {
            if (!fill.verb().contains("Cover")) {
                made.add(fill);
            }
        }

        return made;
    }

    private static List<Fill> covers(Result result) {
        List<Fill> made = new ArrayList<>();

        for (Fill fill : result.fills()) {
            if (fill.verb().contains("Cover")) {
                made.add(fill);
            }
        }

        return made;
    }

    // ---------------------------------------------------------------- a entrada

    @Test
    @DisplayName("COMPRA SÓ COM O IFR ABAIXO DO NÍVEL, e sempre comprado")
    void itbuysOnlyBelowTheLevelAndOnlyLong() {
        Bars bars = saw(400);
        Result result = run(bars);

        double[] ifr = index(bars);
        List<Fill> opened = openings(result);

        assertFalse(opened.isEmpty(), "nao abriu posicao nenhuma");

        for (Fill fill : opened) {
            // A ordem sai no fechamento da barra do sinal e executa na ABERTURA
            // da seguinte, entao o sinal esta em bar - 1.
            int signal = fill.bar() - 1;

            assertTrue(signal >= 0, "a execucao da barra " + fill.bar() + " nao tem barra anterior");

            assertEquals(Side.BUY, fill.side(),
                    "o modelo so compra, e a barra " + fill.bar() + " abriu vendida");

            assertTrue(ifr[signal] < OVERSOLD, "abriu na barra " + fill.bar()
                    + " com o IFR da " + signal + " em " + ifr[signal]
                    + ", que nao esta abaixo de " + OVERSOLD);
        }
    }

    @Test
    @DisplayName("NÃO COMPRA ABAIXO DA MÉDIA, por mais sobrevendido que esteja")
    void itneverBuysUnderTheAverage() {
        Bars bars = downhill(400);
        Result result = run(bars);

        double[] ifr = index(bars);
        double[] mean = average(bars, TREND);
        int oversold = 0;

        for (int bar = TREND; bar < bars.size(); bar++) {
            if (ifr[bar] < OVERSOLD && bars.closeAt(bar) <= mean[bar]) {
                oversold++;
            }
        }

        // A FIXTURE PRECISA EXERCER A REGRA. Sem esta linha, uma serie que nunca
        // fica sobrevendida passaria no teste sem que o filtro fosse consultado
        // uma unica vez, e o teste diria "aprovado" sobre nada.
        assertTrue(oversold > 100,
                "a ladeira so ficou sobrevendida em " + oversold + " barras; a fixture nao exerce o filtro");

        assertEquals(List.of(), openings(result),
                "comprou numa serie que so cai, onde o fechamento nunca esteve acima da media");
    }

    // ------------------------------------------------------------------ a saida

    @Test
    @DisplayName("SAI NA MÁXIMA DAS DUAS ÚLTIMAS, no preço exato que ela manda")
    void itleavesAtTheHighOfTheLastTwo() {
        // AS DUAS FIXTURES, e nao so a serra. Na serra a cobertura sempre sai
        // numa barra de alta, onde a maxima de agora ja e a maior das duas -- e
        // ali olhar uma barra ou duas da o mesmo numero, de modo que o teste
        // nao teria opiniao sobre quantas foram olhadas. O degrau segura a
        // posicao atraves de uma barra de BAIXA, que e onde as duas divergem.
        leavesRight(saw(400));
        leavesRight(step());
    }

    private static void leavesRight(Bars bars) {
        Result result = run(bars);

        List<Fill> left = covers(result);

        assertFalse(left.isEmpty(), "nenhuma operacao terminou");

        for (Fill fill : left) {
            // A ordem de cobertura sai no fechamento da barra anterior, com o
            // limite medido ALI -- e casa nesta.
            int sent = fill.bar() - 1;
            double wanted = Double.NEGATIVE_INFINITY;

            for (int back = 0; back < EXIT_BARS && sent - back >= 0; back++) {
                wanted = Math.max(wanted, bars.highAt(sent - back));
            }

            assertTrue(bars.highAt(fill.bar()) >= wanted, "cobriu na barra " + fill.bar()
                    + " sem que a barra alcancasse " + wanted);

            assertEquals(Side.SELL, fill.side(), "cobriu comprando, na barra " + fill.bar());

            // Uma venda limitada casa NO LIMITE, ou na abertura quando a
            // abertura ja esta melhor -- e uma coisa ou a outra, nao a media.
            assertEquals(Math.max(bars.openAt(fill.bar()), wanted), fill.price(), 1e-9,
                    "cobriu na barra " + fill.bar() + " a um preco que a maxima das "
                            + EXIT_BARS + " ultimas nao explica");
        }
    }

    // ------------------------------------------------------------------ o stop

    @Test
    @DisplayName("NÃO TEM STOP DE PERDA: a posição atravessa a ladeira inteira")
    void ithasNoLossStopAtAll() {
        Bars bars = sawThenCrash(220, 150);
        Result result = run(bars);

        List<Fill> all = result.fills();

        assertFalse(all.isEmpty(), "nao operou nada");

        for (Fill fill : all) {
            assertFalse(fill.verb().contains("Stop"),
                    "o modelo nao tem stop, e a barra " + fill.bar() + " executou um " + fill.verb());
        }

        Fill last = all.get(all.size() - 1);

        assertEquals(Side.BUY, last.side(),
                "a ultima execucao foi uma saida: alguma coisa fechou a posicao na queda");

        // O QUE O TESTE REALMENTE PROVA. A posicao aberta na barra 220 atravessa
        // cento e cinquenta barras de queda sem que nada a encerre -- e qualquer
        // stop mais curto que esta distancia teria executado e derrubado a
        // asserticao acima.
        double under = last.price() - bars.closeAt(bars.size() - 1);

        assertTrue(under > 10_000, "a posicao so ficou " + under
                + " pontos no vermelho; a fixture nao prova a ausencia do stop");
    }
}
