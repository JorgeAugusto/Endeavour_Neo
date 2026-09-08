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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import javax.swing.JDesktopPane;
import javax.swing.JFrame;
import javax.swing.JInternalFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Shortcuts, when more than one chart is open.
 *
 * <h2>Why the count of charts is the whole subject</h2>
 *
 * <p>Both defects here are invisible with one chart on screen and certain with
 * two, and the shell arranges charts in a grid on purpose. A digit went to
 * whichever canvas had registered its binding last -- not the one in front --
 * and Control on its own toggled one global switch once per canvas, so with an
 * even number of charts the shortcut did nothing at all.</p>
 */
@DisplayName("Atalhos com mais de um grafico")
class ChartShortcutsTest {

    @Test
    @DisplayName("o digito age no grafico da FRENTE, nao no que registrou por ultimo")
    void thedigitActsOnTheFrontChart() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        // "The chart is the window" was the justification for binding the digit
        // to the whole window, and it was never true here: the charts are
        // internal frames inside one desktop. Swing walks the registered
        // bindings from the last to the first and stops at the one that
        // consumes, so typing 5 changed the period of a chart the reader was not
        // looking at and left the one they were alone.
        //
        // Asserted through frontChart, which is what the action now asks, and
        // not through the action itself: the action opens a modal dialog.
        SwingUtilities.invokeAndWait(() -> {
            JDesktopPane desktop = new JDesktopPane();

            ChartCanvas first = inFrame(desktop, "one");
            ChartCanvas second = inFrame(desktop, "two");

            try {
                select(desktop, frameOf(first));

                assertSame(first, first.frontChart(),
                        "the chart in front did not answer for itself");
                assertSame(first, second.frontChart(),
                        "the binding that fired belonged to the chart BEHIND, and it "
                                + "changed its own period instead of the one in front");

                select(desktop, frameOf(second));

                assertSame(second, first.frontChart(),
                        "bringing the other chart to the front did not move the shortcut "
                                + "with it");
            } finally {
                desktop.removeAll();
            }
        });
    }

    @Test
    @DisplayName("soltar o Control com DOIS graficos abertos ainda alterna o modo")
    void thecontrolGestureFiresOnceWhateverTheChartCount() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        // Every canvas used to install a dispatcher of its own, and none of them
        // consumed the event -- so one release of Control passed through all of
        // them and each toggled the SAME global boolean. TWO charts docked in
        // the window, which is the arrangement the shell offers in a grid, and
        // the shortcut did nothing at all; with three it worked. The behaviour
        // depended on the parity of the number of open charts, and nothing on
        // screen said so.
        //
        // An EVEN number on purpose: that is the count at which the old code
        // looks like a shortcut that simply does not exist.
        JFrame window = new JFrame("test");
        boolean before = RulerMode.isOn();

        try {
            SwingUtilities.invokeAndWait(() -> {
                JDesktopPane desktop = new JDesktopPane();

                inFrame(desktop, "one");
                inFrame(desktop, "two");

                window.setContentPane(desktop);
                window.setSize(400, 300);
                window.setVisible(true);
            });

            assertEquals(2, ChartCanvas.watchingCanvases(),
                    "the two charts are not both on screen, so nothing here is tested");

            org.junit.jupiter.api.Assumptions.assumeTrue(
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                            .getActiveWindow() != null,
                    "no window took focus, so the gesture's own guard cannot be met");

            press(window, java.awt.event.KeyEvent.KEY_PRESSED);
            press(window, java.awt.event.KeyEvent.KEY_RELEASED);

            assertEquals(!before, RulerMode.isOn(),
                    "releasing Control with two charts open left the mode where it was: "
                            + "the gesture fired once per chart on one global switch, so "
                            + "an even number of charts cancels it out");
        } finally {
            RulerMode.set(before);

            SwingUtilities.invokeAndWait(window::dispose);
        }

        assertEquals(0, ChartCanvas.watchingCanvases(),
                "a chart that closed is still counted as on screen");
        assertEquals(0, ChartCanvas.controlGestures(),
                "the gesture stayed installed after the last chart closed, reacting to "
                        + "keys for windows that are gone");
    }

    /** Sends one Control key event through the same path a real one takes. */
    private static void press(JFrame window, int id) throws Exception {
        // Through the KEY EVENT DISPATCHER CHAIN, which is the path a real key
        // takes and the one the gesture is registered on. dispatchKeyEvent is the
        // default dispatcher and skips the chain entirely.
        SwingUtilities.invokeAndWait(() -> java.awt.KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .dispatchEvent(new java.awt.event.KeyEvent(window, id,
                        System.currentTimeMillis(), 0,
                        java.awt.event.KeyEvent.VK_CONTROL,
                        java.awt.event.KeyEvent.CHAR_UNDEFINED)));
    }

    /** Brings that frame to the front, the way a click does. */
    private static void select(JDesktopPane desktop, JInternalFrame frame) {
        try {
            desktop.setSelectedFrame(frame);
            frame.setSelected(true);
        } catch (java.beans.PropertyVetoException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return a canvas inside its own internal frame, added to that desktop */
    private static ChartCanvas inFrame(JDesktopPane desktop, String title) {
        ChartCanvas canvas = new ChartCanvas();
        JInternalFrame frame = new JInternalFrame(title);

        frame.setContentPane(new javax.swing.JPanel(new java.awt.BorderLayout()));
        frame.getContentPane().add(canvas, java.awt.BorderLayout.CENTER);
        frame.setSize(300, 200);
        frame.setVisible(true);

        desktop.add(frame);

        return canvas;
    }

    private static JInternalFrame frameOf(ChartCanvas canvas) {
        return (JInternalFrame) SwingUtilities.getAncestorOfClass(
                JInternalFrame.class, canvas);
    }
}
