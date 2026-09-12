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

import br.com.jorge.reis.endeavourneo.domain.indicator.Pivots;
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
 * O stop que a posição já nasce com: o menor dos dois últimos fundos, cinco
 * abaixo.
 *
 * <p>O que estes testes defendem é que o nível sai dos <b>dois</b> — e do
 * <b>menor</b> deles. Um pregão em que os dois últimos fundos estão na mesma
 * altura não distingue "o menor dos dois" de "o último"; este foi construído
 * com eles em alturas diferentes de propósito, e há uma asserção conferindo que
 * continuam assim.</p>
 */
@DisplayName("Stop de entrada pelos dois fundos")
class ChannelFadeGuardTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

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

    /** Arma a COMPRA na primeira barra e nunca desarma. */
    private static final StochasticLatch.Settings COMPRA =
            new StochasticLatch.Settings(true, 1, 1, 101, 200, 200, 9);

    private static final int MERGULHO = 241;

    /**
     * Sobe, com DOIS mergulhos de fundos diferentes antes da compra.
     *
     * <p>O primeiro mergulho, na barra 210, vai fundo; o segundo, na 225, vai
     * menos fundo. Os dois viram fundos confirmados do zigzag, e o menor deles é
     * o da 210 — que é o que o stop tem de usar. Um pregão com os dois na mesma
     * altura passaria neste teste sem provar a palavra "menor".</p>
     *
     * <p>O mergulho da 241 é o que leva o preço à borda de baixo do canal e faz
     * a compra acontecer; ele é raso o bastante para não virar o canal.</p>
     */
    private static double[] pregao() {
        return pregao(0);
    }

    /**
     * @param piso onde a perna final para de cair
     *
     * <p>Existe por causa de UM teste: o do tique. Deixada cair livre, a perna
     * final passa tao longe do nivel que cinco pontos a mais ou a menos nao
     * mudam nada, e a quebra que tira o tique passa. Com um piso escolhido
     * entre o fundo e o fundo menos cinco, o stop com tique nao e tocado e o
     * sem tique e -- e ai os cinco pontos sao a diferenca entre sair e nao
     * sair.</p>
     */
    private static double[] pregao(double piso) {
        double[] price = new double[340];

        for (int bar = 0; bar < price.length; bar++) {
            double base;

            if (bar < 256) {
                base = 100_000 + 20.0 * bar;
            } else if (bar < 301) {
                base = 100_000 + 20.0 * 255 - 25.0 * (bar - 255);
            } else if (bar < 312) {
                base = 100_000 + 20.0 * 255 - 25.0 * 45 + 30.0 * (bar - 300);
            } else {
                base = Math.max(piso,
                        100_000 + 20.0 * 255 - 25.0 * 45 + 30.0 * 11 - 60.0 * (bar - 311));
            }

            price[bar] = base + (bar % 2 == 0 ? 100 : -100);

            // O fundo MAIS FUNDO dos dois, e o mais antigo.
            if (bar >= 210 && bar < 213) {
                price[bar] -= 900;
            }

            // O mais raso, e o mais recente.
            if (bar >= 225 && bar < 228) {
                price[bar] -= 400;
            }

            if (bar >= MERGULHO && bar < MERGULHO + 3) {
                price[bar] -= 600;
            }
        }

        return price;
    }

    private static Result run(ChannelFade.Guard guard) {
        return run(guard, pregao());
    }

    private static Result run(ChannelFade.Guard guard, double[] price) {
        return rodar(guard, price).result();
    }

    /**
     * @param result o que a rodada produziu
     * @param entrada a curva "Entrada": o NIVEL em que a ordem ficou de pe
     *
     * <p>O nivel e o preco EXECUTADO nao sao o mesmo numero — um limite executa
     * igual ou melhor — e a regra "so entrar quando o nivel protege" compara com
     * o nivel, que e o que a estrategia sabia ao mandar a ordem. Um teste que
     * conferisse pelo preco executado mediria outra coisa, e foi o que ele
     * mediu na primeira escrita.</p>
     */
    private record Rodada(Result result, double[] entrada) { }

    private static Rodada rodar(ChannelFade.Guard guard, double[] price) {
        return rodar(guard, price, 2.0);
    }

    /**
     * @param entrada quantas deviacoes abaixo da linha a ordem espera
     *
     * <p>Parametrizado por causa de UM teste. Para exercitar "so entrar quando
     * o nivel protege" e preciso um nivel ABAIXO dos dois fundos, e mexer nos
     * fundos nao serve: eles estao dentro da janela do canal, entao abaixa-los
     * abaixa o nivel junto e a comparacao nao sai do lugar. O degrau move o
     * nivel sem tocar nos fundos.</p>
     */
    private static Rodada rodar(ChannelFade.Guard guard, double[] price, double entrada) {
        PriceSeries bars = new Bars(price);
        ChannelFade what = new ChannelFade(SP,
                List.of(new ChannelFade.Rung(Regression.SHORT, entrada, 40.0)), 1,
                COMPRA, ChannelFade.Flip.off(), guard);

        what.sourcedFrom(bars);

        Result result = new Backtest(Costs.NONE, 1).run(bars, bars, what, null);

        return new Rodada(result, what.curves().get("Entrada"));
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

    private static Fill saida(Result result) {
        for (Fill fill : result.fills()) {
            if (fill.verb().contains("Cover") || fill.verb().contains("Close")) {
                return fill;
            }
        }

        return null;
    }

    /** Os dois fundos confirmados mais recentes ATE a barra informada. */
    private static double[] doisUltimosFundos(PriceSeries bars, int ate) {
        Pivots.Pivot[] quando = Pivots.standard().confirmedAt(bars);
        List<Double> fundos = new ArrayList<>();

        for (int bar = 0; bar <= ate && bar < quando.length; bar++) {
            Pivots.Pivot fato = quando[bar];

            if (fato != null && !fato.top()) {
                fundos.add(fato.price());
            }
        }

        int quantos = fundos.size();

        return quantos < 2
                ? new double[0]
                : new double[] {fundos.get(quantos - 2), fundos.get(quantos - 1)};
    }

    @Test
    @DisplayName("O STOP SAI DO MENOR DOS DOIS ULTIMOS FUNDOS, cinco abaixo")
    void thestopComesFromTheLowerOfTheLastTwoBottoms() {
        PriceSeries bars = new Bars(pregao());
        Result result = run(ChannelFade.Guard.standard());
        List<Fill> abriu = aberturas(result);

        assertFalse(abriu.isEmpty(), "o pregao nao gerou compra nenhuma");
        assertEquals("BuyLimit", abriu.get(0).verb(),
                "a entrada nao foi uma compra: " + abriu.get(0).verb());

        // A DECISAO foi na barra anterior a execucao: e ali que a estrategia
        // olhou os fundos.
        int decidiu = abriu.get(0).bar() - 1;
        double[] dois = doisUltimosFundos(bars, decidiu);

        assertEquals(2, dois.length, "nao havia dois fundos confirmados na decisao");

        // OS DOIS TEM DE ESTAR EM ALTURAS DIFERENTES, senao "o menor dos dois" e
        // indistinguivel de "o ultimo" e o teste abaixo nao prova a palavra.
        assertTrue(Math.abs(dois[0] - dois[1]) > 100,
                "os dois fundos ficaram na mesma altura (" + dois[0] + " e " + dois[1]
                        + "), entao este pregao nao distingue o menor do ultimo");

        assertTrue(dois[0] < dois[1],
                "o menor dos dois deixou de ser o mais ANTIGO neste pregao,"
                        + " entao a asercao abaixo passaria pelo motivo errado");

        Fill saiu = saida(result);

        assertNotNull(saiu, "a posicao nunca foi fechada");
        assertTrue(saiu.verb().contains("Stop"),
                "a saida nao foi pelo stop de entrada: " + saiu.verb());

        double nivel = Math.min(dois[0], dois[1]) - ChannelFade.TICK;

        assertTrue(saiu.price() <= nivel + 1e-6,
                "o stop saiu acima do nivel: " + saiu.price() + " contra " + nivel);
        assertTrue(saiu.price() >= nivel - ChannelFade.Flip.SLIP - 1e-6,
                "o stop saiu mais de " + ChannelFade.Flip.SLIP + " pontos abaixo do nivel: "
                        + saiu.price() + " contra " + nivel);
    }

    @Test
    @DisplayName("SEM O MODULO, a mesma posicao so sai no sino")
    void withoutThemoduleThePositionOnlyLeavesAtTheBell() {
        Fill sem = saida(run(ChannelFade.Guard.off()));

        assertNotNull(sem, "a posicao nunca foi fechada");
        assertEquals("ClosePosition", sem.verb(),
                "sem o modulo alguma outra coisa fechou a posicao: " + sem.verb());

        Fill com = saida(run(ChannelFade.Guard.standard()));

        assertNotNull(com, "a posicao nunca foi fechada");
        assertTrue(com.bar() < sem.bar(),
                "com o stop a posicao nao saiu antes: barra " + com.bar()
                        + " contra " + sem.bar());
    }

    /**
     * O piso que faz a perna final parar em 103.212.
     *
     * <p>As barras pares ficam em {@code piso + 100} e as impares em
     * {@code piso - 100}, e a minima e o fechamento menos cinco: a menor minima
     * do pregao e portanto {@code piso - 105}. O fundo menor e 103.215 e o stop
     * fica em 103.210, entao 103.212 cai no meio dos dois -- longe o bastante
     * de um para nao o tocar e perto o bastante do outro para toca-lo.</p>
     */
    private static final double PISO = 103_212 + 105;

    @Test
    @DisplayName("OS CINCO PONTOS SAO A DIFERENCA entre sair e nao sair")
    void thefiveiPointsAreTheDifference() {
        double[] price = pregao(PISO);
        PriceSeries bars = new Bars(price);

        // A guarda do pregao, conferida em vez de suposta.
        double menor = Double.MAX_VALUE;

        for (int bar = 312; bar < bars.size(); bar++) {
            menor = Math.min(menor, bars.lowAt(bar));
        }

        double[] dois = doisUltimosFundos(bars, 240);

        assertEquals(2, dois.length, "nao havia dois fundos confirmados na decisao");

        double fundo = Math.min(dois[0], dois[1]);

        assertTrue(menor < fundo && menor > fundo - ChannelFade.TICK,
                "a perna final parou em " + menor + ", e precisa parar entre "
                        + (fundo - ChannelFade.TICK) + " e " + fundo
                        + " para este teste medir os cinco pontos");

        // COM o tique, o stop fica em fundo-5 e o preco para ANTES dele: a
        // posicao sai no sino. Sem o tique, o stop ficaria no proprio fundo e
        // seria tocado.
        Fill saiu = saida(run(ChannelFade.Guard.standard(), price));

        assertNotNull(saiu, "a posicao nunca foi fechada");
        assertEquals("ClosePosition", saiu.verb(),
                "o stop foi tocado, e com o tique ele fica abaixo de onde o preco chegou: "
                        + saiu.verb() + " em " + saiu.price());
    }

    @Test
    @DisplayName("SO QUANDO PROTEGE: o nivel do lado errado recusa a entrada")
    void onlyWhenTheLevelProtects() {
        PriceSeries bars = new Bars(pregao());

        // A COMPRA DESTE PREGAO TEM O NIVEL DO LADO CERTO -- ela mergulha ate
        // abaixo dos dois fundos -- entao com a caixa ligada ela continua
        // acontecendo. Sem esta medida, um pregao em que nenhuma entrada
        // protege faria a caixa parecer certa recusando tudo.
        List<Fill> comCaixa = aberturas(run(
                new ChannelFade.Guard(true, ChannelFade.Flip.SLIP, true, false)));

        assertFalse(comCaixa.isEmpty(), "a caixa recusou uma entrada que protegia");

        double[] dois = doisUltimosFundos(bars, comCaixa.get(0).bar() - 1);

        assertEquals(2, dois.length, "nao havia dois fundos na decisao");
        assertTrue(Math.min(dois[0], dois[1]) < comCaixa.get(0).price(),
                "o nivel desta entrada nao protege, e a caixa deixou passar");

        // E AGORA UMA QUE NAO PROTEGE. Os dois fundos ANTERIORES e que ficam
        // rasos -- acima de onde a compra vai acontecer -- que e exatamente a
        // situacao de 51% das entradas do ano dele: o fade compra abaixo de
        // onde o mercado vinha marcando.
        // Os dois mergulhos rasos ficam PERTO da entrada, e e o que os mantem
        // acima dela: numa alta de vinte pontos por barra, um fundo de trinta
        // barras atras esta seiscentos pontos mais baixo so pela inclinacao, e
        // acaba abaixo da compra por acidente. Recuos recentes e que sao os que
        // ficam por cima -- e e essa a situacao real dos 51%.
        double[] raso = pregao();

        for (int bar = 210; bar < 213; bar++) {
            raso[bar] += 900;
        }

        for (int bar = 225; bar < 228; bar++) {
            raso[bar] += 400;
        }

        for (int bar = 230; bar < 233; bar++) {
            raso[bar] -= 250;
        }

        for (int bar = 235; bar < 238; bar++) {
            raso[bar] -= 200;
        }

        PriceSeries rasas = new Bars(raso);
        // O degrau FUNDO poe o nivel da ordem abaixo dos dois fundos; ver a
        // nota do rodar de tres argumentos.
        double degrau = 4.0;
        Rodada solta = rodar(ChannelFade.Guard.standard(), raso, degrau);
        List<Fill> semCaixa = aberturas(solta.result());

        assertFalse(semCaixa.isEmpty(), "o pregao raso nao gera entrada nenhuma");

        int decidiu = semCaixa.get(0).bar() - 1;
        double[] rasos = doisUltimosFundos(rasas, decidiu);
        double nivelDaOrdem = solta.entrada()[decidiu];

        assertEquals(2, rasos.length, "o pregao raso perdeu os dois fundos");
        assertFalse(Double.isNaN(nivelDaOrdem), "a curva Entrada nao tem nivel na decisao");
        assertTrue(Math.min(rasos[0], rasos[1]) >= nivelDaOrdem,
                "os fundos rasos ainda ficaram ABAIXO do NIVEL da ordem ("
                        + Math.min(rasos[0], rasos[1]) + " contra " + nivelDaOrdem
                        + "), entao este pregao nao exercita a caixa");

        Rodada presa = rodar(new ChannelFade.Guard(true, ChannelFade.Flip.SLIP, true, false),
                raso, degrau);
        List<Fill> preso = aberturas(presa.result());

        assertTrue(preso.size() < semCaixa.size(),
                "a caixa nao recusou nada: " + preso.size() + " contra " + semCaixa.size());

        for (Fill fill : preso) {
            double[] tem = doisUltimosFundos(rasas, fill.bar() - 1);

            assertTrue(Math.min(tem[0], tem[1]) < presa.entrada()[fill.bar() - 1],
                    "entrou na barra " + fill.bar() + " com o nivel em cima da ordem");
        }
    }

    @Test
    @DisplayName("PELO MERGULHO: o nivel vem do primeiro pivo DEPOIS da entrada")
    void fromTheDipTheLevelComesFromAfterTheEntry() {
        PriceSeries bars = new Bars(pregao());

        ChannelFade.Guard peloMergulho =
                new ChannelFade.Guard(true, ChannelFade.Flip.SLIP, false, true);

        Result result = run(peloMergulho);
        List<Fill> abriu = aberturas(result);

        assertFalse(abriu.isEmpty(), "nao entrou");

        int entrou = abriu.get(0).bar();
        Pivots.Pivot[] quando = Pivots.standard().confirmedAt(bars);
        Pivots.Pivot doMergulho = null;

        for (int bar = entrou + 1; bar < bars.size() && doMergulho == null; bar++) {
            if (quando[bar] != null && !quando[bar].top()) {
                doMergulho = quando[bar];
            }
        }

        assertNotNull(doMergulho, "nao houve pivo nenhum depois da entrada");

        // O FUNDO DO MERGULHO FICA ABAIXO DA COMPRA, por construcao -- e a
        // propriedade inteira desta opcao. Sem isto o teste abaixo passaria num
        // pregao onde ela nao vale.
        assertTrue(doMergulho.price() < abriu.get(0).price(),
                "o pivo depois da entrada ficou ACIMA dela: " + doMergulho.price()
                        + " contra " + abriu.get(0).price());

        Fill saiu = saida(result);

        assertNotNull(saiu, "a posicao nunca foi fechada");
        assertTrue(saiu.verb().contains("Stop"),
                "a saida nao foi pelo stop do mergulho: " + saiu.verb());

        double nivel = doMergulho.price() - ChannelFade.TICK;

        assertTrue(saiu.price() <= nivel + 1e-6,
                "o stop saiu acima do nivel do mergulho: " + saiu.price()
                        + " contra " + nivel);
        assertTrue(saiu.price() >= nivel - ChannelFade.Flip.SLIP - 1e-6,
                "o stop saiu longe demais do nivel do mergulho: " + saiu.price()
                        + " contra " + nivel);

        // E A ENTRADA ACONTECEU MAIS CEDO do que com a regra dos dois fundos:
        // com esta opcao ligada o portao dos dois pivos nao se aplica, porque o
        // nivel nao vem deles. Foi o que este teste descobriu na primeira
        // escrita, pedindo "os dois anteriores" numa barra que nao tinha dois.
        List<Fill> comOsDois = aberturas(run(ChannelFade.Guard.standard()));

        assertFalse(comOsDois.isEmpty(), "a regra dos dois fundos nao entrou");
        assertTrue(entrou < comOsDois.get(0).bar(),
                "pelo mergulho a entrada nao veio antes: barra " + entrou
                        + " contra " + comOsDois.get(0).bar());
    }

    // NAO HA TESTE AQUI DA VIRADA DO DIA, e havia um que nao testava nada: o
    // pregao construido tem UM dia so -- 340 minutos a partir das 09:00 -- entao
    // nao existe virada para os fundos atravessarem. A correcao esta escrita em
    // rememberTheLastTwo e foi conferida onde ela importa: na base dele, as 71
    // entradas de 4.337 que usavam fundo de outro pregao passaram a zero.

    @Test
    @DisplayName("SEM DOIS FUNDOS CONFIRMADOS, NAO ENTRA")
    void withoutTwoConfirmedBottomsThereIsNoEntry() {
        // Um pregao que sobe em linha reta com dente de serra nao desenha fundo
        // confirmado nenhum -- e o mergulho que criava a compra foi tirado, de
        // modo que so restaria a entrada, nao o nivel para protege-la.
        double[] price = new double[340];

        for (int bar = 0; bar < price.length; bar++) {
            price[bar] = 100_000 + 20.0 * bar + (bar % 2 == 0 ? 100 : -100);

            if (bar >= MERGULHO && bar < MERGULHO + 3) {
                price[bar] -= 600;
            }
        }

        PriceSeries bars = new Bars(price);

        // A guarda do pregao: ANTES da barra do mergulho nao ha dois fundos.
        assertEquals(0, doisUltimosFundos(bars, MERGULHO - 1).length,
                "este pregao ja tem dois fundos antes da entrada, entao nao mede nada");

        ChannelFade semGuarda = new ChannelFade(SP,
                List.of(new ChannelFade.Rung(Regression.SHORT, 2.0, 40.0)), 1,
                COMPRA, ChannelFade.Flip.off(), ChannelFade.Guard.off());

        semGuarda.sourcedFrom(bars);

        Result solto = new Backtest(Costs.NONE, 1).run(bars, bars, semGuarda, null);

        assertFalse(aberturas(solto).isEmpty(),
                "sem a guarda ja nao havia entrada, entao a recusa abaixo nao mede nada");

        ChannelFade comGuarda = new ChannelFade(SP,
                List.of(new ChannelFade.Rung(Regression.SHORT, 2.0, 40.0)), 1,
                COMPRA, ChannelFade.Flip.off(), ChannelFade.Guard.standard());

        comGuarda.sourcedFrom(bars);

        Result preso = new Backtest(Costs.NONE, 1).run(bars, bars, comGuarda, null);

        for (Fill fill : aberturas(preso)) {
            assertEquals(2, doisUltimosFundos(bars, fill.bar() - 1).length,
                    "entrou na barra " + fill.bar() + " sem dois fundos confirmados");
        }
    }
}
