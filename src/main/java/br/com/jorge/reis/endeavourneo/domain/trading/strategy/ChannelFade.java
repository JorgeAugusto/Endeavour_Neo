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
package br.com.jorge.reis.endeavourneo.domain.trading.strategy;

import br.com.jorge.reis.endeavourneo.domain.indicator.Pivots;
import br.com.jorge.reis.endeavourneo.domain.indicator.Regression;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Market;
import br.com.jorge.reis.endeavourneo.domain.trading.Plotted;
import br.com.jorge.reis.endeavourneo.domain.trading.Sourced;
import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Desk;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fading a stretch back into two regression channels.
 *
 * <p>The thesis: price that has run to the edge of a channel which is itself
 * sloping the other way is stretched twice over, and it goes back. Nothing here
 * is a breakout — every order is a limit, resting where price would have to come
 * to it.
 *
 * <h2>The rule, for the sell; the buy is its mirror</h2>
 *
 * <ol>
 *   <li>The stochastic on one minute goes to eighty — the {@link StochasticLatch}
 *       arms a credit of entries, and throws it away again if the reading comes
 *       back to the middle before they are spent.</li>
 *   <li><b>Both</b> channels are sloping DOWN. Both, and not either: a stretch
 *       against one clock and with the other is not the trade.</li>
 *   <li>Price returns to one of the rungs — two deviations or two and a half,
 *       on the short channel or the long one.</li>
 *   <li>The target is the rung's own, on the rung's own channel, and it MOVES:
 *       the channel is refitted every bar and the order is re-sent at the level
 *       it has now. "Minus two of the channel" means the channel today.</li>
 * </ol>
 *
 * <h2>One order at a time, and why</h2>
 *
 * <p>Four rungs could all rest at once, and then a fill would have to be matched
 * to a rung by its PRICE — which is the bug this project already paid for on the
 * Range 90: a limit fills at the better of its level and the open, so on a gap
 * the fill price is not the level, and it can land exactly on another rung's.
 * A trade would be booked against the wrong target and the position would carry
 * a phantom lot.
 *
 * <p>So only the NEAREST unfilled rung rests, and a fill is unambiguous by
 * construction. It costs something real: a bar that crosses two rungs fills one
 * of them and the other waits for the next bar. It is the conservative side of
 * the error — the alternative reports trades the market did not give.
 *
 * <h2>There is no stop</h2>
 *
 * <p>Deliberately, and it is the first thing to look at in any result: a lot
 * leaves at its target or at the end of the session, and in between the loss is
 * bounded only by the clock. What a strategy without a stop measures is how far
 * the market can go against a fade before the day ends.
 */
public final class ChannelFade implements Strategy, Plotted, Sourced {

    /**
     * One rung of the ladder: where it enters, and where that lot leaves.
     *
     * <p>Both factors are MAGNITUDES and the side signs them. A sell enters at
     * {@code line + entry × sigma} and leaves at {@code line − target × sigma};
     * a buy is the mirror. Written signed instead, every rung would need two
     * spellings and the mirror would be a second rule to keep right.
     *
     * @param period which channel — the number of bars it is fitted over
     * @param entry  deviations from the line where the lot goes on
     * @param target deviations the other side where it comes off
     */
    public record Rung(int period, double entry, double target) {

        public Rung {
            if (period < 2) {
                throw new IllegalArgumentException("a channel of " + period + " bars is not one");
            }

            if (!(entry > 0) || !(target > 0)) {
                throw new IllegalArgumentException(
                        "a rung is measured in deviations from the line, and " + entry
                                + "/" + target + " is not a distance");
            }
        }
    }

    /**
     * The four rungs he specified.
     *
     * <p>Note the long channel's asymmetry — two deviations in, one and
     * three-quarters out — and that the short channel's two rungs share a
     * target. Both were confirmed rather than tidied: a ladder whose numbers
     * were rounded into a pattern would be measuring a strategy nobody asked
     * for.
     */
    public static final List<Rung> LADDER = List.of(
            new Rung(Regression.SHORT, 2.0, 2.0),
            new Rung(Regression.SHORT, 2.5, 2.0),
            new Rung(Regression.LONG, 2.0, 1.75),
            new Rung(Regression.LONG, 2.5, 2.0));

    /**
     * One tick of the mini index. The stop sits one BEYOND the pivot.
     *
     * <p>Beyond and not on it: a stop exactly on the low comes out on a touch
     * that did not break the structure, which is the same argument the pattern
     * breakout makes about its trigger.</p>
     */
    public static final double TICK = 5;

    /**
     * The stop that appears when both channels turn against an open lot.
     *
     * <p>The entry needed both channels agreeing; both agreeing the OTHER way is
     * the thesis being withdrawn. The position does not leave on that alone — it
     * waits for the market to draw a level worth leaving beyond: the first pivot
     * of the zigzag AFTER the turn. A bottom under a long, a top over a short,
     * and the stop one tick past it.
     *
     * <h2>The six bars nobody is watching</h2>
     *
     * <p>Between the turn and a confirmed pivot there is no stop at all, and it
     * is not an oversight. A pivot needs {@code wing} bars on its right before it
     * is a fact, and the bottom itself takes however long it takes: in the
     * drawing of this rule it is four bars to the bottom and two more to confirm
     * it. Anything put in that gap would be a number chosen in a fright rather
     * than a level the market drew, and the whole point of this stop is that the
     * market drew it.
     *
     * <h2>It does not move afterwards</h2>
     *
     * <p>The first pivot after the turn, and that one only. Following later
     * pivots up would be a trailing stop, which is a different rule with a
     * different answer, and this one was specified as a level.
     *
     * <h2>A stop a gap can skip is not a stop</h2>
     *
     * <p>An NTSL stop is a PAIR — a trigger and a limit — and everywhere else in
     * this project the two are the same number, which is right for an ENTRY: a
     * bar that opens beyond the trigger is a breakout already gone, and refusing
     * it is refusing to chase. For a protective stop it is the opposite. Written
     * that way, a bar that opens below the level triggers the order and then
     * refuses the fill for being worse than the limit — measured here on a
     * made-up session: the level was 103.890, the bar opened at 103.845, and the
     * position rode on to the end of the day.
     *
     * <p>So the limit sits {@code slip} points past the trigger, and that number
     * is what the stop is allowed to cost beyond the level. Past it the fill is
     * refused again, which is also true of a real stop-limit: this models the
     * order, it does not pretend the gap away.
     *
     * @param on   whether the stop applies
     * @param wing the zigzag's wing; two is what "zigzag 2" means
     * @param slip the most, in points, the fill may be worse than the level
     */
    public record Flip(boolean on, int wing, double slip) {

        /** Forty ticks. Generous, because refusing a stop is the worse error. */
        public static final double SLIP = 200;

        public Flip {
            wing = Math.max(1, wing);
            slip = slip >= 0 ? slip : SLIP;
        }

        public static Flip off() {
            return new Flip(false, Pivots.WING, SLIP);
        }

        public static Flip standard() {
            return new Flip(true, Pivots.WING, SLIP);
        }
    }

    /**
     * The stop the position is born with, read off the zigzag at the entry.
     *
     * <p>Of the <b>two</b> most recent bottoms a buy can see when it goes on,
     * the LOWER one, and the stop a tick under it. Two and not one because one
     * bottom is a level the market touched once; two that agree is a level it
     * came back to, and the lower of them is the one that has actually held.
     *
     * <h2>No two bottoms, no entry</h2>
     *
     * <p>Not "enter without a stop": the entry is refused. It costs the trades
     * at the start of every recorte and of every session, and that is the price
     * of never carrying a position whose risk has no level attached to it.
     *
     * <h2>One level for the position, re-read at every entry</h2>
     *
     * <p>The second rung goes on further out and brings the bottoms from out
     * there, so the level moves to its — which is wider, because averaging in
     * widened the position. Per-lot stops were the alternative and they cannot
     * be told apart: two resting stops carry the same verb, so a fill would have
     * to be matched to a lot by its PRICE, which is the Range 90's defect all
     * over again.
     *
     * <h2>Half the levels are on the wrong side, and that is the rule meeting
     * this strategy</h2>
     *
     * <p>Measured on his year: of 4.337 entries, <b>2.210 — 51,0%</b> — come out
     * with the level ABOVE the buy, by up to 1.460 points. It is not a slip of
     * the arithmetic, it is what the rule does here. A fade BUYS at the lower
     * edge of the channel, which is a price below what the market had been
     * marking; the last two bottoms are therefore above it. A stop above the buy
     * is no stop at all — it triggers at the next open and the trade is born and
     * dies paying the round trip.
     *
     * <p>The two switches below are the two ways out, and they are switches
     * rather than a decision because they measure differently and the choice is
     * his. Counting only the pivots that would actually protect, 4.336 of the
     * 4.337 entries have two of them, the nearest 124 points away on average.
     *
     * @param on         whether the stop applies
     * @param slip       the most, in points, the fill may be worse than the level
     * @param onlyArmed  refuse the entry when the level would not protect it
     * @param fromTheDip take the level from the first pivot AFTER the entry —
     *                   the bottom of the dip being bought — instead of from the
     *                   two that came before it
     */
    public record Guard(boolean on, double slip, boolean onlyArmed, boolean fromTheDip) {

        public Guard {
            slip = slip >= 0 ? slip : Flip.SLIP;
        }

        public static Guard off() {
            return new Guard(false, Flip.SLIP, false, false);
        }

        /** The rule as he first stated it, with neither way out switched on. */
        public static Guard standard() {
            return new Guard(true, Flip.SLIP, false, false);
        }
    }

    private final ZoneId zone;

    private final List<Rung> ladder;

    private final int lot;

    private final StochasticLatch latch;

    private final Flip flip;

    private final Guard guard;

    // ---------------------------------------------------------------- the run

    private PriceSeries bars;

    private PriceSeries source;

    private int size;

    private int[] session;

    /** Each rung's fit, per decision bar, keyed the way {@link #ladder} is. */
    private Map<Integer, Regression.Fit[]> fits;

    /** The pivot that became a fact at each decision bar, or null. */
    private Pivots.Pivot[] pivots;

    /**
     * The level a buy would stop under at each bar — the lower of the two most
     * recent confirmed bottoms — and the mirror for a sell. NaN where there are
     * not two yet.
     */
    private double[] underTwoBottoms;

    private double[] overTwoTops;

    // --------------------------------------------------------------- the book

    /** One lot that is on: which rung put it there, and which way it faces. */
    private record Lot(Rung rung, int side, int quantity) { }

    private final List<Lot> open = new ArrayList<>();

    /** The rung whose order is on the book, and which way it faces. */
    private Rung resting;

    /** One lot and the level its target went to the book at. */
    private record Aimed(Lot lot, double level) { }

    /**
     * The targets standing on the book, one per open lot.
     *
     * <p>Written down rather than worked out again when the fill arrives: a
     * cover fill carries a verb, a price and a size, not the order it came
     * from, and by the time it is settled the channel has moved on to the
     * next bar — so recomputing the levels would be asking a different
     * question than the one the orders were sent to answer.</p>
     */
    private final List<Aimed> aiming = new ArrayList<>();

    private int restingSide;

    /** Rungs already spent since the latch last armed. */
    private final List<Rung> spent = new ArrayList<>();

    /**
     * The bar both channels first turned against the open position, or −1.
     *
     * <p>On the POSITION and not on a lot: every lot faces the same way, and the
     * turn is about the market rather than about which rung put a lot on.</p>
     */
    private int turned;

    /** Where the flip stop sits once a pivot has set it, or NaN. */
    private double flipStop;

    /** Where the entry stop sits, read at the newest entry, or NaN. */
    private double guardStop;

    /** What the entry stop WOULD be for the order resting now, or NaN. */
    private double restingStop;

    /** The decision bar of the newest entry, for the dip's own pivot; or −1. */
    private int enteredAt;

    private double[] entryLine;

    private double[] targetLine;

    public ChannelFade() {
        this(null, LADDER, 1, StochasticLatch.Settings.standard(),
                Flip.standard(), Guard.standard());
    }

    /** Without either stop: the lot leaves at its target or at the bell. */
    public ChannelFade(ZoneId zone, List<Rung> ladder, int lot,
                       StochasticLatch.Settings latch) {

        this(zone, ladder, lot, latch, Flip.off(), Guard.off());
    }

    /** With the turn stop only. */
    public ChannelFade(ZoneId zone, List<Rung> ladder, int lot,
                       StochasticLatch.Settings latch, Flip flip) {

        this(zone, ladder, lot, latch, flip, Guard.off());
    }

    /**
     * @param zone   the exchange's zone, which decides where a session begins
     * @param ladder the rungs, in any order; the nearest is chosen each bar
     * @param lot    contracts per rung
     * @param latch  the stochastic filter
     * @param flip   the stop for when both channels turn against the position
     * @param guard  the stop read off the zigzag at the entry
     */
    public ChannelFade(ZoneId zone, List<Rung> ladder, int lot,
                       StochasticLatch.Settings latch, Flip flip, Guard guard) {

        this.zone = zone == null ? Timeframe.defaultZone() : zone;
        this.ladder = ladder == null || ladder.isEmpty() ? LADDER : List.copyOf(ladder);
        this.lot = Math.max(1, lot);
        this.latch = new StochasticLatch(latch);
        this.flip = flip == null ? Flip.off() : flip;
        this.guard = guard == null ? Guard.off() : guard;
    }

    @Override
    public void sourcedFrom(PriceSeries stored) {
        source = stored;
    }

    @Override
    public void start(PriceSeries series) {
        bars = series == null ? PriceSeries.empty() : series;
        size = bars.size();
        session = new int[size];

        LocalDate day = null;
        int which = -1;

        for (int bar = 0; bar < size; bar++) {
            LocalDate now = Instant.ofEpochMilli(bars.timeAt(bar)).atZone(zone).toLocalDate();

            if (!now.equals(day)) {
                day = now;
                which++;
            }

            session[bar] = which;
        }

        entryLine = blank(size);
        targetLine = blank(size);

        open.clear();
        spent.clear();

        turned = -1;
        flipStop = Double.NaN;
        guardStop = Double.NaN;
        restingStop = Double.NaN;
        enteredAt = -1;

        aiming.clear();

        latch.start(bars, source);
        fitTheChannels();

        // ONE ZIGZAG for both stops, and its wing lives on Flip because that is
        // where it arrived first. Two wings would be two zigzags, and the chart
        // draws one.
        pivots = flip.on() || guard.on()
                ? new Pivots(flip.wing()).confirmedAt(bars)
                : new Pivots.Pivot[size];

        rememberTheLastTwo();
    }

    /**
     * Works out, for every bar, the level each side would stop beyond.
     *
     * <p>The lower of the two most recent confirmed bottoms, and the higher of
     * the two most recent tops. Walked forwards once rather than searched
     * backwards per bar, and — the part that matters — a pivot is absorbed on
     * the bar that CONFIRMED it, so the answer at any bar is made only of
     * pivots that were facts by then.</p>
     */
    private void rememberTheLastTwo() {
        underTwoBottoms = blank(size);
        overTwoTops = blank(size);

        double newestLow = Double.NaN;
        double olderLow = Double.NaN;
        double newestHigh = Double.NaN;
        double olderHigh = Double.NaN;

        for (int bar = 0; bar < size; bar++) {
            // NADA ATRAVESSA O PREGAO, e isto vazava: o fundo de ontem, depois
            // do salto da noite, nao e um nivel de hoje -- e em 1,6% das
            // entradas do ano dele era exatamente esse o nivel usado. Todo o
            // resto desta estrategia ja respeitava a virada.
            if (bar > 0 && session[bar] != session[bar - 1]) {
                newestLow = Double.NaN;
                olderLow = Double.NaN;
                newestHigh = Double.NaN;
                olderHigh = Double.NaN;
            }

            Pivots.Pivot now = pivots[bar];

            if (now != null) {
                if (now.top()) {
                    olderHigh = newestHigh;
                    newestHigh = now.price();
                } else {
                    olderLow = newestLow;
                    newestLow = now.price();
                }
            }

            if (!Double.isNaN(olderLow)) {
                underTwoBottoms[bar] = Math.min(newestLow, olderLow);
            }

            if (!Double.isNaN(olderHigh)) {
                overTwoTops[bar] = Math.max(newestHigh, olderHigh);
            }
        }
    }

    private static double[] blank(int many) {
        double[] made = new double[many];

        Arrays.fill(made, Double.NaN);

        return made;
    }

    /**
     * Fits every channel the ladder names, once, on ONE MINUTE.
     *
     * <p>On the minute and not on the decision bars, because that is what "canal
     * de regressão em 1m" says and because it has to survive the chart being put
     * on another scale. Each decision bar takes the fit of the LAST minute
     * inside it — every one of those has closed by the time the bar closes, so
     * nothing here reads a price that has not happened.</p>
     */
    private void fitTheChannels() {
        fits = new LinkedHashMap<>();

        if (size == 0) {
            return;
        }

        PriceSeries minutes = Timeframe.ONE_MINUTE.apply(source == null ? bars : source);

        for (Rung rung : ladder) {
            if (fits.containsKey(rung.period())) {
                continue;
            }

            Regression.Fit[] perMinute = new Regression(rung.period()).over(minutes);
            Regression.Fit[] perBar = new Regression.Fit[size];

            Arrays.fill(perBar, Regression.Fit.NONE);

            int at = 0;

            for (int minute = 0; minute < minutes.size(); minute++) {
                while (at + 1 < size && minutes.timeAt(minute) >= bars.timeAt(at + 1)) {
                    at++;
                }

                perBar[at] = perMinute[minute];
            }

            fits.put(rung.period(), perBar);
        }
    }

    /** Both stops, and the turn that armed one of them, forgotten together. */
    private void forgetTheStops() {
        turned = -1;
        flipStop = Double.NaN;
        guardStop = Double.NaN;
        enteredAt = -1;
    }

    private Regression.Fit fitAt(Rung rung, int bar) {
        Regression.Fit[] perBar = fits.get(rung.period());

        return perBar == null ? Regression.Fit.NONE : perBar[bar];
    }

    @Override
    public void onBar(Market market, Desk desk) {
        if (size == 0) {
            return;
        }

        int bar = market.bar();

        settle(market.filled());

        // NOTHING CROSSES THE SESSION, and the order goes out one bar early
        // because ClosePosition is a market order and a market order fills at
        // the NEXT bar's open. Sent on the last bar of the day, it would fill
        // tomorrow, and the exit price would be the overnight gap.
        boolean closing = bar + 2 >= size || session[bar + 2] != session[bar];

        if (closing) {
            open.clear();
            spent.clear();
            latch.clear();

            forgetTheStops();

            restingStop = Double.NaN;

            if (market.hasPosition()) {
                desk.closePosition();
            }

            return;
        }

        // A NEW ARMING FREES THE LADDER. The rungs are rationed per stretch, not
        // per run: price came back to the middle and went out again, so this is
        // a second stretch and it gets the whole ladder, not whatever the first
        // one left over.
        if (latch.at(bar)) {
            spent.clear();
        }

        watchForTheTurn(bar);
        watchForTheDip(bar);
        cover(bar, desk);
        enter(bar, market, desk);
    }

    /**
     * Keeps the book in step with what executed.
     *
     * <p>A cover closes the lot whose target executed, found by the LEVEL the
     * order went to the book at. A limit fills at its level or better, so a
     * sell-cover fill sits at or above the level that sent it: the lot to
     * close is the one with the highest level the fill reached, and the next
     * fill of the same bar takes the one below it. An opening fill is the
     * rung chosen at the last close, which is recorded rather than
     * recognised.
     */
    private void settle(List<Fill> fills) {
        for (Fill fill : fills) {
            String verb = fill.verb();

            if (verb == null) {
                continue;
            }

            // THE VERB SAYS WHICH LEG IT WAS, so nothing has to be matched by
            // price or by size: the target is a *CoverLimit and belongs to one
            // lot, the turn stop is a *CoverStop and belongs to the position.
            if (verb.contains("Cover") && verb.contains("Stop")) {
                open.clear();
                aiming.clear();
                forgetTheStops();
            } else if (verb.contains("Close") || verb.contains("Reverse")) {
                // THE WHOLE MAO, because both of these are the position and
                // not a leg of it.
                open.clear();
                aiming.clear();
                forgetTheStops();
            } else if (verb.contains("Cover")) {
                closeTheLotThatWentAt(fill.price());

                if (open.isEmpty()) {
                    forgetTheStops();
                }
            } else if (verb.startsWith("Buy") || verb.startsWith("SellShort")) {
                if (resting != null) {
                    open.add(new Lot(resting, restingSide, fill.quantity()));
                    spent.add(resting);
                    latch.spend(restingSide);

                    // THE NEWEST ENTRY OWNS THE LEVEL. The second rung went on
                    // further out and brought the bottoms from out there; the
                    // position widened, so its stop widens with it.
                    guardStop = restingStop;
                    enteredAt = fill.bar();
                    resting = null;
                }
            }
        }
    }

    /**
     * Closes the lot whose target executed at this price.
     *
     * <p>A limit never fills worse than its level: a sell-cover fills at or
     * ABOVE the level it rests at, a buy-cover at or below. So every target the
     * fill reached is a candidate, and the one that sent it is the last of them
     * — the highest level for a sell, the lowest for a buy. Taking the closest
     * first is what makes several fills on one bar land on the right lots: each
     * takes its own, in the order the price walked over them.</p>
     *
     * <p>Nothing matching means a cover nobody here asked for — a ClosePosition
     * shaped differently, or the day being shut. The oldest lot goes, so the
     * book cannot drift out of step with the position.</p>
     */
    private void closeTheLotThatWentAt(double price) {
        int found = -1;
        double best = Double.NaN;

        for (int which = 0; which < aiming.size(); which++) {
            Aimed each = aiming.get(which);
            int side = each.lot().side();

            // Reached: for a long exit the fill is at or above the level.
            if (side * (price - each.level()) < -TICK) {
                continue;
            }

            if (found < 0 || side * (each.level() - best) > 0) {
                found = which;
                best = each.level();
            }
        }

        if (found < 0) {
            if (!open.isEmpty()) {
                open.remove(0);
            }

            return;
        }

        open.remove(aiming.remove(found).lot());
    }

    /**
     * Watches for both channels turning against the position, and then for the
     * first pivot that gives the stop a level.
     *
     * <p>The pivot has to be AFTER the turn — a bottom the market drew while the
     * channels still pointed up is a bottom of the old thesis, and putting the
     * stop there would be reading a level out of a market that no longer
     * exists.</p>
     */
    private void watchForTheTurn(int bar) {
        if (!flip.on() || open.isEmpty()) {
            return;
        }

        int side = open.get(0).side();

        if (turned < 0) {
            if (sideAllowed(bar) == -side) {
                turned = bar;
            }

            return;
        }

        if (!Double.isNaN(flipStop)) {
            return;
        }

        Pivots.Pivot now = pivots[bar];

        // A long wants the first BOTTOM; a short wants the first top.
        if (now == null || now.bar() <= turned || now.top() != (side < 0)) {
            return;
        }

        // A CONFIRMED PIVOT HAS NOT BEEN BROKEN -- see Pivots -- so this level
        // cannot already be behind the price, and there is no case here for a
        // stop that is violated the moment it is written down.
        flipStop = now.price() - side * TICK;
    }

    /**
     * Takes the level from the dip the position was bought into.
     *
     * <p>The first pivot of the right kind CONFIRMED after the entry, which on a
     * fade is the bottom of the dip the limit was sitting in. It is the level
     * the rule wants and the one the two earlier bottoms cannot give: those are
     * above the entry, because the entry is below where the market had been.
     *
     * <p>The price of it is the same gap the turn stop has — a pivot is a fact
     * {@code wing} bars after it happens, so the position carries no level of
     * its own until then.
     */
    private void watchForTheDip(int bar) {
        if (!guard.on() || !guard.fromTheDip() || open.isEmpty()) {
            return;
        }

        if (!Double.isNaN(guardStop) || bar <= enteredAt) {
            return;
        }

        Pivots.Pivot now = pivots[bar];
        int side = open.get(0).side();

        // CONFIRMED after the entry, and the pivot's own bar is not asked
        // about: the low of the dip is usually a bar or two BEFORE the fill,
        // and it is still the dip that was bought.
        if (now == null || now.top() != (side < 0)) {
            return;
        }

        guardStop = now.price() - side * TICK;
    }

    /**
     * Sends the cover for the lot closest to leaving.
     *
     * <p>Re-sent every bar at the level the channel has NOW: the book is rebuilt
     * from what a strategy asks for at each close, so an order not asked for
     * again is cancelled — and a target that did not move would be a target of
     * a channel that no longer exists.</p>
     */
    private void cover(int bar, Desk desk) {
        if (open.isEmpty()) {
            return;
        }

        int side = open.get(0).side();
        double now = bars.closeAt(bar);

        // ONE TARGET PER LOT, all of them on the book at once. The rung that
        // put a lot on says where it comes off, and the legs of one OCO
        // compete for the position rather than with each other — so a bar
        // that walks over three levels takes three lots, which is what a
        // ladder was for. His own RoboNovo6s LIFO does exactly this: three
        // SellToCoverLimit in one pass, one per lot of the queue.
        aiming.clear();

        double best = Double.POSITIVE_INFINITY;

        for (Lot lot : open) {
            Regression.Fit fit = fitAt(lot.rung(), bar);

            if (!fit.known()) {
                continue;
            }

            // The other side of the line from the entry: a sell came on above
            // it and comes off below it. The side is -1 for a sell, so adding
            // it subtracts.
            double level = fit.line() + lot.side() * lot.rung().target() * fit.sigma();

            aiming.add(new Aimed(lot, level));

            if (side > 0) {
                desk.sellToCoverLimit(level, lot.quantity());
            } else {
                desk.buyToCoverLimit(level, lot.quantity());
            }

            // Signed, not absolute: a target the price has already gone past
            // fills at the open, and counting it as far away would draw the
            // curve on the one behind it instead.
            double away = lot.side() * (level - now);

            if (away < best) {
                best = away;
                targetLine[bar] = level;
            }
        }

        if (aiming.isEmpty()) {
            return;
        }

        double stop = nearestStop(side);

        if (Double.isNaN(stop)) {
            return;
        }

        // THE WHOLE POSITION, and not this lot. Neither stop is a rung's -- the
        // rungs differ in where they came on and where they aim, and both of
        // these are about the trade as a whole being wrong.
        int held = 0;

        for (Lot each : open) {
            held += each.quantity();
        }

        double room = stop - side * flip.slip();

        if (side > 0) {
            desk.sellToCoverStop(stop, room, held);
        } else {
            desk.buyToCoverStop(stop, room, held);
        }
    }

    /**
     * @param side which way the position faces
     * @return whichever of the two stops the price reaches first, or NaN
     *
     * <p>ONE order for both, and it has to be one: two resting stops carry the
     * same verb, so a fill could not be told apart and the book would have to
     * guess which of them went. The nearer is the one that would have fired
     * first anyway, and either firing ends the trade — so nothing is lost by
     * sending only it.</p>
     */
    private double nearestStop(int side) {
        if (Double.isNaN(flipStop)) {
            return guardStop;
        }

        if (Double.isNaN(guardStop)) {
            return flipStop;
        }

        // Under a long both sit below the price, and the HIGHER is the nearer.
        return side > 0 ? Math.max(flipStop, guardStop) : Math.min(flipStop, guardStop);
    }

    /**
     * Rests the nearest unspent rung, if everything agrees.
     *
     * <p>Nearest to the price that is there now, which is what makes the fill
     * unambiguous: price has to travel through the near rung to reach the far
     * one, so taking them in that order is what the market actually does.</p>
     */
    private void enter(int bar, Market market, Desk desk) {
        resting = null;
        restingStop = Double.NaN;

        int side = sideAllowed(bar);

        if (side == 0 || latch.credit(side) <= 0) {
            return;
        }

        // NO TWO PIVOTS, NO ENTRY -- and not "enter without a stop". It costs
        // the trades at the start of every recorte and of every session, and
        // that is the price of never carrying a position whose risk has no
        // level attached to it.
        double beyond = side > 0 ? underTwoBottoms[bar] : overTwoTops[bar];

        if (guard.on() && !guard.fromTheDip() && Double.isNaN(beyond)) {
            return;
        }

        double now = bars.closeAt(bar);
        Rung nearest = null;
        double best = Double.POSITIVE_INFINITY;
        double level = Double.NaN;

        for (Rung rung : ladder) {
            if (spent.contains(rung)) {
                continue;
            }

            Regression.Fit fit = fitAt(rung, bar);

            if (!fit.known() || fit.direction() != side) {
                continue;
            }

            // A sell rests ABOVE the price, on the high side of the line.
            double price = fit.line() - side * rung.entry() * fit.sigma();
            double away = -side * (price - now);

            // Already past it: the level is on the wrong side of the price now,
            // and a limit there would fill at once at a price the thesis never
            // asked for.
            if (away < 0 || away >= best) {
                continue;
            }

            best = away;
            nearest = rung;
            level = price;
        }

        if (nearest == null) {
            return;
        }

        // ONLY WHEN IT PROTECTS. Compared against the LEVEL the order rests at
        // and not against a fill nobody has yet -- and the level is the
        // optimistic end of it, because a limit can fill better, which for a buy
        // means lower, which only puts the stop further above.
        boolean protects = side > 0 ? beyond < level : beyond > level;

        if (guard.on() && guard.onlyArmed() && !(protects && !Double.isNaN(beyond))) {
            return;
        }

        resting = nearest;
        restingSide = side;
        restingStop = guard.on() && !guard.fromTheDip()
                ? beyond - side * TICK : Double.NaN;
        entryLine[bar] = level;

        int many = lot;

        if (side < 0) {
            desk.sellShortLimit(level, many);
        } else {
            desk.buyLimit(level, many);
        }
    }

    /**
     * @return the side BOTH channels allow, or zero
     *
     * <p>Both, and it is the condition that makes this a fade rather than a
     * guess: a channel falling is a market the model already knows is going
     * down, and the rung is how far above that expectation price has got. Two
     * clocks disagreeing means there is no expectation to be stretched from.</p>
     */
    private int sideAllowed(int bar) {
        int agreed = 0;

        for (Rung rung : ladder) {
            Regression.Fit fit = fitAt(rung, bar);

            if (!fit.known() || fit.direction() == 0) {
                return 0;
            }

            // The SELL is the side that fades a FALLING channel: the stretch is
            // upward, against a market pointing down.
            int wants = fit.direction();

            if (agreed == 0) {
                agreed = wants;
            } else if (agreed != wants) {
                return 0;
            }
        }

        return agreed;
    }

    @Override
    public Map<String, double[]> curves() {
        Map<String, double[]> drawn = new LinkedHashMap<>();

        drawn.put("Entrada", entryLine);
        drawn.put("Alvo", targetLine);

        return drawn;
    }

    @Override
    public String toString() {
        return "Fade de canal";
    }
}
