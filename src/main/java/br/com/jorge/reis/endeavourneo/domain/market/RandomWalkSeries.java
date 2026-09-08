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


import java.util.Random;

/**
 * Synthetic bars, so the chart has something to draw before real data arrives.
 *
 * <p><b>A placeholder, and it says so where it matters.</b> This is a random
 * walk with volatility clustering — it looks convincingly like a market and is
 * not one. Nothing measured on it means anything, and it exists only to
 * exercise the drawing code: candles, zoom, panning, the crosshair.</p>
 *
 * <p>The seed is fixed so the same PRICES come back every run: a chart that
 * redraws differently on each launch makes it impossible to tell a rendering
 * change from new data.</p>
 *
 * <p><b>The prices, and not the times.</b> The short constructor anchors the
 * first bar on {@code System.currentTimeMillis()}, so the same walk lands on
 * different instants at every launch. That is right for the placeholder chart
 * it exists for -- it should look like today -- and it is why a test that
 * cares when a bar happened has to use the long constructor and say so.</p>
 *
 * <p><b>It lives in the domain, and it used to live in {@code ui.chart}.</b>
 * It makes DATA, like {@code SyntheticTicks} beside it -- everything else in
 * {@code ui.chart} draws. Two tests of the domain had to import from the
 * interface to use it, which is the layer boundary pointing the wrong way.</p>
 *
 * <p>It used to say "delete this class the moment a real series is wired in".
 * Real series have been wired in for a long time -- SeriesCatalog, MarketService
 * -- and this is still used, by MainWindow for the chart that opens before
 * anything is chosen and by the replay for a feed with no export. The
 * instruction was simply false, which is worse than no instruction: it tells
 * whoever reads it that they are looking at something nobody meant to keep.</p>
 */
public final class RandomWalkSeries implements PriceSeries {

    private final long[] times;

    private final double[] opens;

    private final double[] highs;

    private final double[] lows;

    private final double[] closes;

    private final double[] volumes;

    /**
     * @param bars how many to generate
     * @param start the starting price
     */
    public RandomWalkSeries(int bars, double start) {
        this(bars, start, System.currentTimeMillis() - bars * 60_000L, 20_260_902L);
    }

    /**
     * @param bars how many minutes to make up
     * @param start the price to start from
     * @param firstBar the instant of the first bar
     * @param seed what makes this series this series
     *
     * <p>The seed is a parameter and not a constant because a replay has to
     * repeat: the same day must play back the same way every time, or comparing
     * two decisions made on it is comparing two different markets.</p>
     */
    public RandomWalkSeries(int bars, double start, long firstBar, long seed) {
        this.times = new long[bars];
        this.opens = new double[bars];
        this.highs = new double[bars];
        this.lows = new double[bars];
        this.closes = new double[bars];
        this.volumes = new double[bars];

        Random random = new Random(seed);

        double price = start;
        // Measured against the real thing: a WIN minute moves some tens of
        // points on an index around 135.000, which is a couple of hundredths of
        // one per cent. The first version used 0,2% -- ten times too much -- and
        // it only showed when renko turned 2.000 bars into 26.000 bricks.
        double volatility = start * 0.00025;
        long time = firstBar;

        for (int i = 0; i < bars; i++) {
            // Volatility that drifts rather than staying constant: real series
            // cluster their movement, and a chart drawn over constant-variance
            // noise looks wrong in a way that is hard to name.
            volatility *= 1.0 + random.nextGaussian() * 0.05;
            volatility = Math.max(start * 0.00008, Math.min(volatility, start * 0.0008));

            double open = price;
            double close = open + random.nextGaussian() * volatility;
            double wick = Math.abs(random.nextGaussian()) * volatility * 0.6;

            times[i] = time + i * 60_000L;
            opens[i] = open;
            closes[i] = close;
            highs[i] = Math.max(open, close) + wick;
            lows[i] = Math.min(open, close) - wick;

            // Volume that follows the movement: a big bar traded more. Not a
            // law of markets, but close enough that a chart drawn over constant
            // volume looks obviously fake.
            volumes[i] = Math.round(500 + Math.abs(close - open) / volatility * 900
                    + random.nextDouble() * 400);

            price = close;
        }
    }

    @Override
    public int size() {
        return times.length;
    }

    @Override
    public long timeAt(int index) {
        return times[index];
    }

    @Override
    public double openAt(int index) {
        return opens[index];
    }

    @Override
    public double highAt(int index) {
        return highs[index];
    }

    @Override
    public double lowAt(int index) {
        return lows[index];
    }

    @Override
    public double closeAt(int index) {
        return closes[index];
    }

    @Override
    public double volumeAt(int index) {
        return volumes[index];
    }
}
