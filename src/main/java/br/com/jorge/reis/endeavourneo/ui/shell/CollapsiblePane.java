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
package br.com.jorge.reis.endeavourneo.ui.shell;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * A titled panel the reader can fold away, leaving only its caption.
 *
 * <h2>Folded, not closed</h2>
 *
 * <p>The caption stays. A panel that vanished entirely would need a menu to
 * come back, and the reader who folded the log to see one more candle would
 * have to remember where that menu was. Folded, the way back is the strip that
 * is still on screen.</p>
 *
 * <p>Same shape as the chart's legend, which folds for the same reason and
 * remembers the same way — one gesture learned once.</p>
 *
 * <h2>It does not move itself</h2>
 *
 * <p>This panel only says it changed; whoever put it in a split pane moves the
 * divider. A component that reached out to resize its own container would work
 * in exactly the arrangement it was written for and quietly not in any
 * other.</p>
 */
public final class CollapsiblePane extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(CollapsiblePane.class);

    /** Pointing at the content when open, at the caption when folded. */
    private static final String OPEN = "▾";

    private static final String FOLDED = "▴";

    private final JLabel caption;

    private final JLabel arrow = new JLabel(OPEN, SwingConstants.CENTER);

    private final transient JComponent content;

    private final transient String key;

    private transient Runnable onToggle = () -> { };

    private boolean folded;

    /**
     * @param title the caption, which stays visible when folded
     * @param content what folds away
     * @param key where the state is remembered, across launches
     */
    public CollapsiblePane(String title, JComponent content, String key) {
        super(new BorderLayout());

        this.content = content;
        this.key = key;
        this.caption = new JLabel(title);
        this.folded = PREFS.getBoolean(key + ".folded", false);

        caption.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 8));
        arrow.setPreferredSize(new Dimension(20, 20));

        JPanel header = new JPanel(new BorderLayout());

        header.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        header.add(arrow, BorderLayout.WEST);
        header.add(caption, BorderLayout.CENTER);
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // The whole strip, not just the triangle. A four-pixel arrow is a
        // target the reader has to aim at; the caption beside it is free.
        header.addMouseListener(new MouseAdapter() {

            @Override
            public void mousePressed(MouseEvent e) {
                setFolded(!folded);
            }
        });

        header.setToolTipText(title);

        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);

        apply();
    }

    /** @param watcher told after every fold and unfold, on the interface thread */
    public void onToggle(Runnable watcher) {
        this.onToggle = watcher == null ? () -> { } : watcher;
    }

    public boolean isFolded() {
        return folded;
    }

    public void setFolded(boolean value) {
        if (folded == value) {
            return;
        }

        folded = value;

        PREFS.putBoolean(key + ".folded", value);
        apply();
        onToggle.run();
    }

    /** @return how tall this is with the content folded away */
    public int foldedHeight() {
        return getComponent(0).getPreferredSize().height;
    }

    private void apply() {
        arrow.setText(folded ? FOLDED : OPEN);
        content.setVisible(!folded);

        revalidate();
        repaint();
    }
}
