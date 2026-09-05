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
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.ui.replay.DatePicker;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Where a segment is cut out of a series.
 *
 * <h2>The shape of it</h2>
 *
 * <p>A map of the whole series with what is already taken drawn inside it, and
 * under it a range with two handles. Between them they answer the two questions
 * this window exists for -- what is left, and how much of it am I taking -- and
 * they answer them in the same units, because they share a scale.</p>
 *
 * <p>Everything below is the same answer said again in words: the two dates,
 * the count of years and months and days, and one sentence. <b>Three ways of
 * saying it is not repetition here</b>: the handles are how you choose, the
 * dates are how you check, and the sentence is what you will remember when you
 * come back to this segment in a month.</p>
 *
 * <h2>Why a dialog and not another row in the table</h2>
 *
 * <p>The table stays, and it stays the place where segments are compared and
 * corrected -- four of them side by side is what it is good at. What it was bad
 * at was the moment of CREATION, where the reader has to know what is still
 * free, and a grid of dates does not say that. This says nothing else.</p>
 */
public final class SegmentDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient List<LocalDate> days;

    private final transient List<Segment> existing;

    private final SeriesMap map = new SeriesMap();

    private final RangeBar range = new RangeBar();

    private final JTextField name = new JTextField(14);

    private final DatePicker from;

    private final DatePicker to;

    private final JLabel sentence = new JLabel(" ");

    private final JLabel[] counts = new JLabel[SUMMARY.length];

    private final JButton create = new JButton(Messages.get("segment.create"));

    /** What was chosen, or null when the window was closed without choosing. */
    private transient Segment chosen;

    /** Set while the dates are being written FROM the handles, and the reverse. */
    private transient boolean echoing;

    /**
     * The cells of the summary, left to right.
     *
     * <p>The last one is the share of the whole series, and it is the one that
     * gets used: nobody remembers that a training set was 1.120 sessions, they
     * remember that it was three quarters. Reading it off two numbers is
     * arithmetic done while choosing, which is when arithmetic is most
     * expensive.</p>
     */
    private static final String[] SUMMARY = {
        "segment.years", "segment.months", "segment.days",
        "segment.calendar", "segment.sessions", "segment.share"
    };

    private SegmentDialog(Window owner, String series, List<LocalDate> sessions,
                          List<Segment> segments, Segment start, boolean editing) {
        super(owner, Messages.get(editing ? "segment.editTitle" : "segment.title"),
                Dialog.ModalityType.APPLICATION_MODAL);

        create.setText(Messages.get(editing ? "segment.save" : "segment.create"));

        this.days = sessions;
        this.existing = segments;

        NavigableSet<LocalDate> playable = new TreeSet<>(sessions);

        from = new DatePicker(start.from());
        to = new DatePicker(start.isOpenEnded() ? sessions.get(sessions.size() - 1)
                : start.to());

        from.setSessions(playable);
        to.setSessions(playable);

        name.setText(start.name());

        map.showSeries(sessions, segments);
        range.setRange(sessions.size(), indexOf(start.from()),
                indexOf(start.isOpenEnded()
                        ? sessions.get(sessions.size() - 1) : start.to()));

        range.onChange(this::followHandles);
        from.onChange(this::followDates);
        to.onChange(this::followDates);

        setLayout(new BorderLayout());
        add(body(series), BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        followHandles();

        pack();
        setMinimumSize(new Dimension(620, getHeight()));
        setLocationRelativeTo(owner);
    }

    /**
     * @param start the segment the window opens on, which is what the caller
     *              would have created without being asked
     * @return the segment chosen, or empty when the window was dismissed
     */
    public static java.util.Optional<Segment> ask(Window owner, String series,
                                                  NavigableSet<LocalDate> sessions,
                                                  List<Segment> segments, Segment start) {
        return show(owner, series, sessions, segments, start, false);
    }

    /**
     * The same window, opened on a segment that already exists.
     *
     * <p>The same window on purpose. A segment is chosen against what is around
     * it -- what is taken, what is left, how much of the series it is -- and
     * that is as true the second time as the first. A separate "edit" form
     * would be the same questions asked worse.</p>
     *
     * @param others every OTHER segment, so the one being edited does not clash
     *               with itself
     */
    public static java.util.Optional<Segment> revise(Window owner, String series,
                                                     NavigableSet<LocalDate> sessions,
                                                     List<Segment> others, Segment start) {
        return show(owner, series, sessions, others, start, true);
    }

    private static java.util.Optional<Segment> show(Window owner, String series,
                                                    NavigableSet<LocalDate> sessions,
                                                    List<Segment> segments, Segment start,
                                                    boolean editing) {
        if (sessions == null || sessions.isEmpty()) {
            return java.util.Optional.empty();
        }

        SegmentDialog dialog = new SegmentDialog(owner, series,
                new ArrayList<>(sessions), new ArrayList<>(segments), start, editing);

        dialog.setVisible(true);

        return java.util.Optional.ofNullable(dialog.chosen);
    }

    // ---------------------------------------------------------------- layout

    /**
     * One column, everything flush left, everything as wide as the window.
     *
     * <p><b>Not a BoxLayout, and that was the defect.</b> A vertical BoxLayout
     * places its children by their own alignmentX, so a column that mixes
     * defaults -- a label set to the left, a panel left at the centre -- comes
     * out with each row starting somewhere different, which is exactly how it
     * looked: title centred, fields centred, and the second date picker pushed
     * onto a line of its own and clipped.</p>
     *
     * <p>A single-column {@code GridBagLayout} has no such rule. Every row
     * fills the width it is given and starts where the row above starts,
     * because that is the only thing it knows how to do.</p>
     */
    private JPanel body(String series) {
        JPanel panel = new JPanel(new GridBagLayout());

        panel.setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 14));

        stack(panel, headline(series), 12);
        stack(panel, track(Messages.get("segment.map")), 2);
        stack(panel, map, 12);
        stack(panel, track(Messages.get("segment.range")), 2);
        stack(panel, range, 10);
        stack(panel, fields(), 12);
        stack(panel, summary(), 8);
        stack(panel, sentence, 0);

        return panel;
    }

    /**
     * @param below how much room to leave under this row
     *
     * <p>The gap belongs to the row above it rather than to a strut between
     * them: a strut is another child with another alignment to get wrong, and
     * this way the spacing is written where it is read.</p>
     */
    private static void stack(JPanel column, Component what, int below) {
        GridBagConstraints where = new GridBagConstraints();

        where.gridx = 0;
        where.gridy = column.getComponentCount();
        where.weightx = 1.0;
        where.fill = GridBagConstraints.HORIZONTAL;
        where.anchor = GridBagConstraints.WEST;
        where.insets = new Insets(0, 0, below, 0);

        column.add(what, where);
    }

    private JLabel headline(String series) {
        JLabel label = new JLabel(Messages.get("segment.series", series,
                String.format("%,d", days.size()),
                days.get(0).format(SeriesWindow.DAY),
                days.get(days.size() - 1).format(SeriesWindow.DAY)));

        label.setFont(label.getFont().deriveFont(Font.BOLD));

        return label;
    }

    private static JLabel track(String text) {
        JLabel label = new JLabel(text);

        label.setFont(label.getFont().deriveFont(label.getFont().getSize2D() - 2f));
        label.setForeground(SeriesMap.hintColour());

        return label;
    }

    /**
     * Name, then the two dates, on one line.
     *
     * <p>A {@link FlowLayout} would wrap this line the moment the window got
     * narrow, and wrapping put half of it under the row below -- which is what
     * the second date picker did. Here the NAME is the only thing that gives
     * ground: it is the field that can be short without becoming useless, and a
     * date picker that shrinks stops showing a date.</p>
     */
    private JPanel fields() {
        JPanel row = new JPanel(new GridBagLayout());
        GridBagConstraints where = new GridBagConstraints();

        where.anchor = GridBagConstraints.WEST;
        where.insets = new Insets(0, 0, 0, 6);

        row.add(new JLabel(Messages.get("segment.name")), where);

        where.gridx = 1;
        where.weightx = 1.0;
        where.fill = GridBagConstraints.HORIZONTAL;
        where.insets = new Insets(0, 0, 0, 18);
        row.add(name, where);

        where.gridx = 2;
        where.weightx = 0.0;
        where.fill = GridBagConstraints.NONE;
        where.insets = new Insets(0, 0, 0, 6);
        row.add(new JLabel(Messages.get("segment.from")), where);

        where.gridx = 3;
        where.insets = new Insets(0, 0, 0, 18);
        row.add(from, where);

        where.gridx = 4;
        where.insets = new Insets(0, 0, 0, 6);
        row.add(new JLabel(Messages.get("segment.to")), where);

        where.gridx = 5;
        where.insets = new Insets(0, 0, 0, 0);
        row.add(to, where);

        return row;
    }

    /**
     * Five cells of equal width, each a word and a number.
     *
     * <p>Divided by a rule between them rather than by a gap: a gap in the
     * panel's own colour is invisible against the panel, which is what the
     * first version drew.</p>
     */
    private JPanel summary() {
        JPanel row = new JPanel(new GridBagLayout());

        row.setBorder(BorderFactory.createLineBorder(SeriesColors.rule()));

        for (int i = 0; i < SUMMARY.length; i++) {
            JPanel cell = new JPanel(new GridBagLayout());

            cell.setOpaque(false);
            cell.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, i == 0 ? 0 : 1, 0, 0,
                            SeriesColors.rule()),
                    BorderFactory.createEmptyBorder(5, 9, 6, 9)));

            JLabel title = new JLabel(Messages.get(SUMMARY[i]));

            title.setFont(title.getFont().deriveFont(title.getFont().getSize2D() - 3f));
            title.setForeground(SeriesMap.hintColour());

            counts[i] = new JLabel("0");
            counts[i].setFont(counts[i].getFont().deriveFont(Font.BOLD,
                    counts[i].getFont().getSize2D() + 2f));

            GridBagConstraints inside = new GridBagConstraints();

            inside.gridx = 0;
            inside.gridy = 0;
            inside.weightx = 1.0;
            inside.fill = GridBagConstraints.HORIZONTAL;
            inside.anchor = GridBagConstraints.WEST;
            cell.add(title, inside);

            inside.gridy = 1;
            cell.add(counts[i], inside);

            GridBagConstraints where = new GridBagConstraints();

            where.gridx = i;
            where.weightx = 1.0;
            where.fill = GridBagConstraints.BOTH;
            row.add(cell, where);
        }

        return row;
    }

    private JPanel buttons() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        JButton cancel = new JButton(Messages.get("segment.cancel"));

        cancel.addActionListener(e -> dispose());
        create.addActionListener(e -> {
            chosen = current();

            dispose();
        });

        getRootPane().setDefaultButton(create);

        row.add(cancel);
        row.add(create);

        return row;
    }

    // ------------------------------------------------------------ the wiring

    private int indexOf(LocalDate day) {
        int at = java.util.Collections.binarySearch(days, day);

        return at >= 0 ? at : Math.min(days.size() - 1, Math.max(0, -at - 1));
    }

    /** The handles moved: write the dates, then say what it all means. */
    private void followHandles() {
        if (echoing) {
            return;
        }

        echoing = true;

        try {
            from.setDate(days.get(range.from()));
            to.setDate(days.get(range.to()));
        } finally {
            echoing = false;
        }

        describe();
    }

    /** A date was typed or picked: move the handles to the nearest session. */
    private void followDates() {
        if (echoing) {
            return;
        }

        LocalDate first = from.date();
        LocalDate last = to.date();

        if (first == null || last == null) {
            return;
        }

        echoing = true;

        try {
            range.setRange(days.size(), indexOf(first), indexOf(last));
        } finally {
            echoing = false;
        }

        describe();
    }

    /**
     * Says the same thing in the three places that say it.
     *
     * <p>Called from both directions, which is why {@link #echoing} exists: the
     * handles write the dates and the dates move the handles, and without the
     * guard the first keystroke starts a conversation between them that does
     * not end.</p>
     */
    private void describe() {
        Segment now = current();
        List<String> hit = existing.stream()
                .filter(each -> overlaps(now, each))
                .map(Segment::name)
                .collect(Collectors.toList());

        boolean clash = !hit.isEmpty();

        map.showFresh(now, clash);
        range.setClashing(clash);
        create.setEnabled(!clash);

        Period spread = Period.between(now.from(), now.to());
        long calendar = ChronoUnit.DAYS.between(now.from(), now.to()) + 1;
        int sessions = map.sessionsBetween(now.from(), now.to());

        counts[0].setText(String.valueOf(spread.getYears()));
        counts[1].setText(String.valueOf(spread.getMonths()));
        counts[2].setText(String.valueOf(spread.getDays()));
        counts[3].setText(String.format("%,d", calendar));
        counts[4].setText(String.format("%,d", sessions));
        counts[5].setText(share(sessions));

        if (clash) {
            sentence.setForeground(SeriesColors.clash());
            sentence.setText(Messages.get("segment.clash", String.join(", ", hit)));

            return;
        }

        sentence.setForeground(SeriesMap.hintColour());
        sentence.setText(Messages.get("segment.sentence",
                now.from().format(SeriesWindow.DAY), now.to().format(SeriesWindow.DAY),
                String.format("%,d", sessions), share(sessions)));
    }

    /**
     * @param sessions how many the segment holds
     * @return its share of the whole series
     *
     * <p>To one decimal, and no further. The second one would move while the
     * handle stood still -- a session is a fifteenth of a percent here -- and a
     * number that trembles is a number nobody trusts.</p>
     */
    private String share(int sessions) {
        if (days.isEmpty()) {
            return "0,0%";
        }

        return String.format("%.1f%%", 100.0 * sessions / days.size());
    }

    private Segment current() {
        String chosenName = name.getText().isBlank()
                ? Messages.get("series.newName") : name.getText().trim();

        return new Segment(chosenName, days.get(range.from()), days.get(range.to()));
    }

    private static boolean overlaps(Segment a, Segment b) {
        LocalDate aEnd = a.isOpenEnded() ? LocalDate.MAX : a.to();
        LocalDate bEnd = b.isOpenEnded() ? LocalDate.MAX : b.to();

        return !a.from().isAfter(bEnd) && !b.from().isAfter(aEnd);
    }
}
