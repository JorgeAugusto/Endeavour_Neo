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
package br.com.jorge.reis.endeavourneo.ui.chart;

import br.com.jorge.reis.endeavourneo.domain.market.Aggregation;
import br.com.jorge.reis.endeavourneo.domain.market.Renko;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What typing a number offers.
 *
 * <p>Copied in shape from the reference product, where a period is <b>reached by
 * typing it</b> rather than found in a menu: type <code>6</code> and the list
 * offers six minutes and six-tick renko. It is faster than any menu can be, and
 * it scales — a menu holding every useful period would need a hundred
 * entries.</p>
 *
 * <p>We offer the ones we can actually build. A list that showed something and
 * then produced nothing would be worse than not showing it, and it is the kind
 * of gap that gets discovered by clicking rather than by reading.</p>
 */
public final class PeriodCatalog {

    /**
     * The size of one tick in points.
     *
     * <p><b>Five, because the mini index moves in five-point steps.</b> It is a
     * property of the instrument and belongs with the base once bases carry
     * their own metadata; until then it lives here, named, rather than as a bare
     * 5 buried in a calculation.</p>
     */
    public static final double TICK = 5.0;

    /**
     * How many points a brick called <code>nR</code> actually measures.
     *
     * <p><b>One tick less than the name suggests</b>, which is the reference
     * product's own formula:</p>
     *
     * <pre>
     * tamanho = (n x tick) - tick
     * </pre>
     *
     * <p>So on the mini index, where a tick is five points, 5R is
     * (5 x 5) - 5 = 20 points and 11R is 50 -- not 25 and 55. This program
     * computed n x tick and was one tick too big at every size, which makes
     * every brick, every count and every comparison against a Profit chart
     * quietly wrong. Nothing on screen would have shown it: a 55-point renko is
     * a perfectly good chart, it is simply not the one the reader asked for.</p>
     *
     * <p>Note what the formula means at the bottom: 2R is one tick, which lays
     * a brick on every price change -- the tick tape drawn as boxes, and the one
     * thing renko exists not to be. That is why the smallest offered is 3R.</p>
     */
    public static double brickOf(int name) {
        return (name - 1) * TICK;
    }

    /**
     * The smallest brick worth offering, by name.
     *
     * <p>Three, so the brick is two ticks. 2R is one tick by the formula above,
     * and a one-tick brick filters nothing.</p>
     */
    public static final int SMALLEST_BRICK = 3;

    /** The largest brick worth offering: 101R is 500 points on the mini. */
    public static final int LARGEST_BRICK = 101;

    /** One offer in the list: what it is called and what it builds. */
    public record Choice(String code, String description, Aggregation aggregation) {

        /**
         * @return how the chart writes this period in its title
         *
         * <p>Renko carries both numbers, because neither alone is enough:
         * <code>11R</code> is what was typed and what the reader will type
         * again, and <code>55 pts</code> is what a brick actually measures. The
         * conversion needs the instrument's tick size, which is why this lives
         * here and not on {@link Renko} — a brick in the domain knows its height
         * in price and has no business knowing what a tick is worth.</p>
         */
        public String title() {
            if (aggregation instanceof Renko renko) {
                return code + " - " + trim(renko.brick()) + " pts";
            }

            return code;
        }
    }

    private PeriodCatalog() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param typed what the reader has typed so far
     * @return the periods that typing offers, best guess first
     *
     * <p>Empty text lists the common periods rather than nothing: the window can
     * be opened with a double click and not only by typing, and an empty window
     * would then be a dead end.</p>
     */
    public static List<Choice> forText(String typed) {
        String clean = typed == null ? "" : typed.trim();

        if (clean.isEmpty()) {
            return common();
        }

        int number = parse(clean);

        if (number <= 0) {
            return byName(clean);
        }

        List<Choice> choices = new ArrayList<>();
        Timeframe minutes = Timeframe.ofMinutes(number);

        if (minutes != null) {
            choices.add(new Choice(minutes.label(), describe(minutes), minutes));
        }

        // Named the way the reference product names them, and sized the way it
        // sizes them -- see brickOf. Naming bricks in points would be more
        // direct and would stop matching what the reader types.
        if (number >= SMALLEST_BRICK && number <= LARGEST_BRICK) {
            choices.add(new Choice(number + "R",
                    number + "R (renko " + trim(brickOf(number)) + " pts)",
                    Renko.of(brickOf(number)).withForming(true)));
        }

        return choices;
    }

    /**
     * @param code what the reader typed, as it was stored -- "5m", "11R"
     * @return that period, or null when nothing answers to the code
     *
     * <p>How a chart comes back after a restart. Null rather than a guess: a
     * window reopening on the wrong scale is worse than one reopening on the
     * default, because it looks right.</p>
     */
    public static Choice byCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }

        String wanted = code.trim();

        for (Choice choice : forText(wanted.replaceAll("[^0-9]", ""))) {
            if (choice.code().equalsIgnoreCase(wanted)) {
                return choice;
            }
        }

        for (Choice choice : common()) {
            if (choice.code().equalsIgnoreCase(wanted)) {
                return choice;
            }
        }

        return null;
    }

    /** @return the periods worth showing before anything is typed */
    private static List<Choice> common() {
        List<Choice> choices = new ArrayList<>();

        for (Timeframe frame : Timeframe.common()) {
            choices.add(new Choice(frame.label(), describe(frame), frame));
        }

        for (int name : new int[]{3, 4, 5, 6, 11, 21}) {
            choices.add(new Choice(name + "R",
                    name + "R (renko " + trim(brickOf(name)) + " pts)",
                    Renko.of(brickOf(name)).withForming(true)));
        }

        return choices;
    }

    /** @return the periods whose name contains the text, for typing "ren" or "dia" */
    private static List<Choice> byName(String text) {
        String wanted = text.toLowerCase(Locale.ROOT);
        List<Choice> choices = new ArrayList<>();

        for (Choice choice : common()) {
            if (choice.description().toLowerCase(Locale.ROOT).contains(wanted)
                    || choice.code().toLowerCase(Locale.ROOT).contains(wanted)) {
                choices.add(choice);
            }
        }

        return choices;
    }

    /**
     * @return how that scale is written out in the list
     *
     * <p>Spelled out rather than abbreviated: the code column already carries
     * <code>15m</code>, and a list where both columns say the same thing wastes
     * the one that could have explained it.</p>
     */
    private static String describe(Timeframe frame) {
        int minutes = frame.minutes();

        if (minutes == 0) {
            return "1 dia";
        }

        if (minutes == -1) {
            return "1 semana";
        }

        if (minutes == -2) {
            return "1 mês";
        }

        if (minutes == 1) {
            return "1 minuto";
        }

        if (minutes % 60 == 0) {
            int hours = minutes / 60;

            return hours + (hours == 1 ? " hora" : " horas")
                    + " (" + minutes + " minutos)";
        }

        return minutes + " minutos";
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static int parse(String text) {
        try {
            return Integer.parseInt(text.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
