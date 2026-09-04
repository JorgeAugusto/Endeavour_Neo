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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final JComboBox<String> series = new JComboBox<>();

    private final transient Model model = new Model();

    private final JTable table = new JTable(model);

    private final JLabel about = new JLabel(" ");

    private final JLabel warning = new JLabel(" ");

    /** The series being edited, so switching away saves what was typed. */
    private transient String editing;

    /** Its bars, read once, for counting sessions. */
    private transient PriceSeries bars;

    private SeriesWindow(Window owner) {
        super(owner, Messages.get("series.title"), ModalityType.MODELESS);

        for (String each : SeriesCatalog.names()) {
            series.addItem(each);
        }

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
        warning.setForeground(new java.awt.Color(0xB0, 0x6A, 0x2E));

        add(header(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);

        load();

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
        new SeriesWindow(owner).setVisible(true);
    }

    private JComponentPanel header() {
        JComponentPanel panel = new JComponentPanel();
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));

        row.add(new JLabel(Messages.get("series.series")));
        row.add(series);

        panel.add(row);
        panel.add(indented(about));

        return panel;
    }

    private JPanel footer() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));

        JButton add = new JButton(Messages.get("series.add"));
        JButton remove = new JButton(Messages.get("series.remove"));

        add.addActionListener(e -> model.add(suggested()));
        remove.addActionListener(e -> {
            int row = table.getSelectedRow();

            if (row >= 0) {
                model.remove(row);
            }
        });

        buttons.add(add);
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
        return bars == null || bars.size() == 0
                ? LocalDate.now()
                : Instant.ofEpochMilli(bars.timeAt(0)).atZone(ZONE).toLocalDate();
    }

    private void load() {
        editing = String.valueOf(series.getSelectedItem());
        bars = null;

        try {
            bars = SeriesCatalog.open(editing).orElse(null);
        } catch (IOException e) {
            // The counts go blank; the segments are still editable, which is
            // what this window is for.
            bars = null;
        }

        about.setText(bars == null || bars.size() == 0
                ? Messages.get("series.unreadable")
                : Messages.get("series.about", firstDay().format(DAY),
                        lastDay().format(DAY), String.format("%,d", sessionsIn(bars))));

        model.replaceAll(Segmentation.of(editing));
        refreshWarning();
    }

    private LocalDate lastDay() {
        return Instant.ofEpochMilli(bars.timeAt(bars.size() - 1)).atZone(ZONE).toLocalDate();
    }

    private void save() {
        if (editing != null) {
            Segmentation.set(editing, model.segments);
        }
    }

    private void refreshWarning() {
        List<String> clashing = Segmentation.overlapping(model.segments);

        warning.setText(clashing.isEmpty() ? " "
                : Messages.get("series.overlap", String.join(", ", clashing)));
    }

    /** @return how many sessions of the series that segment covers */
    private int sessionsCovered(Segment segment) {
        if (bars == null) {
            return 0;
        }

        LocalDate seen = null;
        int days = 0;

        for (int i = 0; i < bars.size(); i++) {
            LocalDate day = Instant.ofEpochMilli(bars.timeAt(i)).atZone(ZONE).toLocalDate();

            if (day.equals(seen)) {
                continue;
            }

            seen = day;

            if (segment.covers(day)) {
                days++;
            }
        }

        return days;
    }

    private static int sessionsIn(PriceSeries series) {
        LocalDate seen = null;
        int days = 0;

        for (int i = 0; i < series.size(); i++) {
            LocalDate day = Instant.ofEpochMilli(series.timeAt(i)).atZone(ZONE).toLocalDate();

            if (!day.equals(seen)) {
                seen = day;

                days++;
            }
        }

        return days;
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

        @Override
        public boolean isCellEditable(int row, int column) {
            // The count is measured, not typed. An editable number that the
            // program overwrites is a promise it does not keep.
            return column < 3;
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

        @Override
        public void setValueAt(Object value, int row, int column) {
            Segment was = segments.get(row);
            String text = value == null ? "" : value.toString().trim();

            try {
                Segment now = switch (column) {
                    case 0 -> new Segment(text, was.from(), was.to());
                    case 1 -> new Segment(was.name(), LocalDate.parse(text, DAY), was.to());
                    default -> new Segment(was.name(), was.from(),
                            text.isEmpty() || text.equals(Messages.get("series.onwards"))
                                    ? null : LocalDate.parse(text, DAY));
                };

                segments.set(row, now);
            } catch (DateTimeParseException | IllegalArgumentException e) {
                // What was typed is not a segment: a date the calendar does not
                // have, an end before the start, a name of nothing. The old
                // value stays and the table redraws it, which says "no" without
                // a dialog interrupting a row of typing.
                java.awt.Toolkit.getDefaultToolkit().beep();
            }

            fireTableRowsUpdated(row, row);
            refreshWarning();
        }
    }
}
