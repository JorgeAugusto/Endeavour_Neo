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

import br.com.jorge.reis.endeavourneo.ui.chart.RandomWalkSeries;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a brick's tail is allowed to say.
 *
 * <p>Found on screen: a down brick of 55 points wearing an upper tail of 117.
 * With a two-brick reversal, price cannot go 117 against the trend without
 * turning — the rules make that brick impossible, and the chart drew it. These
 * are the bounds the rules impose, written down so the drawing cannot disagree
 * with them again.</p>
 */
@DisplayName("Renko tail bounds")
class RenkoWickBoundsTest {

    /** Bars as {open, high, low, close}. */
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

    /** @return how far the tail runs AGAINST the brick's direction */
    private static double against(PriceSeries bricks, int i) {
        boolean rising = bricks.closeAt(i) > bricks.openAt(i);

        return rising
                ? Math.min(bricks.openAt(i), bricks.closeAt(i)) - bricks.lowAt(i)
                : bricks.highAt(i) - Math.max(bricks.openAt(i), bricks.closeAt(i));
    }

    /** @return how far the tail runs PAST the brick, in its own direction */
    private static double beyond(PriceSeries bricks, int i) {
        boolean rising = bricks.closeAt(i) > bricks.openAt(i);

        return rising
                ? bricks.highAt(i) - Math.max(bricks.openAt(i), bricks.closeAt(i))
                : Math.min(bricks.openAt(i), bricks.closeAt(i)) - bricks.lowAt(i);
    }

    @Test
    @DisplayName("no tail runs further against a brick than a reversal would cost")
    void noTailBeyondTheReversal() {
        // The property, over twenty thousand bars of a seeded walk. Measured
        // before this test existed: 90 bricks out of 12.858 broke it, one of
        // them by 207 points where the limit is 110.
        double brick = 55;
        int reversal = 2;
        PriceSeries bricks = new Renko(brick, reversal).apply(
                new RandomWalkSeries(20_000, 136_000.0, 0L, 7L));

        int broken = 0;
        String first = null;

        for (int i = 0; i < bricks.size(); i++) {
            if (against(bricks, i) > reversal * brick + 1e-6) {
                broken++;

                if (first == null) {
                    first = "brick " + i + " opens " + bricks.openAt(i) + ", closes "
                            + bricks.closeAt(i) + ", tail against " + against(bricks, i);
                }
            }
        }

        assertEquals(0, broken, broken + " bricks wear a tail the rules forbid; first: " + first);
        assertTrue(bricks.size() > 1_000, "the walk laid too few bricks to prove anything");
    }

    @Test
    @DisplayName("no tail runs past the close, at any brick")
    void noTailPastTheClose() {
        // A brick ends at its own extreme. Over the whole walk, so the rule
        // holds for batches of many bricks and for the brick that turns.
        PriceSeries bricks = Renko.of(55).apply(new RandomWalkSeries(20_000, 136_000.0, 0L, 7L));

        int broken = 0;

        for (int i = 0; i < bricks.size(); i++) {
            if (beyond(bricks, i) > 1e-6) {
                broken++;
            }
        }

        assertEquals(0, broken, broken + " bricks reach past their own close");
    }

    @Test
    @DisplayName("the extreme a bar reaches AFTER laying a brick still counts for the next one")
    void theSecondExtremeIsNotThrownAway() {
        // Every assertion in this class is a CEILING -- no tail longer than a
        // reversal, no tail past the close. A tail that comes out too SHORT
        // passes all of them, and one did: the counter that decides whether to
        // restart the running extremes was declared outside the two-extreme
        // loop and kept its value across both. When the first extreme laid a
        // brick, the restart also fired at the end of the second -- erasing the
        // extreme the second had just recorded.
        //
        // Brick 10, reversal 2, and the fixture walks the exact path:
        //
        //   bar 1  rises to 125: two up bricks, 100->110->120. Anchor 120.
        //   bar 2  low 95 turns it down -- one brick, 110->100, anchor 100 --
        //          and THEN its high of 118 is recorded. 118 lays nothing,
        //          because turning back up now costs two bricks and 18 is one.
        //          This is where the 118 was being thrown away.
        //   bar 3  falls to 85: one down brick, 100->90. Its upper tail has to
        //          reach 118, the highest price traded since the anchor came to
        //          rest at 100.
        //
        // 118 is a legal tail: 18 against a brick that opens at 100, where the
        // reversal costs 20. With the counter shared, the tail stopped at 105 --
        // bar 3's own high, thirteen points short of what the market did.
        PriceSeries bricks = new Renko(10, 2).apply(ohlc(
                new double[]{100, 100, 100, 100},
                new double[]{100, 125, 100, 120},
                new double[]{115, 118, 95, 100},
                new double[]{100, 105, 85, 90}));

        int last = bricks.size() - 1;

        assertTrue(bricks.closeAt(last) < bricks.openAt(last),
                "the fixture is wrong: the last brick was expected to be a down brick");
        assertEquals(100.0, bricks.openAt(last), 1e-9, "the last brick opens at the anchor");
        assertEquals(118.0, bricks.highAt(last), 1e-9,
                "the tail stops at " + bricks.highAt(last)
                        + "; the market traded up to 118 after the anchor came to rest");
    }

    @Test
    @DisplayName("a bar's second extreme is not on the tail of what its first extreme laid")
    void theOtherExtremeIsNotYetReached() {
        // Brick 10, reversal 2. Two down bricks first, so the trend is down and
        // the assumed order inside a bar is high-then-low. (That bar's high is
        // 95, not 100: a high 20 above the new anchor of 80 would itself be a
        // reversal, and the fixture would turn the trend before the bar it
        // means to test -- which is what its first draft did.) Then one bar whose
        // high turns the trend (125: four bricks up from 80) and whose low is
        // 50 -- THIRTY below the open, where the reversal limit is twenty. The
        // first draft of this fixture used 60, which sits exactly ON the limit
        // and so proved nothing; the defect was there and the test passed.
        //
        // In the assumed path price rose to 125 FIRST, laying 80->...->120, and
        // only then fell to 50. So the up bricks must not wear a tail down to
        // 50: when they were laid, 50 had not happened yet. The fall is a move
        // from 120, and it lays its own down bricks.
        PriceSeries bricks = new Renko(10, 2).apply(ohlc(
                new double[]{100, 100, 100, 100},
                new double[]{95, 95, 78, 80},        // two down bricks: 100->90->80
                new double[]{80, 125, 50, 70}));     // turns up to 120, then falls

        // Find the first up brick.
        int up = -1;

        for (int i = 0; i < bricks.size(); i++) {
            if (bricks.closeAt(i) > bricks.openAt(i)) {
                up = i;

                break;
            }
        }

        assertTrue(up >= 0, "the 125 high should have turned the trend");
        // One brick away from the 80 the last brick closed at: a turn is drawn
        // offset, which is what the reference product does. See Renko.
        assertEquals(90.0, bricks.openAt(up), 1e-9);
        assertTrue(bricks.lowAt(up) >= 80.0 - 2 * 10,
                "the up brick wears a tail to " + bricks.lowAt(up)
                        + ", a price that in the assumed path had not been reached yet");

        // And the fall did lay down bricks from 120, so nothing was lost.
        double lowest = Double.POSITIVE_INFINITY;

        for (int i = 0; i < bricks.size(); i++) {
            lowest = Math.min(lowest, bricks.closeAt(i));
        }

        assertTrue(lowest <= 60.0, "the fall to 50 laid no bricks; lowest close is " + lowest);
    }
}
