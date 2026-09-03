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
package br.com.jorge.reis.endeavourneo.ui.replay;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * The transport glyphs, drawn rather than shipped.
 *
 * <p>Drawn because an image file would need one copy per size and one palette
 * per theme, and these are five shapes anybody can describe in a sentence.</p>
 */
final class ReplayIcons {

    private ReplayIcons() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    static Icon play(int size) {
        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);
            g.fillPolygon(new int[]{3, 3, w - 3}, new int[]{2, h - 2, h / 2}, 3);
        });
    }

    static Icon pause(int size) {
        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);
            g.fillRect(3, 2, 4, h - 4);
            g.fillRect(w - 7, 2, 4, h - 4);
        });
    }

    static Icon back(int size) {
        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);
            g.fillPolygon(new int[]{w / 2, w / 2, 2}, new int[]{2, h - 2, h / 2}, 3);
            g.fillPolygon(new int[]{w - 2, w - 2, w / 2}, new int[]{2, h - 2, h / 2}, 3);
        });
    }

    static Icon forward(int size) {
        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);
            g.fillPolygon(new int[]{2, 2, w / 2}, new int[]{2, h - 2, h / 2}, 3);
            g.fillPolygon(new int[]{w / 2, w / 2, w - 2}, new int[]{2, h - 2, h / 2}, 3);
        });
    }

    @FunctionalInterface
    private interface Glyph {
        void draw(Graphics2D g, int width, int height, Color colour);
    }

    /** Follows the theme by reading its foreground at paint time, not at build time. */
    private static final class Painted implements Icon {

        private final int size;

        private final Glyph glyph;

        Painted(int size, Glyph glyph) {
            this.size = size;
            this.glyph = glyph;
        }

        @Override
        public void paintIcon(Component on, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create(x, y, size, size);

            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(1.4f));

                Color ink = on != null && !on.isEnabled() ? disabled() : foreground();

                glyph.draw(g, size, size, ink);
            } finally {
                g.dispose();
            }
        }

        private static Color foreground() {
            Color colour = UIManager.getColor("Button.foreground");

            return colour == null ? Color.DARK_GRAY : colour;
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
