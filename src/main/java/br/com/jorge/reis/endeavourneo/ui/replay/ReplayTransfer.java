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

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * A running session, carried from the replay panel to a chart.
 *
 * <p>The flavour is a <b>local object</b> one: the session is handed over as
 * itself, not serialised. It has to be — it owns a running timer and a list of
 * watchers, and a copy of it would be a second session playing the same day out
 * of step with the first.</p>
 */
final class ReplayTransfer implements Transferable {

    /** Built once; a malformed mime type would be a programming error, not a runtime one. */
    static final DataFlavor FLAVOR = build();

    private final transient ReplaySession session;

    ReplayTransfer(ReplaySession session) {
        this.session = session;
    }

    private static DataFlavor build() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType
                    + ";class=" + ReplaySession.class.getName());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("the replay flavour names a class that is not here", e);
        }
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[]{FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return FLAVOR.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!FLAVOR.equals(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }

        return session;
    }
}
