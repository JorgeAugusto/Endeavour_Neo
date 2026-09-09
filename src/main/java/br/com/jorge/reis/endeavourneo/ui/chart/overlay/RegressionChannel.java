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
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The linear regression channel: a straight line through the last window, and
 * as many mirrored pairs of edges around it as the reader asks for.
 *
 * <h2>What it is, and what it is not</h2>
 *
 * <p>Least squares over the {@code period} closes that end at one bar. The line
 * is the trend; each level opens a pair of edges that many deviations either
 * side of it.</p>
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
 * <h2>Levels come in pairs, and one number makes both</h2>
 *
 * <p>A level is a single number. Two means the pair at two deviations above and
 * two below, and there is no way to ask for a channel wider on one side than
 * the other -- because there is no reading in which that means anything: the
 * residuals are measured around a line that already carries the trend, so the
 * lopsidedness such a channel would show is the trend, counted twice.</p>
 *
 * <p>The one exception is the {@link Width#EXTREME} criterion, where each side
 * is its own furthest residual. That asymmetry is measured rather than asked
 * for, which is the whole difference.</p>
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

    /**
     * One pair of edges, and how it is drawn.
     *
     * @param factor how many deviations -- or how many times the furthest
     *        residual -- this pair sits from the line
     * @param colour its own, or null to take the indicator's
     * @param line solid or dashed
     * @param thickness in pixels, one to eight
     *
     * <p>A record, and the list of them is replaced whole rather than edited:
     * the painting reads it while the dialog is writing.</p>
     */
    public record Level(double factor, Color colour, MovingAverage.Line line, int thickness) {

        /** Holds what it is given inside the range the dialog offers. */
        public Level {
            factor = Double.isFinite(factor) ? Math.max(0.1, Math.min(factor, 10.0)) : 2.0;
            line = line == null ? MovingAverage.Line.SOLID : line;
            thickness = Math.max(1, Math.min(thickness, 8));
        }

        /** @param factor how far out, in the criterion's units */
        public Level(double factor) {
            this(factor, null, MovingAverage.Line.SOLID, 1);
        }
    }

    /** The window's ceiling, as in the indicator this came from. */
    public static final int MOST_BARS = 400;

    /** What it is fitted over when nobody says. */
    public static final int PERIOD = 90;

    /** How many pairs of edges may be asked for. */
    public static final int MOST_LEVELS = 8;

    /** The centre line, in the colour the source draws it. */
    private static final Color CENTRE = new Color(0x00, 0xC8, 0xFF);

    /** The edges, in the source's colour for its main pair. */
    private static final Color EDGE = new Color(0xFF, 0xFF, 0x00);

    private int period;

    private Width width = Width.DEVIATION;

    /**
     * The pairs, widest last.
     *
     * <p>Replaced whole, never edited in place: the painting reads it and the
     * dialog writes it. Kept sorted so "the outermost pair" -- which is what
     * the shading fills between -- is simply the last one.</p>
     */
    private volatile List<Level> levels = List.of(new Level(2.0));

    private boolean showCentre = true;

    private Color centreColour;

    private MovingAverage.Line centreLine = MovingAverage.Line.SOLID;

    private int centreThickness = 1;

    private Color colour;

    private boolean fill;

    private int opacity = 12;

    private Color fillColour;

    /**
     * Whether the shading takes its colour from where the channel POINTS.
     *
     * <p>Its own switch and not a colour that happens to be two: with it on,
     * the channel is shaded green while the trend rises and red while it falls,
     * so the direction is legible without reading the slope off the line. Off,
     * the shading is one colour and says nothing about direction.</p>
     *
     * <p><b>It implies the shading.</b> Asking for a colour by direction and
     * then having to tick a second box somewhere else for anything to appear is
     * a trap, and the kind that is only found by the reader who falls into
     * it -- so this alone is enough, and the plain fill's controls say they are
     * being overruled.</p>
     */
    private boolean fillByDirection;

    private Color risingFill = new Color(0x2E, 0xA0, 0x43);

    private int risingOpacity = 12;

    private Color fallingFill = new Color(0xD1, 0x3A, 0x3A);

    private int fallingOpacity = 12;

    /**
     * The scale it is fitted on, or null to follow the chart.
     *
     * <p>A period code as {@code PeriodCatalog} spells it. {@link OwnScale} is
     * where the rule that makes this honest lives: the fit reads the last
     * CLOSED bar of the larger scale and never the one still forming.</p>
     */
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
     * The same bars folded to {@link #ownPeriod}, or null when it follows the
     * chart.
     *
     * <p>Folded in {@code calculate} and not per frame: the fold does not
     * depend on where the chart is -- only the anchor does -- and folding
     * 825.000 bars on every movement of the mouse is not a thing to do.</p>
     */
    private volatile PriceSeries coarse;

    /** The last fit. Replaced whole, never edited. Null until the first frame. */
    private volatile Fit fit;

    /** What the last frame brought down from the larger scale, or null. */
    private volatile Mapped mapped;

    /**
     * One fit of the line over one window.
     *
     * @param first the oldest bar in the window
     * @param anchor the newest, the one it was fitted to
     * @param oldest the line's value at {@code first}
     * @param newest its value at {@code anchor}
     * @param above ONE deviation's worth above the line
     * @param below one deviation's worth below it
     * @param slope price per bar, positive when it rises
     * @param rSquared how much of the movement the line explains, nought to one
     *
     * <p>{@code above} and {@code below} are one unit and not a level's worth:
     * a level multiplies them. The fit does not know how many pairs are
     * drawn.</p>
     */
    record Fit(int first, int anchor, double oldest, double newest,
               double above, double below, double slope, double rSquared) {

        /** @return the line's value at that bar */
        double centreAt(int bar) {
            int span = anchor - first;

            if (span <= 0) {
                return newest;
            }

            return oldest + (newest - oldest) * (bar - first) / (double) span;
        }
    }

    /**
     * What a frame worked out for the chart's bars, from a larger scale.
     *
     * @param from the first chart bar {@code lines[n][0]} answers for
     * @param lines one row per drawn line, in the order {@link #valueAt} returns
     */
    private record Mapped(int from, double[][] lines) { }

    public RegressionChannel(int period) {
        setPeriod(period);
    }

    /**
     * @param settings what the catalogue hands over: the period, if anything
     *
     * <p>The varargs constructor the catalogue's factory needs. One number: the
     * levels are settings and not parameters, because 1,75 does not survive a
     * list of integers and because there can be eight of them.</p>
     */
    public RegressionChannel(int... settings) {
        this(settings.length > 0 ? settings[0] : PERIOD);
    }

    // ---------------------------------------------------------- the settings

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

    public Width width() {
        return width;
    }

    public void setWidth(Width value) {
        this.width = value == null ? Width.DEVIATION : value;
    }

    /**
     * @return the pairs of edges, widest last
     *
     * <p>Not {@code levels()}: {@link Overlay#levels()} is already a method,
     * and it means a different thing -- a horizontal line at a fixed VALUE,
     * which is what a stochastic's twenty and eighty are. These move with the
     * fit. Two names for two things, rather than one name that has to be read
     * twice.</p>
     */
    public List<Level> deviationLevels() {
        return levels;
    }

    /**
     * @param wanted the pairs to draw; empty leaves the centre on its own
     *
     * <p>Sorted and capped here rather than at every call site, and copied: the
     * list the dialog builds goes on being the dialog's.</p>
     */
    public void setDeviationLevels(List<Level> wanted) {
        if (wanted == null || wanted.isEmpty()) {
            this.levels = List.of();

            return;
        }

        List<Level> sorted = new ArrayList<>(
                wanted.subList(0, Math.min(wanted.size(), MOST_LEVELS)));

        sorted.sort((one, other) -> Double.compare(one.factor(), other.factor()));

        this.levels = List.copyOf(sorted);
    }

    public boolean isCentreShown() {
        return showCentre;
    }

    public void setCentreShown(boolean value) {
        this.showCentre = value;
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

    public MovingAverage.Line centreLine() {
        return centreLine;
    }

    public void setCentreLine(MovingAverage.Line value) {
        this.centreLine = value == null ? MovingAverage.Line.SOLID : value;
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

    /** @return the shading's chosen colour, or null when it follows the edges */
    public Color chosenFillColour() {
        return fillColour;
    }

    public void setFillColour(Color value) {
        this.fillColour = value;
    }

    /** @return whether the shading is chosen by the direction of the trend */
    public boolean isFilledByDirection() {
        return fillByDirection;
    }

    public void setFilledByDirection(boolean value) {
        this.fillByDirection = value;
    }

    public Color risingFill() {
        return risingFill;
    }

    public void setRisingFill(Color value) {
        this.risingFill = value == null ? new Color(0x2E, 0xA0, 0x43) : value;
    }

    public int risingOpacity() {
        return risingOpacity;
    }

    public void setRisingOpacity(int value) {
        this.risingOpacity = Math.max(0, Math.min(value, 100));
    }

    public Color fallingFill() {
        return fallingFill;
    }

    public void setFallingFill(Color value) {
        this.fallingFill = value == null ? new Color(0xD1, 0x3A, 0x3A) : value;
    }

    public int fallingOpacity() {
        return fallingOpacity;
    }

    public void setFallingOpacity(int value) {
        this.fallingOpacity = Math.max(0, Math.min(value, 100));
    }

    /**
     * @return the colour the channel is shaded with as it stands, or null when
     *         it is not shaded at all
     *
     * <p>Alpha included, so what comes back is what is painted. Package-visible
     * so a test can ask what the reader sees without counting pixels -- the
     * question here is WHICH colour, and a pixel would answer that through two
     * more things that can go wrong.</p>
     */
    Color shading() {
        Fit now = fit;

        if (levels.isEmpty()) {
            return null;
        }

        if (fillByDirection) {
            // A flat channel takes the rising colour. Something has to be
            // chosen, drawing nothing would make the shading blink as the slope
            // crossed zero, and a third colour for "flat" is a setting nobody
            // asked for to describe a case that lasts one frame.
            boolean up = now == null || now.slope() >= 0.0;

            return alpha(up ? risingFill : fallingFill,
                    up ? risingOpacity : fallingOpacity);
        }

        if (!fill) {
            return null;
        }

        int outer = levels.size() - 1;

        return alpha(fillColour != null ? fillColour
                : levels.get(outer).colour() != null ? levels.get(outer).colour()
                        : colour == null ? EDGE : colour, opacity);
    }

    private static Color alpha(Color of, int percent) {
        return percent <= 0 ? null
                : new Color(of.getRed(), of.getGreen(), of.getBlue(),
                        Math.round(255 * percent / 100f));
    }

    @Override
    public String ownPeriod() {
        return ownPeriod;
    }

    /**
     * @param code a period code, or null to follow the chart
     *
     * <p>The fold is redone here and not left to the next {@code calculate}: a
     * scale chosen with the chart already open has to take effect on the next
     * frame, not on the next series.</p>
     */
    public void setOwnPeriod(String code) {
        this.ownPeriod = code == null || code.isBlank() ? null : code;

        refold(source);

        this.fit = null;
        this.mapped = null;
    }


    // ------------------------------------------------------------ the numbers

    /** @return the slope in price per bar, positive when it rises, or NaN */
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
     * @param series what to fit over -- the chart's bars, or the folded ones
     * @param anchor the newest bar of the window
     * @return the fit, or null when there is not that much history
     *
     * <p>Package-private and taking everything it needs: a test can check the
     * arithmetic against a hand computation without a chart, and two threads
     * fitting at once cannot meet.</p>
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
                over, under, gradient,
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
        // Straight lines in points of the index. There is nowhere else they
        // could go.
        return true;
    }

    /**
     * @return how many lines are drawn: the centre, then a pair per level
     *
     * <p>The centre counts even when it is hidden. It is hidden by having no
     * VALUE, so the list stays the same length and the legend keeps its
     * swatch -- the same rule the bands beside this follow.</p>
     */
    private int lineCount() {
        return 1 + 2 * levels.size();
    }

    @Override
    public List<Color> colours() {
        List<Level> now = levels;
        List<Color> found = new ArrayList<>(1 + 2 * now.size());

        found.add(centreColour == null ? CENTRE : centreColour);

        for (Level level : now) {
            Color ink = level.colour() != null ? level.colour()
                    : colour == null ? EDGE : colour;

            // Twice, because a pair is two lines and the caller pairs this list
            // with valueAt by index.
            found.add(ink);
            found.add(ink);
        }

        return List.copyOf(found);
    }

    @Override
    public Stroke stroke() {
        return centreLine.stroke(centreThickness);
    }

    @Override
    public List<Stroke> strokes() {
        List<Level> now = levels;
        List<Stroke> found = new ArrayList<>(1 + 2 * now.size());

        found.add(centreLine.stroke(centreThickness));

        for (Level level : now) {
            Stroke pen = level.line().stroke(level.thickness());

            found.add(pen);
            found.add(pen);
        }

        return List.copyOf(found);
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
        PriceSeries bars = series == null ? PriceSeries.empty() : series;

        this.source = bars;

        refold(bars);

        // AND THE FIT IS DROPPED. It belongs to bars that have just been
        // replaced; keeping it would draw the old channel over the new series
        // for exactly one frame, which is the kind of thing nobody reproduces.
        this.fit = null;
        this.mapped = null;
    }

    /** Folds the chart's bars to the chosen scale, or forgets the fold. */
    private void refold(PriceSeries bars) {
        Aggregation scale = ownPeriod == null ? null : OwnScale.of(ownPeriod);

        this.coarse = scale == null || bars.size() == 0 ? null : scale.apply(bars);
    }

    /**
     * @param bar an index into the chart's series
     * @return the centre and each pair there, or NaN where nothing is drawn
     *
     * <p>From the fit the last {@link #paintUnder} made, which is the fit that
     * is on screen. Outside the window it answers NaN rather than extending the
     * line: a straight line carried past the bars it was fitted to is a
     * forecast, and this indicator does not make one.</p>
     */
    @Override
    public double[] valueAt(int bar) {
        Mapped down = mapped;

        if (down != null) {
            int at = bar - down.from();

            if (down.lines().length == 0 || at < 0 || at >= down.lines()[0].length) {
                return empty();
            }

            double[] found = new double[down.lines().length];

            for (int n = 0; n < found.length; n++) {
                found[n] = down.lines()[n][at];
            }

            return found;
        }

        Fit now = fit;

        if (now == null || bar < now.first() || bar > now.anchor()) {
            return empty();
        }

        return spread(now.centreAt(bar), now);
    }

    /** @return every line's value where the centre sits at that price */
    private double[] spread(double centre, Fit now) {
        List<Level> pairs = levels;
        double[] found = new double[1 + 2 * pairs.size()];

        found[0] = showCentre ? centre : Double.NaN;

        for (int n = 0; n < pairs.size(); n++) {
            double factor = pairs.get(n).factor();

            found[1 + 2 * n] = centre + now.above() * factor;
            found[2 + 2 * n] = centre - now.below() * factor;
        }

        return found;
    }

    private double[] empty() {
        double[] found = new double[lineCount()];

        Arrays.fill(found, Double.NaN);

        return found;
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
        PriceSeries fine = source;
        PriceSeries slow = coarse;

        Fit made = slow == null
                ? fitOnChart(fine, to)
                : fitOnScale(fine, slow, Math.max(0, from), to);

        Color wash = made == null ? null : shading();

        if (wash == null) {
            return;
        }

        shade(g, viewport, wash, Math.max(0, from), Math.min(to, fine.size()));
    }

    /** Fits on the chart's own bars, anchored on the last one in view. */
    private Fit fitOnChart(PriceSeries fine, int to) {
        // `to` is one past the last visible bar, and the view can run past the
        // end of the data -- which is what the right margin IS.
        Fit made = fitAt(fine, Math.min(to, fine.size()) - 1);

        this.mapped = null;
        this.fit = made;

        return made;
    }

    /**
     * Fits on the larger scale, then brings the lines down to the chart's bars.
     *
     * <p>The anchor is the last coarse bar that had CLOSED by the last bar in
     * view. {@link OwnScale} is where that rule lives and this asks it -- by
     * mapping the coarse bars' own indices down -- rather than working it out
     * again. A second place where that rule is written is a second chance to
     * write it wrong, and that class says so itself.</p>
     *
     * <p>Only the VISIBLE bars are brought down. Filling an array the length of
     * the whole series, once per line, per frame, to read the two thousand that
     * are on screen is the shape of waste this file keeps away from.</p>
     */
    private Fit fitOnScale(PriceSeries fine, PriceSeries slow, int from, int to) {
        int width = Math.min(to, fine.size()) - from;

        if (width <= 0 || slow.size() == 0) {
            this.mapped = null;
            this.fit = null;

            return null;
        }

        double[] ordinals = new double[slow.size()];

        for (int n = 0; n < ordinals.length; n++) {
            ordinals[n] = n;
        }

        double[] whichBar = new double[width];

        OwnScale.map(fine, slow, ordinals, whichBar, from);

        double last = whichBar[width - 1];
        Fit made = Double.isNaN(last) ? null : fitAt(slow, (int) Math.round(last));

        this.fit = made;

        if (made == null) {
            this.mapped = null;

            return null;
        }

        int lines = lineCount();
        double[][] onScale = new double[lines][slow.size()];

        for (double[] each : onScale) {
            Arrays.fill(each, Double.NaN);
        }

        // ONE pass over the coarse bars, filling every line: spread() answers
        // for all of them at once, and calling it per line would walk the
        // window as many times as there are edges.
        for (int bar = made.first(); bar <= made.anchor(); bar++) {
            double[] here = spread(made.centreAt(bar), made);

            for (int n = 0; n < lines; n++) {
                onScale[n][bar] = here[n];
            }
        }

        double[][] down = new double[lines][width];

        for (int n = 0; n < lines; n++) {
            if (br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.interpolateOwnScale()) {
                Arrays.fill(down[n], Double.NaN);

                OwnScale.smooth(fine, slow, onScale[n], down[n], from);
            } else {
                OwnScale.map(fine, slow, onScale[n], down[n], from);
            }
        }

        this.mapped = new Mapped(from, down);

        return made;
    }

    /**
     * Fills between the OUTERMOST pair, which is what a channel encloses.
     *
     * <p>Read back through {@link #valueAt}, the same values the lines are
     * drawn from, so the shading cannot disagree with its own boundary --
     * whichever of the two ways the fit was made.</p>
     */
    private void shade(Graphics2D g, Viewport viewport, Color wash, int from, int to) {
        int outer = levels.size() - 1;

        g.setColor(wash);

        int upper = 1 + 2 * outer;
        int lower = 2 + 2 * outer;

        Polygon shape = new Polygon();
        int drawn = 0;

        for (int bar = from; bar < to; bar++) {
            double[] here = valueAt(bar);

            if (upper < here.length && Double.isFinite(here[upper])) {
                shape.addPoint(x(viewport, bar), y(viewport, here[upper]));
                drawn++;
            }
        }

        for (int bar = to - 1; bar >= from; bar--) {
            double[] here = valueAt(bar);

            if (lower < here.length && Double.isFinite(here[lower])) {
                shape.addPoint(x(viewport, bar), y(viewport, here[lower]));
            }
        }

        if (drawn >= 2) {
            g.fillPolygon(shape);
        }
    }

    private static int x(Viewport viewport, int bar) {
        return (int) Math.round(viewport.x(bar));
    }

    private static int y(Viewport viewport, double price) {
        return (int) Math.round(viewport.y(price));
    }

    // ------------------------------------------------------------- the layout

    /**
     * @return every setting as one line, levels included
     *
     * <p>Two separators the layout file does not use: {@code ~} between levels
     * and {@code :} inside one. The pipe is the layout's own field separator
     * and the comma separates the parameters, so neither can appear here.</p>
     */
    @Override
    public String appearance() {
        StringBuilder text = new StringBuilder();

        text.append(width).append(';').append(showCentre)
                .append(';').append(centreLine).append(';').append(centreThickness)
                .append(';').append(hex(centreColour))
                .append(';').append(hex(colour))
                .append(';').append(fill).append(';').append(opacity)
                .append(';').append(hex(fillColour))
                .append(';').append(ownPeriod == null ? "chart" : ownPeriod)
                .append(';');

        List<Level> now = levels;

        for (int n = 0; n < now.size(); n++) {
            Level level = now.get(n);

            if (n > 0) {
                text.append('~');
            }

            text.append(level.factor()).append(':').append(level.line())
                    .append(':').append(level.thickness())
                    .append(':').append(hex(level.colour()));
        }

        // AFTER the levels, which is the field that carries its own separators.
        // A field appended here cannot be confused with one of theirs, and a
        // layout written before this existed simply stops short -- which is the
        // case applyAppearance already handles field by field.
        text.append(';').append(fillByDirection)
                .append(';').append(hex(risingFill)).append(';').append(risingOpacity)
                .append(';').append(hex(fallingFill)).append(';').append(fallingOpacity);

        return text.toString();
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
            setCentreShown(Boolean.parseBoolean(parts[1]));
        }

        if (parts.length > 3) {
            setCentreLine(readLine(parts[2]));
            setCentreThickness((int) readNumber(parts[3], 1));
        }

        if (parts.length > 4) {
            setCentreColour(readColour(parts[4]));
        }

        if (parts.length > 5) {
            setColour(readColour(parts[5]));
        }

        if (parts.length > 7) {
            setFilled(Boolean.parseBoolean(parts[6]));
            setOpacity((int) readNumber(parts[7], 12));
        }

        if (parts.length > 8) {
            setFillColour(readColour(parts[8]));
        }

        if (parts.length > 9) {
            setOwnPeriod("chart".equals(parts[9]) ? null : parts[9]);
        }

        // The slot that held this indicator's own "interpolate" is gone, and
        // everything after it moved down one. No layout in the wild carries the
        // old shape -- this indicator and its levels are a day old -- so there
        // is no migration here, and saying that is cheaper than a guess about
        // which of two shapes a string is.
        if (parts.length > 10) {
            setDeviationLevels(readLevels(parts[10]));
        }

        if (parts.length > 11) {
            setFilledByDirection(Boolean.parseBoolean(parts[11]));
        }

        if (parts.length > 13) {
            setRisingFill(readColour(parts[12]));
            setRisingOpacity((int) readNumber(parts[13], 12));
        }

        if (parts.length > 15) {
            setFallingFill(readColour(parts[14]));
            setFallingOpacity((int) readNumber(parts[15], 12));
        }
    }

    private static List<Level> readLevels(String text) {
        List<Level> found = new ArrayList<>();

        if (text == null || text.isBlank()) {
            return found;
        }

        for (String each : text.split("~", -1)) {
            String[] fields = each.split(":", -1);

            if (fields.length == 0 || fields[0].isBlank()) {
                continue;
            }

            found.add(new Level(readNumber(fields[0], 2.0),
                    fields.length > 3 ? readColour(fields[3]) : null,
                    fields.length > 1 ? readLine(fields[1]) : MovingAverage.Line.SOLID,
                    fields.length > 2 ? (int) readNumber(fields[2], 1) : 1));
        }

        return found;
    }

    private static String hex(Color of) {
        return of == null ? "auto" : Integer.toHexString(of.getRGB() & 0xFFFFFF);
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
