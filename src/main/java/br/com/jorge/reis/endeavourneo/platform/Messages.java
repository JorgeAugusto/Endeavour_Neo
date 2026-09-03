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

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Every string the user reads.
 *
 * <p>Backed by {@link ResourceBundle}, which is the standard mechanism in Java
 * and needs no dependency. {@code messages.properties} is the base bundle and is
 * written in English; {@code messages_pt_BR.properties} carries the Portuguese.
 * The JVM picks by {@link Locale#getDefault()}, so a machine set to Brazilian
 * Portuguese sees Portuguese with no configuration, and everyone else falls back
 * to the base.</p>
 *
 * <p><b>A missing key is loud on purpose.</b> When a key is absent this returns
 * {@code !key!} rather than an empty string. A blank label looks like a layout
 * bug and gets hunted for hours; {@code !file.exit!} says exactly what is wrong
 * and where. The same reasoning is why it does not throw: one forgotten string
 * in a translation should not stop the application from opening.</p>
 *
 * <p><b>Mnemonics live in the bundle too</b>, under {@code <key>.mnemonic}. The
 * underlined letter has to differ per language — "File" wants F, "Arquivo" wants
 * A — so hard-coding {@code KeyEvent.VK_F} in the window would quietly break
 * every translation.</p>
 */
public final class Messages {

    private static final String BASE = "messages";

    private static ResourceBundle bundle = ResourceBundle.getBundle(BASE);

    private Messages() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param key the key in the bundle
     * @return the translated text, or {@code !key!} when the key is missing
     */
    public static String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return "!" + key + "!";
        }
    }

    /**
     * @param key the key in the bundle
     * @param arguments values for the {0}, {1}... placeholders
     * @return the formatted text
     *
     * <p>Uses {@link MessageFormat} rather than concatenation because word order
     * changes between languages: "opened {0}" and "{0} aberto" cannot both be
     * built by gluing strings in a fixed order.</p>
     */
    public static String get(String key, Object... arguments) {
        return MessageFormat.format(get(key), arguments);
    }

    /**
     * @param key the key in the bundle
     * @param fallback what to show when the bundle has no such key
     * @return the text, or the fallback
     *
     * <p>For text built from DATA rather than from the interface. A missing
     * interface label showing as {@code !key!} is right — it is a defect and
     * has to be seen. A base the reader added yesterday having no translated
     * group name is not a defect, and shouting about it would make the tree
     * unreadable for a file that is perfectly fine.</p>
     */
    public static String orElse(String key, String fallback) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return fallback;
        }
    }

    /**
     * @param key the base key; the mnemonic is read from {@code key + ".mnemonic"}
     * @return the key code for the underlined letter, or 0 when unset
     */
    public static int mnemonic(String key) {
        String letter = get(key + ".mnemonic");

        if (letter.isEmpty() || letter.startsWith("!")) {
            return 0;
        }

        return Character.toUpperCase(letter.charAt(0));
    }

    /**
     * Switches language at runtime.
     *
     * <p>Only affects text built <i>after</i> the call. Swing has no notion of
     * re-reading a label, so the caller has to rebuild the windows — which is
     * why this is not offered in the preferences dialog yet.</p>
     *
     * @param locale the locale to use
     */
    public static void setLocale(Locale locale) {
        // NO FALLBACK TO THE DEFAULT LOCALE, and this is the whole fix for a
        // real defect: asking for English on a Brazilian machine gave
        // Portuguese. getBundle's normal order is
        //
        //     messages_en  ->  messages_<default locale>  ->  messages
        //
        // so with no messages_en on disk it landed on messages_pt_BR -- the
        // base bundle, which IS English, was never reached. It looked like the
        // setting did nothing.
        bundle = ResourceBundle.getBundle(BASE, locale,
                ResourceBundle.Control.getNoFallbackControl(
                        ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    /** @return the locale actually in use, which may be the fallback */
    public static Locale getLocale() {
        return bundle.getLocale();
    }
}
