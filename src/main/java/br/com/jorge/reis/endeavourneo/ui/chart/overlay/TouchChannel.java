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
package br.com.jorge.reis.endeavourneo.ui.chart.overlay;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;

/**
 * The channel a reader would draw by hand: two parallel lines resting on the
 * tops and the bottoms.
 *
 * <h2>A channel is one number</h2>
 *
 * <p>Fix a slope {@code m} and project every pivot onto {@code r = y - m*x}.
 * That straightens the chart: each pivot becomes a single number. The upper
 * edge is then the largest {@code r} among the TOPS and the lower edge the
 * smallest among the BOTTOMS — <b>both follow from the slope</b>. So this is
 * not a search in three dimensions, or two. It is a search in one, and that is
 * what makes it exhaustive rather than iterative: no optimiser, no starting
 * guess, no local minimum to fall into.</p>
 *
 * <h2>And the slopes worth trying are finite</h2>
 *
 * <p>No angle is swept. For a criterion that counts touches, the best line can
 * always be rotated until it rests on a SECOND pivot of the same kind without
 * losing anything — so the answer is always the slope through two tops, or
 * through two bottoms. Every such pair is tried; with the thirty-odd pivots a
 * ninety-bar window holds, that is a few hundred slopes.</p>
 *
 * <h2>Why the edges enclose</h2>
 *
 * <p>The upper edge sits at the highest top and not in the middle of a cluster
 * of them. A line with tops above it is not the top of a channel — it is a
 * line with tops above it. Nothing is discarded to keep that true.</p>
 *
 * <p><b>So one spike can leave no channel at all, and that is the honest
 * answer.</b> A top far above everything else is alone on its edge; the search
 * then has to find a slope where it shares that edge with a second top AND two
 * bottoms still share the other, and there may be none. The indicator draws
 * nothing rather than drawing a spike with a line under it and calling the pair
 * a channel. Written down because the first version of this paragraph claimed
 * the search would "absorb" the outlier, and the test showed it cannot always:
 * on the fixture's sawtooth, a top thirty points out leaves no channel.</p>
 *
 * <h2>The tolerance is not a fraction of the channel</h2>
 *
 * <p><b>It reads like the obvious choice and it is a trap.</b> A tolerance
 * proportional to the channel's own width makes the score reward widening:
 * a wider channel gets a wider band, a wider band catches more pivots, more
 * pivots score better — and the search walks off towards a channel that
 * contains the whole screen. The tolerance is therefore a fraction of the
 * AMPLITUDE of the pivots in the window, worked out once, before any slope is
 * tried, so it is the same number for every candidate and cannot be gamed by
 * the thing being chosen.</p>
 *
 * <h2>What it is not</h2>
 *
 * <p>Not the narrowest strip that contains everything — that one has an exact
 * answer through the convex hull, and it is not what a reader draws: a single
 * spike opens it, and a channel the price is allowed to break is the useful
 * one. Not least squares either: {@link RegressionChannel} takes its slope
 * from the CLOSES, and this takes it from the turns, which is why the two do
 * not land in the same place.</p>
 */
public final class TouchChannel implements Overlay {

    /** Bars in the window, as the reference app's own trendlines use. */
    public static final int PERIOD = 90;

    /** As far back as the window may be asked to reach. */
    public static final int MOST_BARS = 400;

    /** How far a pivot may sit from an edge and still count, as a percentage. */
    public static final int TOLERANCE = 5;

    /** How many pivots each edge must carry for this to be a channel at all. */
    private static final int LEAST_TOUCHES = 2;

    private static final Color UPPER_INK = new Color(0xE0, 0x4F, 0x4F);

    private static final Color LOWER_INK = new Color(0x2E, 0xA0, 0x43);

    private int wing = TopsAndBottoms.WING;

    private int period;

    private int tolerance = TOLERANCE;

    private TopsAndBottoms.Ties ties = TopsAndBottoms.Ties.LAST;

    private Color upperColour;

    private Color lowerColour;

    private MovingAverage.Line line = MovingAverage.Line.SOLID;

    private int thickness = 2;

    private boolean fill;

    private int opacity = 10;

    private boolean visible = true;

    private volatile PriceSeries source = PriceSeries.empty();

    private volatile Fit fit;

    /**
     * One channel, as the numbers it takes to draw it.
     *
     * @param first the oldest bar the channel is drawn over
     * @param anchor the newest
     * @param slope price per bar, shared by both edges
     * @param upper the upper edge's intercept, in the projected coordinate
     * @param lower the lower edge's
     * @param touchesAbove how many tops rest on the upper edge
     * @param touchesBelow how many bottoms rest on the lower edge
     * @param resting the pivots that count as touching, for the markers
     */
    record Fit(int first, int anchor, double slope, double upper, double lower,
               int touchesAbove, int touchesBelow, List<TopsAndBottoms.Pivot> resting) {

        /** @return the upper edge's price at that bar */
        double upperAt(int bar) {
            return upper + slope * bar;
        }

        /** @return the lower edge's price there */
        double lowerAt(int bar) {
            return lower + slope * bar;
        }

        /** @return how far apart the two edges are, in price */
        double width() {
            return upper - lower;
        }

        int touches() {
            return touchesAbove + touchesBelow;
        }
    }

    public TouchChannel(int period) {
        setPeriod(period);
    }

    /** @param settings the window, then the wing — the catalogue's factory */
    public TouchChannel(int... settings) {
        this(settings.length > 0 ? settings[0] : PERIOD);

        if (settings.length > 1) {
            setWing(settings[1]);
        }
    }

    public int period() {
        return period;
    }

    public void setPeriod(int value) {
        this.period = Math.max(10, Math.min(value, MOST_BARS));
    }

    public int wing() {
        return wing;
    }

    public void setWing(int value) {
        this.wing = Math.max(1, Math.min(value, TopsAndBottoms.MOST_WING));
    }

    public int tolerance() {
        return tolerance;
    }

    /** @param value how far from an edge still counts, as a percentage of the swing */
    public void setTolerance(int value) {
        this.tolerance = Math.max(1, Math.min(value, 25));
    }

    public TopsAndBottoms.Ties ties() {
        return ties;
    }

    public void setTies(TopsAndBottoms.Ties value) {
        this.ties = value == null ? TopsAndBottoms.Ties.LAST : value;
    }

    public Color chosenUpperColour() {
        return upperColour;
    }

    public void setUpperColour(Color value) {
        this.upperColour = value;
    }

    public Color chosenLowerColour() {
        return lowerColour;
    }

    public void setLowerColour(Color value) {
        this.lowerColour = value;
    }

    public MovingAverage.Line line() {
        return line;
    }

    public void setLine(MovingAverage.Line value) {
        this.line = value == null ? MovingAverage.Line.SOLID : value;
    }

    public int thickness() {
        return thickness;
    }

    public void setThickness(int value) {
        this.thickness = Math.max(1, Math.min(value, 8));
    }

    public boolean isFilled() {
        return fill;
    }

    public void setFilled(boolean value) {
        this.fill = value;
    }

    public int opacity() {
        return opacity;
    }

    public void setOpacity(int value) {
        this.opacity = Math.max(0, Math.min(value, 100));
    }

    // ------------------------------------------------------------ the numbers

    /** @return the channel's slope in price per bar, or NaN when there is none */
    public double slope() {
        Fit now = fit;

        return now == null ? Double.NaN : now.slope();
    }

    /** @return how far apart the edges are, or NaN */
    public double width() {
        Fit now = fit;

        return now == null ? Double.NaN : now.width();
    }

    /** @return how many pivots rest on the two edges, or zero */
    public int touches() {
        Fit now = fit;

        return now == null ? 0 : now.touches();
    }

    // ------------------------------------------------------------ the working

    /**
     * Finds the channel over the window that ends at {@code anchor}.
     *
     * @param series the bars
     * @param anchor the newest bar of the window
     * @return the best channel, or null when there is not enough to fit one
     *
     * <p>Package-private so a test can hand it a shape it made itself and check
     * the answer against one worked out on paper, with no chart involved.</p>
     */
    Fit fitAt(PriceSeries series, int anchor) {
        if (series == null || anchor < 1 || anchor >= series.size()) {
            return null;
        }

        int first = Math.max(0, anchor - period + 1);

        List<TopsAndBottoms.Pivot> pivots = new ArrayList<>();

        // FROM THE WINDOW, not from bar zero. The fit only ever uses pivots at
        // or after `first`, and scanning the whole series for them cost 9,3 ms
        // per frame on two thousand bars -- on a chart holding a hundred
        // thousand it is the whole budget, on the interface thread, on every
        // movement of the mouse. Measured before and after.
        pivots.addAll(TopsAndBottoms.alternating(
                TopsAndBottoms.candidates(series, wing, ties, first, anchor + 1)));

        List<TopsAndBottoms.Pivot> tops = ofKind(pivots, true);
        List<TopsAndBottoms.Pivot> bottoms = ofKind(pivots, false);

        if (tops.size() < LEAST_TOUCHES || bottoms.size() < LEAST_TOUCHES) {
            return null;
        }

        // WORKED OUT ONCE, before any slope is tried. See the class javadoc:
        // a tolerance that grows with the channel rewards widening, and the
        // search walks off towards a channel the size of the screen.
        double band = amplitudeOf(pivots) * tolerance / 100.0;

        if (!(band > 0)) {
            return null;
        }

        Fit best = null;

        for (double slope : slopesFrom(tops, bottoms)) {
            Fit made = channelAt(slope, band, first, anchor, tops, bottoms);

            if (made != null && better(made, best)) {
                best = made;
            }
        }

        return best;
    }

    private static List<TopsAndBottoms.Pivot> ofKind(List<TopsAndBottoms.Pivot> from,
            boolean top) {

        List<TopsAndBottoms.Pivot> found = new ArrayList<>();

        for (TopsAndBottoms.Pivot each : from) {
            if (each.top() == top) {
                found.add(each);
            }
        }

        return found;
    }

    private static double amplitudeOf(List<TopsAndBottoms.Pivot> pivots) {
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;

        for (TopsAndBottoms.Pivot each : pivots) {
            low = Math.min(low, each.price());
            high = Math.max(high, each.price());
        }

        return high - low;
    }

    /**
     * @return every slope through two pivots of the same kind
     *
     * <p>Only same-kind pairs, because a line that rests on a top and a bottom
     * rests on neither edge of a channel. And every pair, because the best line
     * can always be rotated until it meets a second pivot: the answer is
     * therefore always one of these, and trying them all is exhaustive rather
     * than a search that might stop early in the wrong place.</p>
     */
    private static List<Double> slopesFrom(List<TopsAndBottoms.Pivot> tops,
            List<TopsAndBottoms.Pivot> bottoms) {

        List<Double> found = new ArrayList<>();

        pairsInto(tops, found);
        pairsInto(bottoms, found);

        return found;
    }

    private static void pairsInto(List<TopsAndBottoms.Pivot> pivots, List<Double> into) {
        for (int i = 0; i < pivots.size(); i++) {
            for (int j = i + 1; j < pivots.size(); j++) {
                int run = pivots.get(j).bar() - pivots.get(i).bar();

                if (run != 0) {
                    into.add((pivots.get(j).price() - pivots.get(i).price()) / run);
                }
            }
        }
    }

    /**
     * @param slope the candidate
     * @param band how far from an edge still counts
     * @return the channel that slope gives, or null when an edge carries too few
     *
     * <p>The edges ENCLOSE: the upper one at the highest projected top, the
     * lower at the lowest projected bottom. Then the touches are counted — the
     * pivots within {@code band} of their own edge, the two extremes
     * included.</p>
     */
    private static Fit channelAt(double slope, double band, int first, int anchor,
            List<TopsAndBottoms.Pivot> tops, List<TopsAndBottoms.Pivot> bottoms) {

        double upper = -Double.MAX_VALUE;
        double lower = Double.MAX_VALUE;

        for (TopsAndBottoms.Pivot each : tops) {
            upper = Math.max(upper, each.price() - slope * each.bar());
        }

        for (TopsAndBottoms.Pivot each : bottoms) {
            lower = Math.min(lower, each.price() - slope * each.bar());
        }

        if (!(upper > lower)) {
            // The two edges crossed, which a slope steeper than the swing can
            // do. Not a channel.
            return null;
        }

        List<TopsAndBottoms.Pivot> resting = new ArrayList<>();
        int above = 0;
        int below = 0;

        for (TopsAndBottoms.Pivot each : tops) {
            if (upper - (each.price() - slope * each.bar()) <= band) {
                above++;
                resting.add(each);
            }
        }

        for (TopsAndBottoms.Pivot each : bottoms) {
            if ((each.price() - slope * each.bar()) - lower <= band) {
                below++;
                resting.add(each);
            }
        }

        if (above < LEAST_TOUCHES || below < LEAST_TOUCHES) {
            // TWO ON EACH SIDE, and this is what makes it a channel rather
            // than a trendline with a stray parallel: without it the search
            // happily rests three tops on one edge and one bottom on the
            // other, and calls the result a channel.
            return null;
        }

        return new Fit(first, anchor, slope, upper, lower, above, below, List.copyOf(resting));
    }

    /**
     * @return whether the first channel beats the second
     *
     * <p>Most touches; then the narrower, because between two channels the
     * price respects equally the tighter one describes it better; then the
     * one whose pivots run furthest, because a channel resting on a longer
     * stretch has been tested by more of the market.</p>
     */
    private static boolean better(Fit made, Fit than) {
        if (than == null) {
            return true;
        }

        if (made.touches() != than.touches()) {
            return made.touches() > than.touches();
        }

        if (Math.abs(made.width() - than.width()) > 1e-9) {
            return made.width() < than.width();
        }

        return spanOf(made) > spanOf(than);
    }

    private static int spanOf(Fit fit) {
        int oldest = Integer.MAX_VALUE;
        int newest = Integer.MIN_VALUE;

        for (TopsAndBottoms.Pivot each : fit.resting()) {
            oldest = Math.min(oldest, each.bar());
            newest = Math.max(newest, each.bar());
        }

        return newest - oldest;
    }

    // ------------------------------------------------------------ the contract

    @Override
    public String nameKey() {
        return "overlay.touchChannel";
    }

    @Override
    public List<Integer> parameters() {
        return List.of(period, wing);
    }

    @Override
    public boolean fitsOnPrice() {
        // Two lines in points of the index, resting on the highs and lows.
        return true;
    }

    @Override
    public List<Color> colours() {
        // In the order valueAt returns them: upper, then lower.
        return List.of(upperColour == null ? UPPER_INK : upperColour,
                lowerColour == null ? LOWER_INK : lowerColour);
    }

    @Override
    public Stroke stroke() {
        return line.stroke(thickness);
    }

    @Override
    public List<Stroke> strokes() {
        return List.of(line.stroke(thickness), line.stroke(thickness));
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void setVisible(boolean value) {
        this.visible = value;
    }

    @Override
    public void calculate(PriceSeries series) {
        this.source = series == null ? PriceSeries.empty() : series;

        // The fit belongs to bars that have just been replaced.
        this.fit = null;
    }

    /**
     * @param bar an index into the series
     * @return the two edges there, or two NaN outside the window
     *
     * <p>From the fit the last {@link #paintUnder} made, which is the fit on
     * screen. Outside the window it answers NaN rather than extending the
     * lines: a channel carried past the pivots it rests on is a forecast.</p>
     */
    @Override
    public double[] valueAt(int bar) {
        Fit now = fit;

        if (now == null || bar < now.first() || bar > now.anchor()) {
            return new double[]{Double.NaN, Double.NaN};
        }

        return new double[]{now.upperAt(bar), now.lowerAt(bar)};
    }

    /**
     * Fits against what is on screen, and shades the channel if asked.
     *
     * <p>The fit happens here for the reason {@link RegressionChannel} explains
     * at length: this is the only method handed a {@link Viewport}, the anchor
     * is the last bar in view, and {@code valueAt} is asked immediately
     * afterwards by the same loop.</p>
     */
    @Override
    public void paintUnder(Graphics2D g, Viewport viewport, int from, int to) {
        PriceSeries series = source;

        int anchor = Math.min(to, series.size()) - 1;

        Fit made = fitAt(series, anchor);

        fit = made;

        if (made == null || !fill || opacity <= 0) {
            return;
        }

        Color base = upperColour == null ? UPPER_INK : upperColour;

        g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.round(255 * opacity / 100f)));

        Polygon shape = new Polygon();

        shape.addPoint(x(viewport, made.first()), y(viewport, made.upperAt(made.first())));
        shape.addPoint(x(viewport, made.anchor()), y(viewport, made.upperAt(made.anchor())));
        shape.addPoint(x(viewport, made.anchor()), y(viewport, made.lowerAt(made.anchor())));
        shape.addPoint(x(viewport, made.first()), y(viewport, made.lowerAt(made.first())));

        g.fillPolygon(shape);
    }

    private static int x(Viewport viewport, int bar) {
        return (int) Math.round(viewport.x(bar));
    }

    private static int y(Viewport viewport, double price) {
        return (int) Math.round(viewport.y(price));
    }

    @Override
    public String appearance() {
        return tolerance + ";" + ties + ";" + line + ";" + thickness
                + ";" + hex(upperColour) + ";" + hex(lowerColour)
                + ";" + fill + ";" + opacity;
    }

    private static String hex(Color colour) {
        return colour == null ? "auto" : Integer.toHexString(colour.getRGB() & 0xFFFFFF);
    }

    @Override
    public void applyAppearance(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String[] parts = text.split(";", -1);

        if (parts.length > 0) {
            setTolerance(number(parts[0], TOLERANCE));
        }

        if (parts.length > 1) {
            setTies(TopsAndBottoms.Ties.STRICT.name().equals(parts[1])
                    ? TopsAndBottoms.Ties.STRICT : TopsAndBottoms.Ties.LAST);
        }

        if (parts.length > 3) {
            setLine(readLine(parts[2]));
            setThickness(number(parts[3], 2));
        }

        if (parts.length > 5) {
            setUpperColour(readColour(parts[4]));
            setLowerColour(readColour(parts[5]));
        }

        if (parts.length > 7) {
            setFilled(Boolean.parseBoolean(parts[6]));
            setOpacity(number(parts[7], 10));
        }
    }

    private static MovingAverage.Line readLine(String text) {
        for (MovingAverage.Line each : MovingAverage.Line.values()) {
            if (each.name().equals(text)) {
                return each;
            }
        }

        return MovingAverage.Line.SOLID;
    }

    private static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            // A hand-edited line beats losing the rest of the appearance.
            return fallback;
        }
    }

    private static Color readColour(String text) {
        if (text == null || "auto".equals(text)) {
            return null;
        }

        try {
            return new Color(Integer.parseInt(text, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
