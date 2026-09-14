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

import br.com.jorge.reis.endeavourneo.domain.indicator.Pmo;
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A estratégia do TNO: entra no cruzamento, stop no candle, alvo em 2R. */
@DisplayName("TNO operado")
class MomentumCrossTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    /**
     * Uma onda de um pregão só, começando às 09:00.
     *
     * <p>A hora importa: começando na época zero as barras atravessam a
     * meia-noite de São Paulo, o fim de pregão zera tudo no meio do teste, e
     * nada opera — o que parece defeito do produto e é da fixture.</p>
     */
    private record Bars(double[] price) implements PriceSeries {

        private static Bars wave(int size) {
            double[] made = new double[size];

            for (int i = 0; i < size; i++) {
                made[i] = 100_000 + 600 * Math.sin(i / 23.0) + 200 * Math.sin(i / 7.0);
            }

            return new Bars(made);
        }

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
            return price[index] + 40;
        }

        @Override
        public double lowAt(int index) {
            return price[index] - 40;
        }

        @Override
        public double closeAt(int index) {
            return price[index];
        }
    }

    /**
     * A mesma onda, partida em pregoes de {@code perDay} barras, com PAVIO LARGO
     * e um GAP entre os dois dias.
     *
     * <p>As duas coisas existem para o teste da sessao ter o que provar. Com
     * pavio estreito a operacao morre no stop em poucas barras e nunca chega
     * viva na virada do pregao — entao nada atravessa, com ou sem a regra. E sem
     * gap, atravessar nao custaria nada: a diferenca entre fechar hoje e fechar
     * amanha seria de alguns pontos, invisivel. O salto de cinco mil torna a
     * travessia obvia.</p>
     */
    private record Days(double[] price, int perDay) implements PriceSeries {

        private double priceAt(int index) {
            return price[index] + (index >= perDay ? 5_000 : 0);
        }

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
            return priceAt(index);
        }

        @Override
        public double highAt(int index) {
            return priceAt(index) + 500;
        }

        @Override
        public double lowAt(int index) {
            return priceAt(index) - 500;
        }

        @Override
        public double closeAt(int index) {
            return priceAt(index);
        }
    }

    /**
     * A mesma onda com pavios LARGOS, e é isso que ela existe para fazer.
     *
     * <p>O stop é o extremo do candle do sinal e o alvo é 2R dali: com pavio de
     * quarenta pontos a operação morre em poucas barras, e o sinal contrário
     * nunca chega a encontrar posição aberta — então a regra da inversão fica
     * sem ser exercida. Com pavio de dois mil, nem o stop nem o alvo são
     * alcançados pela onda de oitocentos, e a única coisa que pode terminar a
     * operação é o sinal seguinte.</p>
     */
    private record Wide(double[] price) implements PriceSeries {

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
            return price[index] + 2_000;
        }

        @Override
        public double lowAt(int index) {
            return price[index] - 2_000;
        }

        @Override
        public double closeAt(int index) {
            return price[index];
        }
    }

    /** Períodos curtos: a onda é de 400 barras e 35/20 nem aqueceria. */
    private static final Pmo RAPIDO = new Pmo(1, 8, 5, 3, Pmo.SCALE);

    private static Result run(PriceSeries bars) {
        return new Backtest(Costs.NONE, 1).run(bars, new MomentumCross(SP, RAPIDO, 1));
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
    @DisplayName("ENTRA NO CRUZAMENTO, e do lado dele")
    void itentersOnTheCrossingAndOnItsSide() {
        Bars bars = Bars.wave(400);
        Result result = run(bars);

        int[] cruzou = Pmo.crossings(RAPIDO.over(bars));
        List<Fill> abriu = aberturas(result);

        assertFalse(abriu.isEmpty(), "nao abriu posicao nenhuma");

        for (Fill fill : abriu) {
            // A ordem sai no fechamento da barra que cruzou e executa na
            // ABERTURA da seguinte, entao o sinal esta em bar - 1.
            int sinal = fill.bar() - 1;

            assertTrue(sinal >= 0 && sinal < cruzou.length,
                    "a execucao da barra " + fill.bar() + " nao tem barra anterior");

            assertTrue(cruzou[sinal] != 0,
                    "abriu na barra " + fill.bar() + " sem cruzamento na " + sinal);

            assertEquals(cruzou[sinal] > 0 ? Side.BUY : Side.SELL, fill.side(),
                    "abriu para o lado errado do cruzamento da barra " + sinal);
        }
    }

    @Test
    @DisplayName("O STOP E O EXTREMO DO CANDLE DO SINAL, nao o do candle que executou")
    void thestopIsTheExtremeOfTheSignalCandle() {
        Bars bars = Bars.wave(400);
        Result result = run(bars);

        int conferidos = 0;

        for (Trade trade : result.trades()) {
            Fill entrada = trade.fills().get(0);
            int sinal = entrada.bar() - 1;

            if (sinal < 0) {
                continue;
            }

            double esperado = trade.side() == Side.BUY
                    ? bars.lowAt(sinal) : bars.highAt(sinal);

            // O RISCO E A DISTANCIA DA EXECUCAO ATE AQUELE EXTREMO. Medido do
            // fechamento do sinal em vez da execucao, ele seria outro numero --
            // e o alvo de 2R, outro lugar.
            double risco = trade.side() == Side.BUY
                    ? entrada.price() - esperado : esperado - entrada.price();

            if (!(risco > 0)) {
                continue;   // abriu ja alem do proprio stop; sai a mercado
            }

            Fill saida = trade.fills().get(trade.fills().size() - 1);

            if (!saida.verb().contains("Stop")) {
                continue;
            }

            // NO NIVEL, EXATO -- e nao "dentro da folga". A folga e de duzentos
            // pontos e dois candles vizinhos desta onda diferem por poucos: uma
            // janela dessas engole a diferenca entre o extremo do candle do
            // sinal e o do candle seguinte, que e exatamente a regra sendo
            // testada. Ja aconteceu antes neste projeto, com o fade.
            //
            // Um stop executa no nivel a menos que a barra ABRA alem dele, e ai
            // executa na abertura. Entao a igualdade vale sempre que nao houve
            // salto, e essas sao as barras que contam aqui.
            double abertura = bars.openAt(saida.bar());
            boolean saltou = trade.side() == Side.BUY
                    ? abertura < esperado : abertura > esperado;

            if (saltou) {
                continue;
            }

            assertEquals(esperado, saida.price(), 1e-6,
                    "o stop da barra " + saida.bar() + " nao saiu no extremo do candle "
                            + sinal + ", que era " + esperado);

            conferidos++;
        }

        assertTrue(conferidos > 0, "nenhuma operacao terminou no stop, entao nada foi provado");
    }

    @Test
    @DisplayName("O ALVO E 2R a partir da EXECUCAO, e nao do fechamento do sinal")
    void thetargetIsTwoRfromTheFill() {
        Bars bars = Bars.wave(400);
        Result result = run(bars);

        int conferidos = 0;

        for (Trade trade : result.trades()) {
            Fill entrada = trade.fills().get(0);
            Fill saida = trade.fills().get(trade.fills().size() - 1);
            int sinal = entrada.bar() - 1;

            if (sinal < 0 || !saida.verb().contains("Limit")) {
                continue;
            }

            double parede = trade.side() == Side.BUY
                    ? bars.lowAt(sinal) : bars.highAt(sinal);
            double risco = trade.side() == Side.BUY
                    ? entrada.price() - parede : parede - entrada.price();

            if (!(risco > 0)) {
                continue;
            }

            double alvo = trade.side() == Side.BUY
                    ? entrada.price() + MomentumCross.REWARD * risco
                    : entrada.price() - MomentumCross.REWARD * risco;

            // Maior OU IGUAL, e nao igual: um limite executa no melhor entre o
            // nivel e a abertura. Exigir igualdade seria exigir que o mercado
            // nunca desse gap a favor.
            if (trade.side() == Side.BUY) {
                assertTrue(saida.price() >= alvo - 1e-6,
                        "a compra saiu em " + saida.price() + ", abaixo do alvo " + alvo);
            } else {
                assertTrue(saida.price() <= alvo + 1e-6,
                        "a venda saiu em " + saida.price() + ", acima do alvo " + alvo);
            }

            conferidos++;
        }

        assertTrue(conferidos > 0, "nenhuma operacao terminou no alvo, entao nada foi provado");
    }

    @Test
    @DisplayName("SINAL COM POSICAO ABERTA INVERTE, e em DUAS ordens")
    void asignalWhilePositionedReversesInTwoOrders() {
        Result result = new Backtest(Costs.NONE, 1).run(
                new Wide(Bars.wave(400).price()), new MomentumCross(SP, RAPIDO, 1));

        int invertidas = 0;
        List<Fill> fills = result.fills();

        for (int i = 1; i < fills.size(); i++) {
            Fill antes = fills.get(i - 1);
            Fill agora = fills.get(i);

            if (antes.bar() != agora.bar() || !antes.verb().contains("Close")) {
                continue;
            }

            // DUAS ORDENS, nao uma: fecha e depois abre, na mesma barra e nessa
            // ordem. Um ReversePosition sozinho seria UMA execucao de quantidade
            // dobrada -- o motor a parte em duas, e o leitor fica com duas
            // operacoes que dividem preco e instante sem sinal nenhum de que
            // algo foi decidido entre elas.
            assertTrue(agora.verb().startsWith("Buy") || agora.verb().startsWith("SellShort"),
                    "depois do fechamento da barra " + agora.bar() + " veio " + agora.verb());

            // O LADO SE LE NO VERBO, nao no lado do fill: fechar uma COMPRA e um
            // fill de VENDA, e abrir uma VENDA tambem -- os dois sao SELL, e
            // compara-los diria que a inversao abriu para o mesmo lado.
            //
            // Entao: o fechamento com lado SELL encerrava uma COMPRA, e o que
            // vem depois tem de ser um SellShort. Espelhado do outro lado.
            String esperado = antes.side() == Side.SELL ? "SellShort" : "Buy";

            assertTrue(agora.verb().startsWith(esperado),
                    "a inversao da barra " + agora.bar() + " fechou um "
                            + (antes.side() == Side.SELL ? "comprado" : "vendido")
                            + " e abriu um " + agora.verb());

            invertidas++;
        }

        assertTrue(invertidas > 0, "nenhuma inversao aconteceu, entao a regra nao foi exercida");
    }

    @Test
    @DisplayName("NADA ATRAVESSA O PREGAO: a posicao termina zerada")
    void nothingCrossesTheSession() {
        // UM V POR PREGAO, e a virada NO FIM DELE: cai 380 barras e sobe as dez
        // ultimas.
        //
        // A forma nao e enfeite -- ela e a unica que mantem uma operacao VIVA ate
        // a virada do pregao, que e o que este teste precisa. Com qualquer onda
        // a comprada morre na barra seguinte a entrada, e a razao e geometrica:
        // o stop e a minima do candle do sinal, a minima e o fechamento menos o
        // pavio, e entao QUALQUER queda de um ponto leva a minima da barra
        // seguinte abaixo do stop. Alargar o pavio nao adianta -- ele move o
        // stop e o alcance juntos. Medido tres vezes, com pavio de 40, com piso
        // fixo e com pavio de 3.000.
        //
        // Subindo em linha reta depois da virada, a minima nunca volta ao nivel
        // do sinal e o stop fica intocado; o alvo de 2R fica a ~1.000 e a subida
        // e de 200 no total. Nem o stop nem o alvo acontecem.
        //
        // E A VIRADA E NO FIM DO DIA de proposito. Medido com o V no meio: na
        // perna de subida a taxa de variacao fica CONSTANTE, o PMO converge e
        // nao cruza mais -- entao a posicao ja estava fechada havia 240 barras
        // quando o pregao virou, e nao havia nada para atravessar.
        int perDay = 390;
        double[] price = new double[800];

        for (int i = 0; i < price.length; i++) {
            int within = i % perDay;

            price[i] = within < 380 ? 100_000 - within : 99_620 + (within - 380) * 20;
        }

        Result result = new Backtest(Costs.NONE, 1).run(new Days(price, perDay),
                new MomentumCross(SP, RAPIDO, 1));

        assertEquals(0, result.openAtTheEnd(), "sobrou posicao aberta no fim");

        for (Trade trade : result.trades()) {
            Fill abriu = trade.fills().get(0);
            Fill fechou = trade.fills().get(trade.fills().size() - 1);

            assertEquals(abriu.bar() / 390, fechou.bar() / 390,
                    "uma operacao comecou num pregao e terminou noutro: barras "
                            + abriu.bar() + " a " + fechou.bar());
        }
    }

    @Test
    @DisplayName("A MAO DOBRA A CADA STOP e volta a um no primeiro ganho")
    void thelotDoublesAfterEachLossAndResetsOnAwin() {
        Bars bars = Bars.wave(400);

        MomentumCross simples = new MomentumCross(SP, RAPIDO, 1);
        MomentumCross dobrando = new MomentumCross(SP, RAPIDO, 1,
                MomentumCross.REWARD, MomentumCross.SLIP, RangeGate.Mode.OFF,
                new MomentumCross.Doubling(true, 4));

        Result liso = new Backtest(Costs.NONE, 1).run(bars, simples);
        Result dobrado = new Backtest(Costs.NONE, 1).run(bars, dobrando);

        assertEquals(liso.count(), dobrado.count(),
                "a dobra mudou QUAIS operacoes acontecem, e ela so muda o TAMANHO");

        // A DOBRA MOVE O TAMANHO, NAO O SINAL. As mesmas operacoes, nas mesmas
        // barras, com quantidades diferentes -- e por isso o numero de contratos
        // girados cresce enquanto a contagem de operacoes fica igual.
        assertTrue(dobrado.contractsTurned() > liso.contractsTurned(),
                "dobrando girou " + dobrado.contractsTurned() + " contratos contra "
                        + liso.contractsTurned() + " sem dobrar");

        // E A SEQUENCIA SEGUE A REGRA: depois de uma perda o lote seguinte e o
        // dobro, depois de um ganho volta a um. Refeito por fora.
        int esperado = 1;
        int conferidos = 0;

        for (Trade trade : dobrado.trades()) {
            Fill entrada = trade.fills().get(0);

            assertEquals(esperado, entrada.quantity(),
                    "a operacao da barra " + entrada.bar() + " abriu com "
                            + entrada.quantity() + " e nao com " + esperado);

            conferidos++;

            // O pregao e um so nesta fixture, entao a sequencia nao e zerada por
            // virada de dia.
            esperado = trade.gross() < 0 ? Math.min(esperado * 2, 1 << 4) : 1;
        }

        assertTrue(conferidos > 3, "so " + conferidos + " operacoes, poucas para provar");

        // E A SEQUENCIA MORRE COM O PREGAO.
        //
        // DUAS FIXTURES FALHARAM ANTES DESTA, e as duas pelo mesmo motivo: a
        // regra so aparece quando um pregao TERMINA PERDENDO. Com um dia so nao
        // ha virada; com o V de dois dias, o dia 1 acaba ganhando (a compra
        // pega a subida final), entao a contagem ja estava zerada e carrega-la
        // para o dia seguinte nao mudava nada.
        //
        // Quinze pregoes de ruido resolvem por forca bruta: algum deles termina
        // no vermelho, e ai o dia seguinte tem de comecar do lote base.
        Minutos muitos = new Minutos(ruido(6_000, 3), 400);

        MomentumCross comDobra = new MomentumCross(SP, RAPIDO, 1,
                MomentumCross.REWARD, MomentumCross.SLIP, RangeGate.Mode.OFF,
                new MomentumCross.Doubling(true, 4));

        comDobra.sourcedFrom(muitos);

        Result varios = new Backtest(Costs.NONE, 1).run(muitos, muitos, comDobra, null);

        int dia = -1;
        int viradas = 0;
        boolean perdeuNoDiaAnterior = false;
        boolean algumDiaTerminouPerdendo = false;
        double ultimo = 0;

        for (Trade trade : varios.trades()) {
            Fill entrada = trade.fills().get(0);
            int agora = entrada.bar() / 400;

            if (agora != dia) {
                if (dia >= 0) {
                    perdeuNoDiaAnterior = ultimo < 0;
                    algumDiaTerminouPerdendo |= perdeuNoDiaAnterior;
                }

                dia = agora;

                assertEquals(1, entrada.quantity(),
                        "o pregao " + agora + " comecou com " + entrada.quantity()
                                + " contratos: a sequencia da dobra atravessou a noite");

                if (perdeuNoDiaAnterior) {
                    viradas++;
                }
            }

            ultimo = trade.gross();
        }

        assertTrue(algumDiaTerminouPerdendo,
                "nenhum pregao terminou perdendo, entao a regra da noite nao foi exercida");
        assertTrue(viradas > 0, "nenhuma virada veio depois de um dia no vermelho");
    }

    @Test
    @DisplayName("COM A PORTA LIGADA so entra a favor dos dois ranges")
    void gatedItOnlyEntersWithBothRanges() {
        // Um pregao de verdade nao cabe numa onda de seno: a porta precisa de um
        // range de noventa minutos E de uma primeira briga, e as duas saem das
        // barras de 1m. Entao esta fixture e de MINUTOS, com ruido semeado.
        Minutos minutos = new Minutos(ruido(6_000, 3), 400);

        MomentumCross comPorta = new MomentumCross(SP, RAPIDO, 1,
                MomentumCross.REWARD, MomentumCross.SLIP, RangeGate.Mode.BOTH);
        MomentumCross semPorta = new MomentumCross(SP, RAPIDO, 1,
                MomentumCross.REWARD, MomentumCross.SLIP, RangeGate.Mode.OFF);

        comPorta.sourcedFrom(minutos);
        semPorta.sourcedFrom(minutos);

        Result filtrado = new Backtest(Costs.NONE, 1).run(minutos, minutos, comPorta, null);
        Result solto = new Backtest(Costs.NONE, 1).run(minutos, minutos, semPorta, null);

        assertTrue(solto.count() > 0, "nem sem porta operou, entao nada foi filtrado");
        assertTrue(filtrado.count() < solto.count(),
                "a porta nao cortou operacao nenhuma: " + filtrado.count()
                        + " contra " + solto.count());

        // E O QUE SOBROU ESTA DO LADO DA PORTA. O gabarito e refeito aqui e nao
        // lido do produto -- um gabarito que sai de quem esta sendo testado nao
        // e gabarito.
        int[] porta = portaPorFora(minutos);

        for (Fill fill : filtrado.fills()) {
            if (fill.verb().contains("Cover") || fill.verb().contains("Close")) {
                continue;
            }

            int lado = porta[fill.bar() - 1];

            assertTrue(lado != 0,
                    "abriu na barra " + fill.bar() + " com os ranges em desacordo");

            assertEquals(lado > 0 ? Side.BUY : Side.SELL, fill.side(),
                    "abriu contra os dois ranges na barra " + fill.bar());
        }
    }

    /** Os dois ranges refeitos do zero, sem passar pela estrategia. */
    private static int[] portaPorFora(PriceSeries minutos) {
        br.com.jorge.reis.endeavourneo.domain.market.PriceSeries cinco =
                br.com.jorge.reis.endeavourneo.domain.market.Timeframe.ofMinutes(
                        br.com.jorge.reis.endeavourneo.domain.indicator
                                .OpeningImpulse.MINUTES).apply(minutos, SP);

        br.com.jorge.reis.endeavourneo.domain.indicator.OpeningImpulse impulso =
                br.com.jorge.reis.endeavourneo.domain.indicator.OpeningImpulse.standard();

        int[] briga = impulso.brokenAt(minutos, impulso.of(cinco, SP), SP);
        double[] noRelogio = new double[cinco.size()];

        for (OpeningRange.Session each : OpeningRange.of(cinco, SP, RangeGate.FORMATION)) {
            if (each.side() == 0 || each.breakBar() < 0) {
                continue;
            }

            for (int bar = each.breakBar(); bar <= each.last() && bar < cinco.size(); bar++) {
                noRelogio[bar] = each.side();
            }
        }

        double[] espalhado = br.com.jorge.reis.endeavourneo.domain.indicator.LastClosed
                .spread(minutos, cinco, noRelogio);

        int[] made = new int[minutos.size()];

        for (int bar = 0; bar < made.length; bar++) {
            int relogio = Double.isNaN(espalhado[bar]) ? 0 : (int) Math.round(espalhado[bar]);

            made[bar] = briga[bar] != 0 && briga[bar] == relogio ? briga[bar] : 0;
        }

        return made;
    }

    /** Barras de um minuto com ruido reprodutivel, para os ranges existirem. */
    private record Minutos(double[] price, int perDay) implements PriceSeries {

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

    private static double[] ruido(int count, long seed) {
        double[] made = new double[count];
        java.util.Random random = new java.util.Random(seed);
        double now = 100_000;

        for (int i = 0; i < count; i++) {
            now += random.nextGaussian() * 60;
            made[i] = Math.round(now / 5) * 5;
        }

        return made;
    }

    @Test
    @DisplayName("SEM CRUZAMENTO NAO OPERA, nem com barras de sobra")
    void withoutAcrossingNothingTrades() {
        // Preco estritamente crescente: o PMO sobe e nunca volta, entao a linha
        // nunca corta o sinal de cima para baixo nem de baixo para cima depois
        // do aquecimento.
        double[] price = new double[400];

        for (int i = 0; i < price.length; i++) {
            price[i] = 100_000 + i;
        }

        Result result = run(new Bars(price));
        int[] cruzou = Pmo.crossings(RAPIDO.over(new Bars(price)));
        int quantos = 0;

        for (int each : cruzou) {
            if (each != 0) {
                quantos++;
            }
        }

        assertEquals(quantos > 0, result.count() > 0,
                "operou " + result.count() + " vezes para " + quantos + " cruzamentos");
    }
}
