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
package br.com.jorge.reis.endeavourneo.ui.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.TickSource;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Renko;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.market.TickFile;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The chart drawing a renko from the exchange's own ticks.
 *
 * <p>The difference is not cosmetic. Measured on WINFUT over January 2021,
 * brick 55: 15.100 bricks from one-minute candles against 11.886 from the
 * ticks. The CANDLES lay more, because reading a bar as "the high then the low"
 * manufactures a swing inside every minute that the real path did not make.</p>
 */
@DisplayName("Tick renko on the chart")
class TickRenkoOnChartTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final LocalDate DAY = LocalDate.of(2021, 1, 4);

    @AfterEach
    void stopPointingAtTheTemporaryFolder() {
        SeriesCatalog.useFolderForTest(null);
    }

    /** Forty ticks a minute, for two hours, from ONE price path. */
    private static final int MINUTES = 120;

    private static final int PER_MINUTE = 40;

    /**
     * The path both the ticks and the candles are made of.
     *
     * <p>Built once and used for both, which is the whole point: the two series
     * are then the same market at two resolutions, and any difference between
     * the renkos is the resolution and nothing else. The first draft of this
     * test invented the candles separately, so it was comparing two different
     * markets and its numbers meant nothing.</p>
     *
     * <p>The swings are 90 points against a brick of 55, because a swing
     * smaller than a brick is invisible to BOTH and the test would prove
     * nothing. That is what the first draft also got wrong: it wandered thirty
     * points and the two renkos came out the same size.</p>
     */
    private static int[] path() {
        int[] prices = new int[MINUTES * PER_MINUTE];
        int price = 100_000;

        for (int i = 0; i < prices.length; i++) {
            // Up ninety, down ninety, and a slow drift upwards underneath.
            price += (i % 4 < 2) ? 90 : -90;
            price += 2;

            prices[i] = price;
        }

        return prices;
    }

    private static void session(Path folder) throws IOException {
        int[] prices = path();

        // Pointed at the folder before asking where the ticks go: ticksOf
        // answers about the CURRENT folder, and a fixture that wrote before
        // saying which folder would write into the reader's own data.
        SeriesCatalog.useFolderForTest(folder);

        Path file = TickSource.METATRADER.fileFor(SeriesCatalog.ticksOf("win"), "win", DAY);

        if (!file.toAbsolutePath().startsWith(folder.toAbsolutePath())) {
            throw new IllegalStateException("this fixture was about to write to " + file
                    + ", which is outside " + folder);
        }

        try (TickFile.Writer writer = new TickFile.Writer(file, DAY)) {

            for (int i = 0; i < prices.length; i++) {
                writer.add(9 * 3_600_000 + i * 1_000, 0, 0, prices[i], 1, 88,
                        TickFile.Writer.mask(false, false, true, true));
            }
        }
    }

    /** The same path folded into one-minute candles. */
    private static PriceSeries minutes() {
        int[] prices = path();
        long open = DAY.atStartOfDay(ZONE).toInstant().toEpochMilli() + 9 * 3_600_000L;
        double[][] bars = new double[MINUTES][4];

        for (int m = 0; m < MINUTES; m++) {
            int from = m * PER_MINUTE;
            double high = prices[from];
            double low = prices[from];

            for (int i = from; i < from + PER_MINUTE; i++) {
                high = Math.max(high, prices[i]);
                low = Math.min(low, prices[i]);
            }

            bars[m] = new double[]{prices[from], high, low, prices[from + PER_MINUTE - 1]};
        }

        return new PriceSeries() {

            @Override
            public int size() {
                return MINUTES;
            }

            @Override
            public long timeAt(int index) {
                return open + index * 60_000L;
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

    /** Waits for the background build to land on screen. */
    private static void settle(ChartCanvas canvas) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (!canvas.isFromTicks() && System.nanoTime() < deadline) {
            Thread.sleep(10);

            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    /**
     * Waits for a rebuild to have LANDED, and then lets the caller ask what it did.
     *
     * <p>The three tests that assert the chart did NOT switch to tick bricks used
     * to sleep 150 or 300 milliseconds and check. That is a race with no target:
     * on a loaded machine, a cold disk or a first run of the JVM, the assertion
     * passes by arriving early rather than by the product being right -- and the
     * case they guard is the worst one in this area, a renko dense on one side
     * and sparse on the other "that would look like the market did it".</p>
     *
     * @param canvas the chart
     * @param before what {@code ticksSettled()} said before the change
     */
    private static void settleEitherWay(ChartCanvas canvas, int before) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (canvas.ticksSettled() == before && System.nanoTime() < deadline) {
            Thread.sleep(10);

            SwingUtilities.invokeAndWait(() -> { });
        }

        assertTrue(canvas.ticksSettled() > before,
                "no tick rebuild ever finished, so asserting what it did not do "
                        + "proves nothing");

        SwingUtilities.invokeAndWait(() -> { });
    }

    private static ChartCanvas showing(Path folder) {
        SeriesCatalog.useFolderForTest(folder);

        ChartCanvas canvas = new ChartCanvas();

        canvas.setInstrument("winfull-1m");
        canvas.setSeries(minutes());
        canvas.setSize(900, 500);

        return canvas;
    }

    @Test
    @DisplayName("with ticks for every session on screen, the bricks come from the ticks")
    void theBricksComeFromTheTicks(@TempDir Path folder) throws Exception {
        session(folder);

        ChartCanvas canvas = showing(folder);

        assertFalse(canvas.isFromTicks(), "it claimed tick bricks before building any");

        canvas.setPeriod(new Renko(55, 2), "55R", "55R");

        settle(canvas);

        assertTrue(canvas.isFromTicks(), "the chart stayed on the candle renko");

        int fromCandles = new Renko(55, 2).apply(minutes()).size();

        // DIFFERENT, and the direction is deliberately not asserted. The first
        // version required the ticks to lay MORE, on the reasoning that they
        // reveal reversals the minute hid. Measured on the real export, the
        // opposite is the rule: reading a bar as "the high then the low"
        // manufactures a swing the real path did not make, so the candles lay 8%
        // to 27% more. Which way it goes depends on the market; that the two
        // disagree at all is the thing worth pinning.
        assertNotEquals(fromCandles, canvas.series().size(),
                "the tick renko and the candle renko came out identical, so nothing "
                        + "distinguishes the two sources and this test proves nothing");
    }

    @Test
    @DisplayName("without ticks the chart keeps the candle renko, and says so")
    void withoutTicksItStaysOnCandles(@TempDir Path folder) throws Exception {
        // No session written: the folder is empty. Most of the source is like
        // this -- one month has ticks and six years do not.
        ChartCanvas canvas = showing(folder);
        int before = canvas.ticksSettled();

        canvas.setPeriod(new Renko(55, 2), "55R", "55R");

        settleEitherWay(canvas, before);

        assertFalse(canvas.isFromTicks(),
                "the chart claimed the bricks came from ticks that are not on disk");
        assertEquals(new Renko(55, 2).apply(minutes()).size(), canvas.series().size());
    }

    @Test
    @DisplayName("one session short of ticks and the whole chart stays on candles")
    void partialCoverageIsRefused(@TempDir Path folder) throws Exception {
        // THE dangerous case, and the one the empty-folder test does not reach:
        // there ARE ticks, just not for every day on screen. A renko built from
        // the days that have them would be dense on the left and sparse on the
        // right, and would look like the market did that.
        //
        // Without this test the coverage check could be deleted and the suite
        // would stay green -- which it did, when the teeth were checked.
        session(folder);

        ChartCanvas canvas = new ChartCanvas();

        SeriesCatalog.useFolderForTest(folder);
        canvas.setInstrument("winfull-1m");
        canvas.setSeries(twoDays());
        canvas.setSize(900, 500);
        int before = canvas.ticksSettled();

        canvas.setPeriod(new Renko(55, 2), "55R", "55R");

        settleEitherWay(canvas, before);

        assertFalse(canvas.isFromTicks(),
                "the chart built a renko from the one day that has ticks and drew it beside "
                        + "a day that has none");
    }

    @Test
    @DisplayName("a build started on another series is dropped when the series changes")
    void aBuildForAnotherSeriesIsNotShown(@TempDir Path folder) throws Exception {
        // The sibling of aLateBuildIsNotShown, and the door that one did not
        // watch. The guard in done() compared only the PERIOD, and a replay
        // being dropped on a chart swaps the SOURCE without touching it -- so a
        // renko of the plain series came back afterwards and landed on top of
        // the replay's, which is exactly the candle-and-tick mixing this whole
        // path exists to prevent.
        //
        // Said here with twoDays, whose second day has no ticks: a renko built
        // for THAT series is refused outright, so isFromTicks turning true can
        // only be the stale build for the first one arriving late.
        session(folder);

        ChartCanvas canvas = showing(folder);

        int before = canvas.ticksSettled();

        canvas.setPeriod(new Renko(55, 2), "55R", "55R");
        canvas.setSeries(twoDays());

        settleEitherWay(canvas, before);

        assertFalse(canvas.isFromTicks(),
                "a renko built for the series that was on screen BEFORE was drawn over "
                        + "the one that is on screen now");
    }

    /** The session with ticks, and the next day, which has none. */
    private static PriceSeries twoDays() {
        PriceSeries first = minutes();
        long second = DAY.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
                + 9 * 3_600_000L;

        return new PriceSeries() {

            @Override
            public int size() {
                return first.size() + 60;
            }

            @Override
            public long timeAt(int index) {
                return index < first.size()
                        ? first.timeAt(index) : second + (index - first.size()) * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return index < first.size() ? first.openAt(index) : 101_000;
            }

            @Override
            public double highAt(int index) {
                return index < first.size() ? first.highAt(index) : 101_100;
            }

            @Override
            public double lowAt(int index) {
                return index < first.size() ? first.lowAt(index) : 100_900;
            }

            @Override
            public double closeAt(int index) {
                return index < first.size() ? first.closeAt(index) : 101_050;
            }
        };
    }

    @Test
    @DisplayName("the indicators follow the bricks, not the minutes they replaced")
    void overlaysAreRecalculatedOnTheBricks(@TempDir Path folder) throws Exception {
        // An overlay holds what it worked out, and what it worked out belongs to
        // one series. When the ticks replace the candle renko the series under
        // it changes completely -- different length, different prices -- and an
        // average still holding the minutes is a line of one market drawn across
        // another.
        //
        // seriesGrew always recalculated. The SwingWorker that first builds the
        // renko did not, and excused it in a comment: the next frame is a
        // fortieth of a second away. So it is, while the replay is PLAYING.
        // Paused -- or on a chart with no replay at all, which is this test --
        // there is no next frame.
        session(folder);

        ChartCanvas canvas = showing(folder);

        br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage average =
                new br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage(3);

        canvas.addOverlay(average);

        // THE REPLAY BRANCH, and the first draft of this test forgot it: with no
        // tick source the build lands in show(), which always recalculated, so
        // the test passed with the fix removed. It proved nothing until this
        // line, which is the difference between a test and a decoration.
        canvas.setTickSource(
                br.com.jorge.reis.endeavourneo.domain.market.TickSource.METATRADER);
        canvas.setPeriod(new Renko(55, 2), "55R", "55R");

        settle(canvas);

        assertTrue(canvas.isFromTicks(), "the chart stayed on the candle renko");

        // The same average, worked out fresh on what is actually on screen. If
        // the canvas recalculated, the two agree bar for bar; if it did not,
        // they disagree wherever the two series do -- which is everywhere.
        br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage reference =
                new br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage(3);

        reference.calculate(canvas.series());

        for (int bar = 0; bar < canvas.series().size(); bar++) {
            double drawn = average.valueAt(bar)[0];
            double right = reference.valueAt(bar)[0];

            if (Double.isNaN(right)) {
                continue;
            }

            assertEquals(right, drawn, 1e-9,
                    "bar " + bar + ": the average still holds what it worked out from "
                            + "the minutes, and the chart is showing bricks");
        }
    }

    @Test
    @DisplayName("changing the period drops the build that was already running")
    void aLateBuildIsNotShown(@TempDir Path folder) throws Exception {
        // A reader who types 11 and then 55 must not be shown the eleven,
        // arriving late and looking authoritative.
        session(folder);

        ChartCanvas canvas = showing(folder);

        canvas.setPeriod(new Renko(11, 2), "11R", "11R");
        canvas.setPeriod(new Renko(55, 2), "55R", "55R");

        settle(canvas);

        assertTrue(canvas.isFromTicks());

        // The bricks on screen have to be 55 apart, not 11.
        double height = Math.abs(canvas.series().closeAt(0) - canvas.series().openAt(0));

        assertEquals(55.0, height, 1e-9,
                "the chart is showing the renko that was asked for first");
    }

    @Test
    @DisplayName("a time period is left alone; the ticks only change renko")
    void onlyRenkoIsRebuilt(@TempDir Path folder) throws Exception {
        // A closed five-minute candle is already exact. The ticks add nothing
        // to it, and rebuilding it from them would cost a second to draw the
        // same bars.
        session(folder);

        ChartCanvas canvas = showing(folder);

        canvas.setPeriod(br.com.jorge.reis.endeavourneo.domain.market.Timeframe.ofMinutes(5),
                "5m", "5m");

        Thread.sleep(150);
        SwingUtilities.invokeAndWait(() -> { });

        assertFalse(canvas.isFromTicks(), "a five-minute chart went looking for ticks");
    }
    @Test
    @DisplayName("sair do renko devolve o grafico aos minutos")
    void leavingRenkoGoesBackToMinutes(@TempDir Path folder) throws Exception {
        // Reported: "in minutes it works, 1, 5, 10, all fine; go to renko and
        // it stops -- and then it will not go back to minutes."
        //
        // The cause was one early return. Leaving renko for minutes returned
        // from the tick rebuild before dropping the renko that was still
        // growing, so every frame afterwards took the extend path and put its
        // BRICKS on screen while the chart was set to minutes. Nothing on
        // screen said the chart was showing something the period did not ask
        // for.
        session(folder);

        ChartCanvas canvas = showing(folder);

        canvas.setPeriod(new Renko(55, 2), "55R", "55R");
        settle(canvas);

        assertTrue(canvas.isFromTicks(), "the renko never came from the ticks");

        int bricks = canvas.series().size();

        canvas.setPeriod(Timeframe.ONE_MINUTE, "1m", "1m");

        assertFalse(canvas.isFromTicks(),
                "the chart still claims tick bricks after going back to minutes");

        int minutes = canvas.series().size();

        assertNotEquals(bricks, minutes,
                "the chart kept showing the bricks after the period changed");
        assertEquals(MINUTES, minutes,
                "the chart did not come back to the minute bars it was given");

        // And it keeps moving: the frame after the change must not be served
        // from a renko that no longer belongs to this period.
        canvas.seriesGrew();

        assertFalse(canvas.isFromTicks(), "a later frame put the bricks back");
        assertEquals(MINUTES, canvas.series().size());
    }

    @Test
    @DisplayName("um quadro em que nada imprimiu nao troca o renko de ticks pelo de candles")
    void aQuietFrameKeepsTheTickBricks(@TempDir Path folder) throws Exception {
        // extendBricks answered false for two different things: "this renko has
        // to be abandoned" and "nothing printed since the last frame". seriesGrew
        // reads it as the first and falls through to period.apply(source) -- the
        // candle renko, which lays 8% to 27% more bricks -- while fromTicks goes
        // on saying the bricks came from the ticks. Permanent until the chart is
        // reopened, and nothing on screen says it happened.
        //
        // The quiet frame is the common one: at twenty-five frames a second most
        // frames have no new trade in them.
        session(folder);

        ChartCanvas canvas = showing(folder);

        canvas.setTickSource(
                br.com.jorge.reis.endeavourneo.domain.market.TickSource.METATRADER);
        canvas.setPeriod(new Renko(55, 2), "55R", "55R");

        settle(canvas);

        assertTrue(canvas.isFromTicks(), "the chart never reached the tick bricks");

        int bricks = canvas.series().size();
        PriceSeries was = canvas.series();

        // The clock has not moved, so this frame has nothing to add.
        canvas.seriesGrew();

        assertTrue(canvas.isFromTicks(),
                "a frame with nothing new swapped the tick renko for the candle renko");
        assertEquals(bricks, canvas.series().size(),
                "the bricks changed on a frame in which nothing printed");
        assertSame(was, canvas.series(),
                "the whole view was rebuilt for a chart that did not change");
    }
    @Test
    @DisplayName("the search hands over the library it opened, instead of closing it")
    void theLibraryIsHandedOver(@TempDir Path folder) throws IOException {
        // The search opens a library per export to ask whether it covers the
        // sessions on screen. It used to close every one of them and answer
        // with a name, so rebuildFromTicks opened a THIRD library on the export
        // just chosen and asked it the same question again -- three libraries
        // and three directory listings, on the interface thread, on every fold:
        // every change of period, every setSeries, every page of history.
        session(folder);

        ChartCanvas canvas = showing(folder);
        ChartCanvas.Bricks bricks = canvas.libraryForBricks();

        try {
            assertNotNull(bricks, "no export was found for a day that was written");
            assertEquals(TickSource.METATRADER, bricks.source());
            assertFalse(bricks.library().isClosed(),
                    "the search closed the library it had in its hand, so the caller "
                            + "has to open a third one and ask the same question again");
        } finally {
            if (bricks != null) {
                bricks.library().close();
            }
        }
    }
}
