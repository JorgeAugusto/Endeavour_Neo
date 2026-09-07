# Estado da auditoria

Atualizado em 06/09/2026, 20:10. **A auditoria está completa: nove áreas e quatro lentes.** Este arquivo existe para a auditoria sobreviver
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
| A7a — platform e Launcher (+ 8 testes) | 2.229 | 217.473 | 5 ALTA, 14 MÉDIA, 11 BAIXA | `a7a-platform.md` |
| A7b — casca, preferências, janela de séries | 5.071 | 277.987 | 8 ALTA, 15 MÉDIA, 12 BAIXA | `a7b-shell-series.md` |
| A8a — os testes de domínio | 3.831 | 209.261 | 7 ALTA, 6 MÉDIA, 5 BAIXA | `a8a-testes-dominio.md` |
| A8b — os testes de interface | 4.315 | 220.324 | 6 ALTA, 9 MÉDIA, 5 BAIXA | `a8b-testes-interface.md` |
| **soma das áreas** | **38.737** | **2.108.675** | **47 ALTA, 104 MÉDIA, 97 BAIXA** | |

## As quatro lentes transversais

Não auditam uma área: auditam **uma pergunta em todo o código**, por `grep`
dirigido, lendo ±40 linhas em volta de cada ocorrência, e recebendo o índice dos
253 achados com ordem de reportar só o que as áreas não viram.

| lente | a pergunta | tokens | achados |
|---|---|---:|---|
| L1 | a thread está certa, e o que foi ligado é desligado? | 159.899 | 2 ALTA, 5 MÉDIA, 3 BAIXA |
| L2 | algum número desenhado sabe do futuro? | 156.676 | 2 ALTA, 1 MÉDIA, 1 BAIXA |
| L3 | o que abre fecha, o que grava dá a volta, o que aloca cabe? | 212.856 | 1 ALTA, 10 MÉDIA, 5 BAIXA |
| L4 | o texto está no bundle, e o que devia ser um é um? | 212.592 | 0 ALTA, 10 MÉDIA, 8 BAIXA |
| **soma das lentes** | | **742.023** | **5 ALTA, 26 MÉDIA, 17 BAIXA** |

| **TOTAL DA AUDITORIA** | | **2.850.698** | **52 ALTA, 130 MÉDIA, 114 BAIXA** |

### A estimativa das lentes errou por 4,6×

O desenho previa **~0,1× o corpus** por lente, ou seja algo perto de 40k cada.
Custaram **185k em média**. A razão é que `grep` dirigido reduz o que se **lê**,
não o que se **julga**: a L3 abriu 68 ferramentas e a L4, 79 — mais que qualquer
área. Uma lente boa confere cada ocorrência e escreve por que 23 das 24 estão
certas, e essa conta não cabe em 40k.

**Para a próxima auditoria: ~185k por lente, não 40k.** Ainda é barato perto de
uma área (54 tok/linha × 5.000 linhas), mas não é troco.

## Áreas pendentes

| área | linhas | ~tokens |
|---|---:|---:|
| A4 — holder, layout, legenda, eixos, estilo | 5.167 | ~310k |
| A5 — indicadores, estudos, painéis, diálogos | 6.929 | ~415k |
| A8b — os testes de interface | 4.315 | ~235k |
| **restante** | **4.315** | **~235k** |

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

### ✅ A7a — a base AJUSTADA é a que recebe o nome canônico

`SeriesCatalog.displayOf:314` tira a escala, tira o prefixo do instrumento, e usa
o que sobra como sufixo:

```java
if (rest.startsWith(instrument)) {
    rest = rest.substring(instrument.length());
}

String market = Messages.market(instrument);

return rest.isBlank() ? market
        : market + "-" + rest.replace("-", "").toUpperCase(Locale.ROOT);
```

Para `win-1m` — a série **ajustada por razão** — não sobra nada, então ela cai no
ramo `rest.isBlank()` e recebe `Messages.market("win")`, que o bundle resolve na
linha 59: `navigator.group.win = WINFUT`.

| arquivo | na tela |
|---|---|
| `win-1m` (**ajustada**) | **WINFUT** |
| `winfut-1m` (crua, TESTE) | WINFUT-FUT |
| `winn-1m` (crua, BUSCA) | WINFUT-N |
| `winfut-full-1m` (crua) | WINFUT-FULL |

Está ao contrário: a base que inflava os anos antigos em até 67% é a que parece
canônica, e as três cruas parecem variantes dela. A ajustada já saiu do
repositório, mas a função que a batiza continua, e um workspace salvo a traz de
volta com esse nome. **Confirmado.**

### ✅ A7a — `JobService.java:285` relata OOM como sucesso

```java
} catch (Exception | StackOverflowError e) {
    failure = e;
} finally {
    handle.settle(value, failure, stopped);
```

Um `OutOfMemoryError` — a falha mais provável deste aplicativo, com 1 M de
barras — não é `Exception` nem `StackOverflowError`. Escapa do `catch`, o
`finally` roda com `failure == null` e `value == null`, e o chamador recebe um
**sucesso com valor nulo**. **Confirmado.**

### ✅ A7a — `LayerBoundaryTest.java:120` não tem dentes

```java
if (!trimmed.startsWith("import ")) {
    continue;
}
```

Referência totalmente qualificada passa invisível — e **é assim que este
repositório escreve**: `br.com.jorge.reis.endeavourneo.platform.Settings.workspace()`
(`ReplayPanel:188`), `platform.Messages.market(...)` (`SeriesCatalog:331`). A
regra está cumprida hoje por sorte, não por vigilância. **Quarto teste sem
dentes da auditoria.**

### ✅ A7b — sair pelo menu apaga todos os gráficos abertos

```java
private void exit() {
    closeCharts();
```

`closeCharts` roda um fechamento por gráfico, e cada fechamento chama
`rememberCharts`, que reescreve a lista aberta uma entrada mais curta até
esvaziá-la. O guarda existe (`rememberCharts:253`, `if (leaving || restoring)`) e
**o comentário dele descreve este defeito exato**: *"Closing the application
closes every chart, and each close would rewrite this list one chart shorter
until it was empty."* O X da barra de título arma o guarda via `prepareToLeave`;
o menu não armava. **Confirmado. CORRIGIDO em `423d15a`.**

### ✅ A7b — ternário de ramos idênticos

```java
String title = uniqueTitle(SeriesCatalog.has(name) ? series : series);
```

Os dois ramos são a mesma expressão. A guarda pertence a `asked`, porque `name`
já caiu para `defaultName()` na linha 412 e `has(name)` é verdadeiro dos dois
lados. **Confirmado. CORRIGIDO em `423d15a`.**

### ✅ A7b — série que falha ao ler vira ruído desenhado

O `catch (IOException)` de `seriesFor:362` avisa no console e no rodapé, e então
**cai** na linha 369, `return new RandomWalkSeries(2_000, 135_000.0)`. O javadoc
do próprio método diz que isso é *"never the answer when a series exists and
fails to read"*. O aviso atenua, mas o gráfico desenha preços inventados sob o
nome do instrumento. **Confirmado. NÃO corrigido** — a decisão de o que
desenhar no lugar é dele.

### ✅ A8a — `TimeframeTest` não exercita escala nenhuma acima de 30 minutos

O arquivo usa `ONE_MINUTE`, `FIVE_MINUTES`, `DAILY` e `WEEKLY`. **`ofMinutes` não
aparece uma única vez**, e é exatamente por ali que passa o defeito do
`Timeframe:309` (escala acima de 1440 min colapsa em meia-noite). Não é asserção
fraca: é **buraco de cobertura inteiro**. `MOST_MINUTES = 43_200` deixa o leitor
digitar até 30 dias, e nenhum teste vai por lá. **Confirmado.**

### ✅ A8a — `TickRenkoTest.java:142` não fixa valor

```java
assertTrue(half.size() < whole.size(),
        "the half-played session drew " + half.size()
                + " bricks, the same as the whole day");
```

Desigualdade estrita e nada mais: no fixture literal, 1 e 2 tijolos passam os
dois. É o único teste que guarda "o replay só vê o que já chegou" no caminho
dos ticks, e um lookahead de um negócio por quadro sobrevive a ele.
**Confirmado.**

### ✅ A8b — `SeriesSummaryTest.java:146` não diz qual é qual

```java
assertTrue(ticks != null && !ticks.equals(candles),
        "a chart built from ticks and one built from candles read the same");
```

Afirma só que os dois textos **diferem**. Inverter o ternário do
`SeriesSummary:75` — `fromTicks ? "summary.source.candles" : "...ticks"` —
mantém a diferença e faz o resumo dizer "candles" para um gráfico feito de
ticks. O próprio teste se chama *"the line that matters most"*. **Confirmado.**

### ✅ A8b — `OverlayLegend` tem 632 linhas e **zero** testes

`Get-ChildItem -Recurse -Filter "*Legend*"` sobre `src/test` não devolve nada.
Apagar a correção de `423d15a` deixa os 449 testes verdes. **Confirmado.**

---

## As seis correções de `423d15a`: quais estão protegidas?

A A8b entrou no meio do commit, releu o fonte depois dele e trocou a pergunta de
"o defeito existe?" para "a correção está protegida?". A resposta importa mais
que os achados:

| correção | teste que a segura |
|---|---|
| `OwnScale.smooth` | ✅ `OwnPeriodTest`, três dentes novos |
| `OverlayLegend` | ❌ **nenhum** — reverter deixa a suíte verde |
| `JobService` | ❌ nenhum |
| `MainWindow.exit` | ❌ `MainWindowTest:220` exercita só o `windowClosing` |
| `MainWindow` ternário | ❌ nenhum |
| `ReplayPanel` velocidade | ❌ nenhum |

**Cinco das seis podem ser desfeitas sem que nada avise.**

### ✅ L2-1 — o fuso parametrizado nunca chega à produção

A interface `Aggregation` declara **só** `apply(PriceSeries source)`; não existe
assinatura com fuso. E o `Timeframe`:

```java
@Override
public PriceSeries apply(PriceSeries source) {
    return apply(source, defaultZone());     // ZoneId.systemDefault()
}
```

Os seis chamadores de produção usam o de um argumento (`ChartCanvas:1200,1287`,
`MovingAverage:346`, `BollingerBands:383`, `RelativeStrength:342`,
`SlowStochastic:490`). O `apply(source, zone)` correto só é chamado do
`TimeframeTest`, que fixa `America/Sao_Paulo`. **O teste prova um comportamento
que a aplicação nunca executa** — uma terceira espécie de teste sem dentes, nem
asserção fraca nem cobertura ausente: exercita uma sobrecarga que o produto não
chama. **Confirmado.**

### ✅ L2-2 — o eixo de tempo divide em UTC, e o próprio código proíbe isso

```java
ZonedDateTime time = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone);  // :1895
long bucket = series.timeAt(i) / (step * 60_000L);                         // :1896
```

Duas linhas seguidas na mesma pintura, uma com fuso e a outra sem. `TIME_STEPS`
termina em **10.080** — uma semana em minutos — e aí a conta é exatamente
`epochDay / 7`. O `Timeframe:298` diz por escrito: *"Not epochDay / 7, which
starts weeks on a Thursday because 1970-01-01 was one."* **A divisão semanal do
eixo marca quintas.** **Confirmado.**

### ✅ L1-1 — a janela de replay não se solta na troca de idioma

```java
addWindowListener(new WindowAdapter() {
    @Override
    public void windowClosing(WindowEvent e) {   // ReplayWindow:63
        panel.release();
    }
});
```

`MainWindow.relaunch():820` chama `replay.dispose()`, e `dispose()` dispara
`windowClosed`, **nunca** `windowClosing` — este só vem do X da janela. O
`release()` não roda: o `Timer` de 40 ms segue avançando o mercado, `ticks.close()`
não roda, e `replay = null` apaga a última referência. **Não sobra caminho para
parar. Confirmado.**

### ✅ L3-1 — o layout padrão constrói lista vazia

Rastro de um renome incompleto:

| onde | diz |
|---|---|
| `MovingAverage.java:255` | `nameKey()` devolve **`overlay.movingAverage`** |
| `ChartLayouts.java:143` | o layout padrão pede **`overlay.ema`** |
| `messages.properties:143` | `overlay.ema = EMA` — a chave **existe** no bundle |
| `SettingsTest.java:87` | o fixture do teste usa **`overlay.ema`** |
| `ChartLayout:100`, `ChartLayouts:35`, `Overlay:46` | os javadocs dão `overlay.ema` de exemplo |

O catálogo procura por `nameKey`, não acha, devolve `null`, e o
`ChartLayout.build()` descarta nulos em silêncio — de propósito, para tolerar
layout de versão futura. **O `SettingsTest:87` passa porque usa a chave morta**:
prova a ida e volta do texto, nunca a construção. Quarta espécie de teste sem
dentes — o fixture compartilha o erro do produto.

Ressalva de escopo: quem abre um gráfico pelo caminho normal **vê** as três
médias, porque o `MainWindow:445` as adiciona em código. O que não funciona é o
*layout nomeado*. **Confirmado.**

### ✅ L4-3 — trocar o idioma não move o `Locale` da JVM

```java
public static void install() {
    Messages.setLocale(remembered().locale());   // Language:96 -- e só isso
}
```

Dez sítios leem `Locale.getDefault()` direto (`BarReadout:256`,
`ChartCanvas:163,647,2289`, `OverlayLegend:314`, `RulerReadout:157`,
`Sessions:118`, `StudyPane:886`, `DatePicker:183,196`), e os botões dos
`JOptionPane` o Swing escolhe sozinho. Em inglês numa máquina brasileira, a
pergunta sai em inglês com botões **Sim** e **Não**. **Confirmado.**

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

**Não há leitura de preço futuro no `endeavour_neo`** (L2) — a resposta à pergunta
mais cara de errar do projeto. Das 28 ocorrências de `size()-1`, 13 estão no
caminho de dados e todas são seguras; os irmãos do deslocamento negativo **não
existem** (`shift` só aparece na média, conferido um a um nas bandas, no RSI e no
estocástico); o `OwnScale` cumpre a regra do último candle fechado por
construção.

**24 aberturas de arquivo, 23 fecham** (L3), e as quatro `Files.walk` estão todas
em try-com-recursos. **`java.util.prefs.Preferences` não é usada em lugar
nenhum**, o que mata a suspeita do corte silencioso em 8.192 caracteres. Zero
coleções mutáveis escapando por getter; zero serialização Java.

**`removeXListener` não aparece uma vez na base** (L1), e está certo: conferido um
a um nos 84 casos de widget próprio. `invokeAndWait` não existe; nenhum
`doInBackground` toca componente.

**Nenhuma chave usada no Java falta no bundle** (L4) — zero, conferido por script
contra os `enum` que alimentam as cinco famílias que usam `get` sem `orElse`.
Nenhum construtor de componente carrega texto.

**`SeriesMap` e `RangeBar` decimam, e do jeito certo** (A7b) — o eixo é o
**índice de pregão**: 1.494 entradas contra 824.881 barras, **552× menor**, com
busca binária manual (`SeriesMap:126`) e aritmética pura para posicionar; o
`RangeBar` guarda três `int`. **É o modelo para corrigir as seis varreduras na
EDT.** A ressalva: a decimação está certa no desenho e ausente na aquisição.

**Os dois bundles têm 331 chaves cada, diferença zero** nos dois sentidos (A7a),
e as 88 chaves da área da casca existem nos dois arquivos (A7b).

**Cancelar cancela mesmo nas quatro páginas de preferências** (A7b), inclusive no
`JSpinner` (`COMMIT_OR_REVERT`). Ressalva: **não existe teste algum para
`ui/settings`**.

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
| A7a | 2.229 | 217.473 | 97,6 |
| A7b | 5.071 | 277.987 | 54,8 |
| A8a | 3.831 | 209.261 | 54,6 |
| A8b | 4.315 | 220.324 | 51,1 |
| | **38.737** | **2.108.675** | **54,4** |

**Use 54 tokens por linha** (nove medições), e saiba que o número varia muito: a A7a custou
**97,6 por linha**, o dobro da média, porque o `platform/` é pequeno e cruzado —
o agente leu 331 chaves de dois bundles, oito testes e os chamadores espalhados
pelo projeto todo. **Área pequena e muito referenciada custa mais por linha que
área grande e fechada.** nas projeções. O número sobe com a quantidade de
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
