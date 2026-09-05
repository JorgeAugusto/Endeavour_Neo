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
        // higher. That one print CLOSES the brick that had been forming since
        // yesterday -- so the first of the ten carries yesterday's trade -- and
        // creates the other nine on its way past, in an instant, empty.
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(trades(187_000, 188_100, 188_200));

        assertEquals("#.........#", marks(bricks));

        assertEquals(187_000, bricks.openAt(0), 1e-9);
        assertEquals(187_100, bricks.closeAt(0), 1e-9,
                "the coloured brick is the one the jump closed, not one it created");
    }

    @Test
    @DisplayName("a gap down is the mirror of a gap up")
    void openingGapDown() {
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(trades(188_000, 186_950, 186_890));

        assertEquals("#.........#", marks(bricks));
    }

    @Test
    @DisplayName("candles are never marked, because they cannot be counted")
    void aFastMinuteIsNotAGap() {
        // Four bricks of range inside one bar. A minute is a summary of trades
        // at prices and times it does not report, so nothing here can be called
        // untraded -- unknown is not zero. See Counted.
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(ohlc(new double[]{187_000, 187_000, 187_000, 187_000},
                        new double[]{187_010, 187_450, 187_000, 187_400}));

        assertEquals("####", marks(bricks));
    }

    @Test
    @DisplayName("after the jump, trading resumes and the bricks colour again")
    void gapThenTrading() {
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(trades(187_000, 187_050, 188_100, 188_130, 188_210));

        // Two prints, then a jump that closes their brick and creates nine
        // empty ones, then two prints that each close one of their own.
        assertEquals("#.........##", marks(bricks));
    }

    @Test
    @DisplayName("what was traded before the gap belongs to the brick it closed")
    void whatWasSeenBeforeCounts() {
        // Price drifts to 187.100 and then to 186.910 without drawing anything,
        // so three prints are waiting when the drop finally comes. They belong
        // to the brick that drop CLOSED -- the first one -- and the eight it
        // created on the way down hold nothing.
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
                "the trades waiting overnight belong to the brick the morning closed");
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
