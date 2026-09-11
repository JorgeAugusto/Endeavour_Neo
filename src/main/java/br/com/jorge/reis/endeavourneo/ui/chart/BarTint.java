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
 */
package br.com.jorge.reis.endeavourneo.ui.chart;

import java.awt.Color;

/**
 * Something that recolours individual bars.
 *
 * <h2>Why the style has to ask, instead of the indicator drawing</h2>
 *
 * <p>Everything else on this chart is drawn <b>over</b> the price, and an
 * indicator that wanted to say something about a bar drew a mark near it. This
 * one says it by changing the bar itself, and that cannot be done from on top:
 * whatever is painted under the candles is covered by them, and whatever is
 * painted over them would have to redraw the body and the wick — a second copy
 * of the drawing, which would then have to learn about hollow bodies, about
 * columns narrower than a pixel, and about untraded renko bricks.
 *
 * <p>So the style asks. One question, asked where the colour is already being
 * decided, against an interface that says nothing about patterns — any future
 * indicator that wants to tint a bar answers it too.
 */
@FunctionalInterface
public interface BarTint {

    /** Tints nothing: every bar keeps the colour it would have had. */
    BarTint NONE = bar -> null;

    /**
     * @param bar an index into the series being drawn
     * @return the colour that bar must be drawn in, or <b>null</b> to leave it
     *         the ordinary rising or falling colour
     *
     * <p>Null and not a "no tint" colour, because there is no such colour: the
     * answer depends on whether the bar rose or fell, and only the style knows
     * that by the time it asks.</p>
     */
    Color at(int bar);
}
