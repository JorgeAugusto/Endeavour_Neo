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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scale drag, tested as arithmetic rather than as a gesture.
 *
 * <p>Driving real mouse events through a component is slow and flaky. Pulling
 * the calculation out of the listener makes the part that can actually be wrong
 * checkable in microseconds, and leaves the listener with nothing but plumbing.</p>
 */
@DisplayName("Chart canvas")
class ChartCanvasTest {

    @Test
    @DisplayName("dragging UP stretches and dragging DOWN flattens")
    void dragDirection() {
        // The gesture has to match the metaphor: pulling the axis taller makes
        // the chart taller. Inverted, it is the kind of thing every user notices
        // in the first three seconds and nobody can quite name.
        double up = ChartCanvas.stretchForDrag(1.0, -100);
        double down = ChartCanvas.stretchForDrag(1.0, 100);

        assertTrue(up > 1.0, "dragging up should stretch, got " + up);
        assertTrue(down < 1.0, "dragging down should flatten, got " + down);
    }

    @Test
    @DisplayName("the same drag has the same effect whether the scale is small or large")
    void dragIsMultiplicative() {
        // A fixed step per pixel would crawl at one end of the range and jump at
        // the other. Multiplying keeps the feel constant.
        double fromSmall = ChartCanvas.stretchForDrag(0.5, -50) / 0.5;
        double fromLarge = ChartCanvas.stretchForDrag(4.0, -50) / 4.0;

        assertEquals(fromSmall, fromLarge, 1e-9,
                "the same drag produced different ratios at different scales");
    }

    @Test
    @DisplayName("no drag is long enough to flatten the chart into a line or push it off screen")
    void staysWithinLimits() {
        assertTrue(ChartCanvas.stretchForDrag(1.0, 100_000) >= 0.1,
                "an enormous downward drag went below the floor");
        assertTrue(ChartCanvas.stretchForDrag(1.0, -100_000) <= 20.0,
                "an enormous upward drag went above the ceiling");
    }

    @Test
    @DisplayName("a drag of zero pixels changes nothing")
    void zeroDragIsIdentity() {
        // Guards the click that is not a drag: pressing on the axis and
        // releasing without moving must not nudge the scale.
        assertEquals(2.5, ChartCanvas.stretchForDrag(2.5, 0), 1e-12,
                "a click with no movement changed the scale");
    }
}
