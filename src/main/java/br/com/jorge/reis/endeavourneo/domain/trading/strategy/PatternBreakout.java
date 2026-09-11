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

import br.com.jorge.reis.endeavourneo.domain.indicator.Stochastic;
import br.com.jorge.reis.endeavourneo.domain.market.CandlePattern;
import br.com.jorge.reis.endeavourneo.domain.market.CandlePatterns;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A reversal pattern traded by LEVELS: in on the break, stop at the pattern's
 * own extreme, target by the risk-reward ratio.
 *
 * <p>Brought over from the original endeavour, where it is
 * {@code signal.PatternBreakout}. It replaces the exit-by-bar-count those
 * patterns used before: leaving by the clock measures something else, because
 * the pattern itself says where the thesis dies — the extreme it has just
 * refused — and a timed exit ignores that definition.
 *
 * <h2>The side comes from the pattern, not from convention</h2>
 *
 * <p>A PFR up is a new low refused at the close, so one buys the break of its
 * high with the stop at the low it marked. The down one is the mirror. Unlike
 * the inside bar there is no ambiguity of direction to settle.
 *
 * <ul>
 *   <li><b>Entry</b>: one tick beyond the high (up) or the low (down) of the
 *       bar that confirmed. Beyond and not at it: at the extreme, the order
 *       fills on a touch that never broke anything.</li>
 *   <li><b>A gap over the trigger is a trade not taken.</b> The order is
 *       {@code BuyStop(trigger, trigger)}, the NTSL shape his robots send, so
 *       the limit <i>is</i> the trigger: a bar that opens beyond the level does
 *       not fill, because paying 200 on an order for 115 is exactly what the
 *       limit refuses. The entry price is therefore always the trigger itself,
 *       which is why nothing here re-measures the target from the fill.</li>
 *   <li><b>Stop</b>: the opposite extreme of the <b>STRUCTURE</b> — the three
 *       bars that make the pattern — and not necessarily of the signal bar. On
 *       a PFR the two coincide, because it was the signal bar that marked the
 *       new extreme; on a 1-2-3 the extreme is on the PIVOT, one bar back.
 *       Going beyond it is the pattern having failed — the stop is the
 *       definition of the error, not a number somebody chose.</li>
 *   <li><b>Target</b>: the entry-to-stop distance times the ratio asked for, so
 *       the ratio asked for is the ratio measured.</li>
 * </ul>
 *
 * <h2>The three bars have to be in the same session</h2>
 *
 * <p>{@link CandlePatterns} compares a bar with the two before it without
 * looking at the turn of the day. Without this guard the first bar of a session
 * becomes a "new low" because of the overnight gap, and the pattern is a
 * property of the gap rather than of the market.
 */
public final class PatternBreakout implements Strategy, Plotted, Sourced {

    /** One tick of the mini index. The trigger sits BEYOND the extreme. */
    public static final double TICK = 5;

    /** How many bars the resting entry lives for, unless asked otherwise. */
    public static final int VALID_FOR = 1;

    public static final double REWARD = 1.5;

    /**
     * The stochastic latch: what has to have happened for a pattern to count.
     *
     * <p>A PFR is a refusal, and a refusal is worth more where the market had
     * something to refuse. The latch says so in a testable way: the stochastic
     * of eight on ONE MINUTE has to have reached its level, and reaching it
     * <b>arms</b> a credit of a few entries which the next patterns spend.
     *
     * <h2>It arms on the way IN, not on every bar inside</h2>
     *
     * <p>That is what makes it a latch rather than a condition. Price can sit
     * under twenty for forty minutes; that is one event, not forty, and treating
     * it as forty would refill the credit continuously and the cap would mean
     * nothing. Leaving the zone and coming back is a second event.
     *
     * <h2>The two sides are separate</h2>
     *
     * <p>Twenty arms buys and eighty arms sells: oversold is what makes a
     * refused low worth buying. A buy spends buy credit and leaves the sell
     * credit where it was.
     *
     * @param on        whether the filter applies at all
     * @param period    the stochastic's range, in bars of one minute
     * @param average   its smoothing
     * @param buyLevel  at or below this, buys are armed
     * @param sellLevel at or above this, sells are armed
     * @param entries   how many entries one arming pays for
     */
    public record Latch(boolean on, int period, int average,
                        double buyLevel, double sellLevel, int entries) {

        public Latch {
            period = Math.max(1, period);
            average = Math.max(1, average);
            entries = Math.max(1, entries);
        }

        /** No filter: every pattern of the chosen family is traded. */
        public static Latch off() {
            return new Latch(false, Stochastic.PERIOD, Stochastic.AVERAGE, 20, 80, 2);
        }

        /** Eight and three on one minute, twenty and eighty, two entries. */
        public static Latch standard() {
            return new Latch(true, Stochastic.PERIOD, Stochastic.AVERAGE, 20, 80, 2);
        }
    }

    /**
     * Doubling down: the lot doubles after each loss.
     *
     * <p><b>What this is.</b> A martingale. It does not improve an edge and it
     * cannot create one — it trades a high chance of a small gain for a small
     * chance of a very large loss, and the expected value is unchanged except
     * for the costs, which rise with the lot. What it does do is make a losing
     * run arrive all at once instead of gradually, and that is worth being able
     * to measure rather than argue about.
     *
     * <p>Two things bound it here. The count of doublings is capped, so the lot
     * cannot run away; and the sequence <b>zeroes at the end of every
     * session</b>, so a bad afternoon does not open the next morning holding
     * sixteen contracts. It also zeroes on any trade that closed positive.
     *
     * @param on   whether the module applies
     * @param most how many times the lot may double; N=3 gives 1, 2, 4, 8
     */
    public record Doubling(boolean on, int most) {

        /** Past this the lot is astronomical and the cap is the only thing left. */
        public static final int CEILING = 20;

        /**
         * A module that is off doubles nothing, and says so in the ONE number
         * that decides it.
         *
         * <p>{@code most} was clamped and {@code on} was checked separately
         * wherever the count moved, which made a {@code Doubling(false, 5)}
         * representable — a state where the two fields disagree and every reader
         * has to remember which one wins. Zeroing it here is what lets those
         * checks go: {@code min(doublings + 1, 0)} is zero, so the lot cannot
         * move, and there is no second guard to keep in step with this one.</p>
         */
        public Doubling {
            most = on ? Math.max(0, Math.min(most, CEILING)) : 0;
        }

        public static Doubling off() {
            return new Doubling(false, 0);
        }
    }

    private final ZoneId zone;

    private final CandlePattern.Family family;

    private final double reward;

    private final int validFor;

    private final int lot;

    private final Latch latch;

    private final Doubling doubling;

    // ---------------------------------------------------------------- the run

    private PriceSeries bars;

    /** The bars as stored, which the latch reads its minutes from. */
    private PriceSeries source;

    private int size;

    /** Which session each bar belongs to; a new number means the day turned. */
    private int[] session;

    /** Decision bars during which the stochastic reached the buy level. */
    private boolean[] armsBuy;

    private boolean[] armsSell;

    private int buyCredit;

    private int sellCredit;

    /** How many times the lot has doubled in the current losing run. */
    private int doublings;

    /** What the open position was entered at, for telling a loss from a gain. */
    private double entered;

    // ---------------------------------------------------------------- the plan

    private int side;

    private double trigger;

    private double stop;

    private double target;

    private int livesUntil;

    private boolean holding;

    private double[] entryLine;

    private double[] stopLine;

    private double[] targetLine;

    public PatternBreakout() {
        this(null, CandlePattern.Family.PFR, REWARD, VALID_FOR, 1);
    }

    /** The pattern on its own, with no filter and no doubling. */
    public PatternBreakout(ZoneId zone, CandlePattern.Family family, double reward,
                           int validFor, int lot) {

        this(zone, family, reward, validFor, lot, Latch.off(), Doubling.off());
    }

    /**
     * @param zone     the exchange's zone, which decides where a session begins
     * @param family   which shape to trade: PFR, inside or 1-2-3
     * @param reward   the target, as a multiple of the entry-to-stop distance
     * @param validFor how many bars the resting entry lives for
     * @param lot      contracts per trade, before any doubling
     * @param latch    the stochastic filter, or {@link Latch#off()}
     * @param doubling the martingale, or {@link Doubling#off()}
     */
    public PatternBreakout(ZoneId zone, CandlePattern.Family family, double reward,
                           int validFor, int lot, Latch latch, Doubling doubling) {

        this.zone = zone == null ? Timeframe.defaultZone() : zone;
        this.family = family == null ? CandlePattern.Family.PFR : family;
        this.reward = reward > 0 ? reward : REWARD;
        this.validFor = Math.max(1, validFor);
        this.lot = Math.max(1, lot);
        this.latch = latch == null ? Latch.off() : latch;
        this.doubling = doubling == null ? Doubling.off() : doubling;
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
        stopLine = blank(size);
        targetLine = blank(size);

        side = 0;
        trigger = Double.NaN;
        stop = Double.NaN;
        target = Double.NaN;
        livesUntil = -1;
        holding = false;
        buyCredit = 0;
        sellCredit = 0;
        doublings = 0;
        entered = Double.NaN;

        findTheArmings();
    }

    /**
     * Works out, once, on which decision bars the latch arms.
     *
     * <p>Read on ONE MINUTE and not on the decision bars, which is the whole
     * point of asking for it that way: the chart may be on five minutes or on
     * renko, where there is no minute in the series at all, and the reading is
     * supposed to be the same either way. The minutes come from the bars as
     * stored — see {@link Sourced} — and aggregating them to one minute is a
     * no-op on his base and the only honest answer on a finer one.
     *
     * <p><b>Every minute used here is inside the decision bar it is filed
     * under</b>, so by the time that bar closes and the strategy is asked, all
     * of them have happened. The look-ahead this could have had — filing a
     * minute under the bar it precedes — is the same mistake the multi-timeframe
     * note in the chart's own {@code OwnScale} records, running the other way.
     */
    private void findTheArmings() {
        armsBuy = new boolean[size];
        armsSell = new boolean[size];

        if (!latch.on() || size == 0) {
            return;
        }

        PriceSeries minutes = Timeframe.ONE_MINUTE.apply(source == null ? bars : source);
        double[] slow = new Stochastic(latch.period(), latch.average()).over(minutes).slow();

        int at = 0;
        boolean wasLow = false;
        boolean wasHigh = false;

        for (int minute = 0; minute < minutes.size(); minute++) {
            while (at + 1 < size && minutes.timeAt(minute) >= bars.timeAt(at + 1)) {
                at++;
            }

            double now = slow[minute];

            if (Double.isNaN(now)) {
                continue;
            }

            boolean low = now <= latch.buyLevel();
            boolean high = now >= latch.sellLevel();

            // ON THE WAY IN ONLY. Forty minutes spent under twenty is one event,
            // not forty; counting each of them would refill the credit every
            // bar and the cap on entries would say nothing.
            if (low && !wasLow) {
                armsBuy[at] = true;
            }

            if (high && !wasHigh) {
                armsSell[at] = true;
            }

            wasLow = low;
            wasHigh = high;
        }
    }

    private static double[] blank(int many) {
        double[] made = new double[many];

        Arrays.fill(made, Double.NaN);

        return made;
    }

    @Override
    public void onBar(Market market, Desk desk) {
        if (size == 0) {
            return;
        }

        int bar = market.bar();

        settle(market.filled());

        // NOTHING CROSSES THE SESSION. The pattern is a shape of three bars of
        // one day, and the level it points at stops meaning anything once the
        // market has gapped away from it overnight.
        //
        // THE ORDER GOES OUT ONE BAR EARLY, and that is the whole point of the
        // {@code + 2}. ClosePosition is a MARKET order, and a market order fills
        // at the OPEN OF THE NEXT BAR. Sent on the last bar of the session, the
        // next bar is tomorrow, so the exit price is the overnight gap — the
        // position was carried, and the P&L is a number the day never offered.
        // Sent one bar earlier, the next bar is still today.
        boolean closing = bar + 2 >= size || session[bar + 2] != session[bar];

        if (closing) {
            forget();

            // THE DOUBLING SEQUENCE DIES WITH THE SESSION, and so does whatever
            // the latch had armed. A run of losses that ends the afternoon
            // holding eight contracts would otherwise open tomorrow morning
            // holding sixteen, against a market that has gapped overnight and
            // owes yesterday nothing.
            doublings = 0;
            buyCredit = 0;
            sellCredit = 0;

            if (market.hasPosition()) {
                desk.closePosition();
            }

            draw(bar);

            return;
        }

        // The arming is applied BEFORE the pattern is looked for, and both
        // happen at this bar's close: every minute that armed it is inside the
        // bar that just ended.
        if (armsBuy[bar]) {
            buyCredit = latch.entries();
        }

        if (armsSell[bar]) {
            sellCredit = latch.entries();
        }

        if (!holding) {
            look(bar);
        }

        draw(bar);
        emit(market, desk);
    }

    /**
     * Looks for a pattern on the bar that has just closed, and turns it into a
     * plan.
     *
     * <p>The plan of an older bar is dropped the moment a new pattern appears:
     * the newer level is the one the market has just refused.
     */
    private void look(int bar) {
        if (bar <= livesUntil && side != 0) {
            return;
        }

        if (bar > livesUntil) {
            forget();
        }

        if (bar < CandlePatterns.LOOKBACK) {
            return;
        }

        // The three bars in ONE session. The detector does not know about the
        // turn of the day; without this the first bar of a session is a "new
        // low" by the overnight gap alone.
        for (int back = bar - CandlePatterns.LOOKBACK; back < bar; back++) {
            if (session[back] != session[bar]) {
                return;
            }
        }

        CandlePattern pattern = CandlePatterns.detect(bars, bar);

        if (pattern.isNone() || pattern.family() != family || pattern.direction() == 0) {
            return;
        }

        // THE LATCH HAS TO HAVE BEEN ARMED, on this side. The credit is spent by
        // the ENTRY and not by the plan: a trigger that expires without being
        // touched cost the market nothing and should cost the latch nothing.
        if (latch.on() && creditFor(pattern.direction()) <= 0) {
            return;
        }

        // THE STOP IS THE EXTREME OF THE WHOLE STRUCTURE, which is not always on
        // the same bar. On a PFR the two coincide, because the signal bar is
        // what marked the new extreme; on a 1-2-3 the extreme is on the pivot,
        // one bar back. Using the signal bar for both would put the 1-2-3's stop
        // INSIDE the structure, where the pattern has not failed yet.
        double highest = Double.NEGATIVE_INFINITY;
        double lowest = Double.POSITIVE_INFINITY;

        for (int back = bar - CandlePatterns.LOOKBACK; back <= bar; back++) {
            highest = Math.max(highest, bars.highAt(back));
            lowest = Math.min(lowest, bars.lowAt(back));
        }

        side = pattern.direction();

        if (side > 0) {
            trigger = bars.highAt(bar) + TICK;
            stop = lowest;
        } else {
            trigger = bars.lowAt(bar) - TICK;
            stop = highest;
        }

        double risk = Math.abs(trigger - stop);

        target = trigger + side * reward * risk;
        livesUntil = bar + validFor;
    }

    private void forget() {
        side = 0;
        trigger = Double.NaN;
        stop = Double.NaN;
        target = Double.NaN;
        livesUntil = -1;
    }

    /**
     * Keeps the plan in step with what actually executed.
     *
     * <p>The engine hands over the fills of the bar, so there is nothing to
     * guess: an opening fill means the trigger was taken, and a cover means the
     * trade is over.
     */
    private void settle(List<Fill> fills) {
        for (Fill fill : fills) {
            String verb = fill.verb();

            if (verb == null) {
                continue;
            }

            if (verb.contains("Cover") || verb.contains("Close") || verb.contains("Reverse")) {
                count(fill.price());

                holding = false;
                entered = Double.NaN;

                forget();
            } else if (verb.startsWith("Buy") || verb.startsWith("SellShort")) {
                holding = true;
                entered = fill.price();

                spendTheCredit();
            }
        }
    }

    private int creditFor(int direction) {
        return direction > 0 ? buyCredit : sellCredit;
    }

    private void spendTheCredit() {
        if (!latch.on()) {
            return;
        }

        if (side > 0) {
            buyCredit = Math.max(0, buyCredit - 1);
        } else {
            sellCredit = Math.max(0, sellCredit - 1);
        }
    }

    /**
     * Moves the doubling along, now that a trade has ended.
     *
     * <p>Measured in POINTS and before costs, because costs are the desk's and
     * this strategy is not told them. A trade that made a point and paid six and
     * a half is a loss on the statement and is counted here as a gain — the
     * alternative is the strategy carrying a second, guessed copy of the cost
     * table, and a guessed cost is worse than a known simplification.</p>
     *
     * <p>Exactly flat moves nothing: it was neither.</p>
     */
    private void count(double left) {
        // NO CHECK OF doubling.on() HERE, on purpose: a module that is off has
        // most() == 0 by construction, so the count cannot leave zero. A second
        // guard saying the same thing is one that can be edited out of step
        // with the first, and neither would then be provable on its own.
        if (Double.isNaN(entered) || side == 0) {
            return;
        }

        double points = side * (left - entered);

        if (points < 0) {
            doublings = Math.min(doublings + 1, doubling.most());
        } else if (points > 0) {
            doublings = 0;
        }
    }

    /** @return contracts for the next entry: the lot, doubled once per loss */
    private int lotNow() {
        return lot << Math.min(doublings, Doubling.CEILING);
    }

    private void emit(Market market, Desk desk) {
        if (holding) {
            int many = Math.abs(market.buyPositionQty() - market.sellPositionQty());

            if (many <= 0) {
                return;
            }

            if (side > 0) {
                desk.sellToCoverStop(stop, stop, many);
                desk.sellToCoverLimit(target, many);
            } else {
                desk.buyToCoverStop(stop, stop, many);
                desk.buyToCoverLimit(target, many);
            }

            return;
        }

        if (side == 0 || Double.isNaN(trigger)) {
            return;
        }

        int many = lotNow();

        if (side > 0) {
            desk.buyStop(trigger, trigger, many);
        } else {
            desk.sellShortStop(trigger, trigger, many);
        }
    }

    private void draw(int bar) {
        if (side == 0) {
            return;
        }

        entryLine[bar] = trigger;
        stopLine[bar] = stop;
        targetLine[bar] = target;
    }

    @Override
    public Map<String, double[]> curves() {
        Map<String, double[]> drawn = new LinkedHashMap<>();

        drawn.put("Entrada", entryLine);
        drawn.put("Stop", stopLine);
        drawn.put("Alvo", targetLine);

        return drawn;
    }

    @Override
    public String toString() {
        return "Rompimento de " + family;
    }
}
