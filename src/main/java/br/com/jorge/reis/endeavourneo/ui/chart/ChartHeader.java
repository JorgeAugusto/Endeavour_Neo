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

import br.com.jorge.reis.endeavourneo.domain.market.Segment;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Segmentation;
import br.com.jorge.reis.endeavourneo.ui.series.SeriesColors;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * The instrument and the period, inside the chart.
 *
 * <p>The window title says it too, and that is not a duplication worth removing:
 * the title belongs to the window and disappears when the window is maximised
 * into a workspace or when three of them are stacked. This line belongs to the
 * chart and travels with it.</p>
 *
 * <p><b>Double-clicking it changes the period.</b> The same window a digit opens
 * — one way in for people who type and one for people who point, and only one
 * window to learn.</p>
 *
 * <h2>The segment, when there is one to choose</h2>
 *
 * <p>A series that has been cut into segments shows which one is on screen, in
 * that segment's own colour, and clicking it offers the others. The colour is
 * the one the series window paints it in — the same segment has to be the same
 * colour in both places, or the colour is decoration rather than an answer.</p>
 *
 * <p>The chip is absent when the series has no segments. A control that is
 * permanently empty is a control the reader learns to skip, and this line is
 * short enough that whatever sits on it has to have earned the room.</p>
 */
public final class ChartHeader extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final int PADDING = 8;

    /** Width of the chevron that says the chip opens something. */
    private static final int ARROW = 7;

    private final transient ChartCanvas canvas;

    private final transient String name;

    /**
     * How the workspace names this chart, which is what says WHICH segment.
     *
     * <p>Not the label. The label is {@code WINFUT-FULL · Estudos}, made for
     * reading, and the segment cannot be recovered from it once two segments
     * have names that differ only in punctuation. The key is
     * {@code winfull-1m#Estudos}, and it parses.</p>
     */
    private final transient String key;

    /** Told the name of another segment to open; the shell answers it. */
    private transient java.util.function.Consumer<String> onOpen = wanted -> { };

    /**
     * What is actually on screen, when that is not this chart's own series.
     *
     * <p>A replay dropped here plays something else -- another market, another
     * export, bars folded from trades rather than read from the candle file --
     * and the header went on naming the series the chart was OPENED with. It
     * said "winfull-1m" while every bar came from the Profit tape. The one
     * label that exists to say what you are looking at was the one saying
     * something else.</p>
     */
    private transient String showing;

    /** Where the text actually is, so only the text answers the pointer. */
    private final transient Rectangle hot = new Rectangle();

    /** Where the segment chip is, rebuilt on every paint; empty when there is none. */
    private final transient Rectangle chip = new Rectangle();

    public ChartHeader(ChartCanvas canvas, String name, String key) {
        this.canvas = canvas;
        this.name = name;
        this.key = key;

        setOpaque(true);

        // Rebuilt on every hover rather than set once: the series changes under
        // this component -- period, replay, the bricks arriving from the ticks
        // -- and a summary frozen at construction would describe a chart that
        // is no longer on screen.
        setToolTipText(Messages.get("period.hint"));

        Mouse mouse = new Mouse();

        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** @param listener given the name of another segment, or of the series */
    public void onOpen(java.util.function.Consumer<String> listener) {
        this.onOpen = listener == null ? wanted -> { } : listener;
    }

    /**
     * @param source what is on screen now, or null to go back to this chart's
     *               own series
     */
    public void showing(String source) {
        this.showing = source;

        repaint();
    }

    /** @return the name to draw: what is playing, or what the chart holds */
    private String source() {
        return showing == null ? name : showing;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(240, 20);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();

        try {
            g.setColor(ChartColors.background());
            g.fillRect(0, 0, getWidth(), getHeight());

            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Font base = getFont().deriveFont(12f);

            g.setFont(base.deriveFont(Font.BOLD));

            FontMetrics bold = g.getFontMetrics();
            int baseline = (getHeight() + bold.getAscent()) / 2 - 2;

            g.setColor(ChartColors.foreground());
            String source = source();

            g.drawString(source, PADDING, baseline);

            int x = PADDING + bold.stringWidth(source) + 8;

            g.setFont(base);

            FontMetrics plain = g.getFontMetrics();
            String period = canvas.periodLabel();

            // The period dimmer than the instrument: they are read together, and
            // making both the same weight leaves neither leading.
            g.setColor(fade(ChartColors.foreground()));
            g.drawString(period, x, baseline);

            hot.setBounds(PADDING - 3, 0,
                    bold.stringWidth(source) + 8 + plain.stringWidth(period) + 6, getHeight());

            paintChip(g, plain, x + plain.stringWidth(period) + 12, baseline);
        } finally {
            g.dispose();
        }
    }

    /**
     * The segment on screen, in its own colour, with the others behind a click.
     *
     * <p>Nothing at all when the series has no segments, and nothing while a
     * replay has taken the chart over: what is playing is not this series, so
     * offering to switch a segment of it would be offering to leave without
     * saying so.</p>
     */
    private void paintChip(Graphics2D g, FontMetrics metrics, int x, int baseline) {
        chip.setBounds(0, 0, 0, 0);

        List<Segment> segments = offered();

        if (segments.isEmpty()) {
            return;
        }

        Segment mine = Segmentation.segmentIn(key);
        String text = mine == null ? Messages.get("chart.wholeSeries") : mine.name();
        int dot = 8;
        int wide = dot + 5 + metrics.stringWidth(text) + 5 + ARROW;

        g.setColor(colourOf(segments, mine));
        g.fillOval(x, baseline - dot + 1, dot, dot);

        g.setColor(fade(ChartColors.foreground()));
        g.drawString(text, x + dot + 5, baseline);

        paintArrow(g, x + dot + 5 + metrics.stringWidth(text) + 5, baseline - 4);

        chip.setBounds(x - 3, 0, wide + 6, getHeight());
    }

    /**
     * @return what the chip may offer, which is empty when there is no chip
     *
     * <p>Package-visible because it is the DECISION, and the decision is worth
     * a test while the pixels around it are worth a look. A replay showing
     * gives none: what is playing is not this series.</p>
     */
    List<Segment> offered() {
        return showing == null ? Segmentation.of(Segmentation.seriesIn(key)) : List.of();
    }

    /**
     * @return the colour that segment wears everywhere
     *
     * <p>By its POSITION in the series' list, which is how the series window
     * assigns it. Anything else -- hashing the name, say -- would give the same
     * segment two colours in two windows, and the reader would learn that the
     * colour means nothing.</p>
     */
    private static java.awt.Color colourOf(List<Segment> segments, Segment mine) {
        for (int i = 0; i < segments.size(); i++) {
            if (mine != null && segments.get(i).name().equals(mine.name())) {
                return SeriesColors.taken(i);
            }
        }

        // The whole series, which is not one of them.
        return fade(ChartColors.foreground());
    }

    /** A small chevron: the one shape that says "there are others behind this". */
    private static void paintArrow(Graphics2D g, int x, int y) {
        g.setStroke(new java.awt.BasicStroke(1.3f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        g.drawLine(x, y, x + ARROW / 2, y + 4);
        g.drawLine(x + ARROW / 2, y + 4, x + ARROW, y);
    }

    /**
     * Offers the other segments, and the whole series when that is allowed.
     *
     * <p>A locked series is not offered whole. That is what the lock is for,
     * and a menu that listed it and then refused would be teaching the reader
     * that the lock is advisory.</p>
     */
    private void offerSegments() {
        menuFor().show(this, chip.x, getHeight());
    }

    /** Package-visible so a test can read what is on offer without a screen. */
    javax.swing.JPopupMenu menuFor() {
        String series = Segmentation.seriesIn(key);
        List<Segment> segments = offered();
        Segment mine = Segmentation.segmentIn(key);
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();

        if (!Segmentation.segmentsOnly(series)) {
            menu.add(entry(Messages.get("chart.wholeSeries"),
                    fade(ChartColors.foreground()), mine == null, series));
        }

        for (int i = 0; i < segments.size(); i++) {
            Segment each = segments.get(i);

            menu.add(entry(each.name(), SeriesColors.taken(i),
                    mine != null && mine.name().equals(each.name()),
                    Segmentation.nameOf(series, each)));
        }

        return menu;
    }

    private javax.swing.JMenuItem entry(String text, java.awt.Color colour,
                                        boolean current, String opens) {
        javax.swing.JMenuItem item = new javax.swing.JMenuItem(text, new Dot(colour));

        // The one on screen is disabled rather than absent: seeing it in the
        // list, greyed, is what says the list is complete.
        item.setEnabled(!current);
        item.addActionListener(e -> onOpen.accept(opens));

        return item;
    }

    /** The segment's colour, as something a menu item can wear. */
    private record Dot(java.awt.Color colour) implements javax.swing.Icon {

        @Override
        public void paintIcon(java.awt.Component on, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();

            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(colour);
                g.fillOval(x, y, getIconWidth(), getIconHeight());
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 8;
        }

        @Override
        public int getIconHeight() {
            return 8;
        }
    }

    private static java.awt.Color fade(java.awt.Color colour) {
        return new java.awt.Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 165);
    }

    private final class Mouse extends MouseAdapter {

        @Override
        public void mouseMoved(MouseEvent e) {
            boolean overTheName = hot.contains(e.getPoint());
            boolean overTheChip = chip.contains(e.getPoint());

            setCursor(Cursor.getPredefinedCursor(
                    overTheName || overTheChip ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));

            if (overTheChip) {
                setToolTipText(Messages.get("chart.segmentHint"));

                return;
            }

            setToolTipText(overTheName
                    ? SeriesSummary.html(canvas.series(), source(), canvas.periodLabel(),
                            canvas.isFromTicks())
                    : Messages.get("period.hint"));
        }

        @Override
        public void mouseExited(MouseEvent e) {
            setCursor(Cursor.getDefaultCursor());
        }

        @Override
        public void mousePressed(MouseEvent e) {
            // One press, not two. The chip is a chooser, and choosers open on
            // the first press everywhere else in this program; the name beside
            // it needs two because one click there has to keep meaning nothing.
            if (chip.contains(e.getPoint())) {
                offerSegments();
            }
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2 && hot.contains(e.getPoint())) {
                canvas.askForPeriod(SwingUtilities.getWindowAncestor(ChartHeader.this), null);
            }
        }
    }
}
