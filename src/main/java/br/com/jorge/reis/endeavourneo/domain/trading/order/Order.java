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
package br.com.jorge.reis.endeavourneo.domain.trading.order;

/**
 * One order, as the Profit's automation would understand it.
 *
 * <p>The twelve order verbs of NTSL are not twelve ideas. They are three
 * questions asked at once, and naming them separately is what makes the
 * translation to another platform mechanical instead of a case analysis:</p>
 *
 * <pre>
 *     {Buy, SellShort, BuyToCover, SellToCover} x {AtMarket, Limit, Stop}
 *          =  {BUY, SELL}  x  {OPEN, COVER}  x  {MARKET, LIMIT, STOP}
 * </pre>
 *
 * <p>So {@code SellToCoverLimit(BuyPrice + AlvoNucleo, qty)} — a line that
 * appears in his own robots — is {@code SELL} + {@code COVER} + {@code LIMIT}.
 * The static factories below carry the NTSL names so that a robot read from the
 * Profit can be transcribed line by line, and so that anyone comparing the two
 * files is comparing the same words.</p>
 *
 * <h2>The quantity is always here, and always real</h2>
 *
 * <p>NTSL lets the quantity be omitted, in which case the order takes the
 * "Quantity per Order" field of the strategy's execution tab. That default is a
 * property of the desk, not of the order, so {@link Desk} resolves it and every
 * {@code Order} that exists carries a concrete quantity of at least one. An
 * order whose size depends on a setting nobody passed is the kind of thing that
 * reads fine and backtests a different strategy than it trades.</p>
 *
 * <h2>Prices that do not apply are NaN, not zero</h2>
 *
 * <p>A market order has no limit and no stop. Zero would be a price — a very
 * good one to buy at — and the first arithmetic that touches it produces a
 * number instead of a complaint. {@link Double#NaN} propagates and compares
 * false, so a rule that reads a price it should not have read fails loudly.</p>
 *
 * @param side     which way it pushes the position
 * @param purpose  whether it may open exposure or only give it back
 * @param trigger  what has to happen for it to execute
 * @param stop     the trigger price of a stop order; NaN otherwise
 * @param limit    the worst acceptable price of a limit or stop order; NaN for
 *                 a market order
 * @param quantity contracts, always at least one
 */
public record Order(Side side, Purpose purpose, Trigger trigger,
                    double stop, double limit, int quantity) implements Instruction {

    public Order {
        if (side == null || purpose == null || trigger == null) {
            throw new IllegalArgumentException("an order needs a side, a purpose and a trigger");
        }

        if (quantity < 1) {
            throw new IllegalArgumentException("an order of " + quantity + " contracts is not an order");
        }

        if (trigger == Trigger.MARKET && (!Double.isNaN(stop) || !Double.isNaN(limit))) {
            throw new IllegalArgumentException("a market order takes whatever price is there; it has no price of its own");
        }

        if (trigger == Trigger.LIMIT && (!Double.isNaN(stop) || !Double.isFinite(limit))) {
            throw new IllegalArgumentException("a limit order needs a limit price and no stop");
        }

        if (trigger == Trigger.STOP && (!Double.isFinite(stop) || !Double.isFinite(limit))) {
            throw new IllegalArgumentException("a stop order needs both prices: the trigger and how far it may fill");
        }
    }

    // ---------------------------------------------------------------- opening

    /** {@code BuyAtMarket} — open or increase a long, at whatever price is there. */
    public static Order buyAtMarket(int quantity) {
        return new Order(Side.BUY, Purpose.OPEN, Trigger.MARKET, Double.NaN, Double.NaN, quantity);
    }

    /** {@code BuyLimit} — rest below the price, waiting for the market to come down. */
    public static Order buyLimit(double limit, int quantity) {
        return new Order(Side.BUY, Purpose.OPEN, Trigger.LIMIT, Double.NaN, limit, quantity);
    }

    /** {@code BuyStop} — rest above the price, waiting for a breakout. */
    public static Order buyStop(double stop, double limit, int quantity) {
        return new Order(Side.BUY, Purpose.OPEN, Trigger.STOP, stop, limit, quantity);
    }

    /** {@code SellShortAtMarket} — open or increase a short, at whatever price is there. */
    public static Order sellShortAtMarket(int quantity) {
        return new Order(Side.SELL, Purpose.OPEN, Trigger.MARKET, Double.NaN, Double.NaN, quantity);
    }

    /** {@code SellShortLimit} — rest above the price, waiting for the market to come up. */
    public static Order sellShortLimit(double limit, int quantity) {
        return new Order(Side.SELL, Purpose.OPEN, Trigger.LIMIT, Double.NaN, limit, quantity);
    }

    /** {@code SellShortStop} — rest below the price, waiting for a breakdown. */
    public static Order sellShortStop(double stop, double limit, int quantity) {
        return new Order(Side.SELL, Purpose.OPEN, Trigger.STOP, stop, limit, quantity);
    }

    // --------------------------------------------------------------- covering

    /** {@code BuyToCoverAtMarket} — give back a short now. Ignored if not short. */
    public static Order buyToCoverAtMarket(int quantity) {
        return new Order(Side.BUY, Purpose.COVER, Trigger.MARKET, Double.NaN, Double.NaN, quantity);
    }

    /** {@code BuyToCoverLimit} — a short's target: rest below, take the profit there. */
    public static Order buyToCoverLimit(double limit, int quantity) {
        return new Order(Side.BUY, Purpose.COVER, Trigger.LIMIT, Double.NaN, limit, quantity);
    }

    /** {@code BuyToCoverStop} — a short's stop loss: rest above, give up there. */
    public static Order buyToCoverStop(double stop, double limit, int quantity) {
        return new Order(Side.BUY, Purpose.COVER, Trigger.STOP, stop, limit, quantity);
    }

    /** {@code SellToCoverAtMarket} — give back a long now. Ignored if not long. */
    public static Order sellToCoverAtMarket(int quantity) {
        return new Order(Side.SELL, Purpose.COVER, Trigger.MARKET, Double.NaN, Double.NaN, quantity);
    }

    /** {@code SellToCoverLimit} — a long's target: rest above, take the profit there. */
    public static Order sellToCoverLimit(double limit, int quantity) {
        return new Order(Side.SELL, Purpose.COVER, Trigger.LIMIT, Double.NaN, limit, quantity);
    }

    /** {@code SellToCoverStop} — a long's stop loss: rest below, give up there. */
    public static Order sellToCoverStop(double stop, double limit, int quantity) {
        return new Order(Side.SELL, Purpose.COVER, Trigger.STOP, stop, limit, quantity);
    }

    // ----------------------------------------------------------------- asking

    /** @return whether this order may only reduce the position */
    public boolean covers() {
        return purpose == Purpose.COVER;
    }

    /** @return whether this order waits for a price rather than taking any */
    public boolean rests() {
        return trigger.rests();
    }

    /** @return the same order for a different number of contracts */
    public Order of(int contracts) {
        return new Order(side, purpose, trigger, stop, limit, contracts);
    }

    /**
     * The NTSL name this order would be written with.
     *
     * <p>Not decoration: it is what a translator emits, and what a message says
     * when an order is refused. Keeping it beside the three axes is what stops
     * the two spellings of the same idea from drifting apart.</p>
     *
     * @return one of the twelve verb names, e.g. {@code "SellToCoverLimit"}
     */
    @Override
    public String verb() {
        String family = purpose == Purpose.OPEN
                ? (side == Side.BUY ? "Buy" : "SellShort")
                : (side == Side.BUY ? "BuyToCover" : "SellToCover");

        return family + switch (trigger) {
            case MARKET -> "AtMarket";
            case LIMIT -> "Limit";
            case STOP -> "Stop";
        };
    }
}
