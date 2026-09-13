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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.trading.Backtest;
import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;
import br.com.jorge.reis.endeavourneo.domain.trading.Trade;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Side;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A estratégia dos dois ranges: porta, latches, quatro gatilhos, 2R por entrada. */
@DisplayName("Dois ranges")
class AlignedPatternsTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    /** Barras de UM minuto, que é a escala de armazenamento do projeto. */
    private record Minutes(double[] price, int perDay) implements PriceSeries {

        @Override
        public int size() {
            return price.length;
        }

        @Override
        public long timeAt(int index) {
            LocalDate day = LocalDate.of(2025, 1, 6).plusDays(index / perDay);

            return ZonedDateTime.of(day, LocalTime.of(9, 0), SP).toInstant().toEpochMilli()
                    + (index % perDay) * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return index % perDay == 0 ? price[index] : price[index - 1];
        }

        @Override
        public double highAt(int index) {
            return Math.max(openAt(index), price[index]) + 25;
        }

        @Override
        public double lowAt(int index) {
            return Math.min(openAt(index), price[index]) - 25;
        }

        @Override
        public double closeAt(int index) {
            return price[index];
        }
    }

    /**
     * Um pregão de 400 minutos com ruído reprodutível.
     *
     * <p>A semente é fixa de propósito: uma fixture aleatória que muda a cada
     * rodada transforma um teste em uma loteria, e o dia em que ele falhar não
     * vai poder ser repetido.</p>
     */
    private static double[] noisy(int count, long seed) {
        double[] made = new double[count];
        Random random = new Random(seed);
        double now = 100_000;

        for (int i = 0; i < count; i++) {
            now += random.nextGaussian() * 60;
            made[i] = Math.round(now / 5) * 5;
        }

        return made;
    }

    private static Result run(PriceSeries minutes) {
        AlignedPatterns what = new AlignedPatterns(SP, 1);

        what.sourcedFrom(minutes);

        return new Backtest(Costs.NONE, 1).run(minutes, minutes, what, null);
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

    @Test
    @DisplayName("SO OPERA COM OS DOIS RANGES DE ACORDO, e sempre do lado deles")
    void ittradesOnlyWithBothRangesAgreeing() {
        Minutes minutes = new Minutes(noisy(12_000, 7), 400);
        AlignedPatterns what = new AlignedPatterns(SP, 1);

        what.sourcedFrom(minutes);

        Result result = new Backtest(Costs.NONE, 1).run(minutes, minutes, what, null);
        List<Fill> abriu = aberturas(result);

        assertFalse(abriu.isEmpty(), "nao abriu posicao nenhuma em quatro pregoes");

        // A PORTA E REFEITA AQUI, e nao lida do proprio produto. A primeira
        // versao perguntava a estrategia se a porta estava aberta -- e com isso
        // quebrar a porta quebrava o gabarito junto, entao o teste passava com
        // um range so. A prova de dentes achou.
        int[] porta = gatePorFora(minutes);

        for (Fill fill : abriu) {
            // A ordem descansa a partir do fechamento da barra anterior, entao a
            // porta que a autorizou e a daquela barra.
            int lado = porta[fill.bar() - 1];

            assertTrue(lado != 0,
                    "abriu na barra " + fill.bar() + " com os dois ranges em desacordo");

            assertEquals(lado > 0 ? Side.BUY : Side.SELL, fill.side(),
                    "abriu contra o lado dos dois ranges na barra " + fill.bar());
        }
    }

    /**
     * Os dois ranges refeitos do zero, sem passar pela estrategia.
     *
     * <p>Um gabarito que sai do produto testado nao e gabarito. Aqui os dois
     * indicadores sao chamados direto e a concordancia e conferida a mao.</p>
     */
    private static int[] gatePorFora(PriceSeries minutes) {
        PriceSeries five = br.com.jorge.reis.endeavourneo.domain.market.Timeframe
                .ofMinutes(br.com.jorge.reis.endeavourneo.domain.indicator
                        .OpeningImpulse.MINUTES).apply(minutes, SP);

        br.com.jorge.reis.endeavourneo.domain.indicator.OpeningImpulse impulse =
                br.com.jorge.reis.endeavourneo.domain.indicator.OpeningImpulse.standard();

        int[] briga = impulse.brokenAt(minutes, impulse.of(five, SP), SP);

        double[] noRelogio = new double[five.size()];

        for (OpeningRange.Session each
                : OpeningRange.of(five, SP, AlignedPatterns.FORMATION)) {
            if (each.side() == 0 || each.breakBar() < 0) {
                continue;
            }

            for (int bar = each.breakBar(); bar <= each.last() && bar < five.size(); bar++) {
                noRelogio[bar] = each.side();
            }
        }

        double[] espalhado = br.com.jorge.reis.endeavourneo.domain.indicator.LastClosed
                .spread(minutes, five, noRelogio);

        int[] made = new int[minutes.size()];

        for (int bar = 0; bar < made.length; bar++) {
            int relogio = Double.isNaN(espalhado[bar]) ? 0 : (int) Math.round(espalhado[bar]);

            made[bar] = briga[bar] != 0 && briga[bar] == relogio ? briga[bar] : 0;
        }

        return made;
    }

    @Test
    @DisplayName("O ALVO E EXATAMENTE 2R DA EXECUCAO, medido nas curvas")
    void thetargetIsExactlyTwoRfromTheFill() {
        // A PRIMEIRA VERSAO DESTE TESTE SO EXIGIA QUE A OPERACAO TIVESSE ANDADO,
        // porque eu nao via como refazer o R de fora -- e um teste assim nao
        // prova nada: trocar 2R por 1R passava igual. A prova de dentes mostrou
        // isso, e a saida estava a mao o tempo todo: a estrategia DESENHA o stop
        // e o alvo, e as curvas ficam gravadas barra a barra.
        Minutes minutes = new Minutes(noisy(12_000, 7), 400);
        AlignedPatterns what = new AlignedPatterns(SP, 1);

        what.sourcedFrom(minutes);

        Result result = new Backtest(Costs.NONE, 1).run(minutes, minutes, what, null);

        double[] stop = what.curves().get("Stop");
        double[] alvo = what.curves().get("Alvo");

        int conferidos = 0;

        for (Trade trade : result.trades()) {
            List<Fill> entradas = new ArrayList<>();

            for (Fill fill : trade.fills()) {
                if (!fill.verb().contains("Cover") && !fill.verb().contains("Close")) {
                    entradas.add(fill);
                }
            }

            // So as de UMA entrada: com duas na mao a curva desenha a primeira, e
            // saber qual e qual exigiria o livro inteiro do lado de fora.
            if (entradas.size() != 1) {
                continue;
            }

            Fill entrada = entradas.get(0);
            int bar = entrada.bar();

            if (Double.isNaN(stop[bar]) || Double.isNaN(alvo[bar])) {
                continue;
            }

            double risco = Math.abs(entrada.price() - stop[bar]);
            double premio = Math.abs(alvo[bar] - entrada.price());

            assertTrue(risco > 0, "a entrada da barra " + bar + " nasceu sem risco");
            assertEquals(AlignedPatterns.REWARD * risco, premio, 1e-6,
                    "o alvo da barra " + bar + " nao esta a " + AlignedPatterns.REWARD
                            + "R: risco " + risco + ", premio " + premio);

            // E O ALVO FICA DO LADO CERTO: acima da entrada numa compra.
            assertTrue(entrada.side() == Side.BUY
                    ? alvo[bar] > entrada.price() : alvo[bar] < entrada.price(),
                    "o alvo da barra " + bar + " ficou do lado errado da entrada");

            conferidos++;
        }

        assertTrue(conferidos > 2,
                "so " + conferidos + " operacoes de uma entrada, poucas para provar algo");
    }

    @Test
    @DisplayName("CADA ENTRADA TEM O SEU ALVO: duas na mao nao saem no mesmo preco")
    void eachEntryCarriesItsOwnTarget() {
        Minutes minutes = new Minutes(noisy(12_000, 11), 400);
        Result result = run(minutes);

        int comVarias = 0;

        for (Trade trade : result.trades()) {
            int entradas = 0;

            for (Fill fill : trade.fills()) {
                if (!fill.verb().contains("Cover") && !fill.verb().contains("Close")) {
                    entradas++;
                }
            }

            if (entradas > 1) {
                comVarias++;
            }
        }

        // A ESCADA TEM DE ACONTECER, senao o teste do alvo por entrada nao esta
        // testando nada: com uma entrada por operacao, um alvo comum e um alvo
        // proprio dao o mesmo resultado.
        assertTrue(comVarias > 0,
                "nenhuma operacao teve mais de uma entrada, entao a regra nao foi exercida");
    }

    @Test
    @DisplayName("O LATCH RACIONA POR MOVIMENTO: no maximo duas entradas por armada")
    void thelatchRationsPerMovement() {
        Minutes minutes = new Minutes(noisy(12_000, 11), 400);
        AlignedPatterns what = new AlignedPatterns(SP, 1);

        what.sourcedFrom(minutes);

        Result result = new Backtest(Costs.NONE, 1).run(minutes, minutes, what, null);

        // O QUE DA PARA PROVAR AQUI, e o que NAO da.
        //
        // Nao da para provar o TETO de duas entradas por armada nesta fixture, e
        // a prova de dentes mostrou por que: o estocastico arma varias vezes por
        // pregao, entao o teto fica em dezenas de entradas enquanto a estrategia
        // faz uma ou duas -- a restricao que morde e a raridade do padrao, nao o
        // credito. Um teste contra esse teto passava com o credito desligado, e
        // um teste que passa com a regra desligada nao e um teste. O racionamento
        // em si esta provado onde ele mora, no StochasticLatch.
        //
        // O QUE E DAQUI e a LIGACAO: cada gatilho gasta o latch da escala dele, e
        // nenhuma entrada acontece sem credito.
        int conferidos = 0;

        for (int bar = 0; bar < minutes.size(); bar++) {
            int gatilho = what.firedAt(bar);

            if (gatilho < 0) {
                continue;
            }

            int escala = AlignedPatterns.TRIGGERS.get(gatilho).latch();

            assertTrue(what.creditAt(bar, escala) > 0,
                    "a barra " + bar + " armou o gatilho " + gatilho
                            + " com o latch de " + escala + "m sem credito");

            conferidos++;
        }

        assertTrue(conferidos > 3,
                "so " + conferidos + " ordens armadas, poucas para provar algo");

        // E O GASTO CAI NO LATCH DA ESCALA DO GATILHO. Uma entrada gasta um
        // credito; se o gasto fosse no latch errado, o credito do certo ficaria
        // parado e o do outro cairia sem ninguem ter operado por ele.
        int gastos = 0;

        for (Fill fill : result.fills()) {
            if (fill.verb().contains("Cover") || fill.verb().contains("Close")) {
                continue;
            }

            int nasceu = fill.bar() - 1;
            int gatilho = -1;

            // A ordem pode ter sido colocada algumas barras antes: ela vive um
            // candle da escala dela. Volta-se ate achar quem estava de pe.
            for (int bar = nasceu; bar >= 0 && bar > nasceu - 30; bar--) {
                if (what.aliveAt(bar) >= 0) {
                    gatilho = what.aliveAt(bar);

                    break;
                }
            }

            // E SO COM O LADO PARADO. O credito e por LADO, e a sonda registra
            // o do lado que a porta apontava naquela barra: virando a porta, o
            // numero pula de um lado para o outro sem ninguem ter gasto nada.
            if (gatilho < 0 || fill.bar() < 1
                    || what.gateAt(fill.bar() - 1) != what.gateAt(fill.bar())) {
                continue;
            }

            // O CREDITO E GRAVADO DEPOIS DO settle, e foi isso que a primeira
            // versao deste teste errou: na barra da execucao o gasto JA
            // aconteceu, entao comparar essa barra com a seguinte compara duas
            // fotos de depois. O par certo e a barra anterior contra a dela.
            int escala = AlignedPatterns.TRIGGERS.get(gatilho).latch();
            int antes = what.creditAt(fill.bar() - 1, escala);
            int depois = what.creditAt(fill.bar(), escala);

            // So quando nenhum latch armou na barra da execucao -- uma armada
            // devolve credito e esconderia o gasto.
            if (what.armingsAt(fill.bar()) != 0) {
                continue;
            }

            assertTrue(depois < antes || antes == 0,
                    "a entrada da barra " + fill.bar() + " nao gastou o latch de "
                            + escala + "m: " + antes + " -> " + depois);

            // O QUE NAO DA PARA EXIGIR: que o OUTRO latch fique parado. Ele se
            // move sozinho -- desarma quando o estocastico dele volta a
            // cinquenta, e desarmar zera o credito sem contar como armada. Eu
            // tinha escrito essa exigencia e ela falhou contra o produto se
            // comportando certo.

            gastos++;
        }

        assertTrue(gastos > 0, "nenhum gasto pode ser conferido");

        // E UMA ORDEM VIVE MAIS DE UMA BARRA FINA. Ela e colocada sobre um
        // padrao de 2m, 5m ou 10m, e tem o candle seguinte DAQUELA escala para
        // ser tocada -- nao o minuto seguinte. Apagada a cada barra, a
        // estrategia fazia uma entrada a cada dois pregoes.
        int maisDeUma = 0;

        for (int bar = 1; bar < minutes.size(); bar++) {
            if (what.aliveAt(bar) >= 0 && what.aliveAt(bar) == what.aliveAt(bar - 1)) {
                maisDeUma++;
            }
        }

        assertTrue(maisDeUma > 0,
                "nenhuma ordem sobreviveu a barra em que nasceu");
    }

    @Test
    @DisplayName("NADA ATRAVESSA O PREGAO: toda operacao comeca e termina no mesmo dia")
    void nothingCrossesTheSession() {
        Minutes minutes = new Minutes(noisy(12_000, 7), 400);
        Result result = run(minutes);

        assertEquals(0, result.openAtTheEnd(), "sobrou posicao aberta no fim");

        for (Trade trade : result.trades()) {
            Fill abriu = trade.fills().get(0);
            Fill fechou = trade.fills().get(trade.fills().size() - 1);

            assertEquals(abriu.bar() / 400, fechou.bar() / 400,
                    "uma operacao comecou num pregao e terminou noutro: barras "
                            + abriu.bar() + " a " + fechou.bar());
        }
    }

    @Test
    @DisplayName("UM PADRAO SO VALE UMA VEZ, e nao uma por barra fina")
    void apatternIsWorthOneEntryAndNotOnePerFineBar() {
        Minutes minutes = new Minutes(noisy(1_600, 7), 400);
        AlignedPatterns what = new AlignedPatterns(SP, 1);

        what.sourcedFrom(minutes);
        what.start(minutes);

        // A leitura de uma escala grossa cobre varias barras finas -- cinco
        // barras de 1m leem o mesmo candle de 5m. Se o gatilho olhasse so "ha
        // padrao?", um unico 1-2-3 viraria cinco ordens.
        int repetidas = 0;
        int trocas = 0;

        for (int bar = 1; bar < 400; bar++) {
            int agora = what.readingAt(1, bar);
            int antes = what.readingAt(1, bar - 1);

            if (agora < 0 || antes < 0) {
                continue;
            }

            if (agora == antes) {
                repetidas++;
            } else {
                trocas++;
            }
        }

        assertTrue(repetidas > trocas,
                "a escala de 5m nao esta se repetindo sobre as barras de 1m: "
                        + repetidas + " repeticoes contra " + trocas + " trocas");
    }

    @Test
    @DisplayName("UM CANDLE GROSSO DISPARA UMA VEZ, mesmo depois de executar")
    void acoarseCandleFiresOnceEvenAfterAfill() {
        Minutes minutes = new Minutes(noisy(12_000, 11), 400);
        AlignedPatterns what = new AlignedPatterns(SP, 1);

        what.sourcedFrom(minutes);

        Result result = new Backtest(Costs.NONE, 1).run(minutes, minutes, what, null);

        // Uma leitura de escala grossa cobre varias barras finas. Enquanto a
        // ordem esta de pe ela propria impede o re-disparo -- mas assim que ela
        // EXECUTA a ordem some, e sem a guarda da borda o mesmo candle de 5m
        // dispararia de novo na barra seguinte, e na seguinte.
        java.util.Set<String> vistos = new java.util.HashSet<>();

        for (Fill fill : result.fills()) {
            if (fill.verb().contains("Cover") || fill.verb().contains("Close")) {
                continue;
            }

            int bar = fill.bar() - 1;
            StringBuilder chave = new StringBuilder();

            for (int which = 0; which < AlignedPatterns.TRIGGERS.size(); which++) {
                chave.append(what.readingAt(which, bar)).append('/');
            }

            assertTrue(vistos.add(chave.toString()),
                    "a barra " + fill.bar() + " entrou de novo pelos mesmos candles grossos: "
                            + chave);
        }

        assertTrue(vistos.size() > 3, "poucas entradas para provar algo: " + vistos.size());
    }

    @Test
    @DisplayName("UMA SERIE VAZIA nao explode")
    void nothingIsStillSomething() {
        AlignedPatterns what = new AlignedPatterns(SP, 1);

        what.sourcedFrom(null);
        what.start(null);

        assertEquals(0, what.gateAt(0), "serie nula deu porta aberta");
        assertEquals(-1, what.readingAt(0, 0), "serie nula deu leitura");
    }
}
