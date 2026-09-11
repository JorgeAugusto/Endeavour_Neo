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

import br.com.jorge.reis.endeavourneo.domain.market.CandlePattern;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Settings;
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.Patterns;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/**
 * The pattern indicator's settings: which shapes, and in what colours.
 *
 * <p>Two tabs, and the split is the reader's question rather than the code's.
 * <b>Padrões</b> is three switches — one per shape, because up and down are one
 * shape seen from two sides. <b>Aparência</b> is six colours, because that is
 * where the two sides become separate things worth telling apart.
 */
public final class PatternsDialog {

    /** The six, in the order they are offered: shape by shape, up then down. */
    private static final CandlePattern[] SIX = {
            CandlePattern.PFR_BULLISH, CandlePattern.PFR_BEARISH,
            CandlePattern.INSIDE_BULLISH, CandlePattern.INSIDE_BEARISH,
            CandlePattern.ONE_TWO_THREE_BUY, CandlePattern.ONE_TWO_THREE_SELL,
    };

    private static final CandlePattern.Family[] THREE = {
            CandlePattern.Family.PFR,
            CandlePattern.Family.INSIDE,
            CandlePattern.Family.ONE_TWO_THREE,
    };

    private PatternsDialog() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param owner where to centre it
     * @param patterns the indicator being configured
     * @return the same indicator when the reader accepted, or null when not
     */
    public static Patterns edit(Window owner, Patterns patterns) {
        Map<CandlePattern.Family, JCheckBox> switches = new EnumMap<>(CandlePattern.Family.class);
        Map<CandlePattern, Color> chosen = new EnumMap<>(CandlePattern.class);

        for (CandlePattern.Family family : THREE) {
            JCheckBox box = new JCheckBox(Messages.get(family.nameKey()),
                    PatternPalette.shows(family));

            box.setFocusable(false);
            switches.put(family, box);
        }

        for (CandlePattern pattern : SIX) {
            chosen.put(pattern, PatternPalette.colourOf(pattern));
        }

        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab(Messages.get("patterns.tab.shapes"), shapes(switches));
        tabs.addTab(Messages.get("patterns.tab.look"), look(chosen));

        JDialog dialog = new JDialog(owner, Messages.get("overlay.patterns"),
                JDialog.ModalityType.APPLICATION_MODAL);

        boolean[] accepted = {false};

        JButton ok = new JButton(Messages.get("dialog.ok"));
        JButton cancel = new JButton(Messages.get("dialog.cancel"));

        ok.addActionListener(event -> {
            accepted[0] = true;

            dialog.dispose();
        });

        cancel.addActionListener(event -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

        buttons.add(cancel);
        buttons.add(ok);

        JPanel body = new JPanel(new BorderLayout(0, 8));

        body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        body.add(tabs, BorderLayout.CENTER);
        body.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(body);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(340, dialog.getHeight()));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);

        if (!accepted[0]) {
            return null;
        }

        // WRITTEN IN ONE GO. Nine settings saved one at a time is nine writes of
        // the whole file, and a reader watching the chart would see it repaint
        // as each one landed.
        Settings settings = Settings.settings();

        settings.hold(() -> {
            for (CandlePattern.Family family : THREE) {
                PatternPalette.setShows(family, switches.get(family).isSelected());
            }

            for (CandlePattern pattern : SIX) {
                PatternPalette.setColour(pattern, chosen.get(pattern));
            }
        });

        return patterns;
    }

    private static JPanel shapes(Map<CandlePattern.Family, JCheckBox> switches) {
        JPanel panel = new JPanel();

        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        for (CandlePattern.Family family : THREE) {
            JCheckBox box = switches.get(family);

            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(box);
            panel.add(hint(family.nameKey() + ".hint"));
        }

        panel.add(Box.createVerticalGlue());

        return panel;
    }

    private static JPanel look(Map<CandlePattern, Color> chosen) {
        JPanel panel = new JPanel();

        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        for (CandlePattern pattern : SIX) {
            panel.add(colourRow(pattern, chosen));
        }

        panel.add(Box.createVerticalGlue());

        return panel;
    }

    private static JPanel colourRow(CandlePattern pattern, Map<CandlePattern, Color> chosen) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));

        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton swatch = new JButton();

        swatch.setPreferredSize(new Dimension(34, 18));
        swatch.setBackground(chosen.get(pattern));
        swatch.setOpaque(true);
        swatch.setBorderPainted(true);
        swatch.setFocusable(false);
        swatch.setToolTipText(Messages.get("patterns.pick"));

        swatch.addActionListener(event -> {
            Color picked = JColorChooser.showDialog(row, Messages.get("patterns.pick"),
                    chosen.get(pattern));

            if (picked != null) {
                chosen.put(pattern, picked);
                swatch.setBackground(picked);
            }
        });

        row.add(swatch);
        row.add(new JLabel(Messages.get(pattern.nameKey())));

        return row;
    }

    private static JLabel hint(String key) {
        JLabel label = new JLabel(Messages.orElse(key, ""));

        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(2, 22, 8, 0));
        label.setEnabled(false);

        return label;
    }
}
