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
package br.com.jorge.reis.endeavourneo.ui.series;

import java.awt.Color;
import javax.swing.UIManager;

/**
 * The colours the segment map draws with.
 *
 * <h2>Read from the theme, except where meaning beats style</h2>
 *
 * <p>The ground and the rules come from the look and feel, for the reason a
 * chart's do: a bar painted a fixed pale grey is invisible on the night theme,
 * and nobody notices until they switch.</p>
 *
 * <p>The segment blocks do not. They are a set of things that have to be told
 * apart from each other, which is a different job from fitting in, and a set
 * that changes hue with the theme cannot be learnt. They are dimmed for the
 * night theme and no more than that.</p>
 */
final class SeriesColors {

    /**
     * The blocks, in the order segments are drawn.
     *
     * <p>Blue through teal to slate: neighbours differ enough to be told
     * apart at a glance, and none of them is red or green, which are spoken
     * for. Six because more than six segments on one series has not happened
     * and the seventh simply repeats -- a colour reused two blocks apart is
     * less confusing than a seventh hue nobody can name.</p>
     */
    private static final int[] TAKEN = {
        0x4A6FA5, 0x3F8E8E, 0x6A5D9B, 0x7C8B99, 0x2F6E8F, 0x8A6A9B
    };

    private SeriesColors() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @return whether the installed theme is a dark one */
    static boolean dark() {
        Color background = UIManager.getColor("Panel.background");

        if (background == null) {
            return false;
        }

        // Perceived brightness, not a plain average: the eye weighs green far
        // more than blue, and a plain average calls some dark themes light.
        double luminance = 0.299 * background.getRed()
                + 0.587 * background.getGreen()
                + 0.114 * background.getBlue();

        return luminance < 128.0;
    }

    /** @return the ground of the bar: the part of the series nothing claims */
    static Color free() {
        Color base = UIManager.getColor("TextField.background");

        return base != null ? base : (dark() ? new Color(0x262C34) : Color.WHITE);
    }

    static Color rule() {
        Color base = UIManager.getColor("Label.foreground");

        if (base == null) {
            return dark() ? new Color(0x555F6B) : new Color(0xB0B0B0);
        }

        return new Color(base.getRed(), base.getGreen(), base.getBlue(), dark() ? 80 : 70);
    }

    /** @return a colour for text that is present but must not compete */
    static Color faint() {
        Color base = UIManager.getColor("Label.foreground");

        if (base == null) {
            return Color.GRAY;
        }

        return new Color(base.getRed(), base.getGreen(), base.getBlue(), 150);
    }

    /** @param at which segment, from zero; beyond the sixth they repeat */
    static Color taken(int at) {
        Color base = new Color(TAKEN[Math.floorMod(at, TAKEN.length)]);

        if (!dark()) {
            return base;
        }

        // Dimmed rather than re-chosen: the same six colours in the same order,
        // just quieter against a dark ground.
        return new Color(Math.round(base.getRed() * 0.82f),
                Math.round(base.getGreen() * 0.82f),
                Math.round(base.getBlue() * 0.82f));
    }

    /** @return the mark for the segment being created */
    static Color fresh() {
        return dark() ? new Color(0xE0A83C) : new Color(0xB8791A);
    }

    /** @return the same mark, for when it lands on a segment that already exists */
    static Color clash() {
        return dark() ? new Color(0xE06A5C) : new Color(0xC0392B);
    }
}
