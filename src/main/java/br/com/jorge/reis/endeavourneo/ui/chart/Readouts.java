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

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

/**
 * The little box a chart puts numbers in, drawn once for both of them.
 *
 * <p><b>Two classes carried the same four helpers.</b> {@code BarReadout} and
 * {@code RulerReadout} each had their own copy of the number format, the mood
 * colour, the translucency and the padding, word for word. Two answers to one
 * question is two places to change and one of them gets missed -- and the two
 * had already drifted, one offsetting the box by eighteen pixels and the other
 * by sixteen, for no reason either of them states.</p>
 *
 * <p><b>The drawing itself is shared by the ruler only, and that is
 * deliberate.</b> The bar readout puts a TITLE above its rows -- the bar's
 * timestamp -- so its box is a different shape and measures differently.
 * Forcing both through one routine would mean a title parameter that one
 * caller always passes null for, which is a shared method pretending two
 * things are the same. What is genuinely identical is here; what is not stayed
 * where it is.</p>
 */
final class Readouts {

    /** The margin inside the frame, on all four sides. */
    static final int PADDING = 10;

    /**
     * How solid the ground is.
     *
     * <p>Not opaque: a reader wants to see the candle the box is telling them
     * about. Not far from it either -- text over a grid is text nobody reads.
     * </p>
     */
    static final float ALPHA = 0.94f;

    private Readouts() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param decimals how many places after the point
     * @return a format for a price, in the reader's own separators
     */
    static DecimalFormat format(int decimals) {
        StringBuilder pattern = new StringBuilder("#,##0");

        if (decimals > 0) {
            pattern.append('.').append("0".repeat(decimals));
        }

        return new DecimalFormat(pattern.toString(),
                DecimalFormatSymbols.getInstance(Locale.getDefault()));
    }

    /**
     * @param mood {@code "up"}, {@code "down"}, or anything else
     * @return the ink a value of that mood is written in
     *
     * <p>The mood travels as a string because a row is a {@code String[]} and
     * the third slot is optional. An enum would say it better; changing the
     * carrier is a larger change than having one copy of this switch, and it is
     * the copies that this fixes.</p>
     */
    static Color colourOf(String mood) {
        return switch (mood) {
            case "up" -> ChartColors.up();
            case "down" -> ChartColors.down();
            default -> ChartColors.foreground();
        };
    }

    /** @return the same colour, that much of the way to transparent */
    static Color withAlpha(Color colour, float alpha) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(),
                Math.round(alpha * 255));
    }

    /**
     * The two fonts a readout uses: one for the labels, one for the numbers.
     *
     * @param label the label font
     * @param value the value font, monospaced so digits line up down the column
     */
    record Fonts(Font label, Font value) {

        static Fonts of(Graphics2D g) {
            return new Fonts(g.getFont().deriveFont(11f), Appearance.monospaced(11));
        }
    }

    /**
     * @param rows label and value, plus an optional mood
     * @return how big the box has to be to hold them
     */
    static java.awt.Dimension sizeOf(Graphics2D g, List<String[]> rows, Fonts fonts) {
        FontMetrics labels = g.getFontMetrics(fonts.label());
        FontMetrics values = g.getFontMetrics(fonts.value());

        int labelWidth = 0;
        int valueWidth = 0;

        for (String[] row : rows) {
            labelWidth = Math.max(labelWidth, labels.stringWidth(row[0]));
            valueWidth = Math.max(valueWidth, values.stringWidth(row[1]));
        }

        int lineHeight = Math.max(labels.getHeight(), values.getHeight());

        return new java.awt.Dimension(labelWidth + 18 + valueWidth + PADDING * 2,
                lineHeight * rows.size() + PADDING * 2);
    }

    /**
     * Draws the ground, the frame and every row.
     *
     * @param box where it goes, already placed by whoever is drawing
     */
    static void draw(Graphics2D g, List<String[]> rows, Fonts fonts, Rectangle box) {
        FontMetrics labels = g.getFontMetrics(fonts.label());
        FontMetrics values = g.getFontMetrics(fonts.value());
        int lineHeight = Math.max(labels.getHeight(), values.getHeight());

        g.setColor(withAlpha(ChartColors.background(), ALPHA));
        g.fillRect(box.x, box.y, box.width, box.height);

        g.setColor(ChartColors.grid());
        g.drawRect(box.x, box.y, box.width - 1, box.height - 1);

        int y = box.y + PADDING;

        for (String[] row : rows) {
            y += lineHeight;

            g.setFont(fonts.label());
            g.setColor(withAlpha(ChartColors.foreground(), 0.7f));
            g.drawString(row[0], box.x + PADDING, y - 4);

            g.setFont(fonts.value());
            g.setColor(row.length > 2 ? colourOf(row[2]) : ChartColors.foreground());
            g.drawString(row[1], box.x + box.width - PADDING - values.stringWidth(row[1]), y - 4);
        }
    }
}
