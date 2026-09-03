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

import br.com.jorge.reis.endeavourneo.platform.Settings;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Whether dragging measures or moves — for the whole application at once.
 *
 * <p><b>One setting for every chart, not one per window.</b> A modal tool whose
 * mode depends on which window has focus is the version of this that goes wrong:
 * the reader drags on the chart beside the one they last used and gets the other
 * behaviour, with nothing on screen having changed. Making it application-wide
 * means the answer to "what will a drag do" is the same everywhere, always.</p>
 *
 * <p>It is remembered between runs because it is a way of working rather than a
 * momentary act — somebody measuring levels is going to measure several.</p>
 *
 * <p>Control still toggles it, and now toggles it everywhere. The checkbox in
 * the preferences is the same switch seen from the other side; both write here,
 * and everything showing the state listens here.</p>
 */
public final class RulerMode {

    private static final Settings PREFS = Settings.settings();

    private static final String KEY = "chart.ruler";

    /**
     * Copy-on-write because listeners are added and removed as windows come and
     * go, while the list is walked from the same thread that is doing it.
     */
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private static boolean on = PREFS.getBoolean(KEY, false);

    private RulerMode() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    public static boolean isOn() {
        return on;
    }

    /** @param value true to measure on drag, false to move the chart */
    public static void set(boolean value) {
        if (on == value) {
            return;
        }

        on = value;

        PREFS.putBoolean(KEY, value);

        for (Runnable listener : LISTENERS) {
            listener.run();
        }
    }

    public static void toggle() {
        set(!on);
    }

    /**
     * @param listener told whenever the mode changes
     *
     * <p>Must be paired with {@link #forget}: a chart window that is closed and
     * leaves its listener here keeps the whole window alive, and every later
     * change repaints a component nobody can see.</p>
     */
    public static void listen(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void forget(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /** @return how many are listening — for the test that this does not leak */
    static int listenerCount() {
        return LISTENERS.size();
    }
}
