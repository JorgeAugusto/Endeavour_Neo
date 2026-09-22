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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.Market;
import br.com.jorge.reis.endeavourneo.domain.trading.Plotted;
import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Desk;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trades what an outside model decided, one bar at a time.
 *
 * <p>The model — TypeSafe's Jev, in the case this was built for — is asked, off
 * line and once, "at this moment, buy or sell or stay out". Its answers are
 * frozen into a file and {@link JevDecisions} reads them back. This class does
 * nothing but obey them, with a stop and a target of its own.
 *
 * <h2>Nothing here talks to a network, and that is deliberate</h2>
 *
 * <p>See {@link JevDecisions} for the reasoning. In short: a backtest that
 * calls a probabilistic model answers differently every time it runs, and a
 * number that changes when nothing changed is not a measurement. The decisions
 * are an INPUT to the run, like the price series is.
 *
 * <h2>The model picks the side and nothing else</h2>
 *
 * <p>It is asked one thing. Where the stop goes, how far the target is, how
 * many contracts — none of that is its business here, and all of it is a
 * parameter. That separation is what makes the result readable: if this loses,
 * the question "was it the direction or the geometry" has an answer, because
 * the geometry was fixed by hand and can be varied on its own.
 *
 * <h2>The threshold is where the honesty lives</h2>
 *
 * <p>{@link #leastConfidence()} refuses a decision the model was not sure of.
 * Measured on one hand-made opportunity, Jev answered a nine-way choice with
 * confidence 0,23 against the 0,11 of pure chance — barely discriminating. If
 * that holds across a real run, almost every decision should be refused, and a
 * threshold of zero would be trading noise with conviction.
 *
 * <p>So the default is not zero. But note what the threshold is NOT: it is not
 * something to tune until the curve looks good. Raising it until the result
 * turns positive is selecting on the answer, and this project has measured what
 * that costs. Pick it, freeze it, then measure.
 *
 * <h2>What a run of this can and cannot conclude</h2>
 *
 * <p>Jev is pretrained on the world, and the world includes this market's
 * history. A positive result over 2020 to 2024 is therefore <b>not</b> evidence
 * of an edge, because none of those bars are out of sample for it in any way
 * that can be checked. What a historical run CAN answer is narrower and still
 * worth having: does the model discriminate at all — do its confident calls
 * differ from its unconfident ones — and is its stated probability calibrated.
 * Those questions do not need the data to be unseen.
 */
public final class JevAdvice implements Strategy, Plotted {

    /** Below this the model's own choice is treated as no choice. */
    public static final double LEAST_CONFIDENCE = 0.30;

    public static final double TARGET = 300;

    public static final double STOP = 200;

    /** How far past its trigger a protective stop may still fill. */
    public static final double SLIP = 200;

    private final ZoneId zone;

    private final JevDecisions decisions;

    private final double leastConfidence;

    private final double targetPoints;

    private final double stopPoints;

    private final double slip;

    private final int lot;

    private PriceSeries bars = PriceSeries.empty();

    private int size;

    private LocalDate today;

    /** The open position's own levels, measured from the price it actually got. */
    private double target = Double.NaN;

    private double stop = Double.NaN;

    private double[] sideLine;

    private double[] upLine;

    public JevAdvice() {
        this(null, JevDecisions.empty(), LEAST_CONFIDENCE, TARGET, STOP, SLIP, 1);
    }

    /**
     * @param zone            the exchange's zone, which decides where a session begins
     * @param decisions       what the model already answered
     * @param leastConfidence below which a decision is ignored
     * @param target          points from the fill to the target
     * @param stop            points from the fill to the stop
     * @param slip            how far past its trigger the stop may still fill
     * @param lot             contracts per entry
     */
    public JevAdvice(ZoneId zone, JevDecisions decisions, double leastConfidence,
                     double target, double stop, double slip, int lot) {

        this.zone = zone == null ? Timeframe.defaultZone() : zone;
        this.decisions = decisions == null ? JevDecisions.empty() : decisions;
        this.leastConfidence = Math.max(0, leastConfidence);
        this.targetPoints = Math.max(1, target);
        this.stopPoints = Math.max(1, stop);
        this.slip = Math.max(0, slip);
        this.lot = Math.max(1, lot);
    }

    @Override
    public void start(PriceSeries series) {
        bars = series == null ? PriceSeries.empty() : series;
        size = bars.size();

        sideLine = blank(size);
        upLine = blank(size);

        today = null;
        target = Double.NaN;
        stop = Double.NaN;
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
        LocalDate now = Instant.ofEpochMilli(bars.timeAt(bar)).atZone(zone).toLocalDate();

        if (!now.equals(today)) {
            today = now;
        }

        JevDecisions.Decision said = decisions.at(bars.timeAt(bar));

        sideLine[bar] = said.side();
        upLine[bar] = said.up();

        int held = market.buyPositionQty() + market.sellPositionQty();
        int have = market.isBought() ? 1 : market.isSold() ? -1 : 0;

        // FLAT MEANS THE LEVELS ARE GONE. They used to be cleared only at the
        // session's end and on a reversal, and NOT when the position simply
        // left at its stop or its target -- so the next entry inherited the
        // previous trade's target and stop and rested orders at prices that
        // had nothing to do with where it had just bought. It cost nothing
        // visible: the trades still opened, still closed, and the report
        // looked ordinary.
        if (have == 0) {
            target = Double.NaN;
            stop = Double.NaN;
        }

        if (closing(bar)) {
            // NOTHING CROSSES THE NIGHT. The order goes out one bar early
            // because ClosePosition fills at the next open, and sent on the
            // last bar of the day that open is tomorrow's gap.
            if (held > 0) {
                desk.closePosition();
            }

            target = Double.NaN;
            stop = Double.NaN;

            return;
        }

        int wanted = said.confidence() >= leastConfidence ? said.side() : 0;

        if (have != 0 && wanted != 0 && wanted != have) {
            // THE REVERSAL, IN TWO ORDERS: close, then open. Both are market
            // orders and both execute at the next open, in the order they were
            // asked -- so the report carries two trades and not one fill that
            // crosses zero.
            desk.closePosition();

            if (wanted > 0) {
                desk.buyAtMarket(lot);
            } else {
                desk.sellShortAtMarket(lot);
            }

            target = Double.NaN;
            stop = Double.NaN;

            return;
        }

        if (have != 0) {
            aim(market, desk, have, held);

            return;
        }

        if (wanted > 0) {
            desk.buyAtMarket(lot);
        } else if (wanted < 0) {
            desk.sellShortAtMarket(lot);
        }
    }

    /**
     * Rests the stop and the target, measured from the FILL.
     *
     * <p>From the fill and not from the close that decided: a market order
     * executes at the next open, and on a gap those are different prices. A
     * risk measured from the close is a risk the trade never had.</p>
     */
    private void aim(Market market, Desk desk, int have, int held) {
        if (Double.isNaN(target)) {
            double entry = market.myPrice();

            if (Double.isNaN(entry)) {
                return;
            }

            target = entry + have * targetPoints;
            stop = entry - have * stopPoints;
        }

        // RE-SENT EVERY BAR, because the book is rebuilt from what the strategy
        // asks at each close. An order placed once and assumed to stay is no
        // order from the following bar onward.
        if (have > 0) {
            desk.sellToCoverStop(stop, stop - slip, held);
            desk.sellToCoverLimit(target, held);
        } else {
            desk.buyToCoverStop(stop, stop + slip, held);
            desk.buyToCoverLimit(target, held);
        }
    }

    /** @return whether this bar is the last of its session */
    private boolean closing(int bar) {
        if (bar + 2 >= size) {
            return true;
        }

        return !Instant.ofEpochMilli(bars.timeAt(bar + 2)).atZone(zone)
                .toLocalDate().equals(today);
    }

    public double leastConfidence() {
        return leastConfidence;
    }

    public double targetPoints() {
        return targetPoints;
    }

    public double stopPoints() {
        return stopPoints;
    }

    /** @return how many of this series' bars the decisions file covers */
    public int matchedBars() {
        long[] times = new long[size];

        for (int bar = 0; bar < size; bar++) {
            times[bar] = bars.timeAt(bar);
        }

        return decisions.matched(times);
    }

    @Override
    public Map<String, double[]> curves() {
        Map<String, double[]> drawn = new LinkedHashMap<>();

        drawn.put("Lado", sideLine);
        drawn.put("P(alta)", upLine);

        return drawn;
    }

    @Override
    public String toString() {
        return "Jev";
    }
}
