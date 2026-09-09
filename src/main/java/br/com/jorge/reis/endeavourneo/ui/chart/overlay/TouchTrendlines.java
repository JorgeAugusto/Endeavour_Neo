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

import br.com.jorge.reis.endeavourneo.domain.market.Aggregation;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.OwnScale;
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

    /**
     * Where the older end of the line comes from.
     *
     * <p><b>The newer end is never chosen.</b> It is the last pivot of the
     * kind, in every mode. What changes here is only where the line STARTS,
     * and with it the slope -- so these four are four readings of the same
     * question: which old turn is the one the current move is still measured
     * against?</p>
     */
    public enum Anchoring {

        /**
         * Every turn of the window is tried; most touches wins.
         *
         * <p>The only mode that SEARCHES, and therefore the only one where the
         * tolerance ladder decides anything.</p>
         */
        SEARCH,

        /**
         * The highest top, or the lowest bottom, of the window.
         *
         * <p>The classic reading: the resistance comes off the high of the
         * period, whatever it costs in touches. The line may well pass above
         * every later top -- that is the rule doing what it says, not a bad
         * fit.</p>
         */
        EXTREME,

        /**
         * The turn at which the swings last changed sides of a fast average.
         *
         * <p>The window stops deciding the anchor: what decides it is where the
         * move began. That place is ONE event on the chart, and both lines take
         * their own end of it -- the resistance the top there, the support the
         * bottom there. {@link Crossing} says which side of it is taken, and
         * {@code acrossTheAverage} carries the rule and the reason.</p>
         */
        FAST_AVERAGE,

        /**
         * The same rule, against a slow average on a larger scale.
         *
         * <p>Same mechanism as {@link #FAST_AVERAGE}, longer memory: the turns
         * change sides of it far less often, so the anchor lands much further
         * back and the line spans the whole move rather than its last stretch.
         * That is the entire difference between the two modes.</p>
         */
        SLOW_AVERAGE;

        /** @return whether this mode needs an average computed at all */
        public boolean usesAverage() {
            return this == FAST_AVERAGE || this == SLOW_AVERAGE;
        }

        /** @return whether the tolerance ladder still chooses something */
        public boolean searches() {
            return this == SEARCH;
        }
    }

    /**
     * Which side of the crossing the anchor is taken from.
     *
     * <p>The crossing is between two turns, so there are two candidates and no
     * third answer.</p>
     */
    public enum Crossing {

        /**
         * The first turn on the new side of the average.
         *
         * <p>The first top that failed below it, which is where the move being
         * drawn actually starts. <b>The default</b>, and what the line a reader
         * draws by hand usually rests on.</p>
         */
        AFTER,

        /**
         * The last turn on the old side.
         *
         * <p>The peak the market fell away from. The line is born higher and
         * steeper, and often passes above every later top without touching
         * one.</p>
         */
        BEFORE
    }

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

    /** The fast average's period, on the chart's own scale. */
    public static final int FAST_PERIOD = 17;

    /** The slow average's period, on its own scale. */
    public static final int SLOW_PERIOD = 21;

    /** And that scale. */
    public static final String SLOW_SCALE = "5m";

    private int period;

    private Anchoring anchoring = Anchoring.SEARCH;

    private Crossing crossing = Crossing.AFTER;

    private int fastPeriod = FAST_PERIOD;

    private int slowPeriod = SLOW_PERIOD;

    private String slowScale = SLOW_SCALE;

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
     * The average the anchoring mode reads, one value per bar of the series.
     *
     * <p>Computed in {@link #calculate}, not in the paint: it does not depend
     * on the viewport at all -- only the fit does -- and folding a hundred
     * thousand bars to a coarser scale on every frame would be the same
     * mistake the pivot scan already made once.</p>
     */
    private volatile double[] average;

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

    public Anchoring anchoring() {
        return anchoring;
    }

    public void setAnchoring(Anchoring value) {
        this.anchoring = value == null ? Anchoring.SEARCH : value;
    }

    public Crossing crossing() {
        return crossing;
    }

    public void setCrossing(Crossing value) {
        this.crossing = value == null ? Crossing.AFTER : value;
    }

    public int fastPeriod() {
        return fastPeriod;
    }

    public void setFastPeriod(int value) {
        this.fastPeriod = Math.max(2, Math.min(value, 2_000));
    }

    public int slowPeriod() {
        return slowPeriod;
    }

    public void setSlowPeriod(int value) {
        this.slowPeriod = Math.max(2, Math.min(value, 2_000));
    }

    public String slowScale() {
        return slowScale;
    }

    public void setSlowScale(String code) {
        this.slowScale = code == null || code.isBlank() ? SLOW_SCALE : code;
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
        return fitTo(pivots, top, back, null);
    }

    /**
     * @param average one value per bar of the series, or null when the mode
     *        does not read one
     *
     * <p>Handed in rather than read from the field so a test can put an average
     * of its own beside pivots of its own and check the crossing rule on paper,
     * without a series and without a chart.</p>
     */
    Trend fitTo(List<TopsAndBottoms.Pivot> pivots, boolean top, int back, double[] average) {
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
        List<TopsAndBottoms.Pivot> older = same.subList(0, last + 1);

        if (!anchoring.searches()) {
            TopsAndBottoms.Pivot chosen = anchorFor(pivots, older, top, average);

            // THE LADDER DOES NOT RUN HERE, and that is what the modes are.
            // With the anchor fixed the line is already decided by its two
            // ends, so a tolerance that climbs until it finds a third touch
            // would be climbing to change a number that no longer chooses
            // anything. It stays on the first rung, and the count becomes a
            // measure OF the line instead of the reason for it.
            return chosen == null ? null : lineFrom(chosen, destination, older, top);
        }

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

    /**
     * @param older the pivots of the kind, oldest first, the destination last
     * @param average one value per bar, or null
     * @return the anchor the mode picks, or null when it finds none
     */
    private TopsAndBottoms.Pivot anchorFor(List<TopsAndBottoms.Pivot> pivots,
            List<TopsAndBottoms.Pivot> older, boolean top, double[] average) {

        if (anchoring == Anchoring.EXTREME) {
            return extremeOf(older, top);
        }

        return acrossTheAverage(pivots, older, average);
    }

    /** @return the highest top, or the lowest bottom, inside the window */
    private TopsAndBottoms.Pivot extremeOf(List<TopsAndBottoms.Pivot> older, boolean top) {
        TopsAndBottoms.Pivot destination = older.get(older.size() - 1);
        int windowStart = destination.bar() - period + 1;
        TopsAndBottoms.Pivot best = null;

        // THE DESTINATION IS LEFT OUT, and it has to be: it is the other end of
        // the line. When the last turn is itself the extreme of the window
        // there is no line yet, and answering nothing beats drawing a point.
        for (int i = 0; i < older.size() - 1; i++) {
            TopsAndBottoms.Pivot each = older.get(i);

            if (each.bar() >= windowStart
                    && (best == null || (top ? each.price() > best.price()
                            : each.price() < best.price()))) {
                best = each;
            }
        }

        return best;
    }

    /**
     * Finds where the move began, and takes this line's end of it.
     *
     * @param pivots the whole zigzag, both kinds, in time order
     * @param older the pivots of this line's kind, up to its destination
     * @return the anchor, or null when the move's start is not in the search
     *
     * <h2>ONE crossing, read on the whole zigzag, serving BOTH lines</h2>
     *
     * <p>The crossing is a single event on the chart -- the market changed
     * sides of the average -- and the two trendlines take their own end of it:
     * the LTB the top there, the LTA the bottom there. Anything else and a
     * rising market draws only the resistance, because the pullback bottoms
     * dip through a fast average on every leg and their "last change of side"
     * is always a couple of turns old. That is not a hypothetical: it is what
     * the chart did, and it is why this rule was rewritten.</p>
     *
     * <h2>A whole swing on the other side, not one pivot</h2>
     *
     * <p>The move began at the most recent place where <b>two neighbouring
     * pivots -- a top AND its bottom -- both sat on the far side</b> of the
     * average. The pair is what makes the rule mean anything: asking for one
     * pivot on the far side answers "the previous turn" on every chart ever
     * drawn, since in any zigzag the tops sit above their own average and the
     * bottoms below it. A top and a bottom together on one side is the market
     * actually having been there, not a wick poking through.</p>
     */
    private TopsAndBottoms.Pivot acrossTheAverage(List<TopsAndBottoms.Pivot> pivots,
            List<TopsAndBottoms.Pivot> older, double[] average) {

        if (average == null) {
            return null;
        }

        TopsAndBottoms.Pivot destination = older.get(older.size() - 1);
        Boolean here = sideOf(destination, average);

        if (here == null) {
            return null;
        }

        int crossed = crossingBefore(pivots, destination.bar(), here, average);

        if (crossed < 0) {
            return null;
        }

        if (crossing == Crossing.AFTER) {
            for (TopsAndBottoms.Pivot each : older) {
                if (each.bar() > crossed) {
                    // AFTER can land on the DESTINATION itself, when the
                    // crossing is the newest turn there is. Nothing is done
                    // about it here: lineFrom refuses a run of zero bars, and
                    // the same rule written twice is a second chance to write
                    // it wrong.
                    return each;
                }
            }

            return null;
        }

        TopsAndBottoms.Pivot last = null;

        for (TopsAndBottoms.Pivot each : older) {
            if (each.bar() <= crossed) {
                last = each;
            }
        }

        return last;
    }

    /**
     * @param until the destination's bar; nothing after it is read
     * @param here which side the destination is on
     * @return the bar of the newest pivot still on the FAR side, or -1
     *
     * <p>Bounded at the destination so the memory line answers what the rule
     * said one turn ago, rather than what it says now with one end moved
     * back.</p>
     */
    private static int crossingBefore(List<TopsAndBottoms.Pivot> pivots, int until,
            boolean here, double[] average) {

        List<TopsAndBottoms.Pivot> upTo = new ArrayList<>();

        for (TopsAndBottoms.Pivot each : pivots) {
            if (each.bar() <= until) {
                upTo.add(each);
            }
        }

        for (int i = upTo.size() - 1; i > 0; i--) {
            Boolean now = sideOf(upTo.get(i), average);
            Boolean before = sideOf(upTo.get(i - 1), average);

            if (now == null || before == null) {
                // The average does not answer that far back: on a larger scale
                // it is NaN until the first coarse bar has closed. Unknown is
                // not "the same side", so the walk stops rather than inventing
                // a crossing at the edge of what was computed.
                return -1;
            }

            if (now != here && before != here) {
                return upTo.get(i).bar();
            }
        }

        return -1;
    }

    /** @return whether the pivot is above the average there, or null if unknown */
    private static Boolean sideOf(TopsAndBottoms.Pivot pivot, double[] average) {
        if (pivot.bar() < 0 || pivot.bar() >= average.length
                || !Double.isFinite(average[pivot.bar()])) {
            return null;
        }

        return pivot.price() > average[pivot.bar()];
    }

    /**
     * @return the line between two ends already chosen, with its touches counted
     *
     * <p>The modes that do not search still report a touch count, and it is the
     * same count the search would have made -- at the first rung of the ladder,
     * because here it grades the line instead of picking it.</p>
     */
    private Trend lineFrom(TopsAndBottoms.Pivot anchor, TopsAndBottoms.Pivot destination,
            List<TopsAndBottoms.Pivot> older, boolean top) {

        int run = destination.bar() - anchor.bar();

        if (run <= 0) {
            return null;
        }

        double slope = (destination.price() - anchor.price()) / run;

        List<TopsAndBottoms.Pivot> points = new ArrayList<>();

        for (TopsAndBottoms.Pivot each : older) {
            if (each.bar() >= anchor.bar()) {
                points.add(each);
            }
        }

        double band = Math.max(LEAST_TOLERANCE, amplitudeOf(points) * toleranceFrom / 100.0);
        List<TopsAndBottoms.Pivot> resting = new ArrayList<>();
        double error = 0.0;

        for (TopsAndBottoms.Pivot each : points) {
            double off = Math.abs(each.price()
                    - (anchor.price() + slope * (each.bar() - anchor.bar())));

            if (off <= band) {
                resting.add(each);
                error += off;
            }
        }

        return new Trend(top, anchor.bar(), anchor.price(), destination.bar(), slope,
                resting.size(), error, band, toleranceFrom, List.copyOf(resting));
    }

    /**
     * @return the average the anchoring mode reads, or null when it reads none
     *
     * <p>The slow one is folded by {@link OwnScale}, which owns the rule that
     * an indicator on a larger scale reads the last CLOSED coarse bar. Written
     * a second time here it would be a second chance to write it wrong.</p>
     */
    private double[] averageFor(PriceSeries series) {
        if (!anchoring.usesAverage() || series.size() == 0) {
            return null;
        }

        if (anchoring == Anchoring.FAST_AVERAGE) {
            return exponential(series, fastPeriod);
        }

        Aggregation scale = OwnScale.of(slowScale);

        if (scale == null) {
            // A layout naming a scale this version does not build. The chart's
            // own scale is a smaller wrong than an anchoring mode that answers
            // nothing and looks broken.
            return exponential(series, slowPeriod);
        }

        PriceSeries coarse = scale.apply(series);

        if (coarse.size() == 0) {
            return null;
        }

        double[] slow = exponential(coarse, slowPeriod);
        double[] into = new double[series.size()];

        if (ChartPreferences.interpolateOwnScale()) {
            OwnScale.smooth(series, coarse, slow, into);
        } else {
            OwnScale.map(series, coarse, slow, into);
        }

        return into;
    }

    /** @return an exponential average of the closes, one value per bar */
    private static double[] exponential(PriceSeries series, int period) {
        double[] made = new double[series.size()];
        double weight = 2.0 / (period + 1);
        double now = series.closeAt(0);

        for (int i = 0; i < made.length; i++) {
            now = i == 0 ? now : now + weight * (series.closeAt(i) - now);
            made[i] = now;
        }

        return made;
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
        this.average = averageFor(source);

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

        double[] read = average;

        Drawn made = new Drawn(fitTo(pivots, false, 0, read), fitTo(pivots, true, 0, read),
                memory ? fitTo(pivots, false, 1, read) : null,
                memory ? fitTo(pivots, true, 1, read) : null, anchor);

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
                + ";" + rails + ";" + markTouches + ";" + memory
                + ";" + anchoring + ";" + crossing
                + ";" + fastPeriod + ";" + slowPeriod + ";" + slowScale;
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

        // APPENDED, so a layout written before the anchoring mode existed still
        // reads: it stops at eleven fields and keeps the search, which is what
        // it was drawn with.
        if (parts.length > 15) {
            setAnchoring(readAnchoring(parts[11]));
            setCrossing(Crossing.BEFORE.name().equals(parts[12])
                    ? Crossing.BEFORE : Crossing.AFTER);
            setFastPeriod(number(parts[13], FAST_PERIOD));
            setSlowPeriod(number(parts[14], SLOW_PERIOD));
            setSlowScale(parts[15]);
        }
    }

    private static Anchoring readAnchoring(String text) {
        for (Anchoring each : Anchoring.values()) {
            if (each.name().equals(text)) {
                return each;
            }
        }

        return Anchoring.SEARCH;
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
