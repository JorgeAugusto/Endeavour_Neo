# Anatomy of a trading chart window

A map of every element in the Nelogica Profit chart window, taken from a live
screenshot of `WINFUT 15Min` on 02/09/2026.

**Why this exists.** Profit is what the author uses every day, so it is the
reference for what a chart window is expected to contain. This document names
each element, says what data it needs, and records whether it belongs in this
project — so the decision about each one is made once, deliberately, rather than
by accident while building something else.

Portuguese labels are kept verbatim: they are the artefact being described, not
prose.

---

## The layout

```
┌─ A ─────────────────────────────────────────────────────────────────────────────────┐
│ ⏱▾  │ ● WINFUT 15Min +3,04% ✕ │ +           02/09/2026 18:15  ⋯  ─  □  ✕            │
├─ B ─────────────────────────────────────────────────────────────────────────────────┤
│ ● WINFUT 15Min  [ícones]  (Abr 187.950 Máx 188.035 Mín 187.820                       │
│                            Fch 187.900 V -0,02% A 187.951)      ⌗▾  ⌐▾  +           │
├─ C ─────────────────────────────────────────────────────────────────────────────────┤
│ JorgeReis_EMAs_1m_5m [17 21 0 8 21]  ▪187.945 ▪187.879 ▪187.160 ▪184.972            │
├─ D ──┬──────────────── E ─────────────────────────────────┬─ F ──┬───── H ──────────┤
│ ▲    │                                                    │190.310│ Cross Order ▾    │
│ ■    │                                                    │       │ S  Sim 1120790   │
│ ✎    │              candles                               │189.615│ Estrat. ▾    +   │
│      │                                                    │       │ Preço    187.940 │
│      │                                          ┌─────────┤188.920│ Stop Of.     150 │
│      │                                          │187.976 ◀│       │ Qtd            1 │
│      │                                          │187.900  │188.225│ [1] [2] [3]      │
│      │                                                    │       │ Total 37.588,00  │
│      │                                                    │  ...  │ C Lim │ V Lim    │
│      │                                                    │       │ C Mer │ V Mer    │
│      │                                                    │184.055│ Cancel│ Inverter │
│      │                                                    │      ▐│ Zerar            │
├─ G ──┴────────────────────────────────────────────────────┴───────┤ Cancelar + Zerar │
│ 01/09/2026 17:45 │ 18:15  09:15  09:45 ... 17:45  18:15           │ Resultado  $ %   │
│      01/set      │                  02/set                        │ Qtd            - │
├───────────────────────────────────────────────────────────────────┤ Res. Aberto 0,00 │
│ ◀▶ scrollbar horizontal                                           │ Res. do Dia 0,00 │
├─ I ───────────────────────────────────────────────────────────────┤ Médio       0,00 │
│ ‹› │1M-25 ✕│1M-25-Novo│1M-25-Leve│7R│6-S-2025│5M-Trade│...│ ⋮ │ « │ Total       0,00 │
└───────────────────────────────────────────────────────────────────┴──────────────────┘
```

Status column throughout: **done** — already in Endeavour Neo; **planned** —
agreed and not built; **maybe** — worth discussing; **no** — deliberately out of
scope, with the reason.

---

## A — window bar

| element | what it is | needs | status |
|---|---|---|---|
| instrument tab | one open chart, with the day's change in the title (`+3,04%`) | day open vs last | maybe |
| new tab | opens another chart | — | **done** (a window, not a tab) |
| server clock | the exchange's time, not the machine's | a feed | no |
| window controls | minimise, maximise, close | — | **done** |

The change in the tab title is a small idea worth stealing: it means a glance at
the tab bar reports every instrument without opening any of them.

## B — chart header

| element | what it is | needs | status |
|---|---|---|---|
| instrument + timeframe | `WINFUT 15Min` | — | **done** (window title) |
| `Abr` `Máx` `Mín` `Fch` | OHLC of the bar under the cursor, or of the last bar | OHLC | **done** (in the readout box) |
| `V -0,02%` | change over the previous close | previous close | planned |
| `A 187.951` | *Ajuste* — the day's settlement price | daily settlement | no |
| header controls | three; meaning not established | — | open question |

> **`Ajuste` is not available to us.** It is the exchange's official settlement
> price, published once a day, and it is not in the minute series. It is also
> what revealed the scale error in the old base — the Profit shows it raw while
> drawing an adjusted series. Worth knowing about; not worth showing.

## C — indicator legend

| element | what it is | needs | status |
|---|---|---|---|
| indicator name | `JorgeReis_EMAs_1m_5m` | — | planned |
| parameters | `[17 21 0 8 21]`, inline | the indicator's settings | planned |
| value per line | one box per plot, in that plot's colour | value at the hovered bar | planned |

**This is the contract to copy.** An overlay that can report its value at a given
bar feeds the legend, the crosshair and the readout from one method. Chartsy
names it `getValues(chartFrame, i)`; the equivalent here would be
`Overlay.valueAt(bar)`.

Showing the parameters inline matters more than it looks: a chart with three
moving averages is unreadable without knowing which is which, and opening a
dialog to find out breaks the reading.

## D — indicator side buttons

Three small controls. Best guess: visibility, colour, edit. **Not established.**

## E — plot area

| element | what it is | status |
|---|---|---|
| candles | hollow for up, filled for down | **done** (filled both ways; hollow is a variant) |
| background | vertical gradient | maybe |
| grid | **absent** — no horizontal lines at all | ours draws them |

> Profit draws no horizontal grid. It is a defensible choice: the price labels
> alone carry the levels, and the lines compete with the data. Ours draws a very
> faint grid. Worth comparing side by side before deciding.

## F — price axis

| element | what it is | needs | status |
|---|---|---|---|
| price labels | round values | — | **done** |
| last price tag | **two values**, `187.976` and `187.900` | last trade and bar close, presumably | **done** (one value) |
| vertical scrollbar | scrolls the price range | — | no — we drag the axis instead |
| drag to scale | not present in Profit's axis | — | **done** (ours has it) |

The two-value tag is an open question. If the upper is the last trade and the
lower the bar's close, the distinction only exists live and does not apply to a
closed series.

## G — time axis, in two bands

| element | what it is | status |
|---|---|---|
| hours | `09:15 09:45 ...` on round intervals | **done** |
| **day band** | a **separate strip below**, `01/set` \| `02/set`, split at the change | planned |
| first label | full date, highlighted | maybe |

> **The separate day band is better than what we do.** Ours marks the day change
> by swapping the time label for a date, which loses an hour label and makes the
> reader notice the substitution. A second strip states the day continuously and
> costs nothing at the hour level. This is the clearest single improvement in the
> whole screenshot.

## H — order panel

The reason this document covers the whole window: a **simulator mode** needs
every field here.

### Account and strategy

| field | what it is | simulator |
|---|---|---|
| `Cross Order: Desativado` | prevents self-crossing on the exchange | no — meaningless without a real book |
| `S  Sim 1120790` | account; `Sim` marks a **simulator** account | yes — the mode indicator |
| `Estrat. <Nenhuma>` | the automated strategy attached | planned |

### Order fields

| field | what it is | simulator |
|---|---|---|
| `Preço 187.940` | limit price | yes |
| `Stop Of. 150` | stop offset **in points**, not a price | yes |
| `Qtd 1` | contracts | yes |
| `[1] [2] [3]` | quantity shortcuts | yes |
| `Total 37.588,00` | notional exposure, `Qtd × price × point value` | yes |

> `Stop Of.` being an **offset in points** rather than a price is worth copying:
> it moves with the entry instead of having to be retyped for every trade.

### Actions

| button | what it does | simulator |
|---|---|---|
| `C Limite` / `V Limite` | buy/sell at the limit price | yes |
| `C Mercado` / `V Mercado` | buy/sell at market | yes |
| `Cancel Ord.` | cancels pending orders, keeps the position | yes |
| `Inverter` | reverses the position | yes — **two orders, never one** |
| `Zerar` | closes the position at market | yes |
| `Cancelar ordens + Zerar` | both, in one click | yes |

> **`Inverter` is two orders on B3, not one.** The exchange nets the position;
> going from long 1 to short 1 means selling 2, and the two legs can fill at
> different prices. A simulator that treats it as one instantaneous flip
> understates the cost of every reversal.

### Result

| field | what it is | simulator |
|---|---|---|
| `Resultado $ %` | currency or percentage | yes |
| `Qtd` | current position, signed | yes |
| `Res. Aberto` | unrealised, on the open position | yes |
| `Res. do Dia` | realised today | yes |
| `Médio` | average entry price | yes |
| `Total` | realised plus unrealised | yes |

## I — workspace tabs

Twelve saved layouts (`1M - 25`, `5M- Trade`, `Topos e Fundos`…), each a whole
arrangement of windows and indicators.

This is the Eclipse *perspective*, and it is used heavily here — twelve of them.
Ours stores window geometry but has no named layouts. **Planned**, once there is
more than one kind of window to arrange.

---

## What a simulator actually requires

The panel is the easy half. The hard half is deciding **how an order fills**, and
that decision is where a simulator becomes either useful or a machine for
producing false confidence.

The conventions already settled in the previous project's `LevelBacktest` carry
over, and they were chosen to be pessimistic on purpose:

| situation | the rule | why |
|---|---|---|
| stop and target in the same bar | **the stop wins** | a candle does not say which price came first, and assuming the target flatters exactly the violent bars that decide the curve |
| the session opens past the stop | fill **at the open**, worse than the stop | pretending otherwise invents liquidity that was not there |
| cost | **6,5 points round trip** | measured, not assumed: 0,25 fee plus 2,0 slippage per side |

> A simulator that fills optimistically produces the same false confidence as a
> backtest with a lookahead bug, and it is harder to catch because it feels like
> trading. Every fill rule should be written down with its justification, the way
> the table above does, and each should be tested with a case that fails when the
> rule is reversed.

**Minimum for a first useful simulator:**

1. position state — signed quantity, average price
2. market orders, filling at the next bar's open
3. realised and unrealised result, with cost applied per side
4. `Zerar` and `Inverter`, the latter as two legs
5. the result rows in the panel

Everything else — limit orders resting in a book, partial fills, stop orders
triggering — comes after, and each addition needs its own fill rule written down.

---

## Open questions

1. **The three buttons in zone D.** Visibility, colour, edit?
2. **The two-value price tag in zone F.** Last trade and close? Price and
   settlement?
3. **The three controls at the right of zone B.**
4. **`Res. do Dia` versus `Total`** — is `Total` the accumulated result across
   days, or realised plus unrealised for today?

---

## Suggested order of work

Ranked by value earned per hour spent, not by where they sit on the screen.

| # | item | zone | why first |
|---|---|---|---|
| 1 | day band under the time axis | G | clearest improvement over what we have; small |
| 2 | indicator legend with `valueAt(bar)` | C | the contract everything else hangs off |
| 3 | simulator: position, market orders, result | H | turns the chart into a tool |
| 4 | change against the previous close | B | one line, asked for by everyone |
| 5 | hollow candles for up bars | E | a variant of an existing style |
| 6 | named layouts | I | only worth it with more window kinds |
