# Estado da auditoria

Atualizado em 06/09/2026, 16:15. Este arquivo existe para a auditoria sobreviver
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
| **soma** | **13.998** | **743.812** | **10 ALTA, 38 MÉDIA, 47 BAIXA** | |

## Áreas pendentes

| área | linhas | ~tokens |
|---|---:|---:|
| A4 — holder, layout, legenda, eixos, estilo | 5.167 | ~310k |
| A5 — indicadores, estudos, painéis, diálogos | 6.929 | ~415k |
| A6 — replay | 2.519 | ~150k |
| A7 — platform, shell, settings, series | 7.500 | ~450k |
| A8a — testes de domínio | ~4.600 | ~275k |
| A8b — testes de interface | ~8.300 | ~500k |
| **restante** | **28.178** | **~1,7M** |

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

---

## Calibração de custo, medida

| área | linhas | tokens | por linha |
|---|---:|---:|---:|
| A1 | 3.185 | 151.776 | 47,7 |
| A2 | 2.996 | 196.636 | 65,6 |
| A3 | 2.650 | 183.192 | 69,1 |
| A4 | 5.167 | 212.208 | 41,1 |
| | **13.998** | **743.812** | **53,1** |

**Use 55 tokens por linha** nas projeções. O número sobe com a quantidade de
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
