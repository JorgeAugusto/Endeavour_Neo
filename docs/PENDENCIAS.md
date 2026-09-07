# Pendências

O que está aberto em 06/09/2026, e que não se deduz do código nem do histórico.
Escrito para sobreviver a compactação e a troca de sessão.

---

## 1. Defeitos conhecidos e não corrigidos

### A interpolação de escala maior nunca agiu — em nenhum indicador

**Corrigido em 06/09/2026 pela auditoria A5.** O que estava escrito aqui antes
dizia que o RSI interpolava e o estocástico não, e que a linha do estocástico
saía em degraus ao lado da do RSI inclinada. **O sintoma estava errado**: as duas
saem em degraus, porque `OwnScale.smooth` é matematicamente idêntico ao
`OwnScale.map`.

`indexOfClosed` devolve o maior `c` com `coarse.timeAt(c+1) <= t`, e o `smooth`
mede a rampa de `timeAt(c)` a `timeAt(c+1)` — intervalo que `t` já ultrapassou.
O fator `along` é sempre ≥ 1, é grampeado em 1,0, e o resultado é sempre
`slow[c]`. A caixa "Inclinar entre os pontos fechados", **ligada por padrão** na
média, no RSI e nas bandas, não faz nada. Evidência em
`auditoria/00-estado.md`.

**Ordem de correção, nesta sequência:**

1. **`OwnScale.smooth`** — a rampa tem de correr durante a barra em formação: de
   `timeAt(closed+1)` a `timeAt(closed+2)`, saindo de `slow[closed-1]` e chegando
   em `slow[closed]`. A linha atrasa em vez de adiantar, que é o lado honesto de
   errar.
2. **`OwnPeriodTest.interpolationStaysBehind`** — hoje só afirma um teto
   (`value <= closeAt(bar)`) e passaria com o `smooth` apagado; foi ele que deu a
   licença falsa. Tem de afirmar que a linha **inclina**: dois valores
   consecutivos diferentes dentro de uma mesma barra grossa.
3. **Só então o `SlowStochastic`** ganha o campo `interpolate` (padrão ligado,
   como nos outros), a chamada a `OwnScale.smooth`, e o controle numa aba de
   escala própria — hoje ele tem duas abas e enfia a escala no fim dos
   parâmetros, contra três abas do RSI. Quebra o `appearance` posicional de 15
   campos, que é o único da área **sem teste de ida-e-volta**; quebrar formato
   salvo é aceitável, ver [[quebrar-redes-salvas-nao-e-restricao]].

---

## 2. Decisão pendente sobre a auditoria

### Cortar a fase 3, ou mantê-la?

O plano original tinha três fases: áreas, lentes transversais, e **verificação
adversarial** com 3 lentes por ALTA e 2 por MÉDIA.

Descobri fazendo que **a fase 3 seria a mais cara de todas**, não a mais barata
como eu havia afirmado. Cada verificador é pequeno, mas com as nove áreas
completas seriam ~21 ALTA e ~87 MÉDIA, ou seja **~237 agentes** e algo entre 3 e
5 milhões de tokens.

**Proposta feita e não decidida:** cortar a fase 3 e verificar os ALTA **inline**,
lendo o código na conversa conforme cada área cai. Já funcionou cinco vezes hoje
ao custo de um `grep` e um `sed` cada — 4 dos 7 ALTA abertos já estão verificados
assim, com o trecho gravado no `auditoria/00-estado.md`. Os MÉDIA carregariam a
evidência do agente, que é como as auditorias do `endeavour` sempre fizeram.

---

## 3. Os 47 ALTA da auditoria (6 já corrigidos)

Detalhe completo em `auditoria/a1-series.md`, `a2-renko-ticks.md`,
`a3-chartcanvas.md`, `a4-layout-eixos.md`, `a5-indicadores.md`, `a6-replay.md`, `a7a-platform.md`, `a7b-shell-series.md`, `a8a-testes-dominio.md`, `a8b-testes-interface.md`. Evidência das verificadas em `auditoria/00-estado.md`.

| # | onde | o quê | verificado |
|---|---|---|---|
| A1-1 | `Timeframe.java:309` | escala acima de 1440 min colapsa em D1 e carimba à meia-noite | ✅ |
| A2-1 | `ReplaySeries.java:173` | replay congela para sempre quando `pathFor` devolve null | — |
| A2-2 | `Renko.java:418` | `made` não zera entre extremos; **as caldas saem curtas** | ✅ |
| A3-1 | `ChartCanvas.java:1206` | sem decimação: 1,05 M barras varridas cinco vezes por repintura | ✅ |
| A3-2 | `ChartCanvas.java:1011` | 4 varreduras e 3 `Files.walk` **na EDT** antes do SwingWorker | — |
| A3-3 | `ChartCanvas.java:1094` | replay troca a série sem recalcular overlays: média de candle sobre tijolo | — |
| A3-4 | `ChartCanvas.java:1084` | guarda compara só o período; construção velha substitui a série ao vivo | ✅ |
| A4-1 | `OverlayLegend.java:201` | `hoveredBar()` cru: legenda imprime `—` assim que o ponteiro sai do canvas | ✅ |
| A4-2 | `ChartHeader.java:359` | tooltip varre 1,05 M barras convertendo fuso **na EDT**, a cada `mouseMoved` | ✅ |
| A4-3 | `LineStyle.java:54` | dois `int[]` por repintura e `drawPolyline` de 1 M pontos; sem decimação | ✅ |
| A5-1 | `OwnScale.java:145` | `smooth` é idêntico ao `map`: a interpolação nunca agiu | ✅ |
| A5-2 | `OwnPeriodTest.java:154` | teste sem dentes; só afirma teto, deu a licença falsa ao A5-1 | ✅ |
| A5-3 | `MovingAverageDialog.java:114` | spinner aceita shift −500: a barra `i` lê a média de `i+3` | ✅ |
| A5-4 | `StudyStack.java:121` | `calculate` síncrono na EDT em cinco pontos; 1 M de barras trava a janela | ✅ |
| A6-1 | `ReplayPanel.java:600` | o `done()` nunca aplica a velocidade: combo diz 60, replay anda a 1× | ✅ |
| A6-2 | `ReplayPanel.java:195` | o campo "Até" se corrige dentro da notificação do `Document`: `IllegalStateException` na EDT | — |
| A6-3 | `ReplaySession.java:706` | o congelamento eterno do A2 visto de fora: ícone em "tocando", 25 `refresh()`/s para sempre | — |
| A6-4 | `ReplaySession.java:263` | "carregando ticks..." sem saída quando a leitura falha | — |
| A6-5 | `ReplayFeed.java:150` | varredura da série inteira no `ActionListener` do combo | — |
| A6-6 | `ReplaySessionTest.java:152` | `assertEquals(x.size(), x.size())` — tautologia | ✅ |
| A6-7 | `ReplayRangeTest.java:179` | teto puro; apagar `MOST_SESSIONS` não quebra o teste | ✅ |
| A7a-1 | `JobService.java:285` | `OutOfMemoryError` escapa do `catch` e vira **sucesso com valor nulo** | ✅ |
| A7a-2 | `JobService.java:170` | trabalho cancelado não dispara callback nenhum; `isCancelled()` volta a `false` | — |
| A7a-3 | `JobServiceTest.java:144` | `assertTrue(a \|\| b)` com `b` afirmado sozinho na linha seguinte | — |
| A7a-4 | `LayerBoundaryTest.java:120` | só lê linhas `import `; referência qualificada passa invisível | ✅ |
| A7a-5 | `SeriesCatalog.java:314` | a série **ajustada** recebe o rótulo `WINFUT`; as cruas viram variantes | ✅ |
| A7b-1 | `MainWindow.java:1035` | sair pelo menu apaga todos os gráficos do workspace | ✅ **corrigido** |
| A7b-3 | `Segmentable.java:122` | 824.881 barras varridas no `ActionListener` do combo | — |
| A7b-4 | `MainWindow.java:362` | série que falha ao ler vira `RandomWalkSeries` sob o nome do instrumento | ✅ |
| A7b-5 | `MainWindow.java:420` | ternário de ramos idênticos; o workspace nunca se conserta | ✅ **corrigido** |
| A7b-6 | `SeriesWindow` | MODELESS sem registro: a última a fechar apaga o que a outra gravou | — |
| A8a-1 | `TimeframeTest` | **nenhuma escala acima de 30 min**; `ofMinutes` não aparece no arquivo | ✅ |
| A8a-4 | `TickRenkoTest.java:142` | `assertTrue(half < whole)` não fixa valor; lookahead de um negócio passa | ✅ |
| A8a-7 | `SeriesMergeTest` | `joinsInOrder` lê quatro `closeAt` e **zero `timeAt`** | — |
| A8b-1 | `BollingerBandsTest:182` | teto só; o meio honesto e o que lê a barra não fechada cabem os dois | — |
| A8b-2 | `MovingAverageTest` | **zero testes de escala própria**; `setOwnPeriod` não aparece | — |
| A8b-3 | `RelativeStrengthTest` | idem; escala própria só em round-trip de texto | — |
| A8b-4 | `MovingAverageTest:130` | só shift +1; o spinner aceita −500 e `setShift` não grampeia | — |
| A8b-5 | `SeriesSummaryTest:146` | afirma só que diferem; inverter o ternário passa | ✅ |
| A8b-6 | `OverlayLegend` | 632 linhas, **zero testes**; reverter a correção deixa a suíte verde | ✅ |

### O padrão que se repetiu três vezes: teste que afirma só um teto

Três dos vinte e um ALTA são testes sem dentes, e os três têm a mesma forma —
`assertTrue(x <= limite)` e nenhuma asserção de piso:

| teste | deixou passar |
|---|---|
| `RenkoWickBoundsTest` | a calda curta do A2-2 |
| `OwnPeriodTest.interpolationStaysBehind` | a interpolação que nunca agiu (A5-1) |
| `ReplayRangeTest.thereIsACap` | nada ainda — mas apagar `MOST_SESSIONS` passa |
| `LayerBoundaryTest` | qualquer referência qualificada a `ui` fora de um `import` |
| `TickRenkoTest:142` | um negócio de lookahead por quadro no caminho dos ticks |
| `SeriesMergeTest.joinsInOrder` | esquecer `- starts[part]` em `ConcatSeries.timeAt` |

E o pior de todos não é asserção fraca, é ausência: o `TimeframeTest` **nunca
dobra acima de 30 minutos**, então o defeito do `Timeframe:309` não tinha teste
para atravessar.

Mais um que é pior que teto: `ReplaySessionTest:152` compara uma expressão
consigo mesma.

**Vale uma lente transversal só disto** quando as áreas acabarem: `grep` por
método de teste cujas asserções sejam todas `assertTrue` com `<=` ou `>=`, sem
nenhum `assertEquals` de valor.

### O outro padrão: nada decima

Cinco achados são a mesma ferida em lugares diferentes — 1 M de barras varridas
na EDT: `ChartCanvas.java:1206` (A3-1), `ChartHeader.java:359` (A4-2),
`LineStyle.java:54` (A4-3), `StudyStack.java:121` (A5-4), `ReplayFeed.java:150`
(A6-5) e `MainWindow.java:354` (A7a, o mais caro de todos). É **uma correção
só**, feita no `Viewport`, não seis.

E existe um serviço pronto para isso que ninguém usa: o `JobService` tem
**dois `submit` no repositório inteiro**, e o javadoc de um deles
(`MainWindow:863`) diz que ele não faz nada útil e para ser apagado quando
houver trabalho de verdade. O trabalho de verdade são esses seis.

---

## 3b. As seis correções de `423d15a` estão quase todas desprotegidas

| correção | teste que a segura |
|---|---|
| `OwnScale.smooth` | ✅ `OwnPeriodTest`, três dentes novos |
| `OverlayLegend` | ❌ **nenhum** |
| `JobService` | ❌ nenhum |
| `MainWindow.exit` | ❌ `MainWindowTest:220` exercita só o `windowClosing` |
| `MainWindow` ternário | ❌ nenhum |
| `ReplayPanel` velocidade | ❌ nenhum |

**Cinco das seis podem ser desfeitas sem que nada avise.** Dar dentes a elas vem
antes de corrigir mais defeitos: a lição desta auditoria inteira é que correção
sem teste é meia correção, e quatro dos sete ALTA de teste existem exatamente
porque alguém consertou algo e não prendeu.

---

## 4. Trabalho adiado por decisão dele

- **Simulador** — replay em que dá para operar. Conceito fechado em
  `auditoria/../BACKTEST.md`; nada construído.
- **Backtest** — o avaliador único das duas frentes de pesquisa. Documento com
  as seis decisões pendentes em `BACKTEST.md`; o motor existe no `endeavour` e
  seria portado (~1.240 linhas de núcleo), com duas correções no caminho: custo
  de 6,5 pontos e o motor não executa stop.
- **Linguagem portável de execução** — registrada em `EXECUCAO-PORTAVEL.md`,
  parada de propósito até haver backtest, família clássica escrita, e pelo menos
  uma estratégia portada à mão para o NTSL.
- **Teste end-to-end com janela visível** — modo escolhido por ele; a biblioteca
  é a AssertJ-Swing. Nada construído.
- **As seis capacidades a trazer de outros softwares** — congeladas até a versão
  estabilizar. Ver a memória `lista-do-que-trazer-de-outros-softwares`.

---

## 5. O que a auditoria já atestou como LIMPO

- **As sete regras do renko conferem todas no código.** Nenhuma diverge; duas
  divergem apenas na documentação. A régua validada contra o Profit está íntegra.
- **O `ChartCanvas` não viola nenhuma das três regras de domínio medidas** —
  futuro, base crua e renko. Delega as três corretamente.
