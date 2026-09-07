# Estado da auditoria

Atualizado em 06/09/2026, 17:10. Este arquivo existe para a auditoria sobreviver
a uma compactação de contexto ou a uma sessão nova: o que está aqui não depende
de ninguém lembrar da conversa.

Método e partição: [../AUDITORIA.md](../AUDITORIA.md)

---

## Áreas concluídas

| área | linhas | tokens | achados | arquivo |
|---|---:|---:|---|---|
| A1 — séries, arquivos e sessões | 3.185 | 151.776 | 1 ALTA, 8 MÉDIA, 12 BAIXA | `a1-series.md` |
| A2 — renko, ticks e replay | 2.996 | 196.636 | 2 ALTA, 12 MÉDIA, 13 BAIXA | `a2-renko-ticks.md` |
| A3 — ChartCanvas | 2.650 | 183.192 | 4 ALTA, 9 MÉDIA, 13 BAIXA | `a3-chartcanvas.md` |
| A4 — holder, layout, legenda, eixos, estilo | 5.167 | 212.208 | 3 ALTA, 9 MÉDIA, 9 BAIXA | `a4-layout-eixos.md` |
| A5 — indicadores, estudos, painéis, diálogos | 5.259 | 238.174 | 4 ALTA, 10 MÉDIA, 10 BAIXA | `a5-indicadores.md` |
| A6 — replay (produção + os 9 testes) | 4.034 | 201.644 | 7 ALTA, 12 MÉDIA, 7 BAIXA | `a6-replay.md` |
| **soma** | **23.291** | **1.183.630** | **21 ALTA, 60 MÉDIA, 64 BAIXA** | |

## Áreas pendentes

| área | linhas | ~tokens |
|---|---:|---:|
| A4 — holder, layout, legenda, eixos, estilo | 5.167 | ~310k |
| A5 — indicadores, estudos, painéis, diálogos | 6.929 | ~415k |
| A7a — platform e Launcher | 2.429 | ~125k |
| A7b — shell, settings, series | 5.071 | ~260k |
| A8a — testes de domínio | ~4.600 | ~275k |
| A8b — testes de interface | ~8.300 | ~500k |
| **restante** | **20.400** | **~1,0M** |

Depois das nove áreas: as **quatro lentes transversais** (EDT, tempo/lookahead,
persistência, i18n) por `grep` dirigido, e só então a **verificação
adversarial** dos ALTA e MÉDIA.

---

## O que EU verifiquei lendo o código, além do agente

Os achados abaixo foram reconferidos centralmente contra o código nesta sessão.
Os demais carregam a evidência do agente e podem ter falso positivo residual.

### ✅ A1 — `Timeframe.java:309`

```java
int minuteOfDay = local.getHour() * 60 + local.getMinute();
return local.toLocalDate().toEpochDay() * 1_440L + (minuteOfDay / minutes) * (long) minutes;
```

`minuteOfDay` nunca passa de 1439, então para qualquer `minutes > 1440` a divisão
dá zero e o carimbo vira meia-noite, todo dia. `MOST_MINUTES = 43_200` (linha 74)
deixa passar até 30 dias digitados. **Confirmado.**

### ✅ A2 — `Renko.java:418`

`int made = 0;` é declarado **fora** do laço `for (int step = 0; step < 2; step++)`
e acumula entre os dois extremos da barra. No fim de cada passo:

```java
if (made > 0) {
    sinceLow = anchor;
    sinceHigh = anchor;
}
```

Quando o passo 0 assenta caixa, `made > 0` continua verdadeiro no fim do passo 1
e apaga o extremo que o passo 1 acabou de registrar. **As caldas saem curtas.**
Correção: usar contador por passo para esse reset, não o acumulador da barra.

### ✅ A2 — `RenkoWickBoundsTest` não tem dentes para isso

As asserções são só de **teto** — "não usa cauda que as regras proíbem", "não
passa do próprio fechamento", `lowAt(up) >= 80.0 - 2*10`. **Nenhuma de piso.**
Uma cauda curta demais passa por todas. É por isso que o defeito sobreviveu.

### ✅ A3 — `ChartCanvas.java:1206` — não há decimação

```java
this.visibleBars = Math.max(1, Math.min(visibleBars, Math.max(1, this.series.size())));
```

`visibleBars` só é limitado pelo tamanho da série. Com 1,05 M barras na tela,
cada repintura varre a série inteira. **Confirmado por busca: não existe
decimação em lugar nenhum do arquivo.**

### ✅ A3 — `ChartCanvas.java:1084` — guarda incompleta

```java
protected void done() {
    if (asked != period) {
        library.close();
        return;
    }
```

Compara **só o período**. Como `attachReplay`/`detachReplay` trocam a série sem
mexer no período, uma construção velha passa pela guarda e substitui a série ao
vivo — a mistura candle/tick que a classe existe para impedir. **Confirmado.**

### ✅ A4 — `OverlayLegend.java:201` — a legenda apaga os valores

```java
int bar = canvas.hoveredBar();
```

O contrato está escrito na própria `ChartCanvas`: `/** @return the bar under the
mouse, or -1 when the mouse is elsewhere */`. E o recuo existe pronto, com o
javadoc dizendo exatamente para que serve:

```java
/** @return the last bar on screen, which is what a legend reads when the mouse is away */
public int lastVisibleBar() {
```

O irmão `StudyPane.java:702` usa direito — `return under >= 0 ? under :
canvas.lastVisibleBar();`. A legenda de preço não. Como ela fica **acima** do
canvas, o ponteiro passa por ela para chegar ao gráfico e todo indicador imprime
`—`. **Confirmado.**

### ✅ A4 — `ChartHeader.java:359` — varredura da série inteira na EDT

`SeriesSummary.html(...)` é montado dentro de `mouseMoved`, e dentro dele:

```java
static int sessionsIn(PriceSeries series) {
    ...
    for (int i = 0; i < series.size(); i++) {
        LocalDate day = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone).toLocalDate();
```

Um milhão de conversões de fuso por movimento do ponteiro. **Confirmado.**

### ✅ A4 — `LineStyle.java:54` — o mesmo defeito do A3-1, agora no estilo

```java
int[] xs = new int[to - from];
int[] ys = new int[to - from];
```

Duas alocações por repintura e um `drawPolyline` com um milhão de pontos. Nada
limita `to - from` além do tamanho da série. **Confirmado.**

### ✅ A5 — `OwnScale.smooth` é idêntico ao `map`: a interpolação nunca agiu

`indexOfClosed` devolve o maior `c` tal que `coarse.timeAt(c+1) <= t`. O `smooth`
mede a rampa no intervalo errado:

```java
long from = coarse.timeAt(closed);
long to   = closed + 1 < coarse.size() ? coarse.timeAt(closed + 1) : from;
double along = Math.max(0.0, Math.min(1.0, (fine.timeAt(i) - from) / (double) span));
```

Como `t >= coarse.timeAt(c+1) = to`, então `t - from >= span` e `along` é sempre
grampeado em **1,0**. O resultado é `slow[c-1] + (slow[c]-slow[c-1])*1.0 =
slow[c]` — exatamente o que o `map` já dava. A opção "Inclinar entre os pontos
fechados", **ligada por padrão** na média, no RSI e nas bandas, não faz nada.
**Confirmado por álgebra.**

Correção: a rampa tem de correr **durante a barra em formação** — de
`timeAt(closed+1)` a `timeAt(closed+2)`, saindo de `slow[closed-1]` e chegando em
`slow[closed]`. Assim a linha atrasa em vez de adiantar.

### ✅ A5 — `OwnPeriodTest.interpolationStaysBehind:154` não tem dentes

```java
assertTrue(value <= series.closeAt(bar),
        "interpolation reached bar " + bar + " with " + value);
```

Afirma só um **teto**. Nada afirma que a linha inclina. Com o `smooth` apagado o
teste passa igual — foi ele que deu a licença falsa ao A5-1. Mesmo padrão do
`RenkoWickBoundsTest` (A2).

### ✅ A5 — deslocamento negativo da média lê barras à direita

```java
this.shift = new JSpinner(new SpinnerNumberModel(average.shift(), -500, 500, 1));  // MovingAverageDialog:114
int at = bar - shift;                                                              // MovingAverage:280
```

Com `shift = -3`, a barra `i` mostra a média calculada sobre `i+3`. O javadoc da
classe só explica o shift **positivo** ("pushes it into the future", o uso
clássico) e nunca menciona o negativo — o limite inferior parece intervalo
simétrico por descuido. **Confirmado.**

### ✅ A5 — `StudyStack` recalcula síncrono na EDT

`study.calculate(canvas.source())` nas linhas 121, 148, 227, 282 e 434. O único
`invokeLater` do arquivo (342) é para mover um painel. Com 1 M de barras, agregar
para a escala maior mais O(n×período) trava a janela no OK do diálogo e na troca
de série. **Confirmado.**

### ✅ A6 — a velocidade escolhida nunca chega na sessão nova

O `done()` da `SwingWorker` que constrói a sessão (`ReplayPanel.java:600`) faz
três coisas e nenhuma é aplicar a velocidade:

```java
session = get();
session.watch(refresh);
...
refresh();
```

O único caminho até `setSpeed` é o `ActionListener` do combo:

```java
speed.setSelectedItem(rememberedSpeed());                                    // :186
speed.addActionListener(e -> ... .put("replay.speed", ...));                 // :188
speed.addActionListener(e -> withSession(s -> s.setSpeed((Integer) speed.getSelectedItem())));  // :190
```

O `setSelectedItem` da linha 186 roda **antes** de os listeners existirem, então
nem o restauro dispara. E `ReplaySession.speed = 1` (`:148`) é o padrão. **O
combo mostra 60 e o replay anda a 1×.** Correção: uma linha no `done()`.
**Confirmado.**

### ✅ A6 — `ReplaySessionTest.java:152` compara uma expressão consigo mesma

```java
assertEquals(replay.series().size(), replay.series().size());
```

Tautologia pura: passa com qualquer implementação de `seekFraction`. **Confirmado.**

### ✅ A6 — `ReplayRangeTest.java:179` é teto puro

```java
assertTrue(tenYears <= ReplaySession.MOST_SESSIONS * oneDay,
        "the cap did not hold: " + tenYears / oneDay + " sessions");
```

Só `<=`, e o fixture não tem dez anos de dado — apagar `MOST_SESSIONS` do
produto não quebra o teste. **Terceiro teste sem dentes da auditoria**, e o
terceiro do mesmo formato: asserção de teto onde faltava a de piso.

---

## O que está LIMPO e foi conferido

**As sete regras do renko conferem todas no código** (A2). Nenhuma diverge —
duas divergem apenas na documentação. A régua validada contra o Profit continua
íntegra.

**O ChartCanvas não viola nenhuma das três regras de domínio medidas** (A3):
futuro, base crua e renko. Ele delega as três corretamente.

**O `Viewport` está limpo** (A4): ida-e-volta exata nos dois eixos, casos
degenerados guardados. **O `Reordering` resistiu** à tentativa de quebra pela
ordem de avaliação dos argumentos. **O `Sessions` duplicado NÃO divergiu** — os
dois usam a mesma expressão de virada de dia, então é dívida de camada, não
ALTA.

**O `map` e o `indexOfClosed` do `OwnScale` estão corretos** (A5) — não leem o
futuro, conferidos passo a passo com bordas de 1 e 2 barras grossas. Os quatro
consumidores usam a regra única, as quatro respostas de `fitsOnPrice()` estão
certas, o `StudyStack.fits` implementa exatamente os dois "sim", a aritmética dos
quatro indicadores confere na mão, as 36 chaves de bundle existem nos dois
idiomas, e a extração do `LinePen` foi fiel ao `Pen` interno removido em
`a80f7ea`.

---

## Calibração de custo, medida

| área | linhas | tokens | por linha |
|---|---:|---:|---:|
| A1 | 3.185 | 151.776 | 47,7 |
| A2 | 2.996 | 196.636 | 65,6 |
| A3 | 2.650 | 183.192 | 69,1 |
| A4 | 5.167 | 212.208 | 41,1 |
| A5 | 5.259 | 238.174 | 45,3 |
| A6 | 4.034 | 201.644 | 50,0 |
| | **23.291** | **1.183.630** | **50,8** |

**Use 51 tokens por linha** nas projeções. O número sobe com a quantidade de
regras a conferir e de arquivos cruzados — a estimativa inicial de 12,5 tok/linha
errou por 4,8×.

### Leitura do painel de uso (plano Max 5x)

| | 15:17 | 15:23 | o que rodou no meio |
|---|---:|---:|---|
| Sessão (5h) | 16% | 19% | A3 |
| Semanal — todos | 28% | 29% | A3 |
| Semanal — Fable | 43% | 43% | — |

A A3 (183k) moveu o semanal em **1 ponto**, então o semanal inteiro é da ordem de
**15 a 30 milhões** — o restante da auditoria (~2,1M) cabe com folga. O balde do
Fable está mais gasto (43%) por causa da corrida v1 que não entregou nada.

A sessão sobe mais rápido que o semanal porque **a conversa também conta**: cada
turno reenvia o histórico, e ele cresce.

---

## Regras de operação em vigor

- **Uma área por vez**, nunca em paralelo, enquanto o agente for pesado.
- **Cada agente grava o próprio arquivo antes de terminar.**
- **Nenhum agente acima de 75% da sessão de 5h.** Com a partição atual nenhuma
  área chega perto, então não é preciso partir nada.
- **Nada de trocar de modelo por conta própria.** A escolha é do usuário, feita
  de forma explícita. Hoje: Opus 5.
