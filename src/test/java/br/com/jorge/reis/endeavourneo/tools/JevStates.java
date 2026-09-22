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
package br.com.jorge.reis.endeavourneo.tools;

import br.com.jorge.reis.endeavourneo.domain.indicator.Vwap;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * Writes one market state per decision moment, for an outside model to read.
 *
 * <p>The first of the three steps that let Jev — or anything else — decide the
 * side of a trade without a network call ever happening inside a backtest:
 *
 * <ol>
 *   <li><b>this</b>: walk the series and write {@code estados.jsonl}, one JSON
 *       object per line, each describing the market as it stood at one bar's
 *       close;</li>
 *   <li>a script asks the model about each line and writes {@code decisoes.csv};</li>
 *   <li>{@code JevAdvice} reads that file and trades it, offline and
 *       repeatably.</li>
 * </ol>
 *
 * <h2>Every field is known at that bar's close, and nothing else is</h2>
 *
 * <p>This is the whole discipline of the file and the only thing that can make
 * the measurement worthless if it slips. Every number below is computed from
 * bars up to and including the one being described, and the model is asked
 * about what happens NEXT. A single field that peeks — the day's high when the
 * day is not over, a range that has not formed yet — turns a good result into
 * an artefact, and the result will look excellent while it does.
 *
 * <h2>Why the state is words and numbers, not the 22 standardised attributes</h2>
 *
 * <p>The fade model's network reads twenty-two standardised numbers, which is
 * right for a network fitted to them. This is asked of a general model that has
 * never seen this market's statistics, so it is given what a person watching the
 * screen would have: the time, where price sits in the day, what it has done
 * over the last few minutes, how far it is from the average, how wide the day
 * is. Measured once against a hand-made sample, the standardised form produced a
 * nine-way answer with confidence 0,23 against the 0,11 of chance — barely
 * discriminating — which is what asking an unfitted model to read z-scores
 * should look like.
 *
 * <h2>Frozen on purpose</h2>
 *
 * <p>The fields below were chosen once, before any result was looked at. Adding
 * a field because the answers improved is fitting the prompt to the sample, and
 * it is the same disease as fitting a parameter to it — with the aggravation
 * that nobody counts the attempts. Change this only with a reason stated in
 * advance, and measure again from scratch.
 *
 * <h2>Run it like this</h2>
 *
 * <pre>{@code
 * mvn -o test-compile
 * java -cp "target/classes;target/test-classes" \
 *      br.com.jorge.reis.endeavourneo.tools.JevStates \
 *      <serie> <minutos entre decisoes> <arquivo de saida>
 * }</pre>
 */
public final class JevStates {

    /** How often a decision is asked for, in minutes of clock. */
    public static final int EVERY = 15;

    /** No decision before this: the day has not shown anything yet. */
    private static final LocalTime FROM = LocalTime.of(9, 30);

    /** Nor after it: what is left of the day cannot reach a target. */
    private static final LocalTime UNTIL = LocalTime.of(17, 0);

    private JevStates() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    public static void main(String[] args) throws IOException {
        String name = args.length > 0 ? args[0] : SeriesCatalog.defaultName();
        int every = args.length > 1 ? Integer.parseInt(args[1]) : EVERY;
        Path out = Path.of(args.length > 2 ? args[2] : "estados.jsonl");

        ZoneId zone = Timeframe.defaultZone();
        PriceSeries bars = SeriesCatalog.open(name).orElse(null);

        if (bars == null) {
            System.out.println("serie nao encontrada: " + name);

            return;
        }

        System.out.println("serie " + name + ": " + bars.size() + " barras");

        int written = write(bars, zone, every, out);

        System.out.println("gravou " + written + " estados em " + out.toAbsolutePath());
        System.out.println("um pedido por estado, ~900 tokens de entrada cada");
        System.out.printf(Locale.ROOT, "custo estimado a US$42/bilhao: US$ %.2f%n",
                written * 900.0 * 42.0 / 1e9);
    }

    /** @return how many states were written */
    static int write(PriceSeries bars, ZoneId zone, int every, Path out) throws IOException {
        Vwap.Lines vwap = Vwap.standard().over(bars, zone);

        int written = 0;

        try (BufferedWriter file = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            int dayStart = 0;
            LocalDate today = dateOf(bars, 0, zone);

            double dayHigh = Double.NEGATIVE_INFINITY;
            double dayLow = Double.POSITIVE_INFINITY;

            for (int bar = 0; bar < bars.size(); bar++) {
                LocalDate now = dateOf(bars, bar, zone);

                if (!now.equals(today)) {
                    today = now;
                    dayStart = bar;
                    dayHigh = Double.NEGATIVE_INFINITY;
                    dayLow = Double.POSITIVE_INFINITY;
                }

                // THE DAY'S RANGE SO FAR, and only so far. Folding the whole
                // session first and reading its high here would be the peek
                // this file exists to avoid, and it would not look like one.
                dayHigh = Math.max(dayHigh, bars.highAt(bar));
                dayLow = Math.min(dayLow, bars.lowAt(bar));

                LocalTime clock = timeOf(bars, bar, zone);

                if (clock.isBefore(FROM) || clock.isAfter(UNTIL)) {
                    continue;
                }

                if ((bar - dayStart) % every != 0) {
                    continue;
                }

                String line = describe(bars, vwap, bar, dayStart, dayHigh, dayLow, clock);

                if (line == null) {
                    continue;
                }

                file.write(line);
                file.newLine();

                written++;
            }
        }

        return written;
    }

    /**
     * One state, as a JSON object on one line.
     *
     * <p>Hand-written rather than produced by a library, because this project
     * carries no runtime dependency and the shape here is six numbers and two
     * strings. Every value is a plain number or a quoted word, so nothing needs
     * escaping — and if that ever stops being true, this is where it breaks
     * loudly rather than quietly producing broken JSON.</p>
     */
    private static String describe(PriceSeries bars, Vwap.Lines vwap, int bar,
                                   int dayStart, double dayHigh, double dayLow,
                                   LocalTime clock) {

        if (bar - 60 < 0) {
            return null;
        }

        double close = bars.closeAt(bar);
        double open = bars.openAt(dayStart);
        double width = dayHigh - dayLow;

        if (!(width > 0)) {
            return null;
        }

        double spread = vwap.deviation()[bar];
        double away = spread > 0 ? (close - vwap.vwap()[bar]) / spread : 0;

        double moved = 0;

        for (int back = 0; back < 15; back++) {
            moved += Math.abs(bars.closeAt(bar - back) - bars.closeAt(bar - back - 1));
        }

        return String.format(Locale.ROOT,
                "{\"tempo\":%d,\"hora\":\"%s\",\"preco\":%.0f,"
                        + "\"desde_a_abertura\":%.0f,\"na_faixa_do_dia\":%.3f,"
                        + "\"largura_do_dia\":%.0f,"
                        + "\"ultimos_5min\":%.0f,\"ultimos_15min\":%.0f,\"ultimos_60min\":%.0f,"
                        + "\"agitacao_15min\":%.1f,\"desvios_da_vwap\":%.2f,"
                        + "\"minutos_para_o_fim\":%d}",
                bars.timeAt(bar), clock,
                close,
                close - open,
                (close - dayLow) / width,
                width,
                close - bars.closeAt(bar - 5),
                close - bars.closeAt(bar - 15),
                close - bars.closeAt(bar - 60),
                moved / 15,
                away,
                minutesTo(clock));
    }

    private static int minutesTo(LocalTime clock) {
        return (int) java.time.Duration.between(clock, LocalTime.of(17, 55)).toMinutes();
    }

    private static LocalDate dateOf(PriceSeries bars, int bar, ZoneId zone) {
        return Instant.ofEpochMilli(bars.timeAt(bar)).atZone(zone).toLocalDate();
    }

    private static LocalTime timeOf(PriceSeries bars, int bar, ZoneId zone) {
        ZonedDateTime when = Instant.ofEpochMilli(bars.timeAt(bar)).atZone(zone);

        return LocalTime.of(when.getHour(), when.getMinute());
    }
}
