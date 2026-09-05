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
package br.com.jorge.reis.endeavourneo.ui.series;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;

/**
 * Two handles on one track: where the new segment starts and where it ends.
 *
 * <h2>Counted in sessions</h2>
 *
 * <p>The track is the session index, the same scale {@link SeriesMap} uses, so
 * a handle here lands exactly under the day it points at up there. It also
 * means <b>a handle can only stop on a session</b>: there is no way to drag one
 * onto a Sunday and then wonder why the count did not move.</p>
 *
 * <h2>Two handles rather than a block to drag</h2>
 *
 * <p>A block that is grabbed by its middle to move and by its edges to resize
 * is one gesture doing two jobs, and the edges end up six pixels wide. Two
 * handles are a control people already know, and the pair reads as a range even
 * standing still. What is lost is moving the whole span without changing its
 * size -- which the keyboard gives back, and which is the rarer thing to
 * want.</p>
 *
 * <h2>The keyboard</h2>
 *
 * <p>Left and right move the handle being driven by one session, page moves it
 * by twenty, home and end take it to the ends, and SPACE passes the keyboard to
 * the other handle. A control that can only be driven by dragging cannot be
 * driven at all by somebody who cannot drag.</p>
 */
final class RangeBar extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final int TRACK = 8;

    private static final int GRIP = 7;

    private static final int HEIGHT = 30;

    private static final int SIDE = GRIP + 2;

    /** How many sessions the series has; the track runs from 0 to this less one. */
    private transient int sessions;

    private transient int from;

    private transient int to;

    /** Which handle the keyboard is driving: false for the start. */
    private transient boolean tail;

    private transient boolean dragging;

    private transient boolean clashing;

    private transient Runnable onChange = () -> { };

    RangeBar() {
        setPreferredSize(new Dimension(520, HEIGHT));
        setMinimumSize(new Dimension(200, HEIGHT));
        setFocusable(true);

        addMouseListener(new MouseAdapter() {

            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                grab(e.getX());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = false;
            }
        });

        addMouseMotionListener(new javax.swing.event.MouseInputAdapter() {

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragging) {
                    move(at(e.getX()));
                }
            }
        });

        addKeyListener(new KeyAdapter() {

            @Override
            public void keyPressed(KeyEvent e) {
                if (nudge(e.getKeyCode())) {
                    e.consume();
                }
            }
        });
    }

    void onChange(Runnable listener) {
        onChange = listener == null ? () -> { } : listener;
    }

    /**
     * @param count how many sessions the series has
     * @param first where the new segment starts, as a session index
     * @param last where it ends
     */
    void setRange(int count, int first, int last) {
        sessions = Math.max(0, count);
        from = clamp(first);
        to = clamp(last);

        if (to < from) {
            to = from;
        }

        repaint();
    }

    void setClashing(boolean clash) {
        if (clashing != clash) {
            clashing = clash;

            repaint();
        }
    }

    int from() {
        return from;
    }

    int to() {
        return to;
    }

    private int clamp(int index) {
        return Math.max(0, Math.min(Math.max(0, sessions - 1), index));
    }

    private int span() {
        return Math.max(1, getWidth() - SIDE * 2);
    }

    private int x(int index) {
        if (sessions <= 1) {
            return SIDE;
        }

        return SIDE + (int) Math.round((double) index / (sessions - 1) * span());
    }

    private int at(int pixel) {
        if (sessions <= 1) {
            return 0;
        }

        return clamp((int) Math.round((double) (pixel - SIDE) / span() * (sessions - 1)));
    }

    private void grab(int pixel) {
        int wanted = at(pixel);

        // The NEARER handle, and the start when they are the same distance
        // away: two handles sitting on top of each other would otherwise be
        // impossible to separate, because one of them would always win.
        tail = Math.abs(wanted - to) < Math.abs(wanted - from);

        if (from == to) {
            tail = wanted >= to;
        }

        dragging = true;

        move(wanted);
    }

    private void move(int wanted) {
        int before = from * 100000 + to;

        if (tail) {
            to = Math.max(from, clamp(wanted));
        } else {
            from = Math.min(to, clamp(wanted));
        }

        if (before != from * 100000 + to) {
            repaint();
            onChange.run();
        }
    }

    /**
     * @param code the key that was pressed
     * @return whether it meant anything here
     *
     * <p>Apart from the listener so it can be driven without a focused window,
     * which is the only way a test can reach it: a key event handed to a
     * component that is not showing is swallowed by the focus manager long
     * before any listener sees it.</p>
     */
    boolean nudge(int code) {
        int step;

        switch (code) {
            case KeyEvent.VK_LEFT -> step = -1;
            case KeyEvent.VK_RIGHT -> step = 1;
            case KeyEvent.VK_PAGE_DOWN -> step = -20;
            case KeyEvent.VK_PAGE_UP -> step = 20;
            case KeyEvent.VK_HOME -> step = -sessions;
            case KeyEvent.VK_END -> step = sessions;
            case KeyEvent.VK_SPACE -> {
                // The one thing two handles cannot do with the mouse: swap
                // which one is being driven without moving either.
                tail = !tail;
                repaint();

                return true;
            }
            default -> {
                return false;
            }
        }

        move((tail ? to : from) + step);

        return true;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();

        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            int mid = getHeight() / 2;

            g.setColor(SeriesColors.free());
            g.fillRoundRect(SIDE, mid - TRACK / 2, span(), TRACK, TRACK, TRACK);

            g.setColor(SeriesColors.rule());
            g.drawRoundRect(SIDE, mid - TRACK / 2, span(), TRACK, TRACK, TRACK);

            if (sessions == 0) {
                return;
            }

            int left = x(from);
            int right = x(to);

            g.setColor(clashing ? SeriesColors.clash() : SeriesColors.fresh());
            g.fillRoundRect(left, mid - TRACK / 2, Math.max(2, right - left),
                    TRACK, TRACK, TRACK);

            paintGrip(g, left, mid, !tail);
            paintGrip(g, right, mid, tail);
        } finally {
            g.dispose();
        }
    }

    private void paintGrip(Graphics2D g, int at, int mid, boolean driven) {
        g.setColor(SeriesColors.free());
        g.fillOval(at - GRIP, mid - GRIP, GRIP * 2, GRIP * 2);

        g.setStroke(new BasicStroke(driven && isFocusOwner() ? 3f : 2f));
        g.setColor(clashing ? SeriesColors.clash() : SeriesColors.fresh());
        g.drawOval(at - GRIP, mid - GRIP, GRIP * 2, GRIP * 2);
    }
}
