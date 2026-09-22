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
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.JevDecisions;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Does the model's stated probability match how often the thing happens?
 *
 * <h2>Why this is a better question than "did it make money"</h2>
 *
 * <p>Profit needs the model to have an edge. Calibration only needs it to know
 * what it does not know — and those are different, useful, separately
 * measurable things. A model that says 55% and is right 55% of the time is
 * useful even with no edge at all: it can be combined, thresholded, and sized
 * against. A model that says 80% and is right 45% of the time is worse than
 * useless, because its confidence points the wrong way.
 *
 * <p>And calibration resists the disease that has been eating every other
 * number here. Searching twelve configurations and reporting the most
 * profitable is selection; asking whether a stated probability matches an
 * observed frequency has a right answer that does not improve by being asked
 * five times.
 *
 * <h2>The event, and why it is the LONG one for every row</h2>
 *
 * <p>Each row carries the model's probability that price rises {@link #TARGET}
 * points before falling {@link #STOP}, from that bar, inside that session. This
 * resolves exactly that event — for every row, whichever side the model then
 * chose to trade. Scoring only the side it picked would measure the decision;
 * this measures the belief, which is the thing being calibrated.
 *
 * <h2>The Brier score, and the two numbers it hides</h2>
 *
 * <p>The mean squared error between the stated probability and the outcome, so
 * lower is better. Printed beside it is what a constant prediction of the base
 * rate would have scored — because a Brier score alone is unreadable. Beating
 * the base rate means the model is discriminating; matching it means every
 * number it produced could have been replaced by one constant.
 */
public final class JevCalibration {

    public static final double TARGET = 300;

    public static final double STOP = 200;

    /** Below this, no side pays target, stop and cost. */
    public static final double BREAK_EVEN = 0.413;

    private JevCalibration() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    public static void main(String[] args) throws IOException {
        Path file = Path.of(args.length > 0 ? args[0] : "decisoes.csv");
        String name = args.length > 1 ? args[1] : "winfull-1m";

        ZoneId zone = Timeframe.defaultZone();
        PriceSeries bars = SeriesCatalog.open(name).orElse(null);

        if (bars == null) {
            System.out.println("serie nao encontrada: " + name);

            return;
        }

        JevDecisions said = JevDecisions.read(file);
        Map<Long, Integer> barOf = new HashMap<>();
        int[] lastOfSession = new int[bars.size()];

        map(bars, zone, barOf, lastOfSession);

        List<double[]> rows = new ArrayList<>();

        for (Map.Entry<Long, Integer> each : barOf.entrySet()) {
            JevDecisions.Decision decision = said.at(each.getKey());

            // A row that is not in the file answers 0.5 by default, and a
            // default is not a prediction. Only real rows are scored.
            if (decision.confidence() == 0 && decision.side() == 0
                    && decision.up() == 0.5) {
                continue;
            }

            int bar = each.getValue();
            int last = lastOfSession[bar];

            if (bar + 1 > last) {
                continue;
            }

            double entry = bars.openAt(bar + 1);
            int rose = roseFirst(bars, bar + 1, last, entry);

            if (rose < 0) {
                continue;
            }

            rows.add(new double[] {decision.up(), rose});
        }

        if (rows.isEmpty()) {
            System.out.println("nenhuma linha resolvida");

            return;
        }

        report(rows);
    }

    /**
     * @return {@code 1} when it rose the target before falling the stop,
     *         {@code 0} when it did not, {@code -1} when neither happened
     *
     * <p>Neither happening is dropped rather than counted as a failure. The
     * event asked about was "which comes first"; a session that ends with
     * neither reached did not answer it, and forcing it to zero would push the
     * observed frequency down for reasons that have nothing to do with the
     * model.</p>
     */
    private static int roseFirst(PriceSeries bars, int from, int last, double entry) {
        double up = entry + TARGET;
        double down = entry - STOP;

        for (int bar = from; bar <= last; bar++) {
            boolean fell = bars.lowAt(bar) <= down;
            boolean rose = bars.highAt(bar) >= up;

            if (fell) {
                // THE FALL WINS A TIE, the same way the engine settles it. One
                // bar cannot say which came first, and this is the side that
                // does not flatter.
                return 0;
            }

            if (rose) {
                return 1;
            }
        }

        return -1;
    }

    private static void map(PriceSeries bars, ZoneId zone,
                            Map<Long, Integer> barOf, int[] lastOfSession) {

        int start = 0;
        LocalDate day = Instant.ofEpochMilli(bars.timeAt(0)).atZone(zone).toLocalDate();

        for (int bar = 0; bar < bars.size(); bar++) {
            barOf.put(bars.timeAt(bar), bar);

            LocalDate now = Instant.ofEpochMilli(bars.timeAt(bar)).atZone(zone).toLocalDate();

            if (!now.equals(day)) {
                Arrays.fill(lastOfSession, start, bar, bar - 1);

                start = bar;
                day = now;
            }
        }

        Arrays.fill(lastOfSession, start, bars.size(), bars.size() - 1);
    }

    private static void report(List<double[]> rows) {
        double base = 0;
        double brier = 0;
        double predicted = 0;

        for (double[] row : rows) {
            base += row[1];
            predicted += row[0];
            brier += (row[0] - row[1]) * (row[0] - row[1]);
        }

        int many = rows.size();

        base /= many;
        predicted /= many;
        brier /= many;

        double constant = base * (1 - base);

        System.out.printf(Locale.ROOT, "eventos resolvidos : %d%n", many);
        System.out.printf(Locale.ROOT, "frequencia real    : %.3f  (subiu %.0f antes de cair %.0f)%n",
                base, TARGET, STOP);
        System.out.printf(Locale.ROOT, "media do que disse : %.3f%n", predicted);
        System.out.printf(Locale.ROOT, "vies               : %+.3f%n", predicted - base);
        System.out.println();
        System.out.printf(Locale.ROOT, "Brier do modelo    : %.4f%n", brier);
        System.out.printf(Locale.ROOT, "Brier do constante : %.4f  (dizer sempre %.3f)%n",
                constant, base);
        System.out.printf(Locale.ROOT, "ganho sobre ele    : %+.1f%%%n",
                100 * (constant - brier) / constant);
        System.out.println();
        System.out.println("POR FAIXA DO QUE ELE DISSE -- a calibracao propriamente dita:");
        System.out.println("  faixa        n    disse    ocorreu   diferenca");

        double[] cuts = {0, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 1.01};

        for (int i = 0; i + 1 < cuts.length; i++) {
            int n = 0;
            double says = 0;
            double happened = 0;

            for (double[] row : rows) {
                if (row[0] >= cuts[i] && row[0] < cuts[i + 1]) {
                    n++;
                    says += row[0];
                    happened += row[1];
                }
            }

            if (n == 0) {
                continue;
            }

            System.out.printf(Locale.ROOT, "  %.2f-%.2f %5d    %.3f    %.3f     %+.3f%n",
                    cuts[i], cuts[i + 1], n, says / n, happened / n,
                    happened / n - says / n);
        }

        System.out.println();
        System.out.printf(Locale.ROOT,
                "O equilibrio e %.3f: acima disso uma ponta paga alvo, stop e custo.%n",
                BREAK_EVEN);
    }
}
