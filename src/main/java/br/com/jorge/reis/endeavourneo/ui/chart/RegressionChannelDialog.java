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

import br.com.jorge.reis.endeavourneo.platform.Messages;

import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.RegressionChannel;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * The settings for {@link RegressionChannel}, in the tabs the other two
 * indicators use.
 *
 * <h2>Why the appearance tab is the CENTRE's</h2>
 *
 * <p>The bands next door have one pen for both edges and a second for the
 * middle, so their appearance tab is the edges' and a fourth tab carries the
 * middle. Here the edges come in as many pairs as the reader asks for and each
 * pair has its own pen -- so the pens live in the levels table, and what is left
 * for a tab of its own is the line down the middle.</p>
 *
 * <h2>What a level is, in the table</h2>
 *
 * <p>One number and a pen. The number is entered once and draws BOTH edges:
 * typing 2 gives the pair at two deviations above and two below. There is no
 * column for the other side because there is no reading in which a lopsided
 * channel means anything -- the residuals are already measured around a line
 * that carries the trend.</p>
 *
 * <p>The one asymmetry there can be is the {@code EXTREME} criterion's, where
 * each side is its own furthest residual. That one is measured rather than
 * asked for.</p>
 */
public final class RegressionChannelDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient RegressionChannel channel;

    private final JSpinner period;

    private final JComboBox<RegressionChannel.Width> width =
            new JComboBox<>(RegressionChannel.Width.values());

    private final JCheckBox showCentre = new JCheckBox(Messages.get("overlay.lrc.showCentre"));

    private final JComboBox<MovingAverage.Line> centreLine =
            new JComboBox<>(MovingAverage.Line.values());

    private final JSpinner centreThickness;

    private final JButton centreColour = new JButton();

    private final transient Levels model = new Levels();

    private final JTable table = new JTable(model);

    private final JButton add = new JButton(Messages.get("overlay.lrc.add"));

    private final JButton remove = new JButton(Messages.get("overlay.lrc.remove"));

    private final JButton levelColour = new JButton(Messages.get("overlay.lrc.levelColour"));

    private final JCheckBox fill = new JCheckBox(Messages.get("overlay.lrc.fill"));

    private final JButton fillColour = new JButton();

    private final JSlider opacity;

    private final JCheckBox byDirection =
            new JCheckBox(Messages.get("overlay.lrc.byDirection"));

    private final JButton risingColour = new JButton();

    private final JSlider risingOpacity;

    private final JButton fallingColour = new JButton();

    private final JSlider fallingOpacity;

    private final JCheckBox ownPeriod = new JCheckBox(Messages.get("overlay.ma.ownPeriod"));

    private final JButton periodButton = new JButton();


    private transient String periodCode;

    private transient Color chosenCentre;

    private transient Color chosenFill;

    private transient Color chosenRising;

    private transient Color chosenFalling;

    private final transient Forms.Sample centreSample;

    private transient boolean accepted;

    private RegressionChannelDialog(Window owner, RegressionChannel channel) {
        super(owner, ModalityType.APPLICATION_MODAL);

        this.channel = channel;
        this.chosenCentre = channel.centreColour();
        this.chosenFill = channel.chosenFillColour();
        this.periodCode = channel.ownPeriod();

        this.period = new JSpinner(new SpinnerNumberModel(channel.period(), 2,
                RegressionChannel.MOST_BARS, 1));
        this.centreThickness =
                new JSpinner(new SpinnerNumberModel(channel.centreThickness(), 1, 8, 1));
        this.opacity = new JSlider(0, 100, channel.opacity());
        this.risingOpacity = new JSlider(0, 100, channel.risingOpacity());
        this.fallingOpacity = new JSlider(0, 100, channel.fallingOpacity());

        this.chosenRising = channel.risingFill();
        this.chosenFalling = channel.fallingFill();

        this.centreSample = new Forms.Sample(
                () -> chosenCentre == null ? channel.colours().get(0) : chosenCentre,
                () -> (MovingAverage.Line) centreLine.getSelectedItem(),
                () -> (Integer) centreThickness.getValue());

        setTitle(Messages.get("overlay.dialog.title",
                Messages.get(channel.nameKey()) + " [" + channel.period() + "]"));

        width.setSelectedItem(channel.width());
        centreLine.setSelectedItem(channel.centreLine());
        showCentre.setSelected(channel.isCentreShown());
        fill.setSelected(channel.isFilled());

        width.setRenderer(Forms.named("overlay.lrc.width."));
        centreLine.setRenderer(Forms.lineStyles());

        centreColour.addActionListener(e -> pick(false));
        fillColour.addActionListener(e -> pick(true));

        centreLine.addActionListener(e -> centreSample.repaint());
        centreThickness.addChangeListener(e -> centreSample.repaint());
        fill.addActionListener(e -> refreshFill());
        showCentre.addActionListener(e -> refreshCentre());

        risingColour.addActionListener(e -> pickDirection(true));
        fallingColour.addActionListener(e -> pickDirection(false));

        // The two tabs have to agree the moment either of them changes: the
        // plain fill's controls mean nothing while the directional one is on,
        // and a reader who ticked this and then went back to find those still
        // live would reasonably conclude both were being drawn.
        byDirection.setSelected(channel.isFilledByDirection());
        byDirection.addActionListener(e -> {
            refreshFill();
            refreshDirection();
        });

        model.load(channel.deviationLevels());
        paintButtons();

        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab(Messages.get("overlay.tab.parameters"), parameters());
        tabs.addTab(Messages.get("overlay.lrc.tab.levels"), levels());
        tabs.addTab(Messages.get("overlay.tab.appearance"), appearance());
        tabs.addTab(Messages.get("overlay.lrc.tab.direction"), direction());
        tabs.addTab(Messages.get("overlay.tab.period"), period());

        add(tabs, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(Math.max(540, getWidth()), Math.max(380, getHeight())));
        setLocationRelativeTo(owner);
    }

    /**
     * @return the indicator with the changes applied, or null if it was cancelled
     *
     * <p>The SAME object, changed in place, for the reason the other two
     * dialogs do it: the chart holds the one it was given.</p>
     */
    public static RegressionChannel edit(Window owner, RegressionChannel channel) {
        RegressionChannelDialog dialog = new RegressionChannelDialog(owner, channel);

        dialog.setVisible(true);

        return dialog.accepted ? channel : null;
    }

    // ------------------------------------------------------------- the tabs

    private JComponent parameters() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.tab.parameters"));
        Forms.field(panel, 1, Messages.get("overlay.ma.period"), period);
        Forms.field(panel, 2, Messages.get("overlay.lrc.width"), width);

        Forms.group(panel, 3, Messages.get("overlay.lrc.centre"));
        Forms.across(panel, 4, showCentre);

        return panel;
    }

    /** The table of pairs, and the three things that can be done to it. */
    private JComponent levels() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));

        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12));

        table.setRowHeight(Math.max(table.getRowHeight(), 22));
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(Levels.LINE)
                .setCellEditor(new DefaultCellEditor(styles()));
        table.getColumnModel().getColumn(Levels.INK).setCellRenderer(new Swatches());

        table.getSelectionModel().addListSelectionListener(e -> refreshLevelButtons());

        add.addActionListener(e -> {
            model.add();
            refreshLevelButtons();
        });

        remove.addActionListener(e -> {
            model.remove(table.getSelectedRow());
            refreshLevelButtons();
        });

        levelColour.addActionListener(e -> pickLevelColour());

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        row.add(add);
        row.add(remove);
        row.add(levelColour);

        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(row, BorderLayout.SOUTH);

        refreshLevelButtons();

        return panel;
    }

    private JComponent appearance() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.lrc.centre"));
        Forms.field(panel, 1, Messages.get("overlay.ma.style"), centreLine);
        Forms.field(panel, 2, Messages.get("overlay.ma.colour"), centreColour);
        Forms.field(panel, 3, Messages.get("overlay.ma.thickness"), centreThickness);

        Forms.group(panel, 4, Messages.get("overlay.bb.fillGroup"));
        Forms.across(panel, 5, fill);
        Forms.field(panel, 6, Messages.get("overlay.ma.colour"), fillColour);

        opacity.setMajorTickSpacing(25);
        opacity.setPaintTicks(true);
        opacity.setPaintLabels(true);
        Forms.field(panel, 7, Messages.get("overlay.bb.opacity"), opacity);

        Forms.group(panel, 8, Messages.get("overlay.ma.sample"));
        Forms.across(panel, 9, centreSample);

        refreshFill();
        refreshCentre();

        return panel;
    }

    /**
     * The shading that says which way the channel points.
     *
     * <p>Green while the trend rises and red while it falls, or whichever two
     * colours the reader picks -- each with a transparency of its own, because
     * the two are not read the same: a shading that works over a green candle
     * can disappear over a red one.</p>
     *
     * <p>Its own tab rather than two more rows under the plain fill, because it
     * REPLACES that fill rather than adding to it. Two shadings under one
     * heading would read as two things drawn at once.</p>
     */
    private JComponent direction() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.lrc.tab.direction"));
        Forms.across(panel, 1, byDirection);

        Forms.group(panel, 2, Messages.get("overlay.lrc.rising"));
        Forms.field(panel, 3, Messages.get("overlay.ma.colour"), risingColour);

        risingOpacity.setMajorTickSpacing(25);
        risingOpacity.setPaintTicks(true);
        risingOpacity.setPaintLabels(true);
        Forms.field(panel, 4, Messages.get("overlay.bb.opacity"), risingOpacity);

        Forms.group(panel, 5, Messages.get("overlay.lrc.falling"));
        Forms.field(panel, 6, Messages.get("overlay.ma.colour"), fallingColour);

        fallingOpacity.setMajorTickSpacing(25);
        fallingOpacity.setPaintTicks(true);
        fallingOpacity.setPaintLabels(true);
        Forms.field(panel, 7, Messages.get("overlay.bb.opacity"), fallingOpacity);

        refreshDirection();

        return panel;
    }

    private JComponent period() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.tab.period"));
        Forms.across(panel, 1, ownPeriod);

        // Set before the field is laid out: field() freezes the height it
        // measures, and an empty button measures almost nothing.
        ownPeriod.setSelected(periodCode != null);
        refreshPeriod();

        Forms.field(panel, 2, Messages.get("overlay.ma.scale"), periodButton);

        Forms.group(panel, 3, Messages.get("overlay.ma.painting"));

        // TICKING IT ASKS, the way the other two dialogs do. Ticked with no
        // scale chosen, the code stayed null and the indicator went back to
        // following the chart with the box ticked -- the state that says it
        // does not.
        ownPeriod.addActionListener(e -> {
            if (ownPeriod.isSelected() && periodCode == null) {
                askForTheScale();

                if (periodCode == null) {
                    ownPeriod.setSelected(false);
                }
            }

            refreshPeriod();
        });

        periodButton.addActionListener(e -> askForTheScale());

        return panel;
    }

    // ------------------------------------------------------------ the levels

    /**
     * The pairs, one per row.
     *
     * <p>Holds its own list rather than the indicator's: cancelling has to
     * leave the chart exactly as it was, and a model editing the live list
     * would have changed it three keystrokes ago.</p>
     */
    private static final class Levels extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        static final int FACTOR = 0;

        static final int LINE = 1;

        static final int THICKNESS = 2;

        static final int INK = 3;

        private final transient List<RegressionChannel.Level> rows = new ArrayList<>();

        void load(List<RegressionChannel.Level> from) {
            rows.clear();
            rows.addAll(from);
            fireTableDataChanged();
        }

        List<RegressionChannel.Level> rows() {
            return List.copyOf(rows);
        }

        void add() {
            if (rows.size() >= RegressionChannel.MOST_LEVELS) {
                return;
            }

            // One step wider than the widest there is, which is what somebody
            // adding a second pair almost always wants -- and never a duplicate
            // of a row that is already there, which would draw two lines on top
            // of each other and look like one.
            double widest = 0.0;

            for (RegressionChannel.Level each : rows) {
                widest = Math.max(widest, each.factor());
            }

            rows.add(new RegressionChannel.Level(widest == 0.0 ? 2.0 : widest + 0.5));
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        void remove(int row) {
            if (row < 0 || row >= rows.size()) {
                return;
            }

            rows.remove(row);
            fireTableRowsDeleted(row, row);
        }

        void recolour(int row, Color ink) {
            if (row < 0 || row >= rows.size()) {
                return;
            }

            RegressionChannel.Level was = rows.get(row);

            rows.set(row, new RegressionChannel.Level(
                    was.factor(), ink, was.line(), was.thickness()));

            fireTableRowsUpdated(row, row);
        }

        Color colourOf(int row) {
            return row < 0 || row >= rows.size() ? null : rows.get(row).colour();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case FACTOR -> Messages.get("overlay.lrc.column.factor");
                case LINE -> Messages.get("overlay.ma.style");
                case THICKNESS -> Messages.get("overlay.ma.thickness");
                default -> Messages.get("overlay.ma.colour");
            };
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return switch (column) {
                case FACTOR -> Double.class;
                case THICKNESS -> Integer.class;
                case LINE -> MovingAverage.Line.class;
                default -> String.class;
            };
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column != INK;
        }

        @Override
        public Object getValueAt(int row, int column) {
            RegressionChannel.Level level = rows.get(row);

            return switch (column) {
                case FACTOR -> level.factor();
                case LINE -> level.line();
                case THICKNESS -> level.thickness();
                default -> level.colour() == null
                        ? Messages.get("overlay.ma.automatic")
                        : Messages.get("overlay.ma.chosen");
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            RegressionChannel.Level was = rows.get(row);

            // Through the record's own constructor, which holds each field
            // inside its range -- so a factor typed as 400 comes back as ten
            // rather than being refused with a dialog.
            RegressionChannel.Level now = switch (column) {
                case FACTOR -> new RegressionChannel.Level(
                        value instanceof Number number ? number.doubleValue() : was.factor(),
                        was.colour(), was.line(), was.thickness());
                case LINE -> new RegressionChannel.Level(was.factor(), was.colour(),
                        value instanceof MovingAverage.Line line ? line : was.line(),
                        was.thickness());
                case THICKNESS -> new RegressionChannel.Level(was.factor(), was.colour(),
                        was.line(),
                        value instanceof Number number ? number.intValue() : was.thickness());
                default -> was;
            };

            rows.set(row, now);
            fireTableRowsUpdated(row, row);
        }
    }

    /** Draws the level's own colour beside the word for it. */
    private final class Swatches extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable on, Object value,
                boolean selected, boolean focused, int row, int column) {

            super.getTableCellRendererComponent(on, value, selected, focused, row, column);

            Color ink = model.colourOf(row);

            setIcon(new Forms.Swatch(ink == null ? edgeColour() : ink));

            return this;
        }
    }

    /** @return the colour a level with none of its own is drawn in */
    private Color edgeColour() {
        List<Color> lines = channel.colours();

        return lines.size() > 1 ? lines.get(1) : lines.get(0);
    }

    private JComboBox<MovingAverage.Line> styles() {
        JComboBox<MovingAverage.Line> box = new JComboBox<>(MovingAverage.Line.values());

        box.setRenderer(Forms.lineStyles());

        return box;
    }

    private void pickLevelColour() {
        int row = table.getSelectedRow();

        if (row < 0) {
            return;
        }

        Color current = model.colourOf(row);
        Color picked = JColorChooser.showDialog(this, Messages.get("overlay.ma.colour"),
                current == null ? edgeColour() : current);

        if (picked != null) {
            model.recolour(row, picked);
        }
    }

    private void refreshLevelButtons() {
        boolean chosen = table.getSelectedRow() >= 0;

        remove.setEnabled(chosen);
        levelColour.setEnabled(chosen);
        add.setEnabled(model.getRowCount() < RegressionChannel.MOST_LEVELS);
    }

    // ------------------------------------------------------------ the pieces

    /** Opens the scale chooser, and keeps what it answers. */
    private void askForTheScale() {
        PeriodCatalog.Choice choice = PeriodDialog.ask(this, null);

        if (choice != null) {
            periodCode = choice.code();

            refreshPeriod();
        }
    }

    private void refreshPeriod() {
        boolean own = ownPeriod.isSelected();

        periodButton.setEnabled(own);
        periodButton.setText(periodCode == null
                ? Messages.get("overlay.ma.chooseScale") : periodCode);
    }

    /**
     * The shading's colour and its transparency mean nothing while it is off --
     * nor while the directional shading has taken it over.
     */
    private void refreshFill() {
        boolean on = fill.isSelected() && !byDirection.isSelected();

        fill.setEnabled(!byDirection.isSelected());
        fillColour.setEnabled(on);
        opacity.setEnabled(on);

        fill.setToolTipText(byDirection.isSelected()
                ? Messages.get("overlay.lrc.byDirection.wins") : null);
    }

    /** And the two directional colours mean nothing while that is off. */
    private void refreshDirection() {
        boolean on = byDirection.isSelected();

        risingColour.setEnabled(on);
        risingOpacity.setEnabled(on);
        fallingColour.setEnabled(on);
        fallingOpacity.setEnabled(on);
    }

    /** Nor does the centre's pen while the centre is hidden. */
    private void refreshCentre() {
        boolean on = showCentre.isSelected();

        centreLine.setEnabled(on);
        centreColour.setEnabled(on);
        centreThickness.setEnabled(on);
    }

    private JComponent buttons() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));

        JButton cancel = new JButton(Messages.get("dialog.cancel"));
        JButton ok = new JButton(Messages.get("dialog.ok"));

        cancel.addActionListener(e -> dispose());
        ok.addActionListener(e -> {
            apply();

            accepted = true;

            dispose();
        });

        row.add(cancel);
        row.add(ok);
        getRootPane().setDefaultButton(ok);

        return row;
    }

    private void apply() {
        // The cell being typed in has not been committed yet, and OK is a
        // button: pressing it takes the focus and the editor is asked to stop
        // only if somebody asks. Without this, the last number typed is the one
        // that never arrives.
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }

        channel.setPeriod((Integer) period.getValue());
        channel.setWidth((RegressionChannel.Width) width.getSelectedItem());
        channel.setDeviationLevels(model.rows());
        channel.setCentreShown(showCentre.isSelected());
        channel.setCentreLine((MovingAverage.Line) centreLine.getSelectedItem());
        channel.setCentreThickness((Integer) centreThickness.getValue());
        channel.setCentreColour(chosenCentre);
        channel.setFilled(fill.isSelected());
        channel.setFillColour(chosenFill);
        channel.setOpacity(opacity.getValue());
        channel.setFilledByDirection(byDirection.isSelected());
        channel.setRisingFill(chosenRising);
        channel.setRisingOpacity(risingOpacity.getValue());
        channel.setFallingFill(chosenFalling);
        channel.setFallingOpacity(fallingOpacity.getValue());
        channel.setOwnPeriod(ownPeriod.isSelected() ? periodCode : null);
    }

    private void pick(boolean shading) {
        Color current = shading ? chosenFill : chosenCentre;
        Color fallback = shading ? edgeColour() : channel.colours().get(0);
        Color picked = JColorChooser.showDialog(this, Messages.get("overlay.ma.colour"),
                current == null ? fallback : current);

        if (picked == null) {
            return;
        }

        if (shading) {
            chosenFill = picked;
        } else {
            chosenCentre = picked;
        }

        paintButtons();
        centreSample.repaint();
    }

    /** Opens the chooser for one of the two directional colours. */
    private void pickDirection(boolean up) {
        Color current = up ? chosenRising : chosenFalling;
        Color picked = JColorChooser.showDialog(this,
                Messages.get(up ? "overlay.lrc.rising" : "overlay.lrc.falling"), current);

        if (picked == null) {
            return;
        }

        if (up) {
            chosenRising = picked;
        } else {
            chosenFalling = picked;
        }

        paintButtons();
    }

    private void paintButtons() {
        paintButton(centreColour, chosenCentre, channel.colours().get(0));
        paintButton(fillColour, chosenFill, edgeColour());

        // Never "automatic": these two have no colour to fall back to. What
        // they are is what was picked, so the swatch is the whole answer and
        // the word beside it would be the same on both for ever.
        risingColour.setIcon(new Forms.Swatch(chosenRising));
        risingColour.setText(Messages.get("overlay.ma.chosen"));
        risingColour.setHorizontalAlignment(SwingConstants.LEFT);

        fallingColour.setIcon(new Forms.Swatch(chosenFalling));
        fallingColour.setText(Messages.get("overlay.ma.chosen"));
        fallingColour.setHorizontalAlignment(SwingConstants.LEFT);
    }

    private static void paintButton(JButton button, Color chosen, Color fallback) {
        button.setText(chosen == null
                ? Messages.get("overlay.ma.automatic")
                : Messages.get("overlay.ma.chosen"));
        button.setIcon(new Forms.Swatch(chosen == null ? fallback : chosen));
        button.setHorizontalAlignment(SwingConstants.LEFT);
    }
}
