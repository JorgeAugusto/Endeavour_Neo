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


/**
 * The application's colour themes.
 *
 * <p>{@link #NIGHT} is not "dark, but darker". Dark and night solve different
 * problems:</p>
 *
 * <ul>
 *   <li><b>DARK</b> is an aesthetic preference. It keeps the blue-grey cast and
 *       the high contrast, because the screen still competes with room light.</li>
 *   <li><b>NIGHT</b> is for an unlit room. Two things change, both for
 *       physiological reasons: colours move from blue towards amber, because
 *       blue light is what most disturbs sleep; and contrast <i>drops</i>,
 *       because pure white on pure black in the dark produces the halo that
 *       tires the eye over a long session. High contrast is a virtue by day and
 *       a defect at 3am.</li>
 * </ul>
 *
 * <p>That is why night uses a warm grey-brown background and cream text, rather
 * than white on black.</p>
 */
public enum Theme {

    LIGHT("com.formdev.flatlaf.FlatLightLaf", null, "light"),

    DARK("com.formdev.flatlaf.FlatDarkLaf", null, "dark"),

    /**
     * Inherits FlatDarkLaf and overrides the palette from the properties file
     * under {@code src/main/resources/themes}. FlatLaf derives dozens of
     * colours from half a dozen base ones, so changing the bases keeps
     * everything coherent — which overriding key by key would not.
     */
    NIGHT("com.formdev.flatlaf.FlatDarkLaf", "themes", "night");

    /**
     * Where the chosen theme is kept: the settings file, like everything else.
     *
     * <p>This paragraph used to explain why {@code userNodeForPackage} is not
     * called from each class -- a mechanism this class stopped using when the
     * settings moved to a file. The warning it carried is still true and now
     * lives where it applies: see the note on "Why files and not
     * java.util.prefs" in {@link Settings}.</p>
     */
    private static final Settings PREFS = Settings.settings();

    private static final String KEY = "theme";

    private final String lookAndFeelClass;

    private final String themePackage;

    private final String label;

    Theme(String lookAndFeelClass, String themePackage, String label) {
        this.lookAndFeelClass = lookAndFeelClass;
        this.themePackage = themePackage;
        this.label = label;
    }

    public String getLookAndFeelClass() {
        return lookAndFeelClass;
    }

    /** @return the resource package holding the palette, or null for the default */
    public String getThemePackage() {
        return themePackage;
    }

    public String getLabel() {
        return label;
    }

    /** @return the remembered theme, or {@link #LIGHT} on first run */
    public static Theme remembered() {
        return of(PREFS.get(KEY, LIGHT.label));
    }

    public void remember() {
        PREFS.put(KEY, label);
    }

    /**
     * @param name from the command line or from preferences
     * @return the matching theme, or {@link #LIGHT} when unrecognised
     */
    public static Theme of(String name) {
        if (name == null) {
            return LIGHT;
        }

        for (Theme theme : values()) {
            if (theme.label.equalsIgnoreCase(name) || theme.name().equalsIgnoreCase(name)) {
                return theme;
            }
        }

        return LIGHT;
    }
}
