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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Side;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O motor: ordens em repouso, execução dentro da barra, e operações.
 *
 * <p>Quase tudo que se testa aqui é uma frase do manual do NTSL virada em
 * número. As que não são — a ordem dentro da barra, o desempate entre stop e
 * alvo — são as que o OHLC não consegue responder, e nessas o que se defende é
 * que a escolha é a conservadora <b>e que ela é contada</b>.</p>
 */
@DisplayName("O motor de backtest")
class BacktestTest {

    /** Uma série escrita à mão, barra por barra. */
    private record Bars(double[] open, double[] high, double[] low, double[] close)
            implements PriceSeries {

        @Override
        public int size() {
            return open.length;
        }

        @Override
        public long timeAt(int index) {
            return 1_600_000_000_000L + index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return open[index];
        }

        @Override
        public double highAt(int index) {
            return high[index];
        }

        @Override
        public double lowAt(int index) {
            return low[index];
        }

        @Override
        public double closeAt(int index) {
            return close[index];
        }
    }

    /** Cinco barras paradas em 100, para quando o preço não é o assunto. */
    private static Bars flat(int bars) {
        double[] o = new double[bars];
        double[] h = new double[bars];
        double[] l = new double[bars];
        double[] c = new double[bars];

        for (int i = 0; i < bars; i++) {
            o[i] = 100;
            h[i] = 101;
            l[i] = 99;
            c[i] = 100;
        }

        return new Bars(o, h, l, c);
    }

    private static Result run(PriceSeries series, Strategy strategy) {
        return new Backtest(Costs.NONE, 1).run(series, strategy);
    }

    // ------------------------------------------------------------- causalidade

    @Test
    @DisplayName("a ordem pedida no fechamento NAO executa na propria barra")
    void anOrderAskedAtTheCloseDoesNotFillOnItsOwnBar() {
        Result result = run(flat(3), (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket();
            }
        });

        assertEquals(1, result.fills().size(), "executou mais de uma vez");

        // Pedida no fechamento da barra 0; a mais cedo que pode acontecer
        // e' a ABERTURA da 1. Trocar isso de lugar e' o look-ahead inteiro:
        // deixa a estrategia mandar sabendo em que barra vai executar.
        assertEquals(1, result.fills().get(0).bar(), "a ordem executou na barra que a pediu");
    }

    @Test
    @DisplayName("a estrategia nao consegue olhar para frente")
    void theStrategyCannotLookForward() {
        assertThrows(IllegalArgumentException.class,
                () -> run(flat(3), (market, desk) -> market.close(-1)),
                "a serie deixou ler uma barra que ainda nao aconteceu");
    }

    @Test
    @DisplayName("historia que nao existe e NaN, nao zero")
    void historyThatDoesNotExistIsNaN() {
        run(flat(3), (market, desk) -> {
            if (market.bar() == 0) {
                assertTrue(Double.isNaN(market.close(5)),
                        "barra anterior ao inicio da serie devolveu um numero");
            }
        });
    }

    // ------------------------------------------------------------------ preco

    @Test
    @DisplayName("a ordem a mercado executa na abertura seguinte")
    void aMarketOrderFillsAtTheNextOpen() {
        Bars bars = new Bars(
                new double[] {100, 107, 100},
                new double[] {101, 110, 101},
                new double[] {99, 105, 99},
                new double[] {100, 108, 100});

        Result result = run(bars, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket();
            }
        });

        assertEquals(107, result.fills().get(0).price(), 0.0, "nao executou na abertura da barra 1");
    }

    /** Barras finas: quatro por barra grossa, um minuto cada. */
    private record Fine(double[] open, double[] high, double[] low, double[] close)
            implements PriceSeries {

        @Override
        public int size() {
            return close.length;
        }

        @Override
        public long timeAt(int index) {
            return index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return open[index];
        }

        @Override
        public double highAt(int index) {
            return high[index];
        }

        @Override
        public double lowAt(int index) {
            return low[index];
        }

        @Override
        public double closeAt(int index) {
            return close[index];
        }
    }

    /** As mesmas barras, agrupadas de quatro em quatro. */
    private record Coarse(Fine fine) implements PriceSeries {

        @Override
        public int size() {
            return fine.size() / 4;
        }

        @Override
        public long timeAt(int index) {
            return fine.timeAt(index * 4);
        }

        @Override
        public double openAt(int index) {
            return fine.openAt(index * 4);
        }

        @Override
        public double closeAt(int index) {
            return fine.closeAt(index * 4 + 3);
        }

        @Override
        public double highAt(int index) {
            double top = Double.NEGATIVE_INFINITY;

            for (int at = index * 4; at < index * 4 + 4; at++) {
                top = Math.max(top, fine.highAt(at));
            }

            return top;
        }

        @Override
        public double lowAt(int index) {
            double bottom = Double.POSITIVE_INFINITY;

            for (int at = index * 4; at < index * 4 + 4; at++) {
                bottom = Math.min(bottom, fine.lowAt(at));
            }

            return bottom;
        }
    }

    @Test
    @DisplayName("A ESTRATEGIA LE A BARRA DA DECISAO, e nao a da execucao")
    void thestrategyReadsTheDecisionBarAndNotTheExecutedOne() {
        Fine fine = new Fine(
                new double[] {100, 101, 102, 103, 200, 201, 202, 203},
                new double[] {110, 111, 112, 113, 210, 211, 212, 213},
                new double[] {90, 91, 92, 93, 190, 191, 192, 193},
                new double[] {105, 106, 107, 108, 205, 206, 207, 208});

        Coarse coarse = new Coarse(fine);

        java.util.List<String> visto = new java.util.ArrayList<>();

        new Backtest(Costs.NONE, 1).run(fine, coarse, (market, desk) ->
                visto.add(market.bar() + ":" + market.open() + "/" + market.close()), null);

        // Uma EMA 17 que nao nomeia a propria escala e dezessete DESTAS barras.
        // Lendo a serie da execucao ela virava dezessete ticks -- menos de um
        // segundo de mercado -- no instante em que o leitor escolhesse ticks, e
        // nada na tela dizia isso.
        assertEquals(java.util.List.of("0:100.0/108.0", "1:200.0/208.0"), visto,
                "a estrategia nao leu as barras grossas: " + visto);
    }

    @Test
    @DisplayName("EXECUTA CONTRA A BARRA FINA, e e reportada na barra da decisao")
    void theorderFillsAgainstTheFineBarAndIsReportedOnTheDecisionBar() {
        Fine fine = new Fine(
                new double[] {100, 100, 100, 100, 100, 100, 100, 100},
                new double[] {100, 100, 100, 100, 100, 130, 100, 100},
                new double[] {100, 100, 100, 100, 100, 100, 100, 100},
                new double[] {100, 100, 100, 100, 100, 100, 100, 100});

        Coarse coarse = new Coarse(fine);

        Result result = new Backtest(Costs.NONE, 1).run(fine, coarse, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyStop(120, 120);
            }
        }, null);

        assertEquals(1, result.fills().size(), "a ordem nao executou");

        // O PRECO VEM DA BARRA FINA -- o stop disparou no quinto minuto, que e o
        // segundo da segunda barra grossa, e e ai que ele estaria no mercado. Uma
        // execucao por barra grossa teria de esperar a abertura dela.
        assertEquals(120, result.fills().get(0).price(), 0.0, "nao executou no gatilho");

        // E O NUMERO VEM DA BARRA DA DECISAO, que e a que o grafico desenha. O
        // numero da barra fina nao serve a ninguem de fora do motor: e um entre
        // quatro milhoes e meio, e todo leitor -- o grafico, a tabela, a curva --
        // trabalha em candles.
        assertEquals(1, result.fills().get(0).bar(), "a execucao nao foi reportada na barra do grafico");
    }

    @Test
    @DisplayName("A ORDEM A MERCADO EXECUTA UMA VEZ, nao a cada barra fina")
    void amarketOrderFillsOnceAndNotOnEveryFineBar() {
        double[] flat = new double[16];

        java.util.Arrays.fill(flat, 100);

        Fine fine = new Fine(flat, flat, flat, flat);
        Coarse coarse = new Coarse(fine);

        Result result = new Backtest(Costs.NONE, 1).run(fine, coarse, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket();
            }
        }, null);

        // ORDEM A MERCADO E ENVIADA, nao fica em repouso: executa na abertura
        // seguinte e acabou. Enquanto o livro era refeito em toda barra isto nao
        // dava para notar, porque a proxima reconstrucao a apagava.
        //
        // Com a execucao mais fina que a decisao, uma ordem a mercado deixada no
        // livro executa DE NOVO em cada barra entre duas decisoes -- e uma
        // semana do cruzamento sobre ticks saiu com 136.455 operacoes contra as
        // 65 que ela faz de verdade.
        assertEquals(1, result.fills().size(),
                "a ordem a mercado executou " + result.fills().size() + " vezes");
    }

    @Test
    @DisplayName("A ORDEM EM REPOUSO QUE EXECUTOU SAI DO LIVRO")
    void arestingOrderThatFilledLeavesTheBook() {
        // O gatilho e alcancado em TRES barras finas seguidas, todas dentro da
        // mesma barra de decisao. A ordem foi pedida uma vez.
        Fine fine = new Fine(
                new double[] {100, 100, 100, 100, 100, 100, 100, 100},
                new double[] {100, 100, 100, 100, 130, 130, 130, 100},
                new double[] {100, 100, 100, 100, 100, 100, 100, 100},
                new double[] {100, 100, 100, 100, 100, 100, 100, 100});

        Coarse coarse = new Coarse(fine);

        Result result = new Backtest(Costs.NONE, 1).run(fine, coarse, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyStop(120, 120);
            }
        }, null);

        // ORDEM QUE EXECUTOU NAO E MAIS ORDEM. Isso nao precisava ser dito
        // enquanto o livro era refeito em toda barra, porque a reconstrucao
        // seguinte a varria antes que alguem notasse -- e com a execucao mais
        // fina que a decisao ela executava de novo a cada preco.
        //
        // Foi assim que a Range 90 caiu com um lote de stop NaN: o gatilho de
        // entrada executava duas vezes, a segunda ja com a posicao aberta, e o
        // segundo lote entrava pelo caminho da adicao sem nunca ter tido um
        // pullback para lhe dar um stop.
        assertEquals(1, result.fills().size(),
                "a ordem em repouso executou " + result.fills().size() + " vezes");
    }

    @Test
    @DisplayName("A PERNA DA OCO QUE EXECUTA MATA AS OUTRAS ate a proxima volta")
    void oneOCOlegFillingKillsTheOthersUntilTheNextTurn() {
        // Comprado em QUATRO, com stop de quatro e alvo parcial de dois. O alvo
        // e alcancado na primeira barra fina da volta e o stop na segunda --
        // separados de proposito, porque com os dois na mesma barra o desempate
        // do stop resolve sozinho e nao se prova nada.
        Fine fine = new Fine(
                new double[] {100, 100, 100, 100, 100, 100, 100, 100,
                        100, 100, 100, 100, 100, 100, 100, 100},
                new double[] {100, 100, 100, 100, 100, 100, 100, 100,
                        140, 100, 100, 100, 100, 100, 100, 100},
                new double[] {100, 100, 100, 100, 100, 100, 100, 100,
                        100, 60, 100, 100, 100, 100, 100, 100},
                new double[] {100, 100, 100, 100, 100, 100, 100, 100,
                        100, 100, 100, 100, 100, 100, 100, 100});

        Coarse coarse = new Coarse(fine);

        Result result = new Backtest(Costs.NONE, 1).run(fine, coarse, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(4);
            }

            if (market.bar() == 1) {
                desk.sellToCoverStop(80, 80, 4);
                desk.sellToCoverLimit(130, 2);
            }
        }, null);

        // O manual: as coberturas sao mandadas como OCO, "de modo que voce nao
        // precisa se preocupar em gerenciar e cancelar eventuais ordens de
        // cobertura que possam permanecer abertas apos a execucao de apenas uma
        // das pernas de saida". Uma perna executa, as outras morrem -- e morrem
        // ATE A PROXIMA VOLTA, nao ate a proxima barra fina.
        //
        // Sem isso: a parcial sai com dois, e o stop que sobrou no livro leva os
        // outros dois um preco depois, fechando o dia por uma ordem que a
        // estrategia nunca reautorizou.
        assertEquals(2, result.fills().size(),
                "a OCO deixou executar mais de uma perna: " + result.fills().size());
        assertEquals(2, result.openAtTheEnd(),
                "a posicao devia ter sobrado em dois contratos");
    }

    @Test
    @DisplayName("A CURVA DE PATRIMONIO E UM PONTO POR BARRA DO GRAFICO")
    void theworthCurveIsOnePointPerChartBar() {
        double[] up = new double[16];

        for (int at = 0; at < up.length; at++) {
            up[at] = 100 + at;
        }

        Fine fine = new Fine(up, up, up, up);
        Coarse coarse = new Coarse(fine);

        Result result = new Backtest(Costs.NONE, 1).run(fine, coarse, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket();
            }

            if (market.bar() == 2) {
                desk.closePosition();
            }
        }, null);

        // UM PONTO POR BARRA DE DECISAO, e nao por barra executada. E o eixo
        // honesto -- a curva e desenhada ao lado de um grafico exatamente destas
        // barras -- e e a diferenca entre rodar e nao rodar: um ano do WIN em
        // ticks sao 194 MILHOES de barras, e um double em cada uma e um giga e
        // meio de curva para um grafico de 141.602 candles. Acabava a memoria
        // antes de acabar a paciencia.
        assertEquals(coarse.size(), result.worth().length,
                "a curva de patrimonio nao esta no eixo do grafico");
        assertEquals(coarse.size(), result.balancePerBar().length,
                "a curva de saldo nao esta no eixo do grafico");

        // E ELA ANDA. As tres curvas caminham pelo indice da barra e comparam
        // com o indice que o fill carrega; se os dois estiverem em eixos
        // diferentes, o saldo fica reto em zero o tempo todo e parece uma
        // estrategia que nao fez nada.
        double[] balance = result.balancePerBar();

        assertTrue(balance[balance.length - 1] != 0,
                "o saldo terminou em zero: a curva nunca encontrou as operacoes");
    }

    @Test
    @DisplayName("a ordem em repouso continua viva entre duas decisoes")
    void arestingOrderStaysAliveBetweenTwoDecisions() {
        Fine fine = new Fine(
                new double[] {100, 100, 100, 100, 100, 100, 100, 100},
                new double[] {100, 100, 100, 100, 100, 100, 130, 100},
                new double[] {100, 100, 100, 100, 100, 100, 100, 100},
                new double[] {100, 100, 100, 100, 100, 100, 100, 100});

        Coarse coarse = new Coarse(fine);

        Result result = new Backtest(Costs.NONE, 1).run(fine, coarse, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyStop(120, 120);
            }
        }, null);

        // O OUTRO LADO DA MESMA REGRA. Limitada e stop esperam um preco, e
        // esperam ate a estrategia parar de pedi-las -- aqui o gatilho so e
        // alcancado na setima barra fina, tres depois da decisao que o colocou.
        // Um livro que jogasse fora tudo que foi enviado deixaria esta de fora.
        assertEquals(1, result.fills().size(), "a ordem em repouso nao sobreviveu a barra");
        assertEquals(1, result.fills().get(0).bar(), "nao foi reportada na barra do grafico");
        assertEquals(120, result.fills().get(0).price(), 0.0, "nao executou no gatilho");
    }

    @Test
    @DisplayName("A ESTRATEGIA VE TUDO QUE EXECUTOU DESDE A VEZ ANTERIOR")
    void thestrategySeesEverythingThatFilledSinceItsLastTurn() {
        Fine fine = new Fine(
                new double[] {100, 100, 100, 100, 100, 100, 100, 100},
                new double[] {100, 100, 100, 100, 130, 130, 100, 100},
                new double[] {100, 100, 100, 100, 100, 100, 70, 100},
                new double[] {100, 100, 100, 100, 100, 100, 100, 100});

        Coarse coarse = new Coarse(fine);

        java.util.List<Integer> quantos = new java.util.ArrayList<>();

        new Backtest(Costs.NONE, 1).run(fine, coarse, (market, desk) -> {
            quantos.add(market.filled().size());

            if (market.bar() == 0) {
                desk.buyStop(120, 120, 2);
                desk.sellShortStop(80, 80, 2);
            }
        }, null);

        // DUAS EXECUCOES NUMA VOLTA SO: a compra dispara no quinto minuto e a
        // venda no setimo, e as duas acontecem entre a mesma decisao e a
        // seguinte.
        //
        // Este numero ja foi TRES, e o tres era sintoma: a ordem de compra
        // continuava no livro depois de executar e executava de novo no minuto
        // seguinte, que tambem alcanca o gatilho. Ordem que executou sai do
        // livro.
        //
        // Um livro de lotes que so visse a ultima execucao da volta seguiria
        // emitindo ordem para contratos que sairam tres minutos antes.
        assertEquals(java.util.List.of(0, 2), quantos,
                "a estrategia nao viu as execucoes da volta: " + quantos);
    }

    @Test
    @DisplayName("A VARREDURA DIZ POR ONDE ANDA, e termina dizendo que acabou")
    void therunSaysHowFarAlongItIs() {
        double[] flat = new double[1_000];

        java.util.Arrays.fill(flat, 100);

        Bars bars = new Bars(flat, flat, flat, flat);

        java.util.List<int[]> ditos = new java.util.ArrayList<>();

        new Backtest(Costs.NONE, 1).run(bars, (market, desk) -> { },
                (reached, many) -> ditos.add(new int[] {reached, many}));

        // AVISA DURANTE, e nao so no fim. Uma varredura que so fala na ultima
        // barra deixa a barra vazia o tempo todo e cheia de uma vez, que e a
        // mesma coisa que nao ter barra nenhuma.
        assertTrue(ditos.size() >= 50,
                "a varredura so avisou " + ditos.size() + " vezes em mil barras");

        // POUCOS AVISOS, nao um por barra: dezessete milhoes de chamadas para
        // mover uma barra que redesenha sessenta vezes por segundo seria o motor
        // trabalhando para a tela em vez do contrario.
        assertTrue(ditos.size() <= 220,
                "a varredura avisou " + ditos.size() + " vezes em mil barras");

        int antes = 0;

        for (int[] dito : ditos) {
            assertTrue(dito[0] >= antes, "o contador voltou: " + dito[0] + " depois de " + antes);
            assertEquals(1_000, dito[1], "o total mudou no meio da varredura");

            antes = dito[0];
        }

        // E O ULTIMO E O FIM. Sem isto uma varredura de mil barras avisando de
        // cinco em cinco para em 996, e a barra fica parada quase cheia enquanto
        // a janela ja mostra o resultado -- que e exatamente a hora em que
        // alguem olha para ela.
        assertEquals(1_000, ditos.get(ditos.size() - 1)[0],
                "o ultimo aviso nao foi o da ultima barra");
    }

    @Test
    @DisplayName("A ESTRATEGIA LE AS EXECUCOES DA PROPRIA BARRA, e so as dela")
    void thestrategyReadsTheFillsOfItsOwnBarAndNoOthers() {
        Bars bars = new Bars(
                new double[] {100, 107, 120, 130},
                new double[] {101, 110, 121, 131},
                new double[] {99, 105, 119, 129},
                new double[] {100, 108, 120, 130});

        java.util.List<String> seen = new java.util.ArrayList<>();

        run(bars, (market, desk) -> {
            for (Fill fill : market.filled()) {
                seen.add(market.bar() + ":" + fill.side() + fill.quantity() + "@" + fill.price());
            }

            if (market.bar() == 0) {
                desk.buyAtMarket(2);
            }

            if (market.bar() == 1) {
                desk.closePosition();
            }
        });

        // Uma estrategia que guarda livro de lotes nao consegue saber, pela
        // posicao liquida, QUAL lote acabou de fechar. Adivinhar pelos precos
        // seria refazer os desempates do motor dentro de cada estrategia; uma
        // mesa de verdade sabe as proprias execucoes.
        assertEquals(java.util.List.of("1:BUY2@107.0", "2:SELL2@120.0"), seen,
                "a barra nao entregou exatamente as execucoes dela: " + seen);
    }

    @Test
    @DisplayName("as execucoes lidas ja aconteceram: nao ha futuro nelas")
    void thefillsReadAreOnesThatAlreadyHappened() {
        Bars bars = new Bars(
                new double[] {100, 107},
                new double[] {101, 110},
                new double[] {99, 105},
                new double[] {100, 108});

        java.util.List<Integer> quando = new java.util.ArrayList<>();

        run(bars, (market, desk) -> {
            if (!market.filled().isEmpty()) {
                quando.add(market.bar());
            }

            if (market.bar() == 0) {
                desk.buyAtMarket();
            }
        });

        // A ordem foi pedida no fechamento da barra 0 e executou na abertura da
        // 1. Se a leitura aparecesse na propria barra 0, a estrategia estaria
        // vendo o resultado de uma ordem que ainda nao foi ao mercado.
        assertEquals(java.util.List.of(1), quando,
                "a execucao apareceu numa barra em que ainda nao tinha acontecido: " + quando);
    }

    @Test
    @DisplayName("a compra limitada executa no limite quando o preco desce ate ele")
    void aBuyLimitFillsAtTheLimit() {
        Bars bars = new Bars(
                new double[] {100, 100},
                new double[] {101, 101},
                new double[] {99, 95},
                new double[] {100, 96});

        Result result = run(bars, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyLimit(97);
            }
        });

        assertEquals(1, result.fills().size(), "nao executou");
        assertEquals(97, result.fills().get(0).price(), 0.0, "executou fora do limite");
    }

    @Test
    @DisplayName("no gap a favor, a limitada executa na abertura -- nao no limite")
    void onAGapInItsFavourTheLimitFillsAtTheOpen() {
        Bars bars = new Bars(
                new double[] {100, 90},
                new double[] {101, 92},
                new double[] {99, 88},
                new double[] {100, 91});

        Result result = run(bars, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyLimit(97);
            }
        });

        // Executar em 97 seria embolsar 7 pontos que nunca existiram -- o
        // mercado JA estava melhor que a ordem quando ela ficou viva.
        assertEquals(90, result.fills().get(0).price(), 0.0,
                "a limitada embolsou o gap em vez de executar na abertura");
    }

    @Test
    @DisplayName("no gap contra, o stop executa na abertura -- pior do que foi pedido")
    void onAGapAgainstItTheStopFillsAtTheOpenAndWorse() {
        Bars bars = new Bars(
                new double[] {100, 90},
                new double[] {101, 92},
                new double[] {99, 88},
                new double[] {100, 91});

        Result result = run(bars, (market, desk) -> {
            if (market.bar() == 0) {
                desk.sellShortStop(97, 85);
            }
        });

        assertEquals(90, result.fills().get(0).price(), 0.0,
                "o stop executou no nivel pedido, e nao onde o preco estava");
    }

    @Test
    @DisplayName("o stop que o preco atravessou alem do limite nao executa")
    void aStopThePriceRanPastDoesNotFillAtAll() {
        Bars bars = new Bars(
                new double[] {100, 80},
                new double[] {101, 82},
                new double[] {99, 78},
                new double[] {100, 81});

        Result result = run(bars, (market, desk) -> {
            if (market.bar() == 0) {
                desk.sellShortStop(97, 95);   // dispara em 97, mas so ate 95
            }
        });

        // O preco abriu em 80: passou do gatilho E do limite. E' isso que o
        // segundo preco do stop do NTSL serve para dizer.
        assertTrue(result.fills().isEmpty(), "executou 15 pontos alem do limite pedido");
    }

    // --------------------------------------------------------------- proposito

    @Test
    @DisplayName("cobertura sem o que cobrir e ignorada, nao e erro")
    void aCoverWithNothingToCoverIsIgnored() {
        Result result = run(flat(3), (market, desk) -> {
            if (market.bar() == 0) {
                desk.sellToCoverAtMarket(5);   // zerado: o Profit ignora
            }
        });

        assertTrue(result.fills().isEmpty(), "abriu posicao vendida com uma ordem de cobertura");
    }

    @Test
    @DisplayName("a cobertura nunca inverte: e limitada ao que esta aberto")
    void aCoverNeverInverts() {
        Result result = run(flat(4), (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(2);
            } else if (market.bar() == 1) {
                desk.sellToCoverAtMarket(10);   // pede 10, ha 2
            }
        });

        assertEquals(2, result.fills().get(1).quantity(), "a cobertura passou do tamanho da posicao");
        assertEquals(0, result.openAtTheEnd(), "sobrou posicao depois de cobrir tudo");
    }

    @Test
    @DisplayName("ordem de abertura do outro lado cobre, e pode inverter")
    void anOpeningOrderOnTheOtherSideCoversAndMayInvert() {
        Result result = run(flat(4), (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(2);
            } else if (market.bar() == 1) {
                desk.sellShortAtMarket(5);   // 2 cobrem, 3 abrem vendido
            }
        });

        assertEquals(-3, result.openAtTheEnd(), "a inversao nao deixou 3 vendidos");
        assertEquals(1, result.count(), "a operacao comprada nao foi fechada pela inversao");
        assertEquals(Side.BUY, result.trades().get(0).side(), "a operacao fechada nao era a comprada");
    }

    // ------------------------------------------------------------------- OCO

    @Test
    @DisplayName("as coberturas sao uma OCO: no maximo uma executa por barra")
    void theCoversAreOneOcoSoOnlyOneFillsPerBar() {
        Bars bars = new Bars(
                new double[] {100, 100, 100},
                new double[] {101, 120, 120},
                new double[] {99, 99, 99},
                new double[] {100, 110, 110});

        Result result = run(bars, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(4);
            } else if (market.bar() == 1) {
                desk.sellToCoverLimit(105, 2);
                desk.sellToCoverLimit(110, 2);
            }
        });

        // A barra 2 alcanca os dois alvos. A OCO deixa um so: o outro morre, e
        // a estrategia o reapregoa no fechamento seguinte se ainda quiser.
        long covers = result.fills().stream().filter(f -> f.side() == Side.SELL).count();

        assertEquals(1, covers, "as duas pernas da OCO executaram na mesma barra");
        assertEquals(2, result.openAtTheEnd(), "sobrou posicao diferente de dois contratos");
    }

    @Test
    @DisplayName("stop e alvo na mesma barra: ganha o stop, e a duvida e contada")
    void whenBothAreReachableTheStopWinsAndTheDoubtIsCounted() {
        Bars bars = new Bars(
                new double[] {100, 100, 100},
                new double[] {101, 101, 120},
                new double[] {99, 99, 80},
                new double[] {100, 100, 100});

        Result result = run(bars, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(1);
            } else if (market.bar() == 1) {
                desk.sellToCoverLimit(115, 1);       // alvo
                desk.sellToCoverStop(85, 85, 1);     // stop
            }
        });

        assertEquals(85, result.fills().get(1).price(), 0.0,
                "a barra pagou o alvo em vez do stop");

        // O que torna o desempate honesto nao e' acertar -- o OHLC nao permite
        // acertar -- e' saber quantas vezes foi preciso desempatar.
        assertEquals(1, result.ambiguousBars(), "a barra ambigua nao foi contada");
    }

    // ------------------------------------------------------------------ livro

    @Test
    @DisplayName("nao reemitir a ordem e como cancela-la")
    void notReEmittingAnOrderIsHowYouCancelIt() {
        Bars bars = new Bars(
                new double[] {100, 100, 100, 100},
                new double[] {101, 101, 101, 101},
                new double[] {99, 99, 90, 90},
                new double[] {100, 100, 100, 100});

        Result result = run(bars, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyLimit(95);   // pedida uma vez, e nunca mais
            }
        });

        // A barra 1 nao alcanca 95. No fechamento da 1 a estrategia nao pede
        // nada, entao a ordem morre -- e a barra 2, que alcancaria, nao acha
        // nada apregoado.
        assertTrue(result.fills().isEmpty(), "a ordem sobreviveu sem ser reemitida");
    }

    @Test
    @DisplayName("a ordem reemitida todo fechamento continua viva")
    void anOrderReAskedAtEveryCloseStaysAlive() {
        Bars bars = new Bars(
                new double[] {100, 100, 100, 100},
                new double[] {101, 101, 101, 101},
                new double[] {99, 99, 90, 90},
                new double[] {100, 100, 100, 100});

        Result result = run(bars, (market, desk) -> {
            if (!market.hasPosition()) {
                desk.buyLimit(95);
            }
        });

        assertEquals(1, result.fills().size(), "a ordem reemitida nao executou");
        assertEquals(2, result.fills().get(0).bar(), "executou em outra barra");
    }

    @Test
    @DisplayName("CancelPendingOrders limpa o que foi pedido antes dele")
    void cancelPendingOrdersClearsWhatWasAskedBeforeIt() {
        Bars bars = new Bars(
                new double[] {100, 100},
                new double[] {101, 101},
                new double[] {99, 90},
                new double[] {100, 95});

        Result result = run(bars, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyLimit(95);
                desk.cancelPendingOrders();
                desk.buyLimit(92);
            }
        });

        assertEquals(1, result.fills().size(), "sobrou ordem de antes do cancelamento");
        assertEquals(92, result.fills().get(0).price(), 0.0, "executou a ordem cancelada");
    }

    // ------------------------------------------------------------- a posicao

    @Test
    @DisplayName("acumular reajusta o preco medio, e a parcial realiza contra ele")
    void accumulatingRe_averagesAndThePartialRealisesAgainstIt() {
        Bars bars = new Bars(
                new double[] {100, 100, 120, 130},
                new double[] {101, 121, 131, 131},
                new double[] {99, 99, 119, 129},
                new double[] {100, 120, 130, 130});

        Result result = run(bars, (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(1);         // executa em 100
            } else if (market.bar() == 1) {
                desk.buyAtMarket(1);         // executa em 120 -> medio 110
            } else if (market.bar() == 2) {
                desk.sellToCoverAtMarket(2); // executa em 130
            }
        });

        // Dois contratos comprados a 110 em media, vendidos a 130: 40 pontos.
        assertEquals(40, result.gross(), 1e-9, "o preco medio nao foi o de duas entradas");
        assertEquals(1, result.count(), "as duas entradas viraram duas operacoes");
        assertEquals(2, result.trades().get(0).contracts(), "a operacao nao registrou o pico de 2");
    }

    @Test
    @DisplayName("a operacao vai de zerado a zerado")
    void aTradeRunsFromFlatToFlat() {
        Result result = run(flat(6), (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(1);
            } else if (market.bar() == 1) {
                desk.buyAtMarket(1);
            } else if (market.bar() == 2) {
                desk.sellToCoverAtMarket(1);
            } else if (market.bar() == 3) {
                desk.sellToCoverAtMarket(1);
            }
        });

        assertEquals(1, result.count(), "duas entradas e duas saidas viraram mais de uma operacao");
        assertEquals(4, result.trades().get(0).fills().size(), "a operacao perdeu execucoes pelo caminho");
    }

    // ------------------------------------------------------------------ custo

    @Test
    @DisplayName("o custo e cobrado por contrato, metade em cada ponta")
    void theCostIsChargedPerContractHalfOnEachSide() {
        Result result = new Backtest(Costs.MEASURED, 1).run(flat(4), (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(3);
            } else if (market.bar() == 1) {
                desk.sellToCoverAtMarket(3);
            }
        });

        // 6,5 pontos por giro completo de UM contrato; tres contratos entrando
        // e saindo = 19,5.
        assertEquals(19.5, result.cost(), 1e-9, "o custo nao acompanhou o numero de contratos");
    }

    // ------------------------------------------------------------------- fim

    @Test
    @DisplayName("posicao aberta no fim nao vira operacao, e nao some do relatorio")
    void aPositionLeftOpenIsNotATradeAndIsNotHidden() {
        Result result = run(flat(3), (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(2);
            }
        });

        assertEquals(0, result.count(), "inventou uma saida que a estrategia nao pediu");
        assertEquals(2, result.openAtTheEnd(), "a posicao aberta sumiu do relatorio");
        assertTrue(result.endedHolding(), "o relatorio nao avisa que terminou posicionado");
    }

    @Test
    @DisplayName("ClosePosition zera seja qual for o tamanho")
    void closePositionGoesFlatWhateverTheSize() {
        Result result = run(flat(4), (market, desk) -> {
            if (market.bar() == 0) {
                desk.buyAtMarket(7);
            } else if (market.bar() == 1) {
                desk.closePosition();
            }
        });

        assertEquals(0, result.openAtTheEnd(), "nao zerou");
        assertEquals("ClosePosition", result.fills().get(1).verb(), "a execucao nao lembra quem a causou");
    }

    @Test
    @DisplayName("o mesmo grafico da a mesma figura")
    void theSameChartGivesTheSameFigure() {
        Bars bars = new Bars(
                new double[] {100, 105, 95, 110, 90},
                new double[] {106, 108, 100, 112, 95},
                new double[] {98, 94, 89, 105, 85},
                new double[] {105, 95, 99, 106, 91});

        Strategy strategy = (market, desk) -> {
            if (!market.hasPosition()) {
                desk.buyLimit(market.close() - 5, 2);
            } else {
                desk.sellToCoverLimit(market.myPrice() + 8, 1);
                desk.sellToCoverStop(market.myPrice() - 10, market.myPrice() - 20, 2);
            }
        };

        Result first = run(bars, strategy);
        Result again = run(bars, strategy);

        // Nada sobrevive entre rodadas. E' isso que deixa o desenho no grafico,
        // o replay e o relatorio concordarem sobre o que aconteceu -- e que vai
        // deixar a serie cortada em duas somar exatamente a serie inteira.
        assertEquals(first.fills(), again.fills(), "duas rodadas iguais deram execucoes diferentes");
        assertEquals(first.net(), again.net(), 0.0, "duas rodadas iguais deram resultados diferentes");
    }
}
