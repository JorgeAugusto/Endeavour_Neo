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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A slice is a window, not a copy.
 *
 * <p>The bars carry their own index as the close price, so every assertion below
 * reads directly as "which bar of the base did this slice hand back".</p>
 */
@DisplayName("Segmented series")
class SegmentedSeriesTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    /**
     * Ten days of three bars each, 2026-09-01 onwards: bar {@code i} closes at
     * {@code i}, so day {@code d} holds bars {@code 3d}, {@code 3d+1},
     * {@code 3d+2}.
     */
    private static PriceSeries tenDays() {
        int bars = 30;
        long[] times = new long[bars];

        for (int i = 0; i < bars; i++) {
            times[i] = LocalDateTime.of(2026, 9, 1 + i / 3, 10 + i % 3, 0)
                    .atZone(ZONE).toInstant().toEpochMilli();
        }

        return new PriceSeries() {

            @Override
            public int size() {
                return bars;
            }

            @Override
            public long timeAt(int index) {
                return times[index];
            }

            @Override
            public double openAt(int index) {
                return index;
            }

            @Override
            public double highAt(int index) {
                return index;
            }

            @Override
            public double lowAt(int index) {
                return index;
            }

            @Override
            public double closeAt(int index) {
                return index;
            }
        };
    }

    private static PriceSeries slice(LocalDate from, LocalDate to) {
        return SegmentedSeries.of(tenDays(), new Segment("Estudo", from, to), ZONE);
    }

    @Test
    @DisplayName("the slice hands back the base's bars, shifted")
    void translatesTheIndex() {
        // Days 3 and 4 of the month are the base's bars 6..11.
        PriceSeries cut = slice(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 4));

        assertEquals(6, cut.size());
        assertEquals(6.0, cut.closeAt(0), "the slice did not start where it was asked to");
        assertEquals(11.0, cut.closeAt(5), "the last day was cut short");
    }

    @Test
    @DisplayName("the last day is included whole, whatever time its bars carry")
    void theLastDayIsWhole() {
        // The classic off-by-one here takes "until the 4th" as "until the 4th at
        // 00:00" and silently drops that whole day's bars.
        PriceSeries cut = slice(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4));

        assertEquals(3, cut.size(), "the final day lost its bars");
        assertEquals(9.0, cut.closeAt(0));
        assertEquals(11.0, cut.closeAt(2));
    }

    @Test
    @DisplayName("an open slice runs to the end, and grows with the base")
    void openEndedRunsToTheEnd() {
        PriceSeries cut = SegmentedSeries.of(tenDays(),
                Segment.from("Prova", LocalDate.of(2026, 9, 9)), ZONE);

        assertEquals(6, cut.size());
        assertEquals(29.0, cut.closeAt(5), "an open slice must reach the last bar there is");
    }

    @Test
    @DisplayName("no segment means the base itself, untouched")
    void noSegmentIsTheWholeBase() {
        PriceSeries base = tenDays();

        assertSame(base, SegmentedSeries.of(base, null, ZONE),
                "asking for everything should not wrap anything");
    }

    @Test
    @DisplayName("a range outside the base gives an empty series, not an error")
    void outsideTheBaseIsEmpty() {
        // Normal while a date is still being typed. An empty chart says it
        // better than a dialog.
        assertEquals(0, slice(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 12, 31)).size());
        assertEquals(0, slice(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 12, 31)).size());
    }

    @Test
    @DisplayName("an index past the slice names the slice, not the base")
    void outOfBoundsNamesTheSlice() {
        PriceSeries cut = slice(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 4));

        // "bar 40000 out of bounds" on a six-bar slice sends the reader looking
        // in entirely the wrong place.
        IndexOutOfBoundsException thrown =
                assertThrows(IndexOutOfBoundsException.class, () -> cut.closeAt(6));

        assertTrue(thrown.getMessage().contains("6 bars"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Estudo"), thrown.getMessage());
    }

    @Test
    @DisplayName("the times come from the base, unshifted")
    void timesAreTheBaseTimes() {
        PriceSeries base = tenDays();
        PriceSeries cut = slice(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 4));

        // A slice that renumbered time would make every chart axis and every
        // trade timestamp a lie.
        assertEquals(base.timeAt(6), cut.timeAt(0));
    }

    @Test
    @DisplayName("a segment that ends before it starts is refused at birth")
    void backwardsSegmentIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new Segment("nada", LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 1)));
    }

    @Test
    @DisplayName("the label is short enough to sit in a title")
    void labelReadsAtAGlance() {
        assertEquals("Estudo 2021–2024", new Segment("Estudo",
                LocalDate.of(2021, 8, 30), LocalDate.of(2024, 12, 31)).label());
        assertEquals("Prova 2025–", Segment.from("Prova", LocalDate.of(2025, 1, 1)).label());
    }
    @Test
    @DisplayName("o recorte e a juncao carregam o tijolo cinza e a contagem")
    void thewrappersCarryUntradedAndCounted() {
        // Untraded and Counted decide by instanceof, and a wrapper that declares
        // only PriceSeries answers "no bar was untraded" and "the number of
        // trades is unknown" for a renko it is holding -- in silence, with no
        // exception anywhere.
        //
        // Not reachable through today's pipeline, which is source, then slice,
        // then scale, with the renko always last. That is a trap held shut by
        // the ORDER of three calls rather than by the types, and the order is
        // not written down anywhere.
        PriceSeries marked = new ArraySeries(
                new long[]{0, 60_000, 120_000, 180_000},
                new double[]{1, 2, 3, 4}, new double[]{1, 2, 3, 4},
                new double[]{1, 2, 3, 4}, new double[]{1, 2, 3, 4},
                null,
                new boolean[]{false, true, false, true},
                new long[]{7, 0, 9, 0});

        // Midnight of 01/01/1970 in the zone this test uses, which is where
        // epoch-zero timestamps land.
        java.time.LocalDate DAY_OF_THE_FIXTURE =
                java.time.Instant.ofEpochMilli(0).atZone(ZONE).toLocalDate();

        PriceSeries joined = ConcatSeries.of(java.util.List.of(marked, marked));

        assertTrue(Untraded.at(joined, 1), "the join lost which bricks were grey");
        assertEquals(0L, Counted.at(joined, 1), "the join lost the trade count");
        assertTrue(Untraded.at(joined, 5), "the second part lost it too");
        assertEquals(9L, Counted.at(joined, 6), "the second part lost its count");

        // And the slice, which is the other wrapper. The bars are one a minute
        // from midnight, so a segment of that single day holds all four.
        PriceSeries sliced = SegmentedSeries.of(marked,
                new Segment("Estudo", DAY_OF_THE_FIXTURE, DAY_OF_THE_FIXTURE), ZONE);

        assertEquals(4, sliced.size(), "the fixture did not slice, so this proves nothing");
        assertTrue(Untraded.at(sliced, 1), "the slice lost which bricks were grey");
        assertEquals(9L, Counted.at(sliced, 2), "the slice lost the trade count");
    }
}
