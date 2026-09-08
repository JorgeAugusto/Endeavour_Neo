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

    /** Whether this window only shows. Nothing in it moves, and nothing is saved. */
    private final transient boolean readOnly;

    /**
     * What the window is for.
     *
     * <p>Three uses of one window, and the differences between them are two
     * words and a switch: what it is called, what its button says, and whether
     * anything can be moved. Three windows would have been three chances for
     * the map to disagree with itself.</p>
     */
    private enum Mode {

        CREATE("segment.title", "segment.create"),
        EDIT("segment.editTitle", "segment.save"),
        VIEW("segment.viewTitle", "segment.close");

        private final String title;

        private final String button;

        Mode(String title, String button) {
            this.title = title;
            this.button = button;
        }
    }

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

    /**
     * @param sessions the days the series has
     * @param start what the fields open on
     * @return one of these, built but never shown
     *
     * <p>For a test. {@link #ask} is the way in for everything else and it is
     * modal, so a test that went through it would block until somebody pressed
     * a button -- and what is worth checking is what the fields say after a
     * date is typed into them.</p>
     */
    static SegmentDialog forTest(List<LocalDate> sessions, Segment start) {
        return new SegmentDialog(null, "test-1m", sessions, new ArrayList<>(),
                start, Mode.CREATE);
    }

    private SegmentDialog(Window owner, String series, List<LocalDate> sessions,
                          List<Segment> segments, Segment start, Mode mode) {
        super(owner, Messages.get(mode.title), Dialog.ModalityType.APPLICATION_MODAL);

        this.readOnly = mode == Mode.VIEW;

        create.setText(Messages.get(mode.button));

        this.days = sessions;
        this.existing = segments;
        this.openEnded = start.isOpenEnded();

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

        if (readOnly) {
            // Shown, not offered. Everything still reads -- the map, the count,
            // the sentence -- and nothing takes a hand: a window that looks
            // editable and silently discards what was typed is worse than one
            // that plainly does not edit.
            name.setEditable(false);
            from.setEnabled(false);
            to.setEnabled(false);
            range.setEnabled(false);
            range.setFocusable(false);
        }

        // DISPOSED, not hidden. See SeriesWindow: JDialog defaults to
        // HIDE_ON_CLOSE, and a window shut with the X stays in
        // Window.getWindows() for the life of the program, where every change
        // of theme walks it again.
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

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
        return show(owner, series, sessions, segments, start, Mode.CREATE);
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
        return show(owner, series, sessions, others, start, Mode.EDIT);
    }

    /**
     * The same window again, with nothing that moves.
     *
     * <p>For looking. A segment is read the same way it is chosen -- against
     * what is around it -- so the answer to "how is this one set up" is this
     * picture, not a row of dates in a table.</p>
     */
    public static void view(Window owner, String series,
                            NavigableSet<LocalDate> sessions,
                            List<Segment> others, Segment shown) {
        show(owner, series, sessions, others, shown, Mode.VIEW);
    }

    private static java.util.Optional<Segment> show(Window owner, String series,
                                                    NavigableSet<LocalDate> sessions,
                                                    List<Segment> segments, Segment start,
                                                    Mode mode) {
        if (sessions == null || sessions.isEmpty()) {
            return java.util.Optional.empty();
        }

        SegmentDialog dialog = new SegmentDialog(owner, series,
                new ArrayList<>(sessions), new ArrayList<>(segments), start, mode);

        dialog.setVisible(true);

        return java.util.Optional.ofNullable(dialog.chosen);
    }

    /** @return the field where the start is typed; for a test */
    DatePicker fromField() {
        return from;
    }

    /** @return the field where the end is typed; for a test */
    DatePicker toField() {
        return to;
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
     * One cell of equal width per entry in SUMMARY, each a word and a number.
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
            if (!readOnly) {
                chosen = current();
            }

            dispose();
        });

        if (readOnly) {
            // One button, and it closes. A "Cancelar" beside a "Fechar" would
            // ask the reader which of the two does nothing.
            //
            // No second listener: the one registered above already calls
            // dispose() on every path. Adding another was harmless -- dispose
            // is idempotent -- and it read as though the first did not cover
            // this case.
            getRootPane().setDefaultButton(create);
            row.add(create);

            return row;
        }

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

        int wantedFrom = indexOf(first);
        int wantedTo = indexOf(last);

        echoing = true;

        try {
            range.setRange(days.size(), wantedFrom, wantedTo);
        } finally {
            echoing = false;
        }

        // AND BACK INTO THE FIELDS when the bar refused what it was asked for.
        // RangeBar collapses an inverted range -- an end before the start gives
        // to = from -- and nothing wrote that back, so the field the reader had
        // just typed into went on showing the date they typed while the segment
        // being built was one session long. The sentence and the counters showed
        // the collapsed value, because those read the range; current() reads the
        // range too, so the field was the only thing saying what would not
        // happen.
        //
        // ONLY when the indices differ, and not on every keystroke: a date that
        // merely falls on a day the market was shut is snapped by indexOf and
        // the bar takes it as asked, so nothing is rewritten under the pointer
        // while somebody is still typing.
        //
        // POSTED, not written here. This runs inside the date field's own
        // document notification, and Swing refuses a document change from
        // within one -- "Attempt to mutate in notification". followHandles does
        // not have the problem because it is called from the bar.
        if (range.from() != wantedFrom || range.to() != wantedTo) {
            LocalDate showFrom = days.get(range.from());
            LocalDate showTo = days.get(range.to());

            javax.swing.SwingUtilities.invokeLater(() -> {
                echoing = true;

                try {
                    from.setDate(showFrom);
                    to.setDate(showTo);
                } finally {
                    echoing = false;
                }

                describe();
            });
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
        create.setEnabled(!clash || readOnly);

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
            // Through the same format as the line below, not "0,0%" written out:
            // the comma is the separator of pt_BR, and the bundle's base
            // language is English. The one case that never went through the
            // formatter was the one that hard-coded a locale.
            return String.format("%.1f%%", 0.0);
        }

        return String.format("%.1f%%", 100.0 * sessions / days.size());
    }

    /**
     * Whether the segment arrived with no end.
     *
     * <p>The handle has to be parked somewhere, so an open segment comes in with
     * its end on the last session known -- and there is no way to tell that
     * apart from a reader who chose the last session, unless the answer is
     * remembered from before the dialog opened.</p>
     */
    private final boolean openEnded;

    /**
     * @return the segment as the handles now stand
     *
     * <p><b>An open segment that was not moved stays open.</b> One with no end
     * -- shown as "em diante" in the table -- comes into this dialog with its
     * end handle parked on the last session known, because a handle has to be
     * somewhere. Building a two-date segment from that CLOSED it: opening
     * <i>Edit</i>, changing nothing and pressing OK turned "from here on" into
     * "up to the last session that happened to be on disk", and the only sign
     * was the table showing a date where it used to show two words.</p>
     *
     * <p>The difference is real further down. A chart of an open segment reads
     * to the end of the file; a closed one stops at a fixed instant, so the same
     * segment now hides every session imported afterwards -- and the whole point
     * of a segment kept for testing is that it goes on being the part that was
     * not looked at.</p>
     *
     * <p>Moving the end handle off the last session closes it, which is the
     * reader saying so.</p>
     */
    private Segment current() {
        String chosenName = name.getText().isBlank()
                ? Messages.get("series.newName") : name.getText().trim();

        return new Segment(chosenName, days.get(range.from()),
                endFor(openEnded, range.to(), days));
    }

    /**
     * @param arrivedOpen whether the segment had no end when the dialog opened
     * @param handle where the end handle sits now
     * @param sessions the days the handles run over
     * @return the end to store, or null to leave it open
     *
     * <p>Package-visible and static because it is a RULE, and a rule can be
     * asked without a window.</p>
     */
    static LocalDate endFor(boolean arrivedOpen, int handle, List<LocalDate> sessions) {
        return arrivedOpen && handle == sessions.size() - 1 ? null : sessions.get(handle);
    }

    private static boolean overlaps(Segment a, Segment b) {
        LocalDate aEnd = a.isOpenEnded() ? LocalDate.MAX : a.to();
        LocalDate bEnd = b.isOpenEnded() ? LocalDate.MAX : b.to();

        return !a.from().isAfter(bEnd) && !b.from().isAfter(aEnd);
    }
}
