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

import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.ui.settings.SettingsPage;

import java.util.List;

/**
 * One entry of the strategy list: what it is called, how it is set up, and how
 * to build one.
 *
 * <p>Three things a strategy needs before it can be offered, and they are here
 * together because the second is the reason the other two exist separately. A
 * strategy has <b>its own</b> parameters — a crossing has two periods and a kind
 * of average, the next one will have something else entirely — and they cannot
 * live on the command bar, because the bar would have to grow a row per
 * strategy and show the wrong one most of the time.</p>
 *
 * <p>So the bar says <i>which</i> strategy, the gear beside it opens
 * <i>that</i> strategy's screen, and {@link #build()} reads whatever the screen
 * saved. The bar never learns what a period is.</p>
 */
interface StrategyKind {

    /** @return what the combo shows */
    String label();

    /** @return its own settings screen, built fresh each time it is opened */
    SettingsPage page();

    /**
     * @return a strategy set up the way its screen was left
     *
     * <p>Read at the moment of the run, never held: the screen can have been
     * through twice since the window opened.</p>
     */
    Strategy build();

    /** @return every strategy the backtest offers, in the order it offers them */
    static List<StrategyKind> available() {
        return List.of(new MovingAverageKind(), new RangeBreakoutKind(),
                new PatternBreakoutKind(), new ChannelFadeKind(),
                new MomentumCrossKind());
    }
}
