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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    private static final Path HOME = Path.of(System.getProperty("user.home"), ".endeavourneo");

    private static final Settings SETTINGS = new Settings("settings.properties",
            "Endeavour Neo -- what you chose. Safe to copy to another machine.");

    private static final Settings WORKSPACE = new Settings("workspace.properties",
            "Endeavour Neo -- what the application was doing. Delete this to reset the layout.");

    private final Path file;

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

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }

        try (InputStream in = Files.newInputStream(file)) {
            values.load(in);
        } catch (IOException e) {
            // Unreadable settings are the same as none: the application opens
            // with its defaults rather than refusing to open at all. Losing a
            // theme is a smaller harm than losing the program.
            values.clear();
        }
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
        List<String> keys = new ArrayList<>(values.stringPropertyNames());

        Collections.sort(keys);

        try {
            Files.createDirectories(file.getParent());

            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write("# " + banner);
                out.newLine();
                out.write("# " + LocalDateTime.now());
                out.newLine();

                for (String key : keys) {
                    out.write(escape(key, true));
                    out.write('=');
                    out.write(escape(values.getProperty(key), false));
                    out.newLine();
                }
            }
        } catch (IOException e) {
            // Nothing useful to do and nowhere useful to say it: the reader is
            // mid-click, and a dialog about a settings file would interrupt the
            // thing they were actually doing.
            values.putIfAbsent("_unsaved", "true");
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

    /** @return every key starting with that prefix, sorted */
    public List<String> keysStartingWith(String prefix) {
        List<String> found = new ArrayList<>();

        for (String key : values.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                found.add(key);
            }
        }

        Collections.sort(found);

        return found;
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
