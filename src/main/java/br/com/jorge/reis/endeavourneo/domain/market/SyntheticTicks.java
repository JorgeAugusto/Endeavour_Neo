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

import java.util.Random;

/**
 * A plausible path of prices inside one bar.
 *
 * <p>A stored bar keeps four numbers; what happened between them is gone. This
 * makes up a route that passes through all four in a believable order, so a bar
 * can be watched forming instead of appearing whole.</p>
 *
 * <h2>The four rules</h2>
 *
 * <ol>
 *   <li><b>The order follows the direction.</b> A rising bar goes
 *       open → low → high → close; a falling one, open → high → low → close.
 *       It is the assumption MetaTrader makes in its "OHLC on M1" mode and the
 *       most defensible one without real ticks.</li>
 *   <li><b>Each leg is a Brownian bridge.</b> The noise dies at both ends —
 *       it carries a factor of {@code sqrt(t·(1−t))} — so the path arrives
 *       exactly on target instead of jumping there on the last step. That is
 *       what makes it read as movement rather than as a saw.</li>
 *   <li><b>How many: {@code 4 + 2 × range in ticks}</b>, floored at 6 and capped
 *       at 60. A big bar earns more movement; a doji earns little.</li>
 *   <li><b>The prices in between land on the tick grid</b>; the open, high, low
 *       and close are the bar's own true values. Rounding those would leave a
 *       path that ends somewhere the bar did not close, and renko built on the
 *       ticks would then disagree with renko built on the closes.</li>
 * </ol>
 *
 * <p><b>The path is invention.</b> It reproduces four true prices and makes up
 * the rest. It is for watching. It is <b>not</b> for measuring: a backtest run
 * over these ticks measures this bridge, not the market.</p>
 *
 * <p>The route for a given bar is the same every time it is asked for — the seed
 * mixes in the bar's index. Scrubbing backwards and forwards over the same
 * minute has to redraw the same minute.</p>
 */
public final class SyntheticTicks {

    private static final int FLOOR = 6;

    private static final int CEILING = 60;

    /** How far the bridge is allowed to wander off the straight line. */
    private static final double WOBBLE = 0.9;

    private final double tick;

    private final long seed;

    /**
     * @param tick the smallest price step the instrument moves in
     * @param seed what makes this replay this replay
     */
    public SyntheticTicks(double tick, long seed) {
        this.tick = tick > 0 ? tick : 1.0;
        this.seed = seed;
    }

    /** @return how many prices the bar at that index will be broken into */
    public int countFor(PriceSeries series, int index) {
        int ticks = (int) Math.max(1, Math.round((series.highAt(index) - series.lowAt(index)) / tick));

        return Math.max(FLOOR, Math.min(4 + ticks * 2, CEILING));
    }

    /**
     * @return the prices inside that bar, the first being its open and the last
     *         its close
     */
    public double[] pathFor(PriceSeries series, int index) {
        double open = series.openAt(index);
        double high = series.highAt(index);
        double low = series.lowAt(index);
        double close = series.closeAt(index);

        // Mixed with the index so each bar has its own route, and the same route
        // every time: scrubbing back over a minute must redraw that minute.
        Random random = new Random(seed * 1_000_003L + index);

        boolean rising = close >= open;
        double first = rising ? low : high;
        double second = rising ? high : low;

        int total = countFor(series, index);
        double[] legs = {Math.abs(first - open), Math.abs(second - first), Math.abs(close - second)};
        double travelled = legs[0] + legs[1] + legs[2];

        int[] each = counts(legs, travelled, total);
        double[] path = new double[1 + each[0] + each[1] + each[2]];

        path[0] = open;

        int at = 1;

        at = bridge(path, at, open, first, each[0], low, high, random);
        at = bridge(path, at, first, second, each[1], low, high, random);
        bridge(path, at, second, close, each[2], low, high, random);

        return path;
    }

    /** Shares the movements between the three legs, by how far each one travels. */
    private static int[] counts(double[] legs, double travelled, int total) {
        if (travelled <= 0.0) {
            // A bar with no range at all: four identical prices. One movement
            // per leg, so the path still has a shape to walk along.
            return new int[]{1, 1, 1};
        }

        int[] each = new int[3];

        for (int i = 0; i < 3; i++) {
            each[i] = Math.max(1, (int) Math.round(total * legs[i] / travelled));
        }

        return each;
    }

    /**
     * Walks from {@code from} to {@code to} in {@code steps}, wandering on the way.
     *
     * @return where the next leg should start writing
     */
    private int bridge(double[] path, int at, double from, double to, int steps,
                       double low, double high, Random random) {
        double span = Math.abs(to - from);
        double reach = span > 0 ? span : tick;

        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            double straight = from + (to - from) * t;

            // sqrt(t(1-t)) is zero at both ends and widest in the middle: the
            // wander is largest where there is room for it and vanishes exactly
            // where the path has to hit its target.
            double noise = WOBBLE * reach * Math.sqrt(t * (1 - t)) * random.nextGaussian();

            path[at + i - 1] = Math.min(high, Math.max(low, snap(straight + noise)));
        }

        // The end of a leg is one of the bar's four real prices, unrounded.
        path[at + steps - 1] = to;

        return at + steps;
    }

    private double snap(double price) {
        return Math.round(price / tick) * tick;
    }
}
