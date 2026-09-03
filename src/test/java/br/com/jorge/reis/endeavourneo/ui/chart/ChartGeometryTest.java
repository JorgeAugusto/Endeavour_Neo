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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.Rectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a chart window remembers about its own size.
 *
 * <p>These two rules are here because getting them wrong is invisible until the
 * next launch: nothing throws, nothing logs, the window simply opens the wrong
 * size and the reader assumes it was always like that.</p>
 */
@DisplayName("Chart window geometry")
class ChartGeometryTest {

    private static final Rectangle DESKTOP = new Rectangle(0, 0, 1920, 1000);

    private static final Rectangle NORMAL = new Rectangle(0, 0, 900, 560);

    @Test
    @DisplayName("a maximised chart remembers the size behind it, not the desktop")
    void maximisedStoresTheNormalBounds() {
        // The bug this exists for: storing the desktop's bounds turns "maximised"
        // into "1920 by 1000", and the chart reopens merely large -- no longer
        // maximised, no longer following the window when it is resized.
        Dimension stored = ChartHolder.sizeToRemember(true, NORMAL, DESKTOP);

        assertEquals(new Dimension(900, 560), stored,
                "the desktop's size was stored as though the reader had chosen it");
    }

    @Test
    @DisplayName("a chart that is not maximised remembers what it occupies")
    void plainStoresItsOwnBounds() {
        Dimension stored = ChartHolder.sizeToRemember(false, NORMAL, new Rectangle(0, 0, 640, 400));

        assertEquals(new Dimension(640, 400), stored);
    }

    @Test
    @DisplayName("maximised before ever having a size of its own stores nothing")
    void emptyNormalBoundsStoreNothing() {
        // A zero written here reopens a chart no pixels across, and the restore
        // button cannot undo it. Nothing written is better: the birth size takes
        // over.
        assertNull(ChartHolder.sizeToRemember(true, new Rectangle(0, 0, 0, 0), DESKTOP));
    }

    @Test
    @DisplayName("what was maximised comes back maximised, however many charts are open")
    void maximisedWins() {
        assertTrue(ChartHolder.opensMaximised(false, true, true),
                "a chart maximised last night must not reopen in a corner");
    }

    @Test
    @DisplayName("the first chart in an empty desktop is born filling it")
    void firstIsBornFull() {
        assertTrue(ChartHolder.opensMaximised(true, false, false));
    }

    @Test
    @DisplayName("a chart with a remembered size is not maximised just for being alone")
    void rememberedSizeSurvivesBeingAlone() {
        // Being the only chart open is not a request. A chart deliberately made
        // small and then reopened first must stay small.
        assertFalse(ChartHolder.opensMaximised(true, true, false));
    }

    @Test
    @DisplayName("an arriving chart is not maximised")
    void arrivingChartTakesAQuarter() {
        assertFalse(ChartHolder.opensMaximised(false, false, false),
                "a chart arriving beside others would bury what was being watched");
    }
}
