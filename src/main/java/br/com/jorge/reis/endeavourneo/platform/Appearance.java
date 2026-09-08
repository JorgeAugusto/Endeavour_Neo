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

import java.awt.Dimension;
import java.awt.Font;
import java.lang.reflect.Method;
import java.util.Locale;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

/**
 * The application's look and feel, and why it is optional.
 *
 * <p>The modern IDE aesthetic wanted here is best represented today by
 * IntelliJ — which is written in Swing. <b>FlatLaf</b> is the look and feel
 * extracted from that family: flat, gradient-free, with light and dark themes.
 * Swapping the look and feel restyles every component at once, without touching
 * a single panel.</p>
 *
 * <p><b>Why by reflection.</b> Without the FlatLaf jar the application still
 * starts, using the system look and feel, instead of dying with {@code
 * NoClassDefFoundError}. FlatLaf becomes an addition that improves things
 * rather than a requirement that stops someone who cloned the project offline
 * from opening the window. It costs ten lines.</p>
 *
 * <p><b>Order matters and is not negotiable:</b> the custom palette must be
 * registered <i>before</i> {@code setLookAndFeel}, and the look and feel must be
 * installed <i>before</i> any component exists. A component already created does
 * not change colour on its own.</p>
 */
public final class Appearance {

    private static final String FLATLAF = "com.formdev.flatlaf.FlatLaf";

    private Appearance() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * Installs the best look and feel available for the requested theme.
     *
     * @param theme which palette to use, not null
     * @return what was actually installed, so the console and status bar can say
     */
    public static String install(Theme theme) {
        // Before anything: drop whatever palette a previous theme registered.
        // registerCustomDefaultsSource is a permanent, global registration --
        // nothing about installing a different look and feel undoes it. Without
        // this, selecting night once turned plain dark into night for the rest
        // of the session, which is measurable and was measured: dark came back
        // with night's own background, text and amber accent.
        forgetPalettes();

        if (theme.getThemePackage() != null
                && !registerPalette(theme.getThemePackage(), theme.getLookAndFeelClass())) {
            // Without the palette the theme would come out identical to plain
            // dark. Install plain dark, which is what will actually be seen,
            // and SAY that the palette was missing.
            apply(theme.getLookAndFeelClass());
            tightenSpacing();

            return Messages.get("appearance.flatDarkFallback", theme.getLabel());
        }

        if (apply(theme.getLookAndFeelClass())) {
            tightenSpacing();

            return Messages.get("appearance.flat", theme.getLabel());
        }

        return installWithoutFlatLaf();
    }

    private static String installWithoutFlatLaf() {
        // The system look and feel still beats Metal, which is Swing's default
        // and the reason for its reputation.
        if (apply(UIManager.getSystemLookAndFeelClassName())) {
            return Messages.get("appearance.system");
        }

        return Messages.get("appearance.swing");
    }

    /**
     * Unregisters every palette any theme may have registered.
     *
     * <p>All of them, not just the one being left: the theme being installed
     * knows what it wants, and it does not know what was there before. Asking
     * FlatLaf to forget a package it never had is harmless.</p>
     */
    private static void forgetPalettes() {
        try {
            Class<?> flatLaf = Class.forName(FLATLAF);
            Method forget = flatLaf.getMethod("unregisterCustomDefaultsSource", String.class);

            for (Theme other : Theme.values()) {
                if (other.getThemePackage() != null) {
                    forget.invoke(null, other.getThemePackage());
                }
            }
        } catch (ReflectiveOperationException e) {
            // No FlatLaf, or a version without the method: then no palette was
            // registered either, and there is nothing to undo.
        }
    }

    /**
     * Tells FlatLaf to also read the {@code .properties} files in this package.
     *
     * <p>This is FlatLaf's own customisation mechanism: instead of overriding
     * key by key in the {@code UIManager}, you change half a dozen base colours
     * and FlatLaf derives the remaining dozens while preserving the contrast
     * relationships. Overriding key by key produces the classic incoherence — a
     * panel that turned brown next to a menu that stayed blue.</p>
     *
     * @return whether it worked; false when FlatLaf or the file is absent
     */
    private static boolean registerPalette(String resourcePackage, String lookAndFeelClass) {
        // FlatLaf looks for a .properties named after the LOOK AND FEEL CLASS.
        // If the file is not there it does not complain: it just uses the
        // defaults. The result would be a "night" theme identical to plain
        // dark, announced as night -- a silent failure, which is the worst way
        // to fail. So check the file exists first.
        String simpleName = lookAndFeelClass.substring(lookAndFeelClass.lastIndexOf('.') + 1);

        if (Appearance.class.getResource(
                "/" + resourcePackage + "/" + simpleName + ".properties") == null) {
            return false;
        }

        try {
            Class<?> flatLaf = Class.forName(FLATLAF);
            Method register = flatLaf.getMethod("registerCustomDefaultsSource", String.class);

            register.invoke(null, resourcePackage);

            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static boolean apply(String className) {
        try {
            UIManager.setLookAndFeel(className);

            return true;
        } catch (ReflectiveOperationException | UnsupportedOperationException
                 | UnsupportedLookAndFeelException e) {
            return false;
        }
    }

    /**
     * The tweaks that separate "looks like an IDE" from "looks like a form".
     *
     * <p>There are few and they are all about space. Dense layout is what tells
     * a professional tool apart from a Swing exercise.</p>
     */
    private static void tightenSpacing() {
        UIManager.put("TabbedPane.showTabSeparators", Boolean.TRUE);
        UIManager.put("TabbedPane.tabHeight", 28);
        UIManager.put("SplitPane.dividerSize", 4);
        UIManager.put("SplitPaneDivider.gripDotCount", 0);
        UIManager.put("Tree.paintLines", Boolean.FALSE);
        UIManager.put("Table.showHorizontalLines", Boolean.TRUE);
        UIManager.put("Table.intercellSpacing", new Dimension(0, 1));
    }

    /**
     * @param size point size
     * @return the system's fixed-width font, for the console and for numbers
     *
     * <p>Numbers in a column only line up in a fixed-width font. And the console
     * imitates a terminal, where alignment is the only formatting there is.</p>
     */
    public static Font monospaced(int size) {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "Consolas"
                : Font.MONOSPACED;

        return new Font(name, Font.PLAIN, size);
    }
}
