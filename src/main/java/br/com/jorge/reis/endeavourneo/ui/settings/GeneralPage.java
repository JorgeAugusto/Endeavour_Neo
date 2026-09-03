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
package br.com.jorge.reis.endeavourneo.ui.settings;

import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.ui.chart.RulerMode;

import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The settings that belong to the application rather than to one window.
 *
 * <p>The ruler is here and not in the chart's own toolbar for the reason that
 * makes modal tools go wrong: a mode that depends on which window has focus
 * means dragging on the chart beside the last one used does something else,
 * with nothing on screen having changed. One switch, every chart.</p>
 *
 * <p>The page names the keyboard shortcut rather than owning it. A checkbox is
 * where you look to find out that the shortcut exists; Control is how you use it
 * once you know.</p>
 */
public final class GeneralPage implements SettingsPage {

    private final JPanel panel = new JPanel();

    private final JCheckBox ruler = new JCheckBox(Messages.get("settings.ruler"));

    private final JCheckBox synthetic =
            new JCheckBox(Messages.get("settings.syntheticTicks"));

    private final javax.swing.JComboBox<br.com.jorge.reis.endeavourneo.platform.Language> language =
            new javax.swing.JComboBox<>(
                    br.com.jorge.reis.endeavourneo.platform.Language.values());

    public GeneralPage() {
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        ruler.setAlignmentX(Component.LEFT_ALIGNMENT);
        ruler.setMnemonic(Messages.mnemonic("settings.ruler"));

        JLabel hint = new JLabel(Messages.get("settings.ruler.hint"));

        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(BorderFactory.createEmptyBorder(0, 24, 8, 0));
        hint.setEnabled(false);

        panel.add(ruler);
        panel.add(hint);

        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));

        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(new JLabel(Messages.get("settings.language")));
        row.add(language);

        language.setRenderer(new javax.swing.DefaultListCellRenderer() {

            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
                    Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);

                if (value instanceof br.com.jorge.reis.endeavourneo.platform.Language chosen) {
                    setText(Messages.get("settings.language." + chosen.code()));
                }

                return this;
            }
        });

        JLabel languageHint = new JLabel(Messages.get("settings.language.hint"));

        languageHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        languageHint.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 0));
        languageHint.setEnabled(false);

        panel.add(row);
        panel.add(languageHint);

        JLabel syntheticHint = new JLabel(Messages.get("settings.syntheticTicks.hint"));

        syntheticHint.setFont(syntheticHint.getFont().deriveFont(
                syntheticHint.getFont().getSize2D() - 1f));
        syntheticHint.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        synthetic.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        panel.add(Box.createVerticalStrut(14));
        panel.add(synthetic);
        panel.add(syntheticHint);
        panel.add(Box.createVerticalGlue());
    }

    @Override
    public String getTitle() {
        return Messages.get("settings.general");
    }

    @Override
    public JComponent getComponent() {
        return panel;
    }

    @Override
    public void load() {
        // Read on every opening, not once at construction: Control may have
        // flipped it since, and a dialog showing the old value would put it back
        // the moment anything else on the page is applied.
        ruler.setSelected(RulerMode.isOn());
        synthetic.setSelected(
                br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.syntheticTicks());
        language.setSelectedItem(br.com.jorge.reis.endeavourneo.platform.Language.remembered());
    }

    @Override
    public void apply() {
        RulerMode.set(ruler.isSelected());
        br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences
                .setSyntheticTicks(synthetic.isSelected());
        ((br.com.jorge.reis.endeavourneo.platform.Language) language.getSelectedItem()).remember();
    }
}
