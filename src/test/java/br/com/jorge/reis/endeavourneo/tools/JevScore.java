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
 * What each decision would have produced, one by one.
 *
 * <p>Answers the only question worth asking of a set of decisions before
 * running a whole backtest on them: taken as written, with a fixed target and a
 * fixed stop, would they have won or lost — and does the model's own confidence
 * separate the good ones from the bad?
 *
 * <h2>This is a scoring pass and NOT a backtest</h2>
 *
 * <p>Each decision is evaluated on its own, as if it were the only position
 * open. There is no book, no limit on how many run at once, no interaction
 * between them. That makes every decision an independent observation, which is
 * exactly what is wanted for asking "is there signal here" — and it is NOT what
 * a tradeable result looks like, because a real account cannot hold forty
 * overlapping positions and a real run would refuse most of them.
 *
 * <p>So the total here is not money anybody could have made. The per-decision
 * average, its spread and its t are what this is for.
 *
 * <h2>The nulls are printed beside it, because a number alone says nothing</h2>
 *
 * <p>A model that is short eighty per cent of the time, measured on an index
 * that tripled, will lose — and that tells you about the market, not about the
 * model. So the report splits buys from sells and prints what the same
 * decisions would have produced with the side FLIPPED. If flipping turns a loss
 * into a gain of the same size, the model found direction and pointed the wrong
 * way; if both sides lose, the geometry is eating it.
 */
public final class JevScore {

    /** Points, per round trip, per contract. Measured, not arbitrated. */
    public static final double COST = 6.5;

    public static final double TARGET = 300;

    public static final double STOP = 200;

    private JevScore() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** One decision, already resolved. */
    private record Scored(int side, double confidence, double up, double points) { }

    public static void main(String[] args) throws IOException {
        Path file = Path.of(args.length > 0 ? args[0] : "decisoes.csv");
        String name = args.length > 1 ? args[1] : "winfull-1m";
        double least = args.length > 2 ? Double.parseDouble(args[2]) : 0.0;

        ZoneId zone = Timeframe.defaultZone();
        PriceSeries bars = SeriesCatalog.open(name).orElse(null);

        if (bars == null) {
            System.out.println("serie nao encontrada: " + name);

            return;
        }

        JevDecisions said = JevDecisions.read(file);

        System.out.println("decisoes lidas: " + said.size());
        System.out.printf(Locale.ROOT, "alvo %.0f  stop %.0f  custo %.1f pontos por giro%n",
                TARGET, STOP, COST);
        System.out.println();

        Map<Long, Integer> barOf = new HashMap<>();
        int[] lastOfSession = new int[bars.size()];

        mapSessions(bars, zone, barOf, lastOfSession);

        List<Scored> scored = new ArrayList<>();
        List<Scored> flipped = new ArrayList<>();

        for (Map.Entry<Long, Integer> each : barOf.entrySet()) {
            JevDecisions.Decision decision = said.at(each.getKey());

            if (decision.side() == 0 || decision.confidence() < least) {
                continue;
            }

            int bar = each.getValue();
            int last = lastOfSession[bar];

            if (bar + 1 > last) {
                // Decided on the session's last bar: there is no next open to
                // enter at, so there is nothing to score.
                continue;
            }

            double entry = bars.openAt(bar + 1);

            scored.add(new Scored(decision.side(), decision.confidence(), decision.up(),
                    walk(bars, bar + 1, last, decision.side(), entry)));

            flipped.add(new Scored(-decision.side(), decision.confidence(), decision.up(),
                    walk(bars, bar + 1, last, -decision.side(), entry)));
        }

        report("TODAS", scored);
        report("  so compras", filterSide(scored, 1));
        report("  so vendas", filterSide(scored, -1));

        System.out.println();
        report("LADO INVERTIDO", flipped);

        System.out.println();
        System.out.println("POR FAIXA DE CONFIANCA -- a pergunta que o historico pode responder:");
        report("  confianca < 0,30", between(scored, 0, 0.30));
        report("  0,30 a 0,50", between(scored, 0.30, 0.50));
        report("  0,50 a 0,70", between(scored, 0.50, 0.70));
        report("  0,70 ou mais", between(scored, 0.70, 2));
    }

    /** Where each session ends, and which bar each timestamp is. */
    private static void mapSessions(PriceSeries bars, ZoneId zone,
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

    /**
     * @return the points the position made, net of cost
     *
     * <p>Same rules the engine uses: the stop wins a tie, a stop fills at the
     * worse of its trigger and the open, a target at the better. Nothing
     * crosses the session.</p>
     */
    private static double walk(PriceSeries bars, int from, int last, int side, double entry) {
        double target = entry + side * TARGET;
        double stop = entry - side * STOP;

        for (int bar = from; bar <= last; bar++) {
            boolean hitStop = side > 0 ? bars.lowAt(bar) <= stop : bars.highAt(bar) >= stop;
            boolean hitTarget = side > 0
                    ? bars.highAt(bar) >= target : bars.lowAt(bar) <= target;

            if (hitStop) {
                double price = side > 0
                        ? Math.min(bars.openAt(bar), stop) : Math.max(bars.openAt(bar), stop);

                return (price - entry) * side - COST;
            }

            if (hitTarget) {
                double price = side > 0
                        ? Math.max(bars.openAt(bar), target) : Math.min(bars.openAt(bar), target);

                return (price - entry) * side - COST;
            }
        }

        return (bars.closeAt(last) - entry) * side - COST;
    }

    private static List<Scored> filterSide(List<Scored> all, int side) {
        List<Scored> made = new ArrayList<>();

        for (Scored each : all) {
            if (each.side() == side) {
                made.add(each);
            }
        }

        return made;
    }

    private static List<Scored> between(List<Scored> all, double from, double to) {
        List<Scored> made = new ArrayList<>();

        for (Scored each : all) {
            if (each.confidence() >= from && each.confidence() < to) {
                made.add(each);
            }
        }

        return made;
    }

    /** Count, total, per decision, spread, t, hit rate and MEDIAN. */
    private static void report(String what, List<Scored> all) {
        if (all.isEmpty()) {
            System.out.printf(Locale.ROOT, "%-18s  nenhuma%n", what);

            return;
        }

        int many = all.size();
        double total = 0;
        double squares = 0;
        int won = 0;
        double[] points = new double[many];

        for (int i = 0; i < many; i++) {
            double each = all.get(i).points();

            points[i] = each;
            total += each;
            squares += each * each;

            if (each > 0) {
                won++;
            }
        }

        double mean = total / many;
        double deviation = Math.sqrt(Math.max(0, squares / many - mean * mean));
        double error = deviation / Math.sqrt(many);
        double t = error == 0 ? 0 : mean / error;

        Arrays.sort(points);

        double median = many % 2 == 1 ? points[many / 2]
                : (points[many / 2 - 1] + points[many / 2]) / 2;

        System.out.printf(Locale.ROOT,
                "%-18s  n %4d   media %8.1f   mediana %7.1f   t %6.2f   acerto %4.1f%%   total %9.0f%n",
                what, many, mean, median, t, 100.0 * won / many, total);
    }
}
