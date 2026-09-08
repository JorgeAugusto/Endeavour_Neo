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

    /**
     * The triangle, worked out once and not on every repaint.
     *
     * <p><b>Outside the lambda, which is the whole of the trick.</b> Every
     * glyph here built its {@code int[]} pairs inside the painting -- eight
     * arrays in {@code drag} alone -- and the transport repaints twenty-five
     * times a second while a session plays. The arrays depend only on the size,
     * the size is fixed when the icon is made, and the icons are made once. So
     * they are computed here and closed over.</p>
     *
     * <p>No cache and no map: the alternative considered was keeping the shapes
     * against the size in a map, which is machinery this class -- a list of
     * literals -- has no other use for. The lambda ignores its {@code w} and
     * {@code h} because for a given icon they ARE the size.</p>
     */
    static Icon play(int size) {
        int[] xs = {3, 3, size - 3};
        int[] ys = {2, size - 2, size / 2};

        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);
            g.fillPolygon(xs, ys, 3);
        });
    }

    static Icon pause(int size) {
        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);
            g.fillRect(3, 2, 4, h - 4);
            g.fillRect(w - 7, 2, 4, h - 4);
        });
    }

    /**
     * A square, because stopping is not a smaller pause.
     *
     * <p>The universal transport symbol, and worth keeping universal: a reader
     * who has to work out what a button does before pressing it will not press
     * it.</p>
     */
    static Icon stop(int size) {
        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);
            g.fillRect(3, 3, w - 6, h - 6);
        });
    }

    /** Two triangles pointing left. See {@link #play} about the arrays. */
    static Icon back(int size) {
        int[] leftX = {size / 2, size / 2, 2};
        int[] rightX = {size - 2, size - 2, size / 2};
        int[] ys = {2, size - 2, size / 2};

        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);
            g.fillPolygon(leftX, ys, 3);
            g.fillPolygon(rightX, ys, 3);
        });
    }

    /** Two triangles pointing right. See {@link #play} about the arrays. */
    static Icon forward(int size) {
        int[] leftX = {2, 2, size / 2};
        int[] rightX = {size / 2, size / 2, size - 2};
        int[] ys = {2, size - 2, size / 2};

        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);
            g.fillPolygon(leftX, ys, 3);
            g.fillPolygon(rightX, ys, 3);
        });
    }

    /**
     * The four-way arrow: this can be picked up and carried.
     *
     * <p>Beside the market's name in the transport, because the name IS the
     * handle -- the session is taken to a chart by dragging it, and a label
     * that behaves like a control has to say so. Without the cross the gesture
     * is only discoverable by accident, and the reference product marks the
     * same label the same way.</p>
     */
    static Icon drag(int size) {
        // Eight arrays, and they were all built on every repaint. See play().
        int cx = size / 2;
        int cy = size / 2;
        int arm = size / 2 - 1;
        int head = Math.max(2, arm / 2);

        int[] acrossX = {cx - head, cx + head, cx};
        int[] upY = {cy - arm + head, cy - arm + head, cy - arm};
        int[] downY = {cy + arm - head, cy + arm - head, cy + arm};

        int[] leftX = {cx - arm + head, cx - arm + head, cx - arm};
        int[] rightX = {cx + arm - head, cx + arm - head, cx + arm};
        int[] acrossY = {cy - head, cy + head, cy};

        return new Painted(size, (g, w, h, colour) -> {
            g.setColor(colour);
            g.setStroke(HAIRLINE);

            g.drawLine(cx, cy - arm, cx, cy + arm);
            g.drawLine(cx - arm, cy, cx + arm, cy);

            g.fillPolygon(acrossX, upY, 3);
            g.fillPolygon(acrossX, downY, 3);
            g.fillPolygon(leftX, acrossY, 3);
            g.fillPolygon(rightX, acrossY, 3);
        });
    }

    /**
     * The line the glyphs are drawn with.
     *
     * <p>One and a bit pixels, to match the rest of the transport. It was a
     * {@code new BasicStroke} inside the painting, which runs on every repaint
     * of every icon.</p>
     */
    private static final BasicStroke PEN = new BasicStroke(1.4f);

    /** The thinner line the four-way arrow's shaft is drawn with. */
    private static final BasicStroke HAIRLINE = new BasicStroke(1.0f);

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
                g.setStroke(PEN);

                Color ink = on != null && !on.isEnabled() ? disabled() : foreground(on);

                glyph.draw(g, size, size, ink);
            } finally {
                g.dispose();
            }
        }

        /**
         * @param on the component the icon is being drawn on, or null
         * @return the ink to draw it in
         *
         * <p><b>The component's own colour first.</b> These glyphs used to take
         * the theme's button colour and nothing else, which is right for a
         * button and wrong for anything that paints its own ground: the replay
         * handle is dark with white text in both themes, and a dark-on-dark
         * arrow would simply not be there.</p>
         */
        private static Color foreground(Component on) {
            if (on != null && on.getForeground() != null) {
                return on.getForeground();
            }

            Color colour = UIManager.getColor("Button.foreground");

            return colour == null ? Color.DARK_GRAY : colour;
        }

        /**
         * The same ink, faded, built once per theme.
         *
         * <p>It was built on every paint, and the transport repaints twenty-five
         * times a second while a session is playing -- so did the stroke above.
         * Neither varies with anything the caller passes.</p>
         *
         * <p>Kept against the colour it was faded FROM: a theme change gives a
         * different foreground, and comparing the two is how this notices
         * without having to be told.</p>
         */
        private static Color disabled() {
            Color colour = foreground(null);

            if (!colour.equals(fadedFrom)) {
                fadedFrom = colour;
                faded = new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 90);
            }

            return faded;
        }

        private static Color fadedFrom;

        private static Color faded = Color.GRAY;

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
