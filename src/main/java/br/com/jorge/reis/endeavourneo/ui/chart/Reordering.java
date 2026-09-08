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

import java.util.List;

/**
 * Dropping one item of a list into a gap between two others.
 *
 * <h2>Why this is not written twice</h2>
 *
 * <p>Two things in this chart are dragged into a new order — the layout tabs
 * along the top, sideways, and the indicator panes underneath, up and down —
 * and both are the same question with a different axis. The arithmetic is one
 * off-by-one deep, so having it in two places would mean two chances to get
 * that one wrong and only one of them being noticed.</p>
 */
public final class Reordering {

    private Reordering() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * Moves one item to a gap, in place.
     *
     * @param list the items, in the order they are shown
     * @param from which item, by its index right now
     * @param gap where it should land, counted in the gaps BETWEEN items:
     *        zero is before the first, {@code list.size()} is after the last
     * @return whether anything actually moved
     *
     * <p>Gaps and not indices, because dropping is answering "before which
     * one", and that answer has to include "after the last" — which no item
     * index can say.</p>
     *
     * <p>The subtraction is the whole of it: the gap was counted while the
     * dragged item was still occupying its place, so every gap beyond it is
     * one too high once it is taken out.</p>
     *
     * <p>A request that makes no sense is refused rather than clamped. A drag
     * that ended somewhere impossible should leave the list alone, and saying
     * "no" lets the caller skip writing an order that did not change.</p>
     */
    public static <T> boolean move(List<T> list, int from, int gap) {
        if (from < 0 || from >= list.size() || gap < 0 || gap > list.size()) {
            return false;
        }

        int to = gap > from ? gap - 1 : gap;

        if (to == from) {
            return false;
        }

        // THE SIZE IS READ BEFORE THE REMOVE, and only because Java evaluates
        // arguments left to right. `to` may legitimately be n-1 -- dropping
        // after the last one -- and with the remove happening first,
        // list.size() - 1 would be n-2 and the item would land one place short
        // of the end.
        //
        // Written out rather than left to the evaluation order: a rule that
        // holds by a language guarantee nobody mentions is a rule the next
        // person breaks while tidying.
        int last = list.size() - 1;
        int landing = Math.max(0, Math.min(to, last));

        list.add(landing, list.remove(from));

        return true;
    }

    /**
     * @param selected which item is being looked at, before the removal
     * @param removed which item is going, by its index right now
     * @param sizeAfter how many are left once it is gone
     * @return which item is being looked at afterwards
     *
     * <p><b>The selection follows the ITEM, not the slot.</b> The removal used
     * to be {@code selected = Math.min(selected, size - 1)}, which is right for
     * the two easy cases and wrong for the third: taking out an item BEFORE the
     * selected one shifts everything after it down, and the reader is left
     * looking at the neighbour. With three layouts and the reader on the second,
     * deleting the first left them on the third -- and the chart swapped its
     * indicators for a layout the reader had not even been using, with nothing
     * on screen to say why.</p>
     *
     * <p>Here, beside {@code move}, and for the same reason that one is here:
     * the arithmetic is one off-by-one deep and it can be tested without a
     * window.</p>
     */
    public static int selectionAfterRemoval(int selected, int removed, int sizeAfter) {
        if (sizeAfter <= 0) {
            return -1;
        }

        if (removed < selected) {
            // Everything after the hole slid down by one, the selection with it.
            return selected - 1;
        }

        if (removed > selected) {
            // Nothing before the selection moved.
            return selected;
        }

        // The selected one is what went. The nearest thing to where the reader
        // was looking is what is in that slot now, or the last one.
        return Math.min(selected, sizeAfter - 1);
    }
}
