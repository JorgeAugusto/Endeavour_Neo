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
package br.com.jorge.reis.endeavourneo.ui.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.trading.Backtest;
import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;
import br.com.jorge.reis.endeavourneo.domain.trading.Trade;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Side;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.MovingAverageCrossing;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A tabela de operações e a curva — o que a tela mostra depois de rodar.
 *
 * <p>Estes testes não abrem janela. O que eles defendem é a ponte: que a
 * operação que o motor produziu chega à linha da tabela com os números certos,
 * e que a curva soma o que as operações somam. Se essa ponte quebrar, a tela
 * continua bonita e mente.</p>
 */
@DisplayName("A tela de backtest")
class BacktestPanelTest {

    /** Ajustes deste teste, nunca os do leitor. */
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path settings;

    /** Sobe, desce, sobe — o bastante para o cruzamento operar algumas vezes. */
    private record Waves(int bars) implements PriceSeries {

        @Override
        public int size() {
            return bars;
        }

        @Override
        public long timeAt(int index) {
            return 1_600_000_000_000L + index * 300_000L;
        }

        @Override
        public double openAt(int index) {
            return index == 0 ? closeAt(0) : closeAt(index - 1);
        }

        @Override
        public double highAt(int index) {
            return Math.max(openAt(index), closeAt(index)) + 20;
        }

        @Override
        public double lowAt(int index) {
            return Math.min(openAt(index), closeAt(index)) - 20;
        }

        @Override
        public double closeAt(int index) {
            return 100_000 + 3_000 * Math.sin(index / 40.0) + 400 * Math.sin(index / 3.0);
        }
    }

    private static Result run() {
        return new Backtest(Costs.MEASURED, 1)
                .run(new Waves(2_000), new MovingAverageCrossing(17, 34, 1));
    }

    @Test
    @DisplayName("a operacao chega na linha com os numeros do motor")
    void theTradeReachesTheRowWithTheEnginesNumbers() {
        Result result = run();
        PriceSeries series = new Waves(2_000);

        assertTrue(result.count() > 3, "a serie de teste nao produziu operacoes suficientes");

        TradeTableModel model = new TradeTableModel(new String[11]);
        model.show(result.trades(), series);

        assertEquals(result.count(), model.getRowCount(), "a tabela perdeu operacoes");

        Trade first = result.trades().get(0);

        assertEquals(first.gross(), (Double) model.getValueAt(0, TradeTableModel.POINTS), 1e-9,
                "o bruto da linha nao e o bruto da operacao");
        assertEquals(first.net(), (Double) model.getValueAt(0, TradeTableModel.NET), 1e-9,
                "o liquido da linha nao e o liquido da operacao");
        assertEquals(first.contracts(), model.getValueAt(0, TradeTableModel.CONTRACTS),
                "a quantidade da linha nao e a da operacao");

        // O preco de entrada tem de ser um preco QUE ACONTECEU na barra de
        // entrada -- e a checagem que pega os dois lados trocados, que passaria
        // por qualquer comparacao com a propria operacao.
        double entrada = (Double) model.getValueAt(0, TradeTableModel.ENTRY);

        assertTrue(entrada >= series.lowAt(first.openedAt())
                        && entrada <= series.highAt(first.openedAt()),
                "o preco de entrada (" + entrada + ") nao aconteceu na barra " + first.openedAt());

        double saida = (Double) model.getValueAt(0, TradeTableModel.EXIT);

        assertTrue(saida >= series.lowAt(first.closedAt()) && saida <= series.highAt(first.closedAt()),
                "o preco de saida (" + saida + ") nao aconteceu na barra " + first.closedAt());
    }

    @Test
    @DisplayName("a data da linha e a da barra, nao o indice dela")
    void theRowShowsTheBarsDateNotItsIndex() {
        Result result = run();

        TradeTableModel model = new TradeTableModel(new String[11]);
        model.show(result.trades(), new Waves(2_000));

        String opened = (String) model.getValueAt(0, TradeTableModel.OPENED);

        assertTrue(opened.contains("/"), "a coluna de entrada mostrou " + opened + ", nao uma data");
    }

    @Test
    @DisplayName("sem serie a linha cai para o numero da barra, e nao estoura")
    void withoutASeriesTheRowFallsBackToTheBarNumber() {
        Result result = run();

        TradeTableModel model = new TradeTableModel(new String[11]);
        model.show(result.trades(), null);

        // Um modelo montado antes de a serie chegar acontece, e uma tabela que
        // estoura ao ser desenhada leva a janela junto.
        assertNotNull(model.getValueAt(0, TradeTableModel.OPENED), "estourou sem serie");
    }

    @Test
    @DisplayName("a curva termina no liquido total")
    void theCurveEndsAtTheTotalNet() {
        Result result = run();
        double[] curve = result.equity();

        assertEquals(result.count() + 1, curve.length, "a curva nao tem um ponto por operacao mais a origem");
        assertEquals(0, curve[0], 0.0, "a curva nao comeca do zero");
        assertEquals(result.net(), curve[curve.length - 1], 1e-9,
                "a curva termina num numero diferente do liquido");
    }

    /** @return uma operacao que rendeu {@code net} pontos, sem execucoes */
    private static Trade worth(double net) {
        return new Trade(0, 1, Side.BUY, 1, net, 0, List.of());
    }

    @Test
    @DisplayName("a queda maxima mede do PICO, nao do zero")
    void theDrawdownIsMeasuredFromThePeakNotFromZero() {
        // Curva: 0 -> 100 -> 70 -> 40. Ela NUNCA fica negativa, entao uma queda
        // medida a partir do zero daria zero -- e a estrategia que subiu 100 e
        // devolveu 60 pareceria nao ter tido queda nenhuma.
        Result result = new Result(List.of(worth(100), worth(-30), worth(-30)),
                List.of(), 0, Costs.NONE, 0, 0, new double[0], 0);

        assertEquals(40, result.net(), 1e-9, "a serie de teste nao soma o que devia");
        assertEquals(-60, result.drawdown(), 1e-9,
                "a queda maxima nao mediu a distancia ate o pico");
    }

    @Test
    @DisplayName("sem operacao nenhuma a queda maxima e zero")
    void withNoTradesTheDrawdownIsZero() {
        Result nothing = Result.empty();

        assertEquals(0, nothing.drawdown(), 0.0, "queda maxima inventada do nada");
        assertEquals(1, nothing.equity().length, "a curva vazia nao e so a origem");
    }

    @Test
    @DisplayName("linha fora da tabela devolve nulo em vez de estourar")
    void aRowOutsideTheTableGivesNullInsteadOfThrowing() {
        TradeTableModel model = new TradeTableModel(new String[11]);

        assertNull(model.at(0), "modelo vazio devolveu operacao");
        assertNull(model.at(-1), "indice negativo nao foi tratado");
    }

    /**
     * Casa cada rotulo do quadro com o valor ao lado dele.
     *
     * <p>Cada linha e um {@code JPanel} com o rotulo a oeste e o valor no
     * centro, entao o par se le da propria arvore -- e nao da ORDEM em que os
     * valores foram preenchidos. E' essa ordem a parte fragil: o painel enche
     * uma lista por indice, e um bloco que ganha uma linha desloca todos os
     * seguintes em silencio. Um teste que lesse a lista pela mesma ordem nao
     * veria o deslocamento; este ve.</p>
     */
    private static java.util.Map<String, String> readOut(java.awt.Container root) {
        java.util.Map<String, String> pairs = new java.util.LinkedHashMap<>();

        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JPanel panel
                    && panel.getLayout() instanceof java.awt.BorderLayout layout) {
                java.awt.Component west = layout.getLayoutComponent(java.awt.BorderLayout.WEST);
                java.awt.Component centre = layout.getLayoutComponent(java.awt.BorderLayout.CENTER);

                if (west instanceof javax.swing.JLabel caption
                        && centre instanceof javax.swing.JLabel value) {
                    pairs.put(caption.getText(), value.getText());
                }
            }

            if (child instanceof java.awt.Container deeper) {
                pairs.putAll(readOut(deeper));
            }
        }

        return pairs;
    }

    @Test
    @DisplayName("o quadro mostra o resultado em pontos E em reais, na linha certa")
    void theQuadroShowsPointsAndReaisOnTheRightRow() {
        br.com.jorge.reis.endeavourneo.platform.Settings.useForTest(settings);

        try {
            Result result = run();
            ResultPanel quadro = new ResultPanel();

            quadro.show(result, br.com.jorge.reis.endeavourneo.domain.trading.Metrics
                    .of(result, new Waves(2_000)), "WINFULL");

            java.util.Map<String, String> shown = readOut(quadro);

            String perTrade = shown.get(
                    br.com.jorge.reis.endeavourneo.platform.Messages.get("backtest.perTrade"));

            assertNotNull(perTrade, "o quadro nao tem linha de resultado por operacao: " + shown.keySet());
            assertTrue(perTrade.contains("R$"), "o por-operacao nao mostra reais: " + perTrade);
            assertTrue(perTrade.contains(String.format("%+,.0f", result.perTrade()))
                            || perTrade.startsWith(String.format("%+,.0f", result.perTrade())),
                    "o por-operacao nao mostra pontos: " + perTrade);

            // A CHECAGEM DO DESLOCAMENTO: acerto e porcentagem, e ponto vale e
            // dinheiro. Se os indices escorregarem, um cai na linha do outro.
            String hit = shown.get(
                    br.com.jorge.reis.endeavourneo.platform.Messages.get("backtest.hitRate"));

            assertTrue(hit != null && hit.endsWith("%"), "a linha de acerto mostra " + hit);

            String point = shown.get(
                    br.com.jorge.reis.endeavourneo.platform.Messages.get("backtest.pointValue"));

            assertTrue(point != null && point.startsWith("R$"), "a linha do valor do ponto mostra " + point);
        } finally {
            br.com.jorge.reis.endeavourneo.platform.Settings.stopUsingTestStore();
        }
    }

    @Test
    @DisplayName("as marcas aceitam a lista e a escolha sem serie carregada")
    void theMarksTakeTheListAndTheChoiceBeforeAnythingIsDrawn() {
        Result result = run();
        TradeMarks marks = new TradeMarks();

        marks.show(result.trades());
        marks.highlight(result.trades().get(0));

        // Nao desenha nada por barra: tudo que ela mostra sai do paintUnder.
        assertEquals(0, marks.colours().size(), "a sobreposicao pediu uma linha por barra");
        assertEquals(0, marks.valueAt(0).length, "a sobreposicao devolveu valor por barra");
        assertTrue(marks.fitsOnPrice(), "as marcas sairiam do painel de preco");
    }
}
