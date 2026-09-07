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
package br.com.jorge.reis.endeavourneo.ui.shell;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * The toolbar icons, drawn in code rather than loaded from files.
 *
 * <p><b>Three reasons, and the third is the one that decides it.</b> A drawn
 * icon needs no binary in the repository, so a review can see what changed. It
 * needs no set of sizes for different screen densities, because it is redrawn at
 * whatever size is asked for. And it <b>follows the look and feel</b>: a PNG
 * exported for the light theme becomes a dark smudge on the night one, and
 * nobody notices until they switch.</p>
 *
 * <p>The trade-off is honest: anything with real detail — a logo, an
 * illustration — belongs in a file. These are glyphs of a few rectangles.</p>
 */
public final class Icons {

    private Icons() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** A filled candle: the default drawing style. */
    public static Icon candle(int size) {
        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);

            int mid = w / 2;

            g.drawLine(mid, 1, mid, h - 2);
            g.fillRect(mid - 3, 4, 7, h - 8);
        });
    }

    /**
     * The same candle, hollow.
     *
     * <p>Outline against fill, and the same silhouette: the pair has to read as
     * two settings of one thing rather than as two different tools.</p>
     */
    public static Icon candleHollow(int size) {
        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);

            int mid = w / 2;

            g.drawLine(mid, 1, mid, h - 2);
            g.drawRect(mid - 3, 4, 6, h - 9);
        });
    }

    /** A polyline: the close-only drawing style. */
    public static Icon line(int size) {
        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);
            g.drawPolyline(
                    new int[]{1, w / 3, 2 * w / 3, w - 2},
                    new int[]{h - 3, h / 2, h - 5, 2}, 4);
        });
    }

    /**
     * A double arrow across the height: the vertical scale, back to automatic.
     *
     * <p>Vertical and not four-way, because that is the only axis this button
     * touches — the horizontal scale is set by dragging the time axis.</p>
     */
    public static Icon fitVertical(int size) {
        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);

            int mid = w / 2;

            g.drawLine(mid, 2, mid, h - 3);
            g.drawLine(mid, 2, mid - 3, 5);
            g.drawLine(mid, 2, mid + 3, 5);
            g.drawLine(mid, h - 3, mid - 3, h - 6);
            g.drawLine(mid, h - 3, mid + 3, h - 6);
        });
    }

    /** A small pane settling inside a larger one: docking. */
    public static Icon dock(int size) {
        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);
            g.drawRect(0, 0, w - 1, h - 1);
            g.fillRect(3, 4, w - 7, h - 8);
        });
    }

    /** A pane leaving its frame: floating free. */
    public static Icon undock(int size) {
        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);

            // The frame it leaves is drawn broken on the side it leaves from,
            // so the two icons differ in more than which square is filled.
            g.drawLine(0, 0, w - 6, 0);
            g.drawLine(0, 0, 0, h - 1);
            g.drawLine(0, h - 1, w - 6, h - 1);

            g.drawRect(4, 3, w - 6, h - 7);
        });
    }

    /** A brick with a tail: the renko wick toggle. */
    public static Icon wick(int size) {
        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);

            int mid = w / 2;

            // The tail below and the body above, so the icon reads as the thing
            // the button turns on rather than as a generic candle.
            g.drawLine(mid, h - 2, mid, h - 6);
            g.fillRect(mid - 4, 3, 8, h - 9);
        });
    }

    /**
     * @param size the square side, in pixels
     * @return the "arrange the windows" glyph: four panes in a frame
     */
    public static Icon tile(int size) {
        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);
            g.drawRect(0, 0, w - 1, h - 1);

            int mid = w / 2;

            // Panes rather than a plain cross: the filled quadrant reads as
            // "windows", while four empty squares read as a table or a grid.
            g.drawLine(mid, 1, mid, h - 2);
            g.drawLine(1, h / 2, w - 2, h / 2);

            g.fillRect(1, 1, mid - 1, h / 2 - 1);
            g.fillRect(mid + 1, h / 2 + 1, w - mid - 2, h / 2 - 2);
        });
    }

    /** What a glyph does with the graphics it is handed. */
    @FunctionalInterface
    private interface Glyph {

        void draw(Graphics2D g, int width, int height, Color colour);
    }

    /** An {@link Icon} that paints a glyph in the look and feel's own colour. */
    private record Painted(int size, Glyph glyph) implements Icon {

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create(x, y, size, size);

            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                Color colour = component != null && !component.isEnabled()
                        ? disabled()
                        : foreground();

                glyph.draw(g, size, size, colour);
            } finally {
                g.dispose();
            }
        }

        private static Color foreground() {
            Color colour = UIManager.getColor("Button.foreground");

            return colour != null ? colour : Color.DARK_GRAY;
        }

        private static Color disabled() {
            Color colour = foreground();

            return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 90);
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }
}
