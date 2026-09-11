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

import java.util.Arrays;

/**
 * One bar per synthetic tick: the inside of every minute, walked.
 *
 * <p>A minute of OHLC is four numbers and an unanswerable question — which of
 * the high and the low came first. Every backtest that runs on minutes has to
 * answer it with a rule, and the rule then decides part of the result. This
 * series answers it with a <b>path</b> instead: the minute is broken into the
 * prices that plausibly printed inside it, in order, and a strategy walking
 * those never has to be asked.</p>
 *
 * <h2>What it is, and what it is not</h2>
 *
 * <p>It is not the tape. The path is generated from the shape of the minute by
 * {@link SyntheticTicks}, whose constants were measured against the Profit's
 * real tape — 97,1% of steps are one tick and 17,6% keep the direction of the
 * one before. So the <b>statistics</b> of the walk are real and the walk itself
 * is invented, and the honest use is the one that depends on the statistics: a
 * stop and a target in the same minute stop being a coin toss, because the path
 * says which was reached first.</p>
 *
 * <p>What it must not be used for is anything that depends on a particular
 * print: there is no order book here, no aggressor, no volume that means
 * anything.</p>
 *
 * <h2>It is big, and that is the point of the recorte</h2>
 *
 * <p>A quiet minute becomes eight bars and a violent one becomes thousands. Six
 * years of WIN is around eighty million of them — a run that takes minutes
 * rather than milliseconds. That is the price of the question being answered
 * rather than ruled on, and it is why a range is picked before a run rather
 * than after.</p>
 *
 * <h2>Each bar has one price</h2>
 *
 * <p>Open, high, low and close of a synthetic bar are the same number, because
 * a tick is a price and not a period. Anything that draws candles will draw
 * lines; anything that asks "did the price reach here" gets the answer it
 * came for.</p>
 */
public final class SyntheticSeries implements PriceSeries {

    private static final long MINUTE = 60_000L;

    private final PriceSeries source;

    private final TickPath path;

    /**
     * Where each source bar's ticks begin, plus the total at the end.
     *
     * <p>Built once, from {@link SyntheticTicks#lengthFor} alone — which is
     * arithmetic over the bar and needs no path generated. Eight hundred thousand
     * minutes cost six megabytes here and nothing else is held.</p>
     *
     * <p>It used to read {@code countFor}, which is the <b>fitted number of price
     * changes</b> and not the length of the walk that comes out of it. The walk
     * is longer — measured over two thousand bars, longer on every single one, by
     * 2,6 prices on average — so this index ended each minute a few prices early
     * and <b>the close of every minute was cut off</b>. A backtest run over these
     * ticks was running over minutes that never closed where they closed.</p>
     */
    private final long[] starts;

    /**
     * The last path asked for, kept whole.
     *
     * <p>A record and a single reference, rather than two fields: a reader that
     * saw the new index beside the old prices would read a price from the wrong
     * minute, and nothing downstream would look wrong. Replacing one reference
     * cannot be seen half-done.</p>
     */
    private volatile Cached cached = new Cached(-1, new double[0]);

    private record Cached(int bar, double[] prices) { }

    private SyntheticSeries(PriceSeries source, TickPath path, long[] starts) {
        this.source = source;
        this.path = path;
        this.starts = starts;
    }

    /**
     * @param source the bars to walk inside, usually one minute each
     * @param path   how a bar becomes prices
     * @return the series of ticks, or an empty one when there is nothing to walk
     */
    public static PriceSeries of(PriceSeries source, TickPath path) {
        if (source == null || source.size() == 0 || path == null) {
            return PriceSeries.empty();
        }

        long[] starts = new long[source.size() + 1];

        for (int bar = 0; bar < source.size(); bar++) {
            starts[bar + 1] = starts[bar] + Math.max(1, lengthOf(path, source, bar));
        }

        if (starts[source.size()] > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "that recorte is " + starts[source.size()] + " ticks; cut it smaller");
        }

        return new SyntheticSeries(source, path, starts);
    }

    /**
     * @return how many prices that bar becomes, without walking it when it can be
     *         helped — the whole point of the index being cheap to build
     */
    private static int lengthOf(TickPath path, PriceSeries source, int bar) {
        return path instanceof SyntheticTicks ticks
                ? ticks.lengthFor(source, bar)
                : path.pathFor(source, bar).length;
    }

    @Override
    public int size() {
        return (int) starts[starts.length - 1];
    }

    @Override
    public long timeAt(int index) {
        int bar = barOf(index);
        int within = index - (int) starts[bar];
        int many = (int) (starts[bar + 1] - starts[bar]);

        // Spread evenly across the minute the bar stands for. The path does not
        // carry instants, and pretending otherwise -- clustering them, say --
        // would be inventing a second thing on top of the first.
        return source.timeAt(bar) + (long) within * MINUTE / many;
    }

    @Override
    public double openAt(int index) {
        return priceAt(index);
    }

    @Override
    public double highAt(int index) {
        return priceAt(index);
    }

    @Override
    public double lowAt(int index) {
        return priceAt(index);
    }

    @Override
    public double closeAt(int index) {
        return priceAt(index);
    }

    private double priceAt(int index) {
        int bar = barOf(index);
        double[] prices = pricesOf(bar);
        int within = index - (int) starts[bar];

        // A TickPath is not promised to be deterministic, and one that answers
        // shorter the second time would index past the end. Clamping rather than
        // throwing, because a price one step from the right one is an ordinary
        // approximation and an exception in the middle of a run is not.
        //
        // This is NOT the old countFor/pathFor gap. That one fired on every bar
        // of every series and is gone: the index is built from the length of the
        // walk itself now.
        return prices[Math.min(within, prices.length - 1)];
    }

    private double[] pricesOf(int bar) {
        Cached seen = cached;

        if (seen.bar() == bar) {
            return seen.prices();
        }

        double[] prices = path.pathFor(source, bar);

        if (prices == null || prices.length == 0) {
            prices = new double[] {source.closeAt(bar)};
        }

        cached = new Cached(bar, prices);

        return prices;
    }

    /** @return which source bar that tick belongs to */
    private int barOf(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("tick " + index + " of " + size());
        }

        int at = Arrays.binarySearch(starts, 0, starts.length - 1, index);

        return at >= 0 ? at : -at - 2;
    }

    /** @return the bars this was built over */
    public PriceSeries source() {
        return source;
    }
}
