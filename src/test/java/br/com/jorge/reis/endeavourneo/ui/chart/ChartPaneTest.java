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
package br.com.jorge.reis.endeavourneo.ui.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.RandomWalkSeries;
import br.com.jorge.reis.endeavourneo.platform.Settings;

import java.awt.Component;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O gráfico como componente: preço, estudos, legenda e o caminho de inserir.
 *
 * <p>Ele existe porque eram quatro campos e um método no meio do código de
 * <b>janela</b> — e por isso o backtest não conseguia ter um. O que estes testes
 * defendem é que a montagem é uma só, e que quem quiser um gráfico pede um.</p>
 */
@DisplayName("O grafico como componente")
class ChartPaneTest {

    @TempDir
    Path folder;

    @BeforeEach
    void useMyOwnSettings() {
        Settings.useForTest(folder);
    }

    @AfterEach
    void putThemBack() {
        Settings.stopUsingTestStore();
    }

    private static boolean holds(Component root, Class<?> wanted) {
        if (wanted.isInstance(root)) {
            return true;
        }

        if (root instanceof java.awt.Container deeper) {
            for (Component child : deeper.getComponents()) {
                if (holds(child, wanted)) {
                    return true;
                }
            }
        }

        return false;
    }

    @Test
    @DisplayName("O GRAFICO TRAZ A PILHA DE ESTUDOS, que e onde um estocastico cabe")
    void thechartCarriesTheStudyStack() {
        ChartCanvas canvas = new ChartCanvas();
        ChartPane pane = new ChartPane(canvas, "teste.grafico");

        // UM ESTUDO MORA NUM PAINEL PROPRIO debaixo do preco. Sem a pilha, um
        // indicador de escala propria -- um estocastico, que vai de zero a cem
        // qualquer que seja o indice -- simplesmente nao tem onde ser posto, e a
        // tela nao diz isso: o dialogo abre e nao oferece o lugar.
        assertNotNull(pane.studies(), "o grafico nao tem pilha de estudos");
        assertSame(canvas, pane.canvas(), "o grafico trocou o preco que recebeu");
        assertTrue(holds(pane, br.com.jorge.reis.endeavourneo.ui.chart.study.StudyStack.class),
                "a pilha de estudos nao esta dentro do componente");
        assertTrue(holds(pane, OverlayLegend.class), "a legenda nao esta dentro do componente");
    }

    @Test
    @DisplayName("a barra de layouts e construida uma vez, e so quando pedida")
    void thelayoutBarIsBuiltOnceAndOnlyWhenAsked() {
        ChartPane pane = new ChartPane(new ChartCanvas(), "teste.grafico");

        LayoutBar first = pane.layouts();

        assertNotNull(first, "nao construiu a barra de layouts");
        assertSame(first, pane.layouts(), "construiu a barra duas vezes");
    }

    @Test
    @DisplayName("dois graficos com chaves diferentes nao dividem layout")
    void twochartsWithDifferentKeysDoNotShareLayouts() {
        ChartPane one = new ChartPane(new ChartCanvas(), "teste.um");
        ChartPane other = new ChartPane(new ChartCanvas(), "teste.outro");

        // O backtest guarda os layouts DELE. Dividir a chave com uma janela de
        // grafico faria os indicadores de uma aparecerem na outra, o que e pior
        // que nao ter layout nenhum.
        assertEquals("teste.um", one.key(), "a chave do primeiro mudou");
        assertNotSame(one.studies(), other.studies(), "dois graficos dividiram a pilha");
        assertNotEquals(one.key(), other.key(), "as duas chaves sao a mesma");
    }

    private static void assertNotEquals(Object one, Object other, String message) {
        assertTrue(one == null ? other != null : !one.equals(other), message);
    }

    @Test
    @DisplayName("o estudo e recalculado contra a serie que o grafico mostra agora")
    void thestudyIsRecalculatedAgainstTheSeriesOnScreen() {
        ChartCanvas canvas = new ChartCanvas();
        ChartPane pane = new ChartPane(canvas, "teste.grafico");

        // UM ESPIAO e nao uma media de verdade: o que se prova aqui e que o
        // recalculo CHEGA no estudo, e um indicador real traria junto as
        // condicoes dele -- aquecimento, escala, e ter sido realizado na tela.
        java.util.List<Integer> contra =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());


        Overlay spy = new Overlay() {

            @Override
            public String nameKey() {
                return "overlay.movingAverage";
            }

            @Override
            public java.util.List<Integer> parameters() {
                return java.util.List.of(5);
            }

            @Override
            public java.util.List<java.awt.Color> colours() {
                return java.util.List.of(java.awt.Color.RED);
            }

            @Override
            public double[] valueAt(int bar) {
                return new double[] {Double.NaN};
            }

            @Override
            public void calculate(
                    br.com.jorge.reis.endeavourneo.domain.market.PriceSeries series) {

                contra.add(series == null ? -1 : series.size());
            }

            @Override
            public boolean isVisible() {
                return true;
            }

            @Override
            public void setVisible(boolean visible) {
                // Nada: o espiao esta sempre visivel.
            }

            @Override
            public boolean fitsOnPrice() {
                return false;
            }
        };

        pane.studies().show(spy);
        canvas.setSeries(new RandomWalkSeries(300, 100.0));

        contra.clear();
        pane.recalculate();

        // ESPERA A CONDICAO, e nao um sinal de uso unico. O recalculo nao
        // acontece na hora nem na linha da interface: e enviado para um executor
        // de fundo, e esperar a fila da interface esvaziar nao alcanca isso.
        //
        // Um CountDownLatch nao serve aqui: mostrar o estudo ja o calcula uma
        // vez, entao o latch chegava a zero antes do recalculo que interessa e a
        // espera passava direto.
        //
        // A primeira versao deste teste leu a lista vazia -- e ainda assim viu
        // [300] na mensagem de falha, porque o JUnit formata o valor observado
        // depois, quando o recalculo ja tinha acontecido. Duas leituras do
        // mesmo objeto em dois instantes, que e como uma corrida se parece
        // quando aparece num teste.
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

        while (contra.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();

                break;
            }
        }

        // Um estudo ainda com os valores da serie anterior desenharia uma forma
        // que nunca aconteceu -- num grafico cujos candles ja sao os novos.
        java.util.List<Integer> visto = java.util.List.copyOf(contra);

        assertEquals(java.util.List.of(300), visto,
                "o recalculo nao chegou no estudo com a serie que esta na tela: " + visto);
    }

    @Test
    @DisplayName("o preco ja sabe quem perguntar quando alguem pede um indicador")
    void thepriceKnowsWhoToAskWhenAnindicatorIsWanted() {
        ChartCanvas canvas = new ChartCanvas();

        // ANTES do painel, o proprio canvas so consegue oferecer o preco --
        // indicador de escala propria nao tem onde ir. Depois, quem responde e o
        // painel, que tem os dois.
        ChartPane pane = new ChartPane(canvas, "teste.grafico");

        assertNotNull(pane, "o painel nao foi construido");

        // O menu do botao direito e por onde o leitor pede: se o item nao
        // estiver la, nao ha caminho nenhum.
        javax.swing.JPopupMenu menu = canvas.buildContextMenu();

        assertTrue(menu.getComponentCount() >= 2,
                "o menu do grafico perdeu o item de inserir ou o de remover");
    }
}
