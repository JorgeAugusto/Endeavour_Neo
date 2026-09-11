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
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        TradeTableModel model = new TradeTableModel(new String[TradeTableModel.NET + 1]);
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

        TradeTableModel model = new TradeTableModel(new String[TradeTableModel.NET + 1]);
        model.show(result.trades(), new Waves(2_000));

        String opened = (String) model.getValueAt(0, TradeTableModel.OPENED);

        assertTrue(opened.contains("/"), "a coluna de entrada mostrou " + opened + ", nao uma data");
    }

    @Test
    @DisplayName("sem serie a linha cai para o numero da barra, e nao estoura")
    void withoutASeriesTheRowFallsBackToTheBarNumber() {
        Result result = run();

        TradeTableModel model = new TradeTableModel(new String[TradeTableModel.NET + 1]);
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
        return new Trade(0, 1, Side.BUY, 1, 1, net, 0, List.of());
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
        TradeTableModel model = new TradeTableModel(new String[TradeTableModel.NET + 1]);

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

    /** Todo texto de rotulo do quadro, sem olhar onde ele mora. */
    private static String allText(java.awt.Container root) {
        StringBuilder found = new StringBuilder();

        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JLabel label && label.getText() != null) {
                found.append(label.getText()).append('\n');
            }

            if (child instanceof java.awt.Container deeper) {
                found.append(allText(deeper));
            }
        }

        return found.toString();
    }

    @Test
    @DisplayName("UMA RODADA SEM OPERACAO DIZ ISSO, em vez de mostrar zeros")
    void arunWithNoTradeSaysSoInsteadOfShowingZeros() {
        br.com.jorge.reis.endeavourneo.platform.Settings.useForTest(settings);

        try {
            ResultPanel quadro = new ResultPanel();

            quadro.show(Result.empty(), br.com.jorge.reis.endeavourneo.domain.trading.Metrics
                    .of(Result.empty(), new Waves(2_000)), "WINFULL", "OHLC");

            String aviso = br.com.jorge.reis.endeavourneo.platform.Messages
                    .get("backtest.wrong.empty");

            // Um quadro de zeros parece uma estrategia que nao ganha nada. E
            // outra coisa quando ela nem rodou -- a Range 90 num recorte de um
            // mes nao entra uma vez, porque o seletor dela nao tem historia
            // dentro daquele trecho.
            assertTrue(allText(quadro).contains(aviso),
                    "o quadro nao avisou que nao houve operacao nenhuma");

            Result algo = run();

            quadro.show(algo, br.com.jorge.reis.endeavourneo.domain.trading.Metrics
                    .of(algo, new Waves(2_000)), "WINFULL", "OHLC");

            assertFalse(allText(quadro).contains(aviso),
                    "o quadro avisou que nao houve operacao numa rodada que operou");
        } finally {
            br.com.jorge.reis.endeavourneo.platform.Settings.stopUsingTestStore();
        }
    }

    @Test
    @DisplayName("o quadro mostra o resultado em pontos E em reais, na linha certa")
    void theQuadroShowsPointsAndReaisOnTheRightRow() {
        br.com.jorge.reis.endeavourneo.platform.Settings.useForTest(settings);

        try {
            Result result = run();
            ResultPanel quadro = new ResultPanel();

            quadro.show(result, br.com.jorge.reis.endeavourneo.domain.trading.Metrics
                    .of(result, new Waves(2_000)), "WINFULL", "ticks sinteticos");

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

    // ------------------------------------------------------------- o quadro

    /** @return o primeiro componente da arvore que for da classe pedida */
    private static <T> T find(java.awt.Container root, Class<T> what,
                              java.util.function.Predicate<T> matching) {
        for (java.awt.Component child : root.getComponents()) {
            if (what.isInstance(child) && matching.test(what.cast(child))) {
                return what.cast(child);
            }

            if (child instanceof java.awt.Container deeper) {
                T found = find(deeper, what, matching);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /**
     * Monta o painel numa janela de verdade, para o divisor ter largura.
     *
     * <p><b>A janela TEM de ser descartada</b>, e por isso ela e entregue junto:
     * o painel carrega um {@code ChartCanvas}, e um canvas numa arvore
     * mostravel entra num contador ESTATICO de quantos estao na tela. Deixada
     * de pe, ela some da vista e continua contando -- e quem paga e outro
     * arquivo de teste, que conta dois e encontra quatro. Foi exatamente assim
     * que este vazamento apareceu.</p>
     */
    private static javax.swing.JFrame laidOut() throws Exception {
        java.util.concurrent.atomic.AtomicReference<javax.swing.JFrame> made =
                new java.util.concurrent.atomic.AtomicReference<>();

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            javax.swing.JFrame frame = new javax.swing.JFrame();

            frame.setContentPane(new BacktestPanel());
            frame.setSize(1_200, 700);
            frame.pack();
            frame.setSize(1_200, 700);
            frame.doLayout();
            frame.validate();

            made.set(frame);
        });

        javax.swing.SwingUtilities.invokeAndWait(() -> { });

        return made.get();
    }

    private static void letGo(javax.swing.JFrame frame) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(frame::dispose);
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
    }

    private static javax.swing.JScrollPane quadroOf(javax.swing.JFrame frame) {
        return find(frame, javax.swing.JScrollPane.class,
                scroll -> scroll.getViewport().getView() instanceof ResultPanel);
    }

    private static javax.swing.JToggleButton switchOf(javax.swing.JFrame frame) {
        return find(frame, javax.swing.JToggleButton.class, button -> true);
    }

    @Test
    @DisplayName("o quadro nasce visivel, e nao tomando a janela inteira")
    void theQuadroIsBornVisibleAndNotOverTheWholeWindow() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no graphics environment");

        br.com.jorge.reis.endeavourneo.platform.Settings.useForTest(settings);

        javax.swing.JFrame frame = laidOut();

        try {
            javax.swing.JScrollPane quadro = quadroOf(frame);

            assertNotNull(quadro, "o quadro nao esta na arvore");
            assertTrue(quadro.isVisible(), "o quadro nasceu escondido");

            javax.swing.JSplitPane sides = (javax.swing.JSplitPane) quadro.getParent();

            assertTrue(sides.getDividerLocation() > 300,
                    "o quadro nasceu sobre a janela inteira: divisor em "
                            + sides.getDividerLocation() + " de " + sides.getWidth());
        } finally {
            letGo(frame);
            br.com.jorge.reis.endeavourneo.platform.Settings.stopUsingTestStore();
        }
    }

    @Test
    @DisplayName("uma montagem SEM largura nao gasta a unica chance de colocar o divisor")
    void alayoutWithNoWidthDoesNotSpendTheOneChanceToPlaceTheDivider() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no graphics environment");

        br.com.jorge.reis.endeavourneo.platform.Settings.useForTest(settings);

        javax.swing.JFrame frame = new javax.swing.JFrame();

        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                frame.setContentPane(new BacktestPanel());

                // pack() PRIMEIRO: um frame que nunca foi mostravel nao tem
                // layout nenhum, e entao nem o passo de largura zero acontece
                // -- o teste passaria a nao testar nada.
                frame.pack();

                // SEM LARGURA PRIMEIRO. E o que acontece quando a janela abre
                // dentro de um divisor recolhido, ou antes de ter sido
                // dimensionada: o evento de redimensionamento chega com zero.
                // Marcar "ja coloquei" ali gasta a unica chance, o divisor
                // nunca e posto, e o quadro abre sobre a janela inteira.
                frame.setSize(0, 0);
                frame.doLayout();
                frame.validate();
            });

            javax.swing.SwingUtilities.invokeAndWait(() -> {
                frame.setSize(1_200, 700);
                frame.doLayout();
                frame.validate();
            });

            javax.swing.SwingUtilities.invokeAndWait(() -> { });

            javax.swing.JScrollPane quadro = quadroOf(frame);
            javax.swing.JSplitPane sides = (javax.swing.JSplitPane) quadro.getParent();

            assertTrue(sides.getDividerLocation() > 300,
                    "o quadro tomou a janela inteira: divisor em "
                            + sides.getDividerLocation() + " de " + sides.getWidth());
        } finally {
            letGo(frame);
            br.com.jorge.reis.endeavourneo.platform.Settings.stopUsingTestStore();
        }
    }

    @Test
    @DisplayName("uma largura absurda nao e guardada, e a janela seguinte nasce inteira")
    void anabsurdWidthIsNotKeptSoTheNextWindowIsBornWhole() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no graphics environment");

        br.com.jorge.reis.endeavourneo.platform.Settings.useForTest(settings);

        javax.swing.JFrame primeira = laidOut();

        try {
            javax.swing.JSplitPane sides =
                    (javax.swing.JSplitPane) quadroOf(primeira).getParent();

            // O DIVISOR ARRASTADO ATE O FIM. E o gesto que gravava "o quadro
            // quer a janela inteira" -- e a janela SEGUINTE nascia assim, que e
            // o defeito que ele viu. Gravar so o que cabe entre os dois minimos
            // e o que impede a preferencia de virar uma tela quebrada.
            javax.swing.SwingUtilities.invokeAndWait(() -> sides.setDividerLocation(0));
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        } finally {
            letGo(primeira);
        }

        javax.swing.JFrame segunda = laidOut();

        try {
            javax.swing.JSplitPane sides =
                    (javax.swing.JSplitPane) quadroOf(segunda).getParent();

            assertTrue(sides.getDividerLocation() > 300,
                    "a janela seguinte nasceu com o quadro sobre tudo: divisor em "
                            + sides.getDividerLocation() + " de " + sides.getWidth());
        } finally {
            letGo(segunda);
            br.com.jorge.reis.endeavourneo.platform.Settings.stopUsingTestStore();
        }
    }

    @Test
    @DisplayName("largura absurda no arquivo ainda abre uma janela inteira")
    void anabsurdWidthInTheFileStillOpensASaneWindow() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no graphics environment");

        br.com.jorge.reis.endeavourneo.platform.Settings.useForTest(settings);

        // ESCRITO DIRETO NO ARQUIVO, porque e por ai que um numero assim entra:
        // uma versao anterior que gravava errado, ou alguem que abriu o
        // workspace. O que segura a tela nao e o que se recusa a gravar -- e o
        // que se recusa a obedecer na hora de abrir.
        br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
                .putInt("backtest.quadro.width", 5_000);

        javax.swing.JFrame frame = laidOut();

        try {
            javax.swing.JSplitPane sides = (javax.swing.JSplitPane) quadroOf(frame).getParent();

            assertTrue(sides.getDividerLocation() >= 320,
                    "cinco mil pixels de quadro nao sobraram grafico: divisor em "
                            + sides.getDividerLocation() + " de " + sides.getWidth());
        } finally {
            letGo(frame);
        }

        // E O OUTRO EXTREMO: cinco pixels de quadro sao uma coluna de numeros
        // cortados, nao um quadro. O teto sozinho nao segura este lado.
        br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
                .putInt("backtest.quadro.width", 5);

        javax.swing.JFrame estreita = laidOut();

        try {
            javax.swing.JSplitPane sides = (javax.swing.JSplitPane) quadroOf(estreita).getParent();
            int quadro = sides.getWidth() - sides.getDividerLocation();

            assertTrue(quadro >= 220,
                    "o quadro abriu com " + quadro + " pixels, que nao da para ler");
        } finally {
            letGo(estreita);
            br.com.jorge.reis.endeavourneo.platform.Settings.stopUsingTestStore();
        }
    }

    @Test
    @DisplayName("o recorte escolhido volta com a janela")
    void thechosenRangeComesBackWithTheWindow() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no graphics environment");

        br.com.jorge.reis.endeavourneo.platform.Settings.useForTest(settings);
        br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
                .put("backtest.pick.slice",
                        br.com.jorge.reis.endeavourneo.domain.market.Slice.WEEK.name());

        javax.swing.JFrame frame = laidOut();

        try {
            javax.swing.JComboBox<?> combo = find(frame, javax.swing.JComboBox.class,
                    box -> box.getItemCount() > 0
                            && box.getItemAt(0)
                                    instanceof br.com.jorge.reis.endeavourneo.domain.market.Slice);

            assertNotNull(combo, "a barra nao tem o seletor de recorte");

            // A ORDEM NO CONSTRUTOR. O padrao era posto DEPOIS de ler a
            // preferencia, entao era o padrao que sobrescrevia a escolha -- e o
            // recorte nunca voltava. Um ajuste que so e lido ao reabrir a janela
            // falha calado e parece que funciona.
            assertEquals(br.com.jorge.reis.endeavourneo.domain.market.Slice.WEEK,
                    combo.getSelectedItem(), "o recorte escolhido nao voltou");
        } finally {
            letGo(frame);
            br.com.jorge.reis.endeavourneo.platform.Settings.stopUsingTestStore();
        }
    }

    @Test
    @DisplayName("recolher esconde o quadro; voltar o traz do tamanho que tinha")
    void collapsingHidesItAndBringingItBackKeepsItsWidth() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no graphics environment");

        br.com.jorge.reis.endeavourneo.platform.Settings.useForTest(settings);

        javax.swing.JFrame frame = laidOut();

        try {
            javax.swing.JScrollPane quadro = quadroOf(frame);
            javax.swing.JSplitPane sides = (javax.swing.JSplitPane) quadro.getParent();
            javax.swing.JToggleButton toggle = switchOf(frame);

            assertNotNull(toggle, "nao ha botao para recolher o quadro");

            int before = sides.getWidth() - sides.getDividerLocation();

            javax.swing.SwingUtilities.invokeAndWait(toggle::doClick);

            assertTrue(!quadro.isVisible(), "recolher nao escondeu o quadro");

            javax.swing.SwingUtilities.invokeAndWait(toggle::doClick);
            javax.swing.SwingUtilities.invokeAndWait(() -> { });

            assertTrue(quadro.isVisible(), "voltar nao trouxe o quadro");

            int after = sides.getWidth() - sides.getDividerLocation();

            // O DEFEITO QUE ELE VIU: com setOneTouchExpandable, a seta que traz
            // o quadro de volta o leva ao EXTREMO -- a janela inteira. Esconder
            // e restaurar nao e o mesmo gesto que arrastar o divisor ate o fim,
            // e o controle pronto nao sabe distinguir os dois.
            assertTrue(after < sides.getWidth() / 2,
                    "o quadro voltou tomando " + after + " de " + sides.getWidth());
            assertTrue(Math.abs(after - before) <= 40,
                    "o quadro voltou com " + after + " onde tinha " + before);
        } finally {
            letGo(frame);
            br.com.jorge.reis.endeavourneo.platform.Settings.stopUsingTestStore();
        }
    }

    // -------------------------------------------- as curvas da estrategia

    private static StrategyCurves curvesOf(double[] fast, double[] slow) {
        StrategyCurves curves = new StrategyCurves();
        java.util.Map<String, double[]> lines = new java.util.LinkedHashMap<>();

        lines.put("EMA 3", fast);
        lines.put("EMA 8", slow);

        curves.show(lines);

        return curves;
    }

    @Test
    @DisplayName("a sobreposicao entrega, por barra, o valor que a estrategia gravou")
    void theOverlayHandsBackWhatTheStrategyRecorded() {
        StrategyCurves curves = curvesOf(
                new double[] {10, 11, 12}, new double[] {20, 21, 22});

        assertEquals(2, curves.colours().size(), "nao pediu uma linha por curva");

        assertEquals(10, curves.valueAt(0)[0], 0.0, "a rapida na barra 0 nao e a que foi gravada");
        assertEquals(22, curves.valueAt(2)[1], 0.0, "a lenta na barra 2 nao e a que foi gravada");
    }

    @Test
    @DisplayName("barra fora da curva e NaN, que e como a linha se interrompe")
    void abarOutsideTheCurveIsNaN() {
        StrategyCurves curves = curvesOf(new double[] {10}, new double[] {20});

        // NaN quebra a polilinha, e e assim que se diz "aqui a media ainda nao
        // existia". Zero seria um preco, e a linha desceria ate o chao.
        assertTrue(Double.isNaN(curves.valueAt(5)[0]), "barra alem do fim devolveu um numero");
        assertTrue(Double.isNaN(curves.valueAt(-1)[0]), "barra negativa devolveu um numero");
    }

    @Test
    @DisplayName("a legenda diz QUAIS linhas sao, nao que ha linhas")
    void thelegendSaysWhichLinesTheseAre() {
        StrategyCurves curves = curvesOf(new double[] {1}, new double[] {2});

        assertTrue(curves.label().contains("EMA 3") && curves.label().contains("EMA 8"),
                "a legenda nao nomeia as medias: " + curves.label());
    }

    @Test
    @DisplayName("sem rodada, a sobreposicao nao desenha nada")
    void withNoRunTheOverlayDrawsNothing() {
        StrategyCurves curves = new StrategyCurves();

        assertEquals(0, curves.colours().size(), "pediu linha sem ter curva");
        assertEquals(0, curves.valueAt(0).length, "devolveu valor sem ter curva");

        curves.show(null);

        assertEquals(0, curves.colours().size(), "um mapa nulo virou linha");
    }

    @Test
    @DisplayName("mais curvas que cores: as que sobram ficam de fora")
    void morecurvesThanColoursDropsTheExtras() {
        java.util.Map<String, double[]> many = new java.util.LinkedHashMap<>();

        for (int i = 0; i < 12; i++) {
            many.put("linha " + i, new double[] {i});
        }

        StrategyCurves curves = new StrategyCurves();
        curves.show(many);

        // Duas linhas da MESMA cor sao piores que uma linha faltando: o leitor
        // le um cruzamento que nao existe.
        assertTrue(curves.colours().size() <= 6,
                "desenhou " + curves.colours().size() + " linhas com seis cores");
        assertEquals(curves.colours().size(), curves.colours().stream().distinct().count(),
                "duas linhas dividem a mesma cor");
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
