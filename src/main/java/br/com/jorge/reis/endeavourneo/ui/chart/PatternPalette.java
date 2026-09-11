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

import br.com.jorge.reis.endeavourneo.domain.market.CandlePattern;
import br.com.jorge.reis.endeavourneo.platform.Settings;

import java.awt.Color;

/**
 * Which patterns are painted, and in what.
 *
 * <h2>Why this is a setting and not a property of each indicator</h2>
 *
 * <p>Every other appearance in this chart — the pens of a channel, the colours
 * of a pair of trendlines — lives on the indicator instance, which means two
 * charts can dress the same indicator differently. That is right for a channel:
 * the colour is decoration, and two channels on one screen need telling apart.
 *
 * <p>It is wrong here. The colour of a PFR is not decoration, it is a
 * <b>legend</b> — the reader learns that cyan means a bottom was rejected, and a
 * second chart painting it magenta would be teaching two languages at once. So
 * the six colours and the three switches are the application's, and they follow
 * the reader from chart to chart and across restarts.
 *
 * <p>The side effect is worth naming: an indicator instance carries nothing, so
 * it survives a layout being saved and reopened, which the pens of {@code
 * TouchChannel} currently do not.
 */
public final class PatternPalette {

    /**
     * The colours of the Profit indicator this came from, kept as defaults.
     *
     * <p>Not because they are good — cyan and magenta on a dark chart are loud —
     * but because the point of the first run is to put this chart beside that one
     * and see the same bars painted. Once that check is done they are his to
     * change, which is what the Aparência tab is for.
     */
    private static final int[] ORIGINAL = {
            0,          // NONE, never used
            0x00FFFF,   // PFR de alta
            0xFF00FF,   // PFR de baixa
            0x0000FF,   // inside de alta
            0xFFFF00,   // inside de baixa
            0x0080FF,   // 1-2-3 de compra
            0x800064,   // 1-2-3 de venda
    };

    private PatternPalette() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    private static String colourKey(CandlePattern pattern) {
        return "chart.pattern.colour." + pattern.name();
    }

    private static String onKey(CandlePattern.Family family) {
        return "chart.pattern.on." + family.name();
    }

    /** @return the colour that pattern is painted in */
    public static Color colourOf(CandlePattern pattern) {
        int rgb = Settings.settings().getInt(colourKey(pattern), defaultOf(pattern));

        return new Color(rgb & 0xFFFFFF);
    }

    /** @return the colour it starts life with, which is the original indicator's */
    public static int defaultOf(CandlePattern pattern) {
        return ORIGINAL[pattern.ordinal()];
    }

    public static void setColour(CandlePattern pattern, Color colour) {
        if (colour != null) {
            Settings.settings().putInt(colourKey(pattern), colour.getRGB() & 0xFFFFFF);
        }
    }

    /**
     * @return whether that shape is painted at all
     *
     * <p>All three on to begin with: an indicator that is inserted and paints
     * nothing reads as broken, and the reader has no way of knowing which switch
     * to look for.
     */
    public static boolean shows(CandlePattern.Family family) {
        return family != CandlePattern.Family.NONE
                && Settings.settings().getBoolean(onKey(family), true);
    }

    public static void setShows(CandlePattern.Family family, boolean shows) {
        if (family != CandlePattern.Family.NONE) {
            Settings.settings().putBoolean(onKey(family), shows);
        }
    }

    /**
     * @param pattern what the bar formed
     * @return the colour to paint it, or null to leave it the usual up or down
     */
    public static Color tintFor(CandlePattern pattern) {
        return pattern == null || pattern.isNone() || !shows(pattern.family())
                ? null : colourOf(pattern);
    }
}
