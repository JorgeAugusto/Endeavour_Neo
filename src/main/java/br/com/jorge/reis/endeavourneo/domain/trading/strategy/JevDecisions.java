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
package br.com.jorge.reis.endeavourneo.domain.trading.strategy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * What an outside model already decided, read back from a file.
 *
 * <p>One row per moment a decision was asked for, keyed by the epoch millis of
 * the bar that closed at that moment. Nothing here knows what produced the
 * rows — a network model, a spreadsheet, a person — and that is the point:
 * whatever produced them did so ONCE, and the backtest reads the same answers
 * every time it runs.
 *
 * <h2>Why a file, and not a call</h2>
 *
 * <p>Two reasons, and the second is the one that decides it.
 *
 * <p>The first is size. A year of one-minute bars is a hundred and forty
 * thousand of them and the whole base is over two million; a network call per
 * bar is not slow, it is impossible.
 *
 * <p>The second is that <b>a backtest that calls a probabilistic model is not a
 * measurement</b>. Run it twice and it answers twice, and the difference
 * between the two runs is not the strategy — it is the model's sampling. Every
 * number this project publishes rests on the run being repeatable, so the
 * model's answers are frozen into a file and the file is the input. What the
 * backtest measures is then a fixed set of decisions, which is a thing that can
 * be argued with.
 *
 * <h2>The format</h2>
 *
 * <pre>{@code
 * # anything after a hash is a comment, and the header belongs there
 * tempo;lado;alta;confianca
 * 1736164800000;1;0,62;0,41
 * }</pre>
 *
 * <ul>
 *   <li>{@code tempo} — epoch millis of the decision bar, matched exactly;</li>
 *   <li>{@code lado} — {@code 1} buy, {@code -1} sell, {@code 0} stay out;</li>
 *   <li>{@code alta} — the model's probability that price rises, nought to one;</li>
 *   <li>{@code confianca} — how sure the model was of its own choice.</li>
 * </ul>
 *
 * <p>Semicolons and comma decimals, like every other file this project writes,
 * so a spreadsheet in this locale opens it without a dialog.
 *
 * <h2>A row that does not match a bar is a defect, not a rounding</h2>
 *
 * <p>Lookup is by EXACT timestamp. A decision keyed to a moment that is not a
 * bar of the series being run cannot be used at all — and silently skipping
 * those would hide the commonest way this goes wrong, which is decisions
 * exported from one scale and run on another. {@link #matched(long[])} counts
 * them so a caller can refuse a file that mostly misses.
 */
public final class JevDecisions {

    /** What the file's columns are called. */
    public static final String COLUMNS = "tempo;lado;alta;confianca";

    /** One decision, as the file carries it. */
    public record Decision(int side, double up, double confidence) {

        public Decision {
            side = Integer.signum(side);
        }

        /** Nothing decided: neither side, and no opinion. */
        public static Decision none() {
            return new Decision(0, 0.5, 0);
        }
    }

    private final Map<Long, Decision> byTime;

    private JevDecisions(Map<Long, Decision> byTime) {
        this.byTime = byTime;
    }

    /** @return a set with nothing in it, which trades nothing */
    public static JevDecisions empty() {
        return new JevDecisions(Map.of());
    }

    /**
     * @param file the exported decisions
     * @return them, indexed by the moment they belong to
     * @throws IOException if the file cannot be read
     */
    public static JevDecisions read(Path file) throws IOException {
        if (file == null || !Files.isReadable(file)) {
            return empty();
        }

        try (Reader source = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(source);
        }
    }

    /** @see #read(Path) */
    public static JevDecisions read(Reader source) throws IOException {
        Map<Long, Decision> made = new HashMap<>();

        try (BufferedReader lines = new BufferedReader(source)) {
            String line;

            while ((line = lines.readLine()) != null) {
                String clean = line.trim();

                if (clean.isEmpty() || clean.startsWith("#")) {
                    continue;
                }

                String[] parts = clean.split(";");

                if (parts.length < 2 || parts[0].equalsIgnoreCase("tempo")) {
                    // The header row, or a line too short to be one. Skipped
                    // rather than refused: a file is allowed to carry its own
                    // column names, and every writer here puts them in.
                    continue;
                }

                try {
                    made.put(Long.parseLong(parts[0].trim()),
                            new Decision(Integer.parseInt(parts[1].trim()),
                                    number(parts, 2, 0.5), number(parts, 3, 0)));
                } catch (NumberFormatException e) {
                    // A row that is not numbers is not a decision. Left out,
                    // and it will show up in the matched count rather than as
                    // a trade nobody can explain.
                    continue;
                }
            }
        }

        return new JevDecisions(made);
    }

    private static double number(String[] parts, int at, double fallback) {
        if (at >= parts.length) {
            return fallback;
        }

        String text = parts[at].trim().replace(',', '.');

        if (text.isEmpty()) {
            return fallback;
        }

        return Double.parseDouble(text);
    }

    /**
     * @param time the epoch millis of a bar
     * @return what was decided there, or {@link Decision#none()}
     */
    public Decision at(long time) {
        return byTime.getOrDefault(time, Decision.none());
    }

    /** @return how many decisions the file carried */
    public int size() {
        return byTime.size();
    }

    /**
     * @param times every bar's timestamp, in the series about to be run
     * @return how many decisions land on one of them
     *
     * <p>The number to look at before trusting a run. A file exported from
     * fifteen-minute bars and run against five-minute ones matches nothing, and
     * the strategy would simply never trade — which reads like a strategy with
     * no signals rather than a file that does not fit.</p>
     */
    public int matched(long[] times) {
        int many = 0;

        for (long each : times) {
            if (byTime.containsKey(each)) {
                many++;
            }
        }

        return many;
    }
}
