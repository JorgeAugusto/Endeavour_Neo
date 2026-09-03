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
package br.com.jorge.reis.endeavourneo.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import javax.swing.UIManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Switching themes, in both directions, more than once.
 *
 * <p>Written after a bug that only appeared on the SECOND change: night
 * registers a palette with FlatLaf, that registration is global and permanent,
 * and nothing undid it. Dark installed afterwards came back wearing night's
 * background, text and amber accent — so the two themes looked identical, and
 * only restarting the application separated them again.</p>
 *
 * <p>Which is why every assertion here is about a SEQUENCE. Installing each
 * theme once and looking at it proves nothing about this defect: the first
 * install of each was always right.</p>
 */
@DisplayName("Theme switching")
class ThemeSwitchTest {

    private static Color background() {
        return UIManager.getColor("Panel.background");
    }

    @Test
    @DisplayName("dark comes back as dark after a visit to night")
    void darkSurvivesNight() {
        Appearance.install(Theme.DARK);

        Color darkFirst = background();

        assumeTrue(darkFirst != null, "no look and feel to compare");

        Appearance.install(Theme.NIGHT);

        Color night = background();

        assertNotEquals(darkFirst, night,
                "night and dark painted the same background, so the palette never loaded");

        Appearance.install(Theme.DARK);

        assertEquals(darkFirst, background(),
                "dark came back wearing night's palette: nothing unregistered it");
    }

    @Test
    @DisplayName("night is still night after a visit to dark")
    void nightSurvivesDark() {
        Appearance.install(Theme.NIGHT);

        Color nightFirst = background();

        assumeTrue(nightFirst != null, "no look and feel to compare");

        Appearance.install(Theme.DARK);
        Appearance.install(Theme.NIGHT);

        assertEquals(nightFirst, background(),
                "night lost its palette on the way back");
    }

    @Test
    @DisplayName("night reports itself as night, not as dark with a missing palette")
    void nightFindsItsPalette() {
        // The palette lives in src/main/resources/themes and is found by the
        // look and feel's class name. A rename on either side would leave the
        // theme silently installing as plain dark -- install() says so, and this
        // is where that sentence gets read.
        String installed = Appearance.install(Theme.NIGHT);

        assumeTrue(installed.startsWith("FlatLaf"), "FlatLaf is not on the classpath");
        assertTrue(installed.contains("night") && !installed.contains("missing"),
                "night installed without its palette: " + installed);
    }

    @Test
    @DisplayName("light is not left tinted by either dark theme")
    void lightSurvivesBoth() {
        Appearance.install(Theme.LIGHT);

        Color lightFirst = background();

        assumeTrue(lightFirst != null, "no look and feel to compare");

        Appearance.install(Theme.NIGHT);
        Appearance.install(Theme.LIGHT);

        assertEquals(lightFirst, background());
    }
}
