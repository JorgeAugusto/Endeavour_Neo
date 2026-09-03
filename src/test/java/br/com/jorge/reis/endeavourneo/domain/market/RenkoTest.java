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
    @DisplayName("a level TOUCHED lays a brick, even if the close comes back")
    void touchingTheLevelIsEnough() {
        // The discriminating case. Price reaches 115 and closes back at 102.
        // Reading closes alone, nothing happened; reading what was reached, the
        // level went through and the brick belongs on the chart.
        PriceSeries bricks = Renko.of(10).apply(ohlc(
                new double[]{100, 100, 100, 100},
                new double[]{100, 115, 100, 102}));

        assertEquals(1, bricks.size(),
                "the level was reached and no brick was laid: this is the closes-only reading");
        assertEquals(110.0, bricks.closeAt(0));
    }

    @Test
    @DisplayName("a brick already laid is never taken away as the bar goes on")
    void bricksAreNeverUnlaid() {
        // The defect, as a property. A forming bar's extremes only widen, so
        // rebuilding the renko from scratch on every frame must never come back
        // with fewer bricks than the frame before.
        double[][] forming = {
                {100, 100, 100, 100},
                {100, 100, 100, 100},
        };

        int most = 0;

        // The second bar forms: its high climbs, then its low drops, then the
        // close wanders -- exactly what a replay feeds in.
        double[][] steps = {
                {100, 104, 99, 103}, {100, 112, 99, 111}, {100, 112, 99, 101},
                {100, 116, 95, 102}, {100, 124, 88, 90}, {100, 124, 85, 121},
        };

        for (double[] step : steps) {
            forming[1] = step;

            int now = Renko.of(10).apply(ohlc(forming[0], forming[1])).size();

            assertTrue(now >= most,
                    "the chart lost a brick it had already drawn: " + most + " then " + now);

            most = Math.max(most, now);
        }

        assertTrue(most > 0, "no brick was ever laid, so the check proved nothing");
    }

    @Test
    @DisplayName("a brick with nothing fought against it has no tail")
    void noExcursionMeansNoTail() {
        PriceSeries bricks = Renko.of(10).apply(closes(100, 110));

        assertEquals(110.0, bricks.highAt(0));
        assertEquals(100.0, bricks.lowAt(0), "a tail appeared where price never went");
    }

    @Test
    @DisplayName("the tail shows how far price went the other way before breaking")
    void tailShowsTheFightBeforeTheBreak() {
        // Anchored at 100. The second bar runs down to 93 and then up through
        // 110: the brick was fought seven points the other way, and its tail
        // has to say so.
        //
        // Seven, not twelve. The first draft fought twelve, which at a brick of
        // ten and no trend yet is not a fight but a DOWN brick, 100 to 90 -- and
        // the 88 the test then saw was that brick's overshoot, back when the
        // overshoot was drawn. The test passed on a tail that meant the
        // opposite of what it claimed.
        PriceSeries bricks = Renko.of(10).apply(ohlc(
                new double[]{100, 101, 100, 100},
                new double[]{100, 112, 93, 112}));

        assertTrue(bricks.size() >= 1, "no brick was laid at all");
        assertEquals(110.0, bricks.closeAt(0), 1e-9, "the first brick should be the up brick");

        double firstTail = bricks.lowAt(0);

        assertTrue(firstTail <= 93.0,
                "the brick forgot the seven points price went the other way: " + firstTail);
    }

    @Test
    @DisplayName("a brick ends at its own extreme: the overshoot is not a tail")
    void noTailPastTheClose() {
        // Price reaches 117 and only 110 became a brick. The seven points that
        // went through are not drawn -- the brick ends at 110, as it does in the
        // Profit. The first version kept them as a tail, and a brick that ended
        // a little beyond its own close read as wrong on screen.
        PriceSeries bricks = Renko.of(10).apply(ohlc(
                new double[]{100, 100, 100, 100},
                new double[]{100, 117, 100, 112}));

        double top = bricks.highAt(bricks.size() - 1);

        assertEquals(110.0, top, 1e-9, "the brick reaches past its own close: " + top);
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
    @DisplayName("the brick being built is off unless asked for")
    void formingIsOptOut() {
        // Off by default because a partial brick is NOT a brick: it grows,
        // shrinks and can vanish, and a backtest counting bricks must not
        // count it as one. The chart asks for it; measurement does not.
        PriceSeries measured = Renko.of(10).apply(closes(100, 110, 113));
        PriceSeries drawn = Renko.of(10).withForming(true).apply(closes(100, 110, 113));

        assertEquals(1, measured.size());
        assertEquals(2, drawn.size(), "the brick under construction was not drawn");

        // And the completed brick is the same one either way.
        assertEquals(measured.closeAt(0), drawn.closeAt(0));
    }

    @Test
    @DisplayName("the brick being built runs from the last level to the price now")
    void formingRunsFromTheAnchor() {
        // This is the only thing on a renko chart that moves. Without it,
        // nothing changes between one brick and the next and a replay looks
        // frozen -- measured at 0,5% of frames against a candle chart's 1,7%.
        PriceSeries drawn = Renko.of(10).withForming(true).apply(closes(100, 110, 116));

        assertEquals(110.0, drawn.openAt(1), "it must start where the last brick ended");
        assertEquals(116.0, drawn.closeAt(1), "it must end at the price right now");
    }

    @Test
    @DisplayName("the brick under construction is ALWAYS there, even at no height")
    void formingIsAlwaysDrawn() {
        // This assertion used to say the opposite, and that was the defect,
        // reported from the screen as the chart trembling: price wanders across
        // the level, the partial brick appears and vanishes, the BAR COUNT
        // changes and the whole chart shifts sideways by one bar.
        //
        // A brick of no height is a flat mark at the level, which is what price
        // sitting on the level looks like. A bar that never comes and goes is
        // worth more than one that is always meaningful.
        assertEquals(2, Renko.of(10).withForming(true).apply(closes(100, 110)).size(),
                "the brick under construction went missing when it had no height");
    }

    @Test
    @DisplayName("the bar count never falls while a bar is still forming")
    void theCountNeverFalls() {
        // The trembling, as a property. The ruler is anchored on the first
        // bar's OPEN, which never moves; anchoring it on the first CLOSE was the
        // defect -- while that bar formed, its close wandered and every brick
        // was measured from a shifting origin. Instrumented at the time: the
        // same bar, the same high, the same low, and the completed bricks going
        // from four to two.
        double[] first = {100, 100, 100, 100};
        int most = 0;

        for (double[] step : new double[][]{
                {100, 104, 96, 103}, {100, 112, 96, 111}, {100, 112, 96, 101},
                {100, 116, 92, 93}, {100, 124, 88, 121}, {100, 124, 85, 90}}) {

            int now = Renko.of(10).withForming(true).apply(ohlc(first, step)).size();

            assertTrue(now >= most, "the chart lost a bar it had already drawn: "
                    + most + " then " + now);

            most = Math.max(most, now);
        }
    }

    @Test
    @DisplayName("tails are on unless asked otherwise, and the switch says which")
    void tailsAreOnByDefault() {
        assertTrue(Renko.of(10).hasWicks());
        assertTrue(new Renko(10, 2).hasWicks());
        assertTrue(Renko.of(10).withWicks(false).hasWicks() == false);

        // The two switches are independent: changing one must not clear the other.
        assertTrue(Renko.of(10).withForming(true).withWicks(false).hasForming());
        assertTrue(Renko.of(10).withWicks(false).withForming(true).hasWicks() == false);
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
    @DisplayName("the ruler starts at the first OPEN, the one price that never moves")
    void anchoredOnTheFirstOpen() {
        // Not on a rounded grid the series never touched: starting at 137 with
        // ten-point bricks, the first brick top is 147, not 140.
        assertEquals(147.0, Renko.of(10).apply(closes(137, 148)).closeAt(0));

        // The OPEN and not the close: while a bar forms its close moves, and a
        // ruler that moves with it measures every brick from a shifting origin.
        assertEquals(100.0, Renko.of(10).apply(ohlc(new double[]{100, 130, 100, 125})).openAt(0),
                "the ruler started somewhere other than the first open");
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
