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

import java.util.ArrayList;
import java.util.List;

/**
 * Several sessions read as one continuous series.
 *
 * <p>Made for the replay, which needs the days before the one being played so
 * the chart does not start on an empty screen. It is a <b>view</b>: the parts
 * are not copied, and a hundred days joined here cost one array of offsets.</p>
 *
 * <p>The parts must already be in order. Nothing here checks that, because
 * checking would mean reading every bar of every part on construction — the one
 * thing this class exists to avoid.</p>
 */
public final class ConcatSeries implements PriceSeries {

    private final PriceSeries[] parts;

    /** Where each part starts, plus the total at the end. */
    private final int[] starts;

    private ConcatSeries(PriceSeries[] parts, int[] starts) {
        this.parts = parts;
        this.starts = starts;
    }

    /**
     * @param parts the sessions, oldest first
     * @return them as one series
     */
    public static PriceSeries of(List<PriceSeries> parts) {
        if (parts == null || parts.isEmpty()) {
            return PriceSeries.empty();
        }

        List<PriceSeries> kept = new ArrayList<>(parts.size());

        for (PriceSeries part : parts) {
            // Empty parts are dropped rather than kept as zero-width entries:
            // a market holiday in the middle would otherwise put a boundary in
            // the offsets that no bar ever lands on.
            if (part != null && part.size() > 0) {
                kept.add(part);
            }
        }

        if (kept.isEmpty()) {
            return PriceSeries.empty();
        }

        if (kept.size() == 1) {
            return kept.get(0);
        }

        int[] starts = new int[kept.size() + 1];

        for (int i = 0; i < kept.size(); i++) {
            starts[i + 1] = starts[i] + kept.get(i).size();
        }

        return new ConcatSeries(kept.toArray(new PriceSeries[0]), starts);
    }

    /**
     * @return which part holds that index
     *
     * <p>Binary search rather than a walk: the chart asks for a bar several
     * times per repaint, and a hundred parts walked linearly on every question
     * would be felt.</p>
     */
    private int partOf(int index) {
        if (index < 0 || index >= starts[starts.length - 1]) {
            throw new IndexOutOfBoundsException(
                    "bar " + index + " of " + starts[starts.length - 1]);
        }

        int low = 0;
        int high = parts.length - 1;

        while (low < high) {
            int middle = (low + high + 1) >>> 1;

            if (starts[middle] <= index) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }

        return low;
    }

    @Override
    public int size() {
        return starts[starts.length - 1];
    }

    @Override
    public long timeAt(int index) {
        int part = partOf(index);

        return parts[part].timeAt(index - starts[part]);
    }

    @Override
    public double openAt(int index) {
        int part = partOf(index);

        return parts[part].openAt(index - starts[part]);
    }

    @Override
    public double highAt(int index) {
        int part = partOf(index);

        return parts[part].highAt(index - starts[part]);
    }

    @Override
    public double lowAt(int index) {
        int part = partOf(index);

        return parts[part].lowAt(index - starts[part]);
    }

    @Override
    public double closeAt(int index) {
        int part = partOf(index);

        return parts[part].closeAt(index - starts[part]);
    }

    @Override
    public double volumeAt(int index) {
        int part = partOf(index);

        return parts[part].volumeAt(index - starts[part]);
    }
}
