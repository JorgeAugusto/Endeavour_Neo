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

import java.util.ArrayList;
import java.util.List;

/**
 * What a strategy talks to, spelled the way NTSL spells it.
 *
 * <p>A strategy does not build {@link Order} values; it calls
 * {@code sellToCoverLimit(price, qty)} the same way its NTSL original does, and
 * the desk writes down what was asked. Two things come out of that. A robot read
 * from the Profit transcribes line for line, so the reading is a translation and
 * not an interpretation. And the strategy is left with no way to say anything
 * the Profit cannot execute — which is the entire point of having a vocabulary
 * instead of a language.</p>
 *
 * <h2>The quantity may be left out, exactly once</h2>
 *
 * <p>NTSL lets every order verb omit the quantity, in which case it takes the
 * "Quantity per Order" field of the strategy's execution tab. That default lives
 * here, is fixed when the desk is built, and is resolved before the
 * {@link Order} exists — so no order downstream has a size that depends on a
 * setting it never saw.</p>
 *
 * <h2>The stop's limit price is NOT optional here</h2>
 *
 * <p>NTSL marks it optional: {@code BuyStop(Stop; Limit?; Quantity?)}. The
 * manual does not say what price the order gets when it is left out, and the
 * difference between "fills at any price once triggered" and "fills at the stop
 * or better" is a different backtest — quietly, on exactly the bars where a stop
 * matters. So both prices are required, and his robots already write both:
 * {@code BuyStop(pStop, pStop, lote)}. If the Profit's behaviour is ever
 * measured, the overload can be added then, with the measurement beside it.</p>
 */
public final class Desk {

    /** The Profit's "Quantity per Order": what an order without a size gets. */
    private final int lot;

    private final List<Instruction> instructions = new ArrayList<>();

    /**
     * @param lot the default quantity, at least one contract
     */
    public Desk(int lot) {
        if (lot < 1) {
            throw new IllegalArgumentException("a desk that trades " + lot + " contracts trades nothing");
        }

        this.lot = lot;
    }

    // ------------------------------------------------------------------ long

    /** {@code BuyAtMarket} */
    public void buyAtMarket() {
        buyAtMarket(lot);
    }

    /** {@code BuyAtMarket(Quantity)} */
    public void buyAtMarket(int quantity) {
        ask(Order.buyAtMarket(quantity));
    }

    /** {@code BuyLimit(Limit)} */
    public void buyLimit(double limit) {
        buyLimit(limit, lot);
    }

    /** {@code BuyLimit(Limit, Quantity)} */
    public void buyLimit(double limit, int quantity) {
        ask(Order.buyLimit(limit, quantity));
    }

    /** {@code BuyStop(Stop, Limit)} */
    public void buyStop(double stop, double limit) {
        buyStop(stop, limit, lot);
    }

    /** {@code BuyStop(Stop, Limit, Quantity)} */
    public void buyStop(double stop, double limit, int quantity) {
        ask(Order.buyStop(stop, limit, quantity));
    }

    /** {@code SellToCoverAtMarket} */
    public void sellToCoverAtMarket() {
        sellToCoverAtMarket(lot);
    }

    /** {@code SellToCoverAtMarket(Quantity)} */
    public void sellToCoverAtMarket(int quantity) {
        ask(Order.sellToCoverAtMarket(quantity));
    }

    /** {@code SellToCoverLimit(Limit)} */
    public void sellToCoverLimit(double limit) {
        sellToCoverLimit(limit, lot);
    }

    /** {@code SellToCoverLimit(Limit, Quantity)} */
    public void sellToCoverLimit(double limit, int quantity) {
        ask(Order.sellToCoverLimit(limit, quantity));
    }

    /** {@code SellToCoverStop(Stop, Limit)} */
    public void sellToCoverStop(double stop, double limit) {
        sellToCoverStop(stop, limit, lot);
    }

    /** {@code SellToCoverStop(Stop, Limit, Quantity)} */
    public void sellToCoverStop(double stop, double limit, int quantity) {
        ask(Order.sellToCoverStop(stop, limit, quantity));
    }

    // ----------------------------------------------------------------- short

    /** {@code SellShortAtMarket} */
    public void sellShortAtMarket() {
        sellShortAtMarket(lot);
    }

    /** {@code SellShortAtMarket(Quantity)} */
    public void sellShortAtMarket(int quantity) {
        ask(Order.sellShortAtMarket(quantity));
    }

    /** {@code SellShortLimit(Limit)} */
    public void sellShortLimit(double limit) {
        sellShortLimit(limit, lot);
    }

    /** {@code SellShortLimit(Limit, Quantity)} */
    public void sellShortLimit(double limit, int quantity) {
        ask(Order.sellShortLimit(limit, quantity));
    }

    /** {@code SellShortStop(Stop, Limit)} */
    public void sellShortStop(double stop, double limit) {
        sellShortStop(stop, limit, lot);
    }

    /** {@code SellShortStop(Stop, Limit, Quantity)} */
    public void sellShortStop(double stop, double limit, int quantity) {
        ask(Order.sellShortStop(stop, limit, quantity));
    }

    /** {@code BuyToCoverAtMarket} */
    public void buyToCoverAtMarket() {
        buyToCoverAtMarket(lot);
    }

    /** {@code BuyToCoverAtMarket(Quantity)} */
    public void buyToCoverAtMarket(int quantity) {
        ask(Order.buyToCoverAtMarket(quantity));
    }

    /** {@code BuyToCoverLimit(Limit)} */
    public void buyToCoverLimit(double limit) {
        buyToCoverLimit(limit, lot);
    }

    /** {@code BuyToCoverLimit(Limit, Quantity)} */
    public void buyToCoverLimit(double limit, int quantity) {
        ask(Order.buyToCoverLimit(limit, quantity));
    }

    /** {@code BuyToCoverStop(Stop, Limit)} */
    public void buyToCoverStop(double stop, double limit) {
        buyToCoverStop(stop, limit, lot);
    }

    /** {@code BuyToCoverStop(Stop, Limit, Quantity)} */
    public void buyToCoverStop(double stop, double limit, int quantity) {
        ask(Order.buyToCoverStop(stop, limit, quantity));
    }

    // -------------------------------------------------------------- position

    /** {@code ClosePosition} — flat at market, whatever the position is. */
    public void closePosition() {
        ask(Command.CLOSE_POSITION);
    }

    /** {@code ReversePosition} — flat, then the same size the other way. */
    public void reversePosition() {
        ask(Command.REVERSE_POSITION);
    }

    /** {@code CancelPendingOrders} — clear the resting orders, keep the position. */
    public void cancelPendingOrders() {
        ask(Command.CANCEL_PENDING_ORDERS);
    }

    // --------------------------------------------------------------- reading

    /**
     * What was asked for, in the order it was asked.
     *
     * <p>The order is load-bearing, not incidental — see {@link Instruction}.</p>
     */
    public List<Instruction> instructions() {
        return List.copyOf(instructions);
    }

    /** @return the default quantity an order without a size gets */
    public int lot() {
        return lot;
    }

    /** Forgets everything asked so far. The engine calls this between bars. */
    public void clear() {
        instructions.clear();
    }

    private void ask(Instruction instruction) {
        instructions.add(instruction);
    }
}
