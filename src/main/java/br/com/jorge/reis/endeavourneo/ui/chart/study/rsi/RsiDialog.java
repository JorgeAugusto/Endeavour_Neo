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
package br.com.jorge.reis.endeavourneo.ui.chart.study.rsi;

import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.ui.chart.Forms;
import br.com.jorge.reis.endeavourneo.ui.chart.LinePen;
import br.com.jorge.reis.endeavourneo.ui.chart.PeriodCatalog;
import br.com.jorge.reis.endeavourneo.ui.chart.PeriodDialog;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
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
 * The RSI's settings, in the three tabs the reference product gives it.
 *
 * <p><b>Parameters</b> is the period and the kind of averaging.
 * <b>Appearance</b> is the one line it draws. <b>Instrument and period</b> is
 * the scale it is computed on, and whether the line is sloped between the
 * points of that scale.</p>
 *
 * <p>Three tabs for six settings is more furniture than they need, and it is
 * still right: the reader arrives here from another program where this
 * indicator has exactly these tabs with exactly these names, and a dialog that
 * rearranged them to be tidier would cost more in hunting than it saved in
 * space.</p>
 *
 * <h2>No overbought and oversold lines</h2>
 *
 * <p>The reference product offers none for this indicator, so neither does
 * this. The scale strip still writes the ends, and when the RSI shares a pane
 * with a stochastic — which is what its fixed nought-to-a-hundred range is
 * for — the stochastic's own twenty and eighty are already drawn across it.</p>
 */
public final class RsiDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient RelativeStrength study;

    private final JSpinner period;

    private final JComboBox<RelativeStrength.Smoothing> smoothing =
            new JComboBox<>(RelativeStrength.Smoothing.values());

    private final JCheckBox ownPeriod = new JCheckBox(Messages.get("overlay.ma.ownPeriod"));

    private final JButton periodButton = new JButton();

    private final JCheckBox interpolate =
            new JCheckBox(Messages.get("overlay.ma.interpolate"));

    private transient String periodCode;

    private final transient LinePen pen;

    private transient boolean accepted;

    private RsiDialog(Window owner, RelativeStrength study) {
        // THROUGH overlay.dialog.title, like the other two. Four dialogs of
        // the same kind, and two of them wore the "Indicators >" prefix while
        // these two did not -- so opening the moving average and then the RSI
        // changed the shape of the title bar for no reason the reader could
        // name.
        super(owner, Messages.get("overlay.dialog.title",
                Messages.get("study.rsi") + " [" + study.period() + "]"),
                ModalityType.APPLICATION_MODAL);

        this.study = study;

        period = new JSpinner(new SpinnerNumberModel(study.period(), 1, 2_000, 1));

        smoothing.setRenderer(Forms.named("study.rsi.smoothing."));
        smoothing.setSelectedItem(study.smoothing());

        periodCode = study.ownPeriod();
        ownPeriod.setSelected(periodCode != null);
        interpolate.setSelected(study.isInterpolated());

        pen = new LinePen(this, study.line(), study.colour(), study.width());

        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab(Messages.get("study.tab.parameters"), parameters());
        tabs.addTab(Messages.get("study.tab.appearance"), appearance());
        tabs.addTab(Messages.get("study.tab.scale"), scale());

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
    public static boolean edit(Window owner, RelativeStrength study) {
        RsiDialog dialog = new RsiDialog(owner, study);

        dialog.setVisible(true);

        return dialog.accepted;
    }

    // ---------------------------------------------------------------- tab one

    private JPanel parameters() {
        JPanel panel = Forms.form();
        int row = 0;

        Forms.group(panel, row++, Messages.get("study.tab.parameters"));
        Forms.field(panel, row++, Messages.get("overlay.ma.period"), period);
        Forms.field(panel, row++, Messages.get("study.rsi.smoothing"), smoothing);

        return panel;
    }

    // ---------------------------------------------------------------- tab two

    private JPanel appearance() {
        JPanel panel = Forms.form();

        pen.addTo(panel, 0, Messages.get("study.rsi.line"));

        return panel;
    }

    // -------------------------------------------------------------- tab three

    private JPanel scale() {
        JPanel panel = Forms.form();
        int row = 0;

        // The scale it is computed on: the last CLOSED bar of the larger
        // scale, never the one containing this one. See OwnScale, where the
        // rule and the reason for it live.
        Forms.group(panel, row++, Messages.get("overlay.ma.scale"));
        Forms.across(panel, row++, ownPeriod);
        Forms.field(panel, row++, Messages.get("overlay.ma.scale"), periodButton);

        Forms.group(panel, row++, Messages.get("overlay.ma.painting"));
        Forms.across(panel, row++, interpolate);

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

    private void refreshEnabled() {
        periodButton.setEnabled(ownPeriod.isSelected());
        periodButton.setText(periodCode == null
                ? Messages.get("overlay.ma.chooseScale") : periodCode);

        // Sloping between closed points only means anything when there ARE
        // points of another scale to slope between.
        interpolate.setEnabled(ownPeriod.isSelected());
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
        study.setSmoothing((RelativeStrength.Smoothing) smoothing.getSelectedItem());
        study.setOwnPeriod(ownPeriod.isSelected() ? periodCode : null);
        study.setInterpolated(interpolate.isSelected());

        study.setLine(pen.line());
        study.setColour(pen.colour());
        study.setWidth(pen.width());
    }
}
