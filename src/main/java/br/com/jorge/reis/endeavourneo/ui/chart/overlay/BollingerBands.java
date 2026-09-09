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

import br.com.jorge.reis.endeavourneo.ui.chart.OwnScale;

import br.com.jorge.reis.endeavourneo.domain.market.Aggregation;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bollinger bands: a moving average with a band each side of it.
 *
 * <p>The width between the bands is not decoration — it is the recent
 * volatility, in points. Tight bands say the market has stopped; open bands
 * say it is moving.</p>
 *
 * <h2>Where the deviation is measured from</h2>
 *
 * <p><b>Around the middle line that is actually drawn</b>, not around the
 * simple mean of the period. Both conventions exist, and several platforms use
 * the second one even when the middle is exponential. The choice here is the
 * self-consistent one: "two deviations from the middle band" then means two
 * deviations from <em>that</em> line, and not from another that is not on the
 * chart. With an arithmetic middle — which is the default, and what the Profit
 * shows — the two are the same thing; they part company in a strong trend,
 * where an exponential middle pulls away from the simple mean.</p>
 *
 * <h2>Population, not sample</h2>
 *
 * <p>Divided by {@code n}, not {@code n - 1}. This is not a sample drawn from
 * a larger population that we are trying to infer: the window IS the thing
 * being described. It is also what the reference implementation in the first
 * Endeavour does and what the old Chartsy does; ta4j offers both and this
 * matches its {@code ofPopulation}. The difference at period 20 is a factor of
 * 1,026 on the width — visible, and a reason to state which one this is.</p>
 *
 * <p>The two deviations are separate settings because the Profit has them
 * separate: an upper of 2 with a lower of 1 is a legitimate, if unusual, way to
 * read a market with a floor under it.</p>
 */
public final class BollingerBands implements Overlay {

    // UPPER, MIDDLE and LOWER are gone. Three constants under a javadoc that
    // said "where the values sit in valueAt", and nothing read any of them:
    // valueAt builds its answer as an array literal in that order, colours()
    // and strokes() build lists in that order, and paintUnder reads the fields
    // by name. Naming the positions is a good idea and this was not it -- the
    // names existed and the agreement between the three lists went on being
    // held by the order they are written in, which is what the constants were
    // supposed to stop being true. What holds it now is that colours() says so
    // and OverlayLegendTest pairs them.
    private static final Color BAND = new Color(0xE8, 0x8C, 0x3A);

    private static final Color CENTRE = new Color(0x5E, 0xC2, 0x76);

    /**
     * The middle line, and what the deviation is measured around.
     *
     * <p>A real {@link MovingAverage} rather than an arithmetic mean computed
     * here, so that a Bollinger of period 20 and an average of period 20 drawn
     * on the same chart are the same line to the last decimal — including on
     * its own scale, where the last-closed rule applies. Two implementations of
     * one idea drift, and the drift shows up as a middle band that does not sit
     * on the average the reader also has on screen.</p>
     */
    private final MovingAverage basis;

    private double upperDeviations = 2.0;

    private double lowerDeviations = 2.0;

    private boolean showMiddle = true;

    private Color colour;

    private MovingAverage.Line line = MovingAverage.Line.SOLID;

    private int thickness = 1;

    private Color middleColour;

    private MovingAverage.Line middleLine = MovingAverage.Line.SOLID;

    private int middleThickness = 1;

    private boolean fill;

    /** How much of the fill shows, 0 to 100. */
    private int opacity = 20;

    private Color fillColour;

    private volatile double[] upper = new double[0];

    private volatile double[] middle = new double[0];

    private volatile double[] lower = new double[0];

    private boolean visible = true;

    public BollingerBands(int period) {
        this.basis = new MovingAverage(Math.max(1, period));
    }

    /**
     * @param settings period first, then the deviation as a whole number
     *
     * <p>The catalogue hands parameters in as integers, which is right for
     * every other indicator here. A deviation of 2,5 is set in the dialog, not
     * on this path.</p>
     */
    public BollingerBands(int... settings) {
        this(settings.length > 0 ? settings[0] : 20);

        if (settings.length > 1) {
            setUpperDeviations(settings[1]);
            setLowerDeviations(settings[1]);
        }
    }

    public int period() {
        return basis.period();
    }

    public void setPeriod(int value) {
        basis.setPeriod(value);
    }

    public MovingAverage.Kind kind() {
        return basis.kind();
    }

    public void setKind(MovingAverage.Kind value) {
        basis.setKind(value);
    }

    public MovingAverage.Source source() {
        return basis.source();
    }

    public void setSource(MovingAverage.Source value) {
        basis.setSource(value);
    }

    public String ownPeriod() {
        return basis.ownPeriod();
    }

    public void setOwnPeriod(String code) {
        basis.setOwnPeriod(code);
    }


    public double upperDeviations() {
        return upperDeviations;
    }

    public void setUpperDeviations(double value) {
        upperDeviations = clampDeviation(value);
    }

    public double lowerDeviations() {
        return lowerDeviations;
    }

    public void setLowerDeviations(double value) {
        lowerDeviations = clampDeviation(value);
    }

    /**
     * @return the value made safe to draw
     *
     * <p>Negative would put the upper band below the lower one and turn the
     * fill inside out; NaN would erase both bands with no message. Neither is
     * reachable from the dialog, and both are reachable from a hand-edited
     * layout file.</p>
     */
    private static double clampDeviation(double value) {
        if (!Double.isFinite(value) || value < 0) {
            return 0;
        }

        return Math.min(value, 10);
    }

    public boolean isMiddleShown() {
        return showMiddle;
    }

    public void setMiddleShown(boolean value) {
        showMiddle = value;
    }

    public boolean isFilled() {
        return fill;
    }

    public void setFilled(boolean value) {
        fill = value;
    }

    public int opacity() {
        return opacity;
    }

    /** @param value 0 for invisible, 100 for solid */
    public void setOpacity(int value) {
        opacity = Math.max(0, Math.min(100, value));
    }

    public Color chosenColour() {
        return colour;
    }

    public void setColour(Color value) {
        colour = value;
    }

    public Color chosenFillColour() {
        return fillColour;
    }

    public void setFillColour(Color value) {
        fillColour = value;
    }

    public Color chosenMiddleColour() {
        return middleColour;
    }

    public void setMiddleColour(Color value) {
        middleColour = value;
    }

    public MovingAverage.Line line() {
        return line;
    }

    public void setLine(MovingAverage.Line value) {
        line = value == null ? MovingAverage.Line.SOLID : value;
    }

    public int thickness() {
        return thickness;
    }

    public void setThickness(int value) {
        thickness = Math.max(1, Math.min(value, 10));
    }

    public MovingAverage.Line middleLine() {
        return middleLine;
    }

    public void setMiddleLine(MovingAverage.Line value) {
        middleLine = value == null ? MovingAverage.Line.SOLID : value;
    }

    public int middleThickness() {
        return middleThickness;
    }

    public void setMiddleThickness(int value) {
        middleThickness = Math.max(1, Math.min(value, 10));
    }

    private Color bandColour() {
        return colour == null ? BAND : colour;
    }

    private Color centreColour() {
        return middleColour == null ? CENTRE : middleColour;
    }

    @Override
    public String nameKey() {
        return "overlay.bollinger";
    }

    @Override
    public boolean fitsOnPrice() {
        // Three lines in points of the index, and the shading between two of
        // them. There is nowhere else they could go.
        return true;
    }

    @Override
    public List<Integer> parameters() {
        return List.of(period());
    }

    @Override
    public List<Color> colours() {
        // Three lines, in the order valueAt returns them. The middle keeps its
        // colour even when hidden -- it is hidden by having no value, so the
        // list stays the same length and the legend keeps its swatch.
        return List.of(bandColour(), centreColour(), bandColour());
    }

    @Override
    public Stroke stroke() {
        return line.stroke(thickness);
    }

    /**
     * @return one stroke per line, in the order the values come in
     *
     * <p><b>The middle is drawn with its OWN style and thickness.</b> This class
     * has {@code middleLine} and {@code middleThickness}, the dialog offers
     * both, and neither reached the screen: only {@code stroke()} was asked, and
     * that one answers for the bands. Two settings a reader could change with
     * nothing changing.</p>
     *
     * <p>Three strokes and not two, even though the outer bands share one: the
     * caller pairs this list with {@link #colours} by index, and a shorter list
     * would pair the middle's stroke with the lower band.</p>
     */
    @Override
    public java.util.List<Stroke> strokes() {
        return java.util.List.of(line.stroke(thickness),
                middleLine.stroke(middleThickness),
                line.stroke(thickness));
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
    public double[] valueAt(int bar) {
        // Read ONCE into locals. A background recalculation replaces these
        // fields whole, and checking the length of one array while reading from
        // another is how that swap would show: an index out of bounds, on the
        // painting thread, at a moment nobody can reproduce.
        double[] nowUpper = upper;
        double[] nowMiddle = middle;
        double[] nowLower = lower;

        if (bar < 0 || bar >= nowMiddle.length
                || bar >= nowUpper.length || bar >= nowLower.length) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN};
        }

        return new double[]{
                nowUpper[bar],
                showMiddle ? nowMiddle[bar] : Double.NaN,
                nowLower[bar],
        };
    }

    @Override
    public void calculate(PriceSeries series) {
        int size = series == null ? 0 : series.size();
        double[] builtUpper = new double[size];
        double[] builtMiddle = new double[size];
        double[] builtLower = new double[size];

        if (size > 0) {
            build(series, builtUpper, builtMiddle, builtLower);
        }

        // PUBLISHED WHOLE, at the end. The fields used to be assigned the empty
        // arrays first and filled in place, which is invisible while everything
        // happens on the interface thread -- and this is now called off it, so
        // a repaint landing halfway through would have read an array half full
        // of zeros.
        this.upper = builtUpper;
        this.middle = builtMiddle;
        this.lower = builtLower;
    }

    private void build(PriceSeries series, double[] upper, double[] middle, double[] lower) {
        Aggregation scale = OwnScale.of(ownPeriod());

        if (scale == null) {
            // On the chart's own scale, which is also the answer when a layout
            // names a scale this version does not build.
            computeOver(series, upper, middle, lower);

            return;
        }

        PriceSeries coarse = scale.apply(series);

        if (coarse.size() == 0) {
            Arrays.fill(upper, Double.NaN);
            Arrays.fill(middle, Double.NaN);
            Arrays.fill(lower, Double.NaN);

            return;
        }

        double[] slowUpper = new double[coarse.size()];
        double[] slowMiddle = new double[coarse.size()];
        double[] slowLower = new double[coarse.size()];

        computeOver(coarse, slowUpper, slowMiddle, slowLower);

        // All three mapped by the same rule and the same helper the average
        // uses. Mapping only the middle and deriving the bands from a deviation
        // taken on the fine series would put bands of one scale around a line
        // of another.
        OwnScale.map(series, coarse, slowUpper, upper);
        OwnScale.map(series, coarse, slowMiddle, middle);
        OwnScale.map(series, coarse, slowLower, lower);

        if (br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.interpolateOwnScale()) {
            OwnScale.smooth(series, coarse, slowUpper, upper);
            OwnScale.smooth(series, coarse, slowMiddle, middle);
            OwnScale.smooth(series, coarse, slowLower, lower);
        }
    }

    /**
     * The bands over one series, at that series' own scale.
     *
     * <p>The middle comes from a {@link MovingAverage} told to stay on the
     * scale it is given — its own-period setting is cleared here, because this
     * method is already called with the coarse series when there is one.</p>
     */
    private void computeOver(PriceSeries series, double[] up, double[] mid, double[] down) {
        MovingAverage line = new MovingAverage(period());

        line.setKind(kind());
        line.setSource(source());
        line.setOwnPeriod(null);
        line.calculate(series);

        int period = period();

        for (int i = 0; i < mid.length; i++) {
            // at() and not valueAt(): the second wraps the number in a new
            // double[1] to hand it over, and this loop runs the whole series on
            // every recalculation. The array was built and dropped on the next
            // line, once per bar.
            double centre = line.at(i);

            mid[i] = centre;

            if (!Double.isFinite(centre) || i < period - 1) {
                // NaN through the warm-up, never a deviation over a partial
                // window: a partial one is widest exactly at the left edge,
                // which is where it would be read as a real burst of
                // volatility.
                up[i] = Double.NaN;
                down[i] = Double.NaN;

                continue;
            }

            double sum = 0.0;

            for (int back = i - period + 1; back <= i; back++) {
                double difference = priceAt(series, back) - centre;

                sum += difference * difference;
            }

            double deviation = Math.sqrt(sum / period);

            up[i] = centre + upperDeviations * deviation;
            down[i] = centre - lowerDeviations * deviation;
        }
    }

    private double priceAt(PriceSeries series, int bar) {
        // The average's rule, not a copy of it. This switch used to be written
        // out again here, letter for letter, reading the same source() -- two
        // answers to one question, in the class whose own javadoc says that is
        // what it exists to prevent.
        return source().of(series, bar);
    }

    /**
     * Paints the band between the two lines, before they are drawn.
     *
     * <p>One polygon down the upper band and back along the lower, rather than
     * a vertical line per bar: at one line per bar the seams between them show
     * as banding when the chart is zoomed out, and a polygon is also what the
     * anti-aliasing can smooth.</p>
     */
    @Override
    public void paintUnder(Graphics2D g, Viewport viewport, int from, int to) {
        if (!fill || opacity <= 0) {
            return;
        }

        Color base = fillColour == null ? bandColour() : fillColour;
        int alpha = Math.round(255 * opacity / 100f);

        g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));

        // Broken into runs: a gap where either band is NaN must be a gap in the
        // fill too, not a straight edge drawn across it.
        int at = from;

        while (at < to) {
            while (at < to && !bothFinite(at)) {
                at++;
            }

            int start = at;

            while (at < to && bothFinite(at)) {
                at++;
            }

            if (at - start >= 2) {
                g.fillPolygon(runBetween(viewport, start, at));
            }
        }
    }

    private boolean bothFinite(int bar) {
        return bar >= 0 && bar < upper.length
                && Double.isFinite(upper[bar]) && Double.isFinite(lower[bar]);
    }

    private Polygon runBetween(Viewport viewport, int start, int end) {
        Polygon shape = new Polygon();

        for (int i = start; i < end; i++) {
            shape.addPoint((int) Math.round(viewport.x(i)),
                    (int) Math.round(viewport.y(upper[i])));
        }

        for (int i = end - 1; i >= start; i--) {
            shape.addPoint((int) Math.round(viewport.x(i)),
                    (int) Math.round(viewport.y(lower[i])));
        }

        return shape;
    }

    /**
     * @return every setting, as {@code name=value} pairs
     *
     * <p><b>By name and not by position.</b> The average writes a positional
     * line and gets away with it at seven fields; this has fifteen, and a
     * positional format of fifteen is a format where inserting a setting in the
     * middle silently reinterprets every layout already saved. Reading by name
     * also means an older file simply lacks the newer keys, and keeps their
     * defaults, instead of failing to parse.</p>
     */
    @Override
    public String appearance() {
        Map<String, String> pairs = new LinkedHashMap<>();

        pairs.put("kind", kind().name());
        pairs.put("source", source().name());
        pairs.put("upper", String.valueOf(upperDeviations));
        pairs.put("lower", String.valueOf(lowerDeviations));
        pairs.put("middle", String.valueOf(showMiddle));
        pairs.put("line", line.name());
        pairs.put("thickness", String.valueOf(thickness));
        pairs.put("colour", hex(colour));
        pairs.put("middleLine", middleLine.name());
        pairs.put("middleThickness", String.valueOf(middleThickness));
        pairs.put("middleColour", hex(middleColour));
        pairs.put("fill", String.valueOf(fill));
        pairs.put("fillColour", hex(fillColour));
        pairs.put("opacity", String.valueOf(opacity));
        pairs.put("scale", ownPeriod() == null ? "chart" : ownPeriod());

        List<String> written = new ArrayList<>(pairs.size());

        pairs.forEach((key, value) -> written.add(key + "=" + value));

        return String.join(";", written);
    }

    @Override
    public void applyAppearance(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        Map<String, String> pairs = new LinkedHashMap<>();

        for (String part : text.split(";")) {
            int equals = part.indexOf('=');

            if (equals > 0) {
                pairs.put(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
            }
        }

        setKind(enumOf(MovingAverage.Kind.class, pairs.get("kind"), kind()));
        setSource(enumOf(MovingAverage.Source.class, pairs.get("source"), source()));
        setUpperDeviations(numberOf(pairs.get("upper"), upperDeviations));
        setLowerDeviations(numberOf(pairs.get("lower"), lowerDeviations));
        setMiddleShown(flagOf(pairs.get("middle"), showMiddle));
        setLine(enumOf(MovingAverage.Line.class, pairs.get("line"), line));
        setThickness((int) numberOf(pairs.get("thickness"), thickness));
        setColour(colourOf(pairs.get("colour")));
        setMiddleLine(enumOf(MovingAverage.Line.class, pairs.get("middleLine"), middleLine));
        setMiddleThickness((int) numberOf(pairs.get("middleThickness"), middleThickness));
        setMiddleColour(colourOf(pairs.get("middleColour")));
        setFilled(flagOf(pairs.get("fill"), fill));
        setFillColour(colourOf(pairs.get("fillColour")));
        setOpacity((int) numberOf(pairs.get("opacity"), opacity));

        String scale = pairs.get("scale");

        setOwnPeriod(scale == null || "chart".equals(scale) ? null : scale);
    }

    private static String hex(Color value) {
        return value == null ? "auto" : Integer.toHexString(value.getRGB() & 0xFFFFFF);
    }

    private static Color colourOf(String text) {
        if (text == null || "auto".equals(text)) {
            return null;
        }

        try {
            return new Color(Integer.parseInt(text, 16));
        } catch (NumberFormatException e) {
            // A hand-edited file, or one from a version that wrote colours
            // differently. Automatic is the answer that always draws something.
            return null;
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String text, E fallback) {
        if (text == null) {
            return fallback;
        }

        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static double numberOf(String text, double fallback) {
        if (text == null) {
            return fallback;
        }

        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean flagOf(String text, boolean fallback) {
        if (text == null) {
            return fallback;
        }

        return Boolean.parseBoolean(text);
    }
}
