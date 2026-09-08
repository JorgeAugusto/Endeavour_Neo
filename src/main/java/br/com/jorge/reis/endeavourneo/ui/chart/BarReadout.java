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
package br.com.jorge.reis.endeavourneo.ui.chart;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import br.com.jorge.reis.endeavourneo.platform.Appearance;
import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The summary box that follows the cursor, describing one bar.
 *
 * <p>It answers what the reader is actually asking when they put the mouse on a
 * candle: what were the four prices, how far did it move, and when was it. The
 * chart shows the shape; only a readout gives the numbers.</p>
 *
 * <p><b>A painted box rather than a Swing tooltip.</b> A {@code JToolTip} waits
 * before appearing, vanishes on a timer and flickers when the mouse moves along
 * a row of candles — behaviour tuned for explaining a button, not for reading a
 * series. Painting it means it is simply there, following the mouse with no
 * delay at all.</p>
 *
 * <p>Its own class because {@link ChartCanvas} already paints five layers, and
 * this is the sixth. Layout arithmetic mixed into the canvas is how a chart
 * component grows past a thousand lines and stops accepting new layers.</p>
 */
final class BarReadout {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Shared with the ruler's box; see Readouts. */
    private static final int PADDING = Readouts.PADDING;

    /** Shared with the ruler's box; see Readouts. */
    private static final int OFFSET = Readouts.OFFSET;

    private static final float ALPHA = Readouts.ALPHA;

    private BarReadout() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * Paints the summary of one bar near the cursor.
     *
     * @param g where to draw
     * @param series the data
     * @param index which bar
     * @param cursor where the mouse is
     * @param area the whole component, used to keep the box on screen
     */
    static void paint(Graphics2D g, PriceSeries series, int index, Point cursor, Rectangle area) {
        if (index < 0 || index >= series.size()) {
            return;
        }

        Font labelFont = g.getFont().deriveFont(11f);
        Font valueFont = Appearance.monospaced(11);

        List<String[]> rows = rowsFor(series, index);
        String title = Messages.get("readout.title",
                Instant.ofEpochMilli(series.timeAt(index))
                        .atZone(br.com.jorge.reis.endeavourneo.domain.market.Timeframe.defaultZone()).format(STAMP));

        FontMetrics labels = g.getFontMetrics(labelFont);
        FontMetrics values = g.getFontMetrics(valueFont);

        int labelWidth = 0;
        int valueWidth = 0;

        for (String[] row : rows) {
            labelWidth = Math.max(labelWidth, labels.stringWidth(row[0]));
            valueWidth = Math.max(valueWidth, values.stringWidth(row[1]));
        }

        int lineHeight = Math.max(labels.getHeight(), values.getHeight());
        int width = Math.max(labels.stringWidth(title), labelWidth + 16 + valueWidth)
                + PADDING * 2;
        int height = lineHeight * (rows.size() + 1) + PADDING * 2 + 4;

        Rectangle box = place(cursor, width, height, area);

        // The box has to be readable over candles, so it is nearly opaque. Fully
        // opaque would hide the bar being described, which is the one the reader
        // is looking at.
        g.setColor(Readouts.withAlpha(ChartColors.background(), ALPHA));
        g.fillRect(box.x, box.y, box.width, box.height);

        g.setColor(ChartColors.grid());
        g.drawRect(box.x, box.y, box.width - 1, box.height - 1);

        int y = box.y + PADDING + labels.getAscent();

        g.setFont(labelFont);
        g.setColor(ChartColors.foreground());
        g.drawString(title, box.x + PADDING, y);

        y += 4;

        for (String[] row : rows) {
            y += lineHeight;

            g.setFont(labelFont);
            g.setColor(Readouts.withAlpha(ChartColors.foreground(), 0.7f));
            g.drawString(row[0], box.x + PADDING, y);

            // Values right-aligned and in a fixed-width font: numbers in a
            // column only line up that way, and a column that does not line up
            // is read one row at a time instead of at a glance.
            g.setFont(valueFont);
            g.setColor(row.length > 2 ? Readouts.colourOf(row[2]) : ChartColors.foreground());
            g.drawString(row[1],
                    box.x + box.width - PADDING - values.stringWidth(row[1]), y);
        }
    }

    private static List<String[]> rowsFor(PriceSeries series, int index) {
        double open = series.openAt(index);
        double high = series.highAt(index);
        double low = series.lowAt(index);
        double close = series.closeAt(index);
        double change = close - open;

        DecimalFormat price = Readouts.format(decimalsFor(high - low));
        DecimalFormat percent = Readouts.format(2);

        List<String[]> rows = new ArrayList<>();

        rows.add(new String[]{Messages.get("readout.open"), price.format(open)});
        rows.add(new String[]{Messages.get("readout.high"), price.format(high)});
        rows.add(new String[]{Messages.get("readout.low"), price.format(low)});
        rows.add(new String[]{Messages.get("readout.close"), price.format(close)});

        String sign = change > 0 ? "+" : "";
        String movement = sign + price.format(change)
                + (open != 0.0 ? "  (" + sign + percent.format(100.0 * change / open) + "%)" : "");

        rows.add(new String[]{Messages.get("readout.change"), movement,
                change > 0 ? "up" : change < 0 ? "down" : "flat"});

        // Against the PREVIOUS BAR's close, which is a different question from
        // close-minus-open: a bar can close above its own open and be below
        // where the one before it closed.
        //
        // NOT the previous SESSION's close, which is what a quote screen
        // answers and what this comment used to claim. On a one-minute chart
        // the two are the same thing only at the first bar of the day. The
        // session comparison lives in Sessions, which walks back to the
        // previous day's last bar to find it.
        if (index > 0) {
            double previous = series.closeAt(index - 1);
            double session = close - previous;
            String sessionSign = session > 0 ? "+" : "";
            String text = sessionSign + price.format(session)
                    + (previous != 0.0
                            ? "  (" + sessionSign
                                    + percent.format(100.0 * session / previous) + "%)"
                            : "");

            rows.add(new String[]{Messages.get("readout.fromPrevious"), text,
                    session > 0 ? "up" : session < 0 ? "down" : "flat"});
        }

        rows.add(new String[]{Messages.get("readout.range"), price.format(high - low)});

        double volume = series.volumeAt(index);

        // Absent rather than zero when the series has no volume. A row reading
        // "0" would be a claim, and the wrong one.
        if (Double.isFinite(volume)) {
            rows.add(new String[]{Messages.get("readout.volume"), Readouts.format(0).format(volume)});
        }

        // Counted rather than guessed, and only a source where one bar is one
        // trade can answer -- see Counted. Absent, never zero, when it cannot:
        // a row reading "0" would be a claim, and the wrong one.
        long trades = br.com.jorge.reis.endeavourneo.domain.market.Counted.at(series, index);

        if (trades >= 0) {
            rows.add(new String[]{Messages.get("readout.trades"),
                    Readouts.format(0).format(trades)});
        } else if (br.com.jorge.reis.endeavourneo.domain.market.Untraded.at(series, index)) {
            // A renko built from candles cannot count, but it can still say
            // that nothing was traded in this band -- which is worth saying out
            // loud: the reader is looking at a bar whose open and close are as
            // real as any other and whose SHAPE was drawn by arithmetic,
            // because the market was shut. See Untraded.
            rows.add(new String[]{Messages.get("readout.trades"),
                    Messages.get("readout.untraded.none")});
        }

        return rows;
    }

    /**
     * Keeps the box on screen, flipping sides near an edge.
     *
     * <p>Without the flip the box runs off the right edge exactly when the
     * reader is looking at the most recent bars, which is most of the time.</p>
     */
    private static Rectangle place(Point cursor, int width, int height, Rectangle area) {
        int x = cursor.x + OFFSET;
        int y = cursor.y + OFFSET;

        if (x + width > area.width) {
            x = cursor.x - OFFSET - width;
        }
        if (y + height > area.height) {
            y = cursor.y - OFFSET - height;
        }

        return new Rectangle(Math.max(4, x), Math.max(4, y), width, height);
    }

    private static int decimalsFor(double range) {
        if (range >= 10.0) {
            return 0;
        }

        return range >= 0.1 ? 2 : 4;
    }
}
