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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

/**
 * What a strategy is allowed to see, on the bar it is deciding.
 *
 * <p>The reading side of NTSL, with the same names and the same indexing:
 * {@code close()} is this bar, {@code close(1)} is the one before, exactly as
 * {@code Close[1]} means in the Profit. A strategy written against this reads
 * like the robot it came from.</p>
 *
 * <h2>The future is not merely discouraged here — it is absent</h2>
 *
 * <p>{@code Close[-1]} in NTSL is a mistake nobody catches, because it returns a
 * number. Here the index only goes backwards, and asking for a bar that has not
 * happened is an exception, not a value. Asking for one further back than the
 * series goes returns NaN, which propagates through any arithmetic and makes the
 * comparison that uses it false — so a rule that quietly needs more history than
 * it has does not fire, instead of firing on a zero.</p>
 *
 * <p>This is the single guard that matters most in the whole engine. Every
 * backtest that ever looked wonderful looked that way because something read one
 * bar ahead.</p>
 */
public final class Market {

    private final PriceSeries series;

    private final Position position;

    private final Book book;

    private int bar;

    Market(PriceSeries series, Position position, Book book) {
        this.series = series;
        this.position = position;
        this.book = book;
    }

    void at(int bar) {
        this.bar = bar;
    }

    // ------------------------------------------------------------------ price

    /** {@code Open} — this bar's open. */
    public double open() {
        return open(0);
    }

    /** {@code Open[back]} */
    public double open(int back) {
        int index = indexOf(back);

        return index < 0 ? Double.NaN : series.openAt(index);
    }

    /** {@code High} */
    public double high() {
        return high(0);
    }

    /** {@code High[back]} */
    public double high(int back) {
        int index = indexOf(back);

        return index < 0 ? Double.NaN : series.highAt(index);
    }

    /** {@code Low} */
    public double low() {
        return low(0);
    }

    /** {@code Low[back]} */
    public double low(int back) {
        int index = indexOf(back);

        return index < 0 ? Double.NaN : series.lowAt(index);
    }

    /** {@code Close} */
    public double close() {
        return close(0);
    }

    /** {@code Close[back]} */
    public double close(int back) {
        int index = indexOf(back);

        return index < 0 ? Double.NaN : series.closeAt(index);
    }

    /** @return the epoch millis of a bar's open */
    public long time(int back) {
        int index = indexOf(back);

        return index < 0 ? Long.MIN_VALUE : series.timeAt(index);
    }

    /** {@code CurrentBar} — where we are in the series. */
    public int bar() {
        return bar;
    }

    /** @return how many bars exist up to and including this one */
    public int known() {
        return bar + 1;
    }

    // --------------------------------------------------------------- position

    /** {@code HasPosition} */
    public boolean hasPosition() {
        return !position.flat();
    }

    /** {@code IsBought} */
    public boolean isBought() {
        return position.isBought();
    }

    /** {@code IsSold} */
    public boolean isSold() {
        return position.isSold();
    }

    /** {@code BuyPositionQty} — contracts long, zero when not long. */
    public int buyPositionQty() {
        return position.isBought() ? position.size() : 0;
    }

    /** {@code SellPositionQty} — contracts short, zero when not short. */
    public int sellPositionQty() {
        return position.isSold() ? position.size() : 0;
    }

    /** {@code BuyPrice} — average price of the long, NaN when not long. */
    public double buyPrice() {
        return position.isBought() ? position.average() : Double.NaN;
    }

    /** {@code SellPrice} — average price of the short, NaN when not short. */
    public double sellPrice() {
        return position.isSold() ? position.average() : Double.NaN;
    }

    /** {@code MyPrice} — average price of whatever is open, NaN while flat. */
    public double myPrice() {
        return position.average();
    }

    /**
     * {@code OpenResult} — the open position marked to this bar's close.
     *
     * @return points, zero while flat
     */
    public double openResult() {
        return position.openResult(close());
    }

    // ------------------------------------------------------------------- book

    /** {@code HasPendingOrders} */
    public boolean hasPendingOrders() {
        return book.hasPending();
    }

    /** {@code NumberOfPendingOrders} */
    public int numberOfPendingOrders() {
        return book.pendingCount();
    }

    // ----------------------------------------------------------------- inside

    /**
     * @param back how many bars to step back; zero is this one
     * @return the series index, or a negative number when it predates the series
     */
    private int indexOf(int back) {
        if (back < 0) {
            throw new IllegalArgumentException(
                    "bar " + -back + " has not happened yet; a strategy may only look backwards");
        }

        return bar - back;
    }
}
