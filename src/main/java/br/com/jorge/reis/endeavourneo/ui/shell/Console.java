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
package br.com.jorge.reis.endeavourneo.ui.shell;

import br.com.jorge.reis.endeavourneo.platform.Appearance;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;

/**
 * The bottom console, with standard output redirected into it.
 *
 * <p>It is the view most missed when absent. In a program that runs long tasks,
 * the console is where you find out what happened without opening a debugger.</p>
 *
 * <p><b>The line cap is not a detail.</b> A {@code JTextArea} that grows without
 * bound eats memory and, long before that, becomes slow to scroll: text layout
 * is recomputed over the whole length. Dropping the oldest lines past a ceiling
 * is what separates a usable console from one that freezes the application after
 * an hour of logging.</p>
 */
public final class Console extends JScrollPane {

    private static final long serialVersionUID = 1L;

    /** Past this, the oldest lines are discarded. */
    private static final int LINE_CAP = 5_000;

    /** How many lines to drop at once, so trimming is not done on every write. */
    private static final int TRIM_BLOCK = 1_000;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * The console standard output is going to, or null while it goes nowhere.
     *
     * <p><b>Static, and it has to be.</b> {@code System.out} is one field for
     * the whole machine, so whoever is listening to it is one answer for the
     * whole machine too. Holding it here rather than inside the stream is what
     * lets a second console take the output over -- and, just as much, what
     * lets the first one be let go of. The stream used to capture the console
     * it was built for, and {@code System.out} is a root of the JVM: that one
     * reference held the console, its text area, every parent up to the window,
     * and every chart open in it, for as long as the program ran.</p>
     */
    private static volatile Console listening;

    /** What standard output was before the first capture, so it can be given back. */
    private static PrintStream terminalOut;

    private static PrintStream terminalErr;

    private final JTextArea text = new JTextArea();

    public Console() {
        text.setEditable(false);
        text.setFont(Appearance.monospaced(12));
        text.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        setViewportView(text);
        setBorder(BorderFactory.createEmptyBorder());
    }

    /** @param line what to write; gets a timestamp and a line break */
    public void write(String line) {
        onEdt(() -> {
            text.append(LocalTime.now().format(CLOCK) + "  " + line + System.lineSeparator());
            trim();
            text.setCaretPosition(text.getDocument().getLength());
        });
    }

    public void clear() {
        onEdt(() -> text.setText(""));
    }

    /**
     * Sends {@code System.out} and {@code System.err} to this console.
     *
     * <p>Calling this has a consequence: from then on the entire application's
     * standard output goes through here, libraries included. That is what you
     * want in a windowed application — standard output has nowhere else to go —
     * but it gets in the way when running the same classes from a terminal.
     * Hence a method, rather than the constructor.</p>
     */
    public void captureStandardOutput() {
        if (listening == null) {
            terminalOut = System.out;
            terminalErr = System.err;

            PrintStream stream = new PrintStream(new OutputStream() {

                private final StringBuilder pending = new StringBuilder();

                @Override
                public void write(int b) {
                    char c = (char) b;

                    if (c == '\n') {
                        Console target = listening;

                        if (target != null) {
                            target.write(pending.toString());
                        }

                        pending.setLength(0);
                    } else if (c != '\r') {
                        pending.append(c);
                    }
                }
            }, true, StandardCharsets.UTF_8);

            System.setOut(stream);
            System.setErr(stream);
        }

        listening = this;
    }

    /**
     * Sends standard output here IF it is already going to a console.
     *
     * <p>For the window built to replace another one. Changing language throws
     * the whole window away and makes a new one, and the redirection was set up
     * once at start-up and never again: from the first change onwards every
     * {@code printStackTrace} and every {@code System.err.println} in the
     * program was written into the text area of a window that had been
     * disposed. Silently, and by definition -- in a windowed application
     * standard output has nowhere else to go, so nothing was left to notice it
     * with. The console on screen went on showing what {@code console.write}
     * put there directly, which made the loss look partial and harder to
     * believe.</p>
     *
     * <p><b>Only if.</b> Capturing here instead would arm the redirection in
     * places that deliberately left it off -- the tests, and anything run from
     * a terminal. A window that replaces another one inherits what that one
     * had; it does not decide.</p>
     */
    public void takeOverStandardOutput() {
        if (listening != null) {
            listening = this;
        }
    }

    /**
     * Gives standard output back to where it was before the first capture.
     *
     * <p>Nothing in the application calls this -- the console lives as long as
     * the program does. It exists so a test can capture and then put the
     * machine back the way it found it, which is the only way a test may touch
     * a field as global as {@code System.out}.</p>
     */
    public static void releaseStandardOutput() {
        if (terminalOut != null) {
            System.setOut(terminalOut);
            System.setErr(terminalErr);

            terminalOut = null;
            terminalErr = null;
        }

        listening = null;
    }

    /** @return every line on screen; for the tests that ask where the output landed */
    String contents() {
        return text.getText();
    }

    /** Drops the beginning once the text grows past the cap. */
    private void trim() {
        if (text.getLineCount() <= LINE_CAP) {
            return;
        }

        try {
            text.replaceRange("", 0, text.getLineEndOffset(TRIM_BLOCK));
        } catch (BadLocationException e) {
            text.setText("");
        }
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }
}
