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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O range da primeira briga, portado do {@code JorgeReisRangeAbertura_5m}. */
@DisplayName("Range de abertura: a primeira briga")
class OpeningImpulseTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    /** Candles de 5m ditados um a um: abertura, máxima, mínima, fechamento. */
    private record Bars(double[][] ohlc, int perDay) implements PriceSeries {

        private Bars(double[][] ohlc) {
            this(ohlc, ohlc.length);
        }

        @Override
        public int size() {
            return ohlc.length;
        }

        @Override
        public long timeAt(int index) {
            LocalDate day = LocalDate.of(2025, 1, 6).plusDays(index / perDay);

            return ZonedDateTime.of(day, LocalTime.of(9, 0), SP).toInstant().toEpochMilli()
                    + (index % perDay) * 5 * 60_000L;
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
    }

    @Test
    @DisplayName("O PRIMEIRO CANDLE DA A DIRECAO, e o primeiro contrario FECHA a briga")
    void thefirstCandleGivesTheSideAndTheFirstOppositeEndsIt() {
        // Sobe tres candles e o quarto fecha para baixo. A briga acaba nele, e o
        // que vem depois -- por mais alto que va -- nao alarga mais o range.
        Bars bars = new Bars(new double[][] {
            {100_000, 100_800, 99_900, 100_700},
            {100_700, 101_400, 100_600, 101_300},
            {101_300, 102_000, 101_200, 101_900},
            {101_900, 102_100, 101_500, 101_600},
            {101_600, 109_000, 101_500, 108_900}});

        List<OpeningImpulse.Fight> fights = OpeningImpulse.standard().of(bars, SP);

        assertEquals(1, fights.size(), "nao saiu uma briga por pregao");

        OpeningImpulse.Fight fight = fights.get(0);

        assertTrue(fight.known(), "a briga nao fechou");
        assertEquals(1, fight.direction(), "o primeiro candle subiu e a direcao nao foi de alta");

        // O range e o das TRES primeiras: 102.000 de maxima, 99.900 de minima. O
        // quarto candle so termina a briga -- 2.100 de tamanho ja passa do piso
        // de 500, entao ele nao e absorvido. E o quinto nao existe para o range.
        assertEquals(102_000, fight.high(), 0.0, "a maxima do range nao e a das tres primeiras");
        assertEquals(99_900, fight.low(), 0.0, "a minima do range mudou");
        assertEquals(2_100, fight.size(), 0.0, "o tamanho do range nao bate");
    }

    @Test
    @DisplayName("UMA ESCARAMUCA PEQUENA ABSORVE o candle contrario, em vez de acabar nele")
    void asmallSkirmishAbsorbsTheOppositeCandle() {
        // Primeiro movimento de 200 pontos, abaixo do piso de 500: o candle
        // contrario entra no range em vez de so termina-lo. E a regra do
        // TamMinPrimeiroMovimento, e sem ela o dia fica com um range de tres
        // ticks que nada respeita.
        Bars bars = new Bars(new double[][] {
            {100_000, 100_200, 100_000, 100_150},
            {100_150, 100_300, 99_400, 99_500}});

        OpeningImpulse.Fight fight = OpeningImpulse.standard().of(bars, SP).get(0);

        assertTrue(fight.known(), "a briga nao fechou");
        assertEquals(100_300, fight.high(), 0.0, "a maxima do contrario nao entrou");
        assertEquals(99_400, fight.low(), 0.0, "a minima do contrario nao entrou");

        // E com o piso ZERADO a mesma serie NAO absorve: os 200 ja bastam.
        OpeningImpulse.Fight semPiso = new OpeningImpulse(0).of(bars, SP).get(0);

        assertEquals(100_200, semPiso.high(), 0.0, "com piso zero o contrario foi absorvido");
        assertEquals(100_000, semPiso.low(), 0.0, "com piso zero a minima mudou");
    }

    @Test
    @DisplayName("UM PREGAO QUE NUNCA VIRA nao tem briga, e o range nao e fato")
    void asessionThatNeverTurnsHasNoFight() {
        Bars bars = new Bars(new double[][] {
            {100_000, 100_500, 99_900, 100_400},
            {100_400, 101_000, 100_300, 100_900},
            {100_900, 101_500, 100_800, 101_400}});

        OpeningImpulse.Fight fight = OpeningImpulse.standard().of(bars, SP).get(0);

        // NAO E FATO, e por isso nada pode ser lido dele. Devolver o range como
        // se estivesse pronto seria oferecer um nivel que ainda ia crescer.
        assertFalse(fight.known(), "um pregao sem candle contrario fechou a briga");
    }

    @Test
    @DisplayName("O ROMPIMENTO SO CONTA DEPOIS QUE A BRIGA ACABA")
    void thebreakOnlyCountsOnceTheFightIsOver() {
        // O segundo candle sobe acima da maxima do primeiro -- mas a briga ainda
        // esta aberta e ele ALARGA o range em vez de rompe-lo. So depois do
        // contrario (o terceiro) um rompimento existe.
        Bars bars = new Bars(new double[][] {
            {100_000, 100_500, 99_900, 100_400},
            {100_400, 101_000, 100_300, 100_900},
            {100_900, 101_100, 100_500, 100_600},
            {100_600, 100_900, 100_500, 100_800},
            {100_800, 101_600, 100_700, 101_500}});

        OpeningImpulse what = OpeningImpulse.standard();
        List<OpeningImpulse.Fight> fights = what.of(bars, SP);
        int[] broke = what.brokenAt(bars, fights, SP);

        assertEquals(101_000, fights.get(0).high(), 0.0, "o range nao foi alargado pelo segundo");

        assertEquals(0, broke[0], "rompeu antes da briga acabar");
        assertEquals(0, broke[1], "o candle que ALARGA o range foi lido como rompimento");
        assertEquals(0, broke[2], "o proprio candle que fechou a briga contou como rompimento");
        assertEquals(0, broke[3], "rompeu sem passar da maxima");
        assertEquals(1, broke[4], "o rompimento de verdade nao foi marcado");
    }

    @Test
    @DisplayName("O PRIMEIRO ROMPIMENTO MANDA NO DIA, e nao o ultimo")
    void thefirstBreakOwnsTheDay() {
        // Rompe para cima e depois desaba abaixo da minima. A bussola continua
        // apontando para cima: lida pelo ultimo rompimento ela giraria junto com
        // o mercado, que e a unica coisa que uma bussola nao pode fazer.
        //
        // A ULTIMA BARRA NAO PODE PASSAR DA MAXIMA, e isso e o que a primeira
        // versao desta fixture errou: com maxima de 101.000 ela rompia os DOIS
        // lados, o teste de cima vencia por ser o primeiro no codigo, e a
        // travessura de ler o ultimo rompimento passava despercebida.
        Bars bars = new Bars(new double[][] {
            {100_000, 100_500, 99_900, 100_400},
            {100_400, 100_600, 100_200, 100_300},
            {100_300, 101_000, 100_200, 100_900},
            {100_900, 100_400, 99_000, 99_100}});

        OpeningImpulse what = OpeningImpulse.standard();
        int[] broke = what.brokenAt(bars, what.of(bars, SP), SP);

        assertEquals(1, broke[2], "o rompimento para cima nao foi marcado");
        assertEquals(1, broke[3],
                "a bussola girou: o dia que rompeu para cima passou a apontar para baixo");
    }

    @Test
    @DisplayName("CADA PREGAO TEM A SUA BRIGA, e nada atravessa a noite")
    void eachSessionHasItsOwnFight() {
        // TRES BARRAS POR PREGAO, e o primeiro dia PRECISA romper -- foi o que
        // a primeira versao desta fixture nao fez. Com dois candles por dia o
        // segundo era o que fechava a briga, nenhum rompimento acontecia, e
        // entao nao havia valor velho nenhum para atravessar a noite: a regra
        // ficava sem ser exercida e o teste passava de qualquer jeito.
        // QUATRO BARRAS POR PREGAO, e as duas coisas que a fixture precisa ter
        // custaram duas tentativas:
        //   o primeiro dia tem de ROMPER, senao nao ha valor velho nenhum para
        //     atravessar a noite;
        //   e a briga do segundo dia tem de FECHAR CEDO, com barras sobrando
        //     depois dela -- enquanto a briga esta aberta nada e escrito, entao
        //     um valor herdado ficaria escondido ate o dia acabar.
        Bars bars = new Bars(new double[][] {
            {100_000, 100_800, 99_900, 100_700},
            {100_700, 100_900, 100_500, 100_600},
            {100_600, 101_500, 100_500, 101_400},
            {101_400, 101_500, 101_000, 101_100},
            {200_000, 200_200, 199_000, 199_100},
            {199_100, 199_400, 199_000, 199_300},
            {199_300, 199_500, 199_200, 199_400},
            {199_400, 199_600, 199_300, 199_500}}, 4);

        List<OpeningImpulse.Fight> fights = OpeningImpulse.standard().of(bars, SP);

        assertEquals(2, fights.size(), "nao saiu uma briga por pregao: " + fights.size());
        assertEquals(1, fights.get(0).direction(), "o primeiro pregao nao subiu");
        assertEquals(-1, fights.get(1).direction(), "o segundo pregao nao caiu");

        int[] broke = OpeningImpulse.standard().brokenAt(bars, fights, SP);

        assertEquals(1, broke[2], "o primeiro pregao nao rompeu, entao nada foi exercido");
        assertEquals(0, broke[6], "o segundo pregao herdou o rompimento do primeiro");
        assertEquals(0, broke[7], "o rompimento do primeiro pregao sobreviveu a noite");
    }

    @Test
    @DisplayName("UMA SERIE VAZIA nao explode")
    void nothingIsStillSomething() {
        assertTrue(OpeningImpulse.standard().of(null, SP).isEmpty(), "serie nula deu briga");
        assertEquals(0, OpeningImpulse.standard().brokenAt(null, List.of(), SP).length,
                "serie nula deu rompimento");
    }
}
