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
package br.com.jorge.reis.endeavourneo.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.Segment;

import java.time.LocalDate;
import java.util.List;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a series' segments are kept.
 */
@DisplayName("Segmentation")
class SegmentationTest {

    private static final String SERIES = "test-series-1m";

    /**
     * A settings file of this test's own.
     *
     * <p>Not the reader's workspace. Cleaning up afterwards would be enough
     * only while every test passes, and a test that fails halfway would leave
     * its scaffolding in their settings for ever.</p>
     */
    @TempDir
    Path folder;

    @BeforeEach
    void useATemporaryFile() {
        Segmentation.useForTest(new Settings(folder.resolve("workspace.properties"), "a test"));
    }

    @AfterEach
    void putTheWorkspaceBack() {
        Segmentation.useForTest(null);
    }

    @Test
    @DisplayName("segments come back as they went in, in order")
    void theyRoundTrip() {
        List<Segment> saved = List.of(
                new Segment("busca", LocalDate.of(2020, 9, 1), LocalDate.of(2024, 12, 31)),
                Segment.from("teste", LocalDate.of(2025, 1, 1)));

        Segmentation.set(SERIES, saved);

        List<Segment> read = Segmentation.of(SERIES);

        assertEquals(2, read.size());
        assertEquals("busca", read.get(0).name());
        assertEquals(LocalDate.of(2020, 9, 1), read.get(0).from());
        assertEquals(LocalDate.of(2024, 12, 31), read.get(0).to());
        assertEquals("teste", read.get(1).name());
        assertNull(read.get(1).to(), "the open-ended segment came back with an end");
    }

    @Test
    @DisplayName("a name may contain anything, including the separators")
    void anyNameSurvives() {
        // Why the file uses one key per field instead of one packed line. A
        // packed format needs escaping, and an escape that is forgotten turns
        // one segment into two broken ones.
        String awkward = "busca, 2 anos = quase tudo: 2020-2024";

        Segmentation.set(SERIES, List.of(Segment.from(awkward, LocalDate.of(2020, 9, 1))));

        assertEquals(awkward, Segmentation.of(SERIES).get(0).name());
    }

    @Test
    @DisplayName("saving replaces, so a removed segment is really gone")
    void savingReplaces() {
        // Writing over the old keys without clearing them would leave the third
        // segment behind, and it would come back on the next launch as if the
        // reader had never deleted it.
        Segmentation.set(SERIES, List.of(
                Segment.from("um", LocalDate.of(2021, 1, 1)),
                Segment.from("dois", LocalDate.of(2022, 1, 1)),
                Segment.from("tres", LocalDate.of(2023, 1, 1))));

        Segmentation.set(SERIES, List.of(Segment.from("um", LocalDate.of(2021, 1, 1))));

        assertEquals(1, Segmentation.of(SERIES).size(),
                "a deleted segment survived the save");
    }

    @Test
    @DisplayName("each series keeps its own")
    void seriesDoNotShare() {
        Segmentation.set(SERIES, List.of(Segment.from("um", LocalDate.of(2021, 1, 1))));
        Segmentation.set("outra-1m", List.of(
                Segment.from("a", LocalDate.of(2022, 1, 1)),
                Segment.from("b", LocalDate.of(2023, 1, 1))));

        assertEquals(1, Segmentation.of(SERIES).size());
        assertEquals(2, Segmentation.of("outra-1m").size());
    }

    @Test
    @DisplayName("a series never divided has no segments, which is not an error")
    void nothingIsAnAnswer() {
        assertTrue(Segmentation.of("nunca-dividida-1m").isEmpty());
    }

    @Test
    @DisplayName("overlapping segments are reported, and nothing is refused")
    void overlapsAreToldNotBlocked() {
        // A stretch used for searching AND for testing proves nothing, so it is
        // worth saying. But this is a selector, not a guard: the reader may
        // have meant it, and a window that refused would be pretending to
        // enforce a discipline that lives in their head.
        List<Segment> clashing = List.of(
                new Segment("busca", LocalDate.of(2020, 9, 1), LocalDate.of(2024, 12, 31)),
                Segment.from("teste", LocalDate.of(2024, 6, 1)));

        assertEquals(List.of("busca / teste"), Segmentation.overlapping(clashing));

        // And saving them works, because refusing is not this window's job.
        Segmentation.set(SERIES, clashing);

        assertEquals(2, Segmentation.of(SERIES).size());
    }

    @Test
    @DisplayName("segments that only touch at the edges do not overlap")
    void touchingIsNotOverlapping() {
        // The ordinary case: one ends on the 31st and the next starts on the
        // 1st. Calling that an overlap would make the warning cry wolf on every
        // segmentation anybody actually builds.
        List<Segment> tidy = List.of(
                new Segment("busca", LocalDate.of(2020, 9, 1), LocalDate.of(2024, 12, 31)),
                Segment.from("teste", LocalDate.of(2025, 1, 1)));

        assertTrue(Segmentation.overlapping(tidy).isEmpty());
    }

    @Test
    @DisplayName("an open-ended segment overlaps everything after it")
    void onwardsReachesForward() {
        List<Segment> clashing = List.of(
                Segment.from("tudo", LocalDate.of(2020, 9, 1)),
                new Segment("teste", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)));

        assertEquals(List.of("tudo / teste"), Segmentation.overlapping(clashing));
    }
}
