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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The market's name in the transport is the handle the session is dragged by,
 * and it has to look like one.
 */
@DisplayName("A alca do replay")
class ReplayHandleTest {

    private static JLabel handleOf(Container where) {
        for (Component each : where.getComponents()) {
            if (each instanceof JLabel label
                    && Messages.get("replay.dragHint").equals(label.getToolTipText())) {
                return label;
            }

            if (each instanceof Container inside) {
                JLabel found = handleOf(inside);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    @Test
    @DisplayName("o mercado tem nome, e nao e o nome da pasta")
    void theMarketIsNamed() {
        assertEquals("WINFUT", Messages.market("win"),
                "a pasta se chama win, o contrato nao");
        assertNotEquals("win", Messages.market("win"));
    }

    @Test
    @DisplayName("um mercado sem nome escrito fica com o proprio codigo")
    void anUnnamedMarketKeepsItsCode() {
        assertEquals("nada-disso", Messages.market("nada-disso"));
    }

    @Test
    @DisplayName("sem sessao a alca nao se oferece para ser arrastada")
    void nothingToDragLooksLikeNothingToDrag() throws Exception {
        JLabel[] handle = new JLabel[1];

        SwingUtilities.invokeAndWait(() -> handle[0] = handleOf(new ReplayPanel()));

        assertNotNull(handle[0], "the transport has no handle at all");
        assertNull(handle[0].getIcon(),
                "there is no session, so there is nothing to carry");
        assertEquals(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR),
                handle[0].getCursor(),
                "a control that looks draggable and is not is worse than a label");
    }
}
