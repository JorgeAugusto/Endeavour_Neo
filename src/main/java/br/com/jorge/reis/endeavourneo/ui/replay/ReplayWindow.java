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
 * <p><b>Disposed on close, and it used to be hidden.</b> Closing has to end
 * the replay -- charts left frozen on a day that stopped playing, under a title
 * still claiming a replay, would each need closing too -- and the release was
 * hung on {@code windowClosed}, which {@code HIDE_ON_CLOSE} never fires. So
 * pressing the X hid the transport and left everything running: the session's
 * 40 ms Timer still walking the market, the tick files still open, the charts
 * still frozen, and no way back to any of it.</p>
 *
 * <p>Reported as "ao fechar a janela não está parando, é pra interromper tudo".
 * The same trap {@code SeriesWindow} was caught in, and for the same reason:
 * {@code HIDE_ON_CLOSE} is what a window does by default and it reads like
 * closing.</p>
 *
 * <p>Nothing is lost by disposing. Reopening builds the transport again <b>in
 * its initial state -- a day to choose and a play to press</b>, which is what
 * closing it should mean, and it lands in the same place because the position
 * is worked out from the owner rather than remembered.</p>
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

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        ReplayPanel panel = new ReplayPanel();

        setContentPane(panel);

        // Closing the transport ends the session and gives every chart back its
        // own data. Leaving them frozen on a day that stopped playing, with a
        // title still claiming a replay, would make the charts need closing too.
        //
        // On windowCLOSED, which now fires on both paths BECAUSE of the line
        // above. dispose() -- which MainWindow.relaunch calls on every language
        // change -- has always fired it; pressing the X only started to when
        // the close operation stopped being HIDE_ON_CLOSE. The comment here
        // used to claim "pressing the X disposes as well", which was the
        // assumption the defect lived in.
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
