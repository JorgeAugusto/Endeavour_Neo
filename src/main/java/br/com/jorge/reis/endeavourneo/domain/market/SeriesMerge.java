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

/**
 * Two raw exports of one instrument, joined into one series.
 *
 * <h2>The rule</h2>
 *
 * <p>Everything from the older export that happened <b>strictly before</b> the
 * newer one begins, then the whole of the newer one. One source at a time, one
 * change of source, and no bar taken from both.</p>
 *
 * <p>Blending the overlap would be worse than choosing. Measured on WIN$N
 * against WINFUT over the 86 sessions they share: 97,6% of the minutes are
 * identical, and the ones that are not are not noise. Two whole sessions
 * disagree — 13/10/2021 and 15/12/2021, by up to 1.970 points — because those
 * are contract roll days and on them the two exports are following different
 * contracts. Every other session differs in exactly one minute. An average of
 * the two would invent a price that neither feed ever printed, on precisely the
 * days that matter most.</p>
 *
 * <h2>Where the join must not fall</h2>
 *
 * <p>On a roll day. The two exports are then 1.000 to 2.000 points apart, and a
 * join there would put a step in the series that no trade made — every
 * indicator crossing it would see a gap the market never had. {@link #stepAt}
 * measures the step so the caller can refuse: it is the one number that says
 * whether a join is sound.</p>
 */
public final class SeriesMerge {

    private SeriesMerge() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param older the export that reaches further back
     * @param newer the export that reaches further forward
     * @return the two as one series, in time order
     */
    public static PriceSeries of(PriceSeries older, PriceSeries newer) {
        if (older == null || older.size() == 0) {
            return newer == null ? PriceSeries.empty() : newer;
        }

        if (newer == null || newer.size() == 0) {
            return older;
        }

        long seam = newer.timeAt(0);
        int keep = countBefore(older, seam);

        if (keep == 0) {
            return newer;
        }

        return new Joined(older, keep, newer);
    }

    /**
     * @return how far the price jumps across the join, in points
     *
     * <p>The close of the last bar taken from the older export against the open
     * of the first bar of the newer one. On a sound join this is what any two
     * consecutive bars show — a few points, or nothing. On a roll day it is a
     * thousand.</p>
     */
    public static double stepAt(PriceSeries older, PriceSeries newer) {
        if (older == null || newer == null || older.size() == 0 || newer.size() == 0) {
            return 0;
        }

        int keep = countBefore(older, newer.timeAt(0));

        if (keep == 0) {
            // NOT ZERO. Nothing of the older export survives -- it begins after
            // the newer one, so the two were handed over the wrong way round or
            // they do not overlap the way this method assumes. Zero is the value
            // that says "the join is seamless, go ahead": the one number written
            // to let a caller REFUSE was answering yes to the one case where
            // there is nothing to join.
            //
            // NaN because there is no step to measure, and because it compares
            // false against every threshold a caller might set -- so the refusal
            // is what happens by default rather than what has to be remembered.
            return Double.NaN;
        }

        return Math.abs(newer.openAt(0) - older.closeAt(keep - 1));
    }

    /** @return how many of the older bars start before that instant */
    public static int countBefore(PriceSeries series, long when) {
        int low = 0;
        int high = series.size() - 1;
        int found = 0;

        // Binary search rather than a scan: the older export is 362 thousand
        // bars and this is asked for again on every read of the joined base.
        while (low <= high) {
            int middle = (low + high) >>> 1;

            if (series.timeAt(middle) < when) {
                found = middle + 1;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }

        return found;
    }

    /**
     * A view, not a copy.
     *
     * <p>The two exports are 33 and 17 megabytes. Copying them into a third
     * array to hand back would double that for no gain — the bars are already
     * in memory and neither of them changes.</p>
     */
    private record Joined(PriceSeries older, int keep, PriceSeries newer) implements PriceSeries {

        @Override
        public int size() {
            return keep + newer.size();
        }

        @Override
        public long timeAt(int index) {
            return index < keep ? older.timeAt(index) : newer.timeAt(index - keep);
        }

        @Override
        public double openAt(int index) {
            return index < keep ? older.openAt(index) : newer.openAt(index - keep);
        }

        @Override
        public double highAt(int index) {
            return index < keep ? older.highAt(index) : newer.highAt(index - keep);
        }

        @Override
        public double lowAt(int index) {
            return index < keep ? older.lowAt(index) : newer.lowAt(index - keep);
        }

        @Override
        public double closeAt(int index) {
            return index < keep ? older.closeAt(index) : newer.closeAt(index - keep);
        }

        @Override
        public double volumeAt(int index) {
            return index < keep ? older.volumeAt(index) : newer.volumeAt(index - keep);
        }
    }
}
