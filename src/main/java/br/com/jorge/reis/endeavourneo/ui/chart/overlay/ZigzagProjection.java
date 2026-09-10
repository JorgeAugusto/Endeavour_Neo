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

import java.awt.Color;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The leg of the zigzag that crossed the average, projected forward in
 * quarters: 50%, 100%, 150% and 200% of its own size.
 *
 * <h2>Two legs, and only two</h2>
 *
 * <p>One crossing UP and one crossing DOWN -- the most recent of each -- and
 * that is a decision with a measurement behind it. On one session of the real
 * series, <b>28 of the 32 legs of the zigzag crossed the average</b>: in a
 * market moving sideways nearly every leg does, since the tops sit above the
 * average and the bottoms below it. Four lines each would have been a hundred
 * and twelve lines on the chart, which is a grid, not a reading.</p>
 *
 * <h2>Crossing is about SIDES, not about direction</h2>
 *
 * <p>A leg crosses when its two ends sit on opposite sides of the average. That
 * is not the same as rising or falling: a leg from a bottom to a top with both
 * ends above the average never touched it, and it is common in a trend. So the
 * rising projection is the last leg that went from under the average to over
 * it, whatever its ends were called.</p>
 *
 * <h2>Where the ladder starts</h2>
 *
 * <p>The four levels are {@code base + factor * size}, and {@link Measure} says
 * what the base is. From the ORIGIN the ladder starts at the middle of the leg
 * and its 100% falls on the very turn that ended it; from the END it starts
 * half a leg beyond and its 100% is the measured move. <b>They are the same
 * ladder shifted by one step</b> -- 150% from the origin is 50% from the end --
 * so the setting is really about where the reader wants the ladder to
 * begin.</p>
 */
public final class ZigzagProjection implements Overlay {

    /** Where the ladder of levels is measured from. */
    public enum Measure {

        /**
         * From the turn that STARTED the leg.
         *
         * <p>50% is the middle of the leg and 100% is the turn that ended it,
         * so the first two levels describe the move that just happened and the
         * last two extend it.</p>
         */
        ORIGIN,

        /**
         * From the turn that ENDED it.
         *
         * <p>Every level is beyond the move: 100% is the measured move, the leg
         * repeated once from where it stopped.</p>
         */
        END
    }

    /** The four rungs, as fractions of the leg. */
    static final double[] FACTORS = {0.5, 1.0, 1.5, 2.0};

    /** The average's period. */
    public static final int PERIOD = 17;

    /** The zigzag's wing. */
    public static final int WING = 2;

    /**
     * How far back the legs are looked for, in bars.
     *
     * <p>Ten hours of one-minute bars. The two legs wanted are almost always
     * within the last few minutes -- nearly every leg crosses -- and scanning a
     * hundred thousand bars for them on every recalculation is the cost this
     * bound exists to refuse. When neither leg is in there, nothing is drawn,
     * which is the honest answer to a chart that has not crossed its average in
     * ten hours.</p>
     */
    private static final int LOOKBACK = 600;

    private static final Color RISING_INK = new Color(0x2E, 0xA0, 0x43);

    private static final Color FALLING_INK = new Color(0xE0, 0x4F, 0x4F);

    private int period;

    private String scale;

    private int wing = WING;

    private TopsAndBottoms.Ties ties = TopsAndBottoms.Ties.LAST;

    private Measure measure = Measure.ORIGIN;

    private Color risingColour;

    private Color fallingColour;

    private MovingAverage.Line line = MovingAverage.Line.DASHED;

    private int thickness = 1;

    private boolean visible = true;

    private volatile Legs legs = new Legs(null, null);

    /**
     * One projected leg, as the numbers it takes to draw it.
     *
     * @param start the bar the lines begin at -- the turn that ended the leg
     * @param size the leg in price, signed
     * @param levels the four prices, in the order of {@link #FACTORS}
     */
    record Leg(int start, double size, double[] levels) { }

    /** The two legs on screen: the last one crossing up, the last crossing down. */
    record Legs(Leg rising, Leg falling) { }

    public ZigzagProjection(int period) {
        setPeriod(period);
    }

    /** @param settings the average's period, then the wing -- the catalogue's factory */
    public ZigzagProjection(int... settings) {
        this(settings.length > 0 ? settings[0] : PERIOD);

        if (settings.length > 1) {
            setWing(settings[1]);
        }
    }

    public int period() {
        return period;
    }

    public void setPeriod(int value) {
        this.period = Math.max(2, Math.min(value, 2_000));
    }

    /** @return the code of the scale the AVERAGE is computed on, or null */
    public String scale() {
        return scale;
    }

    public void setScale(String code) {
        this.scale = code == null || code.isBlank() ? null : code;
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

    public Measure measure() {
        return measure;
    }

    public void setMeasure(Measure value) {
        this.measure = value == null ? Measure.ORIGIN : value;
    }

    public Color chosenRisingColour() {
        return risingColour;
    }

    public void setRisingColour(Color value) {
        this.risingColour = value;
    }

    public Color chosenFallingColour() {
        return fallingColour;
    }

    public void setFallingColour(Color value) {
        this.fallingColour = value;
    }

    public MovingAverage.Line line() {
        return line;
    }

    public void setLine(MovingAverage.Line value) {
        this.line = value == null ? MovingAverage.Line.DASHED : value;
    }

    public int thickness() {
        return thickness;
    }

    public void setThickness(int value) {
        this.thickness = Math.max(1, Math.min(value, 8));
    }

    // ------------------------------------------------------------ the numbers

    /** @return the last leg that crossed upwards, or null */
    public Leg rising() {
        return legs.rising();
    }

    /** @return the last leg that crossed downwards, or null */
    public Leg falling() {
        return legs.falling();
    }

    // ------------------------------------------------------------ the working

    /**
     * Finds the two legs and works out their levels.
     *
     * @param pivots the zigzag, in time order
     * @param average one value per bar, or null
     * @return the two legs, either of them possibly null
     *
     * <p>Package-private so a test can hand it pivots and an average of its own
     * and check the four numbers against a sum done on paper.</p>
     */
    Legs legsIn(List<TopsAndBottoms.Pivot> pivots, double[] average) {
        if (average == null) {
            return new Legs(null, null);
        }

        Leg up = null;
        Leg down = null;

        // BACKWARDS, and it stops as soon as it has one of each: the two wanted
        // are the most recent, and in a market moving sideways they are both
        // within the last few legs.
        for (int i = pivots.size() - 1; i > 0 && (up == null || down == null); i--) {
            TopsAndBottoms.Pivot from = pivots.get(i - 1);
            TopsAndBottoms.Pivot to = pivots.get(i);

            Boolean was = sideOf(from, average);
            Boolean now = sideOf(to, average);

            if (was == null || now == null || was.equals(now)) {
                continue;
            }

            if (now && up == null) {
                up = legOf(from, to);
            } else if (!now && down == null) {
                down = legOf(from, to);
            }
        }

        return new Legs(up, down);
    }

    /** @return whether that turn is above the average there, or null if unknown */
    private static Boolean sideOf(TopsAndBottoms.Pivot pivot, double[] average) {
        if (pivot.bar() < 0 || pivot.bar() >= average.length
                || !Double.isFinite(average[pivot.bar()])) {
            return null;
        }

        return pivot.price() > average[pivot.bar()];
    }

    private Leg legOf(TopsAndBottoms.Pivot from, TopsAndBottoms.Pivot to) {
        double size = to.price() - from.price();
        double base = measure == Measure.ORIGIN ? from.price() : to.price();
        double[] levels = new double[FACTORS.length];

        for (int i = 0; i < FACTORS.length; i++) {
            levels[i] = base + FACTORS[i] * size;
        }

        // THE LINES START AT THE TURN THAT ENDED THE LEG, not at the one that
        // began it: before that turn existed the level was not a level, and a
        // line drawn back over bars that could not have known it reads as a
        // level the market already respected.
        return new Leg(to.bar(), size, levels);
    }

    // ------------------------------------------------------------ the contract

    @Override
    public String nameKey() {
        return "overlay.projection";
    }

    @Override
    public List<Integer> parameters() {
        return List.of(period, wing);
    }

    // ownPeriod IS NOT OVERRIDDEN, and that is deliberate. The scale here
    // belongs to the AVERAGE this indicator reads, not to the indicator: the
    // zigzag, the legs and the levels are all on the chart's own bars. Saying
    // "5m" beside the name would claim the whole reading is on five minutes,
    // which would be a lie in the one place a reader trusts.

    @Override
    public boolean fitsOnPrice() {
        // Eight prices of the index, drawn as horizontal lines.
        return true;
    }

    @Override
    public List<Color> colours() {
        List<Color> made = new ArrayList<>();
        Color up = risingColour == null ? RISING_INK : risingColour;
        Color down = fallingColour == null ? FALLING_INK : fallingColour;

        for (int i = 0; i < FACTORS.length; i++) {
            made.add(up);
        }

        for (int i = 0; i < FACTORS.length; i++) {
            made.add(down);
        }

        return List.copyOf(made);
    }

    @Override
    public Stroke stroke() {
        return line.stroke(thickness);
    }

    /**
     * @return one pen per level, with the 100% picked out
     *
     * <p>The 100% is the level the other three are measured against -- the leg
     * itself -- so it is drawn solid and a shade thicker while the rest stay on
     * the reader's chosen dash. Eight lines that all look the same are eight
     * lines nobody can tell apart at a glance.</p>
     */
    @Override
    public List<Stroke> strokes() {
        List<Stroke> made = new ArrayList<>();

        for (int side = 0; side < 2; side++) {
            for (double factor : FACTORS) {
                made.add(factor == 1.0
                        ? MovingAverage.Line.SOLID.stroke(thickness + 1f)
                        : line.stroke(thickness));
            }
        }

        return List.copyOf(made);
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
     * Works out both legs, once per change of data.
     *
     * <p>Everything here is a function of the series and the settings, and none
     * of it of the viewport: the levels are prices, and a price does not move
     * when the chart is scrolled. So the whole calculation happens here and
     * {@link #valueAt} only reads it -- unlike the trendlines, whose fit
     * depends on which bar is the last one in view.</p>
     */
    @Override
    public void calculate(PriceSeries series) {
        PriceSeries source = series == null ? PriceSeries.empty() : series;
        double[] average = Averages.over(source, period, scale);

        if (average == null) {
            this.legs = new Legs(null, null);

            return;
        }

        int from = Math.max(0, source.size() - LOOKBACK);

        this.legs = legsIn(TopsAndBottoms.alternating(
                TopsAndBottoms.candidates(source, wing, ties, from, source.size())), average);
    }

    /**
     * @param bar an index into the series
     * @return the eight levels, NaN before the turn that made each of them
     *
     * <p>Four for the leg that crossed up and four for the one that crossed
     * down, in the order of {@link #FACTORS}. The chart draws each as a line of
     * its own, so a level that is NaN up to its turn and constant after it
     * comes out as a horizontal line starting exactly there.</p>
     */
    @Override
    public double[] valueAt(int bar) {
        Legs now = legs;
        double[] row = new double[2 * FACTORS.length];

        Arrays.fill(row, Double.NaN);

        put(row, 0, now.rising(), bar);
        put(row, FACTORS.length, now.falling(), bar);

        return row;
    }

    private static void put(double[] row, int at, Leg leg, int bar) {
        if (leg == null || bar < leg.start()) {
            return;
        }

        System.arraycopy(leg.levels(), 0, row, at, FACTORS.length);
    }

    @Override
    public String appearance() {
        return ties + ";" + measure + ";" + line + ";" + thickness
                + ";" + hex(risingColour) + ";" + hex(fallingColour)
                + ";" + (scale == null ? "" : scale);
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

        if (parts.length > 1) {
            setTies(TopsAndBottoms.Ties.STRICT.name().equals(parts[0])
                    ? TopsAndBottoms.Ties.STRICT : TopsAndBottoms.Ties.LAST);
            setMeasure(Measure.END.name().equals(parts[1]) ? Measure.END : Measure.ORIGIN);
        }

        if (parts.length > 3) {
            setLine(readLine(parts[2]));
            setThickness(number(parts[3], 1));
        }

        if (parts.length > 5) {
            setRisingColour(readColour(parts[4]));
            setFallingColour(readColour(parts[5]));
        }

        if (parts.length > 6) {
            setScale(parts[6]);
        }
    }

    private static MovingAverage.Line readLine(String text) {
        for (MovingAverage.Line each : MovingAverage.Line.values()) {
            if (each.name().equals(text)) {
                return each;
            }
        }

        return MovingAverage.Line.DASHED;
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
