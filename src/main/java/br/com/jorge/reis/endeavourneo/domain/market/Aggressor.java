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
 * Which side crossed the spread to make a trade happen.
 *
 * <p>The one thing on the tape that says DIRECTION. A price that printed says
 * two people agreed; who came to whom says which of them was in a hurry, and
 * that is the whole reason the tape is worth more than the candle that
 * summarises it.</p>
 *
 * <p>Measured over the eight sessions exported on 04/09/2026 — 43,0 million
 * trades — these five are the only values that appear, and their shares are
 * worth knowing before reading anything into them:</p>
 *
 * <table>
 *   <caption>Share of trades</caption>
 *   <tr><td>{@link #SELLER}</td><td>37,2%</td></tr>
 *   <tr><td>{@link #BUYER}</td><td>36,8%</td></tr>
 *   <tr><td>{@link #RLP}</td><td>25,9%</td></tr>
 *   <tr><td>{@link #AUCTION}</td><td>0,06%</td></tr>
 *   <tr><td>{@link #DIRECT}</td><td>0,001%</td></tr>
 * </table>
 */
public enum Aggressor {

    /** The buyer crossed: someone paid the offer. */
    BUYER("Comprador"),

    /** The seller crossed: someone hit the bid. */
    SELLER("Vendedor"),

    /**
     * Retail Liquidity Provider: the broker took the other side itself.
     *
     * <p>A quarter of every session, so it is not a footnote. These prints
     * never reached the order book — reading them as "the market lifted the
     * offer" is reading a quarter of the tape as something it is not.</p>
     */
    RLP("RLP"),

    /** Opening, closing or a halt: matched by the auction, nobody crossed. */
    AUCTION("Leilão"),

    /** Agreed off the book and printed: two parties, no aggressor. */
    DIRECT("Direto");

    private final String said;

    Aggressor(String said) {
        this.said = said;
    }

    /** @return the word the export uses */
    public String said() {
        return said;
    }

    /**
     * @return the aggressor that export word names, or null if it names none
     *
     * <p>Null so the caller can REFUSE. A sixth kind of print appearing one day
     * is not something to file under "unknown" and average in: the whole point
     * of keeping this column is that it means something, and a value that
     * quietly means "we did not recognise it" would be the one thing on the
     * tape nobody could trust.</p>
     */
    public static Aggressor of(String word) {
        for (Aggressor each : values()) {
            if (each.said.equals(word)) {
                return each;
            }
        }

        return null;
    }
}
