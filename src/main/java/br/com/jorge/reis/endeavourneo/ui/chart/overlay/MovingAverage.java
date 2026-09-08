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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;

/**
 * One moving average, drawn as one line.
 *
 * <p><b>One, not three.</b> The first version took a list of periods and drew
 * them together, which made three lines share a single set of settings: you
 * could not colour one of them, hide one of them or change one of them. Adding
 * the same indicator three times is what the reference product does and what
 * anybody expects — and it costs nothing here, because a chart already holds a
 * list of overlays.</p>
 *
 * <h2>The four things that make it up</h2>
 *
 * <p><b>Period</b> — how many bars are averaged.</p>
 *
 * <p><b>Kind</b> — arithmetic, exponential or weighted. They differ in how much
 * the recent past counts, and the difference is largest exactly when it matters
 * most, at a turn.</p>
 *
 * <p><b>Shift</b> — how many bars the line is moved sideways. A positive shift
 * pushes it into the future, which is the classic way of using an average as a
 * projected level rather than as a description of the past.</p>
 *
 * <p><b>Source</b> — which price is averaged. Close is the usual answer; the
 * median and the typical price are steadier and are what some rules are written
 * against.</p>
 */
public final class MovingAverage implements Overlay {

    /** How the past is weighted. */
    public enum Kind {
        ARITHMETIC, EXPONENTIAL, WEIGHTED
    }

    /** Which price of the bar is averaged. */
    public enum Source {
        CLOSE, OPEN, HIGH, LOW, MEDIAN, TYPICAL;

        /**
         * @param series the bars
         * @param bar an index into them
         * @return the price this source names there
         *
         * <p><b>The rule, in one place.</b> This switch used to be written
         * twice, letter for letter -- once here in the average and once in
         * BollingerBands, which reads the SAME {@code source()} to decide what
         * its centre line is made of. The javadoc of {@code BollingerBands.basis}
         * says a duplicated indicator is what it exists to prevent: "Two
         * implementations of one idea drift". It was right, and did not notice
         * that the second implementation was its own, sixty lines below.</p>
         *
         * <p>No {@code default}: a seventh source added tomorrow breaks the
         * compilation here instead of silently falling through to the close.
         * That was the other half of the defect -- both copies ended in
         * {@code default ->}, so a new constant would have made the average
         * use it and the band centre on a line that was not the one drawn.</p>
         */
        public double of(PriceSeries series, int bar) {
            return switch (this) {
                case CLOSE -> series.closeAt(bar);
                case OPEN -> series.openAt(bar);
                case HIGH -> series.highAt(bar);
                case LOW -> series.lowAt(bar);
                case MEDIAN -> (series.highAt(bar) + series.lowAt(bar)) / 2.0;
                case TYPICAL ->
                        (series.highAt(bar) + series.lowAt(bar) + series.closeAt(bar)) / 3.0;
            };
        }
    }

    /** How the line is drawn. */
    public enum Line {

        SOLID(null),
        DASHED(new float[]{6f, 4f}),
        DOTTED(new float[]{1.5f, 3f}),
        DASH_DOT(new float[]{7f, 3f, 1.5f, 3f});

        private final float[] pattern;

        Line(float[] pattern) {
            this.pattern = pattern;
        }

        /** @param width how thick, in pixels */
        public Stroke stroke(float width) {
            if (pattern == null) {
                return new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            }

            // Scaled by the width, so a thick dashed line keeps the same rhythm
            // instead of turning into a row of blocks.
            float[] scaled = new float[pattern.length];

            for (int i = 0; i < pattern.length; i++) {
                scaled[i] = Math.max(1f, pattern[i] * Math.max(1f, width * 0.8f));
            }

            return new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                    10f, scaled, 0f);
        }
    }

    /**
     * The colours handed out when nobody chose one.
     *
     * <p>So three averages added one after another do not come out identical.
     * Which one an average gets depends on its period, not on the order it was
     * added: adding the same three in a different order has to give the same
     * chart.</p>
     */
    private static final Color[] AUTOMATIC = {
            new Color(0x4C, 0x9B, 0xE8), new Color(0xE8, 0x8C, 0x3A),
            new Color(0x5E, 0xC2, 0x76), new Color(0xC9, 0x5E, 0xD8),
            new Color(0xD8, 0x5E, 0x5E), new Color(0x4F, 0xC2, 0xC2),
    };

    private int period;

    private Kind kind = Kind.ARITHMETIC;

    private Source source = Source.CLOSE;

    private int shift;

    /** Null means "automatic": chosen from the palette by period. */
    private Color colour;

    private Line line = Line.SOLID;

    private int thickness = 1;

    /**
     * The period this average is computed on, or null to follow the chart.
     *
     * <p>Kept as the CODE the reader would type -- "15m" -- and not as an
     * aggregation, because it has to survive being written into a layout and
     * read back, and prose does not.</p>
     */
    private String ownPeriod;

    /**
     * Whether the line is smoothed between the coarse points it is made of.
     *
     * <p>On by default. Without it a fifteen-minute average drawn over
     * five-minute bars is a staircase, and the steps are an artefact of the
     * drawing rather than anything the market did.</p>
     */
    private boolean interpolate = true;

    private double[] values = new double[0];

    private boolean visible = true;

    public MovingAverage(int period) {
        this.period = Math.max(1, period);
    }

    /**
     * @param settings period, then shift -- the shape, as a layout stores it
     *
     * <p>Period and shift, and the javadoc used to say "period, kind, shift"
     * while the code read {@code settings[1]} as the SHIFT. The other side of
     * this trip agrees with the code and not with the comment: {@link
     * #parameters} writes {@code [period]} or {@code [period, shift]}. Only the
     * sentence was wrong, and it was wrong about the one thing a reader would
     * come here to check.</p>
     *
     * <p>The kind is not in the list because it is not a number. It travels as
     * part of the appearance, which a layout stores beside these.</p>
     */
    public MovingAverage(int... settings) {
        this.period = settings.length > 0 ? Math.max(1, settings[0]) : 9;
        this.shift = settings.length > 1 ? settings[1] : 0;
    }

    // ------------------------------------------------------------- the shape

    public int period() {
        return period;
    }

    public void setPeriod(int value) {
        this.period = Math.max(1, value);
    }

    public Kind kind() {
        return kind;
    }

    public void setKind(Kind value) {
        this.kind = value == null ? Kind.ARITHMETIC : value;
    }

    public Source source() {
        return source;
    }

    public void setSource(Source value) {
        this.source = value == null ? Source.CLOSE : value;
    }

    public int shift() {
        return shift;
    }

    /**
     * @param value how many bars to push the line to the RIGHT; never negative
     *
     * <p><b>Zero is the floor, and it is a domain rule rather than a taste.</b>
     * valueAt reads {@code values[bar - shift]}, so a negative shift pulls the
     * line left and puts, on bar {@code i}, an average worked out over bars up
     * to {@code i + |shift|}. That is the chart reading the future -- the one
     * thing this project refuses everywhere else, and it arrived here by a
     * spinner whose lower bound was -500, which reads like a symmetric range
     * somebody typed rather than a decision anybody made. The class javadoc
     * documents the positive shift and has never mentioned a negative one.</p>
     */
    public void setShift(int value) {
        this.shift = Math.max(0, value);
    }

    // -------------------------------------------------------- the appearance

    /** @return the chosen colour, or null when it is automatic */
    public Color chosenColour() {
        return colour;
    }

    public void setColour(Color value) {
        this.colour = value;
    }

    public Line line() {
        return line;
    }

    public void setLine(Line value) {
        this.line = value == null ? Line.SOLID : value;
    }

    /** @return the period this average uses, or null when it follows the chart */
    public String ownPeriod() {
        return ownPeriod;
    }

    public void setOwnPeriod(String code) {
        this.ownPeriod = code == null || code.isBlank() ? null : code.trim();
    }

    public boolean isInterpolated() {
        return interpolate;
    }

    public void setInterpolated(boolean value) {
        this.interpolate = value;
    }

    public int thickness() {
        return thickness;
    }

    public void setThickness(int value) {
        this.thickness = Math.max(1, Math.min(value, 8));
    }

    @Override
    public Stroke stroke() {
        return line.stroke(thickness);
    }

    @Override
    public List<Color> colours() {
        return List.of(colour == null ? AUTOMATIC[Math.abs(period) % AUTOMATIC.length] : colour);
    }

    // ------------------------------------------------------------ the values

    @Override
    public String nameKey() {
        return "overlay.movingAverage";
    }

    @Override
    public boolean fitsOnPrice() {
        // It IS a price: the average of the closes is measured in points of
        // the index, so it belongs on the same axis as the candles.
        return true;
    }

    @Override
    public List<Integer> parameters() {
        List<Integer> shape = new ArrayList<>(2);

        shape.add(period);

        if (shift != 0) {
            shape.add(shift);
        }

        return shape;
    }

    @Override
    public double[] valueAt(int bar) {
        return new double[]{at(bar)};
    }

    /**
     * @param bar an index into the series this was calculated over
     * @return the one value this line has there, unwrapped
     *
     * <p>The same answer {@link #valueAt} gives, without the array it has to
     * build to give it. For the caller inside the package that reads a whole
     * series of them: BollingerBands asks for the centre of every bar it has,
     * and through valueAt that was one {@code double[1]} per bar per
     * recalculation, thrown away on the next line.</p>
     */
    double at(int bar) {
        int index = bar - shift;

        return index >= 0 && index < values.length ? values[index] : Double.NaN;
    }

    @Override
    public void calculate(PriceSeries series) {
        int size = series == null ? 0 : series.size();

        values = new double[size];

        if (size == 0) {
            return;
        }

        if (ownPeriod != null) {
            onItsOwnPeriod(series);

            return;
        }

        computeOver(series, values);
    }

    private void computeOver(PriceSeries series, double[] into) {
        double[] keep = values;

        values = into;

        try {
            switch (kind) {
                case EXPONENTIAL -> exponential(series);
                case WEIGHTED -> weighted(series);
                default -> arithmetic(series);
            }
        } finally {
            if (into != keep) {
                values = keep;
            }
        }
    }

    /**
     * Computes on a larger scale and lays the result over the chart's bars.
     *
     * <p><b>This is where multi-timeframe indicators usually lie.</b> The
     * obvious mapping takes, for each bar on screen, the coarse bar that
     * CONTAINS it — and that coarse bar is not finished yet: it is made partly
     * of bars to the right of the one being drawn. The line then knows the rest
     * of the hour while standing at its first minute, and every measurement
     * built on it is worth nothing.</p>
     *
     * <p>What is used instead is the <b>last coarse bar that has already
     * closed</b>. The line lags, visibly, by up to one coarse bar. That lag is
     * the truth: at 09:05 nobody knew what the nine o'clock hour would close
     * at.</p>
     */
    private void onItsOwnPeriod(PriceSeries series) {
        br.com.jorge.reis.endeavourneo.domain.market.Aggregation scale = scaleOf();

        if (scale == null) {
            computeOver(series, values);

            return;
        }

        PriceSeries coarse = scale.apply(series);

        if (coarse.size() == 0) {
            java.util.Arrays.fill(values, Double.NaN);

            return;
        }

        double[] slow = new double[coarse.size()];

        computeOver(coarse, slow);

        // The rule itself lives in OwnScale, and in one place only: a second
        // copy of "the last CLOSED coarse bar" is a second chance to write the
        // version that reads the future.
        OwnScale.map(series, coarse, slow, values);

        if (interpolate) {
            OwnScale.smooth(series, coarse, slow, values);
        }
    }



    /** @return the aggregation named by {@link #ownPeriod}, or null when unknown */
    private br.com.jorge.reis.endeavourneo.domain.market.Aggregation scaleOf() {
        return OwnScale.of(ownPeriod);
    }

    private double priceAt(PriceSeries series, int bar) {
        return source.of(series, bar);
    }

    private void arithmetic(PriceSeries series) {
        double running = 0.0;

        for (int i = 0; i < values.length; i++) {
            running += priceAt(series, i);

            if (i >= period) {
                running -= priceAt(series, i - period);
            }

            // NaN until the window is full, never a partial average: a partial
            // one is a different indicator wearing this one's name, and it is
            // wrong exactly where a reader looks first, at the left edge.
            values[i] = i >= period - 1 ? running / period : Double.NaN;
        }
    }

    private void exponential(PriceSeries series) {
        double weight = 2.0 / (period + 1.0);
        double seed = 0.0;

        for (int i = 0; i < values.length; i++) {
            if (i < period - 1) {
                seed += priceAt(series, i);
                values[i] = Double.NaN;
            } else if (i == period - 1) {
                // Seeded with the arithmetic average of the first window, which
                // is what every platform does. Starting from the first price
                // instead leaves a visible hook at the left edge.
                seed += priceAt(series, i);
                values[i] = seed / period;
            } else {
                values[i] = priceAt(series, i) * weight + values[i - 1] * (1 - weight);
            }
        }
    }

    private void weighted(PriceSeries series) {
        double divisor = period * (period + 1) / 2.0;

        for (int i = 0; i < values.length; i++) {
            if (i < period - 1) {
                values[i] = Double.NaN;

                continue;
            }

            double total = 0.0;

            for (int back = 0; back < period; back++) {
                total += priceAt(series, i - back) * (period - back);
            }

            values[i] = total / divisor;
        }
    }

    // ------------------------------------------------------ what is remembered

    @Override
    public String appearance() {
        return kind + ";" + source + ";" + line + ";" + thickness + ";"
                + (colour == null ? "auto" : Integer.toHexString(colour.getRGB() & 0xFFFFFF))
                + ";" + (ownPeriod == null ? "chart" : ownPeriod)
                + ";" + interpolate;
    }

    @Override
    public void applyAppearance(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String[] fields = text.split(";");

        // Field by field, each guarded on its own. A stored line written by a
        // later version may carry a kind this one does not know, and losing the
        // colour because of it would be a second failure caused by the first.
        if (fields.length > 0) {
            setKind(read(Kind.class, fields[0], Kind.ARITHMETIC));
        }

        if (fields.length > 1) {
            setSource(read(Source.class, fields[1], Source.CLOSE));
        }

        if (fields.length > 2) {
            setLine(read(Line.class, fields[2], Line.SOLID));
        }

        if (fields.length > 3) {
            try {
                setThickness(Integer.parseInt(fields[3].trim()));
            } catch (NumberFormatException e) {
                setThickness(1);
            }
        }

        if (fields.length > 4) {
            setColour("auto".equals(fields[4]) ? null : parseColour(fields[4]));
        }

        if (fields.length > 5) {
            setOwnPeriod("chart".equals(fields[5]) ? null : fields[5]);
        }

        if (fields.length > 6) {
            setInterpolated(Boolean.parseBoolean(fields[6]));
        }
    }

    private static <E extends Enum<E>> E read(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static Color parseColour(String hex) {
        try {
            return new Color(Integer.parseInt(hex.trim(), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void setVisible(boolean value) {
        this.visible = value;
    }
}
