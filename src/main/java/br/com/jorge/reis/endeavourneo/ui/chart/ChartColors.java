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

import java.awt.Color;
import javax.swing.UIManager;

/**
 * The colours a chart draws with, taken from the look and feel.
 *
 * <p><b>Hard-coded colours are what makes a chart unreadable in the other
 * theme.</b> A candle body painted white looks right on the light theme and
 * disappears on the night one, and nobody notices until they switch. So the
 * neutrals are read from the {@code UIManager} at paint time and follow
 * whatever theme is installed.</p>
 *
 * <p>Up and down are the exception: they carry meaning rather than style, and
 * green-up/red-down is the convention the reader already has. They are only
 * adjusted for legibility on a dark ground.</p>
 */
public final class ChartColors {

    private ChartColors() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @return whether the installed theme is a dark one */
    public static boolean dark() {
        Color background = UIManager.getColor("Panel.background");

        if (background == null) {
            return false;
        }

        // Perceived brightness, not the plain average: the eye weighs green far
        // more than blue, and a plain average calls some dark themes light.
        double luminance = 0.299 * background.getRed()
                + 0.587 * background.getGreen()
                + 0.114 * background.getBlue();

        return luminance < 128.0;
    }

    public static Color background() {
        Color c = UIManager.getColor("TextArea.background");

        return c != null ? c : (dark() ? new Color(0x1E1E1E) : Color.WHITE);
    }

    public static Color foreground() {
        Color c = UIManager.getColor("Label.foreground");

        return c != null ? c : (dark() ? new Color(0xD0D0D0) : Color.DARK_GRAY);
    }

    /**
     * The colours that do not come from the look and feel, held per theme.
     *
     * <p><b>Built once each, and they used to be built per element drawn.</b>
     * Every candle asked for {@code up()} or {@code down()} and got a new {@code
     * Color}; every grid line asked for {@code grid()} and got a new one plus a
     * lookup in the {@code UIManager}. At a hundred thousand bars that is a
     * hundred thousand objects per repaint, for six values that only change when
     * the reader changes theme.</p>
     *
     * <p>Keyed on the answer to {@link #dark}, so switching theme swaps the set
     * rather than staling it. Nothing here caches what the look and feel owns --
     * {@code background} and {@code foreground} still ask it every time, because
     * it is the one that knows when they change.</p>
     */
    private static final Color[] UP = {new Color(0x1B7F3B), new Color(0x4CAF50)};

    private static final Color[] UNTRADED = {new Color(0x9E9E9E), new Color(0x8C8C8C)};

    private static final Color[] DOWN = {new Color(0xC62828), new Color(0xE05252)};

    /** @return the grid colour: present, but never competing with the data */
    public static Color grid() {
        Color base = foreground();
        boolean night = dark();

        if (!base.equals(gridFrom) || night != gridDark) {
            gridFrom = base;
            gridDark = night;
            grid = new Color(base.getRed(), base.getGreen(), base.getBlue(), night ? 38 : 30);
        }

        return grid;
    }

    private static Color grid;

    private static Color gridFrom;

    private static boolean gridDark;

    public static Color up() {
        return UP[dark() ? 1 : 0];
    }

    public static Color down() {
        return DOWN[dark() ? 1 : 0];
    }

    /**
     * @return the colour of a bar nothing traded inside
     *
     * <p>Grey, and deliberately colourless: up and down carry meaning, and a
     * brick laid across an overnight gap did not go up or down -- the market
     * was shut. Painting it green because its close is above its open would be
     * the chart claiming a rally that nobody took part in.</p>
     *
     * <p>Neutral rather than faint. It has to survive being surrounded by
     * fourteen of its own kind and still read as a brick; a wash would look
     * like a rendering fault. See {@link
     * br.com.jorge.reis.endeavourneo.domain.market.Untraded}.</p>
     */
    public static Color untraded() {
        return UNTRADED[dark() ? 1 : 0];
    }
}
