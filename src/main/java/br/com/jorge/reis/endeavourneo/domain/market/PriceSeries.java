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
 * The only thing the chart needs to know about price data.
 *
 * <p><b>The chart declares what it needs; the domain is not asked to fit.</b>
 * A charting component that depends on the full market model drags the whole
 * domain into every test and makes the chart impossible to exercise with three
 * made-up bars. Six accessors is the entire contract.</p>
 *
 * <p>The names deliberately match the accessors the existing {@code
 * CandleSeries} already exposes, so porting it later needs an adapter of one
 * line per method — or nothing at all, if it simply declares this interface.</p>
 *
 * <p><b>Index 0 is the oldest bar</b>, and {@code size() - 1} the newest. That
 * is the order the data arrives in and the order everything else in the project
 * uses; inverting it here would guarantee an off-by-one somewhere.</p>
 */
public interface PriceSeries {

    /** @return how many bars there are; may be zero */
    int size();

    /** @param index 0 is the oldest bar
     *  @return the bar's opening instant, in milliseconds since the epoch */
    long timeAt(int index);

    double openAt(int index);

    double highAt(int index);

    double lowAt(int index);

    double closeAt(int index);

    /**
     * @param index 0 is the oldest bar
     * @return how much traded in that bar, or NaN when the series has none
     *
     * <p>Optional, and NaN rather than zero when absent. Zero is a claim -- that
     * nothing traded -- and the wrong one; NaN says "not known", and the readout
     * omits the row instead of printing a falsehood.</p>
     */
    default double volumeAt(int index) {
        return Double.NaN;
    }

    /** @return a series with no bars, for an empty screen */
    static PriceSeries empty() {
        return new PriceSeries() {

            @Override
            public int size() {
                return 0;
            }

            @Override
            public long timeAt(int index) {
                throw new IndexOutOfBoundsException("empty series");
            }

            @Override
            public double openAt(int index) {
                throw new IndexOutOfBoundsException("empty series");
            }

            @Override
            public double highAt(int index) {
                throw new IndexOutOfBoundsException("empty series");
            }

            @Override
            public double lowAt(int index) {
                throw new IndexOutOfBoundsException("empty series");
            }

            @Override
            public double closeAt(int index) {
                throw new IndexOutOfBoundsException("empty series");
            }
        };
    }
}
