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

import java.time.LocalDate;

/**
 * One session's ticks, exactly as the exchange sent them.
 *
 * <h2>Absent is not zero</h2>
 *
 * <p>Most rows carry a trade and nothing else; a few carry only a new bid or
 * ask. A row that says nothing about the bid is <b>not</b> a row saying the bid
 * is zero — and the very first row of a session really does say zero for
 * everything, so the two must stay apart. Every field therefore has a
 * {@code has} beside it, and reading a field that is not there is a programming
 * error rather than a number.</p>
 *
 * <p>This matters more than it looks: a book rebuilt from a series that
 * confused the two would show the bid collapsing to zero millions of times a
 * day. It would be obviously wrong, which is the good case; the bad case is an
 * average that quietly includes those zeros.</p>
 *
 * <h2>Prices are whole numbers</h2>
 *
 * <p>Measured over January 2021: not one of the 87 million rows has a
 * fractional price or a fractional volume, and the largest price seen is
 * 133.385. Integers are therefore exact here, which {@code double} would not
 * be, and half the size.</p>
 */
public interface TickSeries {

    /** @return how many ticks the session holds */
    int size();

    /** @return the session's date */
    LocalDate date();

    /**
     * @return milliseconds since midnight
     *
     * <p>Kept apart from the date rather than as one instant, because that is
     * how the file stores it and because a millisecond of the day carries no
     * timezone with it to get wrong.</p>
     */
    int millisAt(int index);

    /**
     * @return the instant, in epoch milliseconds
     *
     * <p>In the machine's own zone, which is the convention the candle base
     * already uses — a tick and the minute candle that contains it have to
     * agree, and they only agree if they are read the same way.</p>
     */
    long timeAt(int index);

    boolean hasBid(int index);

    int bidAt(int index);

    boolean hasAsk(int index);

    int askAt(int index);

    boolean hasLast(int index);

    /** @return the traded price */
    int lastAt(int index);

    boolean hasVolume(int index);

    int volumeAt(int index);

    /**
     * @return the exchange's own flags for the row
     *
     * <p>Kept because they are the only thing that says what KIND of row this
     * is — a trade at the bid, a trade at the ask, a new quote. Eight distinct
     * values appear in January 2021. Dropping them would make the file smaller
     * and the book impossible to rebuild.</p>
     *
     * <p>Zero from a source that has no such column. The tape is one: it
     * carries the {@link Aggressor} instead, which is the same question
     * answered better.</p>
     */
    int flagsAt(int index);

    // ------------------------------------------------------------ the tape
    //
    // Defaults that say "no", so a source that does not carry these needs no
    // code at all to decline them -- and so anything reading a series can ASK
    // rather than having to know which source it was handed. That is the whole
    // seam between the two formats: renko, bars and replay only ever want the
    // traded price, and what wants the difference asks for it.

    /** @return whether the row names who bought */
    default boolean hasBuyer(int index) {
        return false;
    }

    /** @return the buying broker's code; see {@link #brokerName} */
    default int buyerAt(int index) {
        throw new IllegalStateException("this source does not name the buyer");
    }

    /** @return whether the row names who sold */
    default boolean hasSeller(int index) {
        return false;
    }

    /** @return the selling broker's code; see {@link #brokerName} */
    default int sellerAt(int index) {
        throw new IllegalStateException("this source does not name the seller");
    }

    /** @return whether the row says which side crossed */
    default boolean hasAggressor(int index) {
        return false;
    }

    /** @return which side crossed the spread */
    default Aggressor aggressorAt(int index) {
        throw new IllegalStateException("this source does not say who crossed");
    }

    /**
     * @return what that broker code is called, or null if this source does not
     *         know
     *
     * <p>A lookup on the series and not a field on the row, because the name is
     * the same on every one of five million rows. Kept once at the end of the
     * file and read back into a map; storing it per trade would be a hundred
     * megabytes of the same thirty-one strings.</p>
     */
    default String brokerName(int code) {
        return null;
    }
}
