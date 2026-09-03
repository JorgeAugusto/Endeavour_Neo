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
    @DisplayName("the time step is always a round interval, never an arbitrary one")
    void timeStepIsRound() {
        // A label at 14:00 and the next at 14:37 is arithmetic showing through.
        // Every value returned has to be one a person would choose.
        int[] round = {1, 2, 5, 10, 15, 30, 60, 120, 180, 240, 360, 720, 1_440, 2_880, 10_080};

        for (long span : new long[]{5, 37, 120, 400, 1_000, 5_000, 50_000, 900_000}) {
            int step = ChartCanvas.niceTimeStep(span, 8);
            boolean known = false;

            for (int candidate : round) {
                known |= candidate == step;
            }

            assertTrue(known, "span " + span + " produced the odd step " + step);
        }
    }

    @Test
    @DisplayName("a wider chart gets finer steps, never coarser")
    void widerMeansFiner() {
        // More room means more labels fit, so the interval may shrink. If it
        // grew instead, widening the window would remove information.
        long span = 600;

        assertTrue(ChartCanvas.niceTimeStep(span, 20) <= ChartCanvas.niceTimeStep(span, 4),
                "more labels asked for produced a coarser step");
    }

    @Test
    @DisplayName("an absurd span falls on the largest step instead of overflowing")
    void absurdSpanIsClamped() {
        assertEquals(10_080, ChartCanvas.niceTimeStep(Long.MAX_VALUE / 2, 8),
                "a span of centuries did not clamp to the largest step");
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
