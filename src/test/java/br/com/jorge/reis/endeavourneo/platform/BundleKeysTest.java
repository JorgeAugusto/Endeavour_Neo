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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two bundles, held to being two spellings of one thing.
 *
 * <h2>Why a key defined twice is worse than a key missing</h2>
 *
 * <p>A missing key shows up on screen as the key itself — ugly, and impossible
 * to miss. A key defined TWICE resolves silently to whichever line comes last,
 * so the earlier one is dead text that reads like live text: somebody edits it,
 * nothing changes, and there is nothing to look at.</p>
 *
 * <p>It happened with {@code replay.speed}, which meant "bars/s" on one line and
 * "speed" on another. The first was left over from a design the transport
 * abandoned — speed as bars per second, replaced by a multiple of real time —
 * and it had been dead for as long as the second line existed.</p>
 */
@DisplayName("Chaves dos bundles")
class BundleKeysTest {

    private static final Path ENGLISH =
            Path.of("src", "main", "resources", "messages.properties");

    private static final Path BRAZILIAN =
            Path.of("src", "main", "resources", "messages_pt_BR.properties");

    /** @return every key in the file, in order, repeats included */
    private static List<String> keysOf(Path bundle) throws IOException {
        List<String> keys = new ArrayList<>();

        for (String line : Files.readAllLines(bundle, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();

            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }

            keys.add(trimmed.substring(0, trimmed.indexOf('=')).strip());
        }

        return keys;
    }

    private static List<String> repeatedIn(Path bundle) throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        List<String> twice = new ArrayList<>();

        for (String key : keysOf(bundle)) {
            if (!seen.add(key)) {
                twice.add(key);
            }
        }

        return twice;
    }

    @Test
    @DisplayName("nenhuma chave e definida duas vezes")
    void noKeyIsDefinedTwice() throws IOException {
        assertEquals(List.of(), repeatedIn(ENGLISH),
                "a key is defined twice in the English bundle; the first is dead text");
        assertEquals(List.of(), repeatedIn(BRAZILIAN),
                "a key is defined twice in the Brazilian bundle; the first is dead text");
    }

    @Test
    @DisplayName("os dois bundles dizem as mesmas chaves")
    void bothBundlesSayTheSameKeys() throws IOException {
        // A key in one and not the other is a screen that falls back to the key
        // itself in one language. The fallback exists and is right -- better the
        // key than a blank -- but it is not something to ship on purpose.
        Set<String> english = new TreeSet<>(keysOf(ENGLISH));
        Set<String> brazilian = new TreeSet<>(keysOf(BRAZILIAN));

        Set<String> missingThere = new TreeSet<>(english);
        Set<String> missingHere = new TreeSet<>(brazilian);

        missingThere.removeAll(brazilian);
        missingHere.removeAll(english);

        assertEquals(Set.of(), missingThere, "keys with no Brazilian text");
        assertEquals(Set.of(), missingHere, "keys with no English text");
    }

    @Test
    @DisplayName("toda chave que o codigo pede existe no bundle")
    void everyKeyTheCodeAsksForExists() throws IOException {
        // The two tests above compare the bundles WITH EACH OTHER: no repeats,
        // and the same set on both sides. Neither compares a bundle with the
        // CODE -- so a key missing from both files satisfies them perfectly,
        // and the screen shows !chart.renkoNeedsTicks! where words should be.
        // This file was the only automatic barrier between the bundle and the
        // screen, and it was looking the other way.
        Set<String> defined = new TreeSet<>(keysOf(ENGLISH));
        List<String> missing = new ArrayList<>();
        int seen = 0;

        try (java.util.stream.Stream<Path> tree =
                Files.walk(Path.of("src", "main", "java"))) {

            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);

                for (String call : CALLS) {
                    int at = source.indexOf(call);

                    while (at >= 0) {
                        int from = at + call.length();
                        int to = source.indexOf(QUOTE, from);

                        if (to > from && !isBuiltAtRuntime(source, to)) {
                            seen++;

                            String key = source.substring(from, to);

                            if (!defined.contains(key)) {
                                missing.add(file.getFileName() + ": " + key);
                            }
                        }

                        at = source.indexOf(call, at + 1);
                    }
                }
            }
        }

        // The sweep has to have found something, or an empty list would mean
        // "nothing asked for anything" and this would pass for ever. Every
        // source sweep fails that way, and this file now has three of them.
        assertEquals(true, seen > 100,
                "only " + seen + " keys were found in the source: the sweep is not "
                        + "reading what it thinks it is reading");

        assertEquals(List.of(), missing,
                "a screen will show !key! where words should be");
    }

    /**
     * @param source the file being read
     * @param quote where the literal ends
     * @return whether the key goes on past it
     *
     * <p>{@code Messages.orElse("navigator.scale." + scale, scale)} names half a
     * key: the other half is a value only the running program has. Asked whether
     * that half is in the bundle, the answer is always no, and it means nothing
     * -- which is why those calls take a fallback in the first place.</p>
     *
     * <p>Detected by looking for the concatenation itself rather than by
     * guessing from the shape of the key. A rule like "ends with a dot" would
     * also excuse a real typo that happened to end with one.</p>
     */
    private static boolean isBuiltAtRuntime(String source, int quote) {
        for (int at = quote + 1; at < source.length(); at++) {
            char letter = source.charAt(at);

            if (letter == ' ') {
                continue;
            }

            return letter == '+';
        }

        return false;
    }

    /** A double quote, named so the calls above read as what they match. */
    private static final String QUOTE = "\"";

    /**
     * The calls that name a key as a literal, up to the opening quote.
     *
     * <p>Only the literal ones. A key built at runtime -- {@code
     * "settings.language." + code} -- is not a string in the source, and
     * pretending to check it would mean guessing what the halves add up to.
     * Those are what {@code Messages.orElse} takes a fallback for.</p>
     */
    private static final List<String> CALLS = List.of(
            "Messages.get(" + QUOTE,
            "Messages.orElse(" + QUOTE,
            "Messages.mnemonic(" + QUOTE);
}
