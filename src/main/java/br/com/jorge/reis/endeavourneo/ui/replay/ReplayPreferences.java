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
package br.com.jorge.reis.endeavourneo.ui.replay;

import java.util.prefs.Preferences;

/**
 * What the replay remembers between runs.
 */
public final class ReplayPreferences {

    private static final Preferences PREFS =
            Preferences.userRoot().node("br/com/jorge/reis/endeavourneo/replay");

    private static final String HISTORY = "historyDays";

    /**
     * Sessions loaded before the one being played.
     *
     * <p>Enough to fill a chart at any scale worth watching: a hundred sessions
     * of minutes is fifty-odd thousand bars, which is eleven thousand at five
     * minutes and still a full screen at an hour.</p>
     */
    public static final int DEFAULT_HISTORY = 100;

    /** More than this and the chart is folding a quarter of a million bars per frame. */
    public static final int MAX_HISTORY = 250;

    private ReplayPreferences() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @return how many sessions to load before the one being replayed
     *
     * <p>The history is stored at the same scale as the session — minutes — and
     * the chart folds it to whatever period it is showing. It is <b>not</b>
     * daily candles: switching the chart to five minutes has to give five-minute
     * history, and a daily bar could not be taken apart again.</p>
     */
    public static int historyDays() {
        return clamp(PREFS.getInt(HISTORY, DEFAULT_HISTORY));
    }

    public static void setHistoryDays(int days) {
        PREFS.putInt(HISTORY, clamp(days));
    }

    private static int clamp(int days) {
        return Math.max(0, Math.min(days, MAX_HISTORY));
    }
}
