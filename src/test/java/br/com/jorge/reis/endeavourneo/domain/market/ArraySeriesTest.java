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
package br.com.jorge.reis.endeavourneo.domain.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The series every fold in this project hands back.
 *
 * <p>It had no test of its own. Everything that folds ends in one of these, so
 * it was exercised constantly and asserted about never -- and what it promises
 * about the arrays it is given was checked by nothing.</p>
 */
@DisplayName("Array series")
class ArraySeriesTest {

    private static long[] times(int bars) {
        long[] times = new long[bars];

        for (int i = 0; i < bars; i++) {
            times[i] = i * 60_000L;
        }

        return times;
    }

    private static double[] filled(int bars, double value) {
        double[] values = new double[bars];

        java.util.Arrays.fill(values, value);

        return values;
    }

    @Test
    @DisplayName("vetores de tamanhos diferentes sao recusados na construcao")
    void arraysOfDifferentLengthsAreRefused() {
        // size() answers times.length and every accessor indexes its own array,
        // so a mismatched set is a series that answers some questions and throws
        // on others, at a bar number that depends on which array is short. The
        // three callers build them all the same size -- and that was the only
        // thing stopping it, which is a rule nobody wrote down.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new ArraySeries(times(4), filled(3, 1), filled(4, 1),
                        filled(4, 1), filled(4, 1), null, null, null),
                "a set of arrays of different lengths was accepted");

        assertTrue(thrown.getMessage().contains("opens"), thrown.getMessage());

        // The optional ones are checked too when they are there, and allowed to
        // be absent -- which is what null means for volume, gaps and counts.
        assertThrows(IllegalArgumentException.class,
                () -> new ArraySeries(times(4), filled(4, 1), filled(4, 1),
                        filled(4, 1), filled(4, 1), filled(2, 1), null, null),
                "a volume array of the wrong length was accepted");
    }

    @Test
    @DisplayName("sem volume, sem tijolo cinza e sem contagem, ela responde o que nao sabe")
    void whatItDoesNotKnowItSaysItDoesNotKnow() {
        // The three optional arrays are the difference between "zero" and "we
        // were not told", which is the distinction half this package exists to
        // keep.
        ArraySeries bars = new ArraySeries(times(3), filled(3, 100), filled(3, 105),
                filled(3, 95), filled(3, 102), null, null, null);

        assertEquals(3, bars.size());
        assertTrue(Double.isNaN(bars.volumeAt(1)), "no volume came back as a number");
        assertEquals(Counted.UNKNOWN, bars.tradesAt(1), "no count came back as a count");
        assertEquals(false, bars.untradedAt(1), "no gap flag came back as a gap");
    }
}
