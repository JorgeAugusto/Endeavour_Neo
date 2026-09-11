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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Os três padrões de três barras, reconhecidos como o indicador de origem os
 * reconhece — e a diferença deliberada, medida.
 */
@DisplayName("Padroes de candle")
class CandlePatternsTest {

    /** Barras ditadas: cada uma é {open, high, low, close}. */
    private record Bars(double[][] ohlc) implements PriceSeries {

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
    }

    private static CandlePattern last(double[]... bars) {
        return CandlePatterns.detect(new Bars(bars), bars.length - 1);
    }

    @Test
    @DisplayName("as duas primeiras barras nunca tem padrao")
    void thefirstTwoBarsNeverHaveApattern() {
        // CLASSIFICAR PRECISA DE TRES BARRAS. Sem as duas anteriores nao ha o
        // que comparar, e chutar seria inventar padrao onde nao ha dado.
        Bars bars = new Bars(new double[][] {
            {100, 110, 90, 105},
            {105, 112, 95, 108},
            {108, 111, 94, 109},
        });

        assertEquals(CandlePattern.NONE, CandlePatterns.detect(bars, 0), "a barra 0 teve padrao");
        assertEquals(CandlePattern.NONE, CandlePatterns.detect(bars, 1), "a barra 1 teve padrao");
        assertEquals(2, CandlePatterns.LOOKBACK, "o alcance deixou de ser duas barras");
    }

    @Test
    @DisplayName("PFR DE ALTA: menor minima das tres, e o fechamento recusa")
    void apfrUpIsTheLowestOfThreeRefusedByTheClose() {
        // Minima 80 e menor que as duas anteriores; fecha em 108, acima do
        // fechamento anterior (100) e da propria abertura (85). Quem vendeu na
        // minima terminou a barra perdendo.
        assertEquals(CandlePattern.PFR_BULLISH, last(
                new double[] {100, 110, 95, 105},
                new double[] {105, 112, 90, 100},
                new double[] {85, 110, 80, 108}),
                "nao reconheceu o PFR de alta");

        // O mesmo extremo, mas fechando ABAIXO da propria abertura: nao ha
        // recusa, e sem recusa nao ha PFR.
        assertNotEquals(CandlePattern.PFR_BULLISH, last(
                new double[] {100, 110, 95, 105},
                new double[] {105, 112, 90, 100},
                new double[] {110, 112, 80, 102}),
                "chamou de PFR uma barra que fechou abaixo da propria abertura");
    }

    @Test
    @DisplayName("PFR de baixa e o espelho")
    void apfrDownIsTheMirror() {
        assertEquals(CandlePattern.PFR_BEARISH, last(
                new double[] {100, 105, 95, 100},
                new double[] {100, 108, 96, 103},
                new double[] {115, 120, 100, 101}),
                "nao reconheceu o PFR de baixa");
    }

    @Test
    @DisplayName("INSIDE: faixa inteiramente dentro da anterior, e o corpo da o lado")
    void aninsideIsWhollyWithinTheOneBefore() {
        assertEquals(CandlePattern.INSIDE_BULLISH, last(
                new double[] {100, 110, 90, 105},
                new double[] {100, 120, 80, 110},
                new double[] {100, 115, 85, 108}),
                "nao reconheceu o inside de alta");

        assertEquals(CandlePattern.INSIDE_BEARISH, last(
                new double[] {100, 110, 90, 105},
                new double[] {100, 120, 80, 110},
                new double[] {108, 115, 85, 100}),
                "nao reconheceu o inside de baixa");
    }

    @Test
    @DisplayName("doji interno nao e padrao: sem corpo nao ha direcao a confirmar")
    void aninsideDojiIsNotApattern() {
        assertEquals(CandlePattern.NONE, last(
                new double[] {100, 110, 90, 105},
                new double[] {100, 120, 80, 110},
                new double[] {100, 115, 85, 100}),
                "pintou um doji interno, que o original tambem nao pintava");
    }

    @Test
    @DisplayName("1-2-3: o pivo e a barra do meio, e esta confirma")
    void aoneTwoThreeHasThePivotOnTheMiddleBar() {
        // A do meio faz a menor minima das tres e a atual fecha em alta.
        //
        // E a atual NAO pode ser interna: a maxima dela passa da anterior. Sem
        // isso o caso vira um inside, que ganha na precedencia -- foi assim que
        // a primeira versao deste teste acusou um defeito que nao existia.
        assertEquals(CandlePattern.ONE_TWO_THREE_BUY, last(
                new double[] {100, 110, 95, 105},
                new double[] {104, 106, 85, 90},
                new double[] {92, 110, 88, 103}),
                "nao reconheceu o 1-2-3 de compra");

        assertEquals(CandlePattern.ONE_TWO_THREE_SELL, last(
                new double[] {100, 105, 95, 100},
                new double[] {101, 125, 99, 120},
                new double[] {118, 122, 95, 110}),
                "nao reconheceu o 1-2-3 de venda");
    }

    @Test
    @DisplayName("A PRECEDENCIA DECIDE: o inside ganha do 1-2-3")
    void theinsideWinsOverTheOneTwoThree() {
        // Uma barra interna satisfaz low[1] < low POR CONSTRUCAO -- e por isso
        // ela pode, ao mesmo tempo, formar um pivo de fundo na barra do meio.
        // Os dois valeriam; o inside ganha, como no original.
        Bars bars = new Bars(new double[][] {
            {100, 110, 95, 105},
            {104, 120, 80, 118},
            {110, 115, 85, 112},
        });

        // Conferindo que o caso e mesmo ambiguo, e nao um inside qualquer: a do
        // meio faz a menor minima das tres e esta fecha em alta, que e a
        // definicao do 1-2-3 de compra.
        assertTrue(bars.lowAt(1) < bars.lowAt(0) && bars.lowAt(1) < bars.lowAt(2),
                "o caso montado nao tem pivo de fundo, entao nao ha disputa");
        assertTrue(bars.closeAt(2) > bars.openAt(2), "a barra atual nao fecha em alta");

        assertEquals(CandlePattern.INSIDE_BULLISH, CandlePatterns.detect(bars, 2),
                "o 1-2-3 ganhou do inside, e a precedencia diz o contrario");
    }

    @Test
    @DisplayName("A DIFERENCA DELIBERADA: o original engolia a barra, aqui ela volta")
    void theoriginalSwallowedTheBarAndHereItComesBack() {
        // Nova maxima de tres, fechando ABAIXO do fechamento anterior e ACIMA da
        // propria abertura. No original ela entrava no bloco do PFR de baixa,
        // falhava o teste interno, saia sem padrao E NAO ERA MAIS TESTADA --
        // mesmo sendo um 1-2-3 de compra perfeito.
        Bars bars = new Bars(new double[][] {
            {100, 110, 95, 105},
            {104, 106, 85, 100},
            {90, 130, 88, 98},
        });

        assertTrue(bars.highAt(2) > bars.highAt(1) && bars.highAt(2) > bars.highAt(0),
                "a barra montada nao faz maxima nova de tres");
        assertTrue(bars.closeAt(2) < bars.closeAt(1), "nao fecha abaixo do fechamento anterior");
        assertTrue(bars.closeAt(2) > bars.openAt(2), "nao fecha acima da propria abertura");

        assertEquals(CandlePattern.NONE, CandlePatterns.asProfitDid(bars, 2),
                "o comportamento literal deixou de engolir a barra");
        assertEquals(CandlePattern.ONE_TWO_THREE_BUY, CandlePatterns.detect(bars, 2),
                "a versao limpa nao devolveu a barra engolida ao 1-2-3");
    }

    @Test
    @DisplayName("detectAll da um padrao por barra, e nunca nulo")
    void detectAllGivesOnePerBarAndNeverNull() {
        PriceSeries series = new RandomWalkSeries(200, 100.0);
        CandlePattern[] found = CandlePatterns.detectAll(series);

        assertEquals(series.size(), found.length, "faltou padrao para alguma barra");

        for (int bar = 0; bar < found.length; bar++) {
            assertTrue(found[bar] != null, "a barra " + bar + " veio nula");
        }

        assertEquals(0, CandlePatterns.detectAll(null).length, "uma serie nula gerou padroes");
    }

    @Test
    @DisplayName("cada padrao sabe a familia dele, e o lado")
    void everypatternKnowsItsShapeAndSide() {
        assertEquals(CandlePattern.Family.PFR, CandlePattern.PFR_BULLISH.family(),
                "o PFR de alta mudou de familia");
        assertEquals(CandlePattern.Family.INSIDE, CandlePattern.INSIDE_BEARISH.family(),
                "o inside de baixa mudou de familia");
        assertEquals(CandlePattern.Family.ONE_TWO_THREE, CandlePattern.ONE_TWO_THREE_SELL.family(),
                "o 1-2-3 de venda mudou de familia");

        // A FAMILIA E O QUE O LEITOR LIGA E DESLIGA: alta e baixa sao uma forma
        // vista de dois lados, nao duas formas. Tres interruptores e seis cores,
        // e e isso que faz a diferenca.
        assertEquals(1, CandlePattern.PFR_BULLISH.direction(), "o PFR de alta nao aponta para cima");
        assertEquals(-1, CandlePattern.PFR_BEARISH.direction(), "o PFR de baixa nao aponta para baixo");
        assertEquals(CandlePattern.Family.NONE, CandlePattern.NONE.family(),
                "o nenhum ganhou familia");
    }
}
