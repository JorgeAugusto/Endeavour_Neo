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
package br.com.jorge.reis.endeavourneo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Javadoc that documents the wrong member.
 *
 * <h2>Why this is worth a test of its own</h2>
 *
 * <p>In Java only the LAST block before a member counts. Two blocks in a row
 * mean the first one is inert: it is not attached to what it describes, it does
 * not appear in the generated documentation, and it goes on reading like an
 * explanation of the member it is sitting on. Nothing warns.</p>
 *
 * <p>The audit found them one file at a time — {@code ChartCanvas} had eight,
 * {@code Renko} and {@code TickRenko} one each, and each was reported as its own
 * finding. A scan finds the whole class at once and keeps it from coming back,
 * which is worth more than any of the individual fixes.</p>
 *
 * <h2>The ceiling, and why there is one</h2>
 *
 * <p>Twenty-three remain, in files this sweep has not reached yet. A test that
 * demanded zero today would simply be red, and a red test is one nobody reads.
 * So the count may not GROW, and the files already cleaned must stay clean —
 * which is what stops the next edit from adding a twenty-fourth while the
 * backlog is being worked off.</p>
 *
 * <p><b>Drive it to zero and delete the ceiling.</b> The list below is the
 * backlog; when it empties, the first assertion is the only one that has to
 * stay.</p>
 */
@DisplayName("Javadoc orfao")
class OrphanJavadocTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    /** How many still stand, in files this sweep has not reached. */
    private static final int BACKLOG = 23;

    /** Files gone through, which may never regress. */
    private static final List<String> CLEANED = List.of(
            "ChartCanvas.java", "Renko.java", "TickRenko.java");

    /**
     * @return where each inert block starts, as {@code File.java:line}
     *
     * <p>A block is inert when the next thing after it, blank lines aside, is
     * another block.</p>
     */
    private static List<String> orphansIn(Path file) throws IOException {
        List<String> found = new ArrayList<>();
        List<String> lines = Files.readString(file, StandardCharsets.UTF_8).lines().toList();

        int i = 0;

        while (i < lines.size()) {
            if (!lines.get(i).strip().startsWith("/**")) {
                i++;

                continue;
            }

            int end = i;

            while (end < lines.size() && !lines.get(end).contains("*/")) {
                end++;
            }

            int next = end + 1;

            while (next < lines.size() && lines.get(next).isBlank()) {
                next++;
            }

            if (next < lines.size() && lines.get(next).strip().startsWith("/**")) {
                found.add(file.getFileName() + ":" + (i + 1));
            }

            i = end + 1;
        }

        return found;
    }

    private static List<String> everyOrphan() throws IOException {
        List<String> found = new ArrayList<>();

        try (Stream<Path> tree = Files.walk(MAIN)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                found.addAll(orphansIn(file));
            }
        }

        return found;
    }

    @Test
    @DisplayName("os arquivos ja varridos continuam limpos")
    void theSweptFilesStayClean() throws IOException {
        List<String> left = new ArrayList<>();

        try (Stream<Path> tree = Files.walk(MAIN)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (CLEANED.contains(file.getFileName().toString())) {
                    left.addAll(orphansIn(file));
                }
            }
        }

        assertEquals(List.of(), left, "a javadoc block went inert again in a swept file");
    }

    @Test
    @DisplayName("o passivo nao cresce")
    void thebacklogDoesNotGrow() throws IOException {
        List<String> all = everyOrphan();

        assertTrue(all.size() <= BACKLOG,
                "the backlog grew from " + BACKLOG + " to " + all.size() + ": " + all);
    }
}
