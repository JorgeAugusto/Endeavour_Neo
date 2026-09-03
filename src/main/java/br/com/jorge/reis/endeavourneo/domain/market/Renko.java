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
 * Bricks of a fixed height, laid down only when price moves that far.
 *
 * <p><b>Time leaves the horizontal axis.</b> A brick can take four seconds or
 * four hours, and a quiet afternoon produces none at all. This is the whole
 * point of the thing — noise below the brick height disappears — and it is also
 * everything that has to be decided differently downstream: the chart spaces
 * bricks evenly by index (which is what every platform does, and what our
 * viewport already assumes), so the <b>time labels come out irregular</b>. They
 * are honest that way; evenly spaced time labels over renko would be a lie.</p>
 *
 * <h2>Three choices made here, none of them the only defensible one</h2>
 *
 * <p><b>Closes only, and no wicks.</b> A brick is drawn from one price level to
 * the next, so its high is its top and its low is its bottom. The variant that
 * reads the source bars' highs and lows produces more bricks on spikes; it is
 * also the variant where a single violent minute manufactures a trend that was
 * never traded through. Closes are the conservative reading.</p>
 *
 * <p><b>A reversal costs {@link #reversal()} bricks, a continuation one.</b>
 * Classic renko asks two for a turn, and that asymmetry is the filter: it is
 * what stops price oscillating around one level from painting a staircase of
 * alternating bricks. Set it to 1 and every crossing of a level draws a brick.</p>
 *
 * <p><b>A brick carries the time of the source bar that completed it.</b> When
 * one minute completes three bricks, the three share a timestamp. Nothing else
 * is true — they really did all happen inside that minute.</p>
 *
 * <p>Volume is accumulated between bricks and split equally among however many
 * complete at once. That split is a convention, not a measurement: the data does
 * not say which part of a minute's volume belonged to which brick.</p>
 *
 * <h2>Against ta4j, which was read before this was written</h2>
 *
 * <p>Its {@code RenkoBarAggregator} agrees on everything structural — closes
 * only, a box size, a reversal in boxes, two by default, anchored on the first
 * close. Three things differ, and each was a choice rather than an oversight:</p>
 *
 * <ul>
 *   <li><b>It refuses unevenly spaced source bars</b> and throws. Ours are
 *       unevenly spaced by nature — nights and weekends — so that aggregator
 *       could not read this project's data at all. That alone settled whether to
 *       write this.</li>
 *   <li><b>It gives all the volume to the first brick</b> of a batch and zero to
 *       the rest. Zero is as much a claim as a share is; we spread it, and say
 *       here that it is a convention.</li>
 *   <li><b>It advances the timestamps</b> so bricks from one bar differ. That is
 *       required by its own series, which will not take two bars at the same
 *       instant. Our chart places bricks by index, so we can keep the true time
 *       instead of inventing gaps inside a minute.</li>
 * </ul>
 */
public final class Renko implements Aggregation {

    private final double brick;

    private final int reversal;

    /**
     * @param brick the height of one brick, in price units
     * @param reversal how many bricks a turn costs; 2 is classic renko
     */
    public Renko(double brick, int reversal) {
        if (!(brick > 0.0) || !Double.isFinite(brick)) {
            throw new IllegalArgumentException(
                    "a brick has to have a height greater than zero: " + brick);
        }

        if (reversal < 1) {
            throw new IllegalArgumentException(
                    "a reversal costs at least one brick: " + reversal);
        }

        this.brick = brick;
        this.reversal = reversal;
    }

    /** @param brick the height of one brick; a turn costs two, as in classic renko */
    public static Renko of(double brick) {
        return new Renko(brick, 2);
    }

    public double brick() {
        return brick;
    }

    public int reversal() {
        return reversal;
    }

    @Override
    public String label() {
        // Trimmed of a pointless ".0": the brick is usually a round number of
        // points and "30 renko" reads better than "30,0 renko" in a title.
        String height = brick == Math.rint(brick)
                ? String.valueOf((long) brick)
                : String.valueOf(brick);

        return height + " renko";
    }

    @Override
    public String toString() {
        return label();
    }

    @Override
    public PriceSeries apply(PriceSeries source) {
        if (source == null || source.size() == 0) {
            return PriceSeries.empty();
        }

        List<double[]> bricks = new ArrayList<>();
        List<Long> stamps = new ArrayList<>();

        // The level the last brick closed at. It starts at the first close, so
        // the first brick is measured from where the series actually begins and
        // not from a rounded grid the data never touched.
        double anchor = source.closeAt(0);
        int direction = 0;
        double pending = 0.0;
        boolean anyVolume = false;

        for (int i = 0; i < source.size(); i++) {
            double close = source.closeAt(i);
            double volume = source.volumeAt(i);

            if (Double.isFinite(volume)) {
                pending += volume;
                anyVolume = true;
            }

            // How far it has to go depends on whether it is carrying on or
            // turning round. That asymmetry IS the filter.
            double upNeeded = direction >= 0 ? brick : reversal * brick;
            double downNeeded = direction <= 0 ? brick : reversal * brick;

            int made = 0;

            if (close - anchor >= upNeeded) {
                made = (int) Math.floor((close - anchor) / brick);
                anchor = laydown(bricks, stamps, anchor, made, +1, source.timeAt(i));
                direction = +1;
            } else if (anchor - close >= downNeeded) {
                made = (int) Math.floor((anchor - close) / brick);
                anchor = laydown(bricks, stamps, anchor, made, -1, source.timeAt(i));
                direction = -1;
            }

            if (made > 0) {
                double each = pending / made;

                for (int b = bricks.size() - made; b < bricks.size(); b++) {
                    bricks.get(b)[4] = each;
                }

                pending = 0.0;
            }
        }

        return assemble(bricks, stamps, anyVolume);
    }

    /**
     * Lays {@code count} bricks in {@code step} direction and returns the new anchor.
     *
     * <p>One at a time and not one tall brick: a move of three brick heights is
     * three bricks, which is exactly what makes the chart show the size of a
     * move as a length rather than as a number to read off an axis.</p>
     */
    private double laydown(List<double[]> bricks, List<Long> stamps,
                           double anchor, int count, int step, long time) {
        double level = anchor;

        for (int b = 0; b < count; b++) {
            double open = level;
            double close = level + step * brick;

            bricks.add(new double[]{open, Math.max(open, close), Math.min(open, close), close, 0.0});
            stamps.add(time);

            level = close;
        }

        return level;
    }

    private static PriceSeries assemble(List<double[]> bricks, List<Long> stamps,
                                        boolean anyVolume) {
        int size = bricks.size();

        long[] times = new long[size];
        double[] opens = new double[size];
        double[] highs = new double[size];
        double[] lows = new double[size];
        double[] closes = new double[size];
        double[] volumes = new double[size];

        for (int i = 0; i < size; i++) {
            double[] one = bricks.get(i);

            times[i] = stamps.get(i);
            opens[i] = one[0];
            highs[i] = one[1];
            lows[i] = one[2];
            closes[i] = one[3];
            volumes[i] = anyVolume ? one[4] : Double.NaN;
        }

        return new ArraySeries(times, opens, highs, lows, closes, volumes);
    }
}
