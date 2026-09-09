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
package br.com.jorge.reis.endeavourneo.ui.chart.study.rsi;

import br.com.jorge.reis.endeavourneo.domain.market.Aggregation;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.OwnScale;
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;

import java.awt.Color;
import java.awt.Stroke;
import java.util.Arrays;
import java.util.List;

/**
 * How much of the recent movement was upward, as a percentage.
 *
 * <h2>What it measures</h2>
 *
 * <p>Every bar closes above or below the one before it. The rises are averaged
 * and so are the falls, and the indicator is where the rises sit between the
 * two: a hundred when nothing fell in the whole window, nought when nothing
 * rose, fifty when they balance. It is not a measure of direction — it is a
 * measure of how <i>one-sided</i> the last few bars were.</p>
 *
 * <h2>The two ways of averaging</h2>
 *
 * <p>The reference product offers both, and they are not the same indicator
 * wearing different clothes:</p>
 *
 * <ul>
 * <li><b>{@link Smoothing#CLASSIC}</b> is what Wilder published: each new bar
 * moves the average by a fraction of one period, so <b>every bar ever seen
 * still counts</b>, a little. It is smoother, it lags more, and it never
 * jumps when an old bar falls out of the window — because none ever does.</li>
 * <li><b>{@link Smoothing#SIMPLE}</b> averages exactly the last N bars. It
 * reacts faster, and it moves twice for one event: once when a big bar
 * arrives, and again N bars later when it leaves.</li>
 * </ul>
 *
 * <p>Classic is the default because it is the original and it is what almost
 * every other program means by "RSI"; a chart here and a chart elsewhere
 * showing different lines under the same name would be a bug nobody could
 * find.</p>
 *
 * <h2>A window with no falls, and one with nothing at all</h2>
 *
 * <p>When nothing fell, the ratio divides by zero and the answer is a hundred
 * — that is the definition working, not failing. When nothing MOVED, both
 * averages are nought and there is no answer at all: the previous value is
 * carried, and fifty is used when there is no previous one. Nought would say
 * "everything fell" and a hundred "everything rose", and on a dead minute
 * neither happened.</p>
 *
 * <h2>Bounded on purpose</h2>
 *
 * <p>Nought to a hundred, always, and never fitted to what is on screen — the
 * same reason as the stochastic. An indicator whose meaning is "how one-sided,
 * out of everything it could have been" cannot have its own ceiling rescaled
 * to the loudest thing this week.</p>
 *
 * <p>That fixed range is also what lets it share a panel with a stochastic:
 * two indicators agree on an axis when their range is fixed and equal, which
 * is the rule in {@code StudyStack.fits}.</p>
 */
public final class RelativeStrength implements Overlay {

    /** The default this program opens with, matching the reference product. */
    public static final int PERIOD = 9;

    /** How the rises and falls are averaged. */
    public enum Smoothing {

        /** Wilder's: every bar ever seen still counts, a little. */
        CLASSIC,

        /** The plain mean of the last N. */
        SIMPLE
    }

    private int period;

    private Smoothing smoothing = Smoothing.CLASSIC;

    private Color colour = new Color(0xD9822B);

    private MovingAverage.Line line = MovingAverage.Line.SOLID;

    private float width = 1.4f;

    /** The scale it is computed on, or null for the chart's own. */
    private String ownPeriod;

    // The per-indicator switch is gone, and with it three copies of one
    // question. Whether a coarser scale is drawn in steps or sloped between
    // them is a property of the CHART, not of the average that happens to be on
    // it -- two indicators on the same chart answering it differently is not a
    // thing anybody wants, and it was three dialogs to change one mind.
    //
    // ChartPreferences.interpolateOwnScale, and it is off by default: the
    // reference product draws these as a staircase.

    private boolean visible = true;

    private volatile double[] values = new double[0];

    public RelativeStrength() {
        this(PERIOD);
    }

    public RelativeStrength(int period) {
        setPeriod(period);
    }

    // ------------------------------------------------------------ what it is

    public int period() {
        return period;
    }

    public void setPeriod(int value) {
        period = Math.max(1, value);
    }

    public Smoothing smoothing() {
        return smoothing;
    }

    public void setSmoothing(Smoothing value) {
        smoothing = value == null ? Smoothing.CLASSIC : value;
    }

    @Override
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

    public MovingAverage.Line line() {
        return line;
    }

    public void setLine(MovingAverage.Line value) {
        line = value == null ? line : value;
    }

    public float width() {
        return width;
    }

    public void setWidth(float value) {
        width = Math.max(0.5f, value);
    }

    // ------------------------------------------------------------- the study

    @Override
    public String nameKey() {
        return "study.rsi";
    }

    @Override
    public boolean fitsOnPrice() {
        // Nought to a hundred, like the stochastic. On the price axis it would
        // be a flat line along the floor of the chart.
        return false;
    }

    @Override
    public List<Integer> parameters() {
        return List.of(period);
    }

    @Override
    public List<Color> colours() {
        return List.of(colour);
    }

    @Override
    public List<Stroke> strokes() {
        return List.of(line.stroke(width));
    }

    @Override
    public Stroke stroke() {
        return line.stroke(width);
    }

    @Override
    public double[] bounds() {
        return new double[]{0.0, 100.0};
    }

    @Override
    public double[] valueAt(int bar) {
        // Read ONCE into locals. A background recalculation replaces these
        // fields whole, and checking the length of one array while reading from
        // another is how that swap would show: an index out of bounds, on the
        // painting thread, at a moment nobody can reproduce.
        double[] now = values;

        return bar < 0 || bar >= now.length
                ? new double[]{Double.NaN}
                : new double[]{now[bar]};
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void setVisible(boolean value) {
        visible = value;
    }

    /**
     * Everything that is not the period, as one line.
     *
     * <p>The period travels apart, in {@link #parameters()}, because that is
     * what the indicator IS and the rest is how it is drawn -- the split
     * {@link Overlay#appearance()} exists to make.</p>
     */
    @Override
    public String appearance() {
        return smoothing + ";" + line + ";" + hex(colour) + ";" + width
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
        setSmoothing(readEnum(Smoothing.class, at(fields, 0), Smoothing.CLASSIC));
        setLine(readEnum(MovingAverage.Line.class, at(fields, 1), MovingAverage.Line.SOLID));
        setColour(readColour(at(fields, 2), colour));
        setWidth(readFloat(at(fields, 3), width));
        // AN OLDER LAYOUT put this indicator's own "interpolate" here, between
        // the line and the scale, and the setting has moved to the chart's
        // preferences. A stored line still has it, so the scale is one field
        // further along -- and the two shapes tell themselves apart: only the
        // old one has "true" or "false" in this slot, because a scale code
        // never reads like that.
        boolean older = "true".equals(at(fields, 4)) || "false".equals(at(fields, 4));

        String scale = at(fields, older ? 5 : 4);

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

    private static boolean readBoolean(String text, boolean fallback) {
        return text == null ? fallback : Boolean.parseBoolean(text);
    }

    // ----------------------------------------------------------- the numbers

    @Override
    public void calculate(PriceSeries series) {
        int size = series == null ? 0 : series.size();
        double[] built = new double[size];

        if (size > 0) {
            build(series, built);
        }

        // PUBLISHED WHOLE, at the end. The fields used to be assigned the empty
        // arrays first and filled in place, which is invisible while everything
        // happens on the interface thread -- and this is now called off it, so
        // a repaint landing halfway through would have read an array half full
        // of zeros.
        this.values = built;
    }

    private void build(PriceSeries series, double[] values) {
        Aggregation scale = OwnScale.of(ownPeriod);

        if (scale == null) {
            // On the chart's own scale, which is also the answer when a layout
            // names a scale this version does not build.
            computeOver(series, values);

            return;
        }

        PriceSeries coarse = scale.apply(series);

        if (coarse.size() == 0) {
            Arrays.fill(values, Double.NaN);

            return;
        }

        double[] over = new double[coarse.size()];

        computeOver(coarse, over);

        // The last CLOSED coarse bar, never the one containing this one. See
        // OwnScale, where the rule and the reason live.
        OwnScale.map(series, coarse, over, values);

        if (br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.interpolateOwnScale()) {
            OwnScale.smooth(series, coarse, over, values);
        }
    }

    /**
     * @param bars whatever scale is being read
     * @param into one value per bar of it
     */
    private void computeOver(PriceSeries bars, double[] into) {
        int size = bars.size();

        Arrays.fill(into, Double.NaN);

        if (size <= period) {
            // Not enough closes for even one window. Every bar is unknown,
            // which is what NaN says and what the pane skips over.
            return;
        }

        double[] rises = new double[size];
        double[] falls = new double[size];

        for (int i = 1; i < size; i++) {
            double change = bars.closeAt(i) - bars.closeAt(i - 1);

            rises[i] = Math.max(change, 0.0);
            falls[i] = Math.max(-change, 0.0);
        }

        // The seed is the plain mean of the first window, for BOTH kinds. That
        // is how Wilder starts too: his smoothing needs a previous value, and
        // the first one has to come from somewhere.
        double up = 0.0;
        double down = 0.0;

        for (int i = 1; i <= period; i++) {
            up += rises[i];
            down += falls[i];
        }

        up /= period;
        down /= period;

        double carried = 50.0;

        into[period] = carried = reading(up, down, carried);

        for (int i = period + 1; i < size; i++) {
            if (smoothing == Smoothing.CLASSIC) {
                // Every bar ever seen still counts, a little, and none ever
                // leaves the window because there is no window.
                up = (up * (period - 1) + rises[i]) / period;
                down = (down * (period - 1) + falls[i]) / period;
            } else {
                up = mean(rises, i);
                down = mean(falls, i);
            }

            into[i] = carried = reading(up, down, carried);
        }
    }

    /** @return the plain mean of the last {@code period} entries ending at {@code i} */
    private double mean(double[] of, int i) {
        double total = 0.0;

        for (int back = 0; back < period; back++) {
            total += of[i - back];
        }

        return total / period;
    }

    /**
     * @param up the average rise
     * @param down the average fall
     * @param carried the last real reading, for a window where nothing moved
     * @return the indicator between nought and a hundred
     */
    private static double reading(double up, double down, double carried) {
        if (down <= 0.0) {
            // Nothing fell. A hundred is the definition working, not failing --
            // unless nothing rose either, in which case nothing happened at all
            // and the honest answer is the one from before.
            return up <= 0.0 ? carried : 100.0;
        }

        return 100.0 - 100.0 / (1.0 + up / down);
    }
}
