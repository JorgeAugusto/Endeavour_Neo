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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ruler is one switch for the whole application.
 *
 * <p>The awkward one to get right is not the switch, it is the unsubscribing: a
 * chart window that is closed and leaves its listener behind keeps the window
 * alive and repaints it forever, and nothing about that is visible from the
 * outside.</p>
 */
@DisplayName("Ruler mode")
class RulerModeTest {

    /**
     * A settings and workspace pair of this file's own.
     *
     * <p>Opening a chart or a window WRITES: the workspace remembers which
     * charts were open, where they were and what they were showing. The home
     * those files live under is redirected by a property set in one place only,
     * the surefire plugin -- and the house runs the suite with javac and a
     * runner instead, where that property is absent and these tests rewrote the
     * reader's own list of open charts on every run.</p>
     *
     * <p>Per test, and put back afterwards, so nothing here can be read by the
     * next file either: MainWindowTest counts the charts it remembers, and a
     * count is a property any other test could change.</p>
     */
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path store;

    @org.junit.jupiter.api.BeforeEach
    void useAStoreOfOurOwn() {
        br.com.jorge.reis.endeavourneo.platform.Settings.useForTest(store);
    }

    private boolean before;

    /**
     * Puts the switch back and only then puts the store back.
     *
     * <p><b>One @AfterEach, because two do not have an order.</b> These were
     * separate: one restoring the ruler switch, one pointing the settings back
     * at the home directory. When the second ran first, the restore wrote into
     * the REAL settings -- and left the ruler on for the whole rest of the
     * suite. ChartViewTest then measured where it meant to pan, and failed in a
     * full run while passing on its own, which is the worst shape a failure
     * can have.</p>
     */
    @AfterEach
    void restore() {
        RulerMode.set(before);

        br.com.jorge.reis.endeavourneo.platform.Settings.stopUsingTestStore();
    }

    @Test
    @DisplayName("the switch is read back as it was set")
    void setAndRead() {
        before = RulerMode.isOn();

        RulerMode.set(true);
        assertTrue(RulerMode.isOn());

        RulerMode.toggle();
        assertFalse(RulerMode.isOn());
    }

    @Test
    @DisplayName("listeners hear a change, and only a change")
    void listenersHearChanges() {
        before = RulerMode.isOn();

        RulerMode.set(false);

        AtomicInteger heard = new AtomicInteger();
        Runnable listener = heard::incrementAndGet;

        RulerMode.listen(listener);

        try {
            RulerMode.set(true);
            assertEquals(1, heard.get());

            // Setting it to what it already is is not a change. Without this,
            // every chart repaints whenever the preferences dialog is applied.
            RulerMode.set(true);
            assertEquals(1, heard.get(), "an unchanged setting told everybody anyway");
        } finally {
            RulerMode.forget(listener);
        }
    }

    @Test
    @DisplayName("a chart follows the switch, and lets go when it is closed")
    void chartSubscribesAndUnsubscribes() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no display to attach a window to");

        before = RulerMode.isOn();

        RulerMode.set(false);

        int idle = RulerMode.listenerCount();

        JFrame frame = new JFrame();
        ChartCanvas canvas = new ChartCanvas();

        frame.getContentPane().add(canvas);
        frame.pack();

        assertEquals(idle + 1, RulerMode.listenerCount(),
                "a chart on screen is not following the ruler switch");

        RulerMode.set(true);

        assertEquals(ChartCanvas.Mode.MEASURE, canvas.getMode(),
                "the chart did not follow the application-wide switch");

        frame.dispose();

        assertEquals(idle, RulerMode.listenerCount(),
                "a closed chart left its listener behind, and with it the whole window");
    }

    @Test
    @DisplayName("a chart born while the ruler is on starts measuring")
    void bornInTheCurrentMode() {
        before = RulerMode.isOn();

        RulerMode.set(true);

        // A window opened later must not start in the other mode: that is the
        // per-window behaviour this replaced, seen from the other side.
        assertEquals(ChartCanvas.Mode.MEASURE, new ChartCanvas().getMode());
    }
}
