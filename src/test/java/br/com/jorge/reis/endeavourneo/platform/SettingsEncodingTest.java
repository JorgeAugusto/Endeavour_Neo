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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Accents surviving a close and an open.
 *
 * <p>Seen on screen: a window titled <i>Sem título</i> came back as <i>Sem
 * tÃ­tulo</i>. The file was written in UTF-8 and read with {@code
 * Properties.load(InputStream)}, which by its own contract decodes ISO-8859-1 —
 * so every accented letter came back as the two characters its UTF-8 bytes
 * happen to spell in the other encoding.</p>
 *
 * <p>The damage <b>compounds</b>: the wrong text was then written back out in
 * UTF-8 and read wrong again on the next launch. The file on the machine where
 * this was found had been through four rounds, and the title had grown from ten
 * characters to twenty-five.</p>
 */
@DisplayName("Settings and accents")
class SettingsEncodingTest {

    private static final String TITLE = "Sem título";

    private static Settings at(Path file) {
        return new Settings(file, "a test");
    }

    /** One launch's worth of damage: UTF-8 bytes read as ISO-8859-1. */
    private static String corrupt(String text) {
        return new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("a title with accents comes back as it was written")
    void accentsSurviveTheRoundTrip(@TempDir Path folder) {
        Path file = folder.resolve("workspace.properties");
        Settings written = at(file);

        written.put("chart.open.0.series", TITLE);
        written.put("plain", "no accents here");

        assertEquals(TITLE, at(file).get("chart.open.0.series", ""));
        assertEquals("no accents here", at(file).get("plain", ""));
    }

    @Test
    @DisplayName("every accented letter in the interface survives, not just one")
    void theWholeAlphabetSurvives(@TempDir Path folder) {
        // The ones the Portuguese interface actually uses, plus the currency
        // sign and a dash that a title can carry.
        String all = "Média móvel · Configurações · Ação · Análise – R$ 1,00 · ãõçéíóúâêôàü";
        Path file = folder.resolve("settings.properties");

        at(file).put("everything", all);

        assertEquals(all, at(file).get("everything", ""));
    }

    @Test
    @DisplayName("a file already damaged is read as what it meant")
    void alreadyDamagedFilesAreRepaired(@TempDir Path folder) throws IOException {
        // Not hypothetical: this is the state of the file that showed the
        // defect. Repairing on read is what makes the fix reach a reader who
        // already has one, instead of only protecting titles written from now
        // on -- their old title would otherwise stay broken for good.
        assertEquals("Sem tÃ­tulo", corrupt(TITLE), "one round should look like the screen did");

        String damaged = corrupt(corrupt(corrupt(corrupt(TITLE))));
        Path file = folder.resolve("workspace.properties");

        Files.write(file, ("chart.open.0.series=" + damaged + "\n")
                .getBytes(StandardCharsets.UTF_8));

        assertEquals(TITLE, at(file).get("chart.open.0.series", ""));
    }

    @Test
    @DisplayName("text that was never damaged is left alone")
    void healthyTextIsNotTouched(@TempDir Path folder) throws IOException {
        // The risk of repairing on read. Correct Portuguese must not be
        // mistaken for damage: "çã" as ISO-8859-1 bytes is not valid UTF-8, so
        // the repair stops -- but that has to be proved, not assumed.
        String healthy = "Ação · Média · coração · ÃGUA";
        Path file = folder.resolve("settings.properties");

        Files.write(file, ("key=" + healthy + "\n").getBytes(StandardCharsets.UTF_8));

        assertEquals(healthy, at(file).get("key", ""));
    }
}
