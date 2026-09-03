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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bricks, and the three rules that decide when one is laid.
 */
@DisplayName("Renko")
class RenkoTest {

    /** One bar per minute from 09:00, closing at each price given. */
    private static PriceSeries closes(double... prices) {
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

    @Test
    @DisplayName("a brick is laid every time price travels its height")
    void oneBrickPerHeight() {
        // From 100, bricks of 10: 110, 120, 130.
        PriceSeries bricks = Renko.of(10).apply(closes(100, 105, 110, 118, 121, 133));

        assertEquals(3, bricks.size());
        assertEquals(100.0, bricks.openAt(0));
        assertEquals(110.0, bricks.closeAt(0));
        assertEquals(130.0, bricks.closeAt(2));
    }

    @Test
    @DisplayName("a move of three heights lays three bricks, not one tall one")
    void aBigMoveIsManyBricks() {
        // What makes the chart show the SIZE of a move as a length. One tall
        // brick would put the size back on the axis, to be read rather than seen.
        PriceSeries bricks = Renko.of(10).apply(closes(100, 135));

        assertEquals(3, bricks.size());
        assertEquals(130.0, bricks.closeAt(2), "the leftover five points wait for the next brick");
    }

    @Test
    @DisplayName("noise below the brick height lays nothing at all")
    void noiseIsInvisible() {
        // The entire purpose. Nine points of thrashing, in a ten-point brick,
        // is not a chart event.
        assertEquals(0, Renko.of(10).apply(closes(100, 108, 101, 109, 102, 107)).size());
    }

    @Test
    @DisplayName("turning round costs two bricks, carrying on costs one")
    void reversalCostsMore() {
        // Up to 110, then down. Nine points down is not enough to turn -- with a
        // one-brick rule it would be, and the chart would fill with alternating
        // bricks around a single level.
        PriceSeries bricks = Renko.of(10).apply(closes(100, 110, 101));

        assertEquals(1, bricks.size(), "a nine-point pullback must not turn the trend");

        // Twenty points down does turn it, and lays both bricks.
        PriceSeries turned = Renko.of(10).apply(closes(100, 110, 90));

        assertEquals(3, turned.size());
        assertEquals(110.0, turned.openAt(1), "the turn starts where the last brick left off");
        assertEquals(90.0, turned.closeAt(2));
    }

    @Test
    @DisplayName("with a one-brick reversal every crossing draws a brick")
    void reversalOfOne() {
        PriceSeries bricks = new Renko(10, 1).apply(closes(100, 110, 100, 110));

        assertEquals(3, bricks.size(), "a one-brick rule turns on every crossing");
    }

    /** Bars given as {open, high, low, close}, one per minute. */
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

    @Test
    @DisplayName("a brick with nothing fought against it has no tail")
    void noExcursionMeansNoTail() {
        PriceSeries bricks = Renko.of(10).apply(closes(100, 110));

        assertEquals(110.0, bricks.highAt(0));
        assertEquals(100.0, bricks.lowAt(0), "a tail appeared where price never went");
    }

    @Test
    @DisplayName("the tail shows how far price went the other way first")
    void tailShowsTheFightBeforeTheBreak() {
        // Price dips to 85 before going through 110. The brick still runs
        // 100 to 110; the tail says the move was fought for fifteen points.
        PriceSeries bricks = Renko.of(10).apply(ohlc(
                new double[]{100, 100, 85, 90},
                new double[]{90, 112, 90, 112}));

        assertTrue(bricks.size() >= 1, "no brick was laid at all");

        double lowestTail = bricks.lowAt(0);

        assertTrue(lowestTail <= 85.0,
                "the brick forgot the fifteen points price went the other way: " + lowestTail);
    }

    @Test
    @DisplayName("turning the tails off leaves the bricks bare")
    void tailsCanBeTurnedOff() {
        PriceSeries[] bars = {ohlc(
                new double[]{100, 100, 85, 90},
                new double[]{90, 112, 90, 112})};

        PriceSeries bare = new Renko(10, 2, false).apply(bars[0]);

        for (int i = 0; i < bare.size(); i++) {
            assertEquals(Math.min(bare.openAt(i), bare.closeAt(i)), bare.lowAt(i), 1e-9,
                    "a bare brick must be exactly its own two levels");
            assertEquals(Math.max(bare.openAt(i), bare.closeAt(i)), bare.highAt(i), 1e-9);
        }
    }

    @Test
    @DisplayName("tails are on unless asked otherwise, and the switch says which")
    void tailsAreOnByDefault() {
        assertTrue(Renko.of(10).hasWicks());
        assertTrue(new Renko(10, 2).hasWicks());
        assertTrue(Renko.of(10).withWicks(false).hasWicks() == false);
        assertEquals("10 renko", Renko.of(10).label());
        assertEquals("10 renko sem calda", Renko.of(10).withWicks(false).label());
    }

    @Test
    @DisplayName("bricks completed by the same bar share its time")
    void bricksShareTheirBarsTime() {
        // Three bricks from one minute really did all happen inside that minute.
        // Spreading them over invented timestamps would be the only alternative,
        // and it would be fiction.
        PriceSeries source = closes(100, 135);
        PriceSeries bricks = Renko.of(10).apply(source);

        assertEquals(source.timeAt(1), bricks.timeAt(0));
        assertEquals(source.timeAt(1), bricks.timeAt(2));
    }

    @Test
    @DisplayName("the first brick is measured from where the data starts")
    void anchoredOnTheFirstClose() {
        // Not on a rounded grid the series never touched: starting at 137 with
        // ten-point bricks, the first brick top is 147, not 140.
        assertEquals(147.0, Renko.of(10).apply(closes(137, 148)).closeAt(0));
    }

    @Test
    @DisplayName("a series with no volume gives bricks with no volume")
    void absentVolumeStaysAbsent() {
        assertTrue(Double.isNaN(Renko.of(10).apply(closes(100, 110)).volumeAt(0)));
    }

    @Test
    @DisplayName("an empty or impossible request is refused or answered empty")
    void edges() {
        assertEquals(0, Renko.of(10).apply(PriceSeries.empty()).size());
        assertEquals(0, Renko.of(10).apply(null).size());
        assertThrows(IllegalArgumentException.class, () -> Renko.of(0));
        assertThrows(IllegalArgumentException.class, () -> Renko.of(-5));
        assertThrows(IllegalArgumentException.class, () -> new Renko(10, 0));
    }

    @Test
    @DisplayName("the label says what it is, without a pointless decimal")
    void label() {
        assertEquals("30 renko", Renko.of(30).label());
        assertEquals("2.5 renko", Renko.of(2.5).label());
    }
}
