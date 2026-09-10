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
package br.com.jorge.reis.endeavourneo.domain.trading;

import br.com.jorge.reis.endeavourneo.domain.trading.order.Desk;

/**
 * A robot: read the bar, ask the desk for something.
 *
 * <p>The same shape as an NTSL {@code begin..end} block, and deliberately so —
 * it runs once per closed bar, it may read only backwards through
 * {@link Market}, and everything it wants from the market it asks
 * {@link Desk} for by the Profit's own verb names.</p>
 *
 * <h2>It is called at the close, and what it asks for happens after</h2>
 *
 * <p>Nothing a strategy asks for can execute on the bar it is looking at. The
 * orders rest, and the earliest anything happens is the next bar's open. That is
 * NTSL's default execution mode, it is what the Profit's own backtest does, and
 * it is the difference between a measurement and a wish: deciding and executing
 * on the same close uses a price that did not exist yet, and it is the most
 * common way a backtest invents an edge.</p>
 *
 * <h2>Re-emit everything, every bar</h2>
 *
 * <p>The book is rebuilt from what the strategy asks for at each close, so an
 * order that is not asked for again is cancelled. This is not our convention —
 * it is the Profit's, and it is why his robots keep the stop level in a
 * {@code var} that persists and re-send {@code *ToCoverStop} unconditionally on
 * every candle. A strategy that places its stop once and assumes it stays will
 * have no stop from the following bar onward.</p>
 */
@FunctionalInterface
public interface Strategy {

    /**
     * Called once, before the first bar of a run.
     *
     * <p>NTSL has the same thing and calls it {@code Initialization}. It exists
     * here for the same reason it exists there: a strategy that carries an
     * average carries state, and state that survives a run makes the second run
     * over the same series produce different trades. Anything a strategy
     * remembers is reset here.</p>
     *
     * <p>A strategy that remembers nothing — most of them, and every lambda —
     * ignores this.</p>
     */
    default void start() {
    }

    /**
     * Decides, on the close of one bar.
     *
     * @param market what may be read; only backwards
     * @param desk   what may be asked; already empty when this is called
     */
    void onBar(Market market, Desk desk);
}
