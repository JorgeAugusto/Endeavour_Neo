# Verificação dos 52 ALTA

Isto é o que ficou no lugar da fase 3 da auditoria, cortada em 06/09/2026 por
decisão dele. Ver `../PENDENCIAS.md` seção 2 para o porquê.

**Todo ALTA foi reconferido centralmente contra o código**, lendo o arquivo e o
trecho, e não apenas aceito do relatório do agente. Onde a conferência derrubou
ou corrigiu o que o agente disse, está dito.

| estado | quantos |
|---|---:|
| **CORRIGIDO** — código alterado e teste que falhou antes | 16 |
| **VERIFICADO** — confirmado no código, ainda aberto | 36 |
| refutado | 0 |

Nenhum ALTA caiu na conferência. Isso é um dado sobre a qualidade dos agentes,
não sobre a minha: eles exigiam `arquivo:linha` e trecho literal, e tentavam
refutar o próprio achado antes de reportar.

---

## Corrigidos, com o commit

| # | onde | commit |
|---|---|---|
| A1-1 | `Timeframe.java:307` — escala acima de 1440 min colapsa em D1 | `2e411ba` |
| A2-2 | `Renko.java:418` — o segundo extremo é apagado; caldas curtas | `2e411ba` |
| A4-1 | `OverlayLegend.java:201` — legenda imprime `—` fora do canvas | `423d15a` |
| A5-1 | `OwnScale.java:136` — `smooth` é idêntico ao `map` | `423d15a` |
| A5-2 | `OwnPeriodTest.java:138` — o teste que deu a licença falsa | `423d15a` |
| A6-1 | `ReplayPanel.java:186` — a velocidade nunca chega na sessão | `423d15a` |
| A7a-1 | `JobService.java:285` — `Error` vira sucesso com `null` | `423d15a` |
| A7b-1 | `MainWindow.java:1035` — sair pelo menu apaga os gráficos | `423d15a` |
| A7b-2 | `MainWindowTest.java:220` — só exercitava a saída que funciona | `f22170a` |
| A7b-5 | `MainWindow.java:420` — ternário de ramos idênticos | `423d15a` |
| A8a-1 | `RenkoWickBoundsTest.java` — três asserções, todas de teto | `2e411ba` |
| A8a-2 | `TimeframeTest.java` — nada dobrado acima de 30 minutos | `2e411ba` |
| A8b-6 | `OverlayLegend` — 632 linhas, zero testes | `f22170a` |
| L1-1 | `ReplayWindow.java:63` — não solta o replay na troca de idioma | `dfc19c4` |
| L2-2 | `ChartCanvas.java:1896` — eixo divide em UTC; semana na quinta | `dfc19c4` |
| L3-1 | `ChartLayouts.java:143` — layout padrão constrói lista vazia | `dfc19c4` |

Cinco deles foram provados do jeito mais forte que existe: **o teste foi escrito
antes e visto falhar**. O `RenkoWickBoundsTest` dizia *"the tail stops at 105.0;
the market traded up to 118"*, e o `TimeframeTest`, *"consecutive days shared a
bar 0 times out of 30"*.

O A5-1 também **corrigiu o relatório**: o `PENDENCIAS` afirmava que o RSI
interpolava e o estocástico não. Os dois saíam em degraus — o `smooth` nunca
agiu para ninguém.

E o L3-1, ao ser corrigido, **revelou um segundo defeito que nenhum agente
viu**: `List.of(17, 55, 200)` não eram três médias, era uma entrada com três
parâmetros — uma média de período 17, deslocada 55 barras, de tipo 200. A chave
morta ao lado fazia a entrada ser descartada antes de alguém tentar construí-la.

---

## Verificados e ainda abertos

### Congelamento do replay — o mesmo defeito por dentro e por fora

**A2-1** `ReplaySeries.java:174`
```java
while (owed > 0) {
    if (path == null && !startForming()) {
        owed = 0;

        return;
    }
```
Zera o débito e sai. Toda chamada seguinte faz o mesmo: a série nunca mais
avança.

**A6-3** `ReplaySession.java:706` é o lado de fora. O `tick()` só chama
`timer.stop()` quando `live.finished()`, e
`finished() = completed >= day.size() && path == null`. Com a série travada,
`completed` nunca alcança `day.size()`. **O timer dispara 25 vezes por segundo,
para sempre**, com o ícone em "tocando" e sem mensagem nenhuma.

**A6-4** `ReplaySession.java:263` — `preparing` só cai se
`ticks.at(this.date) != null`. Leitura que falha deixa "carregando ticks..."
sem saída.

**A8a-3** — nenhum teste exercita isso: `null` não aparece **uma vez** no
`ReplaySeriesTest`.

### Varreduras de 1 M de barras na EDT — seis lugares, uma correção

| # | onde |
|---|---|
| A3-1 | `ChartCanvas.java:2631` — cinco laços por repintura, com alocação por barra |
| A3-2 | `ChartCanvas.java:1011` — `RenkoSource.sessionsIn` **antes** do `SwingWorker` |
| A4-2 | `ChartHeader.java:359` — `sessionsIn` a cada `mouseMoved` |
| A4-3 | `LineStyle.java:54` — dois `int[]` por repintura, `drawPolyline` de 1 M |
| A5-4 | `StudyStack.java:431` — `calculate` síncrono em cinco pontos |
| A6-5 | `ReplayPanel.java:360` — `followFeed` no `ActionListener` do combo |
| A7b-3 | `Segmentable.java:122` ← `SeriesWindow.java:392` — `Sessions.of` na troca do combo |

**O modelo da correção já existe no repositório**: o `SeriesMap` decima pelo
**índice de pregão** — 1.494 entradas contra 824.881 barras, 552× menor, com
busca binária e aritmética pura. E existe um `JobService` pronto que ninguém usa:
**dois `submit` no projeto inteiro**, e o javadoc de um deles (`MainWindow:863`)
diz para apagá-lo quando houver trabalho de verdade.

### Concorrência e ciclo de vida

**A3-3** `ChartCanvas.java:1094` — `if (replaying) { growing = built; ...
extendBricks(); repaint(); }`. A série nova entra na tela sem recalcular
indicador nenhum: média de candle desenhada sobre tijolo.

**A3-4** `ChartCanvas.java:1084` — a guarda compara **só o período**, e
`attachReplay`/`detachReplay` trocam a série sem mexer nele.

**A7a-2** `JobService.java:170` — `if (wasCancelled) { delivered = true;
return; }`. Trabalho cancelado **não dispara callback nenhum**, e o `finally`
remove o handle da lista, então `isCancelled()` volta a `false`.

**L1-2** `Launcher.java:83` — `addShutdownHook(new Thread(jobs::close, ...))` é o
único `close()` do projeto, e o relatório de falha vai para `System.err`, que o
`Console` redireciona para um `invokeLater`. A JVM não espera a EDT drenar.

**A7b-6** `SeriesWindow.java:191` — `open()` faz `new SeriesWindow(owner)` sem
consultar registro nenhum, e `Segmentation.set` começa com
`removeStartingWith(...)`: substituição total. Duas janelas sobre a mesma série,
e a última a gravar apaga o que a outra escreveu.

### Interface

**A6-2** `ReplayPanel.java:195` — o `settle` reage à mudança do campo **alterando
o mesmo campo**, e o `onChange` do `DatePicker` é disparado de dentro de um
`DocumentListener` (`DatePicker:138-152`). `IllegalStateException("Attempt to
mutate in notification")`, na EDT.

**A7b-4** `MainWindow.java:362` — o `catch (IOException)` avisa e **cai** no
`return new RandomWalkSeries(2_000, 135_000.0)`. O javadoc do próprio método diz
que isso é *"never the answer when a series exists and fails to read"*.

**A5-3** `MovingAverage.java:280` — `int at = bar - shift`, e o spinner aceita
**−500** (`MovingAverageDialog:114`). Com shift −3 a barra `i` mostra a média de
`i+3`. O javadoc só explica o shift positivo.

**A7a-5** `SeriesCatalog.java:314` — a série **ajustada por razão** é a única cujo
resto zera, então recebe o rótulo limpo `WINFUT`, enquanto as três cruas viram
`WINFUT-FUT`, `WINFUT-N`, `WINFUT-FULL`. A base envenenada é a que parece
canônica.

**L2-1** `Aggregation.java:55` — a interface declara **só** `apply(source)`. Os
seis chamadores de produção usam essa, que cai em `ZoneId.systemDefault()`. O
`apply(source, zone)` correto só é chamado do `TimeframeTest`: **o teste prova um
caminho que a aplicação não percorre**.

### Testes sem dentes

Onze dos 52 ALTA são testes. Cinco já foram corrigidos; estes seis continuam:

**A6-6** `ReplaySessionTest.java:152`
```java
assertEquals(replay.series().size(), replay.series().size());
```

**A6-7** `ReplayRangeTest.java:179` — `assertTrue(tenYears <= MOST_SESSIONS *
oneDay)`. Teto puro, e o fixture não tem dez anos: apagar o teto do produto não
quebra o teste.

**A7a-3** `JobServiceTest.java:178`
```java
assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) || steps.get() < 1_000, ...);
assertTrue(steps.get() < 1_000, ...);
```
O segundo operando é afirmado sozinho na instrução seguinte: a disjunção não
afirma nada. Pior, `finished.await()` espera os dez segundos inteiros porque o
callback nunca dispara — que é exatamente o A7a-2 que este teste deveria pegar.

**A7a-4** `LayerBoundaryTest.java:120` — `if (!trimmed.startsWith("import "))
continue;`. Referência qualificada passa invisível, e **é assim que este
repositório escreve** (`ReplayPanel:188`, `SeriesCatalog:331`).

**A7b-7** `SegmentPickingTest.java:208` — o `@DisplayName` diz "o mapa **desenha**
os segmentos" e a única asserção é `assertEquals(10, map.days().size())`, número
que o `showSeries` acabou de definir e que nada na pintura pode mudar.

**A7b-8** `StatusStripTest.java:84` — `each.getX() >= 0 &&
!label.getText().isBlank()`. **Largura nunca é lida.** Um rótulo de largura zero
passa; o teste chamado "a mensagem nunca some" passaria com a mensagem sumida.

### Buracos de cobertura, confirmados por contagem

| # | o quê | medido |
|---|---|---|
| A8b-2 | escala própria da média | `setOwnPeriod` aparece **0** vezes no `MovingAverageTest` |
| A8b-3 | escala própria do RSI | 1 ocorrência, e é **round-trip de texto**, não `calculate` |
| A8b-4 | deslocamento negativo | só `setShift(1)`; o negativo não tem teste |
| A8a-6 | `MONTHLY` | **0** ocorrências no `TimeframeTest` |
| A8a-3 | replay travado | **0** ocorrências de `null` no `ReplaySeriesTest` |

**A8b-1** `BollingerBandsTest.java:198` — o teste *usa* escala própria, mas
desliga a interpolação (`setInterpolated(false)`) e afirma só um teto sobre uma
série **crescente**. Numa série que só sobe, a média honesta e a que lê a barra
não fechada cabem as duas debaixo de `closeAt(bar)`.

**A8a-5** `TimeframeTest.java:130` — o fixture parte de
`LocalDateTime.of(2026, 9, 2, 9, 0)`, **já alinhado** à grade de cinco minutos. O
defeito histórico que ele deveria guardar (ticks carimbados até 59 s atrasados)
produz 09:00 de qualquer jeito.

**A8a-4** `TickRenkoTest.java:142` — `assertTrue(half.size() < whole.size())`.
Desigualdade estrita e nada mais; um negócio de lookahead por quadro sobrevive.

**A8a-7** `HistoryBeforeReplayTest.java:75` — `joinsInOrder` promete ordem no
nome e lê **cinco `closeAt` e zero `timeAt`**.

---

## Uma imperfeição do índice, para quem for usá-lo

O `00-achados.md` é gerado por `indice.py` a partir dos treze relatórios, que
foram escritos em treze estilos. Ele extrai 56 linhas na seção ALTA, mas quatro
delas são um bloco de fixture do relatório A2 (`{100,100,100,100}` e irmãs) que
casaram com o padrão de lista. **Os ALTA reais são 52.** Vale corrigir o extrator
se ele for reusado.
