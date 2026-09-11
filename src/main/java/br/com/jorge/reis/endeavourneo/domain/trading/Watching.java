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
package br.com.jorge.reis.endeavourneo.domain.trading;

/**
 * Somebody counting the bars a run has got through.
 *
 * <h2>Why this is not {@code platform.Progress}</h2>
 *
 * <p>There is a {@code Progress} in {@code platform} already, with a stage name
 * and cooperative cancellation, and it is the right thing for a job the shell
 * runs. It is not used here because <b>no class in {@code domain} imports
 * {@code platform}</b> — the engine has no dependency on the application at all,
 * which is what lets it be run from a test, from a script, or overnight with no
 * shell around it. One import would end that, and it would end it for a callback
 * of one method.</p>
 *
 * <p>So the engine reports to whoever asked, in the engine's own terms: bars.
 * Turning bars into a percentage, a phase name, or a cancel button is the
 * caller's business, and callers differ.</p>
 *
 * <h2>The engine decides how often, not the caller</h2>
 *
 * <p>A run over a month of ticks is seventeen million bars. Calling back on
 * every one of them would be seventeen million calls to move a bar that repaints
 * sixty times a second — so {@link Backtest} reports <b>occasionally</b>, at a
 * cadence of its own choosing, and always once when it has finished.</p>
 *
 * <p>That means an implementation must not count the calls, or treat two
 * consecutive reports as adjacent bars. What it may rely on: the bar number
 * never goes backwards, and the last report of a run is the last bar of it.</p>
 *
 * <p>Called from whatever thread the run is on, which for the backtest window is
 * a worker and never the interface thread. An implementation that touches the
 * screen has to get itself there.</p>
 */
@FunctionalInterface
public interface Watching {

    /** Reports nothing to nobody, which is what an unwatched run costs. */
    Watching NOBODY = (reached, bars) -> { };

    /**
     * @param reached how many bars have been walked, from 1 to {@code bars}
     * @param bars    how many there are in all; zero for a series with none
     */
    void at(int reached, int bars);

    /**
     * Asked at the same moments as {@link #at}, and no more often.
     *
     * <h2>Stopping has to be cooperative</h2>
     *
     * <p>Java has no safe way to stop a thread from outside — {@code
     * Thread.stop} was deprecated because it releases locks mid-update and
     * leaves shared state torn. So a run that wants to be stoppable has to ask,
     * and one that never asks simply runs to the end.</p>
     *
     * <p>It is a request and not an order: the engine decides how to unwind, and
     * what it does is <b>return what it has</b> rather than throw. A partial
     * result is not a lie as long as whoever asked for the stop is the one
     * reading it — and that is always the case, because the only way to ask is
     * to hold this object.</p>
     *
     * @return whether somebody asked this run to stop
     */
    default boolean cancelled() {
        return false;
    }
}
