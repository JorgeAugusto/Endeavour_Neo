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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.indicator.Regression;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.trading.Backtest;
import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O fade de canal: a geometria e as três condições.
 *
 * <p>A geometria é o que mais importa, porque é a única parte que um resultado
 * não denuncia: um nível calculado no lado errado da linha vira uma estratégia
 * que <b>compra</b> o estição em vez de vendê-lo, e ela continua produzindo
 * operações e um número no fim. Foi o primeiro erro que estes testes pegaram,
 * e não no código — na fixture: um salto grande o bastante para inverter a
 * inclinação do canal, e aí comprar passa a ser o certo.</p>
 */
@DisplayName("Fade de canal")
class ChannelFadeTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    /**
     * Preços ditados, um por minuto, todos do MESMO pregão.
     *
     * <p>A hora importa. Começando na época zero, as 280 barras atravessam a
     * meia-noite de São Paulo, o fim de pregão zera o crédito do latch no meio
     * do teste, e nada opera — o que parece defeito do produto e é da
     * fixture.</p>
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
            return price[index] + 5;
        }

        @Override
        public double lowAt(int index) {
            return price[index] - 5;
        }

        @Override
        public double closeAt(int index) {
            return price[index];
        }
    }

    /** Arma a VENDA na primeira barra e nunca desarma: o latch sai da frente. */
    private static final StochasticLatch.Settings VENDE =
            new StochasticLatch.Settings(true, 1, 1, -1, 0, -1, 9);

    /** Onde o pico começa, e portanto onde a venda acontece. */
    private static final int PICO = 241;

    /**
     * Sobe duzentas barras, vira e cai; um pico de três barras na 241.
     *
     * <p>A virada é o que faz este pregão servir: na barra 240 o canal de 45
     * já aponta para BAIXO (−18,9 por barra) e o de 90 ainda aponta para CIMA
     * (+3,1) — medido. É a única janela em que os dois discordam com o preço
     * posicionado para operar, e é o que põe a regra dos dois canais à
     * prova.</p>
     *
     * <p>O dente de serra dá desvio para haver borda; o pico de 600 leva o
     * preço além de duas deviações do canal de 45 <b>sem</b> virar a
     * inclinação dele — também medido, e é a diferença entre este pregão e o
     * primeiro que escrevi, onde o salto virava o canal e comprar passava a
     * ser o certo.</p>
     */
    private static double[] pregao() {
        double[] price = new double[330];

        for (int bar = 0; bar < price.length; bar++) {
            double base = bar < 200 ? 100_000 + 20.0 * bar : 104_000 - 20.0 * (bar - 200);

            price[bar] = base + (bar % 2 == 0 ? 100 : -100);

            if (bar >= PICO && bar < PICO + 3) {
                price[bar] += 600;
            }

            // A descida de volta, forte o bastante para alcancar as bordas de
            // baixo e mansa o bastante para que dois alvos diferentes executem
            // em barras diferentes.
            if (bar >= PICO + 6) {
                price[bar] -= 40.0 * (bar - PICO - 5);
            }
        }

        return price;
    }

    /**
     * O mesmo pregão, com o estição subindo em DEGRAUS em vez de um salto só.
     *
     * <p>Um salto único põe o preço além das duas bordas na mesma barra, e aí só
     * um degrau da escada entra: o segundo nível fica <b>abaixo</b> do preço, e
     * um limite ali executaria na hora a um preço que a tese nunca pediu — então
     * {@code enter} o descarta, com razão. Subindo aos poucos o preço cruza uma
     * borda, respira, e cruza a seguinte: é a única forma de ter dois lotes na
     * mão para comparar os alvos deles.</p>
     */
    private static double[] pregaoEmDegraus() {
        double[] price = pregao();

        for (int bar = PICO; bar < PICO + 6; bar++) {
            // Desfaz o salto plano e põe a rampa no lugar dele.
            if (bar < PICO + 3) {
                price[bar] -= 600;
            }

            price[bar] += 150.0 * (bar - PICO + 1);
        }

        return price;
    }

    private static Result run(double[] price, List<ChannelFade.Rung> ladder,
                              StochasticLatch.Settings latch) {

        PriceSeries bars = new Bars(price);
        ChannelFade what = new ChannelFade(SP, ladder, 1, latch);

        what.sourcedFrom(bars);

        return new Backtest(Costs.NONE, 1).run(bars, bars, what, null);
    }

    private static Result run(List<ChannelFade.Rung> ladder,
                              StochasticLatch.Settings latch) {

        return run(pregao(), ladder, latch);
    }

    private static List<ChannelFade.Rung> umDegrau(int periodo, double entrada, double alvo) {
        return List.of(new ChannelFade.Rung(periodo, entrada, alvo));
    }

    private static List<Fill> aberturas(Result result) {
        List<Fill> abriu = new ArrayList<>();

        for (Fill fill : result.fills()) {
            if (!fill.verb().contains("Cover") && !fill.verb().contains("Close")) {
                abriu.add(fill);
            }
        }

        return abriu;
    }

    /**
     * A primeira execução que FECHA posição, seja pelo alvo ou pelo pregão.
     *
     * <p>As duas contam: o alvo é {@code BuyToCoverLimit} e o fim de dia é
     * {@code ClosePosition}, e um helper que só reconhecesse a primeira daria
     * "nunca fechou" para um lote que fechou no sino.</p>
     */
    private static Fill cobertura(Result result) {
        for (Fill fill : result.fills()) {
            if (fill.verb().contains("Cover") || fill.verb().contains("Close")) {
                return fill;
            }
        }

        return null;
    }

    @Test
    @DisplayName("VENDE NA BORDA DE CIMA de um canal que cai")
    void itsellsAtTheUpperEdgeOfAfallingChannel() {
        Result result = run(umDegrau(Regression.SHORT, 2.0, 2.0), VENDE);
        List<Fill> abriu = aberturas(result);

        assertFalse(abriu.isEmpty(), "nao operou nada");

        Fill entrada = abriu.get(0);

        // VENDIDA. O estição e para CIMA e o canal aponta para BAIXO; trocado o
        // sinal, a estrategia compra o estição e continua dando numero no fim.
        assertEquals("SellShortLimit", entrada.verb(),
                "a entrada nao foi uma venda: " + entrada.verb());

        // E NUNCA ABAIXO DA BORDA DE CIMA. Refeita a conta por fora, sobre a
        // barra da DECISAO -- a anterior a execucao, porque uma ordem pedida no
        // fechamento nao executa na propria barra.
        Regression.Fit fit = new Regression(Regression.SHORT)
                .at(new Bars(pregao()), entrada.bar() - 1);

        assertTrue(fit.known(), "o canal nem tinha janela na barra da decisao");
        assertTrue(fit.direction() < 0, "o canal nao estava caindo: " + fit.slope());

        // Maior OU IGUAL, e nao igual: um limite executa no melhor entre o nivel
        // e a abertura, e aqui o preco saltou por cima -- o que uma venda recebe
        // de bom. Exigir igualdade seria exigir que o mercado nunca desse gap.
        assertTrue(entrada.price() >= fit.at(2.0) - 1e-6,
                "a venda saiu ABAIXO da borda de cima: " + entrada.price()
                        + " contra " + fit.at(2.0));
    }

    @Test
    @DisplayName("O ALVO VEM DO DEGRAU: 1,75 sai antes e mais caro que 2,0")
    void thetargetComesFromTheRung() {
        Fill comDois = cobertura(run(umDegrau(Regression.SHORT, 2.0, 2.0), VENDE));
        Fill comUmEtresQuartos = cobertura(run(umDegrau(Regression.SHORT, 2.0, 1.75), VENDE));

        assertNotNull(comDois, "a rodada de alvo 2,0 nao cobriu");
        assertNotNull(comUmEtresQuartos, "a rodada de alvo 1,75 nao cobriu");

        // O alvo mais PERTO da linha e alcancado ANTES pelo preco que desce, e
        // por isso mais caro -- menos lucro numa venda. Se o alvo nao viesse do
        // degrau, as duas rodadas sairiam na mesma barra e no mesmo preco.
        assertTrue(comUmEtresQuartos.bar() < comDois.bar(),
                "o alvo de 1,75 nao saiu antes do de 2,0: barra "
                        + comUmEtresQuartos.bar() + " contra " + comDois.bar());

        assertTrue(comUmEtresQuartos.price() > comDois.price(),
                "o alvo de 1,75 nao saiu mais caro que o de 2,0: "
                        + comUmEtresQuartos.price() + " contra " + comDois.price());

        // E nenhum dos dois executa PIOR que o nivel pedido: uma cobertura de
        // venda e uma compra, e uma compra com limite nao paga acima dele.
        Regression.Fit fit = new Regression(Regression.SHORT)
                .at(new Bars(pregao()), comDois.bar() - 1);

        assertTrue(comDois.price() <= fit.at(-2.0) + 1e-6,
                "a cobertura pagou acima da borda de baixo: " + comDois.price()
                        + " contra " + fit.at(-2.0));
    }

    @Test
    @DisplayName("COM DOIS LOTES NA MAO, quem fica de pe e o alvo MAIS PERTO")
    void withTwoLotsOnItIsTheNearestTargetThatRests() {
        // O degrau de DENTRO entra primeiro e mira LONGE; o de fora entra depois
        // e mira PERTO. Assim o lote mais antigo -- o unico que tinha ordem de pe
        // -- e justamente aquele que o preco nao alcanca, e uma escada que era
        // para sair em pedacos sai inteira, no sino ou no stop.
        List<ChannelFade.Rung> escada = List.of(
                new ChannelFade.Rung(Regression.SHORT, 1.5, 3.0),
                new ChannelFade.Rung(Regression.SHORT, 2.5, 1.0));

        Result result = run(pregaoEmDegraus(), escada, VENDE);

        assertEquals(2, aberturas(result).size(),
                "os dois degraus nao entraram: " + aberturas(result).size());

        List<Fill> fechou = new ArrayList<>();

        for (Fill fill : result.fills()) {
            if (fill.verb().contains("Cover") || fill.verb().contains("Close")) {
                fechou.add(fill);
            }
        }

        assertFalse(fechou.isEmpty(), "nao fechou nada");

        // UMA PARCIAL, e nao o pacote todo de uma vez. O alvo de 1,0 esta ao
        // alcance e o de 3,0 nao: com o alvo certo de pe um contrato sai pelo
        // alvo, e o outro fica.
        assertEquals("BuyToCoverLimit", fechou.get(0).verb(),
                "a primeira saida nao foi pelo alvo: " + fechou.get(0).verb());

        assertEquals(1, fechou.get(0).quantity(),
                "a primeira saida levou a mao inteira: " + fechou.get(0).quantity());

        assertTrue(fechou.size() > 1, "o segundo lote nunca saiu");

        // E foi o alvo PERTO que executou. Refeita a conta por fora, sobre a
        // barra da decisao.
        Regression.Fit fit = new Regression(Regression.SHORT)
                .at(new Bars(pregaoEmDegraus()), fechou.get(0).bar() - 1);

        assertTrue(fechou.get(0).price() <= fit.at(-1.0) + 1e-6,
                "a parcial pagou acima da borda de 1,0: " + fechou.get(0).price()
                        + " contra " + fit.at(-1.0));

        assertTrue(fechou.get(0).price() > fit.at(-3.0),
                "a parcial saiu no alvo de 3,0, o do lote velho: "
                        + fechou.get(0).price() + " contra " + fit.at(-3.0));
    }

    @Test
    @DisplayName("OS DOIS CANAIS TEM DE CONCORDAR: um discordando, nao opera")
    void bothchannelsHaveToAgree() {
        PriceSeries bars = new Bars(pregao());
        int decidiu = PICO - 1;

        // A BARRA DA DECISAO TEM DISCORDANCIA, e isso e lido dos proprios
        // canais em vez de suposto: sem esta medida, o par de rodadas abaixo
        // passaria tambem num pregao em que os dois sempre concordam.
        assertTrue(new Regression(Regression.SHORT).at(bars, decidiu).direction() < 0,
                "o canal de 45 nao estava caindo na barra da decisao");
        assertTrue(new Regression(Regression.LONG).at(bars, decidiu).direction() > 0,
                "o canal de 90 nao estava subindo na barra da decisao");

        // So o de 45 na escada: ele cai, e a venda acontece.
        List<Fill> sozinho = aberturas(run(umDegrau(Regression.SHORT, 2.0, 2.0), VENDE));

        assertFalse(sozinho.isEmpty(), "o canal de 45 sozinho nao operou");
        assertEquals(PICO, sozinho.get(0).bar(),
                "a venda nao saiu no pico: barra " + sozinho.get(0).bar());

        // O de 90 entra na escada e discorda: nada e operado ali.
        List<ChannelFade.Rung> dois = List.of(
                new ChannelFade.Rung(Regression.SHORT, 2.0, 2.0),
                new ChannelFade.Rung(Regression.LONG, 2.0, 2.0));

        for (Fill fill : aberturas(run(dois, VENDE))) {
            assertTrue(fill.bar() != PICO,
                    "operou no pico com o canal de 90 apontando para o outro lado");
        }
    }

    @Test
    @DisplayName("SEM CREDITO NAO ENTRA, e o credito vem do estocastico")
    void withoutCreditNothingEnters() {
        assertFalse(aberturas(run(umDegrau(Regression.SHORT, 2.0, 2.0), VENDE)).isEmpty(),
                "com credito aberto nao operou");

        // Nivel de venda inalcancavel: o estocastico nunca arma a venda.
        StochasticLatch.Settings nunca =
                new StochasticLatch.Settings(true, 1, 1, -1, 101, -1, 9);

        assertTrue(aberturas(run(umDegrau(Regression.SHORT, 2.0, 2.0), nunca)).isEmpty(),
                "vendeu sem o estocastico ter armado a venda");
    }

    @Test
    @DisplayName("NADA ATRAVESSA O PREGAO, nem um lote que nunca alcancou o alvo")
    void nothingCrossesTheSession() {
        // Um alvo a QUARENTA deviacoes: o preco nunca chega la, entao o lote so
        // pode sair de um jeito -- pelo fim do pregao. Com um alvo alcancavel
        // este teste passa sem exercitar nada, porque a posicao ja saiu antes.
        Result result = run(umDegrau(Regression.SHORT, 2.0, 40.0), VENDE);

        assertFalse(aberturas(result).isEmpty(), "nao chegou a abrir posicao");
        assertNotNull(cobertura(result), "o lote nunca foi fechado");

        assertEquals(0, result.openAtTheEnd(), "sobrou posicao no fim da varredura");

        int posicao = 0;

        for (Fill fill : result.fills()) {
            posicao += fill.signed();
        }

        assertEquals(0, posicao, "as execucoes nao se fecham");
    }
}
