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
package br.com.jorge.reis.endeavourneo.domain.trading;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Every execution of a run, written out so it can be read again.
 *
 * <p>The screen answers "what happened" for one trade at a time; a file answers
 * it for all of them at once, and a month later. It is the same arithmetic the
 * detail window shows — {@link Trade#steps()} — so the file and the screen can
 * never disagree about a number.
 *
 * <h2>One row per EXECUTION, not per trade</h2>
 *
 * <p>A trade that ladders into four contracts and leaves in one piece has five
 * stories and one average, and the average hides the thing worth looking at.
 * The row carries what the position was after each execution, so a ladder is
 * legible: the {@code aberto} column counts up and then down.
 *
 * <h2>Semicolons and a comma for the decimal</h2>
 *
 * <p>Because it is opened in a Brazilian spreadsheet. A file that needs an
 * import wizard is a file that gets read once.
 */
public final class Executions {

    /** The header line, and the order the columns are written in. */
    private static final String COLUMNS =
            "operacao;giro;data;hora;barra;verbo;lado;preco;qtd;aberto;medio;pontos;reais";

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private Executions() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param result   the run
     * @param over     the bars it ran on, for the clock; may be null
     * @param zone     the exchange's zone
     * @param perPoint reais per point per contract
     * @param about    lines describing the run — the series, the scale, the
     *                 strategy and its settings
     * @return the whole file, ready to be written
     *
     * <p>The description is written as comment lines above the header, and it is
     * not decoration: a file of executions with no record of WHICH run produced
     * them is a file that cannot be compared with another one, which is the only
     * thing anybody ever wants to do with two of them.</p>
     */
    public static String of(Result result, PriceSeries over, ZoneId zone,
                            double perPoint, List<String> about) {

        StringBuilder text = new StringBuilder();

        for (String line : about == null ? List.<String>of() : about) {
            text.append("# ").append(line).append('\n');
        }

        text.append(COLUMNS).append('\n');

        List<Trade> trades = result.trades();

        for (int which = 0; which < trades.size(); which++) {
            Trade trade = trades.get(which);
            List<Trade.Step> steps = trade.steps();

            for (int giro = 0; giro < steps.size(); giro++) {
                row(text, which + 1, giro + 1, steps.get(giro), over, zone, perPoint);
            }
        }

        return text.toString();
    }

    private static void row(StringBuilder text, int which, int giro, Trade.Step step,
                            PriceSeries over, ZoneId zone, double perPoint) {

        Fill fill = step.fill();

        text.append(which).append(';').append(giro).append(';');

        // THE CLOCK, not the bar number. A bar index is only meaningful beside
        // the run that produced it; a timestamp can be looked up on a chart, in
        // the tape, or in another run at another scale.
        if (over != null && fill.bar() >= 0 && fill.bar() < over.size()) {
            var when = Instant.ofEpochMilli(over.timeAt(fill.bar())).atZone(zone);

            text.append(DAY.format(when)).append(';').append(CLOCK.format(when)).append(';');
        } else {
            text.append(';').append(';');
        }

        text.append(fill.bar()).append(';')
                .append(fill.verb()).append(';')
                .append(step.opening() ? "entrada" : "saida").append(';');

        number(text, fill.price());
        text.append(';').append(fill.quantity()).append(';').append(step.held()).append(';');
        number(text, step.average());
        text.append(';');

        // ZERO AND NOT BLANK on an opening: it realised nothing, and nothing is a
        // number here. Blank would read as "not measured".
        number(text, step.points());
        text.append(';');
        number(text, step.points() * perPoint);
        text.append('\n');
    }

    private static void number(StringBuilder text, double value) {
        if (Double.isNaN(value)) {
            return;
        }

        text.append(String.format(Locale.ROOT, "%.2f", value).replace('.', ','));
    }
}
