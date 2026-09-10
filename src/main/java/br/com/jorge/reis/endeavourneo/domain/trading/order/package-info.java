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
 * The execution vocabulary: everything a strategy may ask of the market.
 *
 * <p>This package is deliberately the poorest one in the project. A strategy's
 * <b>condition</b> layer — indicators, patterns, a network — may be as rich as
 * it likes and never has to leave here. Its <b>execution</b> layer has to be
 * translatable to NTSL and MQL5 to ever trade real money, so it can say only
 * what those can execute. Mixing the two is what makes a strategy that cannot
 * be ported, and it is discovered late, after the strategy has already survived
 * every validation.</p>
 *
 * <h2>Where this came from</h2>
 *
 * <p>Not from design. It was read out of section 15 of the NTSL manual and
 * checked against the forty-five robots in {@code RoboMateus1}, which is what
 * changed it: the vocabulary first sketched in {@code docs/EXECUCAO-PORTAVEL.md}
 * had three verbs — be long, be short, be flat — and <b>not one of his robots
 * fits in it</b>. What they actually do:</p>
 *
 * <ul>
 *   <li><b>Rest limit orders and wait.</b> The canonical robot's entry is a
 *       {@code SellShortLimit} posted ahead of the band — "pescaria" — that sits
 *       there while the filters allow. An engine that only trades at the next
 *       open cannot run it at all.</li>
 *   <li><b>Carry a quantity on every order.</b> Position size is not a fixed
 *       contract: it is a ladder that accumulates, and the quantity is part of
 *       the decision.</li>
 *   <li><b>Exit in pieces, at several prices at once.</b>
 *       {@code SellToCoverLimit(BuyPrice + AlvoNucleo, qty)} beside
 *       {@code SellToCoverLimit(BuyPrice + AlvoInicial, ...)} — partial targets,
 *       resting together.</li>
 * </ul>
 *
 * <h2>The shape</h2>
 *
 * <p>Twelve order verbs that are three questions asked at once, plus three
 * commands that act on the position as a whole:</p>
 *
 * <pre>
 *   {Buy, SellShort, BuyToCover, SellToCover} x {AtMarket, Limit, Stop}
 *       = {@link br.com.jorge.reis.endeavourneo.domain.trading.order.Side}
 *       x {@link br.com.jorge.reis.endeavourneo.domain.trading.order.Purpose}
 *       x {@link br.com.jorge.reis.endeavourneo.domain.trading.order.Trigger}
 *
 *   ClosePosition, ReversePosition, CancelPendingOrders
 * </pre>
 *
 * <p>{@link br.com.jorge.reis.endeavourneo.domain.trading.order.Desk} is the
 * front door — the NTSL names, with the quantity optional exactly where NTSL
 * makes it optional. A strategy calls the desk; the desk writes down
 * {@link br.com.jorge.reis.endeavourneo.domain.trading.order.Instruction}
 * values; the engine reads them.</p>
 *
 * <h2>What was left out, and why</h2>
 *
 * <ul>
 *   <li><b>The stop's optional limit price.</b> NTSL allows
 *       {@code BuyStop(Stop)}; the manual does not say what price the order then
 *       gets. Guessing changes fills on exactly the bars where a stop decides
 *       the trade. Both prices are required here — as his robots already
 *       write them.</li>
 *   <li><b>The "order when the condition is satisfied" execution mode.</b> The
 *       manual rules it out itself: it "is not compatible with the application's
 *       backtest, since it trades at any moment during the formation of the
 *       candles, which prevents the backtest from simulating this mode
 *       properly". Only the candle-close mode is modelled.</li>
 *   <li><b>Everything about sizing that is not a number of contracts.</b> No
 *       financial risk, no percentage of capital. NTSL takes contracts.</li>
 * </ul>
 *
 * <p>A verb enters this package only when NTSL, MQL5 and our own engine can all
 * execute it. NTSL is the poorest of the three, so in practice that means: when
 * NTSL has it.</p>
 */
package br.com.jorge.reis.endeavourneo.domain.trading.order;
