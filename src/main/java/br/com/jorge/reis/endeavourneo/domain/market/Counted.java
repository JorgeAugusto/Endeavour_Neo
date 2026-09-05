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

/**
 * A series whose bars know how many trades made them.
 *
 * <h2>Why it is a separate question from volume</h2>
 *
 * <p>Volume is contracts and this is fills. The reference product shows both
 * side by side — <i>Negócios</i> and <i>Contratos Neg</i> — and the ratio
 * between them is the whole point: an ordinary print on this instrument carries
 * three or four contracts, and an opening auction carries thirty. A brick with
 * seventy thousand contracts in two thousand trades is a different event from
 * one with seventy thousand in two hundred.</p>
 *
 * <h2>Unknown is not zero</h2>
 *
 * <p>Only a source where one bar is one trade can answer this. A minute candle
 * is a summary of trades at prices it does not name, so a renko built from
 * candles says {@link #UNKNOWN} rather than inventing a count — the same
 * distinction {@link TickSeries} makes between "said zero" and "said
 * nothing".</p>
 */
public interface Counted {

    /** What a source that cannot count says. */
    long UNKNOWN = -1L;

    /**
     * @param index the bar
     * @return how many trades it holds, or {@link #UNKNOWN}
     */
    long tradesAt(int index);

    /**
     * @return that bar's trade count, or {@link #UNKNOWN} for a series that
     *         does not carry one
     *
     * <p>Here so a caller never writes the {@code instanceof}, and never has to
     * remember that most series cannot answer.</p>
     */
    static long at(PriceSeries series, int index) {
        return series instanceof Counted counted ? counted.tradesAt(index) : UNKNOWN;
    }
}
