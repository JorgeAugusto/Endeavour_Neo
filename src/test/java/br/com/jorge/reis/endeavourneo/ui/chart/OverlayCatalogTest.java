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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Overlay catalogue")
class OverlayCatalogTest {

    @Test
    @DisplayName("every kind builds, is named, and its factory honours the parameters given")
    void everyKindIsUsable() {
        // Walks the whole catalogue rather than checking one entry: the point of
        // a list is that adding a kind needs no change anywhere else, and this
        // is what makes a new entry fail here instead of in the dialog.
        assertFalse(OverlayCatalog.kinds().isEmpty(), "the catalogue is empty");

        for (OverlayCatalog.Kind kind : OverlayCatalog.kinds()) {
            assertFalse(kind.label().startsWith("!"),
                    "untranslated name: " + kind.nameKey());

            int[] values = new int[kind.defaults().size()];

            for (int i = 0; i < values.length; i++) {
                values[i] = kind.defaults().get(i);
            }

            Overlay overlay = kind.factory().apply(values);

            assertNotNull(overlay, kind.nameKey() + " built nothing");
            assertEquals(kind.defaults(), overlay.parameters(),
                    kind.nameKey() + " ignored the parameters it was given");
            // One colour per LINE, which is what the canvas walks: it draws
            // colours().size() polylines and reads that many values out of
            // valueAt. The first version of this compared against the number of
            // PARAMETERS, which was the same number only for as long as every
            // indicator here was a single line with a single period. Bollinger
            // bands take one parameter and draw three lines, and the old
            // assertion called that a defect.
            assertEquals(overlay.colours().size(), overlay.valueAt(0).length,
                    kind.nameKey() + " draws a line it has no colour for, or the reverse");
        }
    }

    @Test
    @DisplayName("the bounds keep out the values that break the indicator")
    void boundsExcludeTheBreakingValues() {
        // A period of zero divides by zero; a period of half a million allocates
        // a pointless array. Both get typed by accident, and a spinner that
        // refuses them is better than an error dialog after the fact.
        for (OverlayCatalog.Kind kind : OverlayCatalog.kinds()) {
            assertTrue(kind.minimum() >= 1,
                    kind.nameKey() + " allows a period below one");
            assertTrue(kind.maximum() > kind.minimum(),
                    kind.nameKey() + " has an empty range");

            for (int value : kind.defaults()) {
                assertTrue(value >= kind.minimum() && value <= kind.maximum(),
                        kind.nameKey() + " has a default outside its own bounds: " + value);
            }
        }
    }
}
