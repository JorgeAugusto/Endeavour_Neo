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
 */
package br.com.jorge.reis.endeavourneo.domain.trading.strategy;

import br.com.jorge.reis.endeavourneo.domain.indicator.Stochastic;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;

/**
 * Permission to trade, spent by entries and refilled by a stretch.
 *
 * <p>The stochastic of eight on ONE MINUTE reaching its level <b>arms</b> a
 * credit of a few entries, which the next signals spend. It lived inside
 * {@code PatternBreakout} and came out the moment a second strategy wanted the
 * same rule: two copies of a rule drift, and the drift here is invisible — one
 * strategy would say the market was stretched and the other would say it was
 * not, each looking right on its own.
 *
 * <h2>Arming is on the way IN; disarming is by level</h2>
 *
 * <p>Price can sit under twenty for forty minutes. That is one event, not
 * forty: counting each of them would refill the credit every bar and the cap on
 * entries would say nothing. Leaving the zone and coming back is a second
 * event.
 *
 * <p>Coming back to the middle throws the credit away, and there the check is by
 * level rather than by crossing. The asymmetry is deliberate: discarding a
 * credit that is already empty changes nothing, so no run exists in which "it is
 * past the middle" and "it has just gone past the middle" disagree. Arming does
 * not have that luxury.
 *
 * <h2>The two sides are separate</h2>
 *
 * <p>Twenty arms buys and eighty arms sells — oversold is what makes a refused
 * low worth buying. A buy spends buy credit and leaves the sell credit alone.
 *
 * <p>Not thread-safe, and not meant to be: one of these belongs to one run.
 */
public final class StochasticLatch {

    /**
     * What the latch is set to.
     *
     * @param on         whether the filter applies at all
     * @param period     the stochastic's range, in bars of one minute
     * @param average    its smoothing
     * @param buyLevel   at or below this, buys are armed
     * @param sellLevel  at or above this, sells are armed
     * @param resetLevel back at this, the credit is thrown away
     * @param entries    how many entries one arming pays for
     */
    public record Settings(boolean on, int period, int average,
                           double buyLevel, double sellLevel, double resetLevel,
                           int entries) {

        /** The middle of the range, where a stretch is over. */
        public static final double RESET = 50;

        public Settings {
            period = Math.max(1, period);
            average = Math.max(1, average);
            entries = Math.max(1, entries);
        }

        /** No filter: every signal is traded. */
        public static Settings off() {
            return new Settings(false, Stochastic.PERIOD, Stochastic.AVERAGE,
                    20, 80, RESET, 2);
        }

        /** Eight and three on one minute, twenty and eighty, back at fifty. */
        public static Settings standard() {
            return new Settings(true, Stochastic.PERIOD, Stochastic.AVERAGE,
                    20, 80, RESET, 2);
        }
    }

    private final Settings settings;

    /**
     * What the stochastic did to each side on each decision bar.
     *
     * <p>{@code +1} armed, {@code -1} disarmed, {@code 0} neither. One number
     * rather than two flags because a decision bar can hold both events — a
     * five-minute bar is five minutes of stochastic — and then the LAST one is
     * what the strategy finds when the bar closes. Two flags would have to
     * record which came first, which is the same number written twice.</p>
     */
    private int[] buyEvent = new int[0];

    private int[] sellEvent = new int[0];

    private int buyCredit;

    private int sellCredit;

    public StochasticLatch(Settings settings) {
        this.settings = settings == null ? Settings.off() : settings;
    }

    public boolean on() {
        return settings.on();
    }

    public Settings settings() {
        return settings;
    }

    /**
     * Works out, once, what the stochastic does to each decision bar.
     *
     * <p>Read on ONE MINUTE and not on the decision bars, which is the point of
     * asking for it that way: the chart may be on five minutes or on renko,
     * where there is no minute in the series at all, and the reading is supposed
     * to be the same either way.</p>
     *
     * <p><b>Every minute used here is inside the decision bar it is filed
     * under</b>, so by the time that bar closes and the strategy is asked, all
     * of them have happened. The look-ahead this could have had — filing a
     * minute under the bar it precedes — is the multi-timeframe trap running the
     * other way.</p>
     *
     * @param decided the bars the strategy decides on
     * @param source  the bars as stored, which the minutes come from
     */
    public void start(PriceSeries decided, PriceSeries source) {
        int size = decided == null ? 0 : decided.size();

        buyEvent = new int[size];
        sellEvent = new int[size];
        buyCredit = 0;
        sellCredit = 0;

        if (!settings.on() || size == 0) {
            return;
        }

        PriceSeries minutes = Timeframe.ONE_MINUTE.apply(source == null ? decided : source);
        double[] slow = new Stochastic(settings.period(), settings.average())
                .over(minutes).slow();

        int at = 0;
        boolean wasLow = false;
        boolean wasHigh = false;

        for (int minute = 0; minute < minutes.size(); minute++) {
            while (at + 1 < size && minutes.timeAt(minute) >= decided.timeAt(at + 1)) {
                at++;
            }

            double now = slow[minute];

            if (Double.isNaN(now)) {
                continue;
            }

            boolean low = now <= settings.buyLevel();
            boolean high = now >= settings.sellLevel();

            if (low && !wasLow) {
                buyEvent[at] = 1;
            } else if (now >= settings.resetLevel()) {
                buyEvent[at] = -1;
            }

            if (high && !wasHigh) {
                sellEvent[at] = 1;
            } else if (now <= settings.resetLevel()) {
                sellEvent[at] = -1;
            }

            wasLow = low;
            wasHigh = high;
        }
    }

    /**
     * Applies what this bar's minutes did, before anything is decided on it.
     *
     * @param bar the decision bar that has just closed
     * @return whether either side ARMED here — a new stretch, which a caller
     *         that rations something of its own per stretch needs to know about
     */
    public boolean at(int bar) {
        if (bar < 0 || bar >= buyEvent.length) {
            return false;
        }

        if (buyEvent[bar] > 0) {
            buyCredit = settings.entries();
        } else if (buyEvent[bar] < 0) {
            buyCredit = 0;
        }

        if (sellEvent[bar] > 0) {
            sellCredit = settings.entries();
        } else if (sellEvent[bar] < 0) {
            sellCredit = 0;
        }

        return buyEvent[bar] > 0 || sellEvent[bar] > 0;
    }

    /**
     * @param side {@code +1} to buy, {@code -1} to sell
     * @return how many entries that side may still make
     *
     * <p>A latch that is off answers with the number of entries it would have
     * paid for, not zero: "no filter" has to read as permission everywhere, and
     * a caller that asks this without first asking {@link #on()} should get the
     * harmless answer rather than the silent refusal.</p>
     */
    public int credit(int side) {
        if (!settings.on()) {
            return settings.entries();
        }

        return side > 0 ? buyCredit : sellCredit;
    }

    /**
     * Spends one entry's worth on that side.
     *
     * <p>By the ENTRY and not by the plan: a trigger that expires without being
     * touched cost the market nothing and should cost the latch nothing.</p>
     */
    public void spend(int side) {
        if (!settings.on()) {
            return;
        }

        if (side > 0) {
            buyCredit = Math.max(0, buyCredit - 1);
        } else {
            sellCredit = Math.max(0, sellCredit - 1);
        }
    }

    /** Throws away both credits — what the end of a session does. */
    public void clear() {
        buyCredit = 0;
        sellCredit = 0;
    }
}
