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

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One clock, and both panels reading it the same way.
 *
 * <p>The candle being formed used to walk by POSITION — the k-th price of the
 * path, with the clock reading the bar's start plus {@code k / length} of a
 * minute — while the tick renko, driven by that same clock, cut by the trade's
 * REAL stamp. Both read the same trades of the same minute, through two
 * different rulers.</p>
 *
 * <p>Where the trades are not uniform the two disagree, and the open of a
 * session is the least uniform stretch there is — which is exactly where a
 * replay is worth using. Whoever decided by looking at the renko was deciding
 * with prices the candle beside it had not printed yet.</p>
 */
@DisplayName("Um relogio so")
class OneClockTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final LocalDate DAY = LocalDate.of(2021, 1, 4);

    /** Where the minute's high prints, deep inside the opening burst. */
    private static final int PEAK = 700;

    /**
     * A minute whose trades are anything but evenly spaced.
     *
     * <p>Nine hundred trades in the first five seconds, a hundred over the
     * remaining fifty-five. That is what the open of a session looks like, and
     * it is the shape that makes two rulers give two answers: at the halfway
     * mark of the MINUTE almost every trade has printed, while halfway along
     * the PATH is trade five hundred.</p>
     */
    private static void burstingSession(Path folder) throws IOException {
        try (TickFile.Writer writer = new TickFile.Writer(
                TickSource.METATRADER.fileFor(folder, "win", DAY), DAY)) {

            for (int i = 0; i < 900; i++) {
                // The high of the whole minute, well past the halfway point of
                // the path and well before the halfway point of the minute.
                int price = i == PEAK ? 118_500 : 118_000 + i % 40;

                writer.add(9 * 3_600_000 + i * 5, 0, 0, price, 1, 88, trade());
            }

            for (int i = 0; i < 100; i++) {
                writer.add(9 * 3_600_000 + 10_000 + i * 490, 0, 0, 118_020, 1, 88, trade());
            }
        }
    }

    private static int trade() {
        return TickFile.Writer.mask(false, false, true, true);
    }

    private static void settle(TickLibrary library) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (library.residentCount() < 1 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    @DisplayName("no meio do minuto, o candle mostra o que os ticks ja imprimiram")
    void theCandleShowsWhatHasPrinted(@TempDir Path folder) throws Exception {
        burstingSession(folder);

        TickLibrary library = new TickLibrary(folder, "win", TickSource.METATRADER);

        try {
            library.request(DAY);
            settle(library);

            PriceSeries day = FoldedTicks.day(folder, "win", TickSource.METATRADER, DAY, ZONE);

            assertEquals(1, day.size(), "the fixture is not one minute");
            assertEquals(118_500, day.highAt(0), 1e-9, "the peak did not survive the fold");

            ReplaySeries replay = new ReplaySeries(day, 0, 0,
                    new RecordedTicks(library, null));

            // Half a minute of market, a frame at a time, the way the transport
            // spends it.
            for (int frame = 0; frame < 750; frame++) {
                replay.advanceMarketTime(40);
            }

            assertEquals(1, replay.size(), "the bar is not forming any more");

            // The peak printed 3,5 s into the minute. By 30 s it is history, and
            // a candle that has not shown it is a candle disagreeing with the
            // renko beside it -- which cut by the same clock and by real stamps.
            assertEquals(118_500, replay.highAt(0), 1e-9,
                    "the forming bar has not reached the high the ticks printed "
                            + "twenty-six seconds ago");
        } finally {
            library.close();
        }
    }

    @Test
    @DisplayName("o candle e o renko contam os mesmos negocios, quadro a quadro")
    void bothPanelsCountTheSameTrades(@TempDir Path folder) throws Exception {
        // The property itself, and not one of its symptoms. At every instant the
        // clock reads, what the candle has taken in must be what the renko would
        // have folded: same trades, same minute, one ruler.
        burstingSession(folder);

        TickLibrary library = new TickLibrary(folder, "win", TickSource.METATRADER);

        try {
            library.request(DAY);
            settle(library);

            TickBars trades = TickBars.of(library.load(DAY));
            PriceSeries day = FoldedTicks.day(folder, "win", TickSource.METATRADER, DAY, ZONE);

            ReplaySeries replay = new ReplaySeries(day, 0, 0,
                    new RecordedTicks(library, null));

            // COUNTED, and asserted at the end. The loop used to be guarded by
            // `replay.size() == 1`, which is FALSE before the first frame --
            // nothing is forming until market time is spent -- so it never ran
            // once and this test asserted nothing at all, ever. It went green
            // through the whole defect it was written for.
            int checked = 0;

            for (int frame = 0; frame < 1_400; frame++) {
                replay.advanceMarketTime(40);

                if (replay.size() != 1) {
                    break;
                }

                int folded = trades.countUntil(replay.clock() + 1);

                if (folded == 0) {
                    continue;
                }

                checked++;

                double high = Double.NEGATIVE_INFINITY;
                double low = Double.POSITIVE_INFINITY;

                for (int i = 0; i < folded; i++) {
                    high = Math.max(high, trades.closeAt(i));
                    low = Math.min(low, trades.closeAt(i));
                }

                // BOTH SIDES. The first version of this asserted only that the
                // candle was not BEHIND, so adding five seconds to the replay
                // clock -- showing the future -- passed it unchanged. A one-way
                // assertion about two rulers agreeing is not about them
                // agreeing at all.
                //
                // And exactly, with no slack: countUntil(clock() + 1) selects
                // the trades with time <= now, and spendStamped takes the
                // prices with when <= now. They are the same set, so the candle
                // and the renko have seen the same trades and nothing else.
                assertEquals(high, replay.highAt(0), 1e-9,
                        "frame " + frame + ": the renko has seen up to " + high
                                + " and the candle shows " + replay.highAt(0));
                assertEquals(low, replay.lowAt(0), 1e-9,
                        "frame " + frame + ": the renko has seen down to " + low
                                + " and the candle shows " + replay.lowAt(0));
            }

            assertTrue(checked > 100,
                    "the loop compared the two panels only " + checked
                            + " times: it is not exercising the minute");
        } finally {
            library.close();
        }
    }
}
