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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A renko built from ticks, session by session.
 */
@DisplayName("Tick renko")
class TickRenkoTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final LocalDate DAY = LocalDate.of(2021, 1, 4);

    /**
     * A walk that lays bricks in both directions, several times.
     *
     * <p>Both directions and several times on purpose: a slice boundary that
     * fell only on a run of up bricks would never test the reversal, which is
     * where a lost carry shows.</p>
     */
    private static final int[] WALK = walk();

    private static int[] walk() {
        int[] prices = new int[240];
        int at = 118_000;

        for (int i = 0; i < prices.length; i++) {
            at += (i / 20) % 2 == 0 ? 4 : -4;
            prices[i] = at;
        }

        return prices;
    }

    /** A session whose trades walk through the prices given, one a second. */
    private static void session(Path folder, LocalDate date, int... prices) throws IOException {
        try (TickFile.Writer writer = new TickFile.Writer(
                TickSource.METATRADER.fileFor(folder, "winfut", date), date)) {

            for (int i = 0; i < prices.length; i++) {
                writer.add(9 * 3_600_000 + i * 1_000, 0, 0, prices[i], 1, 88,
                        TickFile.Writer.mask(false, false, true, true));
            }
        }
    }

    private static long at(LocalDate date, int second) {
        return date.atStartOfDay(ZONE).toInstant().toEpochMilli()
                + 9 * 3_600_000L + second * 1_000L;
    }

    @Test
    @DisplayName("built day by day, brick for brick the same as one long pass")
    void thePiecesAgreeWithTheWhole(@TempDir Path folder) throws IOException {
        // Prices chosen to lay bricks in both directions and to cross the
        // session boundary mid-run, which is where a lost carry would show.
        int[] monday = {100, 110, 120, 130, 125, 140, 150};
        int[] tuesday = {155, 160, 150, 140, 130, 120, 135};

        session(folder, LocalDate.of(2021, 1, 4), monday);
        session(folder, LocalDate.of(2021, 1, 5), tuesday);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            PriceSeries inPieces = TickRenko.over(new Renko(10, 2), library,
                    List.of(LocalDate.of(2021, 1, 4), LocalDate.of(2021, 1, 5)));

            // The same trades as one series, and one pass of the same renko.
            int[] both = new int[monday.length + tuesday.length];

            System.arraycopy(monday, 0, both, 0, monday.length);
            System.arraycopy(tuesday, 0, both, monday.length, tuesday.length);

            PriceSeries wholeInOne = new Renko(10, 2, true, false).apply(pricesAsBars(both));

            assertEquals(wholeInOne.size(), inPieces.size(),
                    "building it a day at a time laid a different number of bricks");

            for (int i = 0; i < wholeInOne.size(); i++) {
                assertEquals(wholeInOne.openAt(i), inPieces.openAt(i), 1e-9, "open at " + i);
                assertEquals(wholeInOne.closeAt(i), inPieces.closeAt(i), 1e-9, "close at " + i);
                assertEquals(wholeInOne.highAt(i), inPieces.highAt(i), 1e-9, "high at " + i);
                assertEquals(wholeInOne.lowAt(i), inPieces.lowAt(i), 1e-9, "low at " + i);
            }
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("the replay sees only the bricks the clock has reached")
    void nothingFromTheFuture(@TempDir Path folder) throws IOException {
        // The reason addUpTo exists. Folding the whole session in while the
        // replay is halfway through it would put tomorrow's bricks on screen.
        LocalDate day = LocalDate.of(2021, 1, 4);

        session(folder, day, 100, 110, 120, 130, 140, 150, 160);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            TickRenko half = new TickRenko(new Renko(10, 2), library);

            half.addUpTo(day, at(day, 3));

            TickRenko whole = new TickRenko(new Renko(10, 2), library);

            whole.add(day);

            assertTrue(half.size() > 0, "nothing was drawn at all");
            assertTrue(half.size() < whole.size(),
                    "the half-played session drew " + half.size()
                            + " bricks, the same as the whole day");

            // And what it drew is the beginning of what the whole day draws.
            for (int i = 0; i < half.size(); i++) {
                assertEquals(whole.bricks().closeAt(i), half.bricks().closeAt(i), 1e-9,
                        "brick " + i + " is not the one the whole day laid");
            }
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("a session cannot be folded in twice, or out of order")
    void theOrderIsEnforced(@TempDir Path folder) throws IOException {
        // Out of order would put bricks in the wrong sequence AND carry the
        // ruler backwards. Neither is visible in the result: the chart would
        // simply be wrong, quietly.
        session(folder, LocalDate.of(2021, 1, 4), 100, 110, 120);
        session(folder, LocalDate.of(2021, 1, 5), 130, 140);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            TickRenko building = new TickRenko(new Renko(10, 2), library);

            building.add(LocalDate.of(2021, 1, 5));

            assertFalse(building.add(LocalDate.of(2021, 1, 5)), "the same day was folded twice");
            assertThrows(IllegalArgumentException.class,
                    () -> building.add(LocalDate.of(2021, 1, 4)));
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("a session with almost no trades does not reset the ruler")
    void anEmptySessionIsHarmless(@TempDir Path folder) throws IOException {
        // 25/01/2021 has two ticks in the real export. A day like that must not
        // start the next one's bricks over at its own price.
        session(folder, LocalDate.of(2021, 1, 4), 100, 110, 120);
        session(folder, LocalDate.of(2021, 1, 5), 121);
        session(folder, LocalDate.of(2021, 1, 6), 130, 140);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            PriceSeries withGap = TickRenko.over(new Renko(10, 2), library,
                    List.of(LocalDate.of(2021, 1, 4), LocalDate.of(2021, 1, 5),
                            LocalDate.of(2021, 1, 6)));

            for (int i = 1; i < withGap.size(); i++) {
                assertEquals(withGap.closeAt(i - 1), withGap.openAt(i), 1e-9,
                        "brick " + i + " does not start where the one before it closed");
            }
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("only trades become bars; a quote with no trade is not a price")
    void quotesAreNotBars(@TempDir Path folder) throws IOException {
        LocalDate day = LocalDate.of(2021, 1, 4);

        try (TickFile.Writer writer = new TickFile.Writer(
                TickSource.METATRADER.fileFor(folder, "winfut", day), day)) {

            writer.add(9 * 3_600_000, 0, 0, 100, 1, 88,
                    TickFile.Writer.mask(false, false, true, true));
            // A bid moved. Nobody paid it, so it is not a bar.
            writer.add(9 * 3_600_000 + 1_000, 95, 0, 0, 0, 4,
                    TickFile.Writer.mask(true, false, false, false));
            writer.add(9 * 3_600_000 + 2_000, 0, 0, 110, 2, 88,
                    TickFile.Writer.mask(false, false, true, true));
        }

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            TickSeries session = library.load(day);
            TickBars bars = TickBars.of(session);

            assertEquals(3, session.size());
            assertEquals(2, bars.size(), "a quote with no trade became a bar");
            assertEquals(100.0, bars.closeAt(0), 1e-9);
            assertEquals(110.0, bars.closeAt(1), 1e-9);
        } finally {
            library.close();
        }
    }

    /** The same prices as a plain series, one bar each. */
    private static PriceSeries pricesAsBars(int[] prices) {
        return new PriceSeries() {

            @Override
            public int size() {
                return prices.length;
            }

            @Override
            public long timeAt(int index) {
                return index * 1_000L;
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
    @DisplayName("avancar em pedacos da o mesmo renko que dobrar de uma vez")
    void advancingInPiecesIsTheSameRenko(@TempDir Path folder) throws IOException {
        // The property the whole replay rests on. If folding in slices differed
        // from folding at once -- by one brick, at one boundary -- the chart
        // would disagree with itself depending on whether the reader watched
        // the session or opened it afterwards. Nothing on screen would say so.
        session(folder, DAY, WALK);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            TickRenko whole = new TickRenko(new Renko(10, 2), library);

            whole.add(DAY);

            TickSeries ticks = library.load(DAY);
            long first = ticks.timeAt(0);
            long last = ticks.timeAt(ticks.size() - 1);

            TickRenko piecemeal = new TickRenko(new Renko(10, 2), library);

            // Twenty frames across the session, which is what a replay does.
            for (int i = 1; i <= 20; i++) {
                piecemeal.advance(DAY, first + (last - first + 1) * i / 20 + 1);
            }

            assertEquals(whole.size(), piecemeal.size(),
                    "the piecemeal renko laid a different number of bricks");

            PriceSeries one = whole.bricks();
            PriceSeries many = piecemeal.bricks();

            for (int i = 0; i < one.size(); i++) {
                assertEquals(one.openAt(i), many.openAt(i), "brick " + i + " opens elsewhere");
                assertEquals(one.closeAt(i), many.closeAt(i), "brick " + i + " closes elsewhere");
                assertEquals(one.timeAt(i), many.timeAt(i), "brick " + i + " is at another time");
            }
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("um quadro que nao trouxe negocio novo nao poe tijolo")
    void aFrameWithNothingNewLaysNothing(@TempDir Path folder) throws IOException {
        // A replay asks many times a second and the market does not print that
        // often. Laying anything for an empty frame would grow the chart out of
        // nothing.
        session(folder, DAY, WALK);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            TickRenko renko = new TickRenko(new Renko(10, 2), library);
            TickSeries ticks = library.load(DAY);
            long end = ticks.timeAt(ticks.size() - 1) + 1;

            assertTrue(renko.advance(DAY, end), "the first frame laid nothing at all");

            int after = renko.size();

            assertFalse(renko.advance(DAY, end), "an empty frame said it laid a brick");
            assertEquals(after, renko.size(), "an empty frame grew the renko");
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("avancar para tras e recusado, nao aceito em silencio")
    void advancingBackwardsIsRefused(@TempDir Path folder) throws IOException {
        // Backwards carries the ruler back with it, and nothing in the bricks
        // would show that it happened.
        session(folder, DAY, WALK);
        session(folder, DAY.plusDays(1), WALK);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            TickRenko renko = new TickRenko(new Renko(10, 2), library);

            renko.advance(DAY.plusDays(1), Long.MAX_VALUE);

            assertThrows(IllegalArgumentException.class,
                    () -> renko.advance(DAY, Long.MAX_VALUE));
        } finally {
            library.close();
        }
    }
    @Test
    @DisplayName("a borda viva se move a cada quadro, mesmo sem fechar tijolo")
    void theLiveEdgeMovesEveryFrame(@TempDir Path folder) throws IOException {
        // Reported from the screen: "it animates the current candle, then it
        // freezes and shows in jumps". The jumps were the settled bricks: with
        // no brick still being built, the chart only moved when a whole one
        // closed. The renko it replaced draws that brick on every pass, which
        // is why leaving renko for minutes looked like the fix.
        session(folder, DAY, WALK);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            TickRenko renko = new TickRenko(new Renko(10, 2), library);
            TickSeries ticks = library.load(DAY);

            long first = ticks.timeAt(0);
            long last = ticks.timeAt(ticks.size() - 1);

            int movedWithoutClosing = 0;
            double before = Double.NaN;
            int settled = 0;

            // Two hundred frames over a walk that moves four points a step,
            // with ten-point bricks: most frames bring a trade or two and close
            // nothing. Forty frames covered twenty-four points each and closed
            // a brick every time, which left nothing for this test to see.
            for (int i = 1; i <= 200; i++) {
                renko.advance(DAY, first + (last - first + 1) * i / 200 + 1);

                PriceSeries live = renko.live();

                assertEquals(renko.size() + 1, live.size(),
                        "the live edge is not on top of the settled bricks");

                double now = live.closeAt(live.size() - 1);

                if (!Double.isNaN(before) && now != before && renko.size() == settled) {
                    movedWithoutClosing++;
                }

                before = now;
                settled = renko.size();
            }

            // Moved at least once on a frame where nothing closed. Comparing
            // the two totals was fragile: a fixture that happens to close a
            // brick almost every frame fails it while the edge is working
            // perfectly, which is a test about the fixture and not the code.
            assertTrue(movedWithoutClosing > 0,
                    "the edge never moved on a frame that closed no brick -- "
                            + "it is only moving when one does");
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("o tijolo em formacao nunca conta como assentado")
    void theFormingBrickIsNeverCounted(@TempDir Path folder) throws IOException {
        // It belongs on screen and nowhere else. Counted as laid it would make
        // a replayed renko disagree with the same renko opened afterwards, by
        // exactly one brick, for ever.
        session(folder, DAY, WALK);

        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        try {
            TickRenko live = new TickRenko(new Renko(10, 2), library);

            for (int i = 1; i <= 20; i++) {
                live.advance(DAY, Long.MIN_VALUE + 1);
            }

            live.advance(DAY, Long.MAX_VALUE);

            TickRenko whole = new TickRenko(new Renko(10, 2), library);

            whole.add(DAY);

            assertEquals(whole.size(), live.size(),
                    "the forming brick was counted among the settled ones");
            assertEquals(whole.bricks().size(), live.bricks().size());
            assertEquals(whole.size() + 1, live.live().size(),
                    "the live view lost its edge");
        } finally {
            library.close();
        }
    }
}
