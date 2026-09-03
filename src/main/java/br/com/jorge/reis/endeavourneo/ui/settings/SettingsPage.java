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

import javax.swing.JComponent;

/**
 * One page of the preferences dialog.
 *
 * <p>Adding a settings page means writing one class and registering it — the
 * dialog itself never changes. That is the whole point of the interface: in
 * applications that grow, the settings dialog is the screen most likely to rot
 * into a thousand-line class with a switch statement at the centre.</p>
 *
 * <p><b>{@link #load()} and {@link #apply()} exist separately on purpose.</b>
 * A page reads the current state when it is shown and writes it back only when
 * the user confirms. Without that split there is no meaningful Cancel — every
 * click would take effect immediately, and the user would have no way back.</p>
 */
public interface SettingsPage {

    /** @return the name shown in the category tree */
    String getTitle();

    /** @return the page's widgets; built once and reused */
    JComponent getComponent();

    /** Reads the application's current state into the widgets. */
    void load();

    /** Writes the widgets back into the application. */
    void apply();
}
