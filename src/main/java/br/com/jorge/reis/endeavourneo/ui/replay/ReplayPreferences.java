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

import br.com.jorge.reis.endeavourneo.platform.Settings;


/**
 * What the replay remembers between runs.
 */
public final class ReplayPreferences {

    private static final Settings PREFS = Settings.settings();

    private static final String HISTORY = "historyDays";

    private static final String WINDOW = "windowDays";

    /**
     * Sessions loaded before the one being played.
     *
     * <p>Thirty. A hundred was the first guess and it is more than the eye ever
     * uses: thirty sessions of minutes is around seventeen thousand bars, which
     * is still three thousand at five minutes and a month and a half of screen
     * at an hour — past anything a replay is looking back at. The rest was
     * folded again on every frame for nobody.</p>
     */
    public static final int DEFAULT_HISTORY = 30;

    /** More than this and the chart is folding a quarter of a million bars per frame. */
    public static final int MAX_HISTORY = 250;

    /**
     * How many days a single replay may span.
     *
     * <p>Ten. A replay is watched, and watching is the slow way to look at a
     * market: ten sessions at sixty times real time is an hour and a half of
     * sitting there. Wanting more than that is usually wanting a backtest, which
     * is a different tool and reads years in seconds.</p>
     */
    public static final int DEFAULT_WINDOW = 10;

    /** The ceiling on the ceiling, so a typo in preferences cannot ask for a decade. */
    public static final int MAX_WINDOW = 250;

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
        return clamp(PREFS.getInt(HISTORY, DEFAULT_HISTORY), MAX_HISTORY);
    }

    public static void setHistoryDays(int days) {
        PREFS.putInt(HISTORY, clamp(days, MAX_HISTORY));
    }

    /** @return the longest stretch a single replay may cover, in days */
    public static int windowDays() {
        return Math.max(1, clamp(PREFS.getInt(WINDOW, DEFAULT_WINDOW), MAX_WINDOW));
    }

    public static void setWindowDays(int days) {
        PREFS.putInt(WINDOW, Math.max(1, clamp(days, MAX_WINDOW)));
    }

    private static int clamp(int days, int ceiling) {
        return Math.max(0, Math.min(days, ceiling));
    }
}
