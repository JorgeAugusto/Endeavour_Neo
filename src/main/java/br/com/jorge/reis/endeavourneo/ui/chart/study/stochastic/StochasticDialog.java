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
package br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic;

import br.com.jorge.reis.endeavourneo.ui.chart.Forms;
import br.com.jorge.reis.endeavourneo.ui.chart.LinePen;
import br.com.jorge.reis.endeavourneo.ui.chart.PeriodCatalog;
import br.com.jorge.reis.endeavourneo.ui.chart.PeriodDialog;

import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Window;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;

/**
 * The stochastic's settings: what is computed, then what it looks like.
 *
 * <h2>Two tabs, like the reference product's</h2>
 *
 * <p>Every other indicator dialog here is one panel, and that works while an
 * indicator has four settings. This one has fifteen: two periods, a kind, three
 * switches, two levels and four lines each with a style, a colour and a
 * thickness. In one panel they become a wall, and the wall hides the two
 * numbers anybody actually changes.</p>
 *
 * <p>The split is not by size, though — it is by question. <b>Parameters decide
 * what is true; appearance decides what it looks like.</b> A reader who came to
 * change the period never opens the second tab, and a reader who came to make
 * two stochastics tell each other apart never reads the first.</p>
 */
public final class StochasticDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient SlowStochastic study;

    // ------------------------------------------------------------ parameters

    private final JSpinner period;

    private final JSpinner average;

    private final JComboBox<MovingAverage.Kind> kind =
            new JComboBox<>(MovingAverage.Kind.values());

    private final JCheckBox showAverage =
            new JCheckBox(Messages.get("study.stochastic.showAverage"));

    private final JCheckBox showLevels =
            new JCheckBox(Messages.get("study.stochastic.showLevels"));

    private final JSpinner buy;

    private final JSpinner sell;

    private final JCheckBox ownPeriod = new JCheckBox(Messages.get("overlay.ma.ownPeriod"));

    private final JButton periodButton = new JButton();

    private transient String periodCode;

    // ------------------------------------------------------------ appearance

    private final transient LinePen main;

    private final transient LinePen signal;

    private final transient LinePen levels;

    private transient boolean accepted;

    private StochasticDialog(Window owner, SlowStochastic study) {
        // See RsiDialog: the same prefix the other two dialogs already use.
        super(owner, Messages.get("overlay.dialog.title",
                Messages.get("study.stochastic") + " [" + study.period() + "]"),
                ModalityType.APPLICATION_MODAL);

        this.study = study;

        period = new JSpinner(new SpinnerNumberModel(study.period(), 1, 2_000, 1));
        average = new JSpinner(new SpinnerNumberModel(study.average(), 1, 2_000, 1));
        buy = new JSpinner(new SpinnerNumberModel(study.buyLevel(), 0.0, 100.0, 1.0));
        sell = new JSpinner(new SpinnerNumberModel(study.sellLevel(), 0.0, 100.0, 1.0));

        kind.setRenderer(Forms.named("overlay.ma.kind."));
        kind.setSelectedItem(study.kind());
        showAverage.setSelected(study.showsAverage());
        showLevels.setSelected(study.showsLevels());
        periodCode = study.ownPeriod();
        ownPeriod.setSelected(periodCode != null);

        main = new LinePen(this, study.line(), study.colour(), study.width());
        signal = new LinePen(this, study.averageLine(), study.averageColour(),
                study.averageWidth());
        levels = new LinePen(this, study.levelLine(), study.levelColour(),
                study.levelWidth());

        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab(Messages.get("study.tab.parameters"), parameters());
        tabs.addTab(Messages.get("study.tab.appearance"), appearance());

        setLayout(new BorderLayout());
        add(tabs, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        refreshEnabled();
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * @return whether anything was changed
     *
     * <p>The study is written to in place rather than replaced, because a pane
     * is already holding it and a copy would leave the pane drawing the old
     * one.</p>
     */
    public static boolean edit(Window owner, SlowStochastic study) {
        StochasticDialog dialog = new StochasticDialog(owner, study);

        dialog.setVisible(true);

        return dialog.accepted;
    }

    // ---------------------------------------------------------------- tab one

    private JPanel parameters() {
        JPanel panel = Forms.form();
        int row = 0;

        Forms.group(panel, row++, Messages.get("study.tab.parameters"));
        Forms.field(panel, row++, Messages.get("overlay.ma.period"), period);

        Forms.group(panel, row++, Messages.get("study.stochastic.average"));
        Forms.field(panel, row++, Messages.get("overlay.ma.period"), average);
        Forms.field(panel, row++, Messages.get("overlay.ma.kind"), kind);
        Forms.across(panel, row++, showAverage);

        Forms.group(panel, row++, Messages.get("study.stochastic.levels"));
        Forms.across(panel, row++, showLevels);
        Forms.field(panel, row++, Messages.get("study.stochastic.buy"), buy);
        Forms.field(panel, row++, Messages.get("study.stochastic.sell"), sell);

        // The scale it is computed on, like every other indicator here: the
        // last CLOSED bar of the larger scale, never the one containing this
        // one. See OwnScale.
        Forms.group(panel, row++, Messages.get("overlay.ma.scale"));
        Forms.across(panel, row++, ownPeriod);
        Forms.field(panel, row++, Messages.get("overlay.ma.scale"), periodButton);

        showAverage.addActionListener(e -> refreshEnabled());
        showLevels.addActionListener(e -> refreshEnabled());
        ownPeriod.addActionListener(e -> refreshEnabled());
        periodButton.addActionListener(e -> pickScale());

        return panel;
    }

    private void pickScale() {
        PeriodCatalog.Choice choice = PeriodDialog.ask(this, periodCode);

        if (choice != null) {
            periodCode = choice.code();

            refreshEnabled();
        }
    }

    /**
     * Turns off what the boxes above have switched off.
     *
     * <p>The period of the average is NOT among them, and used to be set
     * enabled here unconditionally -- a line that does nothing, since a field
     * is enabled to begin with, and that read as though it did. It looked like
     * a leftover from when the field followed the "show the average" box.</p>
     *
     * <p>It cannot follow that box: the setting decides BOTH lines. The
     * javadoc of SlowStochastic says so -- "that average is the indicator:
     * this is what slow means" -- so hiding the second line does not stop the
     * number mattering.</p>
     */
    private void refreshEnabled() {
        buy.setEnabled(showLevels.isSelected());
        sell.setEnabled(showLevels.isSelected());
        periodButton.setEnabled(ownPeriod.isSelected());
        periodButton.setText(periodCode == null
                ? Messages.get("overlay.ma.chooseScale") : periodCode);

        signal.setEnabled(showAverage.isSelected());
        levels.setEnabled(showLevels.isSelected());
    }

    // ---------------------------------------------------------------- tab two

    private JPanel appearance() {
        JPanel panel = Forms.form();
        int row = 0;

        row = main.addTo(panel, row, Messages.get("study.stochastic.line"));
        row = signal.addTo(panel, row, Messages.get("study.stochastic.averageLine"));
        levels.addTo(panel, row, Messages.get("study.stochastic.levelLine"));

        return panel;
    }

    // ---------------------------------------------------------------- the end

    private JPanel buttons() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        JButton cancel = new JButton(Messages.get("dialog.cancel"));
        JButton ok = new JButton(Messages.get("dialog.ok"));

        cancel.addActionListener(e -> dispose());
        ok.addActionListener(e -> {
            apply();

            accepted = true;

            dispose();
        });

        getRootPane().setDefaultButton(ok);

        row.add(cancel);
        row.add(ok);

        return row;
    }

    private void apply() {
        study.setPeriod((Integer) period.getValue());
        study.setAverage((Integer) average.getValue());
        study.setKind((MovingAverage.Kind) kind.getSelectedItem());
        study.setShowsAverage(showAverage.isSelected());
        study.setShowsLevels(showLevels.isSelected());
        study.setBuyLevel(((Number) buy.getValue()).doubleValue());
        study.setSellLevel(((Number) sell.getValue()).doubleValue());
        study.setOwnPeriod(ownPeriod.isSelected() ? periodCode : null);

        study.setLine(main.line());
        study.setColour(main.colour());
        study.setWidth(main.width());

        study.setAverageLine(signal.line());
        study.setAverageColour(signal.colour());
        study.setAverageWidth(signal.width());

        study.setLevelLine(levels.line());
        study.setLevelWidth(levels.width());

        // One colour for both levels. They are a pair -- twenty and eighty say
        // the same kind of thing at opposite ends -- and two colours for them
        // would be two more decisions for no more meaning. This used to say it
        // in two calls, into two fields, of which only one was ever saved.
        study.setLevelColour(levels.colour());
    }
}
