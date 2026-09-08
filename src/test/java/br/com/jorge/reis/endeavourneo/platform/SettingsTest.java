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

    @Test
    @DisplayName("um escape torto nao impede o programa de abrir")
    void amalformedEscapeDoesNotStopTheProgram(@TempDir Path folder) throws IOException {
        // Properties.load throws IllegalArgumentException for a backslash-u with
        // fewer than four hex digits after it. That is not an IOException, so it
        // went past the catch, out of the static initialiser, and the
        // application would not open at all -- against the sentence in that very
        // catch, which promises the opposite: losing a theme is a smaller harm
        // than losing the program.
        Path file = folder.resolve("torto.properties");

        Files.writeString(file, "theme=" + BROKEN + "dark" + System.lineSeparator(),
                StandardCharsets.UTF_8);

        Settings settings = at(file);

        assertEquals("light", settings.get("theme", "light"),
                "the broken file was read as if it had been understood");
    }

    @Test
    @DisplayName("o que nao se conseguiu ler nao e sobrescrito")
    void whatCouldNotBeReadIsNotOverwritten(@TempDir Path folder) throws IOException {
        // The other half, and the one that cost the reader everything. A failed
        // read cleared the map, and the next put truncated the file and wrote
        // the empty map over it -- on an application that writes at startup, so
        // one unreadable byte took every setting and the whole workspace with
        // it, for good and with nothing said.
        Path file = folder.resolve("torto.properties");
        String original = "theme=" + BROKEN + "dark" + System.lineSeparator()
                + "language=pt_BR" + System.lineSeparator();

        Files.writeString(file, original, StandardCharsets.UTF_8);

        Settings settings = at(file);

        settings.put("theme", "light");

        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8),
                "a file that would not read was overwritten with what little "
                        + "could be salvaged");

        // AND IT STAYS REFUSED. The refusal used to leave a "_unsaved=true" key
        // in the map, and nothing in the program ever read it -- while nothing
        // ever removed it either, so one transient failure kept it in the
        // reader's file for ever, saying something that had stopped being true.
        // The key is gone; what has to hold is that the file on disk is still
        // the reader's, however many times the program tries to write.
        settings.put("language", "en");
        settings.remove("theme");

        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8),
                "a later write got through to a file that would not read");
    }

    /** A backslash-u with two hex digits, which is one Properties refuses. */
    /**
     * A backslash-u with two hex digits, which is one {@code Properties} refuses.
     *
     * <p>Built from the character and not written out, because the COMPILER
     * reads unicode escapes too -- everywhere, including inside string literals
     * and comments -- and refuses this file before it ever runs.</p>
     */
    private static final String BROKEN = ((char) 92) + "u00zz";

    @Test
    @DisplayName("um lote grava o arquivo UMA vez, e nao uma por chave")
    void abatchWritesTheFileOnce(@TempDir Path folder) throws Exception {
        // Every put rewrites the whole file, and the callers that write many
        // keys at a time are not unusual: storing the segments of one series is
        // three writes per segment plus one, remembering the open charts is two
        // per chart plus one, and moving a floating window is four in a row. All
        // of it on the interface thread, which is the thread that may not do
        // file work.
        //
        // COUNTED, because the modification time cannot answer this: ten writes
        // in a row move it exactly as far as one does. The first draft of this
        // test asked the clock, and it passed with the batching taken back out --
        // which is what the teeth proof is for.
        Path file = folder.resolve("lote.properties");
        Settings settings = at(file);

        settings.put("a", "1");

        int before = settings.writes();

        settings.hold(() -> {
            for (int i = 0; i < 10; i++) {
                settings.put("k" + i, String.valueOf(i));
            }
        });

        assertEquals("9", settings.get("k9", null), "the batch did not reach the file");
        assertEquals(before + 1, settings.writes(),
                "ten keys cost " + (settings.writes() - before) + " whole rewrites of "
                        + "the file, on the interface thread");

        // A batch that changes nothing writes nothing.
        int settled = settings.writes();

        settings.hold(() -> { });

        assertEquals(settled, settings.writes(), "an empty batch rewrote the file");

        // And a batch inside a batch does not end the outer one early.
        settings.hold(() -> {
            settings.put("outer", "1");
            settings.hold(() -> settings.put("inner", "2"));
            settings.put("after", "3");
        });

        assertEquals(settled + 1, settings.writes(),
                "a nested batch wrote in the middle of the one around it");
        assertEquals("3", settings.get("after", null), "the nested batch lost a key");
    }

    @Test
    @DisplayName("gravar duas vezes sem mudar nada da o MESMO arquivo, byte a byte")
    void writingTwiceGivesTheSameBytes(@TempDir Path folder) throws Exception {
        // The keys are sorted so that a change is one line in a diff -- the
        // javadoc of save says exactly that -- and line two was the clock at the
        // moment of writing, so EVERY write produced a diff. The launcher writes
        // on every start even when nothing changed, so the file moved every day
        // with no preference having moved at all.
        //
        // It was also a local time with no zone: ambiguous in the hour that
        // repeats at the end of summer time, in a program that treats zones as a
        // serious subject.
        Path file = folder.resolve("carimbo.properties");
        Settings settings = at(file);

        settings.put("theme", "dark");

        String first = Files.readString(file, StandardCharsets.UTF_8);

        Thread.sleep(1_100L);

        settings.put("theme", "dark");

        assertEquals(first, Files.readString(file, StandardCharsets.UTF_8),
                "writing the same settings a second later gave a different file: every "
                        + "save is a diff, which is what the sorting exists to prevent");
    }

    @Test
    @DisplayName("o arquivo diz em que formato esta, e um arquivo antigo vale como 1")
    void thefileSaysWhichFormatItIsIn(@TempDir Path folder) throws IOException {
        // There was no version, and it had already cost twice: the encoding
        // repair is seventy lines that undo up to eight rounds of damage and
        // decide by examining the bytes whether the text is broken, and the
        // change of the default series name leaves any older workspace pointing
        // at a series that still exists and is no longer the source. Both were
        // resolved by guessing at the content instead of by reading a number.
        //
        // Nothing migrates yet. The point is that the next change has somewhere
        // to look.
        Path file = folder.resolve("formato.properties");
        Settings settings = at(file);

        settings.put("theme", "dark");

        String written = Files.readString(file, StandardCharsets.UTF_8);

        // The LINE, spelled out. Asking formatOf what the file says answers 1
        // for a file that says nothing at all, so while the format IS one that
        // assertion passes with the line taken out -- which is what the teeth
        // proof caught.
        assertTrue(written.contains("# format " + Settings.FORMAT),
                "the file does not say what shape it is in: " + written);
        assertEquals(Settings.FORMAT, Settings.formatOf(written));

        // A file written before this existed says nothing, and is the first
        // format by definition.
        assertEquals(1, Settings.formatOf("theme=dark" + System.lineSeparator()));

        // And a "# format" further down is a comment somebody wrote, not the
        // file speaking: the header is over at the first line that is not one.
        assertEquals(1, Settings.formatOf("# banner" + System.lineSeparator()
                + "theme=dark" + System.lineSeparator()
                + "# format 9" + System.lineSeparator()));
    }
    @Test
    @DisplayName("a costura alcanca a instancia que os outros ja guardaram")
    void theSeamReachesTheInstanceEverybodyKept(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path store) throws java.io.IOException {
        // The seam existed on the CONSTRUCTOR and could not reach the two
        // instances everything uses -- the note on Settings.HOME records that
        // and calls it "the guard was built and never armed". Seven classes hold
        // `static final Settings PREFS = Settings.settings()`, so handing out a
        // new instance would have armed nothing: every one of them would still
        // be writing the reader's real file.
        //
        // So the instance is MOVED, and what this asserts is exactly that: the
        // reference taken BEFORE the redirection writes to the new place.
        Settings kept = Settings.settings();

        Settings.useForTest(store);

        try {
            kept.put("teste.da.costura", "vai para a pasta temporaria");

            assertTrue(java.nio.file.Files.exists(store.resolve("settings.properties")),
                    "the reference taken before the redirection is still writing "
                            + "somewhere else, which is the reader's own file");
            assertTrue(java.nio.file.Files.readString(store.resolve("settings.properties"))
                            .contains("teste.da.costura"),
                    "the file was created and the value did not go into it");
        } finally {
            kept.remove("teste.da.costura");
            Settings.stopUsingTestStore();
        }
    }
    @Test
    @DisplayName("as chaves numeradas voltam em ordem de NUMERO, nao de texto")
    void numberedKeysComeBackInNumericOrder(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path folder) {
        // The keys are numbered -- chart.open.0.series, chart.open.1.series --
        // and sorting them as text puts chart.open.10 before chart.open.2. With
        // ten charts open, the next launch restored them in a different order:
        // the "(2)" suffix changed owner, and with it which window the reader
        // had arranged where.
        // Through keysStartingWith, not through the comparator: what is being
        // held is that the LIST comes back in that order, and a test that sorted
        // the list itself would stay green with the sort call put back to
        // Collections.sort.
        Settings store = Settings.at(folder.resolve("ordem.properties"), "teste");

        for (int at = 10; at >= 1; at--) {
            store.put("chart.open." + at + ".series", "win-1m");
        }

        java.util.List<String> keys = store.keysStartingWith("chart.open.");

        assertEquals("chart.open.1.series", keys.get(0));
        assertEquals("chart.open.2.series", keys.get(1));
        assertEquals("chart.open.10.series", keys.get(keys.size() - 1),
                "the tenth chart came back before the second one: " + keys);

        // And a key with no digits still sorts as plain text, or this would be
        // a comparator that only knows one shape of key.
        java.util.List<String> words = new java.util.ArrayList<>(
                java.util.List.of("theme", "language", "data.zone"));

        words.sort(Settings::byNumberThenText);

        assertEquals(java.util.List.of("data.zone", "language", "theme"), words);
    }
/**
     * Where the two files go when the system will not say where home is.
     *
     * <p>{@code System.getProperty("user.home")} was read with no default, and
     * {@code System} documents that property as USUALLY defined: an embedded
     * JVM, a Windows service with no profile loaded, a container, or plainly
     * {@code -Duser.home=} all leave it null. {@code Path.of(null, ...)} then
     * throws inside a static initialiser, which the JVM turns into an error no
     * catch can recover from -- and the third line of {@code main} touches this
     * class, so the process died with a stack trace that never mentions
     * {@code user.home}.</p>
     *
     * <p>Not reachable through the property itself: the field is read once, when
     * the class is initialised, and by the time any test runs it has been. The
     * rule is checked where it lives.</p>
     */
    @Test
    @DisplayName("sem user.home o programa ainda tem onde guardar")
    void withoutAHomeItStillHasSomewhereToWrite() {
        assertEquals(Path.of(".", ".endeavourneo"), Settings.homeIn(null, null),
                "no home meant no application at all");

        // And the two ordinary answers, or the one above is satisfied by a
        // method that ignores what it is given.
        assertEquals(Path.of("/casa", ".endeavourneo"), Settings.homeIn(null, "/casa"));
        assertEquals(Path.of("/outra", ".endeavourneo"),
                Settings.homeIn("/outra", "/casa"),
                "endeavourneo.home has to win, or the suite writes into the reader's own");
    }
}
