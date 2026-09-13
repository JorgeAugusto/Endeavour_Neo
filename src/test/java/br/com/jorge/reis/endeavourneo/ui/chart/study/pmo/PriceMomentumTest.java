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
package br.com.jorge.reis.endeavourneo.ui.chart.study.pmo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.indicator.Pmo;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;

import java.awt.Color;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O PMO no painel: o que o desenho pode quebrar sem ninguém perceber. */
@DisplayName("PMO no grafico")
class PriceMomentumTest {

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

    @Test
    @DisplayName("O PAINEL LE OS MESMOS NUMEROS DO DOMINIO, e nao uma segunda conta")
    void thepanelReadsTheSameNumbersAsTheDomain() {
        Bars bars = new Bars(400);
        PriceMomentum study = new PriceMomentum();

        study.calculate(bars);

        // Duas contas do mesmo indicador divergem, e a divergencia e invisivel:
        // o grafico diz que o momento virou e a rodada diz que nao, e as duas
        // telas parecem certas sozinhas. Por isso o estudo NAO tem aritmetica --
        // ele chama o dominio, e este teste e quem garante que continua assim.
        Pmo.Lines esperado = Pmo.standard().over(bars);
        int conferidos = 0;

        for (int i = 0; i < bars.size(); i++) {
            double[] linhas = study.valueAt(i);

            assertEquals(2, linhas.length, "o painel nao desenhou linha e sinal na barra " + i);

            assertEquals(Double.isNaN(esperado.line()[i]), Double.isNaN(linhas[0]),
                    "o aquecimento da linha diverge na barra " + i);

            if (!Double.isNaN(esperado.line()[i])) {
                assertEquals(esperado.line()[i], linhas[0], 0.0,
                        "a linha do painel diverge do dominio na barra " + i);
                conferidos++;
            }

            if (!Double.isNaN(esperado.signal()[i])) {
                assertEquals(esperado.signal()[i], linhas[1], 0.0,
                        "o sinal do painel diverge do dominio na barra " + i);
            }
        }

        assertTrue(conferidos > 300, "conferiu so " + conferidos + " barras");
    }

    @Test
    @DisplayName("OS QUATRO PERIODOS SOBREVIVEM ao ir e voltar do layout")
    void thefourPeriodsSurviveTheRoundTrip() {
        PriceMomentum study = new PriceMomentum(3, 21, 13, 8);

        study.setShowsSignal(false);
        study.setShowsBands(true);
        study.setShowsZero(false);
        study.setScale(1);
        study.setDeviationWindow(34);
        study.setDeviations(2.5);
        study.setColour(new Color(0x123456));
        study.setWidth(3f);
        study.setOwnPeriod("5m");

        // OS PERIODOS VIAJAM SEPARADOS do resto -- eles sao o que o indicador E,
        // e o resto e como ele e desenhado. E os quatro saem SEMPRE, mesmo com o
        // sinal escondido: uma lista que encolhia junto com as linhas levava o
        // periodo embora, e o indicador voltava com outro numero.
        assertEquals(java.util.List.of(3, 21, 13, 8), study.parameters(),
                "os quatro periodos nao sairam com o sinal escondido");

        PriceMomentum outro = new PriceMomentum(3, 21, 13, 8);

        outro.applyAppearance(study.appearance());

        assertEquals(study.appearance(), outro.appearance(),
                "a aparencia nao sobreviveu a ida e volta");
        assertFalse(outro.showsSignal(), "o sinal voltou ligado");
        assertTrue(outro.showsBands(), "as bandas voltaram desligadas");
        assertEquals(1.0, outro.scale(), 0.0, "a escala nao voltou");
        assertEquals(34, outro.deviationWindow(), "a janela do desvio nao voltou");
        assertEquals("5m", outro.ownPeriod(), "a escala propria nao voltou");
    }

    @Test
    @DisplayName("UMA APARENCIA DE OUTRA VERSAO nao apaga o que faz sentido")
    void anappearanceFromAnotherVersionDoesNotWipeWhatMakesSense() {
        PriceMomentum study = new PriceMomentum();
        String antes = study.appearance();

        // Curta: escrita por uma versao mais velha, que nao tinha os campos do
        // fim. O que veio tem de valer, e o resto tem de ficar como estava.
        study.applyAppearance("DASHED;FF0000;4.0");

        assertEquals(4f, study.width(), 0.0, "a largura que veio no texto nao valeu");
        assertEquals(new Color(0xFF0000), study.colour(), "a cor que veio nao valeu");
        assertTrue(study.showsZero(), "a linha do zero morreu num texto que nao falava dela");
        assertFalse(antes.equals(study.appearance()), "nada mudou, entao o texto foi ignorado");
    }

    @Test
    @DisplayName("A LINHA DO ZERO E DO INDICADOR, nao do painel")
    void thezeroLineBelongsToTheIndicator() {
        PriceMomentum study = new PriceMomentum();

        // Este oscilador e LIDO contra o zero, entao o zero e parte do que ele
        // significa -- nao uma grade que o painel desenha por conta propria.
        assertEquals(1, study.levels().size(), "o zero nao saiu como nivel");
        assertEquals(0.0, study.levels().get(0).at(), 0.0, "o nivel nao estava no zero");

        study.setShowsZero(false);

        assertTrue(study.levels().isEmpty(), "o zero continuou depois de desligado");
    }

    @Test
    @DisplayName("SEM ESCALA PROPRIA ele nao inventa limites verticais")
    void withoutAscaleOfItsOwnItInventsNoBounds() {
        // O estocastico e de zero a cem por construcao; o PMO nao tem faixa
        // nenhuma -- quanto ele oscila depende do papel, da escala e do minuto.
        // Fixar limites aqui seria achatar o desenho num numero inventado.
        assertEquals(null, new PriceMomentum().bounds(), "o PMO fixou uma faixa vertical");
    }

    @Test
    @DisplayName("AS LINHAS, AS CORES E OS TRACOS andam na mesma ordem")
    void thelinesTheColoursAndTheStrokesMarchTogether() {
        PriceMomentum study = new PriceMomentum();

        study.calculate(new Bars(400));

        // O painel casa a i-esima cor com a i-esima linha de valueAt. Uma banda
        // ligada que nao trouxesse cor deixaria a linha sem cor ou -- pior --
        // pintada com a cor da linha seguinte, e ninguem le isso num grafico.
        for (boolean signal : new boolean[] {true, false}) {
            for (boolean bands : new boolean[] {true, false}) {
                study.setShowsSignal(signal);
                study.setShowsBands(bands);

                int many = study.valueAt(399).length;

                assertEquals(many, study.colours().size(),
                        "cores nao batem com as linhas: sinal=" + signal + " bandas=" + bands);
                assertEquals(many, study.strokes().size(),
                        "tracos nao batem com as linhas: sinal=" + signal + " bandas=" + bands);
            }
        }
    }

    @Test
    @DisplayName("UMA SERIE VAZIA nao explode")
    void nothingIsStillSomething() {
        PriceMomentum study = new PriceMomentum();

        study.calculate(new Bars(0));

        for (double each : study.valueAt(0)) {
            assertTrue(Double.isNaN(each), "uma serie vazia produziu numero");
        }

        study.calculate(null);

        assertTrue(Double.isNaN(study.valueAt(-1)[0]), "a barra -1 produziu numero");
    }

    @Test
    @DisplayName("NAO CABE NO GRAFICO DE PRECO: e um oscilador em torno de zero")
    void itdoesNotFitOnThePriceChart() {
        Overlay study = new PriceMomentum();

        assertFalse(study.fitsOnPrice(),
                "o PMO se ofereceu para o grafico de preco, onde seria uma reta no chao");
    }
}
