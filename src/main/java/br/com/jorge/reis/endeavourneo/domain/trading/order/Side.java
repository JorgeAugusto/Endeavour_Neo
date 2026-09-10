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
 * Which way an order pushes the position.
 *
 * <p>Half of what an NTSL verb name says. The other half is {@link Purpose}:
 * {@code Buy} is {@code BUY} + {@code OPEN}, {@code BuyToCover} is {@code BUY} +
 * {@code COVER}, and the four verb families of the Profit are exactly this pair
 * of pairs.</p>
 */
public enum Side {

    /** Pushes the position up: opens or increases a long, or reduces a short. */
    BUY,

    /** Pushes the position down: opens or increases a short, or reduces a long. */
    SELL;

    /** @return the opposite side, which is the side that covers this one */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }

    /** @return {@code +1} for a buy, {@code -1} for a sell */
    public int signal() {
        return this == BUY ? 1 : -1;
    }
}
