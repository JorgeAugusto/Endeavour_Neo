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
 * The three things a strategy can ask for that are not an order.
 *
 * <p>They act on the position or on the book as a whole, so none of them takes
 * a price or a quantity — which is exactly why they cannot be {@link Order}s
 * and why they are worth having: {@code ClosePosition} says "all of it,
 * whatever it is now", and a strategy that had to name the quantity would have
 * to have been right about it.</p>
 */
public enum Command implements Instruction {

    /**
     * {@code ClosePosition} — go flat at market, whatever the position is.
     *
     * <p>The end-of-day zeroing in every one of his robots, and the last line
     * of every circuit breaker. Does nothing when already flat.</p>
     */
    CLOSE_POSITION,

    /**
     * {@code ReversePosition} — go flat and open the same size the other way.
     *
     * <p>In the language, but used with care: the note from the port to the
     * Profit records that an inversion is done as <b>two orders</b>, and
     * {@code RoboRenko11} keeps a backup named for the experiment of trusting
     * this one. It is here because NTSL has it; a strategy that wants to be
     * sure writes {@link #CLOSE_POSITION} and then an opening order.</p>
     */
    REVERSE_POSITION,

    /**
     * {@code CancelPendingOrders} — clear the resting orders, keep the position.
     *
     * <p>What a strategy calls when the reason it was fishing stopped being
     * true. The position is untouched.</p>
     */
    CANCEL_PENDING_ORDERS;

    @Override
    public String verb() {
        return switch (this) {
            case CLOSE_POSITION -> "ClosePosition";
            case REVERSE_POSITION -> "ReversePosition";
            case CANCEL_PENDING_ORDERS -> "CancelPendingOrders";
        };
    }
}
