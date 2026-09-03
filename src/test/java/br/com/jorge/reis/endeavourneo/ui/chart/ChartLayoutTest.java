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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Chart layout")
class ChartLayoutTest {

    @Test
    @DisplayName("a layout survives being written and read back, exactly")
    void roundTrip() {
        // This is the whole promise of a saved layout: reopening tomorrow gives
        // back what was there yesterday. Parameters and the eye included -- a
        // hidden indicator that comes back visible is a change nobody made.
        List<ChartLayout.Entry> entries = List.of(
                new ChartLayout.Entry("overlay.ema", List.of(17, 55, 200), true),
                new ChartLayout.Entry("overlay.ema", List.of(9), false));

        List<ChartLayout.Entry> back = ChartLayouts.parse(ChartLayouts.format(entries));

        assertEquals(entries, back, "a layout changed on the way to storage and back");
    }

    @Test
    @DisplayName("a corrupt line is skipped, and the rest of the layout still loads")
    void corruptLineDoesNotLoseTheOthers() {
        // The file is not written by hand, so a bad line means something went
        // wrong elsewhere. Refusing the whole layout over one line would lose
        // the other five, which is a worse answer than losing the one.
        String text = "overlay.ema|17,55|true\n"
                + "garbage\n"
                + "overlay.ema|abc|true\n"
                + "overlay.ema|9|false";

        List<ChartLayout.Entry> entries = ChartLayouts.parse(text);

        assertEquals(2, entries.size(), "the good lines should have survived");
        assertEquals(List.of(17, 55), entries.get(0).parameters());
        assertFalse(entries.get(1).visible());
    }

    @Test
    @DisplayName("an indicator that no longer exists is skipped, not fatal")
    void unknownKindIsSkipped() {
        // A layout saved by an older version can name an indicator that has since
        // been removed. It must not stop the chart from opening.
        ChartLayout layout = new ChartLayout("old", List.of(
                new ChartLayout.Entry("overlay.thatWentAway", List.of(5), true),
                new ChartLayout.Entry("overlay.ema", List.of(20), true)));

        List<Overlay> built = layout.build();

        assertEquals(1, built.size(), "the surviving indicator should still be built");
        assertEquals(List.of(20), built.get(0).parameters());
    }

    @Test
    @DisplayName("capturing a chart and rebuilding it gives the same indicators")
    void captureAndRebuild() {
        ChartLayout captured = ChartLayout.of("mine", List.of(
                built("overlay.ema", 17, 55),
                hidden("overlay.ema", 200)));

        List<Overlay> rebuilt = captured.build();

        assertEquals(2, rebuilt.size(), "an indicator was lost in the round trip");
        assertTrue(rebuilt.get(0).isVisible(), "the visible one came back hidden");
        assertFalse(rebuilt.get(1).isVisible(), "the hidden one came back visible");
    }

    @Test
    @DisplayName("copying a copy stacks the prefix instead of colliding")
    void copyNamesDoNotCollide() {
        // Silly names, and honest ones: they record that the layout came from
        // another, which is what the reader needs when two charts look almost
        // alike.
        List<String> taken = new java.util.ArrayList<>(List.of("Clean"));
        String first = ChartLayouts.copyName(taken, "Clean");

        taken.add(first);

        String second = ChartLayouts.copyName(taken, "Clean");

        assertFalse(first.equals(second), "the second copy reused the first one's name");
        assertTrue(second.contains(first) || !taken.contains(second),
                "the second copy has to be distinguishable from the first");
    }

    private static Overlay built(String key, int... periods) {
        return new ChartLayout.Entry(key, boxed(periods), true).build();
    }

    private static Overlay hidden(String key, int... periods) {
        return new ChartLayout.Entry(key, boxed(periods), false).build();
    }

    private static List<Integer> boxed(int... values) {
        List<Integer> list = new java.util.ArrayList<>(values.length);

        for (int value : values) {
            list.add(value);
        }

        return list;
    }
}
