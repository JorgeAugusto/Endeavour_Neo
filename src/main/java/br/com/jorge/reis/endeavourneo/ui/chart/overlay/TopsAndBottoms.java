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
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.OwnScale;

import java.awt.Color;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tops and bottoms: the alternating zigzag, in two steps.
 *
 * <h2>Step one — the candidate, a symmetric fractal</h2>
 *
 * <p>For a wing of {@code N} candles each side, bar {@code i} is a candidate
 * TOP when its high is the highest of the {@code 2N+1} bars from {@code i-N} to
 * {@code i+N}, and a candidate BOTTOM when its low is the lowest of the same
 * stretch.</p>
 *
 * <p><b>It can only be said once the N bars after it have closed</b>, so the
 * candidate at bar {@code i} is first knowable at bar {@code i+N}. That lag is
 * inherent to the method — it is the same one the reference product's own
 * detector has, and the same the other platform's pivot high/low has, each with
 * its own wing. What this class does about it is refuse to draw past the last
 * confirmed pivot: see {@link #calculate}.</p>
 *
 * <p>On its own this step <b>does not alternate</b>. Nothing stops two tops in
 * a row with no confirmed bottom between them.</p>
 *
 * <h2>Step two — reduction by alternation, which is what makes it a zigzag</h2>
 *
 * <p>Walking the raw candidates in time order: one of the SAME kind as the last
 * accepted pivot only replaces it when it is more extreme — a higher top, a
 * lower bottom — and does not start a new leg. One of a DIFFERENT kind is
 * accepted, and the leg before it closes.</p>
 *
 * <p><b>Without this step the drawing lies.</b> Joining the raw candidates in
 * sequence puts a leg between two consecutive tops that corresponds to no
 * reversal in the price at all: it is an artefact of drawing in order. One
 * platform's native zigzag does this alternation by definition; the reference
 * product's detector and the other's pivot high/low do not, and need this on
 * top.</p>
 */
public final class TopsAndBottoms implements Overlay {

    /**
     * What counts as "the highest" when two bars of the window are level.
     *
     * <p><b>The two implementations this was brought from disagree here</b>, and
     * the difference is not a corner case. On the mini index the tick is five
     * points, so equal highs on neighbouring minutes are ordinary: measured over
     * 5.074 minutes of the exported tape, 4,8% of bars have the same high as the
     * one before, and the two rules produce 1.093 against 1.220 candidate tops —
     * 127 more, about a tenth again.</p>
     */
    public enum Ties {

        /**
         * The bar has to beat BOTH neighbours outright.
         *
         * <p>What the definition reads as, taken strictly, and what the Python
         * side of the project does. A plateau of equal highs produces no top at
         * all: no single bar of it is the highest.</p>
         *
         * <p>Not the default: see {@link #LAST}.</p>
         */
        STRICT,

        /**
         * The LAST bar of a plateau takes it.
         *
         * <p>What the version that runs in the reference product does: {@code
         * >=} against the older neighbour and {@code >} against the newer. Which
         * is to say a bar may tie with its past and must beat its future — and
         * on a run of equal highs only the final bar beats the one after it, so
         * the pivot lands at the END of the plateau.</p>
         *
         * <p><b>The end, and it reads like the beginning.</b> The rule is
         * written as "greater-or-equal to the one before", which sounds like it
         * lets the first bar in; what it actually does is let every bar of the
         * plateau THROUGH that test, and then the strict test against the newer
         * neighbour throws all of them out but the last. Worth the paragraph:
         * the first reading of it here was wrong, and the test caught it.</p>
         *
         * <p>It is also the answer that survives the reduction better. The
         * plateau's last bar is where the price stopped being flat, which is
         * where the leg turns.</p>
         *
         * <p><b>And it is the default.</b> The purpose of the indicators on
         * this chart is reading them beside the reference product's, so where
         * the two rules disagree the one that agrees with the product wins --
         * a difference of a tenth of the pivots is a difference the eye finds
         * immediately, and finding it every time is not conferring anything.
         * The strict rule stays one click away for whoever wants the literal
         * reading of the definition.</p>
         */
        LAST
    }

    /**
     * One accepted pivot.
     *
     * @param bar where it sits
     * @param top whether it is a top; a bottom otherwise
     * @param price the high of a top, the low of a bottom
     */
    public record Pivot(int bar, boolean top, double price) { }

    /** The wing the app this came from opens with. */
    public static final int WING = 1;

    /** As far out as a wing may be asked to go. */
    public static final int MOST_WING = 60;

    /** Cyan, which is what the version in the reference product draws. */
    private static final Color INK = new Color(0x00, 0xFF, 0xFF);

    private int wing;

    private Ties ties = Ties.LAST;

    private Color colour;

    private MovingAverage.Line line = MovingAverage.Line.SOLID;

    private int thickness = 2;

    private boolean visible = true;

    /**
     * The scale the pivots are found on, or null to use the chart's.
     *
     * <p>A zigzag of the hour drawn over a chart of minutes: the turns are the
     * hour's, and they land on the minute that actually made each extreme. See
     * {@link #onOwnScale}.</p>
     */
    private String ownPeriod;

    /**
     * The zigzag as one value per bar, interpolated along each leg.
     *
     * <p>A line and not a list of points, because that is what the chart draws:
     * one value per bar, joined. Between two pivots the value walks straight
     * from one to the other, which is the leg; outside the pivots it is NaN, and
     * the chart breaks the line there rather than joining across.</p>
     *
     * <p>Replaced whole, never edited in place: a background recalculation can
     * arrive between two reads of the painting.</p>
     */
    private volatile double[] zigzag = new double[0];

    private volatile List<Pivot> pivots = List.of();

    public TopsAndBottoms(int wing) {
        setWing(wing);
    }

    /** @param settings the wing, if anything — the catalogue's factory */
    public TopsAndBottoms(int... settings) {
        this(settings.length > 0 ? settings[0] : WING);
    }

    public int wing() {
        return wing;
    }

    /**
     * @param value candles each side, from one to {@value #MOST_WING}
     *
     * <p>Held rather than refused, which is what every indicator here does with
     * a number out of range: a stored layout is the reader's work.</p>
     */
    public void setWing(int value) {
        this.wing = Math.max(1, Math.min(value, MOST_WING));
    }

    public Ties ties() {
        return ties;
    }

    public void setTies(Ties value) {
        this.ties = value == null ? Ties.LAST : value;
    }

    public Color chosenColour() {
        return colour;
    }

    public void setColour(Color value) {
        this.colour = value;
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

    /** @return the pivots as they stand, oldest first, in the CHART's bars */
    public List<Pivot> pivots() {
        return pivots;
    }

    @Override
    public String ownPeriod() {
        return ownPeriod;
    }

    public void setOwnPeriod(String code) {
        this.ownPeriod = code == null || code.isBlank() ? null : code;
    }

    // ------------------------------------------------------------ the working

    /**
     * @param series the bars to look through
     * @param wing candles each side
     * @param ties what to do when two bars are level
     * @return the raw candidates in time order, before any alternation
     *
     * <p>Static and taking everything it needs, so a test can check it against a
     * hand-counted stretch without a chart, and so step one can be shown to be
     * the thing that does NOT alternate.</p>
     */
    static List<Pivot> candidates(PriceSeries series, int wing, Ties ties) {
        return candidates(series, wing, ties, series == null ? 0 : series.size());
    }

    /**
     * @param until how many bars of the series may be looked at
     *
     * <p><b>A bound and not a filter.</b> On its own scale the last folded bar
     * may still be forming -- a fifteen-minute bar read three minutes in has
     * the high of three minutes, not fifteen -- and it is not enough to drop
     * pivots found there: the WINDOW of the bar before it reads that bar too.
     * The bound stops the reading, which is the only thing that stops it.</p>
     */
    static List<Pivot> candidates(PriceSeries series, int wing, Ties ties, int until) {
        List<Pivot> found = new ArrayList<>();

        if (series == null) {
            return found;
        }

        // Stops `wing` short of the end, which is the lag: the bar at n-wing has
        // no full right-hand window yet, so nothing about it can be said.
        for (int i = wing; i < Math.min(until, series.size()) - wing; i++) {
            if (isTop(series, i, wing, ties)) {
                found.add(new Pivot(i, true, series.highAt(i)));
            }

            // BOTH are asked, and a bar can answer both: one bar between two
            // higher and two lower neighbours is impossible, but a flat stretch
            // under LAST makes its closing bar the top AND the bottom of it.
            // Step two keeps whichever alternates, which is the right answer --
            // dropping one here would decide it without the context.
            if (isBottom(series, i, wing, ties)) {
                found.add(new Pivot(i, false, series.lowAt(i)));
            }
        }

        return found;
    }

    private static boolean isTop(PriceSeries series, int at, int wing, Ties ties) {
        double mine = series.highAt(at);

        for (int i = at - wing; i <= at + wing; i++) {
            if (i == at) {
                continue;
            }

            double other = series.highAt(i);

            // The older side is where the two rules part: FIRST lets the bar
            // tie with what came before it and still be the top, so a plateau
            // is marked at its opening bar rather than not at all.
            boolean beaten = i < at && ties == Ties.LAST ? other > mine : other >= mine;

            if (beaten) {
                return false;
            }
        }

        return true;
    }

    private static boolean isBottom(PriceSeries series, int at, int wing, Ties ties) {
        double mine = series.lowAt(at);

        for (int i = at - wing; i <= at + wing; i++) {
            if (i == at) {
                continue;
            }

            double other = series.lowAt(i);
            boolean beaten = i < at && ties == Ties.LAST ? other < mine : other <= mine;

            if (beaten) {
                return false;
            }
        }

        return true;
    }

    /**
     * @param raw the candidates, in time order
     * @return the pivots that alternate, oldest first
     *
     * <p>Step two, on its own so it can be tested on its own: hand it two tops
     * in a row and it must answer one.</p>
     */
    static List<Pivot> alternating(List<Pivot> raw) {
        List<Pivot> kept = new ArrayList<>();

        for (Pivot each : raw) {
            if (kept.isEmpty() || kept.get(kept.size() - 1).top() != each.top()) {
                kept.add(each);

                continue;
            }

            Pivot last = kept.get(kept.size() - 1);

            // SAME KIND: the more extreme one wins and the leg does not open.
            // Not ">=": a later bar level with the pivot is not more extreme,
            // and moving the pivot forward onto it would slide the leg's end
            // for no reason the price gave.
            boolean better = each.top() ? each.price() > last.price()
                    : each.price() < last.price();

            if (better) {
                kept.set(kept.size() - 1, each);
            }
        }

        return kept;
    }

    // ------------------------------------------------------------ the contract

    @Override
    public String nameKey() {
        return "overlay.pivots";
    }

    @Override
    public List<Integer> parameters() {
        return List.of(wing);
    }

    @Override
    public boolean fitsOnPrice() {
        // It IS a price: every pivot is the high or the low of a bar.
        return true;
    }

    @Override
    public List<Color> colours() {
        return List.of(colour == null ? INK : colour);
    }

    @Override
    public Stroke stroke() {
        return line.stroke(thickness);
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void setVisible(boolean value) {
        this.visible = value;
    }

    /**
     * Finds the pivots and lays the zigzag out as one value per bar.
     *
     * <p><b>Nothing is drawn past the last pivot.</b> The bars after it have no
     * confirmed turn in them yet — that is what the wing's lag means — and
     * running the line on to the last bar would draw a leg towards a pivot that
     * has not happened. It is the same refusal every indicator here makes during
     * its warm-up, at the other end.</p>
     */
    @Override
    public void calculate(PriceSeries series) {
        int size = series == null ? 0 : series.size();
        double[] built = new double[size];

        Arrays.fill(built, Double.NaN);

        Aggregation scale = ownPeriod == null ? null : OwnScale.of(ownPeriod);

        // Null when the code names a scale this version does not build, and the
        // answer to that is the chart's own -- a smaller wrong than an
        // indicator that is listed and invisible. OwnScale.of says so.
        List<Pivot> found = scale == null
                ? alternating(candidates(series, wing, ties))
                : onOwnScale(series, scale);

        for (int i = 1; i < found.size(); i++) {
            Pivot from = found.get(i - 1);
            Pivot to = found.get(i);

            int span = to.bar() - from.bar();

            for (int bar = from.bar(); bar <= to.bar() && bar < size; bar++) {
                built[bar] = span <= 0 ? to.price()
                        : from.price()
                                + (to.price() - from.price()) * (bar - from.bar()) / (double) span;
            }
        }

        this.pivots = List.copyOf(found);
        this.zigzag = built;
    }

    /**
     * Finds the turns of a LARGER scale and puts them on this chart's bars.
     *
     * <p><b>The vertex lands on the bar that actually made the extreme.</b> A
     * folded bar's high happened at one particular minute inside it, and that
     * minute is where the reader's eye goes -- putting the vertex at the start
     * or the end of the folded bar instead would draw a turn at a price no bar
     * there traded at. So the fine bars of each folded bar are walked and the
     * first one carrying that exact high (or low) takes it.</p>
     *
     * <p><b>The last folded bar is left out</b>, and that is the rule this
     * whole area exists for: it may still be forming, and a fifteen-minute bar
     * read three minutes in has the high of three minutes. Reading it would let
     * a turn appear on the chart before the market had made it. The cost is one
     * folded bar of lag at the right edge, which is the same cost {@code
     * OwnScale} pays everywhere else and for the same reason.</p>
     *
     * <p>{@code OwnScale.map} is not used here, and could not be: it spreads
     * ONE value per folded bar across the fine bars, and a leg is two points
     * and the straight line between them. What is shared is the rule, not the
     * arithmetic -- and the rule is written down in that class.</p>
     */
    private List<Pivot> onOwnScale(PriceSeries fine, Aggregation scale) {
        PriceSeries coarse = scale.apply(fine);

        List<Pivot> turns = alternating(
                candidates(coarse, wing, ties, coarse.size() - 1));

        List<Pivot> placed = new ArrayList<>(turns.size());

        // A running pointer over the fine bars, not a search per pivot: both
        // series are chronological, so the answer only ever moves forward.
        int at = 0;

        for (Pivot each : turns) {
            long starts = coarse.timeAt(each.bar());
            long ends = each.bar() + 1 < coarse.size()
                    ? coarse.timeAt(each.bar() + 1) : Long.MAX_VALUE;

            while (at < fine.size() && fine.timeAt(at) < starts) {
                at++;
            }

            int landed = -1;

            for (int i = at; i < fine.size() && fine.timeAt(i) < ends; i++) {
                double mine = each.top() ? fine.highAt(i) : fine.lowAt(i);

                if (mine == each.price()) {
                    landed = i;

                    break;
                }
            }

            if (landed < 0) {
                // The fold made that extreme out of something this series does
                // not show bar for bar. The opening bar is where the folded bar
                // begins, and is the only honest answer left.
                landed = Math.min(at, fine.size() - 1);
            }

            if (landed >= 0 && (placed.isEmpty()
                    || placed.get(placed.size() - 1).bar() < landed)) {

                placed.add(new Pivot(landed, each.top(), each.price()));
            }
        }

        return placed;
    }

    @Override
    public double[] valueAt(int bar) {
        // Read ONCE into a local: a background recalculation replaces the array
        // whole, and checking the length of one while reading from another is
        // how that swap shows up -- an index out of bounds on the painting
        // thread, at a moment nobody can reproduce.
        double[] now = zigzag;

        return new double[]{bar >= 0 && bar < now.length ? now[bar] : Double.NaN};
    }

    @Override
    public String appearance() {
        return ties + ";" + line + ";" + thickness + ";"
                + (colour == null ? "auto" : Integer.toHexString(colour.getRGB() & 0xFFFFFF))
                + ";" + (ownPeriod == null ? "chart" : ownPeriod);
    }

    @Override
    public void applyAppearance(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String[] parts = text.split(";", -1);

        // Field by field, each guarded on its own: a line written by a later
        // version has more of them and one by an earlier version fewer, and
        // neither is a reason to lose the rest of the appearance.
        if (parts.length > 0) {
            setTies(Ties.STRICT.name().equals(parts[0]) ? Ties.STRICT : Ties.LAST);
        }

        if (parts.length > 2) {
            setLine(readLine(parts[1]));
            setThickness(readNumber(parts[2]));
        }

        if (parts.length > 3) {
            setColour(readColour(parts[3]));
        }

        if (parts.length > 4) {
            setOwnPeriod("chart".equals(parts[4]) ? null : parts[4]);
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

    private static int readNumber(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            // A hand-edited line. The default beats losing the appearance.
            return 2;
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
