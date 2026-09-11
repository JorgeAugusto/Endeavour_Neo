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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Renko;
import br.com.jorge.reis.endeavourneo.ui.chart.PeriodCatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A lista de escalas do backtest é a do gráfico.
 *
 * <p>Ela já foi um array de cinco entradas escrito à mão nesta janela, contra as
 * quatorze do catálogo — e foi por isso que não havia como pôr uma rodada em
 * renko. Duas listas para a mesma pergunta divergem; a única defesa que funciona
 * é não haver a segunda.</p>
 */
@DisplayName("Escalas do backtest")
class BacktestScalesTest {

    /**
     * Barras de um minuto subindo cinco pontos por barra.
     *
     * <p>Noventa e sete delas, e o número importa: um tijolo de tamanho
     * <i>b</i> fecha a cada <i>b/5</i> barras, e 97 não é múltiplo de 2, 3, 4,
     * 5, 10 nem 20 — os passos dos seis tijolos oferecidos. Todo tamanho
     * termina com um tijolo <b>pela metade</b>, que é exatamente o que precisa
     * existir para se poder perguntar se ele foi desenhado.</p>
     */
    private record Escada(int quantas) implements PriceSeries {

        @Override
        public int size() {
            return quantas;
        }

        @Override
        public long timeAt(int index) {
            return index * 60_000L;
        }

        private double price(int index) {
            return 100_000 + index * 5.0;
        }

        @Override
        public double openAt(int index) {
            return price(index);
        }

        @Override
        public double highAt(int index) {
            return price(index);
        }

        @Override
        public double lowAt(int index) {
            return price(index);
        }

        @Override
        public double closeAt(int index) {
            return price(index);
        }
    }

    private static final PriceSeries ESCADA = new Escada(97);

    private static List<String> codesOf(BacktestPanel.Scale[] scales) {
        List<String> codes = new ArrayList<>();

        for (BacktestPanel.Scale each : scales) {
            codes.add(each.id());
        }

        return codes;
    }

    @Test
    @DisplayName("A LISTA OFERECE TUDO QUE O CATALOGO OFERECE, e nada por fora dele")
    void thelistOffersEverythingTheCatalogueOffersAndNothingBesides() {
        List<String> oferecidas = codesOf(BacktestPanel.scales());

        // "como esta" e a unica que o catalogo nao tem, e nao deveria ter: nao e
        // um periodo, e a ausencia de um. Um grafico sempre tem periodo; uma
        // rodada pode nao agregar nada.
        assertEquals("asStored", oferecidas.get(0),
                "a primeira entrada deixou de ser 'como esta'");

        Set<String> doCatalogo = new LinkedHashSet<>();

        for (PeriodCatalog.Choice each : PeriodCatalog.common()) {
            doCatalogo.add(each.code());
        }

        List<String> daJanela = oferecidas.subList(1, oferecidas.size());

        assertEquals(new LinkedHashSet<>(daJanela), doCatalogo,
                "a janela e o catalogo discordam sobre que escalas existem");

        // E na MESMA ORDEM: o catalogo poe os tempos antes dos tijolos porque e
        // assim que se procura, e uma janela que reordena e uma janela em que o
        // leitor procura no lugar errado.
        assertEquals(new ArrayList<>(doCatalogo), daJanela,
                "a ordem do catalogo nao foi respeitada");
    }

    @Test
    @DisplayName("NENHUM RENKO DA LISTA DESENHA O TIJOLO EM FORMACAO")
    void norenkoInThelistDrawsTheFormingBrick() {
        int quantos = 0;

        for (BacktestPanel.Scale each : BacktestPanel.scales()) {
            if (!each.isRenko()) {
                continue;
            }

            quantos++;

            double brick = ((Renko) each.how()).brick();

            int fechados = Renko.of(brick).apply(ESCADA).size();
            int comOparcial = Renko.of(brick).withForming(true).apply(ESCADA).size();

            // Sem isso o teste nao prova nada: se esta escada nao deixasse
            // tijolo pela metade neste tamanho, as duas contas seriam iguais e a
            // asercao de baixo passaria de qualquer jeito.
            assertEquals(fechados + 1, comOparcial,
                    "a escada nao deixa tijolo pela metade em " + each.id()
                            + ", entao este tamanho nao esta sendo testado");

            // O grafico DESENHA o tijolo pela metade -- senao a ponta viva para
            // de andar. Uma rodada nao pode nem ve-lo: e uma barra que ainda
            // pode mudar, e uma estrategia decidindo sobre ela decide sobre um
            // preco que o proximo negocio reescreve.
            assertEquals(fechados, each.how().apply(ESCADA).size(),
                    "a escala " + each.id() + " esta desenhando o tijolo em formacao");
        }

        assertTrue(quantos > 0, "a lista nao tem renko nenhum");
    }

    @Test
    @DisplayName("um periodo de fora da lista curta ainda vira escala")
    void aperiodFromOutsideTheShortListStillBecomesAscale() {
        // O que o botao "..." devolve: qualquer coisa que o catalogo saiba
        // construir, e nao so as quatorze listadas.
        PeriodCatalog.Choice digitado = PeriodCatalog.byCode("37R");

        assertNotNull(digitado, "o catalogo nao constroi 37R");
        assertFalse(codesOf(BacktestPanel.scales()).contains("37R"),
                "37R entrou na lista curta; este teste precisa de um codigo de fora dela");

        BacktestPanel.Scale feita = BacktestPanel.settled(digitado);

        assertEquals("37R", feita.id(), "o codigo nao sobreviveu, e e ele que a preferencia guarda");
        assertTrue(feita.isRenko(), "37R nao virou renko");

        double brick = ((Renko) feita.how()).brick();

        assertEquals(Renko.of(brick).apply(ESCADA).size(), feita.how().apply(ESCADA).size(),
                "o periodo digitado entrou desenhando o tijolo em formacao");
    }
}
