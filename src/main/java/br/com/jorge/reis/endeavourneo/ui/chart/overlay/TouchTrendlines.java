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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;

/**
 * The two trendlines drawn by most touches: one under the bottoms, one over
 * the tops, each ending at the most recent turn of its own kind.
 *
 * <h2>Not the channel</h2>
 *
 * <p>{@link TouchChannel} answers a different question. There the two edges are
 * PARALLEL — one slope, and the edges enclose — because the question is what
 * band the market is walking inside. Here the two lines are found <b>one at a
 * time and independently</b>: each has its own slope, so they may converge into
 * a wedge, diverge into a megaphone, or cross. That is not a defect of the fit;
 * it is the shape the reference app draws, and a triangle closing is a reading
 * the parallel channel cannot express at all.</p>
 *
 * <p>The other difference is that <b>a line here does not enclose</b>. The
 * residual is an absolute distance, so a trendline may leave pivots on either
 * side of it. A support line with a bottom underneath is exactly what the
 * reference app draws when the market pierced it, and hiding that would hide
 * the break.</p>
 *
 * <h2>The line ends where the last turn is, and is then projected</h2>
 *
 * <p>The far end is fixed before anything is searched: it is the LAST pivot of
 * that kind. Only the near end — the anchor — is chosen. So this is not a fit
 * over a cloud of points; it is the answer to "from which old turn, through the
 * newest one, does the line touch most?". Everything to the right of that last
 * turn is projection, drawn to the right edge of the window, and that is the
 * part a reader trades against.</p>
 *
 * <h2>The tolerance climbs until the line has a third point</h2>
 *
 * <p><b>Two points define any line at all</b>, so a line that touches only its
 * own two ends says nothing. The search therefore starts at a tight tolerance
 * and, if the winner still has just those two touches, widens it a step and
 * tries again — up to the ceiling, where whatever was found is taken. So the
 * tolerance reported with each line is part of the answer: a line found at 2%
 * is a line the market drew, and one that needed 10% is one the reader talked
 * himself into.</p>
 *
 * <p>The percentage is of the AMPLITUDE of the pivots in the window, worked out
 * before any anchor is tried, for the reason {@link TouchChannel} sets out: a
 * tolerance measured against the thing being chosen rewards choosing a bigger
 * one.</p>
 *
 * <h2>What was kept from the reference app, number for number</h2>
 *
 * <p>The ladder 2-4-6-8-10, the floor of {@value #LEAST_TOLERANCE} points under
 * the tolerance, the third touch as the bar for stopping, and the ranking —
 * most touches, then least total error, then the OLDEST anchor. The last one
 * matters and is easy to get backwards: between two equally good lines the
 * older anchor is preferred, because a line resting on a turn from further back
 * has survived more of the market.</p>
 */
public final class TouchTrendlines implements Overlay {

    /** Bars in the window, as the reference app opens. */
    public static final int PERIOD = 90;

    /** As far back as the window may be asked to reach. */
    public static final int MOST_BARS = 400;

    /** Where the tolerance ladder starts, as a percentage of the swing. */
    public static final int TOLERANCE_FROM = 2;

    /** Where it stops climbing. */
    public static final int TOLERANCE_TO = 10;

    /** How much it climbs each try. */
    public static final int TOLERANCE_STEP = 2;

    /**
     * The tolerance never goes under this, in points of the index.
     *
     * <p>Five points is one tick of the WIN. Without a floor, a window of quiet
     * bars has an amplitude of a few points and 2% of it is a fraction of a
     * tick: no pivot is ever within it, every line scores its own two ends, and
     * the ladder climbs to the ceiling on every frame for nothing. Comes from
     * the reference app, where it is written the same way.</p>
     */
    static final double LEAST_TOLERANCE = 5.0;

    /** Two ends plus one more: what makes a line evidence rather than geometry. */
    private static final int ENOUGH_TOUCHES = 3;

    /**
     * How far back the pivots are looked for, in windows.
     *
     * <p>The reference app scans its whole view because the view is one
     * session. Here the chart holds a hundred thousand bars, and the scan runs
     * on the interface thread on every frame. Three windows is what it takes to
     * find the last two pivots of each kind plus the window in front of the
     * older of them; beyond that the line would be anchored so far off the
     * screen that it is no longer the reading being asked for.</p>
     */
    private static final int LOOKBACK = 3;

    /** The support line's colour in the app this came from. */
    private static final Color SUPPORT_INK = new Color(0x06, 0xB6, 0xD4);

    /** And the resistance line's. */
    private static final Color RESISTANCE_INK = new Color(0xD9, 0x46, 0xEF);

    private int period;

    private int wing = TopsAndBottoms.WING;

    private TopsAndBottoms.Ties ties = TopsAndBottoms.Ties.LAST;

    private int toleranceFrom = TOLERANCE_FROM;

    private int toleranceTo = TOLERANCE_TO;

    private int toleranceStep = TOLERANCE_STEP;

    private boolean rails = true;

    private boolean markTouches = true;

    private boolean memory = true;

    private Color supportColour;

    private Color resistanceColour;

    private MovingAverage.Line line = MovingAverage.Line.SOLID;

    private int thickness = 2;

    private boolean visible = true;

    private volatile PriceSeries source = PriceSeries.empty();

    private volatile Drawn drawn;

    /**
     * One trendline, as the numbers it takes to draw it.
     *
     * @param top whether it rests on the tops -- the LTB
     * @param anchor the older end, the one the search chose
     * @param anchorPrice its price
     * @param destination the newer end, the last pivot of the kind
     * @param slope price per bar
     * @param touches how many pivots of the window fall within the tolerance
     * @param error the sum of their distances to the line
     * @param tolerance how far a pivot could be and still count, in price
     * @param percent which rung of the ladder that tolerance came from
     * @param resting the pivots that touch, for the markers
     */
    record Trend(boolean top, int anchor, double anchorPrice, int destination, double slope,
                 int touches, double error, double tolerance, int percent,
                 List<TopsAndBottoms.Pivot> resting) {

        /** @return the line's price at that bar, projected as far as asked */
        double priceAt(int bar) {
            return anchorPrice + slope * (bar - anchor);
        }
    }

    /**
     * What one frame draws.
     *
     * @param support the LTA, from the bottoms, or null when there is none
     * @param resistance the LTB, from the tops
     * @param supportBefore where the LTA stood before the last bottom confirmed
     * @param resistanceBefore the same for the LTB
     * @param projection the last bar in view, which is how far the lines run
     */
    record Drawn(Trend support, Trend resistance, Trend supportBefore,
                 Trend resistanceBefore, int projection) { }

    public TouchTrendlines(int period) {
        setPeriod(period);
    }

    /** @param settings the window, then the wing -- the catalogue's factory */
    public TouchTrendlines(int... settings) {
        this(settings.length > 0 ? settings[0] : PERIOD);

        if (settings.length > 1) {
            setWing(settings[1]);
        }
    }

    public int period() {
        return period;
    }

    public void setPeriod(int value) {
        this.period = Math.max(3, Math.min(value, MOST_BARS));
    }

    public int wing() {
        return wing;
    }

    public void setWing(int value) {
        this.wing = Math.max(1, Math.min(value, TopsAndBottoms.MOST_WING));
    }

    public TopsAndBottoms.Ties ties() {
        return ties;
    }

    public void setTies(TopsAndBottoms.Ties value) {
        this.ties = value == null ? TopsAndBottoms.Ties.LAST : value;
    }

    public int toleranceFrom() {
        return toleranceFrom;
    }

    public void setToleranceFrom(int value) {
        this.toleranceFrom = Math.max(1, Math.min(value, 50));
    }

    public int toleranceTo() {
        return toleranceTo;
    }

    public void setToleranceTo(int value) {
        this.toleranceTo = Math.max(1, Math.min(value, 50));
    }

    public int toleranceStep() {
        return toleranceStep;
    }

    public void setToleranceStep(int value) {
        this.toleranceStep = Math.max(1, Math.min(value, 25));
    }

    public boolean hasRails() {
        return rails;
    }

    public void setRails(boolean value) {
        this.rails = value;
    }

    public boolean marksTouches() {
        return markTouches;
    }

    public void setMarkTouches(boolean value) {
        this.markTouches = value;
    }

    public boolean hasMemory() {
        return memory;
    }

    public void setMemory(boolean value) {
        this.memory = value;
    }

    public Color chosenSupportColour() {
        return supportColour;
    }

    public void setSupportColour(Color value) {
        this.supportColour = value;
    }

    public Color chosenResistanceColour() {
        return resistanceColour;
    }

    public void setResistanceColour(Color value) {
        this.resistanceColour = value;
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

    // ------------------------------------------------------------ the numbers

    /** @return the support line on screen, or null when there is none */
    public Trend support() {
        Drawn now = drawn;

        return now == null ? null : now.support();
    }

    /** @return the resistance line on screen, or null */
    public Trend resistance() {
        Drawn now = drawn;

        return now == null ? null : now.resistance();
    }

    // ------------------------------------------------------------ the working

    /**
     * @param series the bars
     * @param anchor the newest bar that may be looked at
     * @return the pivots of the window, in time order
     *
     * <p>Bounded at both ends: nothing after {@code anchor}, because the bars
     * to its right are not on screen yet, and nothing before the lookback,
     * because scanning a hundred thousand bars for the last two turns costs the
     * whole frame.</p>
     *
     * <p>Package-private for the same reason {@link #fitTo} is: a test can walk
     * the whole path from a series it made itself to the two lines, without a
     * chart, a viewport or a graphics context anywhere in it.</p>
     */
    List<TopsAndBottoms.Pivot> pivotsFor(PriceSeries series, int anchor) {
        int first = Math.max(0, anchor - period * LOOKBACK + 1);

        return TopsAndBottoms.alternating(
                TopsAndBottoms.candidates(series, wing, ties, first, anchor + 1));
    }

    /**
     * Chooses the line of most touches for one kind of pivot.
     *
     * @param pivots the window's pivots, both kinds, in time order
     * @param top true for the line over the tops, false for the one under the
     *        bottoms
     * @param back zero for the current line, one for where it stood before the
     *        most recent turn confirmed
     * @return the line, or null when the window holds fewer than two turns
     *
     * <p>Package-private so a test can hand it pivots it made itself and check
     * the answer against one worked out on paper, with no chart involved.</p>
     */
    Trend fitTo(List<TopsAndBottoms.Pivot> pivots, boolean top, int back) {
        List<TopsAndBottoms.Pivot> same = new ArrayList<>();

        for (TopsAndBottoms.Pivot each : pivots) {
            if (each.top() == top) {
                same.add(each);
            }
        }

        int last = same.size() - 1 - back;

        if (last < 1) {
            // Fewer than two turns of this kind: there is no line to draw, and
            // one point plus a guess is not a trendline.
            return null;
        }

        TopsAndBottoms.Pivot destination = same.get(last);
        int windowStart = destination.bar() - period + 1;

        List<TopsAndBottoms.Pivot> points = new ArrayList<>();

        for (int i = 0; i <= last; i++) {
            if (same.get(i).bar() >= windowStart) {
                points.add(same.get(i));
            }
        }

        if (points.size() < 2) {
            return null;
        }

        double swing = amplitudeOf(points);

        for (int percent = toleranceFrom; percent <= toleranceTo; percent += toleranceStep) {
            double band = Math.max(LEAST_TOLERANCE, swing * percent / 100.0);
            Trend best = bestAnchor(points, top, band, percent);

            // THE THIRD TOUCH is what stops the climb. Two are the line's own
            // ends and prove nothing. At the top of the ladder whatever was
            // found is taken, reported with the tolerance it needed, so the
            // reader can see which lines the market drew and which ones the
            // widening did.
            if (best != null && (best.touches() >= ENOUGH_TOUCHES
                    || percent + toleranceStep > toleranceTo)) {
                return best;
            }
        }

        return null;
    }

    private static double amplitudeOf(List<TopsAndBottoms.Pivot> points) {
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;

        for (TopsAndBottoms.Pivot each : points) {
            low = Math.min(low, each.price());
            high = Math.max(high, each.price());
        }

        return high - low;
    }

    /**
     * @param points the same-kind pivots of the window, oldest first, the
     *        destination last
     * @param band how far a pivot may sit from the line and still count
     * @return the best line ending at the last point, or null
     *
     * <p>Every older pivot is tried as the anchor. The far end never moves, so
     * each candidate is one line and the count is exact -- no angle is swept
     * and no optimiser is run.</p>
     */
    private static Trend bestAnchor(List<TopsAndBottoms.Pivot> points, boolean top,
            double band, int percent) {

        TopsAndBottoms.Pivot destination = points.get(points.size() - 1);
        Trend best = null;

        for (int i = 0; i < points.size() - 1; i++) {
            TopsAndBottoms.Pivot anchor = points.get(i);
            int run = destination.bar() - anchor.bar();

            if (run == 0) {
                continue;
            }

            double slope = (destination.price() - anchor.price()) / run;

            List<TopsAndBottoms.Pivot> resting = new ArrayList<>();
            double error = 0.0;

            for (TopsAndBottoms.Pivot each : points) {
                // ABSOLUTE distance: a trendline does not enclose. See the
                // class javadoc -- a bottom under the support is the break,
                // and a fit that could not express it would hide it.
                double off = Math.abs(each.price()
                        - (anchor.price() + slope * (each.bar() - anchor.bar())));

                if (off <= band) {
                    resting.add(each);
                    error += off;
                }
            }

            Trend made = new Trend(top, anchor.bar(), anchor.price(), destination.bar(),
                    slope, resting.size(), error, band, percent, List.copyOf(resting));

            if (better(made, best)) {
                best = made;
            }
        }

        return best;
    }

    /**
     * @return whether the first line beats the second
     *
     * <p>Most touches; then the smallest total error, because between two lines
     * touched the same number of times the one they sit closer to is the one
     * they were actually respecting; then the OLDEST anchor, because a line
     * that reaches further back has been tested by more of the market.</p>
     */
    private static boolean better(Trend made, Trend than) {
        if (than == null) {
            return true;
        }

        if (made.touches() != than.touches()) {
            return made.touches() > than.touches();
        }

        if (Math.abs(made.error() - than.error()) > 1e-9) {
            return made.error() < than.error();
        }

        return made.anchor() < than.anchor();
    }

    // ------------------------------------------------------------ the contract

    @Override
    public String nameKey() {
        return "overlay.trendlines";
    }

    @Override
    public List<Integer> parameters() {
        return List.of(period, wing);
    }

    @Override
    public boolean fitsOnPrice() {
        // Two lines in points of the index, resting on the turns.
        return true;
    }

    @Override
    public List<Color> colours() {
        // In the order valueAt returns them: the support, then the resistance.
        return List.of(supportColour == null ? SUPPORT_INK : supportColour,
                resistanceColour == null ? RESISTANCE_INK : resistanceColour);
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

        // The lines belong to bars that have just been replaced.
        this.drawn = null;
    }

    /**
     * @param bar an index into the series
     * @return the two lines there, NaN where a line does not reach
     *
     * <p>From the fit the last {@link #paintUnder} made. A line answers from
     * its anchor to the right edge of the window, and the part past its last
     * pivot is projection -- which is the opposite of what
     * {@link TouchChannel} does, and deliberate: a channel carried past its
     * pivots is a forecast nobody asked for, while a trendline carried forward
     * is the whole point of drawing one.</p>
     */
    @Override
    public double[] valueAt(int bar) {
        Drawn now = drawn;

        if (now == null) {
            return new double[]{Double.NaN, Double.NaN};
        }

        return new double[]{priceOn(now.support(), bar, now.projection()),
                priceOn(now.resistance(), bar, now.projection())};
    }

    private static double priceOn(Trend trend, int bar, int projection) {
        if (trend == null || bar < trend.anchor() || bar > Math.max(projection, trend.destination())) {
            return Double.NaN;
        }

        return trend.priceAt(bar);
    }

    /**
     * Fits against what is on screen, then draws everything that is not one of
     * the two lines themselves.
     *
     * <p>The fit happens here because this is the only method handed a
     * {@link Viewport}: the anchor is the last bar in view, and {@code valueAt}
     * is asked immediately afterwards by the same loop.</p>
     *
     * <p>The rails, the memory lines and the touch markers are drawn here and
     * therefore UNDER the two lines. In the app this came from the markers sit
     * on top; the difference shows only where a line crosses its own marker,
     * and adding a paint-over hook to the contract for that would have every
     * other indicator answer a method none of them needs.</p>
     */
    @Override
    public void paintUnder(Graphics2D g, Viewport viewport, int from, int to) {
        PriceSeries series = source;

        int anchor = Math.min(to, series.size()) - 1;

        if (anchor < 1) {
            drawn = null;

            return;
        }

        List<TopsAndBottoms.Pivot> pivots = pivotsFor(series, anchor);

        Drawn made = new Drawn(fitTo(pivots, false, 0), fitTo(pivots, true, 0),
                memory ? fitTo(pivots, false, 1) : null,
                memory ? fitTo(pivots, true, 1) : null, anchor);

        drawn = made;

        paintRails(g, viewport, made.support(), colours().get(0), made.projection());
        paintRails(g, viewport, made.resistance(), colours().get(1), made.projection());

        paintMemory(g, viewport, made.supportBefore(), colours().get(0), made.projection());
        paintMemory(g, viewport, made.resistanceBefore(), colours().get(1), made.projection());

        paintTouches(g, viewport, made.support(), colours().get(0));
        paintTouches(g, viewport, made.resistance(), colours().get(1));
    }

    /**
     * Draws the tolerance the count was made with, as two dotted lines.
     *
     * <p>Without them the tolerance is a number in a dialog. With them the
     * reader sees the band a pivot had to fall inside to have counted, which is
     * what makes "seven touches at 8%" readable as a claim rather than a
     * score.</p>
     */
    private void paintRails(Graphics2D g, Viewport viewport, Trend trend, Color ink,
            int projection) {

        if (!rails || trend == null) {
            return;
        }

        g.setColor(new Color(ink.getRed(), ink.getGreen(), ink.getBlue(), 90));
        g.setStroke(MovingAverage.Line.DOTTED.stroke(1));

        int left = trend.anchor();
        int right = Math.max(projection, trend.destination());

        for (int side = -1; side <= 1; side += 2) {
            g.drawLine(x(viewport, left), y(viewport, trend.priceAt(left) + side * trend.tolerance()),
                    x(viewport, right), y(viewport, trend.priceAt(right) + side * trend.tolerance()));
        }
    }

    /**
     * Draws where the line stood before the most recent turn confirmed.
     *
     * <p>It is the same fit with the far end moved back one pivot, and it is
     * there to answer a question a single line cannot: whether the last turn
     * CHANGED the reading. Two lines on top of each other mean the market
     * confirmed what was already drawn; a wide gap between them means the
     * reading just moved, and by how much.</p>
     */
    private void paintMemory(Graphics2D g, Viewport viewport, Trend trend, Color ink,
            int projection) {

        if (trend == null) {
            return;
        }

        g.setColor(new Color(ink.getRed(), ink.getGreen(), ink.getBlue(), 158));
        g.setStroke(MovingAverage.Line.DASHED.stroke(Math.max(1, thickness - 1)));

        int left = trend.anchor();
        int right = Math.max(projection, trend.destination());

        g.drawLine(x(viewport, left), y(viewport, trend.priceAt(left)),
                x(viewport, right), y(viewport, trend.priceAt(right)));

        g.setStroke(new BasicStroke(1f));

        for (int bar : new int[]{trend.anchor(), trend.destination()}) {
            int px = x(viewport, bar);
            int py = y(viewport, trend.priceAt(bar));

            g.drawRect(px - 3, py - 3, 6, 6);
        }
    }

    /** Rings the pivots that were counted, so the number can be checked by eye. */
    private void paintTouches(Graphics2D g, Viewport viewport, Trend trend, Color ink) {
        if (!markTouches || trend == null) {
            return;
        }

        g.setColor(ink);
        g.setStroke(new BasicStroke(1.5f));

        for (TopsAndBottoms.Pivot each : trend.resting()) {
            int px = x(viewport, each.bar());
            int py = y(viewport, each.price());

            g.drawOval(px - 4, py - 4, 8, 8);
        }
    }

    private static int x(Viewport viewport, int bar) {
        return (int) Math.round(viewport.x(bar));
    }

    private static int y(Viewport viewport, double price) {
        return (int) Math.round(viewport.y(price));
    }

    @Override
    public String appearance() {
        return ties + ";" + toleranceFrom + ";" + toleranceTo + ";" + toleranceStep
                + ";" + line + ";" + thickness
                + ";" + hex(supportColour) + ";" + hex(resistanceColour)
                + ";" + rails + ";" + markTouches + ";" + memory;
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
            setTies(TopsAndBottoms.Ties.STRICT.name().equals(parts[0])
                    ? TopsAndBottoms.Ties.STRICT : TopsAndBottoms.Ties.LAST);
        }

        if (parts.length > 3) {
            setToleranceFrom(number(parts[1], TOLERANCE_FROM));
            setToleranceTo(number(parts[2], TOLERANCE_TO));
            setToleranceStep(number(parts[3], TOLERANCE_STEP));
        }

        if (parts.length > 5) {
            setLine(readLine(parts[4]));
            setThickness(number(parts[5], 2));
        }

        if (parts.length > 7) {
            setSupportColour(readColour(parts[6]));
            setResistanceColour(readColour(parts[7]));
        }

        if (parts.length > 10) {
            setRails(Boolean.parseBoolean(parts[8]));
            setMarkTouches(Boolean.parseBoolean(parts[9]));
            setMemory(Boolean.parseBoolean(parts[10]));
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
