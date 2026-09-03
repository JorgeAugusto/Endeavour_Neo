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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Settings written to a file and read back.
 *
 * <p>There was no test here at all, and the first thing the file store did on
 * the first launch was overflow the stack: the trick used to sort the output
 * built a sorted copy inside the method that produced it. A single save-and-load
 * would have caught it before it ever ran.</p>
 */
@DisplayName("Settings")
class SettingsTest {

    private static Settings at(Path file) {
        return new Settings(file, "a test");
    }

    @Test
    @DisplayName("a value written is a value read back")
    void roundTrip(@TempDir Path folder) {
        Path file = folder.resolve("s.properties");
        Settings written = at(file);

        written.put("theme", "night");
        written.putInt("history", 30);
        written.putBoolean("grid", true);

        Settings read = at(file);

        assertEquals("night", read.get("theme", "light"));
        assertEquals(30, read.getInt("history", 0));
        assertTrue(read.getBoolean("grid", false));
    }

    @Test
    @DisplayName("saving does not recurse -- the defect that shipped")
    void savingTerminates(@TempDir Path folder) {
        // Not a clever test, and it is the one that was missing. The sorted
        // output was produced by a Properties subclass whose entrySet built a
        // TreeMap of itself, which called putAll, which called entrySet.
        Settings settings = at(folder.resolve("s.properties"));

        for (int i = 0; i < 50; i++) {
            settings.put("key." + i, "value " + i);
        }

        assertEquals("value 49", settings.get("key.49", null));
    }

    @Test
    @DisplayName("a value carrying newlines survives, because a layout is one")
    void newlinesSurvive(@TempDir Path folder) {
        // A chart layout is stored as one line per indicator inside a single
        // value. Losing the newlines would silently flatten every layout into
        // one broken entry.
        Path file = folder.resolve("s.properties");
        String layout = "overlay.ema|17,55,200|true\noverlay.ema|9|false";

        at(file).put("layout.0", layout);

        assertEquals(layout, at(file).get("layout.0", null));
    }

    @Test
    @DisplayName("the awkward characters survive too")
    void awkwardCharactersSurvive(@TempDir Path folder) {
        Path file = folder.resolve("s.properties");
        String nasty = "a=b : c # d ! e \\ f\tg";

        at(file).put("weird", nasty);

        assertEquals(nasty, at(file).get("weird", null));
    }

    @Test
    @DisplayName("the file comes out sorted, so a change is one line in a diff")
    void theFileIsSorted(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("s.properties");
        Settings settings = at(file);

        settings.put("zebra", "1");
        settings.put("alpha", "2");
        settings.put("middle", "3");

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.startsWith("#"))
                .toList();

        assertEquals(List.of("alpha=2", "middle=3", "zebra=1"), lines);
    }

    @Test
    @DisplayName("a missing file is the same as an empty one")
    void missingFileIsEmpty(@TempDir Path folder) {
        // The first launch. Refusing to start because there are no settings yet
        // would be the worst possible first impression.
        assertEquals("light", at(folder.resolve("never-written.properties"))
                .get("theme", "light"));
    }

    @Test
    @DisplayName("a whole group of keys goes at once")
    void removingAGroup(@TempDir Path folder) {
        Path file = folder.resolve("s.properties");
        Settings settings = at(file);

        settings.put("chart.open.0.series", "winn");
        settings.put("chart.open.1.series", "winfut");
        settings.put("theme", "night");

        settings.removeStartingWith("chart.open.");

        assertTrue(settings.keysStartingWith("chart.open.").isEmpty());
        assertEquals("night", settings.get("theme", null), "it took the wrong keys with it");

        assertFalse(at(file).keysStartingWith("chart.open.").size() > 0,
                "the removal was not written to the file");
    }
}
