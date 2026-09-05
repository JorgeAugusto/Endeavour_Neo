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
package br.com.jorge.reis.endeavourneo.ui.series;

import br.com.jorge.reis.endeavourneo.domain.market.Segment;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.UIManager;

/**
 * The whole series as one bar, with what is already segmented drawn inside it.
 *
 * <h2>Measured in sessions, not in days</h2>
 *
 * <p>The horizontal axis is the session INDEX and not the calendar. Two things
 * follow, and both are the point: a weekend takes no width, because nothing
 * happened in it; and equal widths mean equal amounts of market, which is the
 * only comparison anybody makes here. A calendar axis would draw the December
 * holidays as a stretch of series that does not exist.</p>
 *
 * <p>It costs an uneven year axis — 2020 is a third of a year and looks it —
 * and that is honest rather than a flaw. See {@link RangeBar}, which shares the
 * scale so the two line up.</p>
 *
 * <h2>Why one bar and not two</h2>
 *
 * <p>The series and its segments are the same object seen twice, so they are
 * drawn once: the bar is the series, the blocks inside it are the segments, and
 * what is left pale is free. This is what a partition editor does with a disk,
 * and the reason it works there is the reason it works here — the whole is
 * always visible, and the parts cannot add up to more than it.</p>
 */
final class SeriesMap extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final int BAR = 34;

    private static final int AXIS = 18;

    private static final int SIDE = 2;

    /** Every session the series holds, in order. Empty until one is given. */
    private final transient List<LocalDate> days = new ArrayList<>();

    private final transient List<Segment> segments = new ArrayList<>();

    /** The range being drawn on top, or null when nothing is being created. */
    private transient Segment fresh;

    private transient boolean clashing;

    SeriesMap() {
        setPreferredSize(new Dimension(520, BAR + AXIS + 4));
        setMinimumSize(new Dimension(200, BAR + AXIS + 4));
    }

    void showSeries(List<LocalDate> sessions, List<Segment> existing) {
        days.clear();
        days.addAll(sessions);
        segments.clear();
        segments.addAll(existing);

        repaint();
    }

    void showFresh(Segment segment, boolean clash) {
        fresh = segment;
        clashing = clash;

        repaint();
    }

    /**
     * @param index a session, from zero
     * @return where it sits, in pixels
     *
     * <p>Half a slot in from each end, so the FIRST session has width rather
     * than being a line on the border. A series of one session would otherwise
     * draw as nothing at all.</p>
     */
    private double x(int index) {
        int width = Math.max(1, getWidth() - SIDE * 2);

        if (days.size() <= 1) {
            return SIDE;
        }

        return SIDE + (double) index / days.size() * width;
    }

    /** @return the first session at or after that day, or the size when there is none */
    private int indexOf(LocalDate day) {
        int low = 0;
        int high = days.size();

        while (low < high) {
            int middle = (low + high) >>> 1;

            if (days.get(middle).isBefore(day)) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }

        return low;
    }

    private int lastIndexOf(LocalDate day) {
        int at = indexOf(day);

        // indexOf lands on the first session NOT BEFORE the day, which is the
        // day itself when the market opened and the next session when it did
        // not. Either way the segment ends on the session before that one,
        // unless the day is itself a session.
        if (at < days.size() && days.get(at).equals(day)) {
            return at;
        }

        return at - 1;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();

        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            g.setColor(SeriesColors.free());
            g.fillRect(SIDE, 2, Math.max(1, getWidth() - SIDE * 2), BAR);

            if (!days.isEmpty()) {
                paintSegments(g);
                paintFresh(g);
                paintYears(g);
            }

            g.setColor(SeriesColors.rule());
            g.drawRect(SIDE, 2, Math.max(1, getWidth() - SIDE * 2) - 1, BAR - 1);
        } finally {
            g.dispose();
        }
    }

    private void paintSegments(Graphics2D g) {
        FontMetrics metrics = g.getFontMetrics();

        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            int from = indexOf(segment.from());
            int to = segment.isOpenEnded() ? days.size() - 1 : lastIndexOf(segment.to());

            if (to < from) {
                continue;
            }

            int left = (int) Math.round(x(from));
            int width = Math.max(2, (int) Math.round(x(to + 1)) - left);

            g.setColor(SeriesColors.taken(i));
            g.fillRect(left, 2, width, BAR);

            g.setColor(SeriesColors.rule());
            g.drawRect(left, 2, width - 1, BAR - 1);

            // The name only where it fits. A label clipped to three letters
            // says less than no label and costs the same room.
            String name = segment.name();

            if (metrics.stringWidth(name) + 10 < width) {
                g.setColor(Color.WHITE);
                g.drawString(name, left + (width - metrics.stringWidth(name)) / 2,
                        2 + BAR / 2 + metrics.getAscent() / 2 - 1);
            }
        }
    }

    private void paintFresh(Graphics2D g) {
        if (fresh == null) {
            return;
        }

        int from = indexOf(fresh.from());
        int to = fresh.isOpenEnded() ? days.size() - 1 : lastIndexOf(fresh.to());

        if (to < from) {
            return;
        }

        int left = (int) Math.round(x(from));
        int width = Math.max(3, (int) Math.round(x(to + 1)) - left);

        // Hatched rather than filled: what is underneath has to stay readable,
        // because the whole question being asked is what this lands on.
        g.setColor(clashing ? SeriesColors.clash() : SeriesColors.fresh());

        for (int at = left - BAR; at < left + width; at += 6) {
            g.drawLine(Math.max(left, at), 2 + BAR,
                    Math.min(left + width, at + BAR), 2);
        }

        g.setStroke(new java.awt.BasicStroke(2f));
        g.drawRect(left, 3, width - 1, BAR - 2);
    }

    private void paintYears(Graphics2D g) {
        FontMetrics metrics = g.getFontMetrics();
        int previous = -1;

        for (int i = 0; i < days.size(); i++) {
            int year = days.get(i).getYear();

            if (year == previous) {
                continue;
            }

            previous = year;

            int at = (int) Math.round(x(i));
            String label = String.valueOf(year);

            g.setColor(SeriesColors.rule());
            g.drawLine(at, 2 + BAR, at, 2 + BAR + 4);

            g.setColor(SeriesColors.faint());
            g.drawString(label, at + 2, 2 + BAR + 4 + metrics.getAscent());
        }
    }

    /** @return how many sessions lie between two days, both included */
    int sessionsBetween(LocalDate from, LocalDate to) {
        if (days.isEmpty() || to == null || to.isBefore(from)) {
            return 0;
        }

        return Math.max(0, lastIndexOf(to) - indexOf(from) + 1);
    }

    /** @return the sessions this map is showing */
    List<LocalDate> days() {
        return days;
    }

    /** @return the colour the look and feel gives a disabled label, for hints */
    static Color hintColour() {
        Color colour = UIManager.getColor("Label.disabledForeground");

        return colour == null ? SeriesColors.faint() : colour;
    }
}
