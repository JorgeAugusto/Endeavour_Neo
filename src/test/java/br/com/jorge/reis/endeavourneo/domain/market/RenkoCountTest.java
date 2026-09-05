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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How many trades made a brick, how many contracts they carried, and when the
 * first of them arrived.
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
    @DisplayName("a brick holds the trades that landed in its own band")
    void countedByBand() {
        // Two prints inside the band, then one past 187.100 that closes the
        // brick and belongs to the band ABOVE it.
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(trades(187_000, 187_050, 187_080, 187_110));

        assertEquals(1, bricks.size());
        assertEquals(2, Counted.at(bricks, 0), "the print at 187.110 is not in this brick");
        assertEquals(4.0, bricks.volumeAt(0), 1e-9, "two prints of two contracts");
    }

    @Test
    @DisplayName("the print sitting on the level below belongs to the brick under it")
    void theLevelBelowIsNotMine() {
        // 187.000 is the bottom of this brick's band and the top of the one
        // beneath it, which is where it goes.
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(trades(187_000, 187_050, 187_110));

        assertEquals(1, Counted.at(bricks, 0));
    }

    @Test
    @DisplayName("a brick is stamped with its FIRST trade, not with the one that closed it")
    void stampedAtTheFirstTrade() {
        PriceSeries source = trades(187_000, 187_050, 187_080, 187_110);
        PriceSeries bricks = new Renko(100, 2, false).apply(source);

        assertEquals(source.timeAt(1), bricks.timeAt(0),
                "the brick was stamped with the print that closed it");
    }

    @Test
    @DisplayName("a brick nobody traded in keeps the moment it was created")
    void aGapBrickKeepsItsBirthday() {
        PriceSeries source = trades(187_000, 188_110);
        PriceSeries bricks = new Renko(100, 2, false).apply(source);

        assertTrue(bricks.size() > 1, "the jump should have laid a run of bricks");

        for (int i = 0; i < bricks.size(); i++) {
            assertEquals(0, Counted.at(bricks, i), "brick " + i + " holds no trade");
            assertTrue(Untraded.at(bricks, i), "brick " + i + " should be marked");
            assertEquals(source.timeAt(1), bricks.timeAt(i));
        }
    }

    @Test
    @DisplayName("the stamps never run backwards")
    void stampsNeverGoBack() {
        // One bar lays two bricks at once and each takes its own band. Here the
        // LOWER band was traded first, so its brick would be stamped earlier
        // than the one above it -- and the time axis would run backwards.
        PriceSeries bricks = new Renko(100, 2, false)
                .apply(trades(187_050, 187_110, 186_950, 187_050, 186_890));

        assertEquals(3, bricks.size());

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
                    "a minute is a summary of trades at prices it never named");
        }

        // And the volume is still shared out, which is the convention this has
        // always used for candles -- named as one in Renko's own documentation.
        double total = 0.0;

        for (int i = 0; i < bricks.size(); i++) {
            total += bricks.volumeAt(i);
        }

        assertEquals(100.0, total, 1e-9);
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
