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
    BUYER("Comprador", 1),

    /** The seller crossed: someone hit the bid. */
    SELLER("Vendedor", 2),

    /**
     * Retail Liquidity Provider: the broker took the other side itself.
     *
     * <p>A quarter of every session, so it is not a footnote. These prints
     * never reached the order book — reading them as "the market lifted the
     * offer" is reading a quarter of the tape as something it is not.</p>
     */
    RLP("RLP", 3),

    /** Opening, closing or a halt: matched by the auction, nobody crossed. */
    AUCTION("Leilão", 4),

    /** Agreed off the book and printed: two parties, no aggressor. */
    DIRECT("Direto", 5);

    private final String said;

    private final int code;

    Aggressor(String said, int code) {
        this.said = said;
        this.code = code;
    }

    /** Every code, at its own index; built once, and never handed out. */
    private static final Aggressor[] BY_CODE = byCode();

    private static Aggressor[] byCode() {
        int highest = 0;

        for (Aggressor each : values()) {
            highest = Math.max(highest, each.code);
        }

        Aggressor[] found = new Aggressor[highest + 1];

        for (Aggressor each : values()) {
            found[each.code] = each;
        }

        return found;
    }

    /**
     * @return the byte this is written as on the tape
     *
     * <p><b>Stated, and it used to be {@code ordinal() + 1}.</b> The order these
     * five are DECLARED in was the file format, and nothing here said so -- while
     * the table above lists them by frequency, in a different order. Anybody who
     * tidied the declarations to match the table, which is the most natural thing
     * in the world to do, would have swapped buyer for seller in every tape ever
     * written.</p>
     *
     * <p>And it would have been silent and total: the read goes on accepting the
     * file, because the byte is still inside the range, and the one column of the
     * tape that says DIRECTION starts saying the opposite. Forty-three million
     * trades were already converted when this was found.</p>
     *
     * <p>A new value takes the next free code. The declarations can now be moved
     * around freely, which is what makes the question stop mattering.</p>
     */
    public int code() {
        return code;
    }

    /**
     * @param code a byte read from a tape
     * @return what it names, or null when nothing does
     */
    public static Aggressor ofCode(int code) {
        return code < 1 || code >= BY_CODE.length ? null : BY_CODE[code];
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
