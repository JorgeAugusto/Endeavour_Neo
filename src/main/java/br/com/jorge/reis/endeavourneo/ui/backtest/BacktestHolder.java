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
package br.com.jorge.reis.endeavourneo.ui.backtest;

import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.ui.shell.DockablePane;

import java.awt.Window;
import javax.swing.JDesktopPane;

/**
 * The backtest, living where the charts live.
 *
 * <p>Inside the main window by default, and loose when the button in its bar
 * says so — the same two states a chart has, remembered the same way. It was a
 * frame of its own first, which meant it could not be arranged beside a chart,
 * and arranging it beside a chart is most of what looking at a run is.</p>
 */
public final class BacktestHolder {

    private final DockablePane pane;

    /**
     * @param desktop  where it goes when it is inside
     * @param owner    the main window, for placing it when it is loose
     * @param onClosed run after it closes, for the caller to forget it
     */
    public BacktestHolder(JDesktopPane desktop, Window owner, Runnable onClosed) {
        pane = new DockablePane("backtest", Messages.get("backtest.title"),
                new BacktestPanel(), desktop, owner, onClosed);
    }

    /** Opens it the way it was last left, or brings it forward. */
    public void show() {
        pane.show();
    }

    /** Takes it off screen. */
    public void close() {
        pane.close();
    }
}
