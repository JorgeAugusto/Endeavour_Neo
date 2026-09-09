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
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.TouchChannel;

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
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;

/**
 * The settings for {@link TouchChannel}.
 *
 * <p>Two tabs. The parameters tab carries the four numbers the fit turns on —
 * how far back to look, how big a turn counts as one, how close a pivot has to
 * pass to count as a touch, and what to do when two bars are level. The
 * appearance tab dresses the two edges, which share a pen: they are parallel by
 * construction and drawing them differently would suggest they are not.</p>
 */
public final class TouchChannelDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient TouchChannel channel;

    private final JSpinner period;

    private final JSpinner wing;

    private final JSpinner tolerance;

    private final JComboBox<TopsAndBottoms.Ties> ties =
            new JComboBox<>(TopsAndBottoms.Ties.values());

    private final JComboBox<MovingAverage.Line> line =
            new JComboBox<>(MovingAverage.Line.values());

    private final JSpinner thickness;

    private final JButton upperColour = new JButton();

    private final JButton lowerColour = new JButton();

    private final JCheckBox fill = new JCheckBox(Messages.get("overlay.channel.fill"));

    private final JSlider opacity;

    private transient Color chosenUpper;

    private transient Color chosenLower;

    private final transient Forms.Sample upperSample;

    private final transient Forms.Sample lowerSample;

    private transient boolean accepted;

    private TouchChannelDialog(Window owner, TouchChannel channel) {
        super(owner, ModalityType.APPLICATION_MODAL);

        this.channel = channel;
        this.chosenUpper = channel.chosenUpperColour();
        this.chosenLower = channel.chosenLowerColour();

        this.period = new JSpinner(new SpinnerNumberModel(channel.period(), 10,
                TouchChannel.MOST_BARS, 5));
        this.wing = new JSpinner(new SpinnerNumberModel(channel.wing(), 1,
                TopsAndBottoms.MOST_WING, 1));
        this.tolerance = new JSpinner(new SpinnerNumberModel(channel.tolerance(), 1, 25, 1));
        this.thickness = new JSpinner(new SpinnerNumberModel(channel.thickness(), 1, 8, 1));
        this.opacity = new JSlider(0, 100, channel.opacity());

        this.upperSample = new Forms.Sample(
                () -> chosenUpper == null ? channel.colours().get(0) : chosenUpper,
                () -> (MovingAverage.Line) line.getSelectedItem(),
                () -> (Integer) thickness.getValue());
        this.lowerSample = new Forms.Sample(
                () -> chosenLower == null ? channel.colours().get(1) : chosenLower,
                () -> (MovingAverage.Line) line.getSelectedItem(),
                () -> (Integer) thickness.getValue());

        setTitle(Messages.get("overlay.dialog.title",
                Messages.get(channel.nameKey()) + " [" + channel.period() + "]"));

        ties.setSelectedItem(channel.ties());
        line.setSelectedItem(channel.line());
        fill.setSelected(channel.isFilled());

        ties.setRenderer(Forms.named("overlay.pivots.ties."));
        line.setRenderer(Forms.lineStyles());

        upperColour.addActionListener(e -> pick(true));
        lowerColour.addActionListener(e -> pick(false));

        line.addActionListener(e -> repaintSamples());
        thickness.addChangeListener(e -> repaintSamples());
        fill.addActionListener(e -> refreshFill());

        paintButtons();

        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab(Messages.get("overlay.tab.parameters"), parameters());
        tabs.addTab(Messages.get("overlay.tab.appearance"), appearance());

        add(tabs, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(Math.max(500, getWidth()), getHeight()));
        setLocationRelativeTo(owner);
    }

    /**
     * @return the indicator with the changes applied, or null if it was cancelled
     *
     * <p>The SAME object, changed in place, as the other dialogs do it.</p>
     */
    public static TouchChannel edit(Window owner, TouchChannel channel) {
        TouchChannelDialog dialog = new TouchChannelDialog(owner, channel);

        dialog.setVisible(true);

        return dialog.accepted ? channel : null;
    }

    private JComponent parameters() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.channel.window"));
        Forms.field(panel, 1, Messages.get("overlay.ma.period"), period);

        Forms.group(panel, 2, Messages.get("overlay.channel.turns"));
        Forms.field(panel, 3, Messages.get("overlay.pivots.wing"), wing);
        Forms.field(panel, 4, Messages.get("overlay.pivots.ties.rule"), ties);

        Forms.group(panel, 5, Messages.get("overlay.channel.touch"));
        Forms.field(panel, 6, Messages.get("overlay.channel.tolerance"), tolerance);

        // O que o número quer dizer não cabe no rótulo, e sem isso "5" é um
        // número sem unidade: é 5% da amplitude dos pivôs da janela, e não da
        // largura do canal -- ver o javadoc do indicador sobre por quê.
        tolerance.setToolTipText(Messages.get("overlay.channel.tolerance.hint"));

        return panel;
    }

    private JComponent appearance() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.ma.line"));
        Forms.field(panel, 1, Messages.get("overlay.ma.style"), line);
        Forms.field(panel, 2, Messages.get("overlay.ma.thickness"), thickness);

        Forms.group(panel, 3, Messages.get("overlay.channel.upper"));
        Forms.field(panel, 4, Messages.get("overlay.ma.colour"), upperColour);
        Forms.across(panel, 5, upperSample);

        Forms.group(panel, 6, Messages.get("overlay.channel.lower"));
        Forms.field(panel, 7, Messages.get("overlay.ma.colour"), lowerColour);
        Forms.across(panel, 8, lowerSample);

        Forms.group(panel, 9, Messages.get("overlay.bb.fillGroup"));
        Forms.across(panel, 10, fill);

        opacity.setMajorTickSpacing(25);
        opacity.setPaintTicks(true);
        opacity.setPaintLabels(true);
        Forms.field(panel, 11, Messages.get("overlay.bb.opacity"), opacity);

        refreshFill();

        return panel;
    }

    private void repaintSamples() {
        upperSample.repaint();
        lowerSample.repaint();
    }

    private void refreshFill() {
        opacity.setEnabled(fill.isSelected());
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
        channel.setPeriod((Integer) period.getValue());
        channel.setWing((Integer) wing.getValue());
        channel.setTolerance((Integer) tolerance.getValue());
        channel.setTies((TopsAndBottoms.Ties) ties.getSelectedItem());
        channel.setLine((MovingAverage.Line) line.getSelectedItem());
        channel.setThickness((Integer) thickness.getValue());
        channel.setUpperColour(chosenUpper);
        channel.setLowerColour(chosenLower);
        channel.setFilled(fill.isSelected());
        channel.setOpacity(opacity.getValue());
    }

    private void pick(boolean upper) {
        Color current = upper ? chosenUpper : chosenLower;
        Color fallback = channel.colours().get(upper ? 0 : 1);
        Color picked = JColorChooser.showDialog(this,
                Messages.get(upper ? "overlay.channel.upper" : "overlay.channel.lower"),
                current == null ? fallback : current);

        if (picked == null) {
            return;
        }

        if (upper) {
            chosenUpper = picked;
        } else {
            chosenLower = picked;
        }

        paintButtons();
        repaintSamples();
    }

    private void paintButtons() {
        paintButton(upperColour, chosenUpper, channel.colours().get(0));
        paintButton(lowerColour, chosenLower, channel.colours().get(1));
    }

    private static void paintButton(JButton button, Color chosen, Color fallback) {
        button.setText(chosen == null
                ? Messages.get("overlay.ma.automatic")
                : Messages.get("overlay.ma.chosen"));
        button.setIcon(new Forms.Swatch(chosen == null ? fallback : chosen));
        button.setHorizontalAlignment(SwingConstants.LEFT);
    }
}
