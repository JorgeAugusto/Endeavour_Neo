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

/**
 * The backtest engine: strategies, orders, fills and what they were worth.
 *
 * <p>Its job is to answer one question — <i>does this rule have an edge?</i> —
 * and its whole design is about not answering it too generously. It is not the
 * simulator: nothing here is animated and nothing takes a human decision.</p>
 *
 * <h2>Why it is ours and not ta4j's</h2>
 *
 * <p>{@code docs/BACKTEST.md} proposed ta4j for the engine, and that was right
 * until his robots were read. A ta4j {@code Strategy} is
 * {@code shouldEnter(i)} / {@code shouldExit(i)} returning a boolean, over one
 * position at a time. His robots rest limit orders at prices they choose and
 * wait for the market to come, accumulate a ladder, and exit in pieces at
 * several targets at once. None of the three fits, and the one execution model
 * in ta4j that mentions stops and limits derives them as ratios off the signal
 * price rather than accepting an absolute price from the strategy. The engine
 * that runs a moving-average crossing is not the engine that runs his robots.</p>
 *
 * <h2>The execution model, all of it</h2>
 *
 * <p>Read out of sections 11 and 13 of the NTSL manual, not invented:</p>
 *
 * <ol>
 *   <li><b>The code runs at the close of each candle</b>, and nothing it asks
 *       for can execute before the next open. NTSL's other mode — orders the
 *       moment a condition is satisfied — is not modelled, because the manual
 *       itself says that mode "is not compatible with the application's
 *       backtest".</li>
 *   <li><b>The book is rebuilt at every close from what the strategy asks.</b>
 *       An entry that has not executed by the end of the following candle "will
 *       be cancelled or edited when the next candle finishes, according to the
 *       user's strategy" — so not asking again <i>is</i> cancelling.</li>
 *   <li><b>An opening order that lands on the other side covers instead</b>, and
 *       inverts if it is bigger than what was open.</li>
 *   <li><b>A cover never inverts, and is ignored outright</b> when there is
 *       nothing on the other side to give back.</li>
 *   <li><b>Cover orders are one OCO</b>, so at most one fills per bar; the
 *       strategy re-asks for the rest at the next close.</li>
 * </ol>
 *
 * <p>Two things are ours because OHLC cannot say: orders fill nearest-to-the-open
 * first, and when a cover stop and a cover target are both reachable in one bar
 * the stop wins. The second is the conservative side rather than the true one,
 * which is why {@link
 * br.com.jorge.reis.endeavourneo.domain.trading.Result#ambiguousBars()} counts
 * how often it was needed.</p>
 *
 * <h2>What is not built yet</h2>
 *
 * <p>Slicing and the walk-forward test — and they come <b>before</b> any screen,
 * on purpose. A screen that shows one number over the whole period is a screen
 * that teaches you to read the wrong number, and by then it is too late: the
 * previous project's portfolio had {@code t = +3,75} in sample and {@code +0,93}
 * out of it, and that was only found because somebody went back to check.</p>
 */
package br.com.jorge.reis.endeavourneo.domain.trading;
