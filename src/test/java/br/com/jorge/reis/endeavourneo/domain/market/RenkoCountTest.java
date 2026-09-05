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
 * How many trades made a brick, how many contracts they carried, and when the
 * first of them arrived.
 *
 * <p>The rule under all of it: <b>a brick holds everything that traded while it
 * was the one being built</b> — by time, not by price band. See {@link
 * TradeTally}, where it is measured against the reference product.</p>
 */
@DisplayName("Negocios por tijolo")
class RenkoCountTest {

    private static final long OPENED = 1_756_000_000_000L;

    /** One trade a second, each of two contracts, at the prices given. */
    private static PriceSeries trades(double... prices) {
        return new PriceSeries() {

            @Override
            public int size() {
                return prices.length;
            }

            @Override
            public long timeAt(int index) {
                return OPENED + index * 1_000L;
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

            @Override
            public double volumeAt(int index) {
                return 2.0;
            }
        };
    }

    /** Bars of {open, high, low, close, volume}: what candles look like. */
    private static PriceSeries candles(double[]... bars) {
        return new PriceSeries() {

            @Override
            public int size() {
                return bars.length;
            }

            @Override
            public long timeAt(int index) {
                return OPENED + index * 60_000L;
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

            @Override
            public double volumeAt(int index) {
                return bars[index][4];
            }
        };
    }

    @Test
    @DisplayName("a brick holds every trade that arrived while it was forming")
    void countedByTheStretch() {
        // Three prints, then a fourth that takes price past 187.100 and closes
        // the brick. The three belong to it; the fourth starts the next one.
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(trades(187_000, 187_050, 187_080, 187_110));

        assertEquals(1, bricks.size());
        assertEquals(3, Counted.at(bricks, 0));
        assertEquals(6.0, bricks.volumeAt(0), 1e-9, "three prints of two contracts");
    }

    @Test
    @DisplayName("the print that closed a brick belongs to the next one")
    void theClosingPrintStartsTheNext() {
        PriceSeries source = trades(187_000, 187_050, 187_110, 187_130, 187_210);
        PriceSeries bricks = new Renko(100, 2, false).apply(source);

        assertEquals(2, bricks.size());
        assertEquals(2, Counted.at(bricks, 0), "the print at 187.110 is not in the first");
        assertEquals(2, Counted.at(bricks, 1), "and it is in the second");
        assertEquals(source.timeAt(2), bricks.timeAt(1),
                "the second brick starts at the print that closed the first");
    }

    @Test
    @DisplayName("a brick is stamped with its FIRST trade, not with the one that closed it")
    void stampedAtTheFirstTrade() {
        PriceSeries source = trades(187_000, 187_050, 187_080, 187_110);
        PriceSeries bricks = new Renko(100, 2, false).apply(source);

        assertEquals(source.timeAt(0), bricks.timeAt(0),
                "the brick was stamped with the print that closed it");
    }

    @Test
    @DisplayName("a jump fills the brick it closed and leaves the rest empty")
    void aJumpLeavesEmptyBricksBehind() {
        PriceSeries source = trades(187_000, 188_110);
        PriceSeries bricks = new Renko(100, 2, false).apply(source);

        assertTrue(bricks.size() > 1, "the jump should have laid a run of bricks");

        assertEquals(1, Counted.at(bricks, 0), "the brick the jump closed holds the print");
        assertFalse(Untraded.at(bricks, 0));
        assertEquals(source.timeAt(0), bricks.timeAt(0));

        for (int i = 1; i < bricks.size(); i++) {
            assertEquals(0, Counted.at(bricks, i), "brick " + i + " was passed through");
            assertTrue(Untraded.at(bricks, i), "brick " + i + " should be marked");
            assertEquals(source.timeAt(1), bricks.timeAt(i),
                    "a brick nobody traded in keeps the moment it was created");
        }
    }

    @Test
    @DisplayName("the stamps never run backwards")
    void stampsNeverGoBack() {
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(trades(187_050, 187_110, 187_220, 186_890, 186_780, 187_010, 187_330));

        assertTrue(bricks.size() > 2, "the path should have turned at least once");

        for (int i = 1; i < bricks.size(); i++) {
            assertTrue(bricks.timeAt(i) >= bricks.timeAt(i - 1),
                    "brick " + i + " is stamped before the one before it");
        }
    }

    @Test
    @DisplayName("candles cannot be counted, and say so instead of saying zero")
    void candlesAnswerUnknown() {
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(candles(new double[]{187_000, 187_000, 187_000, 187_000, 10},
                        new double[]{187_010, 187_450, 187_000, 187_400, 90}));

        assertTrue(bricks.size() > 0, "the minute should have laid bricks");

        for (int i = 0; i < bricks.size(); i++) {
            assertEquals(Counted.UNKNOWN, Counted.at(bricks, i),
                    "a minute is a summary of trades at times it never named");
            assertFalse(Untraded.at(bricks, i),
                    "unknown is not zero: a candle cannot say a brick was empty");
        }

        // The volume still goes to the brick that was being built. The minute
        // that laid these is not in it -- it belongs to the one now forming.
        assertEquals(10.0, bricks.volumeAt(0), 1e-9);
    }

    @Test
    @DisplayName("the counts survive being folded one stretch at a time")
    void countsCarryAcrossAFold() {
        Renko renko = new Renko(100, 2, false);
        PriceSeries whole = renko.apply(trades(187_000, 187_050, 187_080, 187_110));

        Renko.Continued head = renko.applyFrom(trades(187_000, 187_050), null);
        Renko.Continued tail = renko.applyFrom(trades(187_080, 187_110), head.carry());

        assertEquals(0, head.bricks().size(), "nothing closes in the first stretch");
        assertEquals(Counted.at(whole, 0), Counted.at(tail.bricks(), 0),
                "the trades of the first stretch were lost at the seam");
    }

    @Test
    @DisplayName("anything that is not a counted renko says unknown")
    void plainSeriesCannotCount() {
        assertEquals(Counted.UNKNOWN, Counted.at(trades(187_000, 187_100), 0));
    }
}
