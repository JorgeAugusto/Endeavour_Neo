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

import br.com.jorge.reis.endeavourneo.domain.trading.order.Command;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Instruction;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Order;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Side;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Trigger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns resting orders into fills, and fills into trades.
 *
 * <p>Everything the Profit does that a backtest can see is in this class, and
 * the rules are the manual's, quoted where they are surprising.</p>
 *
 * <h2>What happens inside one bar</h2>
 *
 * <ol>
 *   <li><b>At the open</b>, in the order the strategy asked: market orders,
 *       {@code ClosePosition} and {@code ReversePosition}. A decision made at
 *       the last close is executed here — never at the close that made it.</li>
 *   <li><b>Then the resting limit and stop orders</b>, nearest to the open
 *       first. Price does not really move away from the open monotonically, but
 *       four numbers a minute cannot say more than that, and "whatever the price
 *       reached first" is the least invented ordering available.</li>
 * </ol>
 *
 * <h2>Cover orders are one OCO, so at most one fills per bar</h2>
 *
 * <p>The manual: cover orders "are always sent as OCO orders, so you do not need
 * to worry about managing and cancelling eventual cover orders that could remain
 * open after the execution of only one of the exit legs". One leg fills, the
 * rest die — and the strategy re-emits what it still wants at the next close.
 * That is how a partial exit works: the first target fills, the OCO clears, and
 * next bar the code asks again for the targets that fit the smaller position.</p>
 *
 * <h2>When the stop and the target are both reachable, the stop wins</h2>
 *
 * <p>Not because it is true — OHLC cannot say which came first inside the minute
 * — but because it is the conservative side. What makes this honest rather than
 * arbitrary is that the engine <b>counts how often it had to decide</b>. The
 * count goes in the report. On the previous project it was 0,1% of trades; if it
 * ever climbs, the result is a fact about this tie-break and not about the
 * strategy, and the reader has the number to see that.</p>
 */
final class Broker {

    private final Position position = new Position();

    private final Book book = new Book();

    private final Costs costs;

    private final List<Trade> trades = new ArrayList<>();

    private final List<Fill> fills = new ArrayList<>();

    private int ambiguous;

    // Running totals over EVERY fill, including the ones of a trade that has
    // not ended. Trade.gross() only exists once the position is back to zero,
    // and the balance of an open position is precisely what the mark-to-market
    // curve is about.
    private double realisedGross;

    private double paidCost;

    // The trade being accumulated, or openedAt < 0 while flat.
    private int openedAt = -1;

    private Side openSide;

    private int peak;

    private double gross;

    private double cost;

    private List<Fill> tradeFills;

    Broker(Costs costs) {
        this.costs = costs;
    }

    Position position() {
        return position;
    }

    Book book() {
        return book;
    }

    List<Trade> trades() {
        return List.copyOf(trades);
    }

    List<Fill> fills() {
        return List.copyOf(fills);
    }

    /** @return the live list, for {@link Market#filled()} to read the last bar of */
    List<Fill> liveFills() {
        return fills;
    }

    int ambiguousBars() {
        return ambiguous;
    }

    /**
     * @param price what to mark the open position against
     * @return everything the account is worth right now: what has been realised
     *         so far, net of what it cost, plus the open position at that price
     */
    double worthAt(double price) {
        return realisedGross - paidCost + position.openResult(price);
    }

    // ------------------------------------------------------------------- bar

    /**
     * Runs one bar against everything resting from the previous close.
     *
     * @param bar  index into the series
     * @param open the bar's open
     * @param high the bar's high
     * @param low  the bar's low
     */
    void executeDuring(int bar, double open, double high, double low) {
        List<Instruction> resting = book.resting();

        atTheOpen(resting, bar, open);

        // WHAT WAS SENT IS GONE, before anything else happens. Until a run could
        // execute more finely than it decided, the book was rebuilt every bar
        // and this could not be noticed.
        book.sent();

        thenWhatThePriceReached(resting, bar, open, high, low);
    }

    private void atTheOpen(List<Instruction> resting, int bar, double open) {
        for (Instruction instruction : resting) {
            if (instruction == Command.CLOSE_POSITION) {
                close(bar, open, instruction.verb());
            } else if (instruction == Command.REVERSE_POSITION) {
                reverse(bar, open, instruction.verb());
            } else if (instruction instanceof Order order && order.trigger() == Trigger.MARKET) {
                execute(order, bar, open);
            }
        }
    }

    private void thenWhatThePriceReached(List<Instruction> resting, int bar,
                                         double open, double high, double low) {
        List<Reached> reached = new ArrayList<>();

        for (Instruction instruction : resting) {
            if (!(instruction instanceof Order order) || !order.rests()) {
                continue;
            }

            double price = Matching.priceIn(order, open, high, low);

            if (!Double.isNaN(price)) {
                reached.add(new Reached(order, price, Math.abs(price - open)));
            }
        }

        countTheAmbiguity(reached);

        reached.sort(Comparator.comparingDouble(Reached::distance));

        boolean covered = false;

        for (Reached candidate : theStopFirst(reached)) {
            if (candidate.order().covers()) {
                if (covered) {
                    continue;   // the OCO already fired; the other legs are gone
                }

                covered = true;
            }

            execute(candidate.order(), bar, candidate.price());
        }
    }

    /**
     * Moves a reachable cover stop to the front, ahead of every other cover.
     *
     * <p>Only the covers are reordered: an entry that the price reached is not
     * competing with the stop, and holding it back would invent a cancellation.
     */
    private List<Reached> theStopFirst(List<Reached> reached) {
        List<Reached> ordered = new ArrayList<>(reached.size());
        List<Reached> rest = new ArrayList<>(reached.size());

        for (Reached candidate : reached) {
            if (candidate.order().covers() && candidate.order().trigger() == Trigger.STOP) {
                ordered.add(candidate);
            } else {
                rest.add(candidate);
            }
        }

        ordered.addAll(rest);

        return ordered;
    }

    private void countTheAmbiguity(List<Reached> reached) {
        boolean stop = false;
        boolean target = false;

        for (Reached candidate : reached) {
            if (!candidate.order().covers()) {
                continue;
            }

            if (candidate.order().trigger() == Trigger.STOP) {
                stop = true;
            } else {
                target = true;
            }
        }

        if (stop && target) {
            ambiguous++;
        }
    }

    // -------------------------------------------------------------- executing

    private void execute(Order order, int bar, double price) {
        int quantity = allowed(order);

        if (quantity < 1) {
            return;   // a cover with nothing to cover: ignored, as NTSL ignores it
        }

        record(new Fill(bar, order.side(), price, quantity, order.verb()));
    }

    /**
     * How much of this order the position lets through.
     *
     * <p>The manual's two rules about {@link
     * br.com.jorge.reis.endeavourneo.domain.trading.order.Purpose}: a cover
     * "never inverts the position", and one sent against a position that is flat
     * or already on its side "will be ignored". An opening order has no such
     * limit — and when it lands on the other side it "is treated as a cover
     * order automatically", which the position arithmetic does on its own.</p>
     */
    private int allowed(Order order) {
        if (!order.covers()) {
            return order.quantity();
        }

        int coverable = order.side() == Side.BUY
                ? Math.max(0, -position.net())
                : Math.max(0, position.net());

        return Math.min(order.quantity(), coverable);
    }

    private void close(int bar, double price, String verb) {
        if (position.flat()) {
            return;
        }

        record(new Fill(bar, position.isBought() ? Side.SELL : Side.BUY,
                price, position.size(), verb));
    }

    private void reverse(int bar, double price, String verb) {
        if (position.flat()) {
            return;
        }

        record(new Fill(bar, position.isBought() ? Side.SELL : Side.BUY,
                price, position.size() * 2, verb));
    }

    /**
     * Books a fill, splitting it when it crosses zero.
     *
     * <p>A fill big enough to invert is two events wearing one name: it ends a
     * trade and starts another at the same price. Splitting it here is what
     * keeps {@link Trade} honest — the alternative is a trade whose side changes
     * halfway through, and every number computed from it is a blend of two
     * operations.</p>
     */
    private void record(Fill fill) {
        int before = position.net();
        int after = before + fill.signed();

        if (before != 0 && after != 0 && Integer.signum(before) != Integer.signum(after)) {
            book(new Fill(fill.bar(), fill.side(), fill.price(), Math.abs(before), fill.verb()));
            book(new Fill(fill.bar(), fill.side(), fill.price(), Math.abs(after), fill.verb()));

            return;
        }

        book(fill);
    }

    private void book(Fill fill) {
        if (position.flat()) {
            openedAt = fill.bar();
            openSide = fill.side();
            peak = 0;
            gross = 0;
            cost = 0;
            tradeFills = new ArrayList<>();
        }

        double realised = position.apply(fill);

        gross += realised;
        cost += costs.ofFill(fill.quantity());

        realisedGross += realised;
        paidCost += costs.ofFill(fill.quantity());
        peak = Math.max(peak, position.size());

        fills.add(fill);
        tradeFills.add(fill);

        if (position.flat()) {
            trades.add(new Trade(openedAt, fill.bar(), openSide, peak, gross, cost, tradeFills));

            openedAt = -1;
            tradeFills = null;
        }
    }

    /** One resting order the bar reached, and where. */
    private record Reached(Order order, double price, double distance) {
    }
}
