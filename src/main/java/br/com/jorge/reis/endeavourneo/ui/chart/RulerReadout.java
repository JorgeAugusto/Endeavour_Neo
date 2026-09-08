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

import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * The box the ruler shows: what the two points are worth apart.
 *
 * <p>Both the price move and the time span, and both stated rather than left to
 * be inferred. Six candles is not an hour and a half if a night sits between
 * them, and a projection drawn from the wrong one is wrong by the size of the
 * session break.</p>
 */
final class RulerReadout {

    private static final int PADDING = Readouts.PADDING;

    /** Shared with the bar's box; see Readouts. */
    private static final int OFFSET = Readouts.OFFSET;

    private RulerReadout() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    static void paint(Graphics2D g, Measurement measurement, Point anchor, Rectangle area) {
        // The box itself is drawn by Readouts, which BarReadout also uses:
        // the two carried identical copies of the number format, the mood
        // colour, the translucency, the padding and the whole
        // measure-fill-frame-rows routine. What is this class's own is where
        // the box goes and what is in it.
        List<String[]> rows = rowsFor(measurement);
        Readouts.Fonts fonts = Readouts.Fonts.of(g);
        java.awt.Dimension size = Readouts.sizeOf(g, rows, fonts);

        Readouts.draw(g, rows, fonts, place(anchor, size.width, size.height, area));
    }

    static List<String[]> rowsFor(Measurement measurement) {
        double difference = measurement.difference();
        double percent = measurement.percent();

        DecimalFormat price = Readouts.format(2);
        DecimalFormat plain = Readouts.format(0);

        // THE SAME NUMBER WAS WRITTEN TWICE, under two labels. "Change" showed
        // `difference` with two decimals and " pts"; "Difference" showed the
        // SAME variable rounded to none, so a measurement of 12,50 points read
        // +12,50 pts on one line and 13 on the next. Two answers to "how much is
        // this", one of them rounded, with nothing saying they were the same
        // question -- and on an instrument whose tick is five points that is
        // visible immediately.
        //
        // The second line is gone rather than given a different meaning. The
        // useful thing it might have said -- the distance in TICKS -- needs the
        // tick size, and this box does not have it; inventing a number here
        // would be a third answer.

        String mood = difference > 0 ? "up" : difference < 0 ? "down" : "flat";
        String sign = difference > 0 ? "+" : "";

        List<String[]> rows = new ArrayList<>();

        rows.add(new String[]{Messages.get("ruler.change"),
                sign + price.format(difference) + Messages.get("ruler.points"), mood});

        if (Double.isFinite(percent)) {
            rows.add(new String[]{"", sign + price.format(percent) + "%", mood});
        }

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
}
