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
 * <h2>Measured, not assumed</h2>
 *
 * <p>Every number below was read off the <b>nine Profit sessions of 25/08 to
 * 04/09/2026</b> — 5.074 minutes, 48 million prints, 8,8 million price changes.
 * Before them this class was four plausible rules, and the ticks arrived and
 * said what the rules were worth:</p>
 *
 * <table>
 *   <caption>The same measurements over the same 5.065 minutes</caption>
 *   <tr><th></th><th>real</th><th>the rules this replaced</th><th>here</th></tr>
 *   <tr><td>a step of exactly one tick</td><td>97,2%</td><td>15,4%</td><td>100%</td></tr>
 *   <tr><td>a step keeping the last direction</td>
 *       <td>17,6%</td><td>28,5%</td><td>14,8%</td></tr>
 *   <tr><td>ground covered, in bar ranges</td><td>62,4</td><td>10,0</td><td>60,6</td></tr>
 *   <tr><td>price changes in the minute</td><td>1.118</td><td>35</td><td>1.090</td></tr>
 *   <tr><td>where the high falls in the path</td><td>0,42</td><td>0,30</td><td>0,41</td></tr>
 *   <tr><td>where the low falls</td><td>0,46</td><td>0,29</td><td>0,42</td></tr>
 * </table>
 *
 * <p>The old rules were not slightly off. A minute moved <b>35 times where the
 * market moved 1.118</b>, and covered a tenth of the ground.</p>
 *
 * <h2>The four rules, as the ticks state them</h2>
 *
 * <ol>
 *   <li><b>The order follows the direction — usually.</b> A rising minute makes
 *       its low before its high 83,2% of the time, and a falling one its high
 *       before its low 88,7%. The rule used to be certain and the market is not:
 *       one minute in six goes the other way, so the coin is thrown.</li>
 *   <li><b>The price moves one tick at a time, and mostly back.</b> 97,1% of
 *       changes are a single tick, and only 17,6% keep the direction of the one
 *       before — the bid-ask bounce, which is what a minute of WIN mostly is. A
 *       Brownian bridge with Gaussian noise produces neither: its steps are any
 *       size and its direction is a fair coin.</li>
 *   <li><b>How many: {@code 30,1 × range^1,241}</b>, the range in ticks, fitted
 *       over every range with at least 25 minutes behind it. A 10-tick minute
 *       changes price 534 times and a 20-tick one 1.196; the old rule said 24
 *       and 44.</li>
 *   <li><b>Every price is on the tick grid</b>, which now costs nothing: the
 *       walk is in whole ticks by construction. A minute visits every level
 *       between its low and its high — measured, and a consequence of moving one
 *       tick at a time.</li>
 * </ol>
 *
 * <h2>Why a leg may not reach its target early</h2>
 *
 * <p>A walk merely bounded by the bar's high and low reaches them at once and
 * then rattles along them: the first touch landed at 0,33 of the path against a
 * measured 0,42. So a leg wanders up to one tick short of its target and steps
 * onto it only at the end. That alone moved the extremes to 0,41 and 0,42.</p>
 *
 * <h2>What this is still not</h2>
 *
 * <p><b>The path is invention.</b> It reproduces four true prices and makes up
 * the rest, now with the right statistics rather than merely plausible ones. It
 * is for watching. It is <b>not</b> for measuring: a backtest run over these
 * ticks measures this walk, not the market.</p>
 *
 * <p>And the numbers come from nine sessions of one instrument in one month. The
 * microstructure ones — one tick, and the bounce — are properties of how WIN
 * trades and carry. <b>The count does not</b>: it is liquidity, and 2021 was not
 * 2026. A minute of a thin session will be drawn busier than it was.</p>
 *
 * <p>The route for a given bar is the same every time it is asked for — the seed
 * mixes in the bar's index. Scrubbing backwards and forwards over the same
 * minute has to redraw the same minute.</p>
 */
public final class SyntheticTicks implements TickPath {

    /**
     * The fit: {@code changes = 30,1 × ticks^1,241}.
     *
     * <p>It reads <b>168</b> changes for a four-tick minute against a measured
     * 163, <b>524</b> for ten against 534, and <b>2.929</b> for forty against
     * 2.745 — so it runs +3,1%, −1,9% and +6,7% against the tape.</p>
     *
     * <p><b>Those three numbers used to be 163, 498 and 2.716,</b> which is what
     * an exponent of 1,2187 produces, not 1,241. One of the two was measured and
     * the other was left behind by an edit, and there was no way to tell which
     * from here — in a paragraph that is the only account of where the parameter
     * comes from, for a number that decides how many prices every minute of the
     * replay receives.</p>
     *
     * <p>The exponent in the code is the one kept, because it is the fit the
     * tape study recorded. The examples are recomputed from it, with the
     * residuals beside them, so the next reader can check the arithmetic instead
     * of trusting the sentence.</p>
     */
    private static final double BUSY = 30.1;

    private static final double GROWTH = 1.241;

    /** A minute that barely moved still traded: the smallest path worth walking. */
    private static final int FLOOR = 8;

    /**
     * A guard, not a measurement.
     *
     * <p>The busiest minute of the nine sessions changed price 24.045 times, so
     * nothing real is cut. What this stops is the law being asked about a bar it
     * was not fitted on: a daily bar with a 400-tick range would ask for 56.500
     * prices, and a day does not have one path anyway.</p>
     */
    private static final int CEILING = 25_000;

    /** How often a change keeps the direction of the one before it. */
    private static final double CONTINUES = 0.176;

    /** How often a rising minute makes its low before its high. */
    private static final double LOW_FIRST_RISING = 0.832;

    /** How often a falling minute makes its high before its low. */
    private static final double HIGH_FIRST_FALLING = 0.887;

    /**
     * How the minute is spent between the three legs.
     *
     * <p>Measured as where the extremes fall: on a rising minute the low lands
     * at 0,230 of the path and the high at 0,645. Not the quarter, half, quarter
     * that sharing the steps by distance produces — the market spends longer
     * after the second extreme than before the first.</p>
     */
    private static final double[] SHARE = {0.230, 0.415, 0.355};

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
        int ticks = (int) Math.max(1,
                Math.round((series.highAt(index) - series.lowAt(index)) / tick));

        return (int) Math.max(FLOOR,
                Math.min(Math.round(BUSY * Math.pow(ticks, GROWTH)), CEILING));
    }

    /**
     * @return the prices inside that bar, the first being its open and the last
     *         its close
     */
    @Override
    public double[] pathFor(PriceSeries series, int index) {
        double open = series.openAt(index);
        double high = series.highAt(index);
        double low = series.lowAt(index);
        double close = series.closeAt(index);

        // Mixed with the index so each bar has its own route, and the same route
        // every time: scrubbing back over a minute must redraw that minute.
        Random random = new Random(stir(seed * 1_000_003L + index));

        boolean rising = close >= open;

        // Thrown, not assumed. One rising minute in six makes its high first.
        boolean usual = random.nextDouble()
                < (rising ? LOW_FIRST_RISING : HIGH_FIRST_FALLING);
        boolean lowFirst = rising == usual;
        double[] targets = {
            lowFirst ? low : high,
            lowFirst ? high : low,
            close,
        };

        int total = countFor(series, index);
        double[] path = new double[1 + lengthOf(open, targets, total)];

        path[0] = open;

        int at = 1;
        double from = open;

        for (int leg = 0; leg < targets.length; leg++) {
            at = walk(path, at, from, targets[leg], share(total, leg), low, high, random);
            from = targets[leg];
        }

        return path;
    }

    private static int share(int total, int leg) {
        return Math.max(1, (int) Math.round(total * SHARE[leg]));
    }

    /** @return how long the three legs come out, so the array is sized once */
    private int lengthOf(double open, double[] targets, int total) {
        int length = 0;
        double from = open;

        for (int leg = 0; leg < targets.length; leg++) {
            length += steps(from, targets[leg], share(total, leg))
                    + landing(from, targets[leg]);
            from = targets[leg];
        }

        return length;
    }

    /**
     * @return whether the leg ends with a step of its own onto the target
     *
     * <p>It does not when the target is a tick away or less: there is no room to
     * wander short of it.</p>
     */
    private int landing(double from, double to) {
        return Math.abs(gaps(to - from)) <= 1 ? 0 : 1;
    }

    /** @return the price the leg wanders below, or above, until its last step */
    private double aim(double from, double to) {
        return landing(from, to) == 0 ? to : to - Math.signum(to - from) * tick;
    }

    /**
     * @return how many wandering steps the leg takes
     *
     * <p>At least as many as the ground it has to cover, and of the same parity:
     * a walk of whole ticks cannot end an odd number of ticks away in an even
     * number of steps.</p>
     */
    private int steps(double from, double to, int wanted) {
        int gap = Math.abs(gaps(aim(from, to) - from));
        int steps = Math.max(wanted - landing(from, to), gap);

        return (steps - gap) % 2 == 0 ? steps : steps + 1;
    }

    /**
     * Walks one leg, a tick at a time.
     *
     * <p>The leg has its own box: from where it starts to <b>one tick short of
     * where it is going</b>. A walk allowed to reach its extreme early reaches
     * it at once and then rattles along it, which put the first touch at 0,33 of
     * the path against a measured 0,42.</p>
     *
     * <p>What may be spent is checked before the direction is drawn rather than
     * after. Correcting a drawn step twice — once for the budget, once for the
     * box — let the two corrections undo each other, and the walk stepped up
     * with no up-steps left: it climbed straight out of the bar, and the tests
     * for the four numbers, for staying inside, for the order of the extremes
     * and for where they fall all caught it at once.</p>
     *
     * @param lowest the bar's low, and {@code highest} its high — the outer
     *               bound for a leg that ends where it began
     * @return where the next leg should start writing
     */
    private int walk(double[] path, int at, double from, double to, int wanted,
                     double lowest, double highest, Random random) {
        double aim = aim(from, to);
        int steps = steps(from, to, wanted);
        int up = (steps + gaps(aim - from)) / 2;
        int down = steps - up;
        double floor = Math.min(from, aim);
        double ceiling = Math.max(from, aim);

        if (floor == ceiling) {
            // A leg that ends where it began still has its share of the minute
            // to spend, and a box of no width leaves it nowhere to spend it.
            // One tick of room, never outside the bar.
            floor = Math.max(lowest, floor - tick);
            ceiling = Math.min(highest, ceiling + tick);
        }

        double price = from;
        int last = 0;

        for (int i = 0; i < steps; i++) {
            boolean rise = up > 0 && price + tick <= ceiling + tick / 2;
            boolean fall = down > 0 && price - tick >= floor - tick / 2;
            int step;

            if (rise && fall) {
                step = draw(up, down, last, random);
            } else if (rise) {
                step = 1;
            } else if (fall) {
                step = -1;
            } else {
                // A bar of one single price: there is nowhere to walk, and the
                // rest of the leg stands still rather than inventing a level.
                for (int rest = i; rest < steps; rest++) {
                    path[at + rest] = price;
                }

                break;
            }

            price += step * tick;

            if (step > 0) {
                up--;
            } else {
                down--;
            }

            last = step;
            path[at + i] = price;
        }

        if (steps > 0) {
            // The end of a leg is one of the bar's own numbers, unrounded: the
            // walk lands on the grid, and the bar's high need not be on it.
            path[at + steps - 1] = aim;
        }

        if (landing(from, to) == 0) {
            return at + steps;
        }

        path[at + steps] = to;

        return at + steps + 1;
    }

    /**
     * @return +1 or −1, leaning against the last step and towards what is owed
     *
     * <p>Keeping the last direction is weighted {@link #CONTINUES} and turning
     * back {@code 1 − CONTINUES}, each times how many steps of that kind the leg
     * still owes. A leg that has spent its up-steps therefore turns down on its
     * own, and the excess is spread over the whole leg rather than marched off
     * at the end.</p>
     */
    private static int draw(int up, int down, int last, Random random) {
        if (up == 0) {
            return -1;
        }

        if (down == 0 || last == 0) {
            return random.nextDouble() < up / (double) (up + down) ? 1 : -1;
        }

        double same = CONTINUES * (last > 0 ? up : down);
        double turn = (1 - CONTINUES) * (last > 0 ? down : up);

        return random.nextDouble() * (same + turn) < same ? last : -last;
    }

    /** @return that distance in whole ticks */
    private int gaps(double distance) {
        return (int) Math.round(distance / tick);
    }

    /**
     * @return the bar's seed, stirred
     *
     * <p>{@link Random} scrambles its seed with a single multiply, so seeds one
     * apart — which is what a bar index produces — give strongly correlated
     * FIRST draws. That draw is the one that decides which extreme comes first,
     * and it came out 31% low-first across a day where the measurement says
     * 83,2%. The bias was invisible while the first draw only fed noise; the
     * moment it decided something, the test for the order caught it.</p>
     *
     * <p>SplitMix64's finalizer, which is what {@code SplittableRandom} uses for
     * exactly this.</p>
     */
    private static long stir(long seed) {
        long mixed = seed + 0x9E3779B97F4A7C15L;

        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;

        return mixed ^ (mixed >>> 31);
    }
}
