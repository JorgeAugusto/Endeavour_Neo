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

import br.com.jorge.reis.endeavourneo.domain.indicator.Bollinger;
import br.com.jorge.reis.endeavourneo.domain.indicator.Stochastic;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Locale;

/**
 * One state file per scenario of the sweep: candle scale, and which indicators.
 *
 * <p>Generalises {@link JevCandleStates} so that a whole grid can be built —
 * candles of five, ten or fifteen minutes, with no indicator, with the slow
 * stochastic, with Bollinger bands, or with both.
 *
 * <h2>This is a SEARCH, and a search has to be counted</h2>
 *
 * <p>Twelve scenarios is twelve chances for one of them to look good by
 * accident, and picking the best of twelve and reporting its {@code t} as
 * though it were the only test is the oldest way to fool yourself with a
 * backtest. Two things are done about it, and neither is optional:
 *
 * <ul>
 *   <li>every scenario is reported, not just the winner — a grid where one cell
 *       shines and the eleven around it are noise is a grid with no signal in
 *       it, and that is only visible when all twelve are printed;</li>
 *   <li>the search runs on one stretch of the base and the winner is re-tested
 *       on another the search never touched.</li>
 * </ul>
 *
 * <p><b>What that split does not fix:</b> the model is pretrained on a world
 * that includes every one of these bars. The held-out stretch protects against
 * OUR selection, not against the model's exposure. A winner that survives both
 * is still not evidence of an edge going forward.
 *
 * <h2>The indicators are read on the CANDLE's scale, at the last closed one</h2>
 *
 * <p>An eight-period stochastic over one-minute bars is an eight-MINUTE
 * stochastic, which is not what "slow stochastic 8/3" means to anybody reading
 * a five-minute chart. So the series is folded to the candle's scale first and
 * the indicator is read there — at the last coarse bar that has CLOSED, which
 * is this project's standing rule for reading a coarse indicator on a fine
 * clock. Reading the bar that contains the current minute would be reading a
 * number the market has not finished making.
 */
public final class JevScenarioStates {

    /** The slow stochastic he asked for: eight periods, smoothed by three. */
    private static final Stochastic STOCHASTIC = new Stochastic(8, 3);

    private static final Bollinger BOLLINGER = Bollinger.standard();

    private JevScenarioStates() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** Which indicators a scenario carries. */
    enum Adds {
        NENHUM(false, false),
        ESTOCASTICO(true, false),
        BOLLINGER(false, true),
        AMBOS(true, true);

        private final boolean stochastic;

        private final boolean bands;

        Adds(boolean stochastic, boolean bands) {
            this.stochastic = stochastic;
            this.bands = bands;
        }
    }

    public static void main(String[] args) throws IOException {
        String name = args.length > 0 ? args[0] : "winfull-1m";
        int every = args.length > 1 ? Integer.parseInt(args[1]) : 15;
        Adds adds = Adds.valueOf(args.length > 2 ? args[2].toUpperCase(Locale.ROOT) : "NENHUM");
        Path out = Path.of(args.length > 3 ? args[3] : "estados_cenario.jsonl");

        ZoneId zone = Timeframe.defaultZone();
        PriceSeries bars = SeriesCatalog.open(name).orElse(null);

        if (bars == null) {
            System.out.println("serie nao encontrada: " + name);

            return;
        }

        PriceSeries coarse = Timeframe.ofMinutes(every).apply(bars, zone);

        Stochastic.Lines stochastic = adds.stochastic ? STOCHASTIC.over(coarse) : null;
        Bollinger.Lines bands = adds.bands ? BOLLINGER.over(coarse) : null;

        // O CURSOR, e nao uma busca por decisao. As decisoes saem em ordem
        // crescente de barra, entao a barra grossa correspondente so anda para
        // a frente: procurar do inicio a cada uma seriam trinta e seis mil
        // varreduras de uma serie de cento e sessenta mil, que e a diferenca
        // entre segundos e horas.
        int[] cursor = {0};

        int written = JevStates.write(bars, zone, every, out, (series, bar, dayStart) -> {
            String candles = JevCandleStates.candles(series, bar, dayStart, every,
                    JevCandleStates.CANDLES);

            if (candles == null) {
                return null;
            }

            String extra = readings(series, coarse, stochastic, bands, bar, cursor);

            return extra == null ? null : candles + extra;
        });

        System.out.printf(Locale.ROOT, "%s  candles de %dm  %s  ->  %d estados em %s%n",
                name, every, adds, written, out.toAbsolutePath());
    }

    /**
     * The indicator fields, read at the last CLOSED coarse bar.
     *
     * @return them, or null when one of them does not exist yet
     */
    private static String readings(PriceSeries fine, PriceSeries coarse,
                                   Stochastic.Lines stochastic, Bollinger.Lines bands,
                                   int bar, int[] cursor) {

        if (stochastic == null && bands == null) {
            return "";
        }

        int at = lastClosed(fine, coarse, bar, cursor);

        if (at < 0) {
            return null;
        }

        StringBuilder text = new StringBuilder();

        if (stochastic != null) {
            double slow = stochastic.slow()[at];
            double signal = stochastic.signal()[at];

            if (Double.isNaN(slow) || Double.isNaN(signal)) {
                return null;
            }

            text.append(String.format(Locale.ROOT,
                    ",\"estocastico_lento\":%.1f,\"estocastico_sinal\":%.1f", slow, signal));
        }

        if (bands != null) {
            double place = bands.placeAt(at, fine.closeAt(bar));

            if (Double.isNaN(place)) {
                return null;
            }

            double width = bands.upper()[at] - bands.lower()[at];

            text.append(String.format(Locale.ROOT,
                    ",\"nas_bandas\":%.2f,\"largura_das_bandas\":%.0f", place, width));
        }

        return text.toString();
    }

    /**
     * @return the last coarse bar that closed at or before this fine bar's close
     *
     * <p>Walked rather than looked up because it is called once per decision
     * and the coarse series is small; an index of every timestamp would be a
     * map the size of the series to answer a question asked forty thousand
     * times.</p>
     */
    private static int lastClosed(PriceSeries fine, PriceSeries coarse, int bar,
                                  int[] cursor) {

        long when = fine.timeAt(bar);

        while (cursor[0] + 1 < coarse.size() && coarse.timeAt(cursor[0] + 1) <= when) {
            cursor[0]++;
        }

        int found = coarse.size() == 0 || coarse.timeAt(cursor[0]) > when ? -1 : cursor[0];

        // THE ONE CONTAINING THIS MINUTE IS NOT CLOSED. The decision falls on
        // the last minute of a coarse bar, so that bar's own close is known --
        // but only because the grids are aligned, and this does not assume it.
        return found;
    }
}
