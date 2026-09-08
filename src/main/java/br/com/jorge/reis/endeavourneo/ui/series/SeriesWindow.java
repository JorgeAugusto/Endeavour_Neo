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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Segment;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Segmentation;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

/**
 * Where a series is divided into segments.
 *
 * <h2>What this is not</h2>
 *
 * <p>It refuses nothing. A segment says what a stretch of the series is FOR,
 * and the whole value of saying it is that the reader can then choose
 * deliberately. A window that blocked a chart or a run would be pretending to
 * enforce a discipline that lives in the reader's head — and a guard with holes
 * is worse than no guard, because it gets trusted.</p>
 *
 * <h2>What it shows that the settings file cannot</h2>
 *
 * <p>How many sessions each segment actually covers, counted from the series,
 * and whether two of them overlap. Dates typed into a form look reasonable and
 * turn out to hold four hundred sessions or four; nothing but counting says
 * which.</p>
 */
public final class SeriesWindow extends JDialog {

    private static final long serialVersionUID = 1L;

    /** Shared with the segment dialog, so a date never reads two ways in one window. */
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern(br.com.jorge.reis.endeavourneo.platform.Formats.DATE);


    private final JComboBox<String> series = new JComboBox<>();

    private final transient Model model = new Model();

    private final JTable table = new JTable(model);

    private final JLabel about = new JLabel(" ");

    private final JLabel warning = new JLabel(" ");

    /**
     * The lock: this series may only be used through its segments.
     *
     * <p>Off by default, and it has to be: a series arrives with no segments at
     * all, and a lock that came on by itself would make every new series
     * unopenable until somebody worked out why.</p>
     */
    private final javax.swing.JCheckBox segmentsOnly =
            new javax.swing.JCheckBox(Messages.get("series.segmentsOnly"));

    /** The series being edited, so switching away saves what was typed. */
    private transient String editing;

    /**
     * Whether anything was actually changed since this series was loaded.
     *
     * <p>See {@link #save()}: without it, looking was a write.</p>
     */
    private transient boolean edited;

    /**
     * The days it holds, read once.
     *
     * <p><b>Days and not bars.</b> Everything this window asks -- where the
     * data starts, where it ends, how many sessions a segment covers -- is
     * answered by the list of days, and a tick source can produce that list
     * from a directory listing while a bar series has to be read for it. Asking
     * in days is what lets the two sit in the same combo box. See {@link
     * Segmentable}.</p>
     */
    private transient java.util.NavigableSet<java.time.LocalDate> days =
            new java.util.TreeSet<>();

    /**
     * Told whenever the segments on disk change.
     *
     * <p>The tree lists them, and a tree built once at start-up shows what was
     * true at start-up: editing a segment here left the navigator describing
     * the old one until the application was restarted. That was reported, and
     * it is the kind of wrong that teaches a reader to distrust the window they
     * are looking at.</p>
     */
    private transient Runnable onChanged = () -> { };

    /**
     * Package-visible so a test can build one without putting it on screen.
     *
     * <p>{@link #open(Window)} is the way in for everything else, and it is
     * where the one-window rule lives: two of these editing the same series
     * would each write their own whole list of segments over the other's.</p>
     */
    SeriesWindow(Window owner) {
        super(owner, Messages.get("series.title"), ModalityType.MODELESS);

        // Series AND tick sources. The tape is a series of the same market at
        // a finer resolution, and "which stretch of it am I allowed to look at"
        // is the same question there as it is over minutes.
        for (String each : Segmentable.keys()) {
            series.addItem(each);
        }

        series.setRenderer(new javax.swing.DefaultListCellRenderer() {

            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
                    Object value, int index, boolean chosen, boolean focused) {
                return super.getListCellRendererComponent(list,
                        value == null ? null : Segmentable.labelOf(String.valueOf(value)),
                        index, chosen, focused);
            }
        });

        series.addActionListener(e -> {
            save();
            load();
        });

        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);

        about.setFont(about.getFont().deriveFont(about.getFont().getSize2D() - 1f));
        warning.setFont(warning.getFont().deriveFont(Font.BOLD,
                warning.getFont().getSize2D() - 1f));
        // FROM THE THEME. It was this orange written into the code, in a
        // window whose every other colour comes from SeriesColors -- which has
        // clash() for exactly this, and darkens it under the night theme.
        warning.setForeground(SeriesColors.clash());

        add(header(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);

        load();

        // DISPOSED, not hidden. JDialog defaults to HIDE_ON_CLOSE, so a window
        // shut with the X stayed in Window.getWindows() for the life of the
        // program -- and applying a theme walks every window there, calling
        // updateComponentTreeUI on each. A long session made changing the theme
        // progressively slower, over dozens of invisible windows.
        //
        // The windowClosing below still runs, and still runs first.
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        setSize(560, 360);
        setMinimumSize(new Dimension(460, 260));
        setLocationRelativeTo(owner);

        addWindowListener(new java.awt.event.WindowAdapter() {

            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                // Saved on the way out, not on an OK button. Everything in this
                // window is an edit to something that already exists, and a
                // dialog that can lose what was typed is one the reader learns
                // to distrust.
                save();
            }
        });
    }

    public static void open(Window owner) {
        open(owner, () -> { });
    }

    /**
     * The one that is open, if one is.
     *
     * <p>This window is modeless, so nothing stopped a second one being opened
     * over the same series -- and Segmentation.set REPLACES a series' whole list
     * of segments. Two windows, two lists, and whichever was closed last wrote
     * its own over the other's: a segment created in one simply stopped existing
     * when the other went away, with nothing said. Editing the same thing in two
     * places is not a feature anybody asked for.</p>
     */
    private static SeriesWindow open;

    /** @param whenChanged run after every write, so a listing elsewhere can follow */
    public static void open(Window owner, Runnable whenChanged) {
        if (open != null && open.isDisplayable()) {
            // Fronted rather than opened again. The reader asked to see this
            // window; they already have it.
            open.onChanged = whenChanged == null ? () -> { } : whenChanged;

            open.setVisible(true);
            open.toFront();
            open.requestFocus();

            return;
        }

        SeriesWindow window = new SeriesWindow(owner);

        window.onChanged = whenChanged == null ? () -> { } : whenChanged;

        open = window;

        window.setVisible(true);
    }

    /** @return whether a series window is on screen; for the test that says only one is */
    static boolean isOpen() {
        return open != null && open.isDisplayable();
    }

    private JComponentPanel header() {
        JComponentPanel panel = new JComponentPanel();
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));

        row.add(new JLabel(Messages.get("series.series")));
        row.add(series);

        segmentsOnly.setToolTipText(Messages.get("series.segmentsOnly.hint"));
        segmentsOnly.addActionListener(e -> {
            Segmentation.setSegmentsOnly(editing, segmentsOnly.isSelected());
            refreshWarning();

            // The lock decides whether the series is openable at all, so the
            // tree is wrong the moment this is ticked and not a moment later.
            onChanged.run();
        });

        JPanel lock = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

        lock.add(segmentsOnly);

        panel.add(row);
        panel.add(indented(about));
        panel.add(lock);

        return panel;
    }

    private JPanel footer() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));

        JButton add = new JButton(Messages.get("series.add"));
        JButton show = new JButton(Messages.get("series.show"));
        JButton edit = new JButton(Messages.get("series.edit"));
        JButton remove = new JButton(Messages.get("series.remove"));

        // Nothing selected, nothing to edit or remove. A button that is always
        // enabled and sometimes does nothing teaches the reader to distrust
        // every button beside it.
        show.setEnabled(false);
        edit.setEnabled(false);
        remove.setEnabled(false);

        table.getSelectionModel().addListSelectionListener(e -> {
            boolean picked = table.getSelectedRow() >= 0;

            // AND ONLY WHEN THERE IS SOMETHING TO SHOW. Viewing and editing
            // both open a dialog that places a range inside the sessions, and
            // openSelected returns in silence when there are none -- which is
            // the case this window treats explicitly, with series.unreadable on
            // the label. The comment three lines above says why that matters:
            // "a button that is always enabled and sometimes does nothing
            // teaches the reader to distrust every button beside it".
            //
            // Remove is not in that list: removing a segment from a series that
            // will not read is still removing a segment.
            show.setEnabled(picked && !days.isEmpty());
            edit.setEnabled(picked && !days.isEmpty());
            remove.setEnabled(picked);
        });

        show.addActionListener(e -> openSelected(true));
        edit.addActionListener(e -> openSelected(false));

        // The row itself, too. Double click opens is what the tree does and
        // what every list in every IDE does, and a table that only responds to
        // a button reads as a table that responds to nothing.
        table.addMouseListener(new java.awt.event.MouseAdapter() {

            @Override
            public void mouseClicked(java.awt.event.MouseEvent clicked) {
                if (clicked.getClickCount() == 2) {
                    openSelected(false);
                }
            }
        });

        add.addActionListener(e -> {
            // The dialog needs the sessions to place anything, so with an
            // unreadable series the old behaviour stands: a row appears and is
            // typed into. Refusing to add a segment because a FILE will not
            // open would be the window losing a job it can still do.
            if (editing == null) {
                // No series at all. Nothing to divide, and the dialog would be
                // asked to place a range inside a series that is not there.
                return;
            }

            if (days.isEmpty()) {
                // AND MARKED EDITED. This row is only written by the save
                // on the way out, and that save now asks whether anything
                // was changed -- so without this the one add the window can
                // still do with an unreadable series would be lost on
                // closing, silently.
                model.add(suggested());
                edited = true;

                return;
            }

            SegmentDialog.ask(this, editing, days,
                            List.copyOf(model.segments), suggested())
                    .ifPresent(segment -> {
                        model.add(segment);
                        edited = true;
                        save();
                    });
        });
        remove.addActionListener(e -> removeSelected());

        buttons.add(add);
        buttons.add(show);
        buttons.add(edit);
        buttons.add(remove);

        panel.add(indented(warning), BorderLayout.NORTH);
        panel.add(buttons, BorderLayout.CENTER);

        return panel;
    }

    private static JPanel indented(JLabel label) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));

        row.add(label);

        return row;
    }

    /**
     * Opens the chosen segment in the window it was made in.
     *
     * <p>Every OTHER segment goes along, and this one does not: a segment
     * always overlaps itself, and a window that opened saying "choca com
     * Testes" while editing Testes would be right and useless.</p>
     */
    private void openSelected(boolean onlyLooking) {
        int row = table.getSelectedRow();

        if (row < 0 || days.isEmpty()) {
            return;
        }

        List<Segment> others = new java.util.ArrayList<>(model.segments);
        Segment chosen = others.remove(row);

        if (onlyLooking) {
            SegmentDialog.view(this, editing, days, others, chosen);

            return;
        }

        SegmentDialog.revise(this, editing, days, others, chosen)
                .ifPresent(segment -> {
                    model.replace(row, segment);
                    edited = true;
                    save();
                });
    }

    /**
     * Removes the chosen segment, after asking.
     *
     * <p><b>Asked, like everything this application deletes.</b> A segment is
     * two dates and a name, so losing one is not a catastrophe -- but the
     * button sits beside two that open a window, the list has no undo, and a
     * misfire here is silent. The question costs a keystroke; noticing the loss
     * a week later costs the segment.</p>
     */
    private void removeSelected() {
        int row = table.getSelectedRow();

        if (row < 0) {
            return;
        }

        Segment chosen = model.segments.get(row);
        int answer = javax.swing.JOptionPane.showConfirmDialog(this,
                Messages.get("series.removeAsk", chosen.name()),
                Messages.get("series.removeTitle"),
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE);

        if (answer == javax.swing.JOptionPane.YES_OPTION) {
            model.remove(row);
            edited = true;
            save();
        }
    }

    /**
     * @return a segment starting where the last one ends
     *
     * <p>A new segment nearly always continues the one before it, and typing
     * the same date twice is the kind of small friction that makes a window
     * feel like paperwork.</p>
     */
    private Segment suggested() {
        List<Segment> current = model.segments;
        LocalDate first = firstDay();

        if (!current.isEmpty()) {
            Segment last = current.get(current.size() - 1);

            first = last.to() == null ? last.from().plusDays(1) : last.to().plusDays(1);
        }

        return Segment.from(Messages.get("series.newName"), first);
    }

    private LocalDate firstDay() {
        return days.isEmpty() ? LocalDate.now() : days.first();
    }

    /**
     * Shows the series at once, and reads its days behind the window.
     *
     * <p><b>The days used to be read right here.</b> For a tick source that is
     * a directory listing and costs nothing; for a bar series it is the whole
     * file -- {@code SeriesCatalog.open} says it plainly, "six years of
     * one-minute bars is 39 MB" -- and then a walk over every bar to find where
     * each session breaks. On the interface thread, in the constructor, and
     * again on every move of the combo. Opening <i>Tools -> Series</i> froze the
     * whole application until the read finished, and switching series froze it
     * again: no cursor, no footer, nothing to say what it was doing. The house
     * rule is written thirty files away in {@code MainWindow}: "the interface
     * thread is the one thread that may not spend them".</p>
     *
     * <p>Everything except the days comes from the settings file, which is
     * cheap, so the segments are listed and editable from the first frame. The
     * days fill in the two things that need them -- the range under the combo
     * and the sessions column -- when they arrive.</p>
     */
    private void load() {
        // NULL AND NOT "null". String.valueOf turns an empty combo into the
        // four-letter string, which is not null and so passed every guard: on a
        // machine with no data folder -- a state the navigator treats explicitly
        // -- this wrote keys of the form segments.null.* into the reader's
        // workspace, where they stayed for good, and the window headed itself
        // with the count of a series called "null".
        Object picked = series.getSelectedItem();
        String key = picked == null ? null : String.valueOf(picked);

        editing = key;
        edited = false;

        model.replaceAll(key == null ? List.of() : Segmentation.of(key));
        segmentsOnly.setSelected(key != null && Segmentation.segmentsOnly(key));
        segmentsOnly.setEnabled(key != null);
        refreshWarning();

        // Empty until the answer comes back, and SAYING so. The counts read
        // zero meanwhile, and a zero that means "not yet" has to look different
        // from a zero that means "none", or the reader believes it.
        days = new java.util.TreeSet<>();

        about.setText(Messages.get("series.reading"));

        readDays(key);
    }

    /**
     * Reads one key's days off the interface thread and hands them back to it.
     *
     * <p>The answer is dropped when the combo has moved on: a series that takes
     * seconds to read finishes after the reader has already chosen another one,
     * and posting it then would label the new series with the old one's dates.
     * Later readers do not cancel earlier ones -- they only outlive them.</p>
     */
    private void readDays(String key) {
        new javax.swing.SwingWorker<java.util.NavigableSet<LocalDate>, Void>() {

            @Override
            protected java.util.NavigableSet<LocalDate> doInBackground() {
                return Segmentable.sessionsOf(key);
            }

            @Override
            protected void done() {
                // Objects.equals, because the key is null when there is no
                // series at all -- which is the state a machine with an empty
                // catalogue OPENS in. The guard that exists to drop a stale
                // answer threw a NullPointerException on the interface thread
                // instead of guarding: swallowed by the event loop, printed to
                // the console, and the window left saying "reading..." for
                // ever. It fired on every run of the suite.
                if (!java.util.Objects.equals(key, editing)) {
                    return;
                }

                try {
                    days = get();
                } catch (java.util.concurrent.ExecutionException e) {
                    // Same answer the read itself gives for a file that is
                    // gone: no map, and the segments still editable.
                    days = new java.util.TreeSet<>();
                } catch (InterruptedException e) {
                    days = new java.util.TreeSet<>();

                    Thread.currentThread().interrupt();
                }

                about.setText(days.isEmpty()
                        ? Messages.get("series.unreadable")
                        : Messages.get("series.about", days.first().format(DAY),
                                days.last().format(DAY), String.format("%,d", days.size())));

                // The sessions column is drawn from days, and nothing else told
                // the table they had changed.
                model.countsChanged();
            }
        }.execute();
    }

    /** @return what the window says about the series it is showing; for the test of the read */
    String about() {
        return about.getText();
    }

    /**
     * Writes the segments back, IF any were edited.
     *
     * <p><b>The guard is not an optimisation.</b> This runs when the window is
     * closed and on every change of the series combo, whether or not anything
     * was touched -- and what it writes is what {@code Segmentation.of} handed
     * over, which quietly drops any entry with a date it cannot read. Since
     * {@code set} clears the series's keys before writing, the cycle read ->
     * drop -> write back made a loss that was only a reading into a loss on
     * disk. Opening Tools -> Series, looking, and closing was enough.</p>
     *
     * <p>And the workspace is meant to be edited by hand -- that is the stated
     * reason it is plain text -- so a badly typed date is the ordinary case,
     * not a strange one.</p>
     */
    private void save() {
        if (editing != null && edited) {
            Segmentation.set(editing, model.segments);

            edited = false;

            onChanged.run();
        }
    }

    /** @return the series being edited, or null when there is none; for a test */
    String editingSeries() {
        return editing;
    }

    private void refreshWarning() {
        List<String> clashing = Segmentation.overlapping(model.segments);

        if (!clashing.isEmpty()) {
            warning.setText(Messages.get("series.overlap", String.join(", ", clashing)));

            return;
        }

        // A locked series with nothing to unlock is a series nobody can open,
        // and the reader who did it will not connect the two by themselves --
        // the chart simply refuses, somewhere else, later.
        warning.setText(segmentsOnly.isSelected() && model.segments.isEmpty()
                ? Messages.get("series.lockedEmpty") : " ");
    }

    /**
     * @return how many sessions of the series that segment covers
     *
     * <p>A walk over a set of days, not over the bars: the set is already
     * one entry per session, so this is a subset and a size rather than four
     * years of minutes read again for every row of the table.</p>
     */
    private int sessionsCovered(Segment segment) {
        if (days.isEmpty()) {
            return 0;
        }

        java.time.LocalDate last = segment.to() == null ? days.last() : segment.to();

        if (last.isBefore(segment.from())) {
            return 0;
        }

        return days.subSet(segment.from(), true, last, true).size();
    }

    /** A panel that stacks its rows, which is all the header needs. */
    private static final class JComponentPanel extends JPanel {

        private static final long serialVersionUID = 1L;

        JComponentPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        }

        @Override
        public java.awt.Component add(java.awt.Component what) {
            super.add(what);
            super.add(Box.createVerticalStrut(2));

            return what;
        }
    }

    private final class Model extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private final transient List<Segment> segments = new ArrayList<>();

        void replaceAll(List<Segment> found) {
            segments.clear();
            segments.addAll(found);
            fireTableDataChanged();
        }

        /** The days arrived; only the sessions column moves, but it moves everywhere. */
        void countsChanged() {
            fireTableDataChanged();
        }

        void add(Segment segment) {
            segments.add(segment);
            fireTableRowsInserted(segments.size() - 1, segments.size() - 1);
            refreshWarning();
        }

        void remove(int row) {
            segments.remove(row);
            fireTableRowsDeleted(row, row);
            refreshWarning();
        }

        @Override
        public int getRowCount() {
            return segments.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> Messages.get("series.column.name");
                case 1 -> Messages.get("series.column.from");
                case 2 -> Messages.get("series.column.to");
                default -> Messages.get("series.column.sessions");
            };
        }

        /**
         * @return false, always
         *
         * <p><b>This table lists; it does not edit.</b> Typing a date into a
         * cell asks the reader to know what is free without showing it, which
         * is the whole reason the segment window exists -- and it let a typo
         * become a segment that overlaps another one, discovered later by a
         * warning nobody was looking at.</p>
         *
         * <p>Editing goes through that window now, by the button or by a double
         * click on the row.</p>
         */
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        void replace(int row, Segment segment) {
            segments.set(row, segment);
            fireTableRowsUpdated(row, row);
            refreshWarning();
        }

        @Override
        public Object getValueAt(int row, int column) {
            Segment segment = segments.get(row);

            return switch (column) {
                case 0 -> segment.name();
                case 1 -> segment.from().format(DAY);
                case 2 -> segment.to() == null
                        ? Messages.get("series.onwards") : segment.to().format(DAY);
                default -> String.format("%,d", sessionsCovered(segment));
            };
        }

    }
}
