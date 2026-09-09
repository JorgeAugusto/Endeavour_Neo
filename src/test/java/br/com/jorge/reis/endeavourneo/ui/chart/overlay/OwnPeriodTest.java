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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An average computed on a larger scale than the chart it is drawn on.
 *
 * <p>This is where multi-timeframe indicators lie, and this project has paid for
 * it once already. The obvious mapping takes, for each bar on screen, the coarse
 * bar that CONTAINS it — and that bar is made partly of the future.</p>
 */
@DisplayName("Average on its own period")
class OwnPeriodTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** Fifteen one-minute bars from 09:00, closing at 1, 2, 3 ... 15. */
    private static PriceSeries minutes() {
        return new PriceSeries() {

            @Override
            public int size() {
                return 15;
            }

            @Override
            public long timeAt(int index) {
                return LocalDateTime.of(2026, 9, 2, 9, 0).plusMinutes(index)
                        .atZone(ZONE).toInstant().toEpochMilli();
            }

            @Override
            public double openAt(int index) {
                return index + 1;
            }

            @Override
            public double highAt(int index) {
                return index + 1;
            }

            @Override
            public double lowAt(int index) {
                return index + 1;
            }

            @Override
            public double closeAt(int index) {
                return index + 1;
            }
        };
    }

    private static MovingAverage onFiveMinutes() {
        MovingAverage average = new MovingAverage(1);

        average.setOwnPeriod("5m");

        br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.setInterpolateOwnScale(false);
        average.calculate(minutes());

        return average;
    }

    @Test
    @DisplayName("nothing is drawn before the first coarse bar has closed")
    void nothingBeforeTheFirstClose() {
        MovingAverage average = onFiveMinutes();

        for (int bar = 0; bar < 5; bar++) {
            assertTrue(Double.isNaN(average.valueAt(bar)[0]),
                    "bar " + bar + " drew a value from a five-minute bar still forming");
        }
    }

    @Test
    @DisplayName("the value is the LAST CLOSED coarse bar, never the one forming")
    void neverTheBarStillForming() {
        // The whole point. At 09:10 the 09:10-09:14 bar has not happened; its
        // close is 15, and using it would mean the line knew at 09:10 what the
        // next five minutes would do. The right answer is 10 -- the 09:05 bar,
        // which closed exactly at 09:10.
        MovingAverage average = onFiveMinutes();

        assertEquals(5.0, average.valueAt(5)[0], 1e-9,
                "at 09:05 only the 09:00 bar has closed");
        assertEquals(5.0, average.valueAt(9)[0], 1e-9,
                "at 09:09 the 09:05 bar is still forming");
        assertEquals(10.0, average.valueAt(10)[0], 1e-9,
                "at 09:10 the line read a bar that had not finished");
        assertEquals(10.0, average.valueAt(14)[0], 1e-9,
                "the last bar cannot know its own five-minute close");
    }

    @Test
    @DisplayName("no value on screen comes from the future, at any bar")
    void nothingComesFromTheFuture() {
        // Said as a property rather than as three numbers: every value drawn has
        // to be one the market had already produced at that moment.
        MovingAverage average = onFiveMinutes();
        PriceSeries series = minutes();

        for (int bar = 0; bar < series.size(); bar++) {
            double value = average.valueAt(bar)[0];

            if (Double.isFinite(value)) {
                assertTrue(value <= series.closeAt(bar),
                        "bar " + bar + " drew " + value + ", which the market had not reached");
            }
        }
    }

    @Test
    @DisplayName("interpolation slopes between closed points and adds nothing new")
    void interpolationStaysBehind() {
        MovingAverage sloped = new MovingAverage(1);

        sloped.setOwnPeriod("5m");

        // O AJUSTE E DO GRAFICO, entao os dois nao podem ser calculados com
        // ele em estados diferentes ao mesmo tempo: cada um e calculado com o
        // seu, na ordem.
        MovingAverage stepped = onFiveMinutes();

        br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.setInterpolateOwnScale(true);

        sloped.calculate(minutes());
        PriceSeries series = minutes();

        // TEETH, and the reason they are here. This test used to assert only
        // the ceiling below, and it passed for the whole life of the option
        // while OwnScale.smooth was arithmetically identical to OwnScale.map:
        // the ramp was measured across an interval every bar had already gone
        // past, so the fraction was always clamped to 1. "Never ahead of the
        // market" cannot tell a sloped line from a flat one, and a flat line is
        // exactly what a broken interpolation produces.
        assertNotEquals(stepped.valueAt(12)[0], sloped.valueAt(12)[0], 1e-9,
                "the sloped line equals the stepped one: interpolation did nothing");

        // It has to MOVE, bar by bar, inside one coarse bar.
        for (int bar = 11; bar <= 14; bar++) {
            assertTrue(sloped.valueAt(bar)[0] > sloped.valueAt(bar - 1)[0],
                    "bar " + bar + " did not slope past bar " + (bar - 1));
        }

        for (int bar = 0; bar < series.size(); bar++) {
            double value = sloped.valueAt(bar)[0];

            if (Double.isFinite(value)) {
                // Never ahead of the market...
                assertTrue(value <= series.closeAt(bar),
                        "interpolation reached bar " + bar + " with " + value);

                // ...and never ahead of the step it is walking towards either.
                // The ramp arrives at the closed value, it does not overshoot
                // it: sloping is allowed to lag, never to lead.
                assertTrue(value <= stepped.valueAt(bar)[0] + 1e-9,
                        "bar " + bar + " sloped past the closed value it aims at");
            }
        }
    }

    @Test
    @DisplayName("an unknown period falls back to the chart's own")
    void unknownPeriodFallsBack() {
        // A layout written by a later version can name a scale this one does not
        // build. Drawing on the chart's scale is a smaller wrong than drawing
        // nothing and leaving an indicator listed but invisible.
        MovingAverage average = new MovingAverage(3);

        average.setOwnPeriod("nonsense");
        average.calculate(minutes());

        assertEquals(2.0, average.valueAt(2)[0], 1e-9);
    }

    @Test
    @DisplayName("the chosen period is remembered")
    void theChoiceIsRemembered() {
        MovingAverage set = new MovingAverage(9);

        set.setOwnPeriod("15m");

        MovingAverage read = new MovingAverage(9);

        read.applyAppearance(set.appearance());

        assertEquals("15m", read.ownPeriod());
    }


    /**
     * O degrau é o padrão, e é o que o Profit desenha.
     *
     * <p>Uma média de cinco minutos num gráfico de minutos só pode responder
     * com a última barra de cinco FECHADA — o caminho óbvio lê o futuro — então
     * ela muda de cinco em cinco barras, em degraus. Inclinar entre um degrau e
     * o outro é opção, e custa uma barra grossa inteira a mais de atraso: a
     * linha sai do valor anterior no instante em que o novo se torna
     * conhecível, e só CHEGA nele uma barra depois.</p>
     */
    @Test
    @DisplayName("desligada, a linha e uma escada -- que e o que o Profit desenha")
    void offTheLineIsAStaircase() {
        // POSTO AQUI, e nao herdado do padrao. O ajuste e estatico e a suite
        // roda numa JVM so, entao o valor com que este teste comeca depende de
        // quem rodou antes -- e um teste cuja pergunta muda com a ordem nao
        // pergunta nada.
        //
        // O PADRAO em si nao tem teste, e vale dizer por que: ele e o literal
        // em ChartPreferences.getBoolean(INTERPOLATE, false), lido uma vez na
        // carga da classe, e qualquer teste anterior que mexa no ajuste ja o
        // sobrepos. E o mesmo limite que a costura do JobService tem.
        br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.setInterpolateOwnScale(false);

        MovingAverage average = new MovingAverage(1);

        average.setOwnPeriod("5m");
        average.calculate(minutes());

        // A serie tem quinze minutos, ou seja tres barras de cinco. Os
        // minutos 10 a 14 sao a terceira, e dentro dela o valor nao se mexe:
        // e o mesmo do comeco ao fim. Isso e o degrau.
        double third = average.valueAt(10)[0];

        assertFalse(Double.isNaN(third),
                "there is no value at minute 10, so the loop below compares nothing");

        for (int bar = 10; bar < 15; bar++) {
            assertEquals(third, average.valueAt(bar)[0], 1e-9,
                    "the value moved inside one coarse bar, at minute " + bar + ": the "
                            + "default is no longer a staircase");
        }

        // E MUDA NA FRONTEIRA, senao o degrau seria uma linha reta e o teste
        // acima passaria com o indicador desenhando qualquer constante.
        assertNotEquals(third, average.valueAt(9)[0], 1e-9,
                "the value is the same on both sides of the coarse boundary, so there is "
                        + "no step here and the loop above proves nothing");
    }

    /**
     * E o ajuste do gráfico é quem decide, não o indicador.
     *
     * <p>Era um campo de cada indicador, com uma caixa em cada diálogo: três
     * respostas para uma pergunta que é do gráfico. Dois indicadores na mesma
     * tela respondendo diferente não é coisa que alguém queira.</p>
     */
    @Test
    @DisplayName("o ajuste do grafico e quem decide, e nao o indicador")
    void thechartSettingIsWhatDecides() {
        MovingAverage average = new MovingAverage(1);

        average.setOwnPeriod("5m");
        average.calculate(minutes());

        // O minuto 12 esta no MEIO da terceira barra de cinco, que e onde a
        // rampa e a escada mais se afastam.
        double stepped = average.valueAt(12)[0];

        assertFalse(Double.isNaN(stepped), "there is nothing drawn at minute 12");

        br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.setInterpolateOwnScale(true);

        average.calculate(minutes());

        assertNotEquals(stepped, average.valueAt(12)[0], 1e-9,
                "turning the chart's setting on changed nothing, so the option does not "
                        + "reach the indicator");
    }

    /**
     * Devolve o ajuste ao padrão.
     *
     * <p>É um ajuste do gráfico, e portanto estático: um teste que o liga e não
     * o desliga muda o resultado do teste seguinte, e de uma classe que nem
     * sabe que ele existe. A suíte inteira roda numa JVM só.</p>
     */
    @org.junit.jupiter.api.AfterEach
    void putTheSettingBack() {
        br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.setInterpolateOwnScale(false);
    }
}
