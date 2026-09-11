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

import br.com.jorge.reis.endeavourneo.domain.market.CandlePattern;
import br.com.jorge.reis.endeavourneo.domain.market.CandlePatterns;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Market;
import br.com.jorge.reis.endeavourneo.domain.trading.Plotted;
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
public final class PatternBreakout implements Strategy, Plotted {

    /** One tick of the mini index. The trigger sits BEYOND the extreme. */
    public static final double TICK = 5;

    /** How many bars the resting entry lives for, unless asked otherwise. */
    public static final int VALID_FOR = 1;

    public static final double REWARD = 1.5;

    private final ZoneId zone;

    private final CandlePattern.Family family;

    private final double reward;

    private final int validFor;

    private final int lot;

    // ---------------------------------------------------------------- the run

    private PriceSeries bars;

    private int size;

    /** Which session each bar belongs to; a new number means the day turned. */
    private int[] session;

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

    /**
     * @param zone     the exchange's zone, which decides where a session begins
     * @param family   which shape to trade: PFR, inside or 1-2-3
     * @param reward   the target, as a multiple of the entry-to-stop distance
     * @param validFor how many bars the resting entry lives for
     * @param lot      contracts per trade
     */
    public PatternBreakout(ZoneId zone, CandlePattern.Family family, double reward,
                           int validFor, int lot) {

        this.zone = zone == null ? Timeframe.defaultZone() : zone;
        this.family = family == null ? CandlePattern.Family.PFR : family;
        this.reward = reward > 0 ? reward : REWARD;
        this.validFor = Math.max(1, validFor);
        this.lot = Math.max(1, lot);
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

            if (market.hasPosition()) {
                desk.closePosition();
            }

            draw(bar);

            return;
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
                holding = false;
                forget();
            } else if (verb.startsWith("Buy") || verb.startsWith("SellShort")) {
                holding = true;
            }
        }
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

        if (side > 0) {
            desk.buyStop(trigger, trigger, lot);
        } else {
            desk.sellShortStop(trigger, trigger, lot);
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
