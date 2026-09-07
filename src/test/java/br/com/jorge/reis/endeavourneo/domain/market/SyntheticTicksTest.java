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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The invented path, held to what the real ticks say a minute looks like.
 *
 * <h2>Where the numbers come from</h2>
 *
 * <p>The nine Profit sessions of 25/08 to 04/09/2026: 5.074 minutes, 48 million
 * prints, 8,8 million price changes. Each bound below is a measurement, and each
 * one fails on the rules this replaced — a Brownian bridge of {@code 4 + 2 ×
 * range} Gaussian steps, which moved a minute 35 times where the market moved
 * 1.118.</p>
 *
 * <p>The bounds are wide on purpose. They are not there to pin the constants;
 * they are there so that going back to a model of the wrong SHAPE cannot pass.</p>
 */
@DisplayName("Synthetic ticks")
class SyntheticTicksTest {

    private static final double TICK = 5.0;

    /** A day of bars with the ranges a minute of WIN really has. */
    private static PriceSeries minutes(int count, long seed) {
        Random random = new Random(seed);
        double[][] bars = new double[count][4];
        double price = 120_000;

        for (int i = 0; i < count; i++) {
            // 4 to 63 ticks: the p5 to p95 of the measured ranges is 7 to 58.
            int span = 4 + random.nextInt(60);
            double low = price - random.nextInt(span + 1) * TICK;
            double high = low + span * TICK;
            double open = low + random.nextInt(span + 1) * TICK;
            double close = low + random.nextInt(span + 1) * TICK;

            bars[i] = new double[]{open, high, low, close};
            price = close;
        }

        return new PriceSeries() {

            @Override
            public int size() {
                return count;
            }

            @Override
            public long timeAt(int index) {
                return 1_756_000_000_000L + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return bars[index][0];
            }

            @Override
            public double highAt(int index) {
                return bars[index][1];
            }

            @Override
            public double lowAt(int index) {
                return bars[index][2];
            }

            @Override
            public double closeAt(int index) {
                return bars[index][3];
            }
        };
    }

    /** One bar, stated. */
    private static PriceSeries bar(double open, double high, double low, double close) {
        return new PriceSeries() {

            @Override
            public int size() {
                return 1;
            }

            @Override
            public long timeAt(int index) {
                return 1_756_000_000_000L;
            }

            @Override
            public double openAt(int index) {
                return open;
            }

            @Override
            public double highAt(int index) {
                return high;
            }

            @Override
            public double lowAt(int index) {
                return low;
            }

            @Override
            public double closeAt(int index) {
                return close;
            }
        };
    }

    @Test
    @DisplayName("o caminho comeca na abertura, termina no fechamento e toca os dois extremos")
    void thePathReproducesTheFourNumbers() {
        // The contract, and the only part of the path that is not invention.
        PriceSeries day = minutes(400, 11);
        SyntheticTicks ticks = new SyntheticTicks(TICK, 7);

        for (int i = 0; i < day.size(); i++) {
            double[] path = ticks.pathFor(day, i);
            double high = Double.NEGATIVE_INFINITY;
            double low = Double.POSITIVE_INFINITY;

            for (double price : path) {
                high = Math.max(high, price);
                low = Math.min(low, price);
            }

            assertEquals(day.openAt(i), path[0], 1e-9, "bar " + i);
            assertEquals(day.closeAt(i), path[path.length - 1], 1e-9, "bar " + i);
            assertEquals(day.highAt(i), high, 1e-9, "bar " + i + ": the high was never reached");
            assertEquals(day.lowAt(i), low, 1e-9, "bar " + i + ": the low was never reached");
        }
    }

    @Test
    @DisplayName("o preco anda UM tick por vez, como 97,1% dos negocios reais")
    void everyStepIsOneTick() {
        // Measured: 97,1% of price changes in the real sessions are a single
        // tick, 2,5% are two, and the mean is 1,038. The Brownian bridge this
        // replaced produced one tick 15,4% of the time -- its steps were any
        // size, because a Gaussian is any size.
        PriceSeries day = minutes(400, 12);
        SyntheticTicks ticks = new SyntheticTicks(TICK, 7);
        long single = 0;
        long all = 0;

        for (int i = 0; i < day.size(); i++) {
            double[] path = ticks.pathFor(day, i);

            for (int at = 1; at < path.length; at++) {
                double moved = Math.abs(path[at] - path[at - 1]);

                if (moved > 0) {
                    all++;

                    if (Math.abs(moved - TICK) < 1e-9) {
                        single++;
                    }
                }
            }
        }

        assertTrue(single / (double) all > 0.95,
                "only " + (100 * single / all) + "% of the steps were one tick");
    }

    @Test
    @DisplayName("o preco volta atras cinco vezes em seis: o repique entre compra e venda")
    void thePriceMostlyTurnsBack() {
        // 17,6% of real changes keep the direction of the one before, against
        // the 50% a fair coin gives and the 28,5% the bridge gave. It is the
        // bid-ask bounce, and it is most of what a minute of WIN is.
        PriceSeries day = minutes(400, 13);
        SyntheticTicks ticks = new SyntheticTicks(TICK, 7);
        long same = 0;
        long pairs = 0;

        for (int i = 0; i < day.size(); i++) {
            double[] path = ticks.pathFor(day, i);
            double last = 0;

            for (int at = 1; at < path.length; at++) {
                double moved = path[at] - path[at - 1];

                if (moved == 0) {
                    continue;
                }

                if (last != 0) {
                    pairs++;

                    if (Math.signum(moved) == Math.signum(last)) {
                        same++;
                    }
                }

                last = moved;
            }
        }

        double keeps = same / (double) pairs;

        assertTrue(keeps > 0.10 && keeps < 0.26,
                "the price kept its direction " + Math.round(100 * keeps)
                        + "% of the time; the market does it 17,6%");
    }

    @Test
    @DisplayName("um minuto de dez ticks muda de preco umas quinhentas vezes, nao vinte e quatro")
    void theCountIsTheMeasuredOne() {
        // The single biggest error of the old rules. 4 + 2 x range gave 24
        // movements for a ten-tick minute and 44 for a twenty-tick one; the
        // sessions say 534 and 1.196.
        SyntheticTicks ticks = new SyntheticTicks(TICK, 7);

        int ten = ticks.countFor(bar(120_000, 120_050, 120_000, 120_050), 0);
        int twenty = ticks.countFor(bar(120_000, 120_100, 120_000, 120_100), 0);

        assertTrue(ten > 420 && ten < 640, "a ten-tick minute was broken into " + ten);
        assertTrue(twenty > 980 && twenty < 1_450,
                "a twenty-tick minute was broken into " + twenty);

        // And a minute that barely moved is still a minute that traded.
        assertTrue(ticks.countFor(bar(120_000, 120_000, 120_000, 120_000), 0) >= 8);
    }

    @Test
    @DisplayName("o preco percorre dezenas de vezes a amplitude da barra, nao uma")
    void theGroundCoveredIsTheMeasuredOne() {
        // The consequence of the two rules above, and an independent check on
        // both: a real minute of range R travels 62 x R. The bridge travelled
        // 10 x R -- it crossed the bar and stopped.
        PriceSeries day = minutes(300, 14);
        SyntheticTicks ticks = new SyntheticTicks(TICK, 7);
        double covered = 0;
        double ranges = 0;

        for (int i = 0; i < day.size(); i++) {
            double[] path = ticks.pathFor(day, i);
            double range = day.highAt(i) - day.lowAt(i);

            if (range <= 0) {
                continue;
            }

            double travel = 0;

            for (int at = 1; at < path.length; at++) {
                travel += Math.abs(path[at] - path[at - 1]);
            }

            covered += travel / range;
            ranges++;
        }

        double each = covered / ranges;

        assertTrue(each > 40 && each < 90,
                "the price covered " + Math.round(each) + " ranges; the market covers 62");
    }

    @Test
    @DisplayName("os extremos aparecem no meio do minuto, nao no primeiro terco")
    void theExtremesAreNotTouchedEarly() {
        // A walk merely bounded by the bar reaches its high and low at once and
        // then rattles along them, which put the first touch at 0,33 of the path
        // against a measured 0,42. This is what the one-tick-short rule buys,
        // and it is the difference between a candle that grows and one that is
        // already full size a third of the way in.
        PriceSeries day = minutes(400, 15);
        SyntheticTicks ticks = new SyntheticTicks(TICK, 7);
        double top = 0;
        double bottom = 0;
        int counted = 0;

        for (int i = 0; i < day.size(); i++) {
            double[] path = ticks.pathFor(day, i);

            if (path.length < 3 || day.highAt(i) == day.lowAt(i)) {
                continue;
            }

            int at = 0;
            int under = 0;

            for (int k = 0; k < path.length; k++) {
                if (path[k] > path[at]) {
                    at = k;
                }

                if (path[k] < path[under]) {
                    under = k;
                }
            }

            top += at / (double) (path.length - 1);
            bottom += under / (double) (path.length - 1);
            counted++;
        }

        double high = top / counted;
        double low = bottom / counted;

        assertTrue(high > 0.32 && high < 0.55,
                "the high was first touched at " + high + " of the path; the market, at 0,42");
        assertTrue(low > 0.32 && low < 0.55,
                "the low was first touched at " + low + " of the path; the market, at 0,46");
    }

    @Test
    @DisplayName("a ordem dos extremos e a usual, mas nao sempre: uma barra em seis vai ao contrario")
    void theOrderIsThrownAndNotAssumed() {
        // The rule used to be certain -- a rising bar ALWAYS made its low first
        // -- and the market is not: 83,2% of rising minutes do, and 88,7% of
        // falling ones make their high first. A replay that never shows the
        // other one in a whole session is showing a market that does not exist.
        PriceSeries day = minutes(1_000, 16);
        SyntheticTicks ticks = new SyntheticTicks(TICK, 7);
        int rising = 0;
        int lowFirst = 0;

        for (int i = 0; i < day.size(); i++) {
            if (day.closeAt(i) < day.openAt(i) || day.highAt(i) == day.lowAt(i)) {
                continue;
            }

            double[] path = ticks.pathFor(day, i);
            int at = 0;
            int under = 0;

            for (int k = 0; k < path.length; k++) {
                if (path[k] > path[at]) {
                    at = k;
                }

                if (path[k] < path[under]) {
                    under = k;
                }
            }

            rising++;

            if (under < at) {
                lowFirst++;
            }
        }

        double usual = lowFirst / (double) rising;

        assertTrue(usual > 0.72 && usual < 0.93,
                "rising bars made their low first " + Math.round(100 * usual)
                        + "% of the time; the market does it 83,2%");
    }

    @Test
    @DisplayName("o caminho nao sai da barra")
    void thePathStaysInside() {
        PriceSeries day = minutes(300, 17);
        SyntheticTicks ticks = new SyntheticTicks(TICK, 7);

        for (int i = 0; i < day.size(); i++) {
            for (double price : ticks.pathFor(day, i)) {
                assertTrue(price <= day.highAt(i) + 1e-9 && price >= day.lowAt(i) - 1e-9,
                        "bar " + i + " went to " + price + ", outside ["
                                + day.lowAt(i) + ", " + day.highAt(i) + "]");
            }
        }
    }

    @Test
    @DisplayName("o mesmo minuto redesenha o mesmo minuto")
    void theSameBarRedrawsTheSamePath() {
        // Scrubbing back and forth over a minute must not invent a new one:
        // the seed mixes in the bar index and nothing else.
        PriceSeries day = minutes(20, 18);
        SyntheticTicks ticks = new SyntheticTicks(TICK, 7);

        for (int i = 0; i < day.size(); i++) {
            assertArrayEquals(ticks.pathFor(day, i), ticks.pathFor(day, i), 1e-9,
                    "bar " + i + " was drawn twice and came out different");
        }
    }
}
