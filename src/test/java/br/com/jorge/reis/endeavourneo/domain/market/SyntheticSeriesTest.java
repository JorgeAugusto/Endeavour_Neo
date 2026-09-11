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
package br.com.jorge.reis.endeavourneo.domain.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O caminho de dentro do minuto, um preço por barra.
 *
 * <p>O que estes testes defendem é a propriedade que faz o modo tick a tick
 * valer alguma coisa: <b>o caminho encosta na máxima e na mínima do minuto</b>.
 * Se não encostasse, um stop lá dentro passaria batido — e a execução fina
 * seria pior que a grossa, porque pareceria mais precisa.</p>
 */
@DisplayName("Ticks sinteticos como serie")
class SyntheticSeriesTest {

    /** Minutos com forma variada: sobe, desce, parado, e um de alcance grande. */
    private record Bars(int count) implements PriceSeries {

        @Override
        public int size() {
            return count;
        }

        @Override
        public long timeAt(int index) {
            return 1_600_000_000_000L + index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return 100_000 + index * 25;
        }

        @Override
        public double closeAt(int index) {
            return openAt(index) + (index % 3 == 0 ? 40 : index % 3 == 1 ? -35 : 0);
        }

        @Override
        public double highAt(int index) {
            return Math.max(openAt(index), closeAt(index)) + 5 * (index % 7);
        }

        @Override
        public double lowAt(int index) {
            return Math.min(openAt(index), closeAt(index)) - 5 * (index % 5);
        }
    }

    private static PriceSeries walked(int bars) {
        return SyntheticSeries.of(new Bars(bars), new SyntheticTicks(5, 42));
    }

    @Test
    @DisplayName("O CAMINHO ENCOSTA NA MAXIMA E NA MINIMA DE CADA MINUTO")
    void thepathTouchesEveryMinutesHighAndLow() {
        Bars source = new Bars(40);
        PriceSeries ticks = walked(40);

        double[] high = new double[source.size()];
        double[] low = new double[source.size()];

        java.util.Arrays.fill(high, Double.NEGATIVE_INFINITY);
        java.util.Arrays.fill(low, Double.POSITIVE_INFINITY);

        for (int tick = 0; tick < ticks.size(); tick++) {
            int minute = (int) ((ticks.timeAt(tick) - source.timeAt(0)) / 60_000L);

            high[minute] = Math.max(high[minute], ticks.closeAt(tick));
            low[minute] = Math.min(low[minute], ticks.closeAt(tick));
        }

        for (int minute = 0; minute < source.size(); minute++) {
            // Um stop dentro do minuto SO e alcancado se o caminho chegar la. Um
            // caminho que nao encosta nos extremos torna a execucao fina pior
            // que a grossa, porque parece mais precisa e perde execucoes.
            assertEquals(source.highAt(minute), high[minute], 1e-9,
                    "o caminho do minuto " + minute + " nao encostou na maxima");
            assertEquals(source.lowAt(minute), low[minute], 1e-9,
                    "o caminho do minuto " + minute + " nao encostou na minima");
        }
    }

    @Test
    @DisplayName("cada minuto comeca na abertura e termina no fechamento")
    void everyMinuteStartsAtTheOpenAndEndsAtTheClose() {
        Bars source = new Bars(12);
        PriceSeries ticks = walked(12);

        int at = 0;

        for (int minute = 0; minute < source.size(); minute++) {
            long start = source.timeAt(minute);
            int first = at;

            while (at < ticks.size() && ticks.timeAt(at) < start + 60_000L) {
                at++;
            }

            assertEquals(source.openAt(minute), ticks.openAt(first), 1e-9,
                    "o minuto " + minute + " nao comecou na abertura");
            assertEquals(source.closeAt(minute), ticks.closeAt(at - 1), 1e-9,
                    "o minuto " + minute + " nao terminou no fechamento");
        }
    }

    @Test
    @DisplayName("o tempo anda para frente e nao sai do minuto")
    void timeMovesForwardAndStaysInsideItsMinute() {
        Bars source = new Bars(20);
        PriceSeries ticks = walked(20);

        long before = Long.MIN_VALUE;

        for (int tick = 0; tick < ticks.size(); tick++) {
            long now = ticks.timeAt(tick);

            assertTrue(now >= before, "o tempo voltou no tick " + tick);
            assertTrue(now >= source.timeAt(0), "um tick nasceu antes da serie");
            assertTrue(now < source.timeAt(source.size() - 1) + 60_000L,
                    "um tick passou do fim do ultimo minuto");

            before = now;
        }
    }

    @Test
    @DisplayName("o mesmo minuto da o mesmo caminho, sempre")
    void thesameMinuteGivesTheSamePathEveryTime() {
        PriceSeries uma = walked(15);
        PriceSeries outra = walked(15);

        assertEquals(uma.size(), outra.size(), "duas construcoes deram tamanhos diferentes");

        // Se o caminho mudasse entre rodadas, nada a jusante significaria nada:
        // nem as marcas no grafico, nem a comparacao entre duas estrategias.
        for (int tick = 0; tick < uma.size(); tick += 97) {
            assertEquals(uma.closeAt(tick), outra.closeAt(tick), 0.0,
                    "o tick " + tick + " mudou de preco entre duas construcoes");
        }
    }

    @Test
    @DisplayName("um minuto vira muitos ticks, e um minuto parado vira poucos")
    void aminuteBecomesManyTicksAndAquietOneFew() {
        PriceSeries ticks = walked(30);

        assertTrue(ticks.size() > 30 * 8,
                "trinta minutos viraram so " + ticks.size() + " ticks");
        assertTrue(ticks.size() < 30 * 25_000, "o caminho explodiu: " + ticks.size());
    }

    @Test
    @DisplayName("um caminho que encolhe nao derruba a varredura")
    void apathThatShrinksDoesNotBringDownTheRun() {
        // TickPath nao promete ser deterministico, e RecordedTicks le de arquivo:
        // um arquivo que mudou entre montar o indice e ler o preco devolve um
        // caminho de outro tamanho. Ai a trava do priceAt e a diferenca entre um
        // preco vizinho e uma excecao no meio da varredura.
        PriceSeries shrinking = SyntheticSeries.of(new Bars(3), new TickPath() {

            private int asked;

            @Override
            public double[] pathFor(PriceSeries series, int index) {
                return asked++ < 3
                        ? new double[] {1, 2, 3, 4, 5, 6, 7, 8}
                        : new double[] {1, 2};
            }
        });

        assertEquals(24, shrinking.size(), "o indice nao foi montado com o primeiro tamanho");

        for (int tick = 0; tick < shrinking.size(); tick++) {
            assertTrue(shrinking.closeAt(tick) >= 1, "o tick " + tick + " nao devolveu preco");
        }

        assertEquals(2, shrinking.closeAt(23), 1e-9,
                "o ultimo tick nao caiu no ultimo preco do caminho encolhido");
    }

    @Test
    @DisplayName("serie vazia nao vira caminho nenhum")
    void anemptySeriesBecomesNoPath() {
        assertEquals(0, SyntheticSeries.of(new Bars(0), new SyntheticTicks(5, 1)).size(),
                "uma serie vazia gerou ticks");
        assertEquals(0, SyntheticSeries.of(null, new SyntheticTicks(5, 1)).size(),
                "uma serie nula gerou ticks");
        assertSame(PriceSeries.empty().getClass(),
                SyntheticSeries.of(new Bars(5), null).getClass(),
                "sem caminho, devolveu outra coisa que nao o vazio");
    }
}
