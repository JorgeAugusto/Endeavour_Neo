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
package br.com.jorge.reis.endeavourneo.ui.chart.study;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.ui.chart.ChartCanvas;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartLayout;
import br.com.jorge.reis.endeavourneo.ui.chart.RandomWalkSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic.SlowStochastic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Carrying an indicator pane by its title bar into a different place.
 *
 * <p>The order of the panes has to be the reader's arrangement and not the
 * order they happened to be inserted in, and it has to survive being written
 * to a layout and read back. Both halves are here, because either one alone is
 * a feature that looks like it works until the next launch.</p>
 */
@DisplayName("Ordem dos paineis")
class PaneOrderTest {

    private ChartCanvas canvas;

    private StudyStack stack;

    private AtomicInteger written;

    @BeforeEach
    void setUp() {
        canvas = new ChartCanvas();
        canvas.setSeries(new RandomWalkSeries(300, 100.0));

        written = new AtomicInteger();

        stack = new StudyStack(canvas);
        stack.onArrangement(written::incrementAndGet);
        stack.setSize(600, 500);
    }

    /** @return the period of each pane's study, top to bottom */
    private List<Integer> order() {
        List<Integer> periods = new ArrayList<>();

        for (StudyPane pane : stack.panes()) {
            periods.add(pane.study().parameters().get(0));
        }

        return periods;
    }

    private void put(int period) {
        stack.show(new SlowStochastic(period, 3));
        stack.doLayout();
    }

    /** Lets the move, which is posted rather than done inline, actually happen. */
    private void settle() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });

        stack.doLayout();
    }

    /** Drags one pane so it is released at the very top of the stack. */
    private void carryToTop(int which) throws Exception {
        StudyPane pane = stack.panes().get(which);

        stack.beginDrag(pane);
        stack.dragTo(stack.panes().get(0).getY() + 1);
        stack.endDrag();
        settle();
    }

    @Test
    @DisplayName("os paineis comecam na ordem em que foram inseridos")
    void insertionOrderFirst() {
        put(8);
        put(14);
        put(21);

        assertEquals(List.of(8, 14, 21), order());
    }

    @Test
    @DisplayName("arrastar o ultimo para cima o poe em primeiro")
    void carryingTheLastToTheTop() throws Exception {
        put(8);
        put(14);
        put(21);

        carryToTop(2);

        assertEquals(List.of(21, 8, 14), order(),
                "the pane was dropped above the first one and did not land there");
    }

    @Test
    @DisplayName("a nova ordem e escrita na hora, nao ao fechar")
    void theOrderIsWrittenAtOnce() throws Exception {
        put(8);
        put(14);

        int before = written.get();

        carryToTop(1);

        assertTrue(written.get() > before,
                "nothing was told to write the layout, so the order dies with the window");
    }

    @Test
    @DisplayName("soltar no proprio lugar nao mexe nem escreve")
    void droppingWhereItAlreadyIs() throws Exception {
        put(8);
        put(14);

        int before = written.get();

        carryToTop(0);

        assertEquals(List.of(8, 14), order());
        assertEquals(before, written.get(),
                "a drag that changed nothing still wrote the layout");
    }

    @Test
    @DisplayName("um arrasto sem destino nao move nada")
    void aDragThatNeverMoved() throws Exception {
        put(8);
        put(14);

        // Grabbed and let go without the pointer ever travelling, which is what
        // a click on the title bar looks like from here.
        stack.beginDrag(stack.panes().get(1));
        stack.endDrag();
        settle();

        assertEquals(List.of(8, 14), order());
    }

    @Test
    @DisplayName("a ordem sobrevive a ida e volta pelo layout")
    void theOrderSurvivesTheLayout() throws Exception {
        put(8);
        put(14);
        put(21);

        carryToTop(2);

        List<ChartLayout.Pane> stored = stack.remembered();

        assertEquals(List.of(21, 8, 14),
                stored.stream().map(pane -> pane.parameters().get(0)).toList(),
                "the layout was written in the insertion order, not the arranged one");

        stack.restore(stored);
        stack.doLayout();

        assertEquals(List.of(21, 8, 14), order(),
                "the layout came back in a different order from the one it stored");
    }
}
