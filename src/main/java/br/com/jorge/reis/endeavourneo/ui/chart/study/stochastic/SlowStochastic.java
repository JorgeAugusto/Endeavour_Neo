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
package br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic;

import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.OwnScale;
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;

import br.com.jorge.reis.endeavourneo.domain.market.Aggregation;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.awt.Color;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where the close sits inside the range of the last few bars, smoothed.
 *
 * <h2>The two lines</h2>
 *
 * <p>The raw ratio -- <b>fast %K</b> -- is where today's close falls between
 * the lowest low and the highest high of the period, as a percentage. It is too
 * jumpy to read: one bar with a wide range moves it thirty points and nothing
 * happened. So it is averaged, and <b>that average is the indicator</b>: this
 * is what "slow" means. The second line is the same average applied again, and
 * it is what a crossing is measured against.</p>
 *
 * <p>Both use the one {@link #average()} setting, which is what the reference
 * product's dialog offers and what makes its picture reproduce: its own second
 * line is a three-period average dropped on top of a three-period stochastic.
 * Two separate periods would be a knob nobody turns.</p>
 *
 * <h2>A flat window</h2>
 *
 * <p>When the highest high equals the lowest low -- a quiet minute where price
 * did not move at all -- the ratio divides by zero. <b>The previous value is
 * carried</b>, and fifty is used when there is no previous one. Zero would say
 * "at the bottom of the range" and a hundred "at the top", and neither is true:
 * there was no range. Carrying says the only honest thing, which is that
 * nothing changed.</p>
 *
 * <h2>Bounded on purpose</h2>
 *
 * <p>Zero to a hundred, always, and never fitted to what is on screen. An
 * indicator whose whole meaning is "how near the top of its range" cannot have
 * its own top rescaled to the loudest thing this week; eighty has to look like
 * eighty on a dead Tuesday.</p>
 */
public final class SlowStochastic implements Overlay {

    /** The default this program opens with. */
    public static final int PERIOD = 8;

    /** And the smoothing, which is what makes it slow. */
    public static final int AVERAGE = 3;

    private int period;

    private int average;

    private MovingAverage.Kind kind = MovingAverage.Kind.ARITHMETIC;

    private boolean showAverage = true;

    private boolean showLevels = true;

    private double buy = 20.0;

    private double sell = 80.0;

    private Color colour = new Color(0xE07B39);

    private Color averageColour = new Color(0x3FA85C);

    private Color buyColour = new Color(0x7C8B99);

    private Color sellColour = new Color(0x7C8B99);

    private MovingAverage.Line line = MovingAverage.Line.SOLID;

    private MovingAverage.Line averageLine = MovingAverage.Line.SOLID;

    private MovingAverage.Line levelLine = MovingAverage.Line.DASHED;

    private float width = 1.4f;

    private float averageWidth = 1.4f;

    private float levelWidth = 1.0f;

    /** The scale it is computed on, or null for the chart's own. */
    private String ownPeriod;

    private boolean visible = true;

    private volatile double[] slow = new double[0];

    private volatile double[] signal = new double[0];

    public SlowStochastic() {
        this(PERIOD, AVERAGE);
    }

    public SlowStochastic(int period, int average) {
        setPeriod(period);
        setAverage(average);
    }

    // ------------------------------------------------------------ what it is

    public int period() {
        return period;
    }

    public void setPeriod(int value) {
        period = Math.max(1, value);
    }

    public int average() {
        return average;
    }

    public void setAverage(int value) {
        average = Math.max(1, value);
    }

    public MovingAverage.Kind kind() {
        return kind;
    }

    public void setKind(MovingAverage.Kind value) {
        kind = value == null ? MovingAverage.Kind.ARITHMETIC : value;
    }

    public boolean showsAverage() {
        return showAverage;
    }

    public void setShowsAverage(boolean value) {
        showAverage = value;
    }

    public boolean showsLevels() {
        return showLevels;
    }

    public void setShowsLevels(boolean value) {
        showLevels = value;
    }

    public double buyLevel() {
        return buy;
    }

    public void setBuyLevel(double value) {
        buy = value;
    }

    public double sellLevel() {
        return sell;
    }

    public void setSellLevel(double value) {
        sell = value;
    }

    public String ownPeriod() {
        return ownPeriod;
    }

    public void setOwnPeriod(String code) {
        ownPeriod = code;
    }

    // ---------------------------------------------------------- how it looks

    public Color colour() {
        return colour;
    }

    public void setColour(Color value) {
        colour = value == null ? colour : value;
    }

    public Color averageColour() {
        return averageColour;
    }

    public void setAverageColour(Color value) {
        averageColour = value == null ? averageColour : value;
    }

    public Color buyColour() {
        return buyColour;
    }

    public void setBuyColour(Color value) {
        buyColour = value == null ? buyColour : value;
    }

    public Color sellColour() {
        return sellColour;
    }

    public void setSellColour(Color value) {
        sellColour = value == null ? sellColour : value;
    }

    public MovingAverage.Line line() {
        return line;
    }

    public void setLine(MovingAverage.Line value) {
        line = value == null ? line : value;
    }

    public MovingAverage.Line averageLine() {
        return averageLine;
    }

    public void setAverageLine(MovingAverage.Line value) {
        averageLine = value == null ? averageLine : value;
    }

    public MovingAverage.Line levelLine() {
        return levelLine;
    }

    public void setLevelLine(MovingAverage.Line value) {
        levelLine = value == null ? levelLine : value;
    }

    public float width() {
        return width;
    }

    public void setWidth(float value) {
        width = Math.max(0.5f, value);
    }

    public float averageWidth() {
        return averageWidth;
    }

    public void setAverageWidth(float value) {
        averageWidth = Math.max(0.5f, value);
    }

    public float levelWidth() {
        return levelWidth;
    }

    public void setLevelWidth(float value) {
        levelWidth = Math.max(0.5f, value);
    }

    // ------------------------------------------------------------ the study

    @Override
    public String nameKey() {
        return "study.stochastic";
    }

    @Override
    public boolean fitsOnPrice() {
        // Nought to a hundred. On the price axis of the mini index it would be
        // a flat line along the floor of the chart -- drawn, listed, and
        // saying nothing.
        return false;
    }

    /**
     * @return both periods, always
     *
     * <p>Even when the average is not drawn. These are what a layout STORES to
     * rebuild this indicator, and a list that shrank when a line was hidden
     * took the average's period with it -- turn the average off, save, reopen,
     * and it came back as three however it had been set. Caught by the
     * round-trip test, which is what that test is for.</p>
     *
     * <p>Showing both in the legend is also the honest reading: the setting
     * exists whether or not the line does.</p>
     */
    @Override
    public List<Integer> parameters() {
        return List.of(period, average);
    }

    @Override
    public List<Color> colours() {
        return showAverage ? List.of(colour, averageColour) : List.of(colour);
    }

    @Override
    public List<Stroke> strokes() {
        return showAverage
                ? List.of(line.stroke(width), averageLine.stroke(averageWidth))
                : List.of(line.stroke(width));
    }

    @Override
    public double[] bounds() {
        return new double[]{0.0, 100.0};
    }

    @Override
    public List<Level> levels() {
        if (!showLevels) {
            return List.of();
        }

        Stroke drawn = levelLine.stroke(levelWidth);

        return List.of(new Level(buy, buyColour, drawn), new Level(sell, sellColour, drawn));
    }

    @Override
    public double[] valueAt(int bar) {
        // Read ONCE into locals. A background recalculation replaces these
        // fields whole, and checking the length of one array while reading from
        // another is how that swap would show: an index out of bounds, on the
        // painting thread, at a moment nobody can reproduce.
        double[] nowSlow = slow;
        double[] nowSignal = signal;

        if (bar < 0 || bar >= nowSlow.length || bar >= nowSignal.length) {
            return showAverage ? new double[]{Double.NaN, Double.NaN}
                    : new double[]{Double.NaN};
        }

        return showAverage ? new double[]{nowSlow[bar], nowSignal[bar]}
                : new double[]{nowSlow[bar]};
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void setVisible(boolean value) {
        visible = value;
    }

    @Override
    public Stroke stroke() {
        return line.stroke(width);
    }

    /**
     * Everything that is not the two periods, as one line.
     *
     * <p>The periods travel apart, in {@link #parameters()}, because those are
     * what the indicator IS and the rest is how it is drawn -- the split
     * {@link Overlay#appearance()} exists to make. A pane restored in the right
     * place wearing the wrong colours would be a workspace that only half
     * worked.</p>
     */
    @Override
    public String appearance() {
        return kind + ";" + line + ";" + hex(colour) + ";" + width
                + ";" + averageLine + ";" + hex(averageColour) + ";" + averageWidth
                + ";" + levelLine + ";" + hex(buyColour) + ";" + levelWidth
                + ";" + showAverage + ";" + showLevels + ";" + buy + ";" + sell
                + ";" + (ownPeriod == null ? "chart" : ownPeriod);
    }

    @Override
    public void applyAppearance(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String[] fields = text.split(";");

        // Field by field, each guarded on its own. A line written by a later
        // version may carry a value this one cannot read, and losing the colour
        // because of it would be a second failure caused by the first.
        setKind(readEnum(MovingAverage.Kind.class, at(fields, 0), MovingAverage.Kind.ARITHMETIC));
        setLine(readEnum(MovingAverage.Line.class, at(fields, 1), MovingAverage.Line.SOLID));
        setColour(readColour(at(fields, 2), colour));
        setWidth(readFloat(at(fields, 3), width));

        setAverageLine(readEnum(MovingAverage.Line.class, at(fields, 4),
                MovingAverage.Line.SOLID));
        setAverageColour(readColour(at(fields, 5), averageColour));
        setAverageWidth(readFloat(at(fields, 6), averageWidth));

        setLevelLine(readEnum(MovingAverage.Line.class, at(fields, 7),
                MovingAverage.Line.DASHED));
        setBuyColour(readColour(at(fields, 8), buyColour));
        setSellColour(buyColour);
        setLevelWidth(readFloat(at(fields, 9), levelWidth));

        setShowsAverage(readBoolean(at(fields, 10), showAverage));
        setShowsLevels(readBoolean(at(fields, 11), showLevels));
        setBuyLevel(readDouble(at(fields, 12), buy));
        setSellLevel(readDouble(at(fields, 13), sell));

        String scale = at(fields, 14);

        setOwnPeriod(scale == null || "chart".equals(scale) ? null : scale);
    }

    private static String at(String[] fields, int index) {
        return index < fields.length ? fields[index].trim() : null;
    }

    private static String hex(Color value) {
        return Integer.toHexString(value.getRGB() & 0xFFFFFF);
    }

    private static <E extends Enum<E>> E readEnum(Class<E> type, String text, E fallback) {
        if (text == null) {
            return fallback;
        }

        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static Color readColour(String text, Color fallback) {
        if (text == null) {
            return fallback;
        }

        try {
            return new Color(Integer.parseInt(text, 16));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float readFloat(String text, float fallback) {
        try {
            return text == null ? fallback : Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double readDouble(String text, double fallback) {
        try {
            return text == null ? fallback : Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean readBoolean(String text, boolean fallback) {
        return text == null ? fallback : Boolean.parseBoolean(text);
    }

    // ----------------------------------------------------------- the numbers

    @Override
    public void calculate(PriceSeries series) {
        int size = series == null ? 0 : series.size();
        double[] builtSlow = new double[size];
        double[] builtSignal = new double[size];

        if (size > 0) {
            build(series, builtSlow, builtSignal);
        }

        // PUBLISHED WHOLE, at the end. The fields used to be assigned the empty
        // arrays first and filled in place, which is invisible while everything
        // happens on the interface thread -- and this is now called off it, so
        // a repaint landing halfway through would have read an array half full
        // of zeros.
        this.slow = builtSlow;
        this.signal = builtSignal;
    }

    private void build(PriceSeries series, double[] slow, double[] signal) {
        Aggregation scale = OwnScale.of(ownPeriod);

        if (scale == null) {
            // On the chart's own scale, which is also the answer when a layout
            // names a scale this version does not build.
            computeOver(series, slow, signal);

            return;
        }

        PriceSeries coarse = scale.apply(series);

        if (coarse.size() == 0) {
            Arrays.fill(slow, Double.NaN);
            Arrays.fill(signal, Double.NaN);

            return;
        }

        double[] coarseSlow = new double[coarse.size()];
        double[] coarseSignal = new double[coarse.size()];

        computeOver(coarse, coarseSlow, coarseSignal);

        // The last CLOSED coarse bar, never the one containing this one. See
        // OwnScale, where the rule and the reason live.
        OwnScale.map(series, coarse, coarseSlow, slow);
        OwnScale.map(series, coarse, coarseSignal, signal);
    }

    private void computeOver(PriceSeries bars, double[] intoSlow, double[] intoSignal) {
        double[] fast = new double[bars.size()];
        double carried = 50.0;

        for (int i = 0; i < bars.size(); i++) {
            if (i < period - 1) {
                // NaN, never zero: a period of eight has nothing to say at bar
                // three, and zero would be a claim -- plotted, it drags the line
                // along the floor until the first real value.
                fast[i] = Double.NaN;

                continue;
            }

            double lowest = bars.lowAt(i);
            double highest = bars.highAt(i);

            for (int back = i - period + 1; back <= i; back++) {
                lowest = Math.min(lowest, bars.lowAt(back));
                highest = Math.max(highest, bars.highAt(back));
            }

            double span = highest - lowest;

            // A window where price never moved. See the class documentation:
            // the previous value is carried, because there was no range to be
            // at the top or the bottom of.
            carried = span <= 0.0 ? carried
                    : 100.0 * (bars.closeAt(i) - lowest) / span;
            fast[i] = carried;
        }

        smooth(fast, intoSlow);
        smooth(intoSlow, intoSignal);
    }

    /**
     * Averages one line into another, over {@link #average()} points.
     *
     * <p>Warm-up stays NaN and is not counted: an average of three that met two
     * numbers is an average of two wearing the wrong name.</p>
     */
    private void smooth(double[] from, double[] into) {
        // A RING OF PRIMITIVES, not a List<Double>. This runs twice per
        // calculate, over every bar: on the real source that was 1,65 million
        // Double objects boxed and thrown away per recalculation, plus a
        // remove(0) shifting the list each time. Measured before: 319 ms to
        // recalculate one stochastic, against 5 ms for a moving average over the
        // same bars -- and this method was the whole of the difference. The
        // recalculation happens on the interface thread, in five places, once
        // per indicator in the panel.
        int span = Math.max(1, average);
        double[] window = new double[span];
        int held = 0;
        int next = 0;
        double sum = 0.0;
        double previous = Double.NaN;
        double weight = 2.0 / (average + 1.0);

        for (int i = 0; i < from.length; i++) {
            if (Double.isNaN(from[i])) {
                into[i] = Double.NaN;
                held = 0;
                next = 0;
                sum = 0.0;
                previous = Double.NaN;

                continue;
            }

            if (held == span) {
                // Full: the oldest is where the next one goes.
                sum -= window[next];
            } else {
                held++;
            }

            window[next] = from[i];
            sum += from[i];
            next = (next + 1) % span;

            if (held < average) {
                into[i] = Double.NaN;

                continue;
            }

            if (kind == MovingAverage.Kind.EXPONENTIAL) {
                // AFTER the window, not before it, and seeded with that
                // window's arithmetic mean. This branch used to sit at the top
                // of the loop: it wrote a value on the very first finite point,
                // seeded from that one number, so the stochastic began drawing
                // `average` bars earlier than the javadoc right above says it
                // does, with a hook at the left edge -- and the signal line,
                // being the smoothing of this one, inherited the hook.
                //
                // Three behaviours for one idea, in one project: this, the
                // arithmetic branch below (which does wait), and
                // MovingAverage.exponential, whose own comment says why the
                // seed is the first window and not the first price. This is now
                // the same as that one, number for number.
                previous = Double.isNaN(previous) ? sum / average
                        : from[i] * weight + previous * (1.0 - weight);
                into[i] = previous;

                continue;
            }

            if (kind == MovingAverage.Kind.WEIGHTED) {
                double total = 0.0;
                double divisor = 0.0;

                // Oldest first, which when the ring is full is where the next
                // write would land. The weight rises with age towards the
                // present, exactly as it did over the list.
                for (int at = 0; at < held; at++) {
                    total += window[(next + at) % span] * (at + 1);
                    divisor += at + 1;
                }

                into[i] = total / divisor;

                continue;
            }

            into[i] = sum / average;
        }
    }
}
