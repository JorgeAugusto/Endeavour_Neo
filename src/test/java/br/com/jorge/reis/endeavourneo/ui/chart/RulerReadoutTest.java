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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the ruler's box says, and how many times it says it.
 */
@DisplayName("Ruler readout")
class RulerReadoutTest {

    /** A move of 12,5 points: half a tick on an instrument whose tick is five. */
    private static Measurement halfATick() {
        return new Measurement(0, 6, 118_000, 118_012.5, 0L, 5_400_000L);
    }

    @Test
    @DisplayName("the size of the move is stated once, not once rounded and once not")
    void theMoveIsStatedOnce() {
        // It was on two lines: "Change" wrote 12,50 pts and "Difference" wrote
        // the SAME variable with no decimals, so the box read +12,50 on one row
        // and 13 on the next, with nothing saying they were the same question.
        List<String> mentions = new ArrayList<>();

        for (String[] row : RulerReadout.rowsFor(halfATick())) {
            String value = row[1];

            if (value.startsWith("+12,5") || value.startsWith("+12.5")
                    || "13".equals(value) || "12".equals(value)) {

                mentions.add(row[0] + " = " + value);
            }
        }

        assertEquals(1, mentions.size(),
                "the move is answered more than once, and the answers disagree: " + mentions);
    }

    @Test
    @DisplayName("the two prices and the span are still there")
    void theRestIsIntact() {
        // Deleting the duplicate must not have taken a real row with it.
        List<String> values = new ArrayList<>();

        for (String[] row : RulerReadout.rowsFor(halfATick())) {
            values.add(row[1]);
        }

        assertTrue(values.contains("118.000") || values.contains("118,000"),
                "the price the drag started at is gone: " + values);
        // 118.012 and not 118.013: DecimalFormat rounds half to even, and this
        // row is the rounded one that stays -- it is a PRICE, and prices on this
        // instrument are whole points.
        assertTrue(values.contains("118.012") || values.contains("118,012"),
                "the price it ended at is gone: " + values);
        assertEquals("7", values.get(values.size() - 1), "the bar count is gone: " + values);
    }
}
