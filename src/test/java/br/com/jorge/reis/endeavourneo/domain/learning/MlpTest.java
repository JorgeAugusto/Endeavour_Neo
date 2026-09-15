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
package br.com.jorge.reis.endeavourneo.domain.learning;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A rede do modelo de fade: o que prova uma retropropagação é a diferença finita. */
@DisplayName("Rede do fade")
class MlpTest {

    private static final int INPUTS = 4;

    private static final int SAMPLES = 8;

    /**
     * Um conjunto pequeno, com alvos DENTRO da região quadrática da Huber.
     *
     * <p>Dentro dela a perda é suave e a diferença finita significa alguma coisa.
     * Exatamente em {@code |erro| = 1} a Huber troca de ramo e a derivada tem um
     * canto — ali uma diferença central mediria a média dos dois lados e
     * acusaria um defeito que não existe.</p>
     */
    private static double[][] inputs(Random dice) {
        double[][] made = new double[SAMPLES][INPUTS];

        for (double[] row : made) {
            for (int i = 0; i < row.length; i++) {
                row[i] = dice.nextGaussian();
            }
        }

        return made;
    }

    private static double[][] targets(Random dice, double spread) {
        double[][] made = new double[SAMPLES][Mlp.ACTIONS];

        for (double[] row : made) {
            for (int i = 0; i < row.length; i++) {
                row[i] = dice.nextGaussian() * spread;
            }
        }

        return made;
    }

    /**
     * Compara o gradiente analítico com a diferença central da perda.
     *
     * <p>Este é o teste. Uma retropropagação não se prova lendo: um sinal
     * trocado, um índice deslocado ou uma derivada errada da ativação continuam
     * compilando, continuam treinando e continuam produzindo uma curva de perda
     * que desce — só que para o lugar errado. A diferença finita é a única
     * testemunha independente.</p>
     *
     * @param spread quão longe ficam os alvos, o que escolhe o ramo da Huber
     */
    private static void checkGradient(double spread, double nudge, double tolerance) {
        Random dice = new Random(7);
        Mlp net = new Mlp(INPUTS, 11);

        double[][] x = inputs(dice);
        double[][] y = targets(dice, spread);

        double[] analytic = new double[net.parameters().length];

        net.lossAndGradient(x, y, analytic);

        double[] start = net.parameters();
        int checked = 0;

        // UMA AMOSTRA DOS PARAMETROS, e nao todos: sao mais de cinco mil, cada um
        // custa duas passagens sobre o lote, e duzentos tirados ao acaso de todas
        // as camadas ja pegariam qualquer erro sistematico -- que e a forma que
        // um defeito de retropropagacao tem.
        for (int round = 0; round < 200; round++) {
            int at = dice.nextInt(start.length);

            double[] up = start.clone();
            double[] down = start.clone();

            up[at] += nudge;
            down[at] -= nudge;

            net.parameters(up);
            double above = net.lossAndGradient(x, y, new double[start.length]);

            net.parameters(down);
            double below = net.lossAndGradient(x, y, new double[start.length]);

            net.parameters(start);

            double numeric = (above - below) / (2 * nudge);
            double apart = Math.abs(numeric - analytic[at]);
            double scale = Math.max(1e-8, Math.abs(numeric) + Math.abs(analytic[at]));

            // RELATIVO **OU** ABSOLUTO, e nao so relativo. Uma componente do
            // gradiente que vale 1,9e-6 comparada por erro relativo cobra uma
            // precisao que a diferenca central nao tem: a perda vale quarenta,
            // e subtrair dois numeros dessa ordem para achar uma variacao de
            // 4e-10 gasta quase todos os digitos do double. Abaixo de 1e-9 de
            // erro ABSOLUTO nao ha o que distinguir de zero -- o piso e da
            // aritmetica, nao uma tolerancia afrouxada ate o teste passar.
            assertTrue(apart / scale < tolerance || apart < 1e-9, "no parametro " + at
                    + " o gradiente analitico deu " + analytic[at]
                    + " e a diferenca finita deu " + numeric);

            checked++;
        }

        assertTrue(checked == 200, "conferiu so " + checked + " parametros");
    }

    @Test
    @DisplayName("O GRADIENTE BATE COM A DIFERENÇA FINITA, no ramo quadrático")
    void thegradientMatchesAfiniteDifferenceNearZero() {
        checkGradient(0.1, 1e-6, 1e-5);
    }

    @Test
    @DisplayName("E TAMBÉM NO RAMO LINEAR da Huber, com os alvos longe")
    void thegradientMatchesAfiniteDifferenceFarOut() {
        checkGradient(40, 1e-4, 1e-5);
    }

    @Test
    @DisplayName("TREINAR BAIXA A PERDA, e muito")
    void trainingBringsTheLossDown() {
        Random dice = new Random(3);
        Mlp net = new Mlp(INPUTS, 141);

        double[][] x = inputs(dice);
        double[][] y = targets(dice, 0.5);

        double before = net.lossAndGradient(x, y, new double[net.parameters().length]);
        net.train(x, y, 180);
        double after = net.lossAndGradient(x, y, new double[net.parameters().length]);

        assertTrue(after < before / 2, "a perda foi de " + before + " para " + after
                + ", o que nao e aprender");
    }

    @Test
    @DisplayName("A MESMA SEMENTE DÁ A MESMA REDE")
    void thesameSeedGivesTheSameNetwork() {
        double[] sample = {0.3, -1.1, 0.7, 2.0};

        assertArrayEquals(new Mlp(INPUTS, 141).predict(sample),
                new Mlp(INPUTS, 141).predict(sample), 0.0,
                "duas redes com a mesma semente responderam coisas diferentes");
    }

    @Test
    @DisplayName("O DROPOUT NÃO AGE NA INFERÊNCIA: a mesma entrada dá a mesma saída")
    void dropoutIsOffWhenPredicting() {
        Mlp net = new Mlp(INPUTS, 5);
        double[] sample = {1.0, -0.5, 0.25, 0.75};

        double[] once = net.predict(sample);

        for (int again = 0; again < 20; again++) {
            assertArrayEquals(once, net.predict(sample), 0.0,
                    "a mesma entrada respondeu diferente: o dropout ficou ligado");
        }
    }

    @Test
    @DisplayName("A SAÍDA É LINEAR: nove utilidades, e não uma distribuição")
    void theoutputIsLinearAndNotAdistribution() {
        Random dice = new Random(9);
        Mlp net = new Mlp(INPUTS, 21);

        double[][] x = inputs(dice);
        double[][] y = targets(dice, 5);

        net.train(x, y, 60);

        boolean negative = false;

        for (double[] row : x) {
            double total = 0;

            for (double each : net.predict(row)) {
                total += each;

                negative |= each < 0;
            }

            assertTrue(Math.abs(total - 1) > 1e-6,
                    "as nove saidas somaram um: alguem pos um softmax no fim");
        }

        assertTrue(negative, "nenhuma utilidade saiu negativa, o que uma regressao "
                + "sobre alvos negativos teria de produzir");
    }
}
