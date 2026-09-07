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
import java.time.ZoneId;

/**
 * A slice of a base, seen as a series in its own right.
 *
 * <p><b>Nothing is copied.</b> This is a window: {@code closeAt(0)} here is
 * {@code closeAt(first)} on the base. No second file on disk, no second copy in
 * memory — and, more importantly, the chart and the backtest engine cannot tell
 * they are looking at a slice, which is why nothing else had to change to
 * support this.</p>
 *
 * <p>The dates are resolved to indices once, when the slice is made, by binary
 * search over the bar times. The series is chronological, so that costs
 * {@code log n} instead of a walk over four years of minutes on every open. The
 * consequence to know: <b>a slice made before an import does not see the bars
 * that import added.</b> Make it again after loading, which is what the window
 * does anyway.</p>
 */
public final class SegmentedSeries implements PriceSeries {

    private final PriceSeries base;

    private final String label;

    private final int first;

    private final int count;

    private SegmentedSeries(PriceSeries base, String label, int first, int count) {
        this.base = base;
        this.label = label;
        this.first = first;
        this.count = count;
    }

    /**
     * @param base the whole series
     * @param segment the slice wanted, or null for all of it
     * @param zone the zone whose days the segment's dates mean
     * @return the slice, or the base itself when there is nothing to slice
     *
     * <p>A segment covering nothing gives an empty series rather than throwing:
     * a date range outside the base is a normal thing to ask for while typing
     * one in, and an empty chart says so better than a dialog.</p>
     */
    public static PriceSeries of(PriceSeries base, Segment segment, ZoneId zone) {
        if (base == null) {
            return PriceSeries.empty();
        }

        if (segment == null || base.size() == 0) {
            return base;
        }

        ZoneId at = zone == null ? Timeframe.defaultZone() : zone;

        // The first bar at or after the segment's first midnight, and the first
        // bar at or after the midnight FOLLOWING its last day -- so the last day
        // is included whole, whatever time its final bar carries.
        int from = firstAtOrAfter(base, segment.from().atStartOfDay(at).toInstant().toEpochMilli());
        int until = segment.isOpenEnded()
                ? base.size()
                : firstAtOrAfter(base,
                        segment.to().plusDays(1).atStartOfDay(at).toInstant().toEpochMilli());

        return new SegmentedSeries(base, segment.label(), from, Math.max(0, until - from));
    }

    /**
     * @return the index of the first bar whose time is at or after {@code millis},
     *         or {@code size()} when every bar is earlier
     */
    private static int firstAtOrAfter(PriceSeries series, long millis) {
        int low = 0;
        int high = series.size();

        while (low < high) {
            int middle = (low + high) >>> 1;

            if (series.timeAt(middle) < millis) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }

        return low;
    }

    /** @return the slice's name, for the title and beside a result */
    public String label() {
        return label;
    }

    @Override
    public int size() {
        return count;
    }

    @Override
    public long timeAt(int index) {
        return base.timeAt(translate(index));
    }

    @Override
    public double openAt(int index) {
        return base.openAt(translate(index));
    }

    @Override
    public double highAt(int index) {
        return base.highAt(translate(index));
    }

    @Override
    public double lowAt(int index) {
        return base.lowAt(translate(index));
    }

    @Override
    public double closeAt(int index) {
        return base.closeAt(translate(index));
    }

    @Override
    public double volumeAt(int index) {
        return base.volumeAt(translate(index));
    }

    /**
     * @throws IndexOutOfBoundsException naming the slice, not the base
     *
     * <p>The slice's own index in the message: a report of "index 40000 out of
     * bounds" on a slice of 300 bars sends the reader looking in the wrong
     * place entirely.</p>
     */
    private int translate(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(
                    "bar " + index + " of a slice with " + count + " bars (" + label + ")");
        }

        return first + index;
    }
}
