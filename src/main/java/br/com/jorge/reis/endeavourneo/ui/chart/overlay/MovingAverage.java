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
        CLOSE, OPEN, HIGH, LOW, MEDIAN, TYPICAL
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

    private double[] values = new double[0];

    private boolean visible = true;

    public MovingAverage(int period) {
        this.period = Math.max(1, period);
    }

    /** @param settings period, kind, shift -- the shape, as a layout stores it */
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

    public void setShift(int value) {
        this.shift = value;
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
        int at = bar - shift;

        return new double[]{at >= 0 && at < values.length ? values[at] : Double.NaN};
    }

    @Override
    public void calculate(PriceSeries series) {
        int size = series == null ? 0 : series.size();

        values = new double[size];

        if (size == 0) {
            return;
        }

        switch (kind) {
            case EXPONENTIAL -> exponential(series);
            case WEIGHTED -> weighted(series);
            default -> arithmetic(series);
        }
    }

    private double priceAt(PriceSeries series, int bar) {
        return switch (source) {
            case OPEN -> series.openAt(bar);
            case HIGH -> series.highAt(bar);
            case LOW -> series.lowAt(bar);
            case MEDIAN -> (series.highAt(bar) + series.lowAt(bar)) / 2.0;
            case TYPICAL -> (series.highAt(bar) + series.lowAt(bar) + series.closeAt(bar)) / 3.0;
            default -> series.closeAt(bar);
        };
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
                + (colour == null ? "auto" : Integer.toHexString(colour.getRGB() & 0xFFFFFF));
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
