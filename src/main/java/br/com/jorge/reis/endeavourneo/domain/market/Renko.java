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
 * <p><b>One tail, against the brick.</b> The first brick of a batch shows how
 * far price went the OTHER way before it broke -- the move was fought. There is
 * no tail past the close: a brick ends at its own extreme, as it does in the
 * Profit, and the points that went through without making another brick are
 * simply not drawn. The first version drew them, and a brick that ended a little
 * beyond its close read as wrong to a reader who knew the Profit's.</p>
 *
 * <p><b>A brick laid over a gap is marked, not hidden.</b> When the market
 * reopens 1.400 points above where it closed, the ruler has to lay fourteen
 * bricks at 100 points and thirteen of them cover prices nobody traded. They
 * are drawn in a flat grey, as the reference product draws them, so the first
 * brick that was really traded can be seen — see {@link Untraded}.</p>
 *
 * <p><b>Volume is accumulated between bricks and given whole to the FIRST of a
 * batch</b>, the rest of that batch getting none. It is the seventh of the rules
 * measured against the reference product, and it is a convention rather than a
 * measurement either way: a minute does not say which part of its volume
 * belonged to which brick, so any answer is invented. What can be said is where
 * the volume arrived, and it arrived by the time the first brick completed.</p>
 *
 * <p>This paragraph used to claim the volume was split equally, and so did the
 * comparison with ta4j below — which listed the split as a difference between
 * the two. There is no such difference: the code has always handed the whole
 * accumulator to the first brick. Two paragraphs describing a behaviour the
 * class does not have are worse than none, because a reader trusts them.</p>
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
 *       the rest — and so, it turns out, do we. This entry used to say the
 *       opposite, that ours spread it. Two things had to be true for that to go
 *       unnoticed: nothing reads brick volume yet, and nothing tested it.</li>
 *   <li><b>It advances the timestamps</b> so bricks from one bar differ. That is
 *       required by its own series, which will not take two bars at the same
 *       instant. Our chart places bricks by index, so we can keep the true time
 *       instead of inventing gaps inside a minute.</li>
 * </ul>
 */
public final class Renko implements Aggregation {

    /**
     * The most bricks one move may lay before the move is refused.
     *
     * <p>Generous on purpose, because it is a guard and not a rule: the whole
     * recorded range of WIN — sixty thousand points to a hundred and ninety —
     * is about 43.000 bricks of the smallest size the interface offers. A
     * single bar that lays more than this did not move; a price or a brick is
     * wrong.</p>
     */
    private static final int MOST_BRICKS = 100_000;

    private final double brick;

    private final int reversal;

    private final boolean wicks;

    private final boolean forming;

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
        this(brick, reversal, wicks, false);
    }

    /**
     * @param forming whether to add the brick still being built at the end
     *
     * <p><b>Off unless asked</b>, and the chart asks. A partial brick is the
     * only thing on a renko chart that moves: between one completed brick and
     * the next, nothing changes at all, and a replay looks frozen. Measured over
     * four minutes of market: a one-minute candle chart changed on 1,7% of
     * frames — once per price that arrived — and renko on 0,5%, only when a
     * brick closed.</p>
     *
     * <p>It stays off by default because a partial brick is <b>not a brick</b>.
     * It grows, shrinks and can vanish, and anything measuring bricks — a
     * backtest above all — must not count it as one.</p>
     */
    public Renko(double brick, int reversal, boolean wicks, boolean forming) {
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
        this.forming = forming;
    }

    public boolean hasForming() {
        return forming;
    }

    /** @return the same bricks, with the one under construction shown or not */
    public Renko withForming(boolean show) {
        return show == forming ? this : new Renko(brick, reversal, wicks, show);
    }

    public boolean hasWicks() {
        return wicks;
    }

    /** @return the same bricks, with the tails on or off */
    public Renko withWicks(boolean showWicks) {
        return showWicks == wicks ? this : new Renko(brick, reversal, showWicks, forming);
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

    /**
     * Where a renko stood when its source ran out.
     *
     * <p>Handed to the next {@link #applyFrom} so a renko can be built one
     * session at a time and still be one renko. Without it, each day would
     * start its ruler at its own opening price and the bricks would not line up
     * across the night -- which is not what the chart shows, and not what the
     * reference product shows either.</p>
     *
     * @param anchor the level the last brick closed at
     * @param direction which way the last brick went; zero before the first
     * @param sinceLow how far price ran down since that brick
     * @param sinceHigh how far price ran up since that brick
     * @param pending volume accumulated and not yet given to a brick
     * @param tally what has traded since the last brick closed
     */
    public record Carry(double anchor, int direction,
                        double sinceLow, double sinceHigh, double pending,
                        TradeTally tally) {

        /**
         * Refuses a carry that cannot have come from a renko, and takes its own
         * copy of the tally.
         *
         * <p>There was nothing here, and the proof that invalid states were
         * reachable sat downstream: {@link #applyFrom} had to defend itself
         * against {@code from.tally() == null}. A guard against a state the type
         * allows is the type admitting it should not.</p>
         *
         * <p>The tally is <b>mutable</b>, so a record that merely kept the
         * reference let two carries share one accumulator. {@code applyFrom}
         * copied on the way in and the guarantee lived in the caller; here it
         * lives in the type, where a caller cannot forget it.</p>
         */
        public Carry {
            if (direction < -1 || direction > 1) {
                throw new IllegalArgumentException(
                        "a brick goes up, down or nowhere, not " + direction);
            }

            if (sinceLow > sinceHigh) {
                throw new IllegalArgumentException("the run since the last brick reaches down to "
                        + sinceLow + " and up to " + sinceHigh + ", which is backwards");
            }

            tally = tally == null ? new TradeTally() : tally.copy();
        }
    }

    /**
     * @param bricks what was laid
     * @param carry where the renko stands now
     */
    public record Continued(PriceSeries bricks, Carry carry) { }

    /**
     * @param carry where the renko stands
     * @param now the price right now
     * @return the brick still being built, as {open, high, low, close, volume}
     *
     * <p>Here so there is ONE of it. The renko draws this brick at the end of a
     * pass; a renko being fed a replay has to draw it every frame, from the
     * carry it is holding rather than from a pass it is not making. Two
     * implementations of the same brick is two chances for the live edge to
     * disagree with the finished chart.</p>
     *
     * <p><b>Always returned, even at no height.</b> The first version only drew
     * it once it had grown past a twentieth of a brick, and that was a defect
     * reported from the screen: price wanders across the level, the partial
     * brick appears and vanishes, the bar count changes and the whole chart
     * shifts sideways. The chart trembled. A brick of no height is a flat mark
     * at the level, which is what "price is exactly on the level" looks
     * like.</p>
     */
    public double[] formingAt(Carry carry, double now) {
        double anchor = carry.anchor();
        double top = wicks ? Math.max(carry.sinceHigh(), Math.max(anchor, now))
                : Math.max(anchor, now);
        double bottom = wicks ? Math.min(carry.sinceLow(), Math.min(anchor, now))
                : Math.min(anchor, now);

        return new double[]{anchor, top, bottom, now, carry.pending()};
    }

    @Override
    public PriceSeries apply(PriceSeries source) {
        return applyFrom(source, null).bricks();
    }

    /**
     * @param from where a previous stretch left the renko, or null to begin
     * @return the bricks this source laid, and where the renko now stands
     *
     * <p>Splitting a source in two and running this over each half gives the
     * same bricks as running it over the whole. That is the property the
     * session-by-session build rests on, and it is a test.</p>
     */
    /**
     * @return the grid level at or below that price
     *
     * <p><b>Brick boundaries sit on an absolute price grid</b>, at multiples of
     * the brick size, and not wherever the data happens to begin. Measured
     * against the reference product on 04/09/2026, three sizes and one
     * instrument:</p>
     *
     * <table>
     *   <caption>Bricks read off a Profit chart</caption>
     *   <tr><th></th><th>brick</th><th>open</th><th>open / brick</th></tr>
     *   <tr><td>11R</td><td>50</td><td>187.950</td><td>3.759</td></tr>
     *   <tr><td>21R</td><td>100</td><td>188.000</td><td>1.880</td></tr>
     *   <tr><td>6R</td><td>25</td><td>187.875</td><td>7.515</td></tr>
     * </table>
     *
     * <p>Every one a whole multiple. This program anchored on the first bar's
     * open instead, so its grid was offset by whatever that price happened to
     * be -- and two charts of the same instrument and the same brick, started
     * on different days, drew different bricks. Measured on the tape of
     * 03/09/2026 at 50 points: NONE of 1.651 openings landed on the grid.</p>
     *
     * <p>Only the first anchor needs this. Every brick after it moves exactly
     * one brick size, so a ruler that starts on the grid stays on it.</p>
     */
    private double gridUnder(double price) {
        return Math.floor(price / brick) * brick;
    }

    /**
     * @param moved how far price went from the anchor, in that direction
     * @return how many bricks that move COMPLETES
     *
     * <p><b>A brick closes when price goes PAST its level, not when it reaches
     * it.</b> The difference is one trade wide and it is not academic: this
     * instrument moves in fives and its bricks in twenty-fives, so price lands
     * exactly on a level constantly.</p>
     *
     * <p>Read off the reference product, which reports each brick's own trade
     * count. Its brick 189.400 -&gt; 189.500 on 03/09/2026 holds <b>2.514
     * trades and 72.589 contracts</b> — exactly the first 2.514 trades of that
     * session. The level 189.500 was printed fifty-one times in a row, from
     * trade 2.464 to trade 2.514, and every one of them is inside that brick;
     * trade 2.515, the first at 189.505, is the one that closed it and it
     * belongs to the brick above. The first version here closed on trade 2.464
     * and handed the other fifty upstairs.</p>
     *
     * <p>It changes the drawing and not only the bookkeeping: price that
     * touches a level and turns back without passing it lays a brick under the
     * old rule and none under this one. Price oscillating between two exact
     * levels for ever draws nothing at all, which is right — nothing has
     * happened that the ruler is meant to record.</p>
     */
    private int steps(double moved) {
        if (!(moved > 0.0)) {
            return 0;
        }

        // The epsilon is what makes "exactly on the level" round the right way:
        // a move of precisely one brick has to come out as zero completed.
        double count = Math.ceil(moved / brick - 1e-9) - 1;

        if (count > MOST_BRICKS) {
            // REFUSED, and refused HERE, where the two numbers that produced it
            // are still in hand. There was no ceiling at all: a cast of a double
            // past Integer.MAX_VALUE saturates in silence, and laydown then
            // walked that many times allocating a double[5] each pass -- on the
            // interface thread, since the candle renko is folded synchronously
            // from ChartCanvas.refold. A hang, or an OutOfMemoryError, in place
            // of a sentence.
            //
            // It is not a hypothetical: TickBars records a renko climbing from
            // zero to 120.000 and laying two thousand bricks no trade made,
            // because rows stating zero for everything were read as prices.
            // That source is filtered now; this one is public, takes any brick
            // above zero, and reads candles, ticks and tape alike.
            throw new IllegalArgumentException("a move of " + moved + " over a brick of "
                    + brick + " would lay " + (long) count
                    + " bricks, and no market moves that far: the price or the brick is wrong");
        }

        return (int) count;
    }

    public Continued applyFrom(PriceSeries source, Carry from) {
        if (source == null || source.size() == 0) {
            return new Continued(PriceSeries.empty(),
                    from == null
                            ? new Carry(0, 0, 0, 0, 0, new TradeTally())
                            : from);
        }

        List<double[]> bricks = new ArrayList<>();
        List<Long> stamps = new ArrayList<>();
        List<Boolean> untraded = new ArrayList<>();
        List<Long> counts = new ArrayList<>();

        // The level the last brick closed at. It starts at the first bar's OPEN,
        // which is the one price in a bar that never moves.
        //
        // It used to start at the first CLOSE, and that was a defect reported
        // from the screen as the chart trembling. While the first bar is still
        // forming its close wanders, so the whole ruler moved with the price and
        // every brick was measured from a shifting origin. Instrumented: the
        // same bar, the same high and the same low, and the completed bricks
        // going from four to two because the close had moved eighteen points.
        double anchor = from == null ? gridUnder(source.openAt(0)) : from.anchor();
        int direction = from == null ? 0 : from.direction();
        double pending = from == null ? 0.0 : from.pending();
        boolean anyVolume = false;

        // How far price ran the other way since the last brick. It becomes the
        // tail of whichever brick finally goes through.
        double sinceLow = from == null ? anchor : from.sinceLow();
        double sinceHigh = from == null ? anchor : from.sinceHigh();

        // Copied, never written through: a replay folds the same carry again
        // whenever a frame turns out to have laid nothing, and a tally emptied
        // by that attempt would take the trades with it.
        TradeTally tally = from == null || from.tally() == null
                ? new TradeTally() : from.tally().copy();

        for (int i = 0; i < source.size(); i++) {
            double volume = source.volumeAt(i);

            if (Double.isFinite(volume)) {
                pending += volume;
                anyVolume = true;
            }

            // Held back until AFTER the bricks are laid: the print that
            // closes a brick belongs to the one that starts, not to the one it
            // finished. Measured on 03/09/2026, where the first print of the
            // day closed a box carrying 146.009 trades and then opened the box
            // that went on to hold 2.514 -- itself among them.
            // What kind of bar this is has to be known before it lays
            // anything -- see TradeTally.seeing.
            tally.seeing(source, i);

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
            // Each extreme is folded into the running tail ONLY when its turn
            // comes in the assumed order -- not both at the top of the bar.
            //
            // Folding both first was the defect seen on screen: a brick of 55
            // wearing a tail of 117 against it, where a reversal costs 110. The
            // trend was down, so the high went first and turned it upwards; the
            // up bricks then took their tail from a running low that already
            // held THIS bar's low -- a price that, in the assumed path, had not
            // happened yet. Instrumented: anchor 136.990, bar low 136.835, and
            // the up brick born with a tail 155 below its own open.
            boolean lowFirst = direction >= 0;

            for (int step = 0; step < 2; step++) {
                // INSIDE the loop, per extreme. It used to be declared outside
                // and carried its count across both steps, so once the first
                // extreme laid a brick the restart at the bottom fired again at
                // the end of the second -- throwing away the extreme the second
                // step had just recorded. The next brick then took its tail from
                // the anchor instead of from where price had actually been, and
                // came out SHORT. Every assertion guarding this file is a
                // ceiling, and a tail that is too short passes all of them.
                int made = 0;
                boolean thisIsTheLow = (step == 0) == lowFirst;
                double price;

                if (thisIsTheLow) {
                    sinceLow = Math.min(sinceLow, source.lowAt(i));
                    price = source.lowAt(i);
                } else {
                    sinceHigh = Math.max(sinceHigh, source.highAt(i));
                    price = source.highAt(i);
                }

                int upSteps = steps(price - anchor);
                int downSteps = steps(anchor - price);
                int upNeeded = direction >= 0 ? 1 : reversal;
                int downNeeded = direction <= 0 ? 1 : reversal;

                if (upSteps >= upNeeded) {
                    // A TURN STARTS ONE BRICK AWAY. Classic renko, and what the
                    // reference product draws: after a box closing at 188.100
                    // it took a fall past 187.900 to turn, and it drew ONE box,
                    // from 188.000 to 187.900 -- not two from 188.100 down.
                    // Both of its last two boxes on 02/09/2026 open at 188.000,
                    // which nothing else can produce, and both report a
                    // variation of 200 points on a body of 100.
                    int skip = upNeeded - 1;
                    int count = upSteps - skip;
                    int at = bricks.size();

                    anchor = laydown(bricks, stamps, untraded, counts,
                            anchor + skip * brick, count, +1, source.timeAt(i));
                    direction = +1;
                    made += count;

                    settle(bricks, stamps, untraded, counts, at, tally);

                    if (wicks) {
                        // Only the FIRST of a batch wears a tail: how far price
                        // went the other way before breaking. The overshoot past
                        // the last brick is not drawn -- the brick ends at its
                        // own extreme.
                        bricks.get(at)[2] = Math.min(bricks.get(at)[2], sinceLow);
                    }
                } else if (downSteps >= downNeeded) {
                    int skip = downNeeded - 1;
                    int count = downSteps - skip;
                    int at = bricks.size();

                    anchor = laydown(bricks, stamps, untraded, counts,
                            anchor - skip * brick, count, -1, source.timeAt(i));
                    direction = -1;
                    made += count;

                    settle(bricks, stamps, untraded, counts, at, tally);

                    if (wicks) {
                        bricks.get(at)[1] = Math.max(bricks.get(at)[1], sinceHigh);
                    }
                }

                if (made > 0) {
                    sinceLow = anchor;
                    sinceHigh = anchor;
                }
            }

            // This bar goes into the tally AFTER any bricks it laid, so it
            // belongs to the one now forming rather than to the one it closed.
            tally.add(source, i);
            pending = tally.volume();
        }

        if (forming) {
            double now = source.closeAt(source.size() - 1);
            double[] shape = formingAt(
                    new Carry(anchor, direction, sinceLow, sinceHigh, pending,
                            tally), now);
            double top = shape[1];
            double bottom = shape[2];

            // ALWAYS, even at no height at all. The first version only drew it
            // when it had grown past a twentieth of a brick, and that was a
            // defect reported from the screen: price wanders across the level,
            // the partial brick appears and vanishes, the BAR COUNT CHANGES and
            // the whole chart shifts sideways by one bar. The chart trembled.
            //
            // A brick of no height is a flat mark at the level, which is what
            // "price is exactly on the level" looks like. Nothing is lost by
            // drawing it, and a bar that never comes and goes is worth more than
            // one that is always meaningful.
            bricks.add(new double[]{anchor, top, bottom, now, pending});
            stamps.add(source.timeAt(source.size() - 1));

            // Never a gap: the forming brick is where the price IS.
            untraded.add(Boolean.FALSE);

            // And never a count: it is not one band yet -- it runs from the
            // anchor to wherever price has got to, which can be most of a
            // brick's worth of levels.
            counts.add(Counted.UNKNOWN);
        }

        // The carry is taken from the state, not from the bricks: the forming
        // brick appended just above is provisional and must not become the
        // starting point of the next stretch.
        return new Continued(assemble(bricks, stamps, untraded, counts, anyVolume),
                new Carry(anchor, direction, sinceLow, sinceHigh, pending, tally));
    }

    /**
     * Lays {@code count} bricks in {@code step} direction and returns the new anchor.
     *
     * <p>One at a time and not one tall brick: a move of three brick heights is
     * three bricks, which is exactly what makes the chart show the size of a
     * move as a length rather than as a number to read off an axis.</p>
     */
    private double laydown(List<double[]> bricks, List<Long> stamps,
                           List<Boolean> untraded, List<Long> counts,
                           double anchor, int count, int step, long time) {
        double level = anchor;

        for (int b = 0; b < count; b++) {
            double open = level;
            double close = level + step * brick;

            bricks.add(new double[]{open, Math.max(open, close), Math.min(open, close), close, 0.0});
            stamps.add(time);
            untraded.add(Boolean.FALSE);
            counts.add(Counted.UNKNOWN);

            level = close;
        }

        return level;
    }

    /**
     * Marks the bricks of one batch at whose prices nothing was traded.
     *
     * @param at where this batch starts in {@code bricks}
     * @param barLow the low of the bar that laid it
     * @param barHigh its high
     * @param coverLow the lowest price traded since the previous brick
     * @param coverHigh the highest
     *
     * <p><b>A brick is a band of price, and the question is whether anybody
     * traded inside that band.</b> Nothing else: not how far the bar moved,
     * not which extreme laid the brick, not how many bricks came at once. A
     * night that reopens 1.300 points higher lays thirteen bricks and there
     * were trades at the levels of one of them; a minute that ran 1.300 points
     * lays the same thirteen and there were trades at every level on the way.
     * Only the prices tell them apart.</p>
     *
     * <p>Two sets of prices can put a trade inside a band: what was traded
     * since the last brick was laid, and the range of the bar laying this one.
     * A band no trade fell into is the shape of a gap.</p>
     *
     * <p>The reference product answers the same question and shows it as a
     * count: the brick it draws grey reads <i>Contratos Neg: 0,00</i>.</p>
     */
    private void settle(List<double[]> bricks, List<Long> stamps,
                        List<Boolean> untraded, List<Long> counts, int at,
                        TradeTally tally) {
        // THE FIRST BRICK OF A BATCH TAKES EVERYTHING; the rest take nothing.
        // A batch is one price move: the first brick is the one that was being
        // built, and the others were passed through in the same instant and
        // were never anybody's chart. See TradeTally.
        long trades = tally.trades();

        if (!tally.summarised() && trades > 0) {
            counts.set(at, trades);
            stamps.set(at, tally.first());
        }

        bricks.get(at)[4] = tally.volume();

        for (int b = at; b < bricks.size(); b++) {
            if (tally.summarised()) {
                // Candles cannot be counted -- a minute is a summary of trades
                // at times it does not report -- so nothing here is claimed
                // either way. See Counted.
                continue;
            }

            long mine = b == at ? trades : 0L;

            counts.set(b, mine);
            untraded.set(b, mine == 0);
        }

        tally.clear();
    }

    private static PriceSeries assemble(List<double[]> bricks, List<Long> stamps,
                                        List<Boolean> untraded, List<Long> counts,
                                        boolean anyVolume) {
        int size = bricks.size();

        long[] times = new long[size];
        double[] opens = new double[size];
        double[] highs = new double[size];
        double[] lows = new double[size];
        double[] closes = new double[size];
        double[] volumes = new double[size];
        boolean[] gaps = new boolean[size];
        long[] trades = new long[size];

        for (int i = 0; i < size; i++) {
            double[] one = bricks.get(i);

            times[i] = stamps.get(i);
            gaps[i] = untraded.get(i);
            trades[i] = counts.get(i);
            opens[i] = one[0];
            highs[i] = one[1];
            lows[i] = one[2];
            closes[i] = one[3];
            volumes[i] = anyVolume ? one[4] : Double.NaN;
        }

        return new ArraySeries(times, opens, highs, lows, closes, volumes, gaps, trades);
    }
}
