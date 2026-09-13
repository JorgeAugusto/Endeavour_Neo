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
package br.com.jorge.reis.endeavourneo.ui.chart.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.indicator.Pmo;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.BarTint;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;

import java.awt.Color;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O TNO no gráfico de preço: a cor da barra e a bolinha. */
@DisplayName("PMO nas barras")
class MomentumBarsTest {

    private record Bars(int count) implements PriceSeries {

        @Override
        public int size() {
            return count;
        }

        @Override
        public long timeAt(int index) {
            return index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return closeAt(index);
        }

        @Override
        public double highAt(int index) {
            return closeAt(index) + 30;
        }

        @Override
        public double lowAt(int index) {
            return closeAt(index) - 30;
        }

        @Override
        public double closeAt(int index) {
            return 100_000 + 600 * Math.sin(index / 23.0) + 200 * Math.sin(index / 7.0);
        }
    }

    // AS CORES DO NTSL, escritas aqui de novo e nao lidas do produto. A
    // convencao dos portes e manter as cores do original, para a conferencia
    // lado a lado funcionar -- e um teste que lesse a constante do produto
    // concordaria com ela mesmo depois de alguem troca-la.
    //
    // Cuidado ao conferir contra o .src: cor inteira crua no NTSL e' BGR; o
    // RGB(r,g,b) recebe na ordem normal.
    private static final Color LIMAO = new Color(0x00FF00);        // clLime

    private static final Color VERMELHO = new Color(0xFF0000);     // clRed

    private static final Color AZUL = new Color(0x0000FF);         // clBlue

    private static final Color AMARELO = new Color(0xFFFF00);      // clYellow

    private static final Color[] CIMA = {
        new Color(255, 128, 0), new Color(255, 0, 255), new Color(128, 0, 128)};

    private static final Color[] BAIXO = {
        new Color(0, 255, 255), new Color(0, 128, 128), new Color(0, 0, 128)};

    private static MomentumBars ready() {
        MomentumBars bars = new MomentumBars();

        bars.calculate(new Bars(900));

        return bars;
    }

    @Test
    @DisplayName("VAI NO PRECO, e nao desenha curva nenhuma")
    void itgoesOnThePriceAndDrawsNoCurve() {
        Overlay what = new MomentumBars();

        // A outra metade do porte, PriceMomentum, e o painel com as duas linhas.
        // Esta e a que o original pinta nas velas -- ela nao tem linha propria, e
        // dizer que tem seria pedir ao canvas uma curva no eixo do preco com
        // valores de um oscilador em torno de zero.
        assertTrue(what.fitsOnPrice(), "a metade das barras nao se ofereceu ao preco");
        assertEquals(0, what.valueAt(10).length, "desenhou linha onde nao ha linha");
        assertTrue(what.colours().isEmpty(), "trouxe cor de linha sem ter linha");
        assertTrue(what instanceof BarTint, "nao e' um BarTint, entao nao pinta barra nenhuma");
    }

    @Test
    @DisplayName("A PRIORIDADE E A DO ORIGINAL: divergencia sobre exaustao sobre cruzamento")
    void theprioritiesAreTheOriginals() {
        Bars series = new Bars(900);
        MomentumBars bars = ready();

        Pmo.Lines lines = Pmo.standard().over(series);
        int[] cruzou = Pmo.crossings(lines);
        int[] esticou = Pmo.exhaustion(lines.line(),
                Pmo.deviation(lines.line(), Pmo.DEVIATION));
        int[] divergiu = Pmo.divergences(series, lines.line(),
                br.com.jorge.reis.endeavourneo.domain.indicator.Pivots.WING);

        int comExaustao = 0;
        int comDivergencia = 0;

        for (int i = 0; i < series.size(); i++) {
            Color tint = bars.at(i);

            if (divergiu[i] != 0) {
                assertEquals(divergiu[i] > 0 ? AZUL : AMARELO, tint,
                        "a divergencia da barra " + i + " nao ficou com a cor dela");
                comDivergencia++;

                continue;
            }

            if (esticou[i] != 0) {
                // A EXAUSTAO SOBREPOE O CRUZAMENTO. Trocar a ordem nao quebra
                // nada que compile: as duas cores existem, a barra fica pintada,
                // e o que se perde e' a informacao mais rara.
                Color esperado = esticou[i] > 0
                        ? CIMA[esticou[i] - 1] : BAIXO[-esticou[i] - 1];

                assertEquals(esperado, tint,
                        "a exaustao da barra " + i + " nao ficou com a cor do degrau dela");
                comExaustao++;

                continue;
            }

            assertNull(tint, "a barra " + i + " foi pintada sem motivo nenhum");
        }

        assertTrue(comExaustao > 3, "exaustoes de menos para provar algo: " + comExaustao);
        assertTrue(comDivergencia > 0, "nenhuma divergencia na amostra");

        // O CRUZAMENTO SO APARECE COM AS OUTRAS FORA DO CAMINHO, e isso e um
        // fato do indicador, nao do teste: com MultExaust1 em 1,0 quase toda
        // barra esta esticada um desvio, entao a camada de baixo quase nunca
        // chega a ser vista. Vale no Profit igual. Para prova-la, desliga-se as
        // de cima.
        bars.setPaintsExhaustion(false);
        bars.setPaintsDivergence(false);

        int comCruzamento = 0;

        for (int i = 0; i < series.size(); i++) {
            Color tint = bars.at(i);

            if (cruzou[i] == 0) {
                assertNull(tint, "a barra " + i + " pintou sem cruzamento nenhum");

                continue;
            }

            assertEquals(cruzou[i] > 0 ? LIMAO : VERMELHO, tint,
                    "o cruzamento da barra " + i + " nao ficou com a cor dele");
            comCruzamento++;
        }

        assertTrue(comCruzamento > 3, "cruzamentos de menos para provar algo: " + comCruzamento);
    }

    @Test
    @DisplayName("AS TRES CAMADAS DESLIGAM UMA DE CADA VEZ, sem recalcular")
    void thethreeLayersSwitchOffOneAtAtime() {
        Bars series = new Bars(900);
        MomentumBars bars = ready();

        int pintadas = 0;

        for (int i = 0; i < series.size(); i++) {
            if (bars.at(i) != null) {
                pintadas++;
            }
        }

        assertTrue(pintadas > 20, "quase nada foi pintado: " + pintadas);

        // OS INTERRUPTORES SAO LIDOS NA HORA DE PINTAR, nao no calculo: desligar
        // uma camada tem de repintar o grafico que ja esta aberto, em vez de
        // esperar a proxima serie ser carregada.
        bars.setPaintsDivergence(false);
        bars.setPaintsExhaustion(false);

        int soCruzamento = 0;

        for (int i = 0; i < series.size(); i++) {
            if (bars.at(i) != null) {
                soCruzamento++;
            }
        }

        assertTrue(soCruzamento < pintadas,
                "desligar duas camadas nao pintou menos barra: " + soCruzamento);

        bars.setPaintsBars(false);

        for (int i = 0; i < series.size(); i++) {
            assertNull(bars.at(i), "a barra " + i + " continuou pintada com tudo desligado");
        }
    }

    @Test
    @DisplayName("A VIRADA CHEGA ANTES DO CRUZAMENTO, e por isso acontece mais")
    void theturnComesBeforeTheCrossingAndTherefore() {
        Bars series = new Bars(900);

        MomentumBars cruzando = new MomentumBars();
        MomentumBars virando = new MomentumBars();

        virando.setUsesTurns(true);

        cruzando.calculate(series);
        virando.calculate(series);

        // O UsarInclinacao do original: o alerta em vez da confirmacao. Uma linha
        // que VIRA na direcao do sinal ainda nao o alcancou, entao a virada e
        // sempre mais cedo -- e, pela mesma razao, mais vezes e mais errada.
        int cruzou = 0;
        int virou = 0;

        // So o cruzamento, com as outras camadas fora do caminho.
        cruzando.setPaintsExhaustion(false);
        cruzando.setPaintsDivergence(false);
        virando.setPaintsExhaustion(false);
        virando.setPaintsDivergence(false);

        for (int i = 0; i < series.size(); i++) {
            if (cruzando.at(i) != null) {
                cruzou++;
            }

            if (virando.at(i) != null) {
                virou++;
            }
        }

        assertTrue(virou > cruzou,
                "a virada nao aconteceu mais que o cruzamento: " + virou + " contra " + cruzou);
    }

    @Test
    @DisplayName("A APARENCIA VAI E VOLTA, e uma curta nao apaga o resto")
    void theappearanceSurvivesTheRoundTrip() {
        MomentumBars bars = new MomentumBars(3, 21, 13, 8);

        bars.setPaintsExhaustion(false);
        bars.setShowsDots(false);
        bars.setDotSize(11);
        bars.setUsesTurns(true);
        bars.setWing(4);

        MomentumBars outro = new MomentumBars(3, 21, 13, 8);

        outro.applyAppearance(bars.appearance());

        assertEquals(bars.appearance(), outro.appearance(), "a aparencia nao voltou igual");
        assertFalse(outro.paintsExhaustion(), "a exaustao voltou ligada");
        assertTrue(outro.usesTurns(), "a virada voltou desligada");
        assertEquals(4, outro.wing(), "a asa do pivo nao voltou");

        // E os periodos viajam SEPARADOS da aparencia -- eles sao o que o
        // indicador e, e a aparencia e como ele se pinta.
        assertEquals(java.util.List.of(3, 21, 13, 8), bars.parameters(),
                "os periodos nao sao os quatro que foram pedidos");

        MomentumBars curto = new MomentumBars();

        curto.applyAppearance("false");

        assertFalse(curto.paintsBars(), "o unico campo que veio no texto nao valeu");
        assertTrue(curto.paintsDivergence(),
                "a divergencia morreu num texto que nao falava dela");
    }

    @Test
    @DisplayName("UMA SERIE VAZIA nao explode")
    void nothingIsStillSomething() {
        MomentumBars bars = new MomentumBars();

        bars.calculate(new Bars(0));

        assertNull(bars.at(0), "uma serie vazia pintou barra");

        bars.calculate(null);

        assertNull(bars.at(-1), "a barra -1 foi pintada");
    }
}
