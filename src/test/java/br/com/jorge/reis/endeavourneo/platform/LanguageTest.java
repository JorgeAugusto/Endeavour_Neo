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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Choosing a language, and the fallback that made it look broken.
 */
@DisplayName("Language")
class LanguageTest {

    @AfterEach
    void restore() {
        Messages.setLocale(Locale.getDefault());
    }

    @Test
    @DisplayName("English gives English, even on a Brazilian machine")
    void englishIsReallyEnglish() {
        // The defect this exists for. getBundle looks for messages_en, then for
        // the DEFAULT LOCALE's bundle, then the base one -- so with no
        // messages_en on disk it landed on messages_pt_BR, and the base bundle,
        // which IS English, was never reached. Choosing English did nothing.
        Messages.setLocale(new Locale("pt", "BR"));

        String portuguese = Messages.get("settings.chart");

        Messages.setLocale(Locale.ENGLISH);

        String english = Messages.get("settings.chart");

        assertNotEquals(portuguese, english,
                "English fell back to the machine's language: " + english);
        assertEquals("Chart", english);
        assertEquals("Gráfico", portuguese);
    }

    @Test
    @DisplayName("an unknown code follows the machine rather than guessing")
    void unknownCodeFollowsTheMachine() {
        // A hand-edited file, or one written by a version that knows a language
        // this one does not.
        assertEquals(Language.SYSTEM, Language.of("klingon"));
        assertEquals(Language.SYSTEM, Language.of(null));
        assertEquals(Language.ENGLISH, Language.of("en"));
        assertEquals(Language.PORTUGUESE, Language.of("pt-BR"));
    }

    @Test
    @DisplayName("following the system means the machine's locale, not a frozen one")
    void systemIsNotFrozen() {
        // Switching BACK to "follow the system" used to leave the previous
        // choice in place until the next launch, because install() skipped
        // SYSTEM entirely.
        assertEquals(Locale.getDefault(), Language.SYSTEM.locale());
    }
}
