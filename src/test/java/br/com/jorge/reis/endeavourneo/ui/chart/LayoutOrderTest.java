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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dragging a tab to a gap between two others.
 *
 * <p>All of it is one off-by-one: the gap is counted while the dragged item is
 * still in the list. Get it wrong and a tab lands one place short of where it
 * was dropped, which looks like the drag simply not working.</p>
 *
 * <p>The same arithmetic serves the indicator panes, dragged by their title
 * bars into a different vertical order, so these cases cover both.</p>
 */
@DisplayName("Reordenar abas e paineis")
class LayoutOrderTest {

    private static List<String> four() {
        return new ArrayList<>(List.of("a", "b", "c", "d"));
    }

    @Test
    @DisplayName("arrastar para a direita passa por cima dos que ficaram")
    void draggingRight() {
        List<String> list = four();

        // "a" dropped into the gap before "d" -- gap three, counted while "a"
        // is still there -- lands after "c".
        assertTrue(Reordering.move(list, 0, 3));
        assertEquals(List.of("b", "c", "a", "d"), list);
    }

    @Test
    @DisplayName("arrastar para a esquerda cai no proprio buraco")
    void draggingLeft() {
        List<String> list = four();

        assertTrue(Reordering.move(list, 3, 1));
        assertEquals(List.of("a", "d", "b", "c"), list);
    }

    @Test
    @DisplayName("soltar depois do ultimo poe no fim")
    void droppingPastTheEnd() {
        List<String> list = four();

        assertTrue(Reordering.move(list, 1, 4));
        assertEquals(List.of("a", "c", "d", "b"), list);
    }

    @Test
    @DisplayName("soltar no proprio lugar nao mexe em nada")
    void droppingWhereItAlreadyIs() {
        List<String> list = four();

        assertFalse(Reordering.move(list, 1, 1), "it reported a move that did not happen");
        assertFalse(Reordering.move(list, 1, 2), "the gap after itself is also its own place");
        assertEquals(List.of("a", "b", "c", "d"), list);
    }

    @Test
    @DisplayName("um pedido impossivel nao quebra a lista")
    void nonsenseIsRefused() {
        List<String> list = four();

        assertFalse(Reordering.move(list, -1, 2));
        assertFalse(Reordering.move(list, 9, 2));
        assertFalse(Reordering.move(list, 0, -1));
        assertFalse(Reordering.move(list, 0, 99));
        assertEquals(List.of("a", "b", "c", "d"), list);
    }

    @Test
    @DisplayName("apagar uma aba a ESQUERDA nao troca a que esta sendo olhada")
    void removingToTheLeftKeepsTheSelection() {
        // The one the old arithmetic got wrong. Three layouts, the reader on the
        // second, and the first is deleted: Math.min(1, 1) left them on the
        // third, and applying it swapped the chart's indicators for a layout the
        // reader had not even been using.
        assertEquals(0, Reordering.selectionAfterRemoval(1, 0, 2),
                "deleting a tab to the left moved the reader to the next layout, and the "
                        + "chart swapped its indicators with nothing on screen to say why");

        // Two to the left of a selection further along.
        assertEquals(2, Reordering.selectionAfterRemoval(3, 1, 3));
    }

    @Test
    @DisplayName("apagar uma aba a DIREITA nao mexe na selecao")
    void removingToTheRightKeepsTheSelection() {
        assertEquals(1, Reordering.selectionAfterRemoval(1, 2, 2));
        assertEquals(0, Reordering.selectionAfterRemoval(0, 1, 1));
    }

    @Test
    @DisplayName("apagar a que esta sendo olhada cai na que ficou no lugar dela")
    void removingTheSelectedFallsIntoTheSlot() {
        // The two cases the old Math.min was right for, kept: what is in that
        // slot now, or the last one when there is nothing after it.
        assertEquals(1, Reordering.selectionAfterRemoval(1, 1, 3));
        assertEquals(1, Reordering.selectionAfterRemoval(2, 2, 2));

        // And nothing left is nothing selected.
        assertEquals(-1, Reordering.selectionAfterRemoval(0, 0, 0));
    }

    @Test
    @DisplayName("o primeiro vai para o fim e volta")
    void thereAndBack() {
        List<String> list = four();

        Reordering.move(list, 0, 4);

        assertEquals(List.of("b", "c", "d", "a"), list);

        Reordering.move(list, 3, 0);

        assertEquals(List.of("a", "b", "c", "d"), list);
    }
}
