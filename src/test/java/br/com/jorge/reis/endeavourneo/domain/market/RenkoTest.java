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
    @DisplayName("turning round costs two bricks of distance and draws one")
    void reversalCostsMore() {
        // Past 110, then down. Nine points down is not enough to turn -- with a
        // one-brick rule it would be, and the chart would fill with alternating
        // bricks around a single level.
        //
        // 111 and not 110, here and below: a brick closes when price goes PAST
        // its level. See Renko.steps.
        PriceSeries bricks = Renko.of(10).apply(closes(100, 111, 101));

        assertEquals(1, bricks.size(), "a nine-point pullback must not turn the trend");

        // Past twenty points down does turn it -- and draws ONE brick, starting
        // one brick away from where the last one closed. That offset is what
        // the reference product draws, and it is measured: on 02/09/2026 its
        // last two boxes at 100 points both OPEN at 188.000, one closing at
        // 187.900 and the next at 188.100, which nothing else can produce.
        PriceSeries turned = Renko.of(10).apply(closes(100, 111, 89));

        assertEquals(2, turned.size(), "a turn draws one brick, not two");
        assertEquals(100.0, turned.openAt(1), "the turn starts one brick away from 110");
        assertEquals(90.0, turned.closeAt(1));
    }

    @Test
    @DisplayName("with a one-brick reversal every crossing draws a brick")
    void reversalOfOne() {
        PriceSeries bricks = new Renko(10, 1).apply(closes(100, 111, 99, 111));

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
    @DisplayName("a level PASSED lays a brick, even if the close comes back")
    void passingTheLevelIsWhatCounts() {
        // The name used to say TOUCHED, which is the opposite of the rule this
        // project measured against the reference product: a brick completes when
        // price goes PAST the level, and the arithmetic that says so subtracts an
        // epsilon precisely so that landing exactly on it does not count. The
        // fixture never noticed, because 115 is past 110 either way.
        //
        // What is being pinned here is the other half: reading closes alone,
        // nothing happened -- the close came back to 102. Reading what was
        // REACHED, the level went through and the brick belongs on the chart.
        PriceSeries bricks = Renko.of(10).apply(ohlc(
                new double[]{100, 100, 100, 100},
                new double[]{100, 115, 100, 102}));

        assertEquals(1, bricks.size(),
                "the level was passed and no brick was laid: this is the closes-only reading");
        assertEquals(110.0, bricks.closeAt(0));
    }

    @Test
    @DisplayName("landing EXACTLY on the level lays nothing")
    void touchingTheLevelIsNotEnough() {
        // The case the test above claimed to be about and never exercised. The
        // rule measured against the reference product is that price has to go
        // PAST the level, which is why the count subtracts an epsilon before
        // taking the ceiling. Reaching 110 exactly, from 100, with a brick of
        // 10, is reaching the level and not passing it.
        //
        // Without this, that epsilon could be deleted and every renko test in
        // this file would stay green.
        PriceSeries bricks = Renko.of(10).apply(ohlc(
                new double[]{100, 100, 100, 100},
                new double[]{100, 110, 100, 105}));

        assertEquals(0, bricks.size(),
                "price stopped exactly on the level and a brick was laid anyway");

        // And one tick past it does lay one, so the fixture is sensitive at the
        // boundary rather than merely quiet.
        assertEquals(1, Renko.of(10).apply(ohlc(
                new double[]{100, 100, 100, 100},
                new double[]{100, 110.01, 100, 105})).size(),
                "price went past the level and nothing was laid");
    }

    /** The ohlc fixture, with a volume on every bar. */
    private static PriceSeries withVolume(double volume, double[]... bars) {
        PriceSeries plain = ohlc(bars);

        return new PriceSeries() {

            @Override
            public int size() {
                return plain.size();
            }

            @Override
            public long timeAt(int index) {
                return plain.timeAt(index);
            }

            @Override
            public double openAt(int index) {
                return plain.openAt(index);
            }

            @Override
            public double highAt(int index) {
                return plain.highAt(index);
            }

            @Override
            public double lowAt(int index) {
                return plain.lowAt(index);
            }

            @Override
            public double closeAt(int index) {
                return plain.closeAt(index);
            }

            @Override
            public double volumeAt(int index) {
                return volume;
            }
        };
    }

    @Test
    @DisplayName("the volume of a batch goes WHOLE to its first brick")
    void volumeGoesToTheFirstBrickOfABatch() {
        // Rule seven, and until now nothing checked it -- which is how the class
        // javadoc came to say the opposite, twice, one of them listing the split
        // as a difference from ta4j that does not exist.
        //
        // A jump to 140 over a brick of ten lays THREE bricks from one bar, not
        // four: 110, 120 and 130 are passed, and 140 is only reached. That is
        // rule four again, and the number is left as three here on purpose --
        // writing four is the mistake this fixture is shaped to catch.
        //
        // That bar's volume arrived by the time the first of them completed;
        // which part of it belonged to which brick is not something a minute
        // says, so any division would be invented. The convention is to give it
        // whole to the first.
        PriceSeries bricks = Renko.of(10).apply(withVolume(1_000,
                new double[]{100, 100, 100, 100},
                new double[]{100, 140, 100, 140}));

        assertEquals(3, bricks.size(),
                "reaching 140 passes 110, 120 and 130, and only reaches 140");
        assertEquals(1_000.0, bricks.volumeAt(0), 1e-9,
                "the first brick of the batch did not get the volume");

        for (int brick = 1; brick < bricks.size(); brick++) {
            assertEquals(0.0, bricks.volumeAt(brick), 1e-9,
                    "brick " + brick + " was given a share of a volume that arrived before it");
        }
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
        PriceSeries bricks = Renko.of(10).apply(closes(100, 111));

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
        // One brick laid, then price back exactly on the level: the brick being
        // built has no height at all, and it still has to be drawn.
        assertEquals(2, Renko.of(10).withForming(true).apply(closes(100, 111, 110)).size(),
                "the brick under construction went missing when it had no height");
    }

    @Test
    @DisplayName("the bar count never falls while a bar is still forming")
    void theCountNeverFalls() {
        // THE TREMBLING, as a property, and only that. The count of completed
        // bricks may not fall as the last bar grows.
        //
        // What this does NOT hold is the anchor. The comment here used to claim
        // it did -- "the ruler is anchored on the first bar's OPEN, which never
        // moves" -- and it does not: moving the anchor to closeAt(0) leaves this
        // test green, because monotonicity is true either way. The anchor is
        // held by anchoredOnTheFirstOpen below, which is where that break
        // actually lands.
        //
        // The fixture still opens away from its close, so it cannot pass by the
        // two answers being the same number. It used to be {100, 100, 100, 100},
        // where an anchor on the open and one on the close start from 100 alike.
        double[] first = {100, 130, 100, 125};
        int most = 0;

        for (double[] step : new double[][]{
                {100, 104, 96, 103}, {100, 112, 96, 111}, {100, 112, 96, 101},
                {100, 116, 92, 93}, {100, 124, 88, 121}, {100, 124, 85, 90}}) {

            int now = Renko.of(10).withForming(true).apply(ohlc(first, step)).size();

            assertTrue(now >= most, "the chart lost a bar it had already drawn: "
                    + most + " then " + now);

            most = Math.max(most, now);
        }

        // Or an empty series would satisfy every step: 0 >= 0, twenty times
        // over. The brother test bricksAreNeverUnlaid closes exactly this hole
        // and this one did not.
        assertTrue(most > 0, "no brick was ever laid, so the check proved nothing");
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
        // NEUTRAL, and the same either way: the label used to carry an
        // interface string in one language -- the only one in the whole of
        // domain, outside the bundle, and misspelt. The interface builds its
        // own label and setWicks carries the existing one across, so nothing on
        // screen lost anything.
        assertEquals("10 renko", Renko.of(10).withWicks(false).label());
        assertEquals("10 renko", Renko.of(10).label());
    }

    @Test
    @DisplayName("bricks created by the same bar share its time")
    void bricksShareTheirBarsTime() {
        // Three bricks from one minute really did all happen inside that minute.
        // Spreading them over invented timestamps would be the only alternative,
        // and it would be fiction.
        //
        // The FIRST of them is the exception, and not an invented one: it is the
        // brick that was being built, so it carries the moment building started.
        // The other two were passed through in an instant. See TradeTally.
        PriceSeries source = closes(100, 135);
        PriceSeries bricks = Renko.of(10).apply(source);

        assertEquals(source.timeAt(0), bricks.timeAt(0));
        assertEquals(source.timeAt(1), bricks.timeAt(1));
        assertEquals(source.timeAt(1), bricks.timeAt(2));
    }

    @Test
    @DisplayName("the ruler starts at the first OPEN, the one price that never moves")
    void anchoredOnTheFirstOpen() {
        // On the GRID, and this test used to say the opposite -- "not on a
        // rounded grid the series never touched". It was a deliberate choice,
        // and it was wrong: measured against the reference product on
        // 04/09/2026, four sizes on one instrument, every brick boundary is a
        // whole multiple of the brick.
        //
        //     6R   brick  25   opens 187.875   = 7.515 x 25
        //     11R  brick  50   opens 187.950   = 3.759 x 50
        //     21R  brick 100   opens 188.000   = 1.880 x 100
        //     41R  brick 200   opens 188.200   =   941 x 200
        //
        // Anchoring on the first price instead offset the whole ruler by
        // whatever that price happened to be, so two charts of the same
        // instrument and the same brick, opened on different days, drew
        // different bricks. Starting at 137 with ten-point bricks, the ruler
        // begins at 130 and the first brick tops at 140.
        assertEquals(140.0, Renko.of(10).apply(closes(137, 148)).closeAt(0));

        // The OPEN and not the close: while a bar forms its close moves, and a
        // ruler that moves with it measures every brick from a shifting origin.
        // Still true -- the grid decides WHERE the ruler starts, the open
        // decides WHICH price picks the cell.
        assertEquals(100.0, Renko.of(10).apply(ohlc(new double[]{100, 130, 100, 125})).openAt(0),
                "the ruler started somewhere other than the first open");
    }

    @Test
    @DisplayName("a series with no volume gives bricks with no volume")
    void absentVolumeStaysAbsent() {
        assertTrue(Double.isNaN(Renko.of(10).apply(closes(100, 111)).volumeAt(0)));
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
    @Test
    @DisplayName("toda fronteira cai na grade, nos quatro tamanhos lidos do Profit")
    void everyBoundaryIsOnTheGrid() {
        // The four bricks read off the reference product on 04/09/2026, with
        // the price band they were showing. Each one asserts the whole rule at
        // once: the size is (n-1) x tick, and the boundary is a whole multiple
        // of that size.
        int tick = 5;
        double[][] read = {
            //  n     abertura   fechamento
            {  6,     187_875,   187_850},
            { 11,     187_950,   188_000},
            { 21,     188_000,   187_900},
            { 41,     188_200,   188_000},
        };

        for (double[] each : read) {
            int name = (int) each[0];
            double brick = (name - 1) * tick;
            double open = each[1];
            double close = each[2];

            assertEquals(brick, Math.abs(close - open), 1e-9,
                    name + "R: o tijolo lido nao mede (n-1) x tick");
            assertEquals(0.0, open % brick, 1e-9,
                    name + "R: a abertura " + open + " nao cai na grade de " + brick);
            assertEquals(0.0, close % brick, 1e-9,
                    name + "R: o fechamento " + close + " nao cai na grade de " + brick);
        }

        // And the ruler this program builds lands on that same grid, wherever
        // the data begins. Measured on the tape of 03/09/2026, which opens at
        // 189.480: every one of 7.063 bricks at 25 points, 1.643 at 50, 365 at
        // 100 and 91 at 200.
        for (int name : new int[]{6, 11, 21, 41}) {
            double brick = (name - 1) * tick;
            PriceSeries laid = Renko.of(brick).apply(closes(189_480, 189_480 + 20 * brick));

            for (int i = 0; i < laid.size(); i++) {
                assertEquals(0.0, laid.openAt(i) % brick, 1e-9,
                        name + "R: tijolo " + i + " abre em " + laid.openAt(i)
                                + ", fora da grade de " + brick);
            }
        }
    }

    @Test
    @DisplayName("um lote de caixas grande demais e recusado, nao assentado")
    void anImpossibleNumberOfBricksIsRefused() {
        // There was no ceiling at all. A cast of a double past Integer.MAX_VALUE
        // saturates in silence, and laydown then walked that many times
        // allocating a double[5] each pass -- on the interface thread, since the
        // candle renko is folded synchronously from ChartCanvas.refold. A hang,
        // or an OutOfMemoryError, in place of a sentence.
        //
        // Not hypothetical: TickBars records a renko climbing from zero to
        // 120.000 and laying two thousand bricks no trade made, because rows
        // stating zero for everything were read as prices. That source is
        // filtered now; Renko is public, takes any brick above zero, and reads
        // candles, ticks and tape alike.
        //
        // One brick past the ceiling on purpose: laying 100.001 of them is
        // quick, so this fails as an assertion rather than as a hang if the
        // check ever goes away.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Renko.of(1).apply(closes(0, 100_002)),
                "a hundred thousand bricks from one bar were laid without a word");

        assertTrue(thrown.getMessage().contains("100001"), thrown.getMessage());

        // And the saturating case, which is the one that used to hang: a brick
        // small enough that the count does not fit in an int.
        assertThrows(IllegalArgumentException.class,
                () -> Renko.of(1e-9).apply(closes(0, 1_000)));
    }
}
