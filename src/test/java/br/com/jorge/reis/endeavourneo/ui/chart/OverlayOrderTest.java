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

import br.com.jorge.reis.endeavourneo.domain.market.RandomWalkSeries;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Carrying a row of the legend into a different place.
 *
 * <p>The list is the reader's arrangement, not the order the indicators were
 * inserted in, and it is also the order they are DRAWN in — so a row moved to
 * the bottom is also a line moved to the top of the pile where two cross.</p>
 *
 * <p>Both halves are here: the move itself, and the move surviving the layout.
 * Either one alone is a feature that looks like it works until the next
 * launch.</p>
 */
@DisplayName("Ordem dos indicadores no preco")
class OverlayOrderTest {

    private ChartCanvas canvas;

    private AtomicInteger written;

    @BeforeEach
    void setUp() {
        canvas = new ChartCanvas();
        canvas.setSeries(new RandomWalkSeries(200, 100.0));

        written = new AtomicInteger();

        canvas.addOverlay(new MovingAverage(9));
        canvas.addOverlay(new MovingAverage(21));
        canvas.addOverlay(new MovingAverage(50));

        // After the three, so only the moves are counted.
        canvas.onOverlaysChanged(written::incrementAndGet);
    }

    /** @return the period of each overlay, top to bottom */
    private static List<Integer> order(List<Overlay> overlays) {
        List<Integer> periods = new ArrayList<>();

        for (Overlay each : overlays) {
            periods.add(each.parameters().get(0));
        }

        return periods;
    }

    @Test
    @DisplayName("arrastar o ultimo para o topo o poe em primeiro")
    void carryingTheLastToTheTop() {
        assertTrue(canvas.moveOverlay(canvas.overlays().get(2), 0));

        assertEquals(List.of(50, 9, 21), order(canvas.overlays()));
    }

    @Test
    @DisplayName("arrastar o primeiro para o fim o poe por ultimo")
    void carryingTheFirstToTheEnd() {
        assertTrue(canvas.moveOverlay(canvas.overlays().get(0), 3));

        assertEquals(List.of(21, 50, 9), order(canvas.overlays()));
    }

    @Test
    @DisplayName("a nova ordem e escrita na hora")
    void theOrderIsWrittenAtOnce() {
        canvas.moveOverlay(canvas.overlays().get(2), 0);

        assertEquals(1, written.get(),
                "nothing was told to write the layout, so the order dies with the window");
    }

    @Test
    @DisplayName("soltar no proprio lugar nao mexe nem escreve")
    void droppingWhereItAlreadyIs() {
        assertFalse(canvas.moveOverlay(canvas.overlays().get(1), 1));
        assertFalse(canvas.moveOverlay(canvas.overlays().get(1), 2),
                "the gap after itself is also its own place");

        assertEquals(List.of(9, 21, 50), order(canvas.overlays()));
        assertEquals(0, written.get(), "a move that changed nothing still wrote the layout");
    }

    @Test
    @DisplayName("carregar algo que nao esta na lista nao faz nada")
    void carryingAStranger() {
        assertFalse(canvas.moveOverlay(new MovingAverage(200), 0));

        assertEquals(List.of(9, 21, 50), order(canvas.overlays()));
    }

    @Test
    @DisplayName("a ordem sobrevive a ida e volta pelo layout")
    void theOrderSurvivesTheLayout() {
        canvas.moveOverlay(canvas.overlays().get(2), 0);

        ChartLayout stored = ChartLayout.of("um", canvas.overlays());

        assertEquals(List.of(50, 9, 21), order(stored.build()),
                "the layout came back in a different order from the one it stored");
    }
}
