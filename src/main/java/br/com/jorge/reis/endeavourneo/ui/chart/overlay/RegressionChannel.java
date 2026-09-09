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
import java.util.List;

/**
 * The linear regression channel: a straight line through the last window, and
 * two edges opened from the residuals around it.
 *
 * <h2>What it is, and what it is not</h2>
 *
 * <p>Least squares over the {@code period} closes that end at one bar. The line
 * is the trend; the edges say how far the price usually strays from it.</p>
 *
 * <p><b>The difference from Bollinger is the centre.</b> Bollinger is centred on
 * an average, which is horizontal by nature: in a strong trend the price sits on
 * the upper band the whole way and "stretched" stops meaning anything. Here the
 * centre has a slope, and the distance is measured against the TREND rather
 * than against the level.</p>
 *
 * <p><b>And it is not the drawing tool of the same name.</b> That one is dragged
 * over a stretch and fits a single line across the whole of it, so every bar in
 * the middle is defined by bars that had not happened yet. This looks only
 * backwards from its anchor. Which is the standing rule for everything brought
 * over from the reference product: where it ships a drawing tool and an
 * indicator, the indicator is what gets ported.</p>
 *
 * <h2>Where it is anchored, and why that is not a setting</h2>
 *
 * <p><b>On the last bar in VIEW</b>, not on the last bar of the series.
 * Scrolling back shows the channel as it was at that moment, which is what the
 * source indicator's {@code DeslocarCandles} parameter did one step at a time --
 * here it is continuous and costs nothing, because the fit is redone from the
 * viewport on every frame and a fit is {@code period} additions.</p>
 *
 * <p>That is why this is the one indicator here whose numbers depend on where
 * the chart is. {@link #paintUnder} does the fit -- it is the only method that
 * is handed a {@link Viewport} -- and {@link #valueAt} reads what it produced.
 * The order is guaranteed by the contract: both the price chart and a study
 * pane paint what is underneath before they draw the lines.</p>
 */
public final class RegressionChannel implements Overlay {

    /** How the edges are measured. */
    public enum Width {

        /**
         * Edges at N standard deviations of the residuals.
         *
         * <p>What the reference product's own channel does, and what its
         * {@code UsarDesvioPadrao} selects when it is on. Symmetric: the same
         * distance above and below.</p>
         */
        DEVIATION,

        /**
         * Edges at the most extreme residual on each side.
         *
         * <p>Guarantees that no close in the window fell outside the channel,
         * and is therefore always wider than the deviation criterion -- the
         * maximum almost always passes two sigmas. Asymmetric: each side has
         * its own extreme.</p>
         */
        EXTREME
    }

    /** The window's ceiling, as in the indicator this came from. */
    public static final int MOST_BARS = 400;

    /** What it is fitted over when nobody says. */
    public static final int PERIOD = 90;

    /** The centre line, in the colour the source draws it. */
    private static final Color CENTRE = new Color(0x00, 0xC8, 0xFF);

    /** The edges, in the source's colour for the main pair. */
    private static final Color EDGE = new Color(0xFF, 0xFF, 0x00);

    private int period;

    private double deviations = 2.0;

    private Width width = Width.DEVIATION;

    private Color colour;

    private MovingAverage.Line line = MovingAverage.Line.SOLID;

    private int thickness = 1;

    private Color centreColour;

    private MovingAverage.Line centreLine = MovingAverage.Line.SOLID;

    private int centreThickness = 1;

    private boolean fill;

    private int opacity = 12;

    private boolean visible = true;

    /**
     * The series to fit over, put here by {@link #calculate}.
     *
     * <p>The series itself and not a computed array, which is what every other
     * indicator here keeps. It cannot be an array: what is drawn depends on
     * where the chart is, so there is nothing to compute until the frame is
     * being painted. Volatile because {@code calculate} runs off the interface
     * thread and the painting reads it.</p>
     */
    private volatile PriceSeries source = PriceSeries.empty();

    /**
     * The last fit, as five numbers: first bar, anchor bar, the line's value at
     * each end, and the two half-widths.
     *
     * <p>Replaced whole, never edited in place, for the reason the arrays in
     * {@link BollingerBands} are: the painting reads it and a recalculation can
     * arrive between two reads. Null until the first frame.</p>
     */
    private volatile Fit fit;

    /**
     * One fit of the line over one window.
     *
     * @param first the oldest bar in the window
     * @param anchor the newest, the one it was fitted to
     * @param oldest the line's value at {@code first}
     * @param newest its value at {@code anchor}
     * @param above how far the upper edge sits over the line
     * @param below how far the lower edge sits under it
     * @param slope price per bar, positive when it rises
     * @param rSquared how much of the movement the line explains, nought to one
     */
    record Fit(int first, int anchor, double oldest, double newest,
               double above, double below, double slope, double rSquared) {

        /** @return the line's value at that bar, extended beyond the window */
        double centreAt(int bar) {
            int span = anchor - first;

            if (span <= 0) {
                return newest;
            }

            return oldest + (newest - oldest) * (bar - first) / (double) span;
        }
    }

    public RegressionChannel(int period) {
        setPeriod(period);
    }

    /**
     * @param settings what the catalogue hands over: the period, if anything
     *
     * <p>The varargs constructor the catalogue's factory needs. One number,
     * like the bands: the deviations are a setting and not a parameter, because
     * 1,75 does not survive a list of integers.</p>
     */
    public RegressionChannel(int... settings) {
        this(settings.length > 0 ? settings[0] : PERIOD);
    }

    public int period() {
        return period;
    }

    /**
     * @param value bars in the window, from two to {@value #MOST_BARS}
     *
     * <p>Held rather than refused, which is what every other indicator here
     * does with a number out of range: a stored layout is the reader's work,
     * and a period written by another version is not a reason to lose the
     * chart.</p>
     */
    public void setPeriod(int value) {
        this.period = Math.max(2, Math.min(value, MOST_BARS));
    }

    public double deviations() {
        return deviations;
    }

    public void setDeviations(double value) {
        this.deviations = Double.isFinite(value) ? Math.max(0.1, Math.min(value, 10.0)) : 2.0;
    }

    public Width width() {
        return width;
    }

    public void setWidth(Width value) {
        this.width = value == null ? Width.DEVIATION : value;
    }

    public Color chosenColour() {
        return colour;
    }

    public void setColour(Color value) {
        this.colour = value;
    }

    public Color centreColour() {
        return centreColour;
    }

    public void setCentreColour(Color value) {
        this.centreColour = value;
    }

    public MovingAverage.Line line() {
        return line;
    }

    public void setLine(MovingAverage.Line value) {
        this.line = value == null ? MovingAverage.Line.SOLID : value;
    }

    public MovingAverage.Line centreLine() {
        return centreLine;
    }

    public void setCentreLine(MovingAverage.Line value) {
        this.centreLine = value == null ? MovingAverage.Line.SOLID : value;
    }

    public int thickness() {
        return thickness;
    }

    public void setThickness(int value) {
        this.thickness = Math.max(1, Math.min(value, 8));
    }

    public int centreThickness() {
        return centreThickness;
    }

    public void setCentreThickness(int value) {
        this.centreThickness = Math.max(1, Math.min(value, 8));
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

    /**
     * @return the slope in price per bar, positive when it rises, or NaN
     *
     * <p>Of the fit that is on screen, like everything else this answers.</p>
     */
    public double slope() {
        Fit now = fit;

        return now == null ? Double.NaN : now.slope();
    }

    /**
     * @return the slope as a fraction of the price across the whole window
     *
     * <p>The form without a scale: "the trend pays so many per cent over
     * {@code period} bars" means the same at 120.000 points as at 5, which the
     * raw slope does not. It is this form a network would be fed.</p>
     */
    public double normalisedSlope() {
        Fit now = fit;

        if (now == null || now.newest() == 0.0) {
            return Double.NaN;
        }

        return now.slope() * period / now.newest();
    }

    /**
     * @return how much of the movement the line explains, nought to one, or NaN
     *
     * <p><b>Near zero the channel is drawn over noise.</b> The line exists
     * because least squares always returns one, not because there is a trend.
     * Ignoring that is the classic mistake with this indicator, and it is why
     * this number belongs beside the slope wherever the slope is shown.</p>
     */
    public double rSquared() {
        Fit now = fit;

        return now == null ? Double.NaN : now.rSquared();
    }

    /**
     * Fits the line over the window that ends at {@code anchor}.
     *
     * @param series what to fit over
     * @param anchor the newest bar of the window
     * @return the fit, or null when there is not that much history
     *
     * <p>Package-private and static: it takes everything it needs and keeps
     * nothing, so a test can check the arithmetic against a hand computation
     * without a chart, and two threads fitting at once cannot meet.</p>
     */
    Fit fitAt(PriceSeries series, int anchor) {
        if (series == null || anchor < period - 1 || anchor >= series.size()) {
            return null;
        }

        int first = anchor - period + 1;

        // X GROWS WITH TIME: nought at the oldest bar of the window, period-1
        // at the anchor. That is what makes the slope come out with the right
        // sign -- positive when it rises -- without having to be turned round
        // afterwards.
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXy = 0.0;
        double sumSquaredX = 0.0;

        for (int i = 0; i < period; i++) {
            double y = series.closeAt(first + i);

            sumX += i;
            sumY += y;
            sumXy += i * y;
            sumSquaredX += (double) i * i;
        }

        double denominator = period * sumSquaredX - sumX * sumX;
        double gradient = denominator == 0.0 ? 0.0 : (period * sumXy - sumX * sumY) / denominator;

        double intercept = (sumY - gradient * sumX) / period;
        double mean = sumY / period;

        double residualSum = 0.0;
        double totalSum = 0.0;
        double over = 0.0;
        double under = 0.0;

        for (int i = 0; i < period; i++) {
            double y = series.closeAt(first + i);
            double residual = y - (intercept + gradient * i);

            residualSum += residual * residual;
            totalSum += (y - mean) * (y - mean);

            over = Math.max(over, residual);
            under = Math.max(under, -residual);
        }

        if (width == Width.DEVIATION) {
            // Divided by n and not by n-1: the window IS the thing being
            // described, not a sample drawn from something larger. The same
            // choice the bands beside this make, and the same reason.
            double sigma = Math.sqrt(residualSum / period);

            over = sigma;
            under = sigma;
        }

        return new Fit(first, anchor, intercept, intercept + gradient * (period - 1),
                over * deviations, under * deviations, gradient,
                totalSum <= 0.0 ? 0.0 : 1.0 - residualSum / totalSum);
    }

    // ------------------------------------------------------------ the contract

    @Override
    public String nameKey() {
        return "overlay.regression";
    }

    @Override
    public List<Integer> parameters() {
        return List.of(period);
    }

    @Override
    public boolean fitsOnPrice() {
        // Three straight lines in points of the index. There is nowhere else
        // they could go.
        return true;
    }

    @Override
    public List<Color> colours() {
        Color edge = colour == null ? EDGE : colour;

        // In the order valueAt returns them, which colours() everywhere here
        // promises: upper, centre, lower.
        return List.of(edge, centreColour == null ? CENTRE : centreColour, edge);
    }

    @Override
    public Stroke stroke() {
        return line.stroke(thickness);
    }

    @Override
    public List<Stroke> strokes() {
        return List.of(line.stroke(thickness),
                centreLine.stroke(centreThickness),
                line.stroke(thickness));
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

        // AND THE FIT IS DROPPED. It belongs to bars that have just been
        // replaced; keeping it would draw the old channel over the new series
        // for exactly one frame, which is the kind of thing nobody reproduces.
        this.fit = null;
    }

    /**
     * @param bar an index into the series
     * @return upper, centre and lower there, or three NaN outside the window
     *
     * <p>From the fit the last {@link #paintUnder} made, which is the fit that
     * is on screen. Outside the window it answers NaN rather than extending the
     * line: a straight line carried past the bars it was fitted to is a
     * forecast, and this indicator does not make one.</p>
     */
    @Override
    public double[] valueAt(int bar) {
        Fit now = fit;

        if (now == null || bar < now.first() || bar > now.anchor()) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN};
        }

        double centre = now.centreAt(bar);

        return new double[]{centre + now.above(), centre, centre - now.below()};
    }

    /**
     * Fits against what is on screen, and shades the channel if asked.
     *
     * <p><b>The fit happens here</b>, and this is the only place it can: it is
     * the one method handed a {@link Viewport}, and the anchor is the last bar
     * in view. {@code valueAt} is asked immediately afterwards, by the same
     * loop, for every visible bar -- the contract draws what is underneath
     * before the lines, on the price chart and in a study pane alike.</p>
     *
     * <p>One fit per frame, not one per bar. It is {@code period} additions,
     * ninety of them by default, against the {@code period} multiplications a
     * single moving-average bar costs -- so a channel is cheaper per frame than
     * the average drawn beside it.</p>
     */
    @Override
    public void paintUnder(Graphics2D g, Viewport viewport, int from, int to) {
        PriceSeries series = source;

        // The last bar in VIEW, held inside the series: `to` is one past the
        // last visible bar and the view can run past the end of the data, which
        // is what the right margin IS.
        int anchor = Math.min(to, series.size()) - 1;

        Fit made = fitAt(series, anchor);

        fit = made;

        if (made == null || !fill || opacity <= 0) {
            return;
        }

        Color base = colour == null ? EDGE : colour;

        g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.round(255 * opacity / 100f)));

        Polygon shape = new Polygon();

        shape.addPoint(x(viewport, made.first()), y(viewport, made.oldest() + made.above()));
        shape.addPoint(x(viewport, made.anchor()), y(viewport, made.newest() + made.above()));
        shape.addPoint(x(viewport, made.anchor()), y(viewport, made.newest() - made.below()));
        shape.addPoint(x(viewport, made.first()), y(viewport, made.oldest() - made.below()));

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
        return width + ";" + deviations + ";" + line + ";" + thickness + ";"
                + (colour == null ? "auto" : Integer.toHexString(colour.getRGB() & 0xFFFFFF))
                + ";" + centreLine + ";" + centreThickness
                + ";" + (centreColour == null ? "auto"
                        : Integer.toHexString(centreColour.getRGB() & 0xFFFFFF))
                + ";" + fill + ";" + opacity;
    }

    @Override
    public void applyAppearance(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String[] parts = text.split(";", -1);

        // Read one at a time and each guarded on its own: a line written by a
        // later version has more fields, and one written by an earlier one has
        // fewer. Neither is a reason to lose the rest of the appearance.
        if (parts.length > 0) {
            setWidth(readWidth(parts[0]));
        }

        if (parts.length > 1) {
            setDeviations(readNumber(parts[1], 2.0));
        }

        if (parts.length > 3) {
            setLine(readLine(parts[2]));
            setThickness((int) readNumber(parts[3], 1));
        }

        if (parts.length > 4) {
            setColour(readColour(parts[4]));
        }

        if (parts.length > 6) {
            setCentreLine(readLine(parts[5]));
            setCentreThickness((int) readNumber(parts[6], 1));
        }

        if (parts.length > 7) {
            setCentreColour(readColour(parts[7]));
        }

        if (parts.length > 9) {
            setFilled(Boolean.parseBoolean(parts[8]));
            setOpacity((int) readNumber(parts[9], 12));
        }
    }

    private static Width readWidth(String text) {
        for (Width each : Width.values()) {
            if (each.name().equals(text)) {
                return each;
            }
        }

        return Width.DEVIATION;
    }

    private static MovingAverage.Line readLine(String text) {
        for (MovingAverage.Line each : MovingAverage.Line.values()) {
            if (each.name().equals(text)) {
                return each;
            }
        }

        return MovingAverage.Line.SOLID;
    }

    private static double readNumber(String text, double fallback) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            // A hand-edited line. The default is a better answer than losing
            // the whole appearance over one field.
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
