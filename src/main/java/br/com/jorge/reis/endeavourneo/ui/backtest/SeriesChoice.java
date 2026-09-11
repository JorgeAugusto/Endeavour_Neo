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

    /** @return every series and segment worth offering, series by series */
    static List<SeriesChoice> available() {
        List<SeriesChoice> found = new ArrayList<>();

        // NO FILTER FOR RETIRED SERIES HERE. names() already drops them, and a
        // second guard over the same set could never fire -- it would read like
        // the thing keeping a retired series out while doing nothing at all.
        for (String name : SeriesCatalog.names()) {
            String display = SeriesCatalog.displayOf(name);
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

    /**
     * Reads the bars this entry stands for.
     *
     * @return the series, cut to the segment when there is one
     * @throws IOException if the series cannot be read
     */
    PriceSeries open() throws IOException {
        String name = Segmentation.seriesIn(key);
        PriceSeries whole = SeriesCatalog.open(name).orElse(null);

        if (whole == null) {
            throw new IOException(name);
        }

        // SegmentedSeries hands back the series itself when the segment is
        // null, so nothing below has to know which of the two it got.
        return SegmentedSeries.of(whole, Segmentation.segmentIn(key), Timeframe.defaultZone());
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
        return SeriesCatalog.secondsOf(SeriesCatalog.scaleOf(Segmentation.seriesIn(key))) > 0;
    }
}
