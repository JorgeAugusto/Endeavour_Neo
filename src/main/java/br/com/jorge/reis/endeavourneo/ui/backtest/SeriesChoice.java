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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Segment;
import br.com.jorge.reis.endeavourneo.domain.market.SegmentedSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.platform.Segmentation;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;
import br.com.jorge.reis.endeavourneo.ui.series.Segmentable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One entry of the series list: a whole series, or one segment of one.
 *
 * <p>The backtest does not take a file path. It takes what the rest of the
 * application already calls a series — and, where there is one, the
 * <b>segment</b>: the named part with a role that the segmentation control
 * exists to define. That control was built precisely so that a run can be
 * pointed at "busca" or at "teste" and not at a date range somebody typed.</p>
 *
 * <h2>A run never crosses two segments</h2>
 *
 * <p>The rule is in the vocabulary: a slice lives <i>inside</i> a segment and
 * never spans two. If it spans two it mixes search and test without anyone
 * noticing, which is the whole thing the segmentation exists to prevent. This
 * list cannot express a crossing run — there is no entry for one.</p>
 *
 * <p>And when a series is marked segments-only, the whole-series entry is not
 * offered at all. That mark is how a reader says "this one must never be run
 * end to end", and an entry that ignored it would make the mark decorative.</p>
 *
 * @param key   the name the application knows, {@code winfull-1m} or
 *              {@code winfull-1m#busca}
 * @param label what the reader sees
 */
record SeriesChoice(String key, String label) {

    @Override
    public String toString() {
        return label;
    }

    /**
     * Every series and segment worth offering — <b>tapes included</b>.
     *
     * <p>Through {@code Segmentable.keys()} rather than the catalog's own list,
     * which holds candle files only. A tape is a series: it has sessions, it can
     * be segmented, and it is the one source where a tick-by-tick run is walking
     * prints that actually happened. Leaving it out of this list was leaving the
     * only real evidence out of the backtest.</p>
     *
     * <p>No filter for retired series here: {@code names()} already drops them,
     * and a second guard over the same set could never fire — it would read like
     * the thing keeping a retired series out while doing nothing at all.</p>
     */
    static List<SeriesChoice> available() {
        List<SeriesChoice> found = new ArrayList<>();

        for (String name : Segmentable.keys()) {
            String display = Segmentable.isTicks(name)
                    ? Segmentable.labelOf(name)
                    : SeriesCatalog.displayOf(name);

            List<Segment> segments = Segmentation.of(name);

            if (!Segmentation.segmentsOnly(name)) {
                found.add(new SeriesChoice(name, display));
            }

            for (Segment segment : segments) {
                found.add(new SeriesChoice(Segmentation.nameOf(name, segment),
                        display + " › " + segment.label()));
            }
        }

        return found;
    }

    /** @return whether this entry is a tape rather than a file of candles */
    boolean isTape() {
        return Segmentable.isTicks(Segmentation.seriesIn(key));
    }

    /**
     * Reads the bars this entry stands for.
     *
     * @return the series, cut to the segment when there is one
     * @throws IOException if the series cannot be read
     */
    PriceSeries open() throws IOException {
        String name = Segmentation.seriesIn(key);
        PriceSeries whole = Segmentable.isTicks(name) ? tape(name) : file(name);

        // SegmentedSeries hands back the series itself when the segment is
        // null, so nothing below has to know which of the two it got.
        return SegmentedSeries.of(whole, Segmentation.segmentIn(key), Timeframe.defaultZone());
    }

    private static PriceSeries file(String name) throws IOException {
        PriceSeries whole = SeriesCatalog.open(name).orElse(null);

        if (whole == null) {
            throw new IOException(name);
        }

        return whole;
    }

    /**
     * A tape, read as minutes.
     *
     * <p>Minutes and not prints, because this is what everything upstream of the
     * run works in — the recorte, the scale, the strategy's own candles. The
     * prints come back later, and only if the run is executed tick by tick; see
     * {@code TickLevel}.</p>
     */
    private static PriceSeries tape(String name) {
        String instrument = Segmentable.instrumentOf(name);

        return br.com.jorge.reis.endeavourneo.domain.market.FoldedTicks.all(
                br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.ticksOf(instrument),
                instrument, Segmentable.sourceOf(name), Timeframe.defaultZone());
    }

    /**
     * The days this entry actually has.
     *
     * <p>Of the <b>cut</b> series, not of the series it came from — which is
     * the whole point. {@code Segmentable.sessionsOf} takes a series name and
     * would be handed {@code winfull-1m#busca}: it cannot open that, answers
     * with nothing, and the date fields fall back to "any weekday", offering
     * days the segment does not contain. Opening through {@link #open()} makes
     * the segment part of the answer by construction.</p>
     *
     * @return every session, in order; empty when the series will not read
     */
    java.util.NavigableSet<java.time.LocalDate> sessions() {
        try {
            PriceSeries bars = open();

            return bars.size() == 0
                    ? new java.util.TreeSet<>()
                    : br.com.jorge.reis.endeavourneo.domain.market.Sessions.of(bars);
        } catch (IOException unreadable) {
            // Empty is the right answer -- a series that will not read has no
            // days to offer -- but said out loud, because "no dates" and "this
            // file is broken" look identical in a combo box.
            System.err.println(key + ": the sessions could not be read (" + unreadable + ")");

            return new java.util.TreeSet<>();
        }
    }

    /**
     * @return whether this series is stored at a scale made of time
     *
     * <p>A renko series is not, and aggregating one by the clock would bucket
     * bricks that took three seconds together with bricks that took forty
     * minutes. Where this is false the scale list has nothing to offer and the
     * bars are used as they are stored.</p>
     */
    boolean measuredInTime() {
        String name = Segmentation.seriesIn(key);

        // A tape arrives folded into minutes, so it is measured in time whatever
        // its key looks like -- the key names a source, not a scale.
        return Segmentable.isTicks(name)
                || SeriesCatalog.secondsOf(SeriesCatalog.scaleOf(name)) > 0;
    }
}
