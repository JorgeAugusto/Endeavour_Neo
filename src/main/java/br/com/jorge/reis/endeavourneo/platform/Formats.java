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
 * The shapes this program writes dates and lists of fields in.
 *
 * <h2>Why they are here and not in the bundle</h2>
 *
 * <p>These are not sentences. The separator is a typographic mark and the date
 * patterns are the Brazilian market's own, which is the market this program
 * reads — a reader who switches the interface to English still wants the dates
 * their broker prints. What the bundle holds is what a translator would change;
 * what this holds is what the market decides.</p>
 *
 * <h2>Why they are in ONE place</h2>
 *
 * <p>The separator was written out eleven times across four packages, and for a
 * while in two spacings — one screen showed a middle dot with one space on each
 * side and the screen beside it showed two. The date pattern was written seven
 * times. Neither is a defect while every copy agrees; both are a decision taken
 * again at every call site, and the spacing shows what that costs.</p>
 */
public final class Formats {

    /**
     * Between two fields of the same line: a market and a scale, a name and a
     * role, a series and one of its segments.
     *
     * <p>Two spaces on each side and not one. The mark is narrow and the fields
     * beside it are words; with one space it reads as punctuation inside a
     * phrase rather than as the join between two of them.</p>
     */
    public static final String FIELDS = "  ·  ";

    /** A date, as this market writes one. */
    public static final String DATE = "dd/MM/yyyy";

    /** A date and the time of day, to the minute. */
    public static final String DATE_TIME = DATE + " HH:mm";

    /** A day of the month with no year, for an axis that already says which. */
    public static final String DAY = "dd/MM";

    private Formats() {
        throw new AssertionError("Utility class must not be instantiated");
    }
}
