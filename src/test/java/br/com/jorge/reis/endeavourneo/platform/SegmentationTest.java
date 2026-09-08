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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        Segmentation.useForTest(folder.resolve("workspace.properties"));
    }

    @AfterEach
    void putTheWorkspaceBack() {
        Segmentation.stopUsingTestStore();
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

    @Test
    @DisplayName("um segmento tem nome composto, e ele volta inteiro")
    void aSegmentHasACompoundName() {
        Segment treino = new Segment("treino",
                LocalDate.of(2020, 9, 1), LocalDate.of(2023, 12, 29));

        Segmentation.set("winfull-1m", List.of(treino));

        String name = Segmentation.nameOf("winfull-1m", treino);

        assertEquals("winfull-1m#treino", name);
        assertEquals("winfull-1m", Segmentation.seriesIn(name));
        assertEquals(treino, Segmentation.segmentIn(name));
    }

    @Test
    @DisplayName("um nome sem segmento aponta para a serie inteira")
    void aPlainNameAsksForEverything() {
        assertEquals("winfull-1m", Segmentation.seriesIn("winfull-1m"));
        assertNull(Segmentation.segmentIn("winfull-1m"));
    }

    @Test
    @DisplayName("um segmento que sumiu vira pedido pela serie inteira")
    void aStaleNameFallsBackToTheWhole() {
        // The safe direction: a locked series then refuses out loud, instead of
        // quietly showing everything because a name went stale.
        Segmentation.set("winfull-1m", List.of(new Segment("teste",
                LocalDate.of(2025, 1, 2), LocalDate.of(2026, 9, 1))));

        assertNull(Segmentation.segmentIn("winfull-1m#treino"));
    }

    @Test
    @DisplayName("a trava sobrevive a gravar segmentos")
    void theLockOutlivesASave() {
        Segmentation.setSegmentsOnly("winfull-1m", true);
        Segmentation.set("winfull-1m", List.of(new Segment("treino",
                LocalDate.of(2020, 9, 1), LocalDate.of(2023, 12, 29))));

        assertTrue(Segmentation.segmentsOnly("winfull-1m"),
                "saving segments wiped the lock, which is the one thing it must not do");

        Segmentation.setSegmentsOnly("winfull-1m", false);

        assertFalse(Segmentation.segmentsOnly("winfull-1m"));
    }

    @Test
    @DisplayName("uma serie nunca trancada e uma destrancada sao a mesma coisa")
    void unlockedIsUnlocked() {
        assertFalse(Segmentation.segmentsOnly("nunca-vista"));
    }
/**
     * A line deleted by hand costs that segment, and only that one.
     *
     * <p>The reading loop counted up from zero and stopped at the first index
     * with no name or no date -- so a file missing one line threw away every
     * segment written below it, in silence. And a series marked
     * {@code segmentsOnly} then cannot be opened at all: whole, the lock refuses
     * it; by segment, they are gone.</p>
     *
     * <p>Deleting a line is the likeliest hand edit there is, and hand editing
     * is the declared reason this is stored as plain text.</p>
     */
    @Test
    @DisplayName("uma linha apagada a mao custa um segmento, nao todos")
    void ahandDeletedLineCostsOneSegment() throws java.io.IOException {
        Segmentation.set(SERIES, List.of(
                new Segment("busca", LocalDate.of(2020, 9, 1), LocalDate.of(2023, 12, 31)),
                new Segment("validacao", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)),
                Segment.from("teste", LocalDate.of(2025, 1, 1))));

        // WHAT A READER WITH A TEXT EDITOR DOES, on the file itself and not
        // through the API: one line goes, and the store is opened again so the
        // file is what is read rather than what is still in memory.
        java.nio.file.Path file = folder.resolve("workspace.properties");

        java.util.List<String> kept = new java.util.ArrayList<>();

        for (String line : java.nio.file.Files.readAllLines(file)) {
            if (!line.startsWith("segments." + SERIES + ".0.from")) {
                kept.add(line);
            }
        }

        assertEquals(java.nio.file.Files.readAllLines(file).size() - 1, kept.size(),
                "the line the test means to delete is not in the file");

        java.nio.file.Files.write(file, kept);

        Segmentation.useForTest(file);

        List<Segment> read = Segmentation.of(SERIES);

        assertEquals(2, read.size(),
                "the segments below the damaged one went with it: " + read);
        assertEquals("validacao", read.get(0).name());
        assertEquals("teste", read.get(1).name(), "and they came back out of order");
    }
}
