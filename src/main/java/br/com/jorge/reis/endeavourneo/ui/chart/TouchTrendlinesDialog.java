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
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.TopsAndBottoms;
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.TouchTrendlines;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;

/**
 * The settings for {@link TouchTrendlines}.
 *
 * <p>Three tabs. The parameters tab carries what decides the two lines: how far
 * back to look, what counts as a turn, and the three numbers of the tolerance
 * ladder. The anchoring tab chooses where each line STARTS, which is the one
 * decision that changes the drawing more than any appearance ever will. The
 * appearance tab dresses each line and offers the three things drawn beside
 * them -- the rails, the markers and the memory -- as switches, because each of
 * them is an explanation of the fit rather than the fit, and a chart with four
 * indicators on it can do without three extra dotted lines per indicator.</p>
 *
 * <p><b>Controls that do nothing in the chosen mode are disabled, not
 * hidden.</b> Three of the four modes fix the anchor, and with it fixed the
 * ladder's ceiling and step stop choosing anything -- a spinner that still
 * turns while deciding nothing is how a setting comes to be believed in.</p>
 */
public final class TouchTrendlinesDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient TouchTrendlines trendlines;

    private final JSpinner period;

    private final JSpinner wing;

    private final JSpinner from;

    private final JSpinner until;

    private final JSpinner step;

    private final JComboBox<TopsAndBottoms.Ties> ties =
            new JComboBox<>(TopsAndBottoms.Ties.values());

    private final JComboBox<MovingAverage.Line> line =
            new JComboBox<>(MovingAverage.Line.values());

    private final JComboBox<TouchTrendlines.Anchoring> anchoring =
            new JComboBox<>(TouchTrendlines.Anchoring.values());

    private final JComboBox<TouchTrendlines.Crossing> crossing =
            new JComboBox<>(TouchTrendlines.Crossing.values());

    private final JSpinner fastPeriod;

    private final JSpinner slowPeriod;

    private final JButton slowScale = new JButton();

    private transient String scaleCode;

    private final JSpinner thickness;

    private final JButton supportColour = new JButton();

    private final JButton resistanceColour = new JButton();

    private final JCheckBox rails = new JCheckBox(Messages.get("overlay.lt.rails"));

    private final JCheckBox touches = new JCheckBox(Messages.get("overlay.lt.touches"));

    private final JCheckBox memory = new JCheckBox(Messages.get("overlay.lt.memory"));

    private transient Color chosenSupport;

    private transient Color chosenResistance;

    private final transient Forms.Sample supportSample;

    private final transient Forms.Sample resistanceSample;

    private transient boolean accepted;

    private TouchTrendlinesDialog(Window owner, TouchTrendlines trendlines) {
        super(owner, ModalityType.APPLICATION_MODAL);

        this.trendlines = trendlines;
        this.chosenSupport = trendlines.chosenSupportColour();
        this.chosenResistance = trendlines.chosenResistanceColour();

        this.period = new JSpinner(new SpinnerNumberModel(trendlines.period(), 3,
                TouchTrendlines.MOST_BARS, 5));
        this.wing = new JSpinner(new SpinnerNumberModel(trendlines.wing(), 1,
                TopsAndBottoms.MOST_WING, 1));
        this.from = new JSpinner(new SpinnerNumberModel(trendlines.toleranceFrom(), 1, 50, 1));
        this.until = new JSpinner(new SpinnerNumberModel(trendlines.toleranceTo(), 1, 50, 1));
        this.step = new JSpinner(new SpinnerNumberModel(trendlines.toleranceStep(), 1, 25, 1));
        this.fastPeriod = new JSpinner(
                new SpinnerNumberModel(trendlines.fastPeriod(), 2, 2_000, 1));
        this.slowPeriod = new JSpinner(
                new SpinnerNumberModel(trendlines.slowPeriod(), 2, 2_000, 1));
        this.scaleCode = trendlines.slowScale();

        this.thickness = new JSpinner(new SpinnerNumberModel(trendlines.thickness(), 1, 8, 1));

        this.supportSample = new Forms.Sample(
                () -> chosenSupport == null ? trendlines.colours().get(0) : chosenSupport,
                () -> (MovingAverage.Line) line.getSelectedItem(),
                () -> (Integer) thickness.getValue());
        this.resistanceSample = new Forms.Sample(
                () -> chosenResistance == null ? trendlines.colours().get(1) : chosenResistance,
                () -> (MovingAverage.Line) line.getSelectedItem(),
                () -> (Integer) thickness.getValue());

        setTitle(Messages.get("overlay.dialog.title",
                Messages.get(trendlines.nameKey()) + " [" + trendlines.period() + "]"));

        ties.setSelectedItem(trendlines.ties());
        line.setSelectedItem(trendlines.line());
        anchoring.setSelectedItem(trendlines.anchoring());
        crossing.setSelectedItem(trendlines.crossing());
        rails.setSelected(trendlines.hasRails());
        touches.setSelected(trendlines.marksTouches());
        memory.setSelected(trendlines.hasMemory());

        ties.setRenderer(Forms.named("overlay.pivots.ties."));
        line.setRenderer(Forms.lineStyles());
        anchoring.setRenderer(Forms.named("overlay.lt.anchoring."));
        crossing.setRenderer(Forms.named("overlay.lt.crossing."));

        anchoring.addActionListener(e -> refreshAnchoring());
        slowScale.addActionListener(e -> askForTheScale());

        supportColour.addActionListener(e -> pick(true));
        resistanceColour.addActionListener(e -> pick(false));

        line.addActionListener(e -> repaintSamples());
        thickness.addChangeListener(e -> repaintSamples());

        paintButtons();

        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab(Messages.get("overlay.tab.parameters"), parameters());
        tabs.addTab(Messages.get("overlay.lt.tab.anchoring"), anchorage());
        tabs.addTab(Messages.get("overlay.tab.appearance"), appearance());

        refreshAnchoring();

        add(tabs, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(Math.max(520, getWidth()), getHeight()));
        setLocationRelativeTo(owner);
    }

    /**
     * @return the indicator with the changes applied, or null if it was cancelled
     *
     * <p>The SAME object, changed in place, as the other dialogs do it.</p>
     */
    public static TouchTrendlines edit(Window owner, TouchTrendlines trendlines) {
        TouchTrendlinesDialog dialog = new TouchTrendlinesDialog(owner, trendlines);

        dialog.setVisible(true);

        return dialog.accepted ? trendlines : null;
    }

    private JComponent parameters() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.channel.window"));
        Forms.field(panel, 1, Messages.get("overlay.ma.period"), period);

        Forms.group(panel, 2, Messages.get("overlay.channel.turns"));
        Forms.field(panel, 3, Messages.get("overlay.pivots.wing"), wing);
        Forms.field(panel, 4, Messages.get("overlay.pivots.ties.rule"), ties);

        Forms.group(panel, 5, Messages.get("overlay.lt.ladder"));
        Forms.field(panel, 6, Messages.get("overlay.lt.from"), from);
        Forms.field(panel, 7, Messages.get("overlay.lt.to"), until);
        Forms.field(panel, 8, Messages.get("overlay.lt.step"), step);

        // Three spinners that mean nothing apart. The hint is where the rule
        // lives: the search climbs the ladder until the line has a third
        // touch, and the rung it stopped at is part of the answer.
        String hint = Messages.get("overlay.lt.ladder.hint");

        from.setToolTipText(hint);
        until.setToolTipText(hint);
        step.setToolTipText(hint);

        return panel;
    }

    /**
     * Where each line starts.
     *
     * <p>The mode on top, and under it only what that mode reads. The two
     * averages sit in the same group as the crossing rule because they are not
     * indicators here -- nothing of them is drawn; they exist to say where the
     * move began.</p>
     */
    private JComponent anchorage() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.lt.anchoring"));
        Forms.field(panel, 1, Messages.get("overlay.lt.mode"), anchoring);

        Forms.group(panel, 2, Messages.get("overlay.lt.crossing"));
        Forms.field(panel, 3, Messages.get("overlay.lt.crossing.side"), crossing);
        Forms.field(panel, 4, Messages.get("overlay.lt.fast"), fastPeriod);
        Forms.field(panel, 5, Messages.get("overlay.lt.slow"), slowPeriod);
        Forms.field(panel, 6, Messages.get("overlay.ma.scale"), slowScale);

        return panel;
    }

    /** Leaves turnable only what the chosen mode actually reads. */
    private void refreshAnchoring() {
        TouchTrendlines.Anchoring how =
                (TouchTrendlines.Anchoring) anchoring.getSelectedItem();
        boolean average = how != null && how.usesAverage();
        boolean fast = how != null && how.usesFastAverage();
        boolean sides = average && how != TouchTrendlines.Anchoring.LAST_CROSSING;

        anchoring.setToolTipText(how == null ? null
                : Messages.get("overlay.lt.anchoring." + how.name() + ".hint"));

        // O modo dos giros do cruzamento usa as DUAS pontas dele, entao nao ha
        // lado a escolher: o combo fica em cinza em vez de decidir nada.
        crossing.setEnabled(sides);
        fastPeriod.setEnabled(fast);
        slowPeriod.setEnabled(average && !fast);
        slowScale.setEnabled(average && !fast);
        slowScale.setText(scaleCode);

        // The ladder only picks something where there is a search. See the
        // class javadoc on why these are disabled rather than hidden.
        until.setEnabled(how != null && how.searches());
        step.setEnabled(how != null && how.searches());
    }

    private void askForTheScale() {
        PeriodCatalog.Choice choice = PeriodDialog.ask(this, null);

        if (choice != null) {
            scaleCode = choice.code();

            slowScale.setText(scaleCode);
        }
    }

    private JComponent appearance() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.ma.line"));
        Forms.field(panel, 1, Messages.get("overlay.ma.style"), line);
        Forms.field(panel, 2, Messages.get("overlay.ma.thickness"), thickness);

        Forms.group(panel, 3, Messages.get("overlay.lt.support"));
        Forms.field(panel, 4, Messages.get("overlay.ma.colour"), supportColour);
        Forms.across(panel, 5, supportSample);

        Forms.group(panel, 6, Messages.get("overlay.lt.resistance"));
        Forms.field(panel, 7, Messages.get("overlay.ma.colour"), resistanceColour);
        Forms.across(panel, 8, resistanceSample);

        Forms.group(panel, 9, Messages.get("overlay.lt.extras"));
        Forms.across(panel, 10, rails);
        Forms.across(panel, 11, touches);
        Forms.across(panel, 12, memory);

        return panel;
    }

    private void repaintSamples() {
        supportSample.repaint();
        resistanceSample.repaint();
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
        trendlines.setPeriod((Integer) period.getValue());
        trendlines.setWing((Integer) wing.getValue());
        trendlines.setTies((TopsAndBottoms.Ties) ties.getSelectedItem());
        trendlines.setToleranceFrom((Integer) from.getValue());
        trendlines.setToleranceTo((Integer) until.getValue());
        trendlines.setToleranceStep((Integer) step.getValue());
        trendlines.setAnchoring((TouchTrendlines.Anchoring) anchoring.getSelectedItem());
        trendlines.setCrossing((TouchTrendlines.Crossing) crossing.getSelectedItem());
        trendlines.setFastPeriod((Integer) fastPeriod.getValue());
        trendlines.setSlowPeriod((Integer) slowPeriod.getValue());
        trendlines.setSlowScale(scaleCode);
        trendlines.setLine((MovingAverage.Line) line.getSelectedItem());
        trendlines.setThickness((Integer) thickness.getValue());
        trendlines.setSupportColour(chosenSupport);
        trendlines.setResistanceColour(chosenResistance);
        trendlines.setRails(rails.isSelected());
        trendlines.setMarkTouches(touches.isSelected());
        trendlines.setMemory(memory.isSelected());
    }

    private void pick(boolean support) {
        Color current = support ? chosenSupport : chosenResistance;
        Color fallback = trendlines.colours().get(support ? 0 : 1);
        Color picked = JColorChooser.showDialog(this,
                Messages.get(support ? "overlay.lt.support" : "overlay.lt.resistance"),
                current == null ? fallback : current);

        if (picked == null) {
            return;
        }

        if (support) {
            chosenSupport = picked;
        } else {
            chosenResistance = picked;
        }

        paintButtons();
        repaintSamples();
    }

    private void paintButtons() {
        paintButton(supportColour, chosenSupport, trendlines.colours().get(0));
        paintButton(resistanceColour, chosenResistance, trendlines.colours().get(1));
    }

    private static void paintButton(JButton button, Color chosen, Color fallback) {
        button.setText(chosen == null
                ? Messages.get("overlay.ma.automatic")
                : Messages.get("overlay.ma.chosen"));
        button.setIcon(new Forms.Swatch(chosen == null ? fallback : chosen));
        button.setHorizontalAlignment(SwingConstants.LEFT);
    }
}
