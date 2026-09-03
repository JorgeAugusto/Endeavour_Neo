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

/**
 * What typing a number offers.
 *
 * <p>Copied in shape from the reference product, where a period is <b>reached by
 * typing it</b> rather than found in a menu: type <code>6</code> and the list
 * offers six minutes, six-tick renko, six seconds, six trades. It is faster than
 * any menu can be, and it scales — a menu holding every useful period would need
 * a hundred entries.</p>
 *
 * <p>We offer the ones we can actually build. A list that showed <i>6 Trades</i>
 * and then produced nothing would be worse than not showing it, and it is the
 * kind of gap that gets discovered by clicking rather than by reading.</p>
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

    /** One offer in the list: what it is called and what it builds. */
    public record Choice(String code, String description, Aggregation aggregation) {
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

        Timeframe minutes = timeframeOf(number);

        if (minutes != null) {
            choices.add(new Choice(String.valueOf(number),
                    number + " minutos", minutes));
        }

        // Bricks are named in TICKS, as the reference product names them: six
        // ticks is thirty points on the mini index. Naming them in points would
        // be more direct and would stop matching what the reader types.
        choices.add(new Choice(number + "R",
                number + " ticks (renko " + trim(number * TICK) + " pts)",
                Renko.of(number * TICK)));

        if (number <= 60) {
            choices.add(new Choice(number + "D",
                    number == 1 ? "1 dia" : number + " dias (ainda não)", null));
        }

        choices.removeIf(choice -> choice.aggregation() == null);

        return choices;
    }

    /** @return the periods worth showing before anything is typed */
    private static List<Choice> common() {
        List<Choice> choices = new ArrayList<>();

        for (Timeframe frame : Timeframe.values()) {
            choices.add(new Choice(frame.label(), describe(frame), frame));
        }

        for (int ticks : new int[]{2, 3, 4, 5, 6}) {
            choices.add(new Choice(ticks + "R",
                    ticks + " ticks (renko " + trim(ticks * TICK) + " pts)",
                    Renko.of(ticks * TICK)));
        }

        return choices;
    }

    /** @return the periods whose name contains the text, for typing "ren" or "dia" */
    private static List<Choice> byName(String text) {
        String wanted = text.toLowerCase(java.util.Locale.ROOT);
        List<Choice> choices = new ArrayList<>();

        for (Choice choice : common()) {
            if (choice.description().toLowerCase(java.util.Locale.ROOT).contains(wanted)
                    || choice.code().toLowerCase(java.util.Locale.ROOT).contains(wanted)) {
                choices.add(choice);
            }
        }

        return choices;
    }

    /**
     * @return the timeframe of that many minutes, or null when we have none
     *
     * <p>Only the ones that exist. Folding an arbitrary number of minutes is
     * possible and will probably come, but offering <i>7 minutos</i> today would
     * offer something that cannot be built.</p>
     */
    private static Timeframe timeframeOf(int minutes) {
        for (Timeframe frame : Timeframe.values()) {
            if (frame.label().equals(minutes + "m")
                    || (minutes == 60 && frame == Timeframe.ONE_HOUR)) {
                return frame;
            }
        }

        return null;
    }

    private static String describe(Timeframe frame) {
        return switch (frame) {
            case ONE_MINUTE -> "1 minuto";
            case FIVE_MINUTES -> "5 minutos";
            case FIFTEEN_MINUTES -> "15 minutos";
            case THIRTY_MINUTES -> "30 minutos";
            case ONE_HOUR -> "1 hora";
            case DAILY -> "1 dia";
            case WEEKLY -> "1 semana";
        };
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
