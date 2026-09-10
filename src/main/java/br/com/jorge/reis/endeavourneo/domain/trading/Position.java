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

/**
 * How many contracts are open, and at what average price.
 *
 * <p>One signed number and one price. Long is positive, short is negative, and
 * the sign is what makes accumulation and partial exits fall out instead of
 * needing cases: a fill in the same direction re-averages, a fill against it
 * realises, and a fill big enough to cross zero does both.</p>
 *
 * <h2>The average is NaN while flat</h2>
 *
 * <p>There is no average price of no contracts. Zero would be one, and it is
 * the kind that survives arithmetic silently — a stop computed as
 * {@code average - 200} would come out at -200 and never trigger, which reads
 * as "the stop never hit" rather than as the bug it is.</p>
 *
 * <h2>Points, not money</h2>
 *
 * <p>Everything here is in points of the index. Money is one multiplication away
 * (R$ 0,20 per point per contract on the WIN) and belongs to the report, where
 * the contract size is known; a domain that carried reais would have to be told
 * which instrument it was, and would be wrong quietly when nobody did.</p>
 */
public final class Position {

    private int net;

    private double average = Double.NaN;

    /**
     * Applies a fill and returns what it realised.
     *
     * @param fill what executed
     * @return points realised by this fill, gross of costs; zero when the fill
     *         only opened or increased the position
     */
    public double apply(Fill fill) {
        int delta = fill.signed();

        if (net == 0) {
            net = delta;
            average = fill.price();

            return 0;
        }

        if (Integer.signum(delta) == Integer.signum(net)) {
            average = (average * net + fill.price() * delta) / (net + delta);
            net += delta;

            return 0;
        }

        // Against the position: it gives back as much as there is, and whatever
        // is left over opens the other way.
        int closed = Math.min(Math.abs(delta), Math.abs(net));
        double realised = (fill.price() - average) * closed * Integer.signum(net);

        int before = net;
        net += delta;

        if (net == 0) {
            average = Double.NaN;
        } else if (Integer.signum(net) != Integer.signum(before)) {
            average = fill.price();
        }

        return realised;
    }

    /** @return signed contracts: positive long, negative short, zero flat */
    public int net() {
        return net;
    }

    /** @return contracts open, unsigned */
    public int size() {
        return Math.abs(net);
    }

    /** @return the average price of what is open, or NaN while flat */
    public double average() {
        return average;
    }

    /** @return whether nothing is open */
    public boolean flat() {
        return net == 0;
    }

    /** {@code IsBought} */
    public boolean isBought() {
        return net > 0;
    }

    /** {@code IsSold} */
    public boolean isSold() {
        return net < 0;
    }

    /**
     * {@code OpenResult} — what the open position is worth right now.
     *
     * @param price the price to mark against, usually the bar's close
     * @return points, zero while flat
     */
    public double openResult(double price) {
        return net == 0 ? 0 : (price - average) * net;
    }
}
