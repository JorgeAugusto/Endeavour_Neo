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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The path a bar really took, from the exchange's own ticks.
 *
 * <p>Falls back to a synthetic walk for any bar it has no ticks for, which is
 * most of them: the base runs from 2018 to 2026 and the tick export covers one
 * month. The fallback can be refused — see the constructor — for a reader who
 * would rather see nothing than see a guess.</p>
 *
 * <p><b>The ticks are not waited for.</b> {@link TickLibrary#at} answers with
 * what is in memory and nothing else, because this is called while the chart is
 * painting. A session still loading therefore draws synthetic for a moment and
 * then the real thing, which is the right trade: a quarter-second freeze in the
 * middle of an animation is worse than a quarter-second of approximation.</p>
 */
public final class RecordedTicks implements TickPath {

    private static final long ASSUMED_BAR = 60_000L;

    private final TickLibrary library;

    private final ZoneId zone;

    private final TickPath fallback;

    /**
     * @param fallback what to use where there are no ticks, or null to refuse
     */
    public RecordedTicks(TickLibrary library, TickPath fallback) {
        this(library, fallback, ZoneId.systemDefault());
    }

    public RecordedTicks(TickLibrary library, TickPath fallback, ZoneId zone) {
        this.library = library;
        this.fallback = fallback;
        this.zone = zone;
    }

    /** @return whether that bar can be drawn from real ticks right now */
    public boolean isRecorded(PriceSeries series, int index) {
        if (index < 0 || index >= series.size()) {
            return false;
        }

        return library.at(dayOf(series.timeAt(index))) != null;
    }

    @Override
    public double[] pathFor(PriceSeries series, int index) {
        if (index < 0 || index >= series.size()) {
            return fallback == null ? null : fallback.pathFor(series, index);
        }

        long from = series.timeAt(index);
        TickSeries ticks = library.at(dayOf(from));

        if (ticks == null) {
            return fallback == null ? null : fallback.pathFor(series, index);
        }

        long to = endOf(series, index, from);
        int first = firstAtOrAfter(ticks, from);
        int count = 0;

        for (int i = first; i < ticks.size() && ticks.timeAt(i) < to; i++) {
            if (ticks.hasLast(i)) {
                count++;
            }
        }

        if (count < 2) {
            // A bar the export has no trades for. It happens at the edges of a
            // session and on the days the exchange barely opened. One price is
            // not a path, and pretending otherwise would freeze the animation
            // on that bar.
            return fallback == null ? null : fallback.pathFor(series, index);
        }

        double[] path = new double[count];
        int at = 0;

        for (int i = first; i < ticks.size() && ticks.timeAt(i) < to; i++) {
            if (ticks.hasLast(i)) {
                path[at++] = ticks.lastAt(i);
            }
        }

        return path;
    }

    /**
     * @return where the bar ends
     *
     * <p>The next bar's opening instant. For the last bar there is none, so the
     * width of the one before is used — and a minute when even that is missing.
     * Guessing long would pull the next bar's ticks into this one; guessing
     * short would cut the bar's own tail off.</p>
     */
    private static long endOf(PriceSeries series, int index, long from) {
        if (index + 1 < series.size()) {
            return series.timeAt(index + 1);
        }

        if (index > 0) {
            return from + Math.max(1L, from - series.timeAt(index - 1));
        }

        return from + ASSUMED_BAR;
    }

    /** @return the first tick at or after that instant */
    private static int firstAtOrAfter(TickSeries ticks, long when) {
        int low = 0;
        int high = ticks.size() - 1;
        int found = ticks.size();

        while (low <= high) {
            int middle = (low + high) >>> 1;

            if (ticks.timeAt(middle) >= when) {
                found = middle;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }

        return found;
    }

    private LocalDate dayOf(long when) {
        return Instant.ofEpochMilli(when).atZone(zone).toLocalDate();
    }
}
