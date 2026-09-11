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
 * Recognises the three-bar shapes of the Profit indicator.
 *
 * <p>Each bar is classified by looking at it and the two before it. Nothing else
 * goes in, and <b>no later bar</b>: a bar's pattern is settled the moment it
 * closes, which is what makes it usable on the live edge and not only in
 * hindsight.
 *
 * <h2>Precedence</h2>
 *
 * <p>Tested in order, first match wins: PFR, then inside, then one-two-three.
 * The order decides one real case — an inside bar satisfies {@code low[1] < low}
 * by construction and may at the same time form a bottom pivot. There the inside
 * wins, as in the original. PFR never competes with the other two: it requires a
 * new extreme and they require the opposite.
 *
 * <h2>A deliberate departure from the original</h2>
 *
 * <p>There, the outer {@code if} of the PFR accepted the bar on one condition
 * and the inner one only painted it if the body agreed. A bar that made a new
 * three-bar high, closed below the previous close and above its own open entered
 * the block, was not painted, <b>and was never tested again</b> against inside
 * and one-two-three — even when it was a perfectly good 1-2-3 to buy. Here every
 * pattern is tested by its own definition, and the swallowed bar goes back to
 * being classifiable. {@link #asProfitDid} keeps the literal behaviour, for
 * measuring the difference rather than for drawing.
 */
public final class CandlePatterns {

    /** How many earlier bars are needed to classify one. */
    public static final int LOOKBACK = 2;

    private CandlePatterns() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param series the bars
     * @param index  the bar to classify
     * @return the pattern, or {@link CandlePattern#NONE} when none matches or
     *         the two earlier bars are not there
     */
    public static CandlePattern detect(PriceSeries series, int index) {
        if (series == null || index < LOOKBACK || index >= series.size()) {
            return CandlePattern.NONE;
        }

        double open = series.openAt(index);
        double high = series.highAt(index);
        double low = series.lowAt(index);
        double close = series.closeAt(index);

        double previousHigh = series.highAt(index - 1);
        double previousLow = series.lowAt(index - 1);
        double previousClose = series.closeAt(index - 1);

        double olderHigh = series.highAt(index - 2);
        double olderLow = series.lowAt(index - 2);

        // 1. PFR: a new extreme over three bars, and a close that refuses it.
        if (low < previousLow && low < olderLow && close > previousClose && close > open) {
            return CandlePattern.PFR_BULLISH;
        }

        if (high > previousHigh && high > olderHigh && close < previousClose && close < open) {
            return CandlePattern.PFR_BEARISH;
        }

        // 2. Inside: the range entirely within the previous bar.
        if (high < previousHigh && low > previousLow) {
            if (close > open) {
                return CandlePattern.INSIDE_BULLISH;
            }

            if (close < open) {
                return CandlePattern.INSIDE_BEARISH;
            }

            // An inside doji was not painted in the original and is not a
            // pattern here either: with no body there is no direction to
            // confirm.
            return CandlePattern.NONE;
        }

        // 3. One-two-three: the middle bar is the pivot, this one confirms.
        if (previousLow < olderLow && previousLow < low && close > open) {
            return CandlePattern.ONE_TWO_THREE_BUY;
        }

        if (previousHigh > olderHigh && previousHigh > high && close < open) {
            return CandlePattern.ONE_TWO_THREE_SELL;
        }

        return CandlePattern.NONE;
    }

    /**
     * Classifies as the original indicator did, chain of {@code if} and all.
     *
     * <p>Here to measure the divergence against {@link #detect}, never to draw
     * with. If the difference turns out to be irrelevant on the real base the
     * clean version stays; if it does not, the alternative is written down.
     *
     * @return the pattern the Profit's own indicator would have painted
     */
    public static CandlePattern asProfitDid(PriceSeries series, int index) {
        if (series == null || index < LOOKBACK || index >= series.size()) {
            return CandlePattern.NONE;
        }

        double open = series.openAt(index);
        double high = series.highAt(index);
        double low = series.lowAt(index);
        double close = series.closeAt(index);

        double previousHigh = series.highAt(index - 1);
        double previousLow = series.lowAt(index - 1);
        double previousClose = series.closeAt(index - 1);

        double olderHigh = series.highAt(index - 2);
        double olderLow = series.lowAt(index - 2);

        // THE SWALLOWING IS THE WHOLE POINT of this method: a bar that enters
        // one of the first two blocks and fails the inner test leaves with no
        // pattern, and never reaches the tests below.
        if (low < previousLow && low < olderLow) {
            return close > previousClose && close > open
                    ? CandlePattern.PFR_BULLISH : CandlePattern.NONE;
        }

        if (high > previousHigh && high > olderHigh) {
            return close < previousClose && close < open
                    ? CandlePattern.PFR_BEARISH : CandlePattern.NONE;
        }

        if (high < previousHigh && low > previousLow) {
            if (close > open) {
                return CandlePattern.INSIDE_BULLISH;
            }

            return close < open ? CandlePattern.INSIDE_BEARISH : CandlePattern.NONE;
        }

        if (previousLow < olderLow && previousLow < low && close > open) {
            return CandlePattern.ONE_TWO_THREE_BUY;
        }

        if (previousHigh > olderHigh && previousHigh > high && close < open) {
            return CandlePattern.ONE_TWO_THREE_SELL;
        }

        return CandlePattern.NONE;
    }

    /**
     * @param series the bars
     * @return one pattern per bar, never null and never holding nulls
     */
    public static CandlePattern[] detectAll(PriceSeries series) {
        int size = series == null ? 0 : series.size();
        CandlePattern[] found = new CandlePattern[size];

        for (int bar = 0; bar < size; bar++) {
            found[bar] = detect(series, bar);
        }

        return found;
    }
}
