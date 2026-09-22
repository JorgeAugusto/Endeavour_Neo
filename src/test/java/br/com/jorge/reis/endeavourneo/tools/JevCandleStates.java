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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The same states as {@link JevStates}, plus the candles that led to them.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The first design handed the model eleven scalars — price, where it sits in
 * the day, what it did over the last five, fifteen and sixty minutes — and no
 * SHAPE. Scalars cannot carry shape: sixty points of gain in one jump and sixty
 * dripping in over an hour are the same number, and they are not the same
 * chart. A model that reads language is exactly the kind that might use the
 * difference, and the first design gave it no way to.
 *
 * <p>Measured on 5.172 decisions of the scalar design: 40,4% of them reached
 * the target, against the 40,0% a purely random entry gets with a target of 300
 * against a stop of 200. Four tenths of a percentage point. That is the number
 * this design has to beat, and it is written here so that nobody has to
 * remember it.
 *
 * <h2>What changed, and what deliberately did NOT</h2>
 *
 * <p><b>Only the candles are new.</b> The decision moments, every field of the
 * context and the walk itself come out of {@link JevStates} — literally, through
 * {@link JevStates.Extras}, not by being copied. That is the whole experimental
 * design: change one thing, so a difference can be attributed to it. Two changes
 * at once would produce a number nobody can read.
 *
 * <h2>Offsets, not prices</h2>
 *
 * <p>Each candle is four numbers — open, high, low, close — as POINTS AWAY from
 * the current close, not as prices. Two reasons, and neither is cosmetic. The
 * absolute level of the index says nothing about the next thirty minutes, and
 * the same shape at 100.000 and at 180.000 should read the same. And six-digit
 * numbers eighty times over is most of the request's tokens spent on digits
 * that carry no information.
 *
 * <h2>Blocks, and why they are aligned to the decision and not to the clock</h2>
 *
 * <p>A block is {@link JevStates#EVERY} one-minute bars, counted backwards from
 * the decision bar. Since decisions fall every {@code EVERY} bars from the
 * session's first, the blocks land exactly on the decision grid — so the last
 * candle always ENDS at the moment being decided, which is what a chart shows.
 * Aligning to the wall clock instead would leave the last candle half-formed
 * about half the time, and a half-formed candle looks like a real one.
 *
 * <p>Blocks never cross into yesterday. An overnight gap inside a candle
 * sequence reads as a violent move that did not happen during any session.
 *
 * <h2>Run it like this</h2>
 *
 * <pre>{@code
 * mvn -o test-compile
 * java -cp "target/classes;target/test-classes" \
 *      br.com.jorge.reis.endeavourneo.tools.JevCandleStates \
 *      <serie> <minutos por candle> <quantos candles> <arquivo de saida>
 * }</pre>
 */
public final class JevCandleStates {

    /** How many candles of history the state carries. */
    public static final int CANDLES = 20;

    /** Fewer than this and there is no shape to read; the bar is skipped. */
    public static final int LEAST = 8;

    private JevCandleStates() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    public static void main(String[] args) throws IOException {
        String name = args.length > 0 ? args[0] : SeriesCatalog.defaultName();
        int every = args.length > 1 ? Integer.parseInt(args[1]) : JevStates.EVERY;
        int many = args.length > 2 ? Integer.parseInt(args[2]) : CANDLES;
        Path out = Path.of(args.length > 3 ? args[3] : "estados_candles.jsonl");

        PriceSeries bars = SeriesCatalog.open(name).orElse(null);

        if (bars == null) {
            System.out.println("serie nao encontrada: " + name);

            return;
        }

        System.out.println("serie " + name + ": " + bars.size() + " barras");
        System.out.println("candles de " + every + " minutos, ate " + many + " por estado");

        int written = JevStates.write(bars, Timeframe.defaultZone(), every, out,
                (series, bar, dayStart) -> candles(series, bar, dayStart, every, many));

        System.out.println("gravou " + written + " estados em " + out.toAbsolutePath());
        System.out.printf(Locale.ROOT,
                "a linha de base custou 558 tokens por estado; esta custa mais,%n"
                        + "e o numero real sai do primeiro lote com --limite%n");
    }

    /**
     * The last {@code many} blocks, newest last, as offsets from the close.
     *
     * @return the JSON field, or null when the session has not made
     *         {@link #LEAST} blocks yet
     */
    static String candles(PriceSeries bars, int bar, int dayStart, int every, int many) {
        int available = (bar - dayStart + 1) / every;

        if (available < LEAST) {
            return null;
        }

        int used = Math.min(available, many);
        double now = bars.closeAt(bar);

        StringBuilder text = new StringBuilder("\"candles\":[");

        // OLDEST FIRST, so the sequence reads left to right like a chart. The
        // last one ends exactly at the bar being decided.
        for (int block = used - 1; block >= 0; block--) {
            int last = bar - block * every;
            int first = last - every + 1;

            if (first < dayStart) {
                return null;
            }

            double open = bars.openAt(first);
            double high = Double.NEGATIVE_INFINITY;
            double low = Double.POSITIVE_INFINITY;

            for (int each = first; each <= last; each++) {
                high = Math.max(high, bars.highAt(each));
                low = Math.min(low, bars.lowAt(each));
            }

            if (block < used - 1) {
                text.append(',');
            }

            text.append(String.format(Locale.ROOT, "[%.0f,%.0f,%.0f,%.0f]",
                    open - now, high - now, low - now, bars.closeAt(last) - now));
        }

        return text.append(']').append(String.format(Locale.ROOT,
                ",\"candles_de_minutos\":%d", every)).toString();
    }
}
