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
package br.com.jorge.reis.endeavourneo.domain.market;

import java.util.HashMap;
import java.util.Map;

/**
 * The trades waiting to be given to a brick, gathered by the level they hit.
 *
 * <h2>What it is for</h2>
 *
 * <p>A renko brick is a band of price. The trades that belong to it are the
 * ones that landed in that band while it was forming — that is what the
 * reference product reports as <i>Negócios</i> and <i>Contratos Neg</i>, and
 * measuring it is the only way to say a brick was never traded rather than
 * guessing it from geometry.</p>
 *
 * <p>Splitting a batch's volume equally among its bricks, which is what this
 * replaces for tick sources, is a convention the data does not support. This is
 * counted.</p>
 *
 * <h2>Why by cell and not as a list</h2>
 *
 * <p>Between two bricks there can be a very large number of trades — at 200
 * points, most of an hour, and the tape prints five million a session. Keeping
 * them costs memory that grows with how quiet the market is, which is the wrong
 * way round. A brick's band is always one cell of the price grid, so the trades
 * can be added up as they arrive: <b>a handful of cells instead of hundreds of
 * thousands of trades</b>, and the answer is the same.</p>
 *
 * <h2>The edge of a cell</h2>
 *
 * <p>Prices sitting exactly on a grid level are kept apart from the rest,
 * because they belong to a different brick depending on which way it went. A
 * brick owns the level it closes at: rising from {@code k} to {@code k+1} it
 * covers the inside of cell {@code k} plus the level {@code k+1}, and falling
 * from {@code k+1} to {@code k} it covers the inside of cell {@code k} plus the
 * level {@code k}. On this instrument prices move in fives and bricks in
 * twenty-fives, so landing on a level is routine — see {@link Renko}.</p>
 *
 * <p><b>Only trades can be counted.</b> A bar with a range is a summary of many
 * trades at prices it does not report, so a window that saw one is marked and
 * every brick born from it answers "unknown" rather than inventing a number.</p>
 */
public final class TradeTally {

    /** What landed in one cell: how many, how much, and when the first arrived. */
    static final class Cell {

        long trades;

        double volume;

        long first = Long.MAX_VALUE;

        Cell copy() {
            Cell other = new Cell();

            other.trades = trades;
            other.volume = volume;
            other.first = first;

            return other;
        }

        void take(Cell other) {
            if (other == null) {
                return;
            }

            trades += other.trades;
            volume += other.volume;
            first = Math.min(first, other.first);
        }
    }

    /** Trades strictly between two levels, by the cell below them. */
    private final Map<Long, Cell> inside = new HashMap<>();

    /** Trades exactly on a level, by that level. */
    private final Map<Long, Cell> onLevel = new HashMap<>();

    /** Whether a bar with a range was seen, which makes counting impossible. */
    private boolean summarised;

    public TradeTally() {
        // Empty: nothing has been traded yet.
    }

    /** @return a copy, so folding a stretch never writes through the caller's carry */
    public TradeTally copy() {
        TradeTally other = new TradeTally();

        inside.forEach((cell, held) -> other.inside.put(cell, held.copy()));
        onLevel.forEach((cell, held) -> other.onLevel.put(cell, held.copy()));
        other.summarised = summarised;

        return other;
    }

    /** @return whether anything here came from a bar that is not a single trade */
    public boolean summarised() {
        return summarised;
    }

    /**
     * Adds one bar.
     *
     * @param brick the brick height, which is the width of a cell
     */
    void add(PriceSeries source, int index, double brick) {
        if (source.highAt(index) != source.lowAt(index)) {
            // A bar with a range holds trades at prices it does not name.
            summarised = true;
        }

        double price = source.closeAt(index);
        double exact = price / brick;
        long cell = (long) Math.floor(exact);
        boolean level = Math.abs(exact - Math.rint(exact)) < 1e-9;

        Map<Long, Cell> where = level ? onLevel : inside;

        if (level) {
            cell = Math.round(exact);
        }

        Cell held = where.computeIfAbsent(cell, key -> new Cell());
        double volume = source.volumeAt(index);

        held.trades++;
        held.first = Math.min(held.first, source.timeAt(index));

        if (Double.isFinite(volume)) {
            held.volume += volume;
        }
    }

    /**
     * Hands one brick everything that landed in its band, and forgets it.
     *
     * @param open where the brick started
     * @param close where it ended
     * @param brick the brick height
     * @return the trades, the contracts and the first of them
     */
    Cell claim(double open, double close, double brick) {
        long low = Math.round(Math.min(open, close) / brick);
        Cell mine = new Cell();

        mine.take(inside.remove(low));
        // Rising, the level it closes at is the top of the cell; falling, the
        // bottom. Either way it is the level the brick ENDED on.
        mine.take(onLevel.remove(close > open ? low + 1 : low));

        return mine;
    }

    /** @return the volume held here and not yet given to a brick */
    double waiting() {
        double total = 0.0;

        for (Cell held : inside.values()) {
            total += held.volume;
        }

        for (Cell held : onLevel.values()) {
            total += held.volume;
        }

        return total;
    }
}
