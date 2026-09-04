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
 * Bars held in plain arrays: what an {@link Aggregation} hands back.
 *
 * <p>Parallel arrays rather than an array of bar objects. Four years of minutes
 * is around half a million bars, and half a million small objects is half a
 * million headers and a scattered heap for something the chart walks in order on
 * every repaint. The arrays are contiguous and the difference is felt.</p>
 *
 * <p>The class takes the arrays as they are and does not copy them. It is
 * package-private on purpose: only the aggregations build these, and they build
 * them freshly. Nobody outside holds a reference to write through.</p>
 */
final class ArraySeries implements PriceSeries, Untraded {

    private final long[] times;

    private final double[] opens;

    private final double[] highs;

    private final double[] lows;

    private final double[] closes;

    private final double[] volumes;

    /**
     * Which bars hold no trade at all, or null when the question does not
     * arise.
     *
     * <p>Null for everything but renko. A time-cut bar cannot be untraded --
     * a minute with no trade is simply absent -- so carrying an all-false
     * array of half a million entries for every fold would be paying for an
     * answer nobody asks. See {@link Untraded}.</p>
     */
    private final boolean[] untraded;

    ArraySeries(long[] times, double[] opens, double[] highs,
                double[] lows, double[] closes, double[] volumes) {
        this(times, opens, highs, lows, closes, volumes, null);
    }

    ArraySeries(long[] times, double[] opens, double[] highs,
                double[] lows, double[] closes, double[] volumes,
                boolean[] untraded) {
        this.times = times;
        this.opens = opens;
        this.highs = highs;
        this.lows = lows;
        this.closes = closes;
        this.volumes = volumes;
        this.untraded = untraded;
    }

    @Override
    public boolean untradedAt(int index) {
        return untraded != null && untraded[index];
    }

    @Override
    public int size() {
        return times.length;
    }

    @Override
    public long timeAt(int index) {
        return times[index];
    }

    @Override
    public double openAt(int index) {
        return opens[index];
    }

    @Override
    public double highAt(int index) {
        return highs[index];
    }

    @Override
    public double lowAt(int index) {
        return lows[index];
    }

    @Override
    public double closeAt(int index) {
        return closes[index];
    }

    @Override
    public double volumeAt(int index) {
        return volumes[index];
    }
}
