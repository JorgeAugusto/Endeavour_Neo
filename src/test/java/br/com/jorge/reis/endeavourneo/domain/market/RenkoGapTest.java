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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which bricks nobody traded through, and -- the point of the whole thing --
 * which was the first one somebody did.
 */
@DisplayName("Renko sobre um gap")
class RenkoGapTest {

    /** One bar per minute, each a single trade at that price: what ticks look like. */
    private static PriceSeries trades(double... prices) {
        return new PriceSeries() {

            @Override
            public int size() {
                return prices.length;
            }

            @Override
            public long timeAt(int index) {
                return 1_756_000_000_000L + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return prices[index];
            }

            @Override
            public double highAt(int index) {
                return prices[index];
            }

            @Override
            public double lowAt(int index) {
                return prices[index];
            }

            @Override
            public double closeAt(int index) {
                return prices[index];
            }
        };
    }

    /** Bars of {open, high, low, close}: what candles look like. */
    private static PriceSeries ohlc(double[]... bars) {
        return new PriceSeries() {

            @Override
            public int size() {
                return bars.length;
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

    /** A brick nobody traded through is a dot; a traded one is a hash. */
    private static String marks(PriceSeries bricks) {
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < bricks.size(); i++) {
            out.append(Untraded.at(bricks, i) ? '.' : '#');
        }

        return out.toString();
    }

    @Test
    @DisplayName("a walk with no gap in it marks nothing")
    void steadyClimbIsAllTraded() {
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(trades(187_000, 187_100, 187_200, 187_300, 187_200, 187_100));

        assertTrue(bricks.size() > 0, "the walk should have laid bricks");
        assertEquals("#".repeat(bricks.size()), marks(bricks),
                "every brick here was walked through one price at a time");
    }

    @Test
    @DisplayName("the gap is marked, and the brick holding the first trade is not")
    void openingGapUp() {
        // Yesterday traded at 187.000 and the first print of the day is 1.100
        // higher. Ten bricks bridge it and nobody paid a price inside any of
        // them: yesterday's 187.000 belongs to the brick BELOW the first, which
        // was never laid, and today's print is above the last.
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(trades(187_000, 188_100, 188_200));

        assertEquals("..........#", marks(bricks));

        assertEquals(188_000, bricks.openAt(10), 1e-9);
        assertEquals(188_100, bricks.closeAt(10), 1e-9,
                "the first coloured brick is the one whose band holds the print");
    }

    @Test
    @DisplayName("a gap down is the mirror of a gap up")
    void openingGapDown() {
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(trades(188_000, 186_950, 186_890));

        // Every band from 188.000 down to 187.000 is empty: the 188.000 print
        // belongs to the band above it and the 186.950 print is below them all.
        // The last brick holds 186.950 and is coloured.
        assertEquals("..........#", marks(bricks));
    }

    @Test
    @DisplayName("a minute that ran the same distance is NOT a gap")
    void aFastMinuteIsNotAGap() {
        // Four bricks of range inside one bar, traded through. The same brick
        // count as a 400-point gap, and nothing about it is a gap.
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(ohlc(new double[]{187_000, 187_000, 187_000, 187_000},
                        new double[]{187_010, 187_450, 187_000, 187_400}));

        assertEquals("####", marks(bricks));
    }

    @Test
    @DisplayName("a bar that gaps AND then trades marks only the part it jumped")
    void gapThenRange() {
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(ohlc(new double[]{187_000, 187_000, 187_000, 187_000},
                        new double[]{187_990, 188_250, 187_980, 188_200}));

        // Up to 187.900 was jumped; from the low of that bar at 187.980 upwards
        // it was traded.
        assertEquals(".........###", marks(bricks));
    }

    @Test
    @DisplayName("what was traded before the gap is not part of it")
    void whatWasSeenBeforeCounts() {
        // Price drifts to 187.100 and then to 186.910 without drawing anything
        // -- a reversal of two needs more than that -- so the market really
        // traded down there while the ruler stayed put. Then the gap. The brick
        // covering the ground price actually walked has to stay coloured.
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(trades(187_000, 187_100, 186_910, 186_000, 185_990));

        assertEquals("#........#", marks(bricks));
    }

    @Test
    @DisplayName("the mark survives being built one session at a time")
    void carriedAcrossSessions() {
        Renko renko = new Renko(100, 2, false);

        Renko.Continued first = renko.applyFrom(trades(187_000, 187_050, 187_110), null);
        Renko.Continued second = renko.applyFrom(trades(188_100, 188_200), first.carry());

        assertEquals("#", marks(first.bricks()));
        assertEquals("#........#", marks(second.bricks()),
                "the night is a gap even though each session is folded on its own");
    }

    @Test
    @DisplayName("splitting the source does not change which bricks are marked")
    void wholeAndHalvesAgree() {
        Renko renko = new Renko(100, 2, false);
        double[] path = {187_000, 187_100, 188_400, 188_500, 188_300, 187_200};

        PriceSeries whole = renko.apply(trades(path));

        Renko.Continued head = renko.applyFrom(trades(187_000, 187_100, 188_400), null);
        Renko.Continued tail = renko.applyFrom(
                trades(188_500, 188_300, 187_200), head.carry());

        assertEquals(marks(whole), marks(head.bricks()) + marks(tail.bricks()));
    }

    @Test
    @DisplayName("a print on a level belongs to the brick that closes there")
    void aPriceOnTheEdgeStaysBelow() {
        // Reaching the level is not passing it, so nothing is drawn yet.
        assertEquals(0, new Renko(100, 2, false).apply(trades(187_000, 187_100)).size(),
                "a move of exactly one brick does not close one");

        // Now price passes 187.200, so two bricks are drawn at once. The print
        // that sat on 187.100 belongs to the FIRST of them -- the one closing
        // there -- and the second holds nothing at all.
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(trades(187_000, 187_100, 187_210));

        assertEquals("#.", marks(bricks));
    }

    @Test
    @DisplayName("the forming brick is never a gap")
    void formingIsNeverAGap() {
        PriceSeries bricks = new Renko(100, 2, false, true)
                .apply(trades(187_000, 188_100));

        assertFalse(Untraded.at(bricks, bricks.size() - 1),
                "the brick still being built is where the price is");
    }

    @Test
    @DisplayName("anything that is not renko says no, and means it does not know")
    void plainSeriesAreNotMarked() {
        assertFalse(Untraded.at(trades(187_000, 187_100), 0));
    }
}
