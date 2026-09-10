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

import br.com.jorge.reis.endeavourneo.domain.trading.order.Order;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Side;

/**
 * Whether an order executes inside a bar, and at what price.
 *
 * <p>The whole of the fidelity question lives in these forty lines. We have
 * one-minute bars and no tape, so what happened <i>within</i> the minute is four
 * numbers and an assumption — and the assumption is where a backtest invents
 * money if nobody writes it down.</p>
 *
 * <h2>The gap is the part people get wrong</h2>
 *
 * <p>A buy limit at 138.500 on a bar that opens at 138.400 does not fill at
 * 138.500. It fills at the open, because the market was already better than the
 * order when the order was live. Filling it at the limit would book 100 points
 * that never existed, on every gap, in the strategy's favour — which is exactly
 * the shape of a bug that makes a backtest look good.</p>
 *
 * <p>A stop is the mirror and hurts instead of helps: a sell stop at 138.500 on
 * a bar that opens at 138.300 executes at the open, 200 points worse than asked.
 * That is why NTSL's stop carries a second price. If the fill would be worse
 * than the limit, <b>the order does not fill at all</b> — the price ran past it
 * and left it behind, which is what really happens.</p>
 *
 * <h2>What is not modelled, and cannot be</h2>
 *
 * <p>The order of the high and the low inside the bar is unknowable from OHLC.
 * When both a stop and a target are reachable in the same minute, this class
 * says both are reachable and refuses to pick; {@link Broker} picks, and it
 * picks the stop — not because that is true, but because it is the conservative
 * side. It also counts how often it had to, and the count belongs in the report:
 * on the previous project it was 0,1% of trades, and if it ever climbs the
 * result is a fact about that tie-break rather than about the strategy.</p>
 */
final class Matching {

    private Matching() {
    }

    /**
     * Where this order would execute inside the bar.
     *
     * @param order the resting order
     * @param open  the bar's open
     * @param high  the bar's high
     * @param low   the bar's low
     * @return the execution price, or {@link Double#NaN} if the bar never
     *         reaches it
     */
    static double priceIn(Order order, double open, double high, double low) {
        boolean buying = order.side() == Side.BUY;

        return switch (order.trigger()) {
            case MARKET -> open;

            // A limit fills at the limit, or at the open when the open is
            // already better than the limit.
            case LIMIT -> buying
                    ? (low <= order.limit() ? Math.min(open, order.limit()) : Double.NaN)
                    : (high >= order.limit() ? Math.max(open, order.limit()) : Double.NaN);

            case STOP -> stopPrice(order, buying, open, high, low);
        };
    }

    private static double stopPrice(Order order, boolean buying, double open, double high, double low) {
        double triggered = buying
                ? (high >= order.stop() ? Math.max(open, order.stop()) : Double.NaN)
                : (low <= order.stop() ? Math.min(open, order.stop()) : Double.NaN);

        if (Double.isNaN(triggered)) {
            return Double.NaN;
        }

        // Triggered, but the price ran past the limit before the order could be
        // filled. NTSL's second price says how far is too far.
        boolean worseThanTheLimit = buying
                ? triggered > order.limit()
                : triggered < order.limit();

        return worseThanTheLimit ? Double.NaN : triggered;
    }
}
