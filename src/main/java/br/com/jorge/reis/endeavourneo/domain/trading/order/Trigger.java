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
 * What has to happen for an order to execute.
 *
 * <p>The third axis of an NTSL verb name, and the one that decides whether the
 * order executes now or waits. {@code BuyAtMarket} takes whatever price is
 * there; {@code BuyLimit} waits for a price at least as good as the one asked;
 * {@code BuyStop} waits for the price to reach a level and only then behaves
 * like a limit.</p>
 *
 * <h2>A limit is on the near side, a stop on the far side</h2>
 *
 * <p>Both name a price and both wait, and that similarity hides the difference
 * that matters. Relative to where the price is now:</p>
 *
 * <ul>
 *   <li>A <b>buy limit sits below</b> the price and fills if the price falls to
 *       it. It is how his robots fish — {@code FolgaPescaria} posts the order
 *       ahead of the band and waits for the market to come.</li>
 *   <li>A <b>buy stop sits above</b> the price and fills if the price rises to
 *       it. It is a breakout entry, or the exit of a short.</li>
 * </ul>
 *
 * <p>Sells mirror both. A limit order therefore always fills at a price at least
 * as good as asked, and a stop order at a price at least as bad — which is why
 * the stop carries a second price, the limit, bounding how bad.</p>
 */
public enum Trigger {

    /** Executes on the next price there is, whatever it is. */
    MARKET,

    /**
     * Rests until the price is as good as the limit, or better.
     *
     * <p>Fills at the limit or better; never worse.</p>
     */
    LIMIT,

    /**
     * Rests until the price reaches the stop, and then behaves like a limit.
     *
     * <p>NTSL's {@code Stop} functions take both prices: {@code Stop} is the
     * trigger and {@code Limit} is "up to what price the order may execute". A
     * stop whose limit equals its stop is the common case in his robots, and is
     * what {@code BuyStop(pStop, pStop, lote)} in the manual's own example
     * writes.</p>
     */
    STOP;

    /** @return whether the order waits for a price instead of taking any */
    public boolean rests() {
        return this != MARKET;
    }
}
