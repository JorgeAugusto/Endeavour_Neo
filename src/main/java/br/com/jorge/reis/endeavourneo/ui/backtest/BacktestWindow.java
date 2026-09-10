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

import java.awt.Dimension;
import java.awt.Window;
import javax.swing.JFrame;

/**
 * The backtest, in a window of its own.
 *
 * <p>Not docked into the chart window, and not for lack of trying to follow the
 * plan — {@code BACKTEST.md} proposes a panel below the layout tabs, and it will
 * be right when the panel is a strip. This one carries its own price chart,
 * because clicking an operation has to take you somewhere, and a strip that owns
 * a chart is a window wearing a costume.</p>
 *
 * <p>It follows the replay's shape: one window, opened from the Tools menu,
 * disposed on close, and the caller drops its reference so the next open builds
 * a fresh one.</p>
 */
public final class BacktestWindow extends JFrame {

    private static final long serialVersionUID = 1L;

    public BacktestWindow(Window owner) {
        super(Messages.get("backtest.title"));

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(new BacktestPanel());

        pack();
        setMinimumSize(new Dimension(900, 600));

        if (owner != null) {
            setLocation(owner.getX() + 60, owner.getY() + 40);
        }
    }
}
