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

import java.util.List;

/**
 * What one run produced.
 *
 * <p>Everything here is in points, and everything here is about <b>closed</b>
 * trades.</p>
 *
 * <h2>The position left open is not counted, and is not hidden either</h2>
 *
 * <p>A run that ends with contracts still open has a trade that never finished.
 * Closing it at the last price would be inventing an exit the strategy never
 * asked for — at a price it never saw, on a bar that only exists because the
 * data stops there. So the totals cover the trades that ended, and
 * {@link #openAtTheEnd()} says what is still hanging, with
 * {@link #openResultAtTheEnd()} saying what it would be worth. A strategy that
 * zeroes at the end of the session, as all of his do, leaves both at zero.</p>
 *
 * <h2>Two numbers to read before the profit</h2>
 *
 * <p>{@link #ambiguousBars()} is how many bars had a cover stop and a cover
 * target both reachable, and so were decided by the tie-break rather than by the
 * data. {@link #costs()} is what a round trip was charged. A run where the first
 * is large, or where the second is not the measured cost of the raw series, has
 * a profit figure that is about the settings.</p>
 *
 * @param trades              the operations that ended, in order
 * @param fills               every execution, in order
 * @param ambiguousBars       bars decided by the stop-first tie-break
 * @param costs               what was charged
 * @param openAtTheEnd        signed contracts still open when the data ran out
 * @param openResultAtTheEnd  what those would be worth at the last close
 */
public record Result(List<Trade> trades, List<Fill> fills, int ambiguousBars,
                     Costs costs, int openAtTheEnd, double openResultAtTheEnd) {

    public Result {
        trades = List.copyOf(trades);
        fills = List.copyOf(fills);
    }

    /** @return points after costs, over the trades that ended */
    public double net() {
        return gross() - cost();
    }

    /** @return points before costs, over the trades that ended */
    public double gross() {
        return trades.stream().mapToDouble(Trade::gross).sum();
    }

    /** @return points paid, over the trades that ended */
    public double cost() {
        return trades.stream().mapToDouble(Trade::cost).sum();
    }

    /** @return how many operations ended */
    public int count() {
        return trades.size();
    }

    /** @return how many of them made money after costs */
    public int wins() {
        return (int) trades.stream().filter(Trade::won).count();
    }

    /**
     * Points per operation, after costs.
     *
     * <p>The number that decides whether a strategy is worth anything, because
     * it is the one the cost is subtracted from. A total can look healthy on a
     * thousand trades that each clear less than they pay.</p>
     *
     * @return net over count, or NaN when nothing closed
     */
    public double perTrade() {
        return trades.isEmpty() ? Double.NaN : net() / trades.size();
    }

    /** @return whether anything is still open — see the class note */
    public boolean endedHolding() {
        return openAtTheEnd != 0;
    }
}
