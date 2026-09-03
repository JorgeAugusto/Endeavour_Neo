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

import java.util.Locale;

/**
 * Which language the application speaks.
 *
 * <p>Three choices, and the first is the important one: <b>follow the
 * machine</b>. A program that ignores the system language and picks for itself
 * is wrong on somebody's computer by construction; a program that asks is wrong
 * for anybody who never opens the settings.</p>
 *
 * <p><b>A change takes effect on the next launch</b>, and the page says so.
 * Every label in this application is read once when its window is built, so
 * switching live would mean rebuilding every window, every menu and every
 * tooltip — a great deal of machinery for something done once, if ever. Saying
 * "next time" is honest; silently changing half the labels would not be.</p>
 */
public enum Language {

    /** Whatever the operating system says, which is right far more often than not. */
    SYSTEM("system", null),

    PORTUGUESE("pt-BR", Locale.forLanguageTag("pt-BR")),

    ENGLISH("en", Locale.ENGLISH);

    private static final String KEY = "language";

    private final String code;

    private final Locale locale;

    Language(String code, Locale locale) {
        this.code = code;
        this.locale = locale;
    }

    public String code() {
        return code;
    }

    /** @return the locale to load, or the machine's when this is {@link #SYSTEM} */
    public Locale locale() {
        return locale == null ? Locale.getDefault() : locale;
    }

    public static Language remembered() {
        return of(Settings.settings().get(KEY, SYSTEM.code));
    }

    public void remember() {
        Settings.settings().put(KEY, code);
    }

    public static Language of(String code) {
        for (Language language : values()) {
            if (language.code.equalsIgnoreCase(code)) {
                return language;
            }
        }

        // An unknown code -- a hand-edited file, or one written by a later
        // version that knows a language this one does not. Following the
        // machine is the answer that is never wrong for the wrong reason.
        return SYSTEM;
    }

    /** Applies the remembered choice. Called once, before the first window exists. */
    public static void install() {
        Language chosen = remembered();

        if (chosen != SYSTEM) {
            Messages.setLocale(chosen.locale());
        }
    }
}
