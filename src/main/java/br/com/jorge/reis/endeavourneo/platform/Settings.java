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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Where the application's two kinds of memory live, in two files.
 *
 * <h2>Why two and not one</h2>
 *
 * <p><b>{@link #settings()} is what the reader chose.</b> The theme, the chart
 * options, how many days a replay loads. It is worth backing up, worth copying
 * to another machine, and it means the same thing everywhere.</p>
 *
 * <p><b>{@link #workspace()} is what the application was doing.</b> Which charts
 * were open, how big, on which monitor. It is meaningless on another machine —
 * it names screen coordinates that do not exist there — and it is disposable.</p>
 *
 * <p>The practical payoff is deleting one without the other: a layout that has
 * got into a bad state is fixed by removing the workspace file, and nothing the
 * reader ever chose is lost. Every IDE splits these two for that reason.</p>
 *
 * <h2>Why files and not {@code java.util.prefs}</h2>
 *
 * <p>That is where all of this used to live, and on Windows it is the
 * <b>registry</b>. A setting there cannot be seen, copied, diffed, mailed to
 * somebody or deleted without regedit. These are plain text under the home
 * directory, and every one of those becomes possible.</p>
 *
 * <p>Written on every change rather than at exit. An application that saves on
 * the way out saves nothing when it does not get to leave, and this one is meant
 * to be left running overnight.</p>
 */
public final class Settings {

    /**
     * Where the two files live.
     *
     * <p><b>{@code endeavourneo.home} overrides the home directory</b>, and the
     * suite sets it. The seam on the constructor below has always existed and
     * says why; what it could not reach were the two instances everything
     * actually uses, which were built straight from {@code user.home}. So a test
     * that opened a window wrote the reader's own list of open charts -- their
     * layout, their instruments -- and every run of the suite replaced it. The
     * guard was built and never armed.</p>
     */
    private static final Path HOME = homeIn(System.getProperty("endeavourneo.home"),
            System.getProperty("user.home"));

    /**
     * @param override what {@code endeavourneo.home} says, or null
     * @param home what {@code user.home} says, or null
     * @return the folder the two files live in
     *
     * <p><b>A default for {@code user.home}, and a method so that it can be
     * checked.</b> This read the property with no default, and {@code System}
     * documents it as USUALLY defined -- an embedded JVM, a Windows service with
     * no profile loaded, a container, or plainly {@code -Duser.home=} all leave
     * it null. {@code Path.of(null, ...)} then throws inside a static
     * initialiser, the JVM turns that into an {@code ExceptionInInitializerError}
     * and every later touch of this class into a {@code NoClassDefFoundError} --
     * and {@code Launcher} reaches {@code Theme.remembered()} on the third line
     * of main, so the process died before there was a window, with a stack trace
     * that never mentions {@code user.home}.</p>
     *
     * <p>The working directory instead, which is what {@code SeriesCatalog}
     * already writes for the same read: {@code System.getProperty("user.home",
     * ".")}. Settings kept beside the program are worth more than a program that
     * will not start.</p>
     */
    static Path homeIn(String override, String home) {
        String where = override != null ? override : home;

        return Path.of(where == null ? "." : where, ".endeavourneo");
    }

    private static final Settings SETTINGS = new Settings("settings.properties",
            "Endeavour Neo -- what you chose. Safe to copy to another machine.");

    private static final Settings WORKSPACE = new Settings("workspace.properties",
            "Endeavour Neo -- what the application was doing. Delete this to reset the layout.");

    /**
     * Where this one is written.
     *
     * <p>Not final, so {@link #useForTest} can move BOTH instances somewhere
     * temporary. Swapping the instances instead would not have worked: seven
     * classes hold {@code static final Settings PREFS = Settings.settings()},
     * so a new instance would reach none of them -- a guard that looks armed
     * and is not, which is the exact trap the note on {@link #HOME} already
     * records this file falling into once.</p>
     */
    private Path file;

    private final String banner;

    private final Properties values = new Properties();

    private Settings(String name, String banner) {
        this(HOME.resolve(name), banner);
    }

    /**
     * @param file where to keep it
     * @param banner the line written at the top
     *
     * <p>Package-visible so a test can point one at a temporary file. Without
     * this seam the only way to test saving is to write into the home directory
     * of whoever runs the suite, which is not a test, it is a side effect.</p>
     */
    Settings(Path file, String banner) {
        this.file = file;
        this.banner = banner;

        load();
    }

    /**
     * @param file where to keep it
     * @param banner the line written at the top
     * @return a settings file of its own
     *
     * <p>The constructor above is the seam and it is package-visible, which is
     * enough for a test in this package and not for one anywhere else. A test of
     * the launcher needs the same seam from the root package, and the
     * alternative -- writing into the home directory of whoever runs the suite
     * -- is not a test, it is a side effect.</p>
     */
    public static Settings at(Path file, String banner) {
        return new Settings(file, banner);
    }

    /**
     * Points both files at a folder of the test's own.
     *
     * <p>The two instances are MOVED rather than replaced, because seven classes
     * hold on to them in {@code static final} fields; a replacement would leave
     * every one of those still writing the reader's real files.</p>
     *
     * <p>What this is for: {@code Language.remember()} writes through {@link
     * #settings}, and the property that redirects the home directory is set in
     * one place only -- the surefire plugin. Run the suite the way the house
     * documents it, with javac and a runner, and that property is absent and
     * the test writes the reader's actual settings. The seam existed on the
     * constructor and could not reach the two instances everything uses.</p>
     *
     * @param folder somewhere temporary
     */
    public static void useForTest(Path folder) {
        SETTINGS.moveTo(folder.resolve("settings.properties"));
        WORKSPACE.moveTo(folder.resolve("workspace.properties"));
    }

    /**
     * Puts both files back under the home directory.
     *
     * <p>Public for the same reason {@link #at} is: the tests that need this
     * most are not in this package. A window test opens a chart, and opening a
     * chart writes the workspace -- so without a seam it can reach, the suite
     * rewrites the reader's own list of open charts on every run.</p>
     */
    public static void stopUsingTestStore() {
        SETTINGS.moveTo(HOME.resolve("settings.properties"));
        WORKSPACE.moveTo(HOME.resolve("workspace.properties"));
    }

    private void moveTo(Path other) {
        this.file = other;

        values.clear();
        load();
    }

    /** @return the reader's choices: theme, chart and replay options */
    public static Settings settings() {
        return SETTINGS;
    }

    /** @return the application's state: which windows were open and where */
    public static Settings workspace() {
        return WORKSPACE;
    }

    /** @return where the two files are, for a message that tells the reader */
    public static Path directory() {
        return HOME;
    }

    /**
     * Whether this file was there and would not be read.
     *
     * <p>What cannot be read must not be overwritten. A failed read used to
     * clear the map, and the next {@code put} then truncated the file and wrote
     * the empty map over it -- on an application that writes at startup, so one
     * unreadable byte cost the reader every setting and the whole workspace,
     * for good and with nothing said.</p>
     */
    private boolean unreadable;

    /**
     * Reads the file, in the encoding it was written in.
     *
     * <p><b>UTF-8, said out loud.</b> The obvious {@code load(InputStream)}
     * decodes ISO-8859-1 — that is its contract, not an accident — while the
     * writing side asks for UTF-8. A window called <i>Sem título</i> came back
     * as <i>Sem tÃ­tulo</i>: each accented letter arrived as the two characters
     * its UTF-8 bytes spell in the other encoding. The wrong text was then
     * written back out and read wrong again, so the damage compounded on every
     * launch; the file that showed it had been through four rounds.</p>
     *
     * <p>Bytes that are not UTF-8 are replaced rather than thrown, so a file
     * damaged some other way still opens. Losing one character is a smaller
     * harm than losing the whole layout.</p>
     */
    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        try (InputStream in = Files.newInputStream(file);
                Reader reader = new InputStreamReader(in, decoder)) {
            values.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            // ILLEGALARGUMENT AS WELL, and it is the one that mattered.
            // Properties.load throws it for a malformed unicode escape -- a
            // backslash-u with fewer than four hex digits after it, which this
            // comment may not spell out because the compiler reads escapes even
            // in comments. That is not an IOException, so it went past this
            // catch, out of the static initialiser, and the program would not
            // open at all -- against the sentence right below, which promises
            // the opposite.
            //
            // Unreadable settings are the same as none: the application opens
            // with its defaults rather than refusing to open at all. Losing a
            // theme is a smaller harm than losing the program.
            //
            // NOT CLEARED, and that is the other half. Clearing here and writing
            // on the next put truncated the file: one failed read and the
            // reader's settings and workspace were gone for good, on an
            // application that writes at startup. Refusing to save is the safe
            // side of this trade -- what cannot be read must not be overwritten.
            unreadable = true;

            return;
        }

        repair();
    }

    /**
     * Undoes the damage in a file written before the encoding was fixed.
     *
     * <p>Reading correctly from now on protects what is written from now on. It
     * does nothing for the title already on the reader's disk, which would stay
     * broken for good — so the damage is undone on the way in, as many rounds
     * as it was done.</p>
     *
     * <p>The test for damage is not a guess: text that came from this defect is
     * made only of characters that fit in a byte, and those bytes are valid
     * UTF-8. Correct Portuguese is not — <i>ção</i> as ISO-8859-1 bytes is a
     * malformed UTF-8 sequence, so the undoing stops and the text is left
     * alone. That is asserted in the tests rather than assumed here.</p>
     */
    private void repair() {
        Map<String, String> healed = new LinkedHashMap<>();
        boolean changed = false;

        for (String key : values.stringPropertyNames()) {
            String value = values.getProperty(key);
            String healedKey = repair(key);
            String healedValue = repair(value);

            changed |= !healedKey.equals(key) || !healedValue.equals(value);
            healed.put(healedKey, healedValue);
        }

        if (!changed) {
            return;
        }

        values.clear();
        values.putAll(healed);
    }

    /** How many rounds of damage to undo before deciding the text is just odd. */
    private static final int ROUNDS = 8;

    private static String repair(String text) {
        String current = text;

        for (int round = 0; round < ROUNDS; round++) {
            String once = undo(current);

            if (once == null) {
                return current;
            }

            current = once;
        }

        return current;
    }

    /** @return the text with one round of damage undone, or null if there was none */
    private static String undo(String text) {
        boolean accented = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c > 0xFF) {
                // A character that never fitted in a byte cannot have come from
                // one being misread.
                return null;
            }

            accented |= c > 0x7F;
        }

        if (!accented) {
            return null;
        }

        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        try {
            return strict.decode(ByteBuffer.wrap(
                    text.getBytes(StandardCharsets.ISO_8859_1))).toString();
        } catch (CharacterCodingException e) {
            // Not valid UTF-8, so it was never UTF-8 read wrongly. Healthy text.
            return null;
        }
    }

    /**
     * Which shape this file is in.
     *
     * <p><b>There was no version, and it had already cost twice.</b> Two format
     * changes were resolved by guessing at the content instead of by reading a
     * number: the encoding repair above -- seventy lines that undo up to eight
     * rounds of damage and decide by examining the bytes whether the text is
     * broken -- and the change of the default series name, which leaves any
     * older workspace pointing at a series that still exists and is no longer
     * the source.</p>
     *
     * <p>Written as a comment rather than as a key so it cannot be mistaken for
     * a preference, read back by {@link #formatOf}, and one is what every file
     * without it is taken to be. Nothing migrates yet, and that is the point:
     * the next change has somewhere to look.</p>
     */
    static final int FORMAT = 1;

    /**
     * @param text the whole file as read
     * @return the format it declares, or 1 when it declares none
     */
    static int formatOf(String text) {
        for (String line : text.split("\r?\n")) {
            String trimmed = line.trim();

            if (!trimmed.startsWith("#")) {
                // Past the header. A "# format" further down is a comment
                // somebody wrote, not the file saying what it is.
                break;
            }

            if (trimmed.startsWith("# format ")) {
                try {
                    return Integer.parseInt(trimmed.substring("# format ".length()).trim());
                } catch (NumberFormatException e) {
                    return FORMAT;
                }
            }
        }

        return 1;
    }

    /**
     * Writes the file, one key per line, sorted.
     *
     * <p>Written by hand rather than with {@link Properties#store}, which writes
     * in hash order — the same settings come out in a different order every time
     * and a diff is useless.</p>
     *
     * <p>The obvious way to sort it is a {@code Properties} subclass whose
     * {@code entrySet} returns a sorted copy, and <b>that does not work</b>:
     * building the copy calls {@code putAll}, which calls {@code entrySet},
     * which builds another copy. It recursed until the stack ran out, on the
     * very first launch. Hence the long way round.</p>
     */
    private void save() {
        if (unreadable) {
            // Refusing to save is the safe side of the trade. The reader keeps
            // whatever is on disk, which is more than they would keep if this
            // wrote over it.
            return;
        }

        if (holding > 0) {
            // Inside a batch. One write at the end of it instead of one per key
            // -- see hold().
            wanted = true;

            return;
        }

        List<String> keys = new ArrayList<>(values.stringPropertyNames());

        Collections.sort(keys);

        // WRITTEN BESIDE IT AND MOVED OVER, never truncated in place. This
        // opened the real file with TRUNCATE and wrote it line by line, and it
        // is called on EVERY change on purpose -- so the window in which the
        // file on disk is half a file was as wide as the number of writes, and a
        // machine that stops in the middle of one leaves a cut file. A cut file
        // is what the two failures above this method are about.
        Path working = file.resolveSibling(file.getFileName() + ".parcial");

        try {
            Files.createDirectories(file.getParent());

            try (BufferedWriter out = Files.newBufferedWriter(working, StandardCharsets.UTF_8)) {
                out.write("# " + banner);
                out.newLine();
                out.write("# format " + FORMAT);
                out.newLine();

                // NO TIMESTAMP. The sorting exists so that a change is one line
                // in a diff -- the javadoc above says so -- and a clock on line
                // two made EVERY write a diff. The launcher writes on every
                // start even when nothing changed, so the file moved every day
                // with no preference having moved at all. And it was a local
                // time with no zone, ambiguous in the hour that repeats at the
                // end of summer time, in a program that treats zones as a
                // serious subject.
                for (String key : keys) {
                    out.write(escape(key, true));
                    out.write('=');
                    out.write(escape(values.getProperty(key), false));
                    out.newLine();
                }
            }

            move(working, file);

            writes++;
        } catch (IOException e) {
            // SAID, once. This wrote "_unsaved=true" into the map and nothing
            // ever read that key -- grep found the one line that writes it --
            // and nothing ever removed it either, so one transient failure left
            // it in the reader's file for ever, saying something that had
            // stopped being true.
            //
            // A dialog would be wrong, and the old comment was right about that:
            // the reader is mid-click and a box about a settings file would
            // interrupt what they were actually doing. Silence is not the only
            // other option -- there is a console in the main window, and
            // standard output is captured into it.
            //
            // Once per session, because the failure that matters is the folder
            // being gone or full, and that repeats on every keystroke.
            if (!complained) {
                complained = true;

                System.err.println(file + ": the settings could not be written (" + e + ")");
            }
        }
    }

    /** Whether the failure to write has already been reported this session. */
    private transient boolean complained;

    /** How many times the file has actually been written. */
    private transient int writes;

    /**
     * @return how many times this has written the file
     *
     * <p>For the test that says a batch writes ONCE. The modification time
     * cannot answer it -- ten writes in a row move it exactly as far as one
     * does -- and "how many times the file was written" is the whole property.</p>
     */
    int writes() {
        return writes;
    }

    /** How many batches are open; see {@link #hold}. */
    private transient int holding;

    /** Whether anything asked to be saved while a batch was open. */
    private transient boolean wanted;

    /**
     * Runs that, writing the file ONCE at the end instead of once per key.
     *
     * <p>There was no batch, and the callers that write many keys at a time are
     * not unusual: storing the segments of one series is three writes per
     * segment plus one, remembering the open charts is two per chart plus one,
     * and moving a floating window is four in a row. All of that on the
     * interface thread, which is the thread that may not do file work.</p>
     *
     * <p>Nested holds are counted, so a caller inside another caller's batch
     * does not end it early. Nothing is written if nothing asked to be.</p>
     */
    public void hold(Runnable work) {
        holding++;

        try {
            work.run();
        } finally {
            holding--;

            if (holding == 0 && wanted) {
                wanted = false;

                save();
            }
        }
    }

    /** @param from the file just written; @param to where it belongs */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Some network volumes refuse the atomic form. The replace is still
            // one operation as far as this program is concerned, and it is
            // better than writing over the file in place.
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * @param key whether this is the left of the equals sign
     * @return the text with everything the format would misread escaped
     *
     * <p>Newlines here are not theoretical: a chart layout is stored as one line
     * per indicator, inside a single value.</p>
     */
    private static String escape(String text, boolean key) {
        StringBuilder out = new StringBuilder(text.length() + 8);

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\\') {
                out.append("\\\\");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c == '=' || c == ':' || c == '#' || c == '!') {
                out.append('\\').append(c);
            } else if (c == ' ' && (key || i == 0)) {
                // A space is only special in a key, or leading a value: in the
                // middle of a value it is just a space, and escaping it there
                // would make the file unpleasant to read for no gain.
                out.append("\\ ");
            } else {
                out.append(c);
            }
        }

        return out.toString();
    }

    // ------------------------------------------------------------ the values

    public String get(String key, String fallback) {
        return values.getProperty(key, fallback);
    }

    public void put(String key, String value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.setProperty(key, value);
        }

        save();
    }

    public boolean getBoolean(String key, boolean fallback) {
        String value = values.getProperty(key);

        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    public void putBoolean(String key, boolean value) {
        put(key, String.valueOf(value));
    }

    public int getInt(String key, int fallback) {
        try {
            String value = values.getProperty(key);

            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            // A hand-edited file with a typo in it. The default is a better
            // answer than an exception on the way to the first window.
            return fallback;
        }
    }

    public void putInt(String key, int value) {
        put(key, String.valueOf(value));
    }

    public void remove(String key) {
        values.remove(key);
        save();
    }

    /**
     * @param prefix what the key starts with
     * @return every key that starts with it, in an order a NUMBER understands
     *
     * <p><b>Not plain alphabetical, and it used to be.</b> These keys are
     * numbered -- {@code chart.open.0.series}, {@code chart.open.1.series} --
     * and sorting them as text puts {@code chart.open.10} before {@code
     * chart.open.2}. With ten charts open, the next launch restored them in a
     * different order: the "(2)" suffix changed owner, and with it which
     * window the reader had arranged where.</p>
     *
     * <p>Runs of digits compare as numbers and everything else as text, so a
     * key with no numbers in it sorts exactly as it did.</p>
     */
    public List<String> keysStartingWith(String prefix) {
        List<String> found = new ArrayList<>();

        for (String key : values.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                found.add(key);
            }
        }

        found.sort(Settings::byNumberThenText);

        return found;
    }

    /**
     * @return the two compared with runs of digits read as numbers
     *
     * <p>Package-visible so the ordering can be checked directly, without
     * building a settings file of ten charts to see it.</p>
     */
    static int byNumberThenText(String left, String right) {
        int a = 0;
        int b = 0;

        while (a < left.length() && b < right.length()) {
            char one = left.charAt(a);
            char other = right.charAt(b);

            if (Character.isDigit(one) && Character.isDigit(other)) {
                int endA = a;
                int endB = b;

                while (endA < left.length() && Character.isDigit(left.charAt(endA))) {
                    endA++;
                }

                while (endB < right.length() && Character.isDigit(right.charAt(endB))) {
                    endB++;
                }

                // Compared as text once the leading zeros are gone, so a run of
                // any length works and nothing has to be parsed -- a key with
                // forty digits in it is somebody else's problem, not an
                // exception here.
                String runA = left.substring(a, endA).replaceFirst("^0+(?=.)", "");
                String runB = right.substring(b, endB).replaceFirst("^0+(?=.)", "");

                if (runA.length() != runB.length()) {
                    return runA.length() - runB.length();
                }

                int order = runA.compareTo(runB);

                if (order != 0) {
                    return order;
                }

                a = endA;
                b = endB;

                continue;
            }

            if (one != other) {
                return one - other;
            }

            a++;
            b++;
        }

        return (left.length() - a) - (right.length() - b);
    }

    /** Drops every key starting with that prefix, writing once at the end. */
    public void removeStartingWith(String prefix) {
        boolean changed = values.keySet().removeIf(
                key -> key instanceof String name && name.startsWith(prefix));

        if (changed) {
            save();
        }
    }
}
