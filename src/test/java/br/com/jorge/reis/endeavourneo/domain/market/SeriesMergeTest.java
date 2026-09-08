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

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Joining two raw exports of the same instrument.
 */
@DisplayName("Series merge")
class SeriesMergeTest {

    /** Bars a minute apart from that instant, closing at the prices given. */
    private static PriceSeries from(long start, double... closes) {
        return new PriceSeries() {

            @Override
            public int size() {
                return closes.length;
            }

            @Override
            public long timeAt(int index) {
                return start + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return closes[index];
            }

            @Override
            public double highAt(int index) {
                return closes[index] + 5;
            }

            @Override
            public double lowAt(int index) {
                return closes[index] - 5;
            }

            @Override
            public double closeAt(int index) {
                return closes[index];
            }

            @Override
            public double volumeAt(int index) {
                return 10;
            }
        };
    }

    @Test
    @DisplayName("the older export ends exactly where the newer one begins")
    void oneSourceAtATime() {
        // The rule, and the whole of it: everything from the older that happened
        // strictly BEFORE the newer starts, then all of the newer. No minute is
        // taken from both, and none is left out.
        PriceSeries older = from(0, 100, 101, 102, 103, 104);
        PriceSeries newer = from(3 * 60_000L, 203, 204, 205);

        PriceSeries joined = SeriesMerge.of(older, newer);

        assertEquals(6, joined.size(), "a minute was duplicated or dropped at the join");
        assertEquals(100.0, joined.closeAt(0), 1e-9);
        assertEquals(102.0, joined.closeAt(2), 1e-9);
        assertEquals(203.0, joined.closeAt(3), 1e-9, "the join took the wrong side of the seam");
        assertEquals(205.0, joined.closeAt(5), 1e-9);
    }

    @Test
    @DisplayName("time never goes backwards or repeats across the join")
    void theClockKeepsMovingForward() {
        // What an indicator crossing the seam depends on. A repeated minute
        // would give two bars the same instant and every aggregation would fold
        // them into one.
        PriceSeries joined = SeriesMerge.of(
                from(0, 100, 101, 102, 103, 104),
                from(3 * 60_000L, 203, 204, 205));

        for (int i = 1; i < joined.size(); i++) {
            assertTrue(joined.timeAt(i) > joined.timeAt(i - 1),
                    "bar " + i + " is not after the one before it");
        }
    }

    @Test
    @DisplayName("the step across the join is measured, so a bad seam can be refused")
    void theStepIsVisible() {
        // The one number that says whether a join is sound. On WIN it is a few
        // points on an ordinary day and around fifteen hundred on a contract
        // roll -- and a roll is exactly where this must never fall.
        PriceSeries older = from(0, 100, 101, 102, 103);
        PriceSeries quiet = from(3 * 60_000L, 102, 104);
        PriceSeries rolled = from(3 * 60_000L, 1_600, 1_610);

        assertEquals(0.0, SeriesMerge.stepAt(older, quiet), 1e-9);
        assertEquals(1_498.0, SeriesMerge.stepAt(older, rolled), 1e-9,
                "the step across a roll was not seen");
    }

    @Test
    @DisplayName("an export that starts before the other keeps nothing of it")
    void anOlderThatIsEntirelyInsideIsDropped() {
        // The newer export reaches further back than the older one. There is
        // nothing to take from the older, and taking it anyway would put the
        // series out of order.
        PriceSeries older = from(5 * 60_000L, 100, 101);
        PriceSeries newer = from(0, 200, 201, 202);

        PriceSeries joined = SeriesMerge.of(older, newer);

        assertEquals(3, joined.size());
        assertEquals(200.0, joined.closeAt(0), 1e-9);
    }

    @Test
    @DisplayName("either side missing gives back the other, whole")
    void oneSideAloneIsStillASeries() {
        PriceSeries only = from(0, 100, 101);

        assertEquals(2, SeriesMerge.of(only, null).size());
        assertEquals(2, SeriesMerge.of(null, only).size());
        assertEquals(0, SeriesMerge.of(null, null).size());
        assertEquals(2, SeriesMerge.of(only, PriceSeries.empty()).size());
    }

    @Test
    @DisplayName("a joined base written out reads back the same")
    void itSurvivesTheRoundTrip(@TempDir Path folder) throws IOException {
        // The join is a view over two series; saving it has to flatten it
        // without changing a number, or the base on disk is not the base that
        // was checked.
        PriceSeries joined = SeriesMerge.of(
                from(0, 100, 101, 102, 103),
                from(3 * 60_000L, 203, 204));

        Path file = folder.resolve("winfull-1m.bin");

        MarketFile.write(file, joined, 1);

        PriceSeries read = MarketFile.read(file);

        assertEquals(joined.size(), read.size());

        for (int i = 0; i < joined.size(); i++) {
            assertEquals(joined.timeAt(i), read.timeAt(i), "time at " + i);
            assertEquals(joined.openAt(i), read.openAt(i), 1e-9, "open at " + i);
            assertEquals(joined.highAt(i), read.highAt(i), 1e-9, "high at " + i);
            assertEquals(joined.lowAt(i), read.lowAt(i), 1e-9, "low at " + i);
            assertEquals(joined.closeAt(i), read.closeAt(i), 1e-9, "close at " + i);
            assertEquals(joined.volumeAt(i), read.volumeAt(i), 1e-9, "volume at " + i);
        }

        assertEquals(1, MarketFile.minutesOf(file));
    }

    @Test
    @DisplayName("sem sobreposicao o degrau nao e ZERO, que e o valor que autoriza")
    void nooverlapIsNotAHealthyJoin() {
        // stepAt is, in the class javadoc's own words, "the one number that says
        // whether a join is sound". When nothing of the older export survives --
        // it begins after the newer one, so the two came in the wrong order or
        // they do not overlap the way this assumes -- it used to answer ZERO,
        // which is the value that means "seamless, go ahead". The number written
        // to let a caller refuse was answering yes to the one case with nothing
        // to join.
        //
        // NaN compares false against every threshold, so refusing is what
        // happens by default rather than what has to be remembered.
        PriceSeries newer = bars(1_000, 100);
        PriceSeries older = bars(2_000, 100);

        assertTrue(Double.isNaN(SeriesMerge.stepAt(older, newer)),
                "no overlap at all was reported as a seamless join: "
                        + SeriesMerge.stepAt(older, newer));

        // And a real overlap still measures the step it always did.
        assertFalse(Double.isNaN(SeriesMerge.stepAt(bars(0, 100), bars(500, 100))),
                "an ordinary join stopped being measurable");
    }

    /** Bars a minute apart from that offset, at a flat price. */
    private static PriceSeries bars(int fromMinute, double price) {
        return new PriceSeries() {

            @Override
            public int size() {
                return 1_000;
            }

            @Override
            public long timeAt(int index) {
                return (fromMinute + index) * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return price;
            }

            @Override
            public double highAt(int index) {
                return price;
            }

            @Override
            public double lowAt(int index) {
                return price;
            }

            @Override
            public double closeAt(int index) {
                return price;
            }
        };
    }
/**
     * The join carries what a renko knows about its bars.
     *
     * <p>{@code Untraded} and {@code Counted} are what a renko knows and a
     * series of minutes does not. {@code ConcatSeries} and
     * {@code SegmentedSeries} both carry them, and say so; this envelope was
     * written afterwards and answered "does not know" for every bar — taking
     * the knowledge out of the middle of a chart with nothing said.</p>
     */
    @Test
    @DisplayName("a juncao carrega o que o renko sabe das barras dele")
    void thejoinCarriesWhatTheRenkoKnows() {
        // A walk with room for several boxes on each side of the seam.
        PriceSeries older = new Renko(10, 2).apply(
                from(0L, 100, 110, 120, 130, 140, 150, 160));
        PriceSeries newer = new Renko(10, 2).apply(
                from(7 * 60_000L, 160, 170, 180, 190, 200));

        assertTrue(older instanceof Untraded, "the fixture is not a renko");

        PriceSeries joined = SeriesMerge.of(older, newer);

        assertTrue(joined instanceof Untraded,
                "the join dropped what the renko knew about untraded bars");
        assertTrue(joined instanceof Counted,
                "the join dropped the trade count of every bar");
    }
}
