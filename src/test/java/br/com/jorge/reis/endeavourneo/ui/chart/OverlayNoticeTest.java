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

import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;

import static org.junit.jupiter.api.Assertions.assertEquals;


import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who gets told when the indicators change, and who does not.
 *
 * <p>Two questions that look like one and are not: <b>redraw this</b> goes to
 * everything showing the overlays, and <b>write this down</b> goes to the layout
 * that owns them. Answering them with a single notice was a bug in both
 * directions — either applying a layout captured itself straight back, or the
 * legend went on listing indicators the chart had already dropped.</p>
 */
@DisplayName("Overlay notices")
class OverlayNoticeTest {

    private ChartCanvas canvas;

    private AtomicInteger stored;

    private AtomicInteger redrawn;

    @BeforeEach
    void setUp() {
        canvas = new ChartCanvas();
        stored = new AtomicInteger();
        redrawn = new AtomicInteger();

        canvas.setSeries(new RandomWalkSeries(200, 100.0));
        canvas.onOverlaysChanged(stored::incrementAndGet);
        canvas.onOverlaysRedrawn(redrawn::incrementAndGet);
    }

    @Test
    @DisplayName("applying a layout redraws, and is not written back")
    void applyingALayoutIsSilentButVisible() {
        canvas.setOverlays(List.of(new MovingAverage(9)));

        assertEquals(1, redrawn.get(),
                "the legend was never told the indicators had been replaced");
        assertEquals(0, stored.get(),
                "applying a layout captured itself straight back into storage");
    }

    @Test
    @DisplayName("emptying the chart also redraws")
    void emptyingRedraws() {
        // The case actually seen: switching to a layout with fewer indicators
        // left the departed ones listed until the mouse happened to pass over.
        canvas.setOverlays(List.of(new MovingAverage(9)));
        canvas.setOverlays(List.of());

        assertEquals(2, redrawn.get(), "an empty layout has to redraw too");
    }

    @Test
    @DisplayName("adding an indicator both redraws and is written down")
    void addingDoesBoth() {
        canvas.addOverlay(new MovingAverage(20));

        assertEquals(1, redrawn.get());
        assertEquals(1, stored.get(),
                "an indicator added by hand belongs to the layout from now on");
    }

    @Test
    @DisplayName("removing an indicator both redraws and is written down")
    void removingDoesBoth() {
        Overlay overlay = new MovingAverage(20);

        canvas.addOverlay(overlay);
        canvas.removeOverlay(overlay);

        assertEquals(2, redrawn.get());
        assertEquals(2, stored.get());
    }

    @Test
    @DisplayName("removing something that was never there tells nobody")
    void removingAStrangerIsSilent() {
        canvas.removeOverlay(new MovingAverage(20));

        assertEquals(0, redrawn.get(), "a repaint was asked for over nothing");
        assertEquals(0, stored.get(), "the layout was rewritten over nothing");
    }
}
