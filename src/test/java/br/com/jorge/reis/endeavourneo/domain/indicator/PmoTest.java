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
package br.com.jorge.reis.endeavourneo.domain.indicator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O porte do {@code JorgeReis_TNO_PMO.src}.
 *
 * <p>A convenção dos portes é conferir contra a conta feita <b>à mão, fora da
 * classe</b>, e dizer a diferença numérica. É o primeiro teste daqui, e ele
 * refaz a cadeia inteira com laços explícitos em vez de chamar
 * {@link Ema}.</p>
 */
@DisplayName("PMO portado do Profit")
class PmoTest {

    /** Um pregão com onda: sobe, cai e volta, para o oscilador ter o que cruzar. */
    private record Bars(double[] price) implements PriceSeries {

        private static Bars wave(int size) {
            double[] made = new double[size];

            for (int i = 0; i < size; i++) {
                made[i] = 100_000
                        + 600 * Math.sin(i / 23.0)
                        + 200 * Math.sin(i / 7.0)
                        + (i % 2 == 0 ? 25 : -25);
            }

            return new Bars(made);
        }

        @Override
        public int size() {
            return price.length;
        }

        @Override
        public long timeAt(int index) {
            return index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return price[index];
        }

        @Override
        public double highAt(int index) {
            return price[index] + 30;
        }

        @Override
        public double lowAt(int index) {
            return price[index] - 30;
        }

        @Override
        public double closeAt(int index) {
            return price[index];
        }
    }

    /** A EMA do NTSL refeita aqui: semente pela média da primeira janela. */
    private static double[] emaPorFora(double[] values, int period) {
        double[] made = new double[values.length];
        double seed = 0;
        int counted = 0;

        Arrays.fill(made, Double.NaN);

        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(values[i])) {
                continue;
            }

            counted++;

            if (counted < period) {
                seed += values[i];
            } else if (counted == period) {
                made[i] = (seed + values[i]) / period;
            } else {
                double a = 2.0 / (period + 1.0);

                made[i] = a * values[i] + (1 - a) * made[i - 1];
            }
        }

        return made;
    }

    @Test
    @DisplayName("A CADEIA BATE COM A CONTA A MAO: roc, dupla suavizacao e sinal")
    void thechainAgreesWithTheHandComputation() {
        Bars bars = Bars.wave(400);
        Pmo what = Pmo.standard();
        Pmo.Lines lines = what.over(bars);

        // A conta por fora, com lacos explicitos: ROC em porcento vezes a
        // escala, depois Length1, depois Length2, depois o sinal.
        double[] roc = new double[bars.size()];

        Arrays.fill(roc, Double.NaN);

        for (int i = Pmo.CHANGE; i < bars.size(); i++) {
            double before = bars.closeAt(i - Pmo.CHANGE);

            roc[i] = (bars.closeAt(i) - before) / before * 100 * Pmo.SCALE;
        }

        double[] line = emaPorFora(emaPorFora(roc, Pmo.FIRST), Pmo.SECOND);
        double[] signal = emaPorFora(line, Pmo.SIGNAL);

        double pior = 0;
        int conferidos = 0;

        for (int i = 0; i < bars.size(); i++) {
            assertEquals(Double.isNaN(line[i]), Double.isNaN(lines.line()[i]),
                    "o aquecimento da linha diverge na barra " + i);

            if (!Double.isNaN(line[i])) {
                pior = Math.max(pior, Math.abs(line[i] - lines.line()[i]));
                conferidos++;
            }

            // O SINAL AQUECE DEPOIS DA LINHA -- ele e uma media DELA -- entao
            // as duas janelas sao diferentes e comparar as duas sob a mesma
            // guarda subtrai NaN de NaN e o maximo vira NaN.
            if (!Double.isNaN(signal[i])) {
                pior = Math.max(pior, Math.abs(signal[i] - lines.signal()[i]));
            }
        }

        assertTrue(conferidos > 300, "conferiu so " + conferidos + " barras");

        // A diferenca reportada, como manda a convencao dos portes. Zero seria
        // suspeito de a conta ser a mesma copiada; 1e-9 e aritmetica de ponto
        // flutuante em ordem diferente.
        assertTrue(pior < 1e-9,
                "a maior diferenca contra a conta a mao foi " + pior);

        System.out.println("PMO: maior diferenca contra a conta a mao = " + pior
                + " em " + conferidos + " barras");
    }

    @Test
    @DisplayName("A ESCALA NAO MOVE O CRUZAMENTO, so a altura do desenho")
    void thescaleDoesNotMoveTheCrossings() {
        Bars bars = Bars.wave(400);

        Pmo.Lines dez = Pmo.standard().over(bars);
        Pmo.Lines um = new Pmo(Pmo.CHANGE, Pmo.FIRST, Pmo.SECOND, Pmo.SIGNAL, 1).over(bars);

        // EscalaPMO MULTIPLICA o ROC, e a cadeia inteira e linear a partir
        // dali -- entao a linha de escala dez tem de ser exatamente dez vezes a
        // de escala um, barra a barra. E' aqui que este teste morde: aplicada
        // duas vezes, ou esquecida, ou somada, a proporcao deixa de ser dez.
        double maior = 0;
        int conferidos = 0;

        for (int i = 0; i < bars.size(); i++) {
            if (Double.isNaN(um.line()[i])) {
                continue;
            }

            maior = Math.max(maior, Math.abs(dez.line()[i] - Pmo.SCALE * um.line()[i]));
            conferidos++;
        }

        assertTrue(conferidos > 300, "conferiu so " + conferidos + " barras");
        assertTrue(maior < 1e-9,
                "a linha de escala dez nao e dez vezes a de escala um: erro " + maior);

        // E POR ISSO o cruzamento nao se move: as duas linhas crescem juntas.
        // A escala e cosmetica -- muda a altura do desenho, nunca onde o sinal
        // acontece. Se um dia mexer, deixou de ser cosmetica e virou parametro
        // do sinal, que e' outra coisa.
        assertArrayEquals(Pmo.crossings(dez), Pmo.crossings(um),
                "mudar a escala mexeu em quais barras cruzam");

        int cruzou = 0;

        for (int each : Pmo.crossings(dez)) {
            if (each != 0) {
                cruzou++;
            }
        }

        assertTrue(cruzou > 5, "a onda nao cruzou o bastante para provar nada: " + cruzou);
    }

    @Test
    @DisplayName("O AQUECIMENTO E NaN, e nunca zero -- o zero e o nivel que se le")
    void thewarmUpIsNaNandNeverZero() {
        Bars bars = Bars.wave(400);
        Pmo.Lines lines = Pmo.standard().over(bars);

        // Este oscilador e LIDO contra o zero: acima e' momento comprado, abaixo
        // vendido. Um aquecimento escrito como zero e' um aquecimento sentado
        // exatamente em cima da linha que decide o lado -- e o original faz isso,
        // porque o guarda dele escreve zero quando nao ha fechamento anterior.
        assertTrue(Double.isNaN(lines.line()[0]), "a barra zero ja tinha linha");

        int primeira = -1;

        for (int i = 0; i < bars.size(); i++) {
            if (!Double.isNaN(lines.line()[i])) {
                primeira = i;

                break;
            }
        }

        assertTrue(primeira >= Pmo.CHANGE + Pmo.FIRST + Pmo.SECOND - 2,
                "a linha nasceu cedo demais, na barra " + primeira);

        for (int i = 0; i < primeira; i++) {
            assertFalse(lines.line()[i] == 0,
                    "a barra " + i + " do aquecimento veio ZERO em vez de NaN");
        }
    }

    @Test
    @DisplayName("A DIVERGENCIA E CAUSAL: cortada a serie, as marcas nao mudam")
    void thedivergenceIsCausal() {
        Bars bars = Bars.wave(900);
        double[] line = Pmo.standard().over(bars).line();
        int[] inteira = Pmo.divergences(bars, line, 5);

        int marcas = 0;

        for (int each : inteira) {
            if (each != 0) {
                marcas++;
            }
        }

        assertTrue(marcas > 2, "a onda nao produziu divergencia suficiente: " + marcas);

        // O ORIGINAL REPINTA: ele acha o pivo no centro de uma janela que alcanca
        // PeriodoPivo barras no FUTURO, entao a marca so existe depois e pode
        // sumir de novo ao vivo. Aqui a marca e' arquivada na barra que a
        // CONFIRMOU.
        //
        // CAUSAL QUER DIZER ISTO, e e' assim que se testa: toda marca da barra i
        // tem de continuar la quando a serie e' cortada EXATAMENTE em i. Cortar
        // num ponto qualquer nao serve -- eu tinha escrito assim, e a prova de
        // dentes mostrou que a fixture simplesmente nao tinha divergencia perto
        // do corte, entao o teste passava com a marca no lugar errado.
        for (int i = 0; i < bars.size(); i++) {
            if (inteira[i] == 0) {
                continue;
            }

            Bars ate = new Bars(Arrays.copyOf(bars.price(), i + 1));
            int[] so = Pmo.divergences(ate, Pmo.standard().over(ate).line(), 5);

            assertEquals(inteira[i], so[i],
                    "a marca da barra " + i + " precisou de barras FUTURAS para existir");
        }
    }

    @Test
    @DisplayName("A EXAUSTAO TEM TRES DEGRAUS por lado, e o mais fundo ganha")
    void theexhaustionHasThreeStepsPerSide() {
        double[] line = {1, 5, 15, 25, 35, -15, -25, -35};
        double[] desvio = {10, 10, 10, 10, 10, 10, 10, 10};

        int[] stretch = Pmo.exhaustion(line, desvio);

        assertArrayEquals(new int[] {0, 0, 1, 2, 3, -1, -2, -3}, stretch,
                "os degraus da exaustao nao sairam como o original pinta: "
                        + Arrays.toString(stretch));
    }

    @Test
    @DisplayName("O DESVIO DIVIDE POR n, como o resto do projeto")
    void thedeviationDividesByN() {
        double[] line = {2, 4, 4, 4, 5, 5, 7, 9};
        double[] desvio = Pmo.deviation(line, 8);

        // A populacao inteira: media 5, desvio 2. Por n-1 daria 2,138.
        assertEquals(2.0, desvio[7], 1e-12, "o desvio nao dividiu por n");
        assertTrue(Double.isNaN(desvio[6]), "houve desvio antes da janela fechar");
    }
}
