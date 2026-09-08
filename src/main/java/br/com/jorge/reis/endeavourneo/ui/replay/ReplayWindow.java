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
package br.com.jorge.reis.endeavourneo.ui.replay;

import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.Dimension;
import java.awt.Window;
import javax.swing.JFrame;

/**
 * The replay transport, in a window of its own.
 *
 * <p>A real window and not a panel docked in the main one: a session is dragged
 * from here onto charts that may be on another monitor, and a transport trapped
 * inside the main window would make that drag cross a boundary the window
 * manager does not let it cross.</p>
 *
 * <p><b>Hidden rather than disposed, and the session ends anyway.</b> The
 * window object survives so reopening the transport is instant and keeps
 * where it was on screen; what does NOT survive is the replay. Closing it
 * releases the session and gives every chart back its own data -- the
 * listener below says why, and this paragraph used to say the opposite: that
 * the session survived a close.</p>
 *
 * <p>Which is the right behaviour, and worth stating rather than leaving to
 * be discovered: charts left frozen on a day that stopped playing, under a
 * title still claiming a replay, would each need closing too.</p>
 *
 * <p><b>Above everything, and not resizable.</b> It is a transport, not a view:
 * there is nothing inside it that more room would show more of, and a maximised
 * one would cover the charts it exists to drive. Fixed size is also what takes
 * the maximise button away — Swing offers no way to remove that button on its
 * own.</p>
 */
public final class ReplayWindow extends JFrame {

    private static final long serialVersionUID = 1L;

    public ReplayWindow(Window owner) {
        super(Messages.get("replay.title"));

        setDefaultCloseOperation(HIDE_ON_CLOSE);

        ReplayPanel panel = new ReplayPanel();

        setContentPane(panel);

        // Closing the transport ends the session and gives every chart back its
        // own data. Leaving them frozen on a day that stopped playing, with a
        // title still claiming a replay, would make the charts need closing too.
        //
        // On windowCLOSED, not windowClosing. Closing fires only when the reader
        // presses the X; dispose() -- which MainWindow.relaunch calls on every
        // language change -- fires only closed. Hanging the release on closing
        // alone left a language switch with the session's 40 ms Timer still
        // walking the market, the tick files still open, and no reference left
        // to stop either, because relaunch nulls the field straight after.
        // windowClosed covers both paths: pressing the X disposes as well.
        addWindowListener(new java.awt.event.WindowAdapter() {

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                panel.release();
            }
        });
        setAlwaysOnTop(true);

        pack();
        setResizable(false);
        setMinimumSize(new Dimension(Math.max(360, getWidth()), getHeight()));

        // Beside the main window rather than over it: the charts are where the
        // dragging ends, and a transport centred on them covers the target.
        if (owner != null) {
            setLocation(owner.getX() + owner.getWidth() - getWidth() - 40, owner.getY() + 80);
        }
    }
}
