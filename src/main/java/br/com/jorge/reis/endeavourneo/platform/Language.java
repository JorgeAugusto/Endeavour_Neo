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
 * <p><b>A change takes effect at once.</b> Every label is read once, when its
 * window is built, so the main window is rebuilt — which the application
 * already knows how to do, because it restores itself on every launch. The
 * first version made the reader restart, and that was a worse answer dressed up
 * as an honest one: the machinery to avoid it was already there.</p>
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

    /**
     * Applies the remembered choice.
     *
     * <p>Called before the first window is built, and again whenever the choice
     * changes and the window is rebuilt.</p>
     */
    public static void install() {
        // Always, including SYSTEM -- whose locale() is the machine's. Doing it
        // only for the other two meant switching BACK to "follow the system"
        // left the previous choice in place until the next launch.
        Locale chosen = remembered().locale();

        Messages.setLocale(chosen);

        // The bundle is only half of it. Ten places read Locale.getDefault()
        // directly -- the calendar month, the MMM/yy of the time axis, the
        // decimal separator in seven readouts -- and Swing picks the words on
        // the JOptionPane buttons from the JVM's locale, not from the bundle.
        // Without this line, choosing English on a Brazilian machine asked the
        // question in English over buttons that said Sim and Nao.
        Locale.setDefault(chosen);
    }
}
