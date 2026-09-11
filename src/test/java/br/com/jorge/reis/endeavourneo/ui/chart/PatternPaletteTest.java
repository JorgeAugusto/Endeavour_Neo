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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.CandlePattern;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.platform.Settings;
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.Patterns;

import java.awt.Color;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Quais padrões são pintados, e em quê.
 *
 * <p>As cores aqui não são enfeite, são <b>legenda</b>: o leitor aprende que
 * ciano quer dizer fundo recusado. Por isso elas são do aplicativo e não da
 * instância do indicador — dois gráficos pintando o mesmo padrão de cores
 * diferentes estariam ensinando duas línguas ao mesmo tempo.</p>
 */
@DisplayName("A paleta dos padroes")
class PatternPaletteTest {

    /** Ajustes deste teste, nunca os do leitor. */
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

    @Test
    @DisplayName("de fabrica as tres formas aparecem, nas cores do indicador de origem")
    void outOfTheBoxAllThreeShowInTheOriginalColours() {
        // TODAS LIGADAS de saida: um indicador que e inserido e nao pinta nada
        // se le como quebrado, e o leitor nao tem como saber que interruptor
        // procurar.
        for (CandlePattern.Family family : CandlePattern.Family.values()) {
            assertEquals(family != CandlePattern.Family.NONE,
                    PatternPalette.shows(family), family + " nao comecou como devia");
        }

        // As cores do PaintBar de origem, para a primeira conferencia poder ser
        // feita com os dois graficos lado a lado.
        assertEquals(new Color(0x00FFFF), PatternPalette.colourOf(CandlePattern.PFR_BULLISH),
                "o PFR de alta nao nasce ciano");
        assertEquals(new Color(0xFF00FF), PatternPalette.colourOf(CandlePattern.PFR_BEARISH),
                "o PFR de baixa nao nasce magenta");
        assertEquals(new Color(0x0000FF), PatternPalette.colourOf(CandlePattern.INSIDE_BULLISH),
                "o inside de alta nao nasce azul");
        assertEquals(new Color(0xFFFF00), PatternPalette.colourOf(CandlePattern.INSIDE_BEARISH),
                "o inside de baixa nao nasce amarelo");
    }

    @Test
    @DisplayName("A COR ESCOLHIDA E A COR USADA, e sobrevive")
    void thecolourChosenIsThecolourUsed() {
        PatternPalette.setColour(CandlePattern.PFR_BULLISH, new Color(0x123456));

        assertEquals(new Color(0x123456), PatternPalette.colourOf(CandlePattern.PFR_BULLISH),
                "a cor escolhida voltou diferente");

        // E SO AQUELA. Uma tela de seis cores que mexesse em duas ao trocar uma
        // seria pior que uma sem tela nenhuma.
        assertEquals(new Color(0xFF00FF), PatternPalette.colourOf(CandlePattern.PFR_BEARISH),
                "trocar a cor de um padrao mexeu na de outro");

        // Guardada nos ajustes e nao na instancia do indicador: dois graficos
        // pintando o mesmo padrao de cores diferentes ensinariam duas linguas.
        assertEquals(0x123456,
                Settings.settings().getInt("chart.pattern.colour.PFR_BULLISH", -1),
                "a cor nao ficou guardada onde o proximo grafico a le");
    }

    @Test
    @DisplayName("DESLIGAR UMA FORMA DESLIGA OS DOIS LADOS DELA")
    void turningAshapeOffTurnsOffBothOfItsSides() {
        PatternPalette.setShows(CandlePattern.Family.INSIDE, false);

        // Alta e baixa sao uma forma vista de dois lados, nao duas formas: e por
        // isso que sao TRES interruptores e seis cores, e nao seis de cada.
        assertNull(PatternPalette.tintFor(CandlePattern.INSIDE_BULLISH),
                "o inside de alta continuou pintando com a forma desligada");
        assertNull(PatternPalette.tintFor(CandlePattern.INSIDE_BEARISH),
                "o inside de baixa continuou pintando com a forma desligada");

        // E SO AQUELA FORMA. Desligar o inside nao pode calar o PFR.
        assertNotNull(PatternPalette.tintFor(CandlePattern.PFR_BULLISH),
                "desligar o inside apagou tambem o PFR");
        assertFalse(PatternPalette.shows(CandlePattern.Family.INSIDE), "a forma nao ficou desligada");
    }

    @Test
    @DisplayName("barra sem padrao nao e tingida")
    void abarWithNopatternIsNotTinted() {
        // Null e nao uma cor de "sem tinta": a resposta depende de a barra ter
        // subido ou descido, e so o estilo sabe disso na hora em que pergunta.
        assertNull(PatternPalette.tintFor(CandlePattern.NONE), "o nenhum ganhou cor");
        assertNull(PatternPalette.tintFor(null), "um padrao nulo ganhou cor");
        assertEquals(BarTint.NONE.at(7), null, "a tinta vazia tingiu alguma coisa");
    }

    @Test
    @DisplayName("O INDICADOR TINGE A BARRA QUE FORMOU O PADRAO, e so ela")
    void theindicatorTintsTheBarThatFormedThepattern() {
        // Tres barras onde a terceira e um inside de alta, e nada mais.
        PriceSeries series = new PriceSeries() {

            private final double[][] ohlc = {
                {100, 110, 90, 105},
                {100, 120, 80, 110},
                {100, 115, 85, 108},
            };

            @Override
            public int size() {
                return ohlc.length;
            }

            @Override
            public long timeAt(int index) {
                return index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return ohlc[index][0];
            }

            @Override
            public double highAt(int index) {
                return ohlc[index][1];
            }

            @Override
            public double lowAt(int index) {
                return ohlc[index][2];
            }

            @Override
            public double closeAt(int index) {
                return ohlc[index][3];
            }
        };

        Patterns patterns = new Patterns();

        patterns.calculate(series);

        assertEquals(CandlePattern.INSIDE_BULLISH, patterns.patternAt(2),
                "o indicador nao reconheceu o inside");
        assertEquals(PatternPalette.colourOf(CandlePattern.INSIDE_BULLISH), patterns.at(2),
                "a barra do padrao nao saiu na cor dele");

        // As duas primeiras nao tem padrao -- precisam de duas anteriores -- e
        // por isso guardam a cor de sempre.
        assertNull(patterns.at(0), "a primeira barra foi tingida");
        assertNull(patterns.at(1), "a segunda barra foi tingida");
        assertNull(patterns.at(99), "uma barra fora da serie foi tingida");

        // E DESLIGAR REPINTA O QUE JA ESTA ABERTO: o interruptor e lido na hora
        // de pintar, nao guardado no calculo.
        PatternPalette.setShows(CandlePattern.Family.INSIDE, false);

        assertNull(patterns.at(2), "desligar a forma nao apagou a barra ja calculada");
    }

    @Test
    @DisplayName("o indicador nao desenha linha nenhuma, e diz isso")
    void theindicatorDrawsNolinesAndSaysSo() {
        Patterns patterns = new Patterns();

        // O contrato e uma cor por LINHA, na mesma ordem dos valores. As seis
        // cores dos padroes nao sao linhas -- sao o que as BARRAS levam -- entao
        // declara-las aqui prometeria seis polilinhas sem valores atras delas.
        assertTrue(patterns.colours().isEmpty(), "declarou cor de linha sem desenhar linha");
        assertEquals(0, patterns.valueAt(0).length, "declarou valor sem linha");
        assertTrue(patterns.parameters().isEmpty(), "tres barras viraram um parametro");
        assertTrue(patterns.fitsOnPrice(), "o indicador saiu do eixo do preco");

        // E A LEGENDA NAO MOSTRA COLCHETES VAZIOS. "Padroes []" e um par de
        // colchetes perguntando ao leitor o que falta dentro deles.
        assertFalse(patterns.label().contains("["),
                "a legenda mostrou colchetes para um indicador sem parametro: " + patterns.label());
    }
}
