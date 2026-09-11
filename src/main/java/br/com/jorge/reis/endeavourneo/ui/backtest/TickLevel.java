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
package br.com.jorge.reis.endeavourneo.ui.backtest;

import br.com.jorge.reis.endeavourneo.domain.market.ConcatSeries;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.SyntheticSeries;
import br.com.jorge.reis.endeavourneo.domain.market.SyntheticTicks;
import br.com.jorge.reis.endeavourneo.domain.market.TickBars;
import br.com.jorge.reis.endeavourneo.domain.market.TickSource;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;
import br.com.jorge.reis.endeavourneo.ui.series.Segmentable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * One bar per price, from the tape when there is one and from the shape of the
 * minute when there is not.
 *
 * <p>The rule is his and it is the right one: <b>a real print beats a plausible
 * one</b>. The Profit's tape reaches back eight sessions; everything before that
 * exists only as minutes, and the minutes have to be walked to be executed
 * finely. So a run over the tape is executed against what actually printed, and
 * a run over anything else against a path whose statistics were measured against
 * that same tape.</p>
 *
 * <h2>The quadro says which one ran</h2>
 *
 * <p>Because the two are not the same evidence. A stop that was hit on the tape
 * was hit; a stop that was hit on a synthetic path was hit by one of the paths
 * that minute could have taken. Both are worth running and only one is a fact,
 * and a screen that showed them identically would be hiding the difference that
 * matters most.</p>
 */
final class TickLevel {

    /** The WIN moves in fives, and the path is built out of that step. */
    private static final double STEP = 5;

    /**
     * Fixed, so a run repeats.
     *
     * <p>The path is invented but it must not be <b>different</b> each time: two
     * runs of the same strategy over the same recorte have to produce the same
     * trades, or nothing downstream — the marks on the chart, the table, the
     * comparison between two strategies — means anything.</p>
     */
    private static final long SEED = 20_260_911L;

    /** What a run was executed against, and how big it turned out. */
    record Walked(PriceSeries series, boolean real, int ticks) {

        /** @return the key the quadro shows for how this ran */
        String key() {
            return real ? "backtest.ticks.real" : "backtest.ticks.synthetic";
        }
    }

    private TickLevel() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param choice what the reader picked
     * @param bars   that choice already opened and cut to its recorte
     * @param zone   the exchange's zone
     * @return the same stretch, one bar per price
     */
    static Walked of(SeriesChoice choice, PriceSeries bars, ZoneId zone) {
        PriceSeries tape = choice != null && Segmentable.isTicks(choice.key())
                ? fromTheTape(choice, bars, zone)
                : null;

        if (tape != null && tape.size() > 0) {
            return new Walked(tape, true, tape.size());
        }

        PriceSeries made = SyntheticSeries.of(bars, new SyntheticTicks(STEP, SEED));

        return new Walked(made, false, made.size());
    }

    /**
     * Reads the tape, for the sessions the recorte actually covers.
     *
     * <p>The days come from the bars in hand rather than from the library, so a
     * recorte of one session reads one file. Reading the library and cutting
     * afterwards is the mistake the audit already found on the candle path and
     * then repeated on this one: asking for a week of the tape read all of it
     * and threw the rest away.</p>
     *
     * @return the ticks, or null when this entry is not a tape at all
     */
    private static PriceSeries fromTheTape(SeriesChoice choice, PriceSeries bars, ZoneId zone) {
        TickSource source = Segmentable.sourceOf(choice.key());
        String instrument = Segmentable.instrumentOf(choice.key());

        if (source == null || instrument == null) {
            return null;
        }

        Path folder = SeriesCatalog.ticksOf(instrument);
        List<PriceSeries> parts = new ArrayList<>();

        for (LocalDate day : daysIn(bars, zone)) {
            Path file = source.fileFor(folder, instrument, day);

            try {
                parts.add(TickBars.of(source.read(file)));
            } catch (IOException | RuntimeException missing) {
                // One session of the tape that will not read is a gap, not a
                // failure of the run -- and said out loud, because a quiet gap
                // is a day the strategy appears not to have traded.
                System.err.println(day + ": that session of the tape could not be read ("
                        + missing + ")");
            }
        }

        return parts.isEmpty() ? null : ConcatSeries.of(parts);
    }

    private static List<LocalDate> daysIn(PriceSeries bars, ZoneId zone) {
        TreeSet<LocalDate> days = new TreeSet<>();

        for (int bar = 0; bar < bars.size(); bar++) {
            days.add(Instant.ofEpochMilli(bars.timeAt(bar)).atZone(zone).toLocalDate());
        }

        return new ArrayList<>(days);
    }
}
