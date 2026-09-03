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

import br.com.jorge.reis.endeavourneo.ui.chart.ChartHolder;

import javax.swing.JComponent;
import javax.swing.TransferHandler;

/**
 * Makes a chart accept a session dropped on it.
 *
 * <p>Lives here and not in {@code ui.chart} so the dependency runs one way: the
 * replay knows what a chart is, and a chart knows nothing about replays. A chart
 * being fed by a replay is just a chart whose series happens to grow.</p>
 */
public final class ReplayDrop {

    private ReplayDrop() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @param holder the chart window that should accept a dropped session */
    public static void enable(ChartHolder holder) {
        if (holder == null) {
            return;
        }

        holder.canvas().setTransferHandler(new TransferHandler() {

            private static final long serialVersionUID = 1L;

            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDrop() && support.isDataFlavorSupported(ReplayTransfer.FLAVOR);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }

                try {
                    Object dropped = support.getTransferable()
                            .getTransferData(ReplayTransfer.FLAVOR);

                    if (dropped instanceof ReplaySession session) {
                        attach(holder, session);

                        return true;
                    }
                } catch (java.io.IOException
                         | java.awt.datatransfer.UnsupportedFlavorException e) {
                    // The drag ended badly. Refusing quietly is right: the
                    // reader sees the chart not change, which is what happened.
                    return false;
                }

                return false;
            }
        });
    }

    private static void attach(ChartHolder holder, ReplaySession session) {
        // Redrawn on every tick, and the aggregation applied again: a replay at
        // five minutes has to refold the minutes that have arrived, or the last
        // bar would stop growing the moment its period began.
        Runnable follow = () -> holder.canvas().seriesGrew();

        holder.attachReplay(session.instrument() + " " + session.date(),
                session.series(), () -> session.forget(follow));

        session.watch(follow);
        holder.canvas().seriesGrew();
    }
}
