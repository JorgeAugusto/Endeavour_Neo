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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * Letting go of a tick export.
 *
 * <p>A library holds a reading thread and up to three sessions of ticks — the
 * one playing, one ahead, one behind — which on the real export is hundreds of
 * megabytes. It has always had a {@code close}, and did not say so in its type,
 * so the compiler could not ask for one and three of its owners forgot.</p>
 */
@DisplayName("Closing a tick library")
class TickLibraryClosingTest {

    @Test
    @DisplayName("it is AutoCloseable, so the compiler can ask for the close")
    void theTypeSaysItMustBeClosed() {
        // Not a formality. Without this the only way to be sure is for each
        // owner to remember a finally, and remembering is what three of them
        // did not do -- one in the navigator's tree, one behind a modal dialog,
        // one holding the replay's export for the life of the application.
        assertTrue(AutoCloseable.class.isAssignableFrom(TickLibrary.class),
                "TickLibrary has a close() and does not declare AutoCloseable, so "
                        + "try-with-resources cannot be used and every owner has to "
                        + "remember");
    }

    @Test
    @DisplayName("closing stops the reading thread, and says it did")
    void closingIsVisible(@TempDir Path folder) {
        TickLibrary library = new TickLibrary(folder, "winfut", TickSource.METATRADER);

        assertFalse(library.isClosed(), "a fresh library says it is already closed");

        library.close();

        assertTrue(library.isClosed(), "closing did not stop the reading thread");
    }

    @Test
    @DisplayName("every owner in the code closes what it opens")
    void everyOwnerCloses() throws IOException {
        // Read off the SOURCE, because this is a rule about the code and not
        // about one run of it. Each construction has to sit in a
        // try-with-resources, be handed to a field that something else closes,
        // or be closed in a finally within a few lines.
        //
        // The whole reason this is a test: the compiler cannot check it for a
        // library handed to a field, and a new owner written next year has
        // nothing to remind them.
        Path sources = Path.of("src", "main", "java");

        // A FAILURE AND NOT AN ASSUMPTION, because a skipped test is green in
        // the report. This is the only guard on "every owner closes what it
        // opens" for a class that starts a reading thread and holds up to three
        // sessions of ticks -- hundreds of megabytes -- and run from a
        // directory that is not the project root it used to disappear without
        // a word. The house has paid for that shape before: the neo's own GUI
        // tests once passed with the series deleted from the disk.
        assertTrue(Files.isDirectory(sources),
                "the suite has to run from the project root; this rule cannot be "
                        + "checked from anywhere else, and skipping it would report "
                        + "green for a check that never happened");

        List<String> unguarded = new ArrayList<>();

        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(each -> each.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

                for (int at = 0; at < lines.size(); at++) {
                    if (!lines.get(at).contains("TickLibrary(")
                            || lines.get(at).contains("public TickLibrary(")) {
                        continue;
                    }

                    // The shapes that are safe, read from the lines around the
                    // construction: try-with-resources on it, a finally that
                    // closes it, a field whose owner closes it, or an explicit
                    // "ownership:" note for the one case none of those can
                    // express -- a library handed to a background worker that
                    // either closes it or keeps it.
                    //
                    // The note is deliberately a thing somebody has to WRITE.
                    // Widening the search until every real case slipped through
                    // would have made this test agree with anything.
                    String around = String.join("\n",
                            lines.subList(Math.max(0, at - 8),
                                    Math.min(lines.size(), at + 14)));

                    boolean guarded = around.contains("try (")
                            || around.contains("finally")
                            || around.contains("this.ticks =")
                            || around.contains("ownership:");

                    if (!guarded) {
                        unguarded.add(sources.relativize(file) + ":" + (at + 1));
                    }
                }
            }
        }

        assertTrue(unguarded.isEmpty(),
                "a tick export is opened and nothing closes it, so its reading thread and "
                        + "its sessions stay for the life of the application:\n    "
                        + String.join("\n    ", unguarded));
    }
}
