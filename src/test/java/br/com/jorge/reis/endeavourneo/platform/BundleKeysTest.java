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
}
