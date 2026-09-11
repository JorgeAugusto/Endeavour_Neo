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

    /**
     * The broker's own list, live.
     *
     * <p>Held rather than copied because {@link #filled()} is asked once a bar
     * by anything that keeps a lot book, and copying a run's whole history to
     * answer "what happened just now" would grow with the run.</p>
     */
    private final java.util.List<Fill> fills;

    private int bar;

    /** How many fills the strategy had already been shown. */
    private int seen;

    Market(PriceSeries series, Position position, Book book, java.util.List<Fill> fills) {
        this.series = series;
        this.position = position;
        this.book = book;
        this.fills = fills;
    }

    void at(int bar) {
        this.bar = bar;
    }

    /** Marks everything filled so far as seen, once the strategy has had its turn. */
    void settled() {
        this.seen = fills.size();
    }

    // ------------------------------------------------------------------ price

    /**
     * The executions that happened on this bar, in the order they filled.
     *
     * <p><b>Not a reading NTSL has</b>, and it is here for one reason: a
     * strategy that keeps a <b>book of lots</b> — each with its own stop and its
     * own partial — cannot tell from a net position which lot just closed. The
     * Profit's language has no answer for that because NTSL strategies do not
     * keep lot books; ours does, and the two ways out were to let the strategy
     * <b>guess</b> from the prices the bar reached, or to let it ask.</p>
     *
     * <p>Guessing means re-deriving the engine's own tie-breaks — stop before
     * target, nearest the open first — inside every strategy that scales out,
     * where they would drift from the engine the first time either changed. A
     * real desk knows its own executions. Asking is the truthful one.</p>
     *
     * <p>It cannot leak the future: the loop executes the bar's resting orders
     * <b>before</b> handing the bar to the strategy, so what is here has already
     * happened, at prices the strategy asked for at the previous close.</p>
     *
     * <p><b>Since the last turn, not "on this bar".</b> When the run executes
     * more finely than it decides, a whole tick path goes by between two
     * decisions and several lots can come out inside it. Filtering by bar index
     * would show the strategy the last tick's fill and hide the other four.</p>
     *
     * @return what has filled since the strategy last had its turn, oldest
     *         first; empty when nothing did
     */
    public java.util.List<Fill> filled() {
        return java.util.List.copyOf(fills.subList(Math.min(seen, fills.size()), fills.size()));
    }

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
