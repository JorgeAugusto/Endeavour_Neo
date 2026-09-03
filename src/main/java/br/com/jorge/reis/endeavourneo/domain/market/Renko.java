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
 * <p><b>Built from the extremes each bar reached</b>, in the order a bar of that
 * direction most likely reached them: a rising bar dips before it climbs. The
 * brick itself still has no wick — it runs from one level to the next.</p>
 *
 * <p>The first version read <b>closes only</b>, on the argument that highs and
 * lows let one violent minute manufacture a trend that was never traded
 * through. That argument is still true, and it lost to a bigger one: a renko
 * built from closes <b>cannot be rebuilt from scratch</b> while a bar is still
 * forming. The forming close wanders across a level, a brick is laid, the close
 * comes back and the brick is taken away again. Measured in ten minutes of
 * replay: of the hundred and thirty-two times the chart changed, sixty-five
 * were a brick disappearing. Extremes only widen as a bar forms, so a brick
 * laid from them stays laid.</p>
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

    private final boolean wicks;

    /**
     * @param brick the height of one brick, in price units
     * @param reversal how many bricks a turn costs; 2 is classic renko
     */
    public Renko(double brick, int reversal) {
        this(brick, reversal, true);
    }

    /**
     * @param wicks whether a brick shows how far price went against it first
     *
     * <p>On by default. A bare brick says a level was crossed and nothing else;
     * with the tail it also says the move was fought — that price went thirty
     * points the other way before it went through. Two charts of the same day
     * can look identical without it and completely different with it.</p>
     */
    public Renko(double brick, int reversal, boolean wicks) {
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
        this.wicks = wicks;
    }

    public boolean hasWicks() {
        return wicks;
    }

    /** @return the same bricks, with the tails on or off */
    public Renko withWicks(boolean showWicks) {
        return showWicks == wicks ? this : new Renko(brick, reversal, showWicks);
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

        return height + (wicks ? " renko" : " renko sem calda");
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

        // How far price ran the other way since the last brick. It becomes the
        // tail of whichever brick finally goes through.
        double sinceLow = anchor;
        double sinceHigh = anchor;

        for (int i = 0; i < source.size(); i++) {
            double volume = source.volumeAt(i);

            if (Double.isFinite(volume)) {
                pending += volume;
                anyVolume = true;
            }

            // Both extremes, in the order the bar most likely reached them: a
            // rising bar dips before it climbs. Reading the CLOSE alone was the
            // first version and it cannot survive being rebuilt every frame --
            // a forming bar's close wanders back and forth across a level, so a
            // brick appeared and then vanished. Measured, in ten minutes of
            // replay: sixty-five of the hundred and thirty-two changes on screen
            // were a brick being un-laid.
            //
            // Extremes only ever widen while a bar forms, so a brick laid from
            // them stays laid. That is the whole reason for the change.
            // The order comes from the PREVAILING RENKO TREND, not from the
            // bar's own open-to-close. Using the bar's direction was the second
            // attempt and still flickered: a forming bar's close crosses its
            // open, "rising" flips, the two extremes swap places and the bricks
            // are rebuilt in a different order. The renko trend only changes
            // when a brick is laid, so it cannot flip underneath a bar that is
            // still forming.
            sinceLow = Math.min(sinceLow, source.lowAt(i));
            sinceHigh = Math.max(sinceHigh, source.highAt(i));

            double[] reached = direction >= 0
                    ? new double[]{source.lowAt(i), source.highAt(i)}
                    : new double[]{source.highAt(i), source.lowAt(i)};

            int made = 0;

            for (double price : reached) {
                double upNeeded = direction >= 0 ? brick : reversal * brick;
                double downNeeded = direction <= 0 ? brick : reversal * brick;

                if (price - anchor >= upNeeded) {
                    int count = (int) Math.floor((price - anchor) / brick);
                    int at = bricks.size();

                    anchor = laydown(bricks, stamps, anchor, count, +1, source.timeAt(i));
                    direction = +1;
                    made += count;

                    // The tail goes on the FIRST brick of the batch: that is the
                    // one that was being fought while the others had already
                    // gone through.
                    if (wicks) {
                        bricks.get(at)[2] = Math.min(bricks.get(at)[2], sinceLow);
                    }
                } else if (anchor - price >= downNeeded) {
                    int count = (int) Math.floor((anchor - price) / brick);
                    int at = bricks.size();

                    anchor = laydown(bricks, stamps, anchor, count, -1, source.timeAt(i));
                    direction = -1;
                    made += count;

                    if (wicks) {
                        bricks.get(at)[1] = Math.max(bricks.get(at)[1], sinceHigh);
                    }
                }

                if (made > 0) {
                    sinceLow = anchor;
                    sinceHigh = anchor;
                }
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
