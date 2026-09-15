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

import br.com.jorge.reis.endeavourneo.domain.indicator.Vwap;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The twenty-two attributes the fade network reads, section 5 of its specification.
 *
 * <h2>The ORDER of the columns is part of the model</h2>
 *
 * <p>The specification says it twice and it is worth a third: the columns go in
 * the order of {@link #NAMES}, a reader must require exactly twenty-two of them,
 * and they must never be sorted alphabetically. Nothing in the numbers says
 * which column is which — a network trained with {@code minute} in slot zero and
 * asked with {@code family} there answers confidently and wrongly, and every
 * check downstream still passes.
 *
 * <h2>Everything here is known at the signal, and that is the whole discipline</h2>
 *
 * <p>The daily block comes from row {@code D-1} and never from {@code D}: the
 * day the signal lives in has not closed, so its own high, low and close are
 * facts that do not exist yet. The intraday block is read at the signal's bar
 * and looks only backwards. The VWAP is read there too. Section 17 of the
 * specification states the rule this implements, and the commonest way to break
 * it is not a deliberate peek — it is a daily table indexed by {@code D}.
 *
 * <h2>Two attributes this series cannot produce</h2>
 *
 * <p>{@link PriceSeries} carries no volume. {@code volume_z} is therefore always
 * zero, and the VWAP behind {@code vwap_z} is weighted by one instead of by
 * traded contracts — see {@link Vwap}. That is two of the twenty-two either dead
 * or approximate, it is not hidden, and any comparison with the Python starts
 * there rather than at the totals.
 */
public final class FadeFeatures {

    /** The canonical order. Do not sort this. */
    public static final String[] NAMES = {
        "minute", "family", "band",
        "ro_size_atr", "r90_size_atr", "range_ratio",
        "ro_dir", "r90_dir",
        "prior_ret1", "prior_ret5", "prior_ret20",
        "ema5s", "ema10s", "ema20s",
        "gap_atr",
        "ret5_atr", "ret15_atr", "vol15_atr", "volume_z",
        "pos_ro", "pos_r90", "vwap_z",
    };

    public static final int COUNT = 22;

    /** The averages of section 5.1. */
    private static final int[] SPANS = {5, 10, 20};

    private static final int ATR = 20;

    private final PriceSeries bars;

    private final ZoneId zone;

    private final Vwap.Lines vwap;

    /** One entry per session, in order, with the day's own numbers. */
    private final List<LocalDate> days = new ArrayList<>();

    private final List<Double> dayOpen = new ArrayList<>();

    private final List<Double> dayHigh = new ArrayList<>();

    private final List<Double> dayLow = new ArrayList<>();

    private final List<Double> dayClose = new ArrayList<>();

    /** Which session each bar belongs to. */
    private final int[] dayOf;

    private double[] atr;

    private double[][] slope;

    public FadeFeatures(PriceSeries series, ZoneId at) {
        this.bars = series == null ? PriceSeries.empty() : series;
        this.zone = at == null ? Timeframe.defaultZone() : at;
        this.vwap = Vwap.standard().over(bars, zone);
        this.dayOf = new int[bars.size()];

        fold();
        measure();
    }

    /** Builds the daily table the section 5.1 block is read from. */
    private void fold() {
        LocalDate day = null;

        for (int bar = 0; bar < bars.size(); bar++) {
            LocalDate now = Instant.ofEpochMilli(bars.timeAt(bar)).atZone(zone).toLocalDate();

            if (!now.equals(day)) {
                days.add(now);
                dayOpen.add(bars.openAt(bar));
                dayHigh.add(bars.highAt(bar));
                dayLow.add(bars.lowAt(bar));
                dayClose.add(bars.closeAt(bar));

                day = now;
            } else {
                int at = days.size() - 1;

                dayHigh.set(at, Math.max(dayHigh.get(at), bars.highAt(bar)));
                dayLow.set(at, Math.min(dayLow.get(at), bars.lowAt(bar)));
                dayClose.set(at, bars.closeAt(bar));
            }

            dayOf[bar] = days.size() - 1;
        }
    }

    /** The daily-scale averages: the true range's mean, and each average's slope. */
    private void measure() {
        int many = days.size();

        atr = new double[many];
        slope = new double[SPANS.length][many];

        Arrays.fill(atr, Double.NaN);

        double[] range = new double[many];

        for (int day = 0; day < many; day++) {
            double high = dayHigh.get(day);
            double low = dayLow.get(day);

            range[day] = day == 0 ? high - low
                    : Math.max(high - low, Math.max(Math.abs(high - dayClose.get(day - 1)),
                            Math.abs(low - dayClose.get(day - 1))));
        }

        for (int day = ATR - 1; day < many; day++) {
            double total = 0;

            for (int back = 0; back < ATR; back++) {
                total += range[day - back];
            }

            atr[day] = total / ATR;
        }

        for (int which = 0; which < SPANS.length; which++) {
            double[] line = exponential(SPANS[which]);

            Arrays.fill(slope[which], Double.NaN);

            for (int day = 1; day < many; day++) {
                if (Double.isNaN(line[day]) || Double.isNaN(line[day - 1])) {
                    continue;
                }

                slope[which][day] = (line[day] - line[day - 1]) / dayClose.get(day);
            }
        }
    }

    /**
     * {@code EWM(close, span, adjust=false, minPeriods=span)}.
     *
     * <p>{@code adjust=false} is the recursive form — {@code y = (1-a)*y + a*x},
     * seeded with the first value — and NOT the weighted-sum form pandas uses by
     * default. The two agree only in the limit; over the first twenty rows, which
     * is where {@code minPeriods} makes the series start, they do not.</p>
     */
    private double[] exponential(int span) {
        int many = days.size();
        double[] made = new double[many];

        Arrays.fill(made, Double.NaN);

        if (many == 0) {
            return made;
        }

        double alpha = 2.0 / (span + 1);
        double running = dayClose.get(0);

        for (int day = 0; day < many; day++) {
            running = day == 0 ? dayClose.get(0)
                    : (1 - alpha) * running + alpha * dayClose.get(day);

            if (day >= span - 1) {
                made[day] = running;
            }
        }

        return made;
    }

    // ------------------------------------------------------------ the sample

    /**
     * @param bar    the signal's bar
     * @param source the level's name, which decides {@code family} and {@code band}
     * @param entry  the price the order would rest at
     * @param fight  the day's opening range, or null
     * @param clock  the day's ninety-minute range, or null
     * @return twenty-two values in {@link #NAMES} order; unknown ones are NaN
     */
    public double[] of(int bar, String source, double entry,
                       br.com.jorge.reis.endeavourneo.domain.indicator.OpeningImpulse.Fight fight,
                       OpeningRange.Session clock) {

        double[] made = new double[COUNT];

        Arrays.fill(made, Double.NaN);

        if (bar < 0 || bar >= bars.size()) {
            return made;
        }

        int day = dayOf[bar];
        double priorAtr = day > 0 ? atr[day - 1] : Double.NaN;

        ZonedDateTime when = Instant.ofEpochMilli(bars.timeAt(bar)).atZone(zone);

        made[0] = when.getHour() * 60.0 + when.getMinute();
        made[1] = familyOf(source);
        made[2] = bandOf(source);

        double fightSize = fight == null ? Double.NaN : fight.size();
        double clockSize = clock == null ? Double.NaN : clock.high() - clock.low();

        made[3] = fightSize / priorAtr;
        made[4] = clockSize / priorAtr;
        made[5] = fightSize / clockSize;
        made[6] = fight == null ? Double.NaN : fight.direction();
        made[7] = clock == null ? Double.NaN : initialDirectionOf(clock);

        made[8] = priorReturn(day, 1);
        made[9] = priorReturn(day, 5);
        made[10] = priorReturn(day, 20);

        for (int which = 0; which < SPANS.length; which++) {
            made[11 + which] = day > 0 ? slope[which][day - 1] : Double.NaN;
        }

        made[14] = day > 0 ? (dayOpen.get(day) - dayClose.get(day - 1)) / priorAtr
                : Double.NaN;

        made[15] = change(bar, 5) / priorAtr;
        made[16] = change(bar, 15) / priorAtr;
        made[17] = deviationOfChanges(bar, 15) / priorAtr;

        // NO VOLUME IN THIS SERIES, so this column is a constant zero rather
        // than a NaN: a NaN would drop the whole row, and the other twenty-one
        // attributes of that row are perfectly good.
        made[18] = 0;

        made[19] = fight == null ? Double.NaN
                : (entry - (fight.high() + fight.low()) / 2) / fightSize;
        made[20] = clock == null ? Double.NaN
                : (entry - (clock.high() + clock.low()) / 2) / clockSize;

        double spread = bar < vwap.deviation().length ? vwap.deviation()[bar] : Double.NaN;

        made[21] = Double.isNaN(spread) || spread == 0
                ? 0 : (entry - vwap.vwap()[bar]) / spread;

        return made;
    }

    /** {@code +1} when the formation closed above where it opened. */
    private int initialDirectionOf(OpeningRange.Session session) {
        int last = session.first();

        while (last + 1 <= session.last() && bars.timeAt(last + 1) < session.formedAt()) {
            last++;
        }

        return bars.closeAt(last) >= bars.openAt(session.first()) ? 1 : -1;
    }

    private double priorReturn(int day, int back) {
        int now = day - 1;
        int then = now - back;

        if (then < 0 || now < 0) {
            return Double.NaN;
        }

        double before = dayClose.get(then);

        return before == 0 ? Double.NaN : (dayClose.get(now) - before) / before;
    }

    private double change(int bar, int back) {
        return bar - back < 0 ? Double.NaN : bars.closeAt(bar) - bars.closeAt(bar - back);
    }

    /**
     * The SAMPLE deviation of the last {@code window} one-bar changes.
     *
     * <p>Sample and not population — dividing by {@code n-1} — because that is
     * what {@code pandas.Series.std} does by default and what the specification
     * names. Over fifteen observations the two differ by three and a half per
     * cent, which is not nothing in a column that gets standardised.</p>
     */
    private double deviationOfChanges(int bar, int window) {
        if (bar - window < 0) {
            return Double.NaN;
        }

        double total = 0;

        for (int back = 0; back < window; back++) {
            total += bars.closeAt(bar - back) - bars.closeAt(bar - back - 1);
        }

        double mean = total / window;
        double squared = 0;

        for (int back = 0; back < window; back++) {
            double each = bars.closeAt(bar - back) - bars.closeAt(bar - back - 1) - mean;

            squared += each * each;
        }

        return Math.sqrt(squared / (window - 1));
    }

    /** {@code 0} for the opening range, {@code 1} for the Range 90, {@code 2} for the VWAP. */
    static int familyOf(String source) {
        if (source == null) {
            return 2;
        }

        if (source.startsWith("abertura")) {
            return 0;
        }

        return source.startsWith("range90") ? 1 : 2;
    }

    /** {@code -k} for {@code vwap_mk}, {@code +k} for {@code vwap_pk}, zero otherwise. */
    static int bandOf(String source) {
        if (source == null || !source.startsWith("vwap_")) {
            return 0;
        }

        char which = source.charAt(5);
        int away;

        try {
            away = Integer.parseInt(source.substring(6));
        } catch (NumberFormatException e) {
            // A band name this version does not know. Zero is what every source
            // that is not a band answers, so an unreadable one joins them rather
            // than stopping the run.
            return 0;
        }

        return which == 'm' ? -away : away;
    }

    /** @return whether every attribute is a real number, which is what section 5 requires */
    public static boolean complete(double[] sample) {
        if (sample == null || sample.length != COUNT) {
            return false;
        }

        for (double each : sample) {
            if (Double.isNaN(each) || Double.isInfinite(each)) {
                return false;
            }
        }

        return true;
    }
}
