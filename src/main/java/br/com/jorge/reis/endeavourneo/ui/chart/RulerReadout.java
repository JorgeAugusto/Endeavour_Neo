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

import br.com.jorge.reis.endeavourneo.platform.Appearance;
import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The box the ruler shows: what the two points are worth apart.
 *
 * <p>Both the price move and the time span, and both stated rather than left to
 * be inferred. Six candles is not an hour and a half if a night sits between
 * them, and a projection drawn from the wrong one is wrong by the size of the
 * session break.</p>
 */
final class RulerReadout {

    private static final int PADDING = 10;

    private static final int OFFSET = 16;

    private static final float ALPHA = 0.94f;

    private RulerReadout() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    static void paint(Graphics2D g, Measurement measurement, Point anchor, Rectangle area) {
        Font labelFont = g.getFont().deriveFont(11f);
        Font valueFont = Appearance.monospaced(11);

        List<String[]> rows = rowsFor(measurement);

        FontMetrics labels = g.getFontMetrics(labelFont);
        FontMetrics values = g.getFontMetrics(valueFont);

        int labelWidth = 0;
        int valueWidth = 0;

        for (String[] row : rows) {
            labelWidth = Math.max(labelWidth, labels.stringWidth(row[0]));
            valueWidth = Math.max(valueWidth, values.stringWidth(row[1]));
        }

        int lineHeight = Math.max(labels.getHeight(), values.getHeight());
        int width = labelWidth + 18 + valueWidth + PADDING * 2;
        int height = lineHeight * rows.size() + PADDING * 2;

        Rectangle box = place(anchor, width, height, area);

        g.setColor(withAlpha(ChartColors.background(), ALPHA));
        g.fillRect(box.x, box.y, box.width, box.height);

        g.setColor(ChartColors.grid());
        g.drawRect(box.x, box.y, box.width - 1, box.height - 1);

        int y = box.y + PADDING;

        for (String[] row : rows) {
            y += lineHeight;

            g.setFont(labelFont);
            g.setColor(withAlpha(ChartColors.foreground(), 0.7f));
            g.drawString(row[0], box.x + PADDING, y - 4);

            g.setFont(valueFont);
            g.setColor(row.length > 2 ? colourOf(row[2]) : ChartColors.foreground());
            g.drawString(row[1], box.x + box.width - PADDING - values.stringWidth(row[1]), y - 4);
        }
    }

    private static List<String[]> rowsFor(Measurement measurement) {
        double difference = measurement.difference();
        double percent = measurement.percent();

        DecimalFormat price = format(2);
        DecimalFormat plain = format(0);

        String mood = difference > 0 ? "up" : difference < 0 ? "down" : "flat";
        String sign = difference > 0 ? "+" : "";

        List<String[]> rows = new ArrayList<>();

        rows.add(new String[]{Messages.get("ruler.change"),
                sign + price.format(difference) + Messages.get("ruler.points"), mood});

        if (Double.isFinite(percent)) {
            rows.add(new String[]{"", sign + price.format(percent) + "%", mood});
        }

        rows.add(new String[]{Messages.get("ruler.difference"), plain.format(difference)});
        rows.add(new String[]{Messages.get("ruler.first"), plain.format(measurement.fromPrice())});
        rows.add(new String[]{Messages.get("ruler.second"), plain.format(measurement.toPrice())});
        rows.add(new String[]{Messages.get("ruler.interval"), measurement.elapsedInWords()});
        rows.add(new String[]{Messages.get("ruler.bars"), String.valueOf(measurement.bars())});

        return rows;
    }

    /**
     * Puts the box beside the second point, flipping at the edges.
     *
     * <p>Beside the end of the drag rather than at a fixed corner: the box then
     * sits where the eye already is, and does not cover the stretch of chart
     * being measured.</p>
     */
    private static Rectangle place(Point anchor, int width, int height, Rectangle area) {
        int x = anchor.x + OFFSET;
        int y = anchor.y - height - OFFSET;

        if (x + width > area.width) {
            x = anchor.x - OFFSET - width;
        }
        if (y < 0) {
            y = anchor.y + OFFSET;
        }

        return new Rectangle(Math.max(4, x),
                Math.max(4, Math.min(y, area.height - height - 4)), width, height);
    }

    private static DecimalFormat format(int decimals) {
        StringBuilder pattern = new StringBuilder("#,##0");

        if (decimals > 0) {
            pattern.append('.').append("0".repeat(decimals));
        }

        return new DecimalFormat(pattern.toString(),
                DecimalFormatSymbols.getInstance(Locale.getDefault()));
    }

    private static Color colourOf(String mood) {
        return switch (mood) {
            case "up" -> ChartColors.up();
            case "down" -> ChartColors.down();
            default -> ChartColors.foreground();
        };
    }

    private static Color withAlpha(Color colour, float alpha) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(),
                Math.round(alpha * 255));
    }
}
