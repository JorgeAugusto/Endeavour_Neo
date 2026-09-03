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

    private boolean before;

    @AfterEach
    void restore() {
        // It is stored in the user's real preferences: a test that leaves it on
        // changes how the application opens tomorrow.
        RulerMode.set(before);
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
