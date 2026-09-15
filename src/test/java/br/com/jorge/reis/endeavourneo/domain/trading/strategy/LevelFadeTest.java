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
package br.com.jorge.reis.endeavourneo.domain.trading.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.indicator.Vwap;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.trading.Backtest;
import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Side;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O fade de níveis: dois ranges de acordo, níveis peneirados, armar e tocar. */
@DisplayName("Fade de níveis operado")
class LevelFadeTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private static final double WICK = 50;

    /**
     * Um pregão inteiro, desenhado para exercer a máquina toda.
     *
     * <p>A fixture é elaborada porque a regra é: os dois ranges precisam romper
     * para o MESMO lado antes de qualquer entrada, e só depois o preço precisa
     * oscilar o bastante para ir setenta e cinco pontos além de um nível e voltar
     * a tocá-lo. Um passeio aleatório não faz isso, e uma série curta não chega
     * nem a formar o range de noventa minutos.</p>
     *
     * <p>O desenho, em minutos a partir das 09:00:</p>
     *
     * <ul>
     *   <li>0 a 4 — sobe de 100.000 a 100.600. É o primeiro candle de cinco
     *       minutos, e ele decide a direção da briga: fecha acima da abertura,
     *       então +1. Amplitude 700 com o pavio, acima dos 500 que fariam o
     *       candle contrário ser incorporado.</li>
     *   <li>5 a 9 — recua a 100.500. Candle de cinco minutos CONTRÁRIO, que é o
     *       que encerra a formação da briga.</li>
     *   <li>10 a 89 — sobe a 101.000, rompendo a briga para cima cedo e
     *       fechando a formação do range de noventa às 10:29.</li>
     *   <li>90 — rompe o range de noventa para cima. Os dois lados concordam, e
     *       o dia passa a comprar.</li>
     *   <li>91 em diante — vai e volta entre 100.900 e 101.400, num triângulo de
     *       quarenta barras. É essa oscilação que arma níveis e depois volta a
     *       tocá-los.</li>
     * </ul>
     */
    private record Bars(double[] price) implements PriceSeries {

        @Override
        public int size() {
            return price.length;
        }

        @Override
        public long timeAt(int index) {
            return ZonedDateTime.of(LocalDate.of(2025, 1, 6), LocalTime.of(9, 0), SP)
                    .toInstant().toEpochMilli() + index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return price[index];
        }

        @Override
        public double highAt(int index) {
            return price[index] + WICK;
        }

        @Override
        public double lowAt(int index) {
            return price[index] - WICK;
        }

        @Override
        public double closeAt(int index) {
            return price[index];
        }
    }

    private static Bars day() {
        double[] made = new double[480];

        for (int i = 0; i <= 4; i++) {
            made[i] = 100_000 + 150.0 * i;
        }

        for (int i = 5; i <= 9; i++) {
            made[i] = 100_575 - 15.0 * (i - 5);
        }

        for (int i = 10; i <= 89; i++) {
            made[i] = 100_500 + 500.0 * (i - 9) / 80.0;
        }

        made[90] = 101_010;

        // O triangulo: sobe vinte barras, desce vinte, entre 100.900 e 101.400.
        for (int i = 91; i < made.length; i++) {
            int step = (i - 91) % 40;
            double up = step < 20 ? step : 40 - step;

            made[i] = 100_900 + 500.0 * up / 20.0;
        }

        return new Bars(made);
    }

    private static LevelFade strategy() {
        return new LevelFade(SP, LevelFade.TARGET, LevelFade.STOP, LevelFade.SLIP,
                LevelFade.QTY);
    }

    private static Result run(PriceSeries bars, LevelFade what) {
        what.sourcedFrom(bars);

        return new Backtest(Costs.NONE, LevelFade.QTY).run(bars, bars, what, null);
    }

    private static List<Fill> openings(Result result) {
        List<Fill> made = new ArrayList<>();

        for (Fill fill : result.fills()) {
            if (!fill.verb().contains("Cover") && !fill.verb().contains("Close")) {
                made.add(fill);
            }
        }

        return made;
    }

    // ----------------------------------------------------------- os niveis

    @Test
    @DisplayName("PENEIRA OS NÍVEIS A CEM PONTOS, e guarda só os que sobram")
    void itthinsTheLevelsToAHundredPoints() {
        Bars bars = day();
        LevelFade what = strategy();

        run(bars, what);

        List<Double> kept = what.levelsNow();

        assertFalse(kept.isEmpty(), "o dia nao produziu nivel nenhum");

        // A PENEIRA, conferida por fora: em ordem crescente, dois vizinhos nunca
        // ficam a menos de cem pontos. Se a peneira sair do produto, esta linha
        // e a que cai.
        for (int i = 1; i < kept.size(); i++) {
            double apart = kept.get(i) - kept.get(i - 1);

            assertTrue(apart >= LevelFade.MIN_SPACING, "os niveis " + kept.get(i - 1)
                    + " e " + kept.get(i) + " ficaram a " + apart + " pontos");
        }
    }

    // ----------------------------------------------------------- as entradas

    @Test
    @DisplayName("SÓ COMPRA, e só no lado com que os dois ranges concordam")
    void itonlyTradesTheSideBothRangesAgreeOn() {
        Bars bars = day();
        LevelFade what = strategy();
        Result result = run(bars, what);

        List<Fill> opened = openings(result);

        assertFalse(opened.isEmpty(), "nao abriu posicao nenhuma");

        for (Fill fill : opened) {
            int side = what.sideAt(fill.bar() - 1);

            assertTrue(side != 0, "abriu na barra " + fill.bar()
                    + " num momento em que os ranges nao concordavam");

            assertEquals(side > 0 ? Side.BUY : Side.SELL, fill.side(),
                    "abriu contra o lado dos ranges, na barra " + fill.bar());
        }
    }

    @Test
    @DisplayName("ENTRA NUM NÍVEL, e ao preço que o nível manda")
    void itentersAtALevelAndAtItsPrice() {
        Bars bars = day();
        LevelFade what = strategy();
        Result result = run(bars, what);

        List<Double> levels = new ArrayList<>(what.levelsNow());
        Vwap.Lines lines = Vwap.standard().over(bars, SP);
        List<Fill> opened = openings(result);

        assertFalse(opened.isEmpty(), "nao abriu posicao nenhuma");

        for (Fill fill : opened) {
            List<Double> here = new ArrayList<>(levels);

            for (int away = 1; away <= Vwap.BANDS; away++) {
                here.add(lines.bandAt(fill.bar() - 2, away));
                here.add(lines.bandAt(fill.bar() - 2, -away));
            }

            boolean explained = false;

            for (Double level : here) {
                if (level == null || Double.isNaN(level)) {
                    continue;
                }

                // UMA ORDEM LIMITADA casa no limite, ou na abertura quando a
                // abertura ja esta melhor -- uma coisa ou a outra, nunca a media.
                double wanted = Math.min(bars.openAt(fill.bar()), level);

                if (Math.abs(wanted - fill.price()) < 1e-9
                        && bars.lowAt(fill.bar()) <= level) {
                    explained = true;

                    break;
                }
            }

            assertTrue(explained, "a entrada da barra " + fill.bar() + " a "
                    + fill.price() + " nao corresponde a nivel nenhum do dia");
        }
    }

    @Test
    @DisplayName("ESPERA O ESFRIAMENTO entre uma entrada e a seguinte")
    void itwaitsTheCooldownBetweenEntries() {
        Bars bars = day();
        Result result = run(bars, strategy());

        List<Fill> opened = openings(result);

        assertTrue(opened.size() > 1, "so houve " + opened.size()
                + " entrada; o esfriamento nao chega a ser exercido");

        for (int i = 1; i < opened.size(); i++) {
            int apart = opened.get(i).bar() - opened.get(i - 1).bar();

            assertTrue(apart >= LevelFade.COOLDOWN, "duas entradas a " + apart
                    + " barras uma da outra, nas barras " + opened.get(i - 1).bar()
                    + " e " + opened.get(i).bar());
        }
    }

    @Test
    @DisplayName("NÃO PASSA DO TETO DE ENTRADAS nem da posição máxima")
    void itrespectsTheCeilings() {
        Bars bars = day();
        Result result = run(bars, strategy());

        List<Fill> opened = openings(result);

        assertTrue(opened.size() <= LevelFade.MAX_ENTRIES_DAY, "abriu "
                + opened.size() + " vezes num pregao, acima do teto de "
                + LevelFade.MAX_ENTRIES_DAY);

        int most = 0;

        for (var trade : result.trades()) {
            most = Math.max(most, trade.contracts());
        }

        assertTrue(most <= LevelFade.MAX_POSITION, "chegou a " + most
                + " contratos, acima do teto de " + LevelFade.MAX_POSITION);
    }

    @Test
    @DisplayName("NADA ATRAVESSA O PREGÃO: o dia termina zerado")
    void nothingCrossesTheSession() {
        Bars bars = day();
        Result result = run(bars, strategy());

        int net = 0;

        for (Fill fill : result.fills()) {
            net += fill.signed();
        }

        assertEquals(0, net, "o pregao terminou com " + net + " contratos em aberto");
    }
}
