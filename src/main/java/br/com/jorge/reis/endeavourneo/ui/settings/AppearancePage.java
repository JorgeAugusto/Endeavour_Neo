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

import br.com.jorge.reis.endeavourneo.platform.Appearance;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Theme;

import java.awt.Component;
import java.awt.Window;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;

/**
 * The Appearance page: pick a theme.
 *
 * <p>Each theme carries a one-line explanation, because "Dark" and "Night" are
 * not obviously different and a radio button alone would not say which to pick.
 * A settings screen that lists options without saying what they do makes the
 * user guess, and guessing wrong in preferences is how people end up never
 * touching them again.</p>
 */
public final class AppearancePage implements SettingsPage {

    private final Map<Theme, JRadioButton> buttons = new EnumMap<>(Theme.class);

    private final JPanel panel = new JPanel();

    private final transient Consumer<String> report;

    /**
     * @param report where to announce what was installed; may be null
     *
     * <p>The page does not know about the console or the status bar — it just
     * hands the result to whoever asked for it. That is what keeps this class
     * usable from a dialog that has no window behind it, which is exactly the
     * situation in a test.</p>
     */
    public AppearancePage(Consumer<String> report) {
        this.report = report == null ? text -> { } : report;

        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        ButtonGroup group = new ButtonGroup();

        for (Theme theme : Theme.values()) {
            JRadioButton button = new JRadioButton(title(theme));

            button.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel hint = new JLabel(explanation(theme));
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            hint.setBorder(BorderFactory.createEmptyBorder(0, 24, 8, 0));
            hint.setEnabled(false);

            group.add(button);
            buttons.put(theme, button);

            panel.add(button);
            panel.add(hint);
        }

        panel.add(Box.createVerticalGlue());
    }

    @Override
    public String getTitle() {
        return Messages.get("settings.appearance");
    }

    @Override
    public JComponent getComponent() {
        return panel;
    }

    @Override
    public void load() {
        buttons.get(Theme.remembered()).setSelected(true);
    }

    @Override
    public void apply() {
        Theme chosen = selected();

        if (chosen == Theme.remembered() && !buttons.isEmpty()) {
            // Nothing changed. Reinstalling would still work, but it repaints
            // every window for no reason -- visible as a flicker on Apply.
            return;
        }

        String installed = Appearance.install(chosen);

        chosen.remember();

        // Every window, not just the dialog: the main window behind it, and any
        // panel that may later be torn off onto a second monitor.
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
        }

        report.accept(installed);
    }

    private Theme selected() {
        for (Map.Entry<Theme, JRadioButton> entry : buttons.entrySet()) {
            if (entry.getValue().isSelected()) {
                return entry.getKey();
            }
        }

        return Theme.LIGHT;
    }

    /**
     * @return the bundle key prefix for a theme, e.g. {@code theme.night}
     *
     * <p>Derived from the enum name rather than switched on, so a new theme
     * needs no change here -- only two lines in each bundle.</p>
     */
    private static String key(Theme theme) {
        return "theme." + theme.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String title(Theme theme) {
        return Messages.get(key(theme));
    }

    private static String explanation(Theme theme) {
        return Messages.get(key(theme) + ".hint");
    }
}
