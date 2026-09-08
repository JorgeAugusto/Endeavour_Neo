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

import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Choosing a language, and the fallback that made it look broken.
 */
@DisplayName("Language")
class LanguageTest {

    /**
     * The JVM's own locale, put back after every test.
     *
     * <p>install() now moves it, which is the point of
     * {@link #installMovesTheJvmLocaleToo()}. Leaving it moved would hand the
     * next test in the run a machine that is not this one.</p>
     */
    private final Locale machine = Locale.getDefault();

    /**
     * The reader's own choice, put back after every test.
     *
     * <p>{@code remember()} writes to the real workspace. A test that leaves it
     * moved changes the language of the application on the machine that ran the
     * suite, which is not a thing a test is allowed to do.</p>
     */
    private Language chosen;

    /**
     * A settings file of this test's own, and it used to be the reader's.
     *
     * <p>{@code Language.remember()} writes through {@code Settings.settings()},
     * whose home is redirected by a property set in ONE place: the surefire
     * plugin. The house documents running the suite with javac and a runner
     * instead, and on that path the property is absent and this test rewrote the
     * actual settings file of whoever ran it. Putting it back afterwards only
     * works while every test passes -- and the javadoc of the field below
     * already said, before this was fixed, that it "is not a thing a test is
     * allowed to do".</p>
     */
    @org.junit.jupiter.api.io.TempDir
    Path store;

    @org.junit.jupiter.api.BeforeEach
    void useAStoreOfOurOwn() {
        Settings.useForTest(store);

        chosen = Language.remembered();
    }

    @AfterEach
    void restore() {
        chosen.remember();

        Locale.setDefault(machine);
        Messages.setLocale(machine);

        Settings.stopUsingTestStore();
    }

    @Test
    @DisplayName("choosing a language moves the JVM's locale, not only the bundle")
    void installMovesTheJvmLocaleToo() {
        // install() used to call Messages.setLocale and stop there, and half the
        // application never heard about it. Ten places read Locale.getDefault()
        // directly -- the calendar month, the MMM/yy of the time axis, the
        // decimal separator in seven readouts -- and Swing takes the words on the
        // JOptionPane buttons from the JVM locale, never from the bundle. On a
        // Brazilian machine set to English, the question came out in English over
        // buttons that said Sim and Nao.
        Locale.setDefault(new Locale("pt", "BR"));

        Language.ENGLISH.remember();
        Language.install();

        assertEquals(Language.ENGLISH.locale(), Locale.getDefault(),
                "the bundle changed language and the JVM did not");

        // And back the other way, so this cannot pass by only ever moving once.
        Language.PORTUGUESE.remember();
        Language.install();

        assertEquals(Language.PORTUGUESE.locale(), Locale.getDefault());
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
