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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * What a series is, in the few lines a reader needs before trusting it.
 *
 * <p>The same idea as the summary that follows the cursor over a bar, one level
 * up: that one says what happened in a minute, this one says what the whole
 * series IS. A chart shows a name and a period and nothing else, and those two
 * do not distinguish six years from six weeks, nor a renko built from ticks
 * from one built from candles — which are different charts wearing the same
 * name.</p>
 *
 * <p><b>Where the source line matters most.</b> Measured on WINFUT over January
 * 2021, brick 55: 9.718 bricks from one-minute candles against 8.076 from the
 * exchange's own ticks -- 27% apart, same name, same brick size. Nothing else on
 * screen tells the two apart. This line does.</p>
 */
final class SeriesSummary {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private SeriesSummary() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @return label and value, in the order they should be read */
    static List<String[]> rowsFor(PriceSeries series, String name, String period,
                                  boolean fromTicks) {
        List<String[]> rows = new ArrayList<>();

        rows.add(new String[]{Messages.get("summary.name"), name == null ? "-" : name});
        rows.add(new String[]{Messages.get("summary.period"), period == null ? "-" : period});

        if (series == null || series.size() == 0) {
            rows.add(new String[]{Messages.get("summary.bars"), "0"});

            return rows;
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDate first = Instant.ofEpochMilli(series.timeAt(0)).atZone(zone).toLocalDate();
        LocalDate last = Instant.ofEpochMilli(series.timeAt(series.size() - 1))
                .atZone(zone).toLocalDate();

        rows.add(new String[]{Messages.get("summary.source"),
                Messages.get(fromTicks ? "summary.source.ticks" : "summary.source.candles")});
        rows.add(new String[]{Messages.get("summary.from"), first.format(DAY)});
        rows.add(new String[]{Messages.get("summary.to"), last.format(DAY)});
        rows.add(new String[]{Messages.get("summary.span"), spanBetween(first, last)});
        rows.add(new String[]{Messages.get("summary.sessions"), count(sessionsIn(series))});
        rows.add(new String[]{Messages.get("summary.bars"), count(series.size())});

        return rows;
    }

    /**
     * @return the stretch as years, months and days
     *
     * <p>Only the parts that are not zero. "6 anos, 0 meses e 0 dias" reads as
     * a form to be filled in rather than as an answer.</p>
     */
    static String spanBetween(LocalDate first, LocalDate last) {
        Period span = Period.between(first, last);
        List<String> parts = new ArrayList<>(3);

        if (span.getYears() > 0) {
            parts.add(Messages.get("summary.years", span.getYears()));
        }

        if (span.getMonths() > 0) {
            parts.add(Messages.get("summary.months", span.getMonths()));
        }

        if (span.getDays() > 0 || parts.isEmpty()) {
            parts.add(Messages.get("summary.days", span.getDays()));
        }

        return String.join(", ", parts);
    }

    /**
     * @return how many sessions the series covers
     *
     * <p>Walked once, asking the calendar only where the day changes. Per bar
     * it would be 825 thousand conversions on the source, on the interface
     * thread, every time the pointer crosses the name.</p>
     */
    static int sessionsIn(PriceSeries series) {
        if (series == null || series.size() == 0) {
            return 0;
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDate seen = null;
        int days = 0;

        for (int i = 0; i < series.size(); i++) {
            LocalDate day = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone).toLocalDate();

            if (!day.equals(seen)) {
                seen = day;

                days++;
            }
        }

        return days;
    }

    /** @return the summary as a tooltip */
    static String html(PriceSeries series, String name, String period, boolean fromTicks) {
        StringBuilder text = new StringBuilder(
                "<html><table cellpadding=1 cellspacing=0>");

        for (String[] row : rowsFor(series, name, period, fromTicks)) {
            text.append("<tr><td>").append(escape(row[0]))
                    .append("</td><td align=right><b>&nbsp;&nbsp;")
                    .append(escape(row[1])).append("</b></td></tr>");
        }

        return text.append("</table></html>").toString();
    }

    /**
     * @return the text with the four characters that would end the tooltip early
     *
     * <p>None of them appears in an instrument name today. A name typed by the
     * reader tomorrow could contain any of them, and a tooltip that swallows
     * half of itself is a defect nobody would connect to a name.</p>
     */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String count(int value) {
        return String.format("%,d", value);
    }
}
