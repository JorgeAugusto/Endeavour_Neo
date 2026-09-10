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

import br.com.jorge.reis.endeavourneo.domain.trading.order.Side;

/**
 * One execution: contracts changing hands at a price, on a bar.
 *
 * <p>The atom of everything downstream. A {@link Trade} is a run of fills, the
 * equity curve is their running sum, and the marks on the chart are these — so
 * a fill carries the {@code verb} that produced it, and the report can say
 * <i>"stopped out"</i> rather than <i>"sold"</i>.</p>
 *
 * @param bar       index into the series
 * @param side      which way the contracts went
 * @param price     where it executed
 * @param quantity  how many contracts, always positive
 * @param verb      the NTSL verb of the order that caused it, or the command's
 */
public record Fill(int bar, Side side, double price, int quantity, String verb) {

    public Fill {
        if (side == null) {
            throw new IllegalArgumentException("a fill without a side");
        }

        if (quantity < 1) {
            throw new IllegalArgumentException("a fill of " + quantity + " contracts is not a fill");
        }

        if (!Double.isFinite(price)) {
            throw new IllegalArgumentException("a fill needs a price, not " + price);
        }
    }

    /** @return {@code +quantity} for a buy, {@code -quantity} for a sell */
    public int signed() {
        return side.signal() * quantity;
    }
}
