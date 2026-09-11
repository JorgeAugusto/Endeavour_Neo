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
 * O stop que aparece quando os dois canais viram contra a posição.
 *
 * <p>A posição não sai na virada: espera o primeiro pivô do zigzag depois dela e
 * põe o stop um tique além. É a regra que dá função ao zigzag 2, e o que estes
 * testes defendem é que o nível sai do <b>pivô</b> — não da virada, não do preço
 * de entrada, não de um número escolhido.</p>
 */
@DisplayName("Stop na virada do canal")
class ChannelFadeFlipTest {

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

    /**
     * Arma a COMPRA na primeira barra e nunca desarma.
     *
     * <p>O desarme fica em 200 e não em −1, e a diferença nao e simetrica: a
     * compra desarma quando o estocastico SOBE de volta ao nivel, entao para ela
     * nunca desarmar o nivel tem de ser inalcancavel por CIMA. Com −1, como no
     * latch de venda, ela armava na barra 0 e desarmava na 1.</p>
     */
    private static final StochasticLatch.Settings COMPRA =
            new StochasticLatch.Settings(true, 1, 1, 101, 200, 200, 9);

    private static final int MERGULHO = 241;

    /**
     * Sobe, mergulha uma vez, volta, e depois vira e cai com um repique.
     *
     * <p>É o espelho do pregão do {@code ChannelFadeTest}: o mergulho da barra
     * 241 leva o preço abaixo da borda de baixo com os dois canais ainda
     * subindo, que é a compra. Daí a queda faz os dois virarem, o repique da
     * barra 300 desenha o fundo, e a perna final fura esse fundo.</p>
     */
    private static double[] pregao() {
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
                base = 100_000 + 20.0 * 255 - 25.0 * 45 + 30.0 * 11 - 60.0 * (bar - 311);
            }

            price[bar] = base + (bar % 2 == 0 ? 100 : -100);

            if (bar >= MERGULHO && bar < MERGULHO + 3) {
                price[bar] -= 600;
            }
        }

        return price;
    }

    private static Result run(ChannelFade.Flip flip) {
        PriceSeries bars = new Bars(pregao());
        ChannelFade what = new ChannelFade(SP,
                List.of(new ChannelFade.Rung(Regression.SHORT, 2.0, 40.0)), 1, COMPRA, flip);

        what.sourcedFrom(bars);

        return new Backtest(Costs.NONE, 1).run(bars, bars, what, null);
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

    @Test
    @DisplayName("O STOP SAI DO PIVO, um tique abaixo do primeiro fundo depois da virada")
    void thestopComesFromTheFirstPivotAfterTheTurn() {
        PriceSeries bars = new Bars(pregao());

        // --- as guardas do pregao, lidas dos proprios indicadores ---
        Result comprou = run(ChannelFade.Flip.off());
        List<Fill> abriu = aberturas(comprou);

        assertFalse(abriu.isEmpty(), "o pregao nao gerou compra nenhuma");
        assertEquals("BuyLimit", abriu.get(0).verb(),
                "a entrada nao foi uma compra: " + abriu.get(0).verb());

        int entrou = abriu.get(0).bar();
        Regression canal = new Regression(Regression.SHORT);

        // A virada: a primeira barra depois da entrada em que o canal aponta
        // para baixo. Lida aqui para o teste saber onde procurar o fundo.
        int virou = -1;

        for (int bar = entrou; bar < bars.size(); bar++) {
            if (canal.at(bars, bar).direction() < 0) {
                virou = bar;

                break;
            }
        }

        assertTrue(virou > entrou, "o canal nunca virou depois da compra");

        // O primeiro fundo confirmado com a barra dele DEPOIS da virada.
        Pivots.Pivot[] quando = Pivots.standard().confirmedAt(bars);
        Pivots.Pivot fundo = null;

        for (int bar = virou; bar < bars.size() && fundo == null; bar++) {
            Pivots.Pivot fato = quando[bar];

            if (fato != null && !fato.top() && fato.bar() > virou) {
                fundo = fato;
            }
        }

        assertNotNull(fundo, "nao houve fundo nenhum depois da virada");

        // --- e agora o que a estrategia faz com isso ---
        Fill saiu = saida(run(ChannelFade.Flip.standard()));

        assertNotNull(saiu, "a posicao nunca foi fechada");
        assertTrue(saiu.verb().contains("Stop"),
                "a saida nao foi pelo stop da virada: " + saiu.verb());

        // O NIVEL SAI DO FUNDO, um tique abaixo dele. A execucao e conferida
        // dentro de uma FAIXA e nao por igualdade, porque um stop com folga
        // executa no pior entre o nivel e a abertura: aqui a barra 317 abriu
        // abaixo do nivel e a execucao saiu na abertura.
        //
        // A faixa e estreita o bastante para ter dentes: a entrada foi em
        // 104.120, duzentos e trinta pontos acima do nivel, e portanto fora
        // dela. Um stop tirado do preco de entrada, ou da barra da virada, nao
        // passaria aqui.
        double nivel = fundo.price() - ChannelFade.TICK;

        assertTrue(saiu.price() <= nivel + 1e-6,
                "o stop saiu ACIMA do nivel, o que uma venda de protecao nao faz: "
                        + saiu.price() + " contra " + nivel);

        assertTrue(saiu.price() >= nivel - ChannelFade.Flip.SLIP - 1e-6,
                "o stop saiu mais de " + ChannelFade.Flip.SLIP + " pontos abaixo do nivel: "
                        + saiu.price() + " contra " + nivel);
    }

    @Test
    @DisplayName("O STOP EXECUTA NUM GAP pelo nivel, em vez de ser recusado")
    void thestopFillsThroughAgapInsteadOfBeingRefused() {
        // Sem folga nenhuma -- limite igual ao gatilho, que e a forma que todas
        // as outras estrategias deste projeto usam -- a barra que ABRE abaixo do
        // nivel dispara o stop e tem a execucao recusada por ser "pior" que o
        // limite. A posicao segue viva ate o sino.
        Fill semFolga = saida(run(new ChannelFade.Flip(true, Pivots.WING, 0)));

        assertNotNull(semFolga, "a posicao nunca foi fechada");
        assertEquals("ClosePosition", semFolga.verb(),
                "com limite colado no gatilho o stop executou, e este teste nao mede nada");

        Fill comFolga = saida(run(ChannelFade.Flip.standard()));

        assertNotNull(comFolga, "a posicao nunca foi fechada");
        assertTrue(comFolga.verb().contains("Stop"),
                "com folga o stop ainda nao executou: " + comFolga.verb());
        assertTrue(comFolga.bar() < semFolga.bar(),
                "com folga a saida nao foi antes: barra " + comFolga.bar()
                        + " contra " + semFolga.bar());
    }

    @Test
    @DisplayName("SEM O MODULO, a mesma posicao nao e stopada")
    void withoutThemoduleTheSamePositionIsNotStopped() {
        // O alvo esta a quarenta deviacoes de proposito: sem o stop da virada, a
        // unica saida possivel e o fim do pregao.
        Fill semStop = saida(run(ChannelFade.Flip.off()));

        assertNotNull(semStop, "a posicao nunca foi fechada");
        assertEquals("ClosePosition", semStop.verb(),
                "sem o modulo alguma outra coisa fechou a posicao: " + semStop.verb());

        Fill comStop = saida(run(ChannelFade.Flip.standard()));

        assertNotNull(comStop, "a posicao nunca foi fechada");
        assertTrue(comStop.bar() < semStop.bar(),
                "com o stop a posicao nao saiu antes: barra " + comStop.bar()
                        + " contra " + semStop.bar());
    }

    @Test
    @DisplayName("SEM VIRADA NAO HA STOP, por mais fundos que o preco desenhe")
    void withoutAturnThereIsNoStop() {
        // O MESMO pregao, com um canal longo demais para virar dentro dele: a
        // regressao de 250 barras ainda aponta para cima quando a de 45 ja
        // desabou. Sem os canais virando contra a posicao, o stop nunca arma --
        // e o preco desenha fundos o tempo todo, entao o que o segura e a
        // virada e nao a falta de pivo.
        PriceSeries bars = new Bars(pregao());
        ChannelFade what = new ChannelFade(SP,
                List.of(new ChannelFade.Rung(250, 2.0, 40.0)), 1,
                COMPRA, ChannelFade.Flip.standard());

        what.sourcedFrom(bars);

        Result result = new Backtest(Costs.NONE, 1).run(bars, bars, what, null);

        assertFalse(aberturas(result).isEmpty(), "o canal de 250 nao gerou compra nenhuma");

        Fill saiu = saida(result);

        assertNotNull(saiu, "a posicao nunca foi fechada");
        assertEquals("ClosePosition", saiu.verb(),
                "algo stopou a posicao sem os canais terem virado: " + saiu.verb());

        // E a guarda: o canal de 250 REALMENTE nao vira no pregao inteiro.
        Regression longo = new Regression(250);
        int virou = -1;

        for (int bar = 250; bar < bars.size(); bar++) {
            if (longo.at(bars, bar).direction() < 0) {
                virou = bar;

                break;
            }
        }

        assertEquals(-1, virou,
                "o canal de 250 virou na barra " + virou + ", entao o teste nao mede a virada");
    }

    @Test
    @DisplayName("O STOP NAO ANDA: fica no primeiro fundo, e nao segue os seguintes")
    void thestopDoesNotTrail() {
        PriceSeries bars = new Bars(pregao());
        Fill saiu = saida(run(ChannelFade.Flip.standard()));

        assertNotNull(saiu, "a posicao nunca foi fechada");

        Pivots.Pivot[] quando = Pivots.standard().confirmedAt(bars);
        Regression canal = new Regression(Regression.SHORT);
        int virou = -1;

        for (int bar = 0; bar < bars.size(); bar++) {
            if (canal.at(bars, bar).direction() < 0 && virou < 0
                    && bar > aberturas(run(ChannelFade.Flip.off())).get(0).bar()) {
                virou = bar;
            }
        }

        // SO OS FUNDOS QUE A ESTRATEGIA CHEGA A OLHAR: depois da virada e antes
        // da saida. Contados do pregao inteiro, como estavam, o numero incluia
        // os fundos de antes da virada -- que ela nunca ve -- e a asercao
        // passava sem que houvesse um segundo fundo para o stop poder seguir.
        int candidatos = 0;

        for (int bar = virou; bar > 0 && bar < saiu.bar() && bar < quando.length; bar++) {
            Pivots.Pivot fato = quando[bar];

            if (fato != null && !fato.top() && fato.bar() > virou) {
                candidatos++;
            }
        }

        // UM SO. Este pregao nao prova que o stop deixa de seguir os fundos
        // seguintes, porque nao ha fundo seguinte para ele seguir: entre a
        // virada e a saida o preco desenha exatamente um. A regra esta escrita e
        // comentada no codigo, e NAO esta provada aqui -- dito em voz alta em
        // vez de mascarado por uma asercao que passa pelo motivo errado.
        assertEquals(1, candidatos,
                "o pregao passou a ter " + candidatos + " fundos entre a virada e a saida;"
                        + " com mais de um da para provar que o stop nao anda, e vale"
                        + " trocar esta asercao por essa prova");
    }
}
