# A8b — os testes de interface

**Lidos:** 28 arquivos de teste sob
`src/test/java/br/com/jorge/reis/endeavourneo/ui/` (`chart/`, `chart/overlay/`,
`chart/study/`, `chart/style/`, `shell/NavigatorTreeTest`,
`shell/CollapsiblePaneTest`), 4.298 linhas de teste.

**Produção lida para poder mutar:** `OwnScale` (157), `overlay/MovingAverage`
(522), `overlay/BollingerBands` (658), `study/rsi/RelativeStrength` (448),
`study/stochastic/SlowStochastic` (606), `study/StudyStack` (510),
`study/StudyPane` (888, parcial), `ChartCanvas` (2.650, parcial),
`OverlayLegend` (632, parcial), `style/LineStyle` (76), `style/CandleStyle`
(147), `Viewport`, `SeriesSummary`, `Sessions`, `Measurement`, `Reordering`,
`CollapsiblePane`, `MovingAverageDialog` (só o spinner de deslocamento).

**Método:** para cada método de teste, escolher a mutação mais plausível do
código coberto e responder se o teste falharia. Toda afirmação abaixo foi
verificada contra o fonte de produção citado. Antes de reportar, procurei um
irmão que cobrisse o buraco; os que foram salvos por um irmão estão na seção
LIMPO.

> **Nota sobre o estado do código.** O commit `423d15a`
> (*"fix: seis ALTA da auditoria"*, 06/09/2026 21:35) entrou **durante** esta
> auditoria e corrigiu dois dos cinco defeitos do gabarito — `OwnScale.smooth`
> e `OverlayLegend`. Reli o fonte depois dele e refiz as duas seções
> correspondentes. **Nenhum arquivo de teste desta área foi tocado** por esse
> commit (só `OwnPeriodTest`, que está fora daqui), então todo o resto do
> relatório continua valendo contra o fonte atual. Os três defeitos restantes do
> gabarito — decimação, deslocamento negativo, `strokes()` das Bollinger — foram
> **segurados de propósito** pelo autor do commit, e estão abertos.
>
> Para os dois já corrigidos, a pergunta muda de "por que não pegou?" para
> **"a correção está protegida contra reversão?"** — e a resposta é não, nos
> dois casos: reverter qualquer uma delas deixa esta área inteira verde.

---

## Os cinco defeitos conhecidos: quem deveria ter pegado?

### 1. `OwnScale.smooth` era idêntico ao `map` — CORRIGIDO em `423d15a`, e a correção está desprotegida aqui

A versão defeituosa media a rampa de `timeAt(closed)` a `timeAt(closed+1)`, e
`indexOfClosed` só responde `closed` **depois** que `timeAt(closed+1)` passou:
`along` era sempre grampeado em 1,0 e `into[i] = slow[closed]` — o que o `map`
já tinha escrito. A versão atual (`OwnScale.java:136-172`) faz a rampa correr
durante a barra em formação, de `timeAt(closed+1)` a `timeAt(closed+2)`.

Os três indicadores que chamam `smooth` são a média
(`MovingAverage.java:364`), o RSI (`RelativeStrength.java:359`) e as bandas
(`BollingerBands.java:408-410`), e a opção nasce ligada nos três.

**Reverter a correção — voltar `shut`/`ends` para
`coarse.timeAt(closed)`/`coarse.timeAt(closed+1)` — não quebra um único teste
desta área.** Quem passou a pegar foi `OwnPeriodTest.interpolationStaysBehind`,
que está fora daqui e ganhou três dentes no mesmo commit. Os três testes dos
três indicadores que usam a opção continuam sem nada a dizer sobre ela.

**Quem deveria ter pegado — e por que não pegou:**

| teste | por que não pega |
|---|---|
| `MovingAverageTest` (226 linhas) | **não existe nenhum teste de escala própria.** `setOwnPeriod` não aparece uma vez no arquivo; o `calculate` só é chamado na escala do gráfico, onde `onItsOwnPeriod` nem é alcançado (`MovingAverage.java:295`). |
| `RelativeStrengthTest` (273 linhas) | idem. `setOwnPeriod` aparece só em `theAppearanceRoundTrip:240` e `noScaleOfItsOwn:258` — round-trip de texto. **Nenhum `calculate` com escala própria.** |
| `BollingerBandsTest` (289 linhas) | tem um teste de escala própria, `ownScaleDoesNotReadTheFuture:182` — e ele **desliga a interpolação na linha 198** (`bands.setInterpolated(false)`). O único caminho que exercitaria o `smooth` é o único que o teste evita. |

Nenhum dos três compara `interpolate=true` com `interpolate=false`. Como as duas
saídas são hoje idênticas, essa comparação é a asserção que faltava — e é a
única que pega o defeito. Ver A8b-1 e A8b-2.

### 2. `OverlayLegend` passava `hoveredBar()` cru — CORRIGIDO em `423d15a`, e a correção está desprotegida

A versão defeituosa fazia `int bar = canvas.hoveredBar();` e entregava esse
valor a `paintRow`. `ChartCanvas.hoveredBar():1323` devolve −1 quando o cursor é
nulo. A versão atual (`OverlayLegend.java:207-208`) faz o recuo, igual ao irmão
`StudyPane.readAt():702-705`:
```java
            int under = canvas.hoveredBar();
            int bar = under >= 0 ? under : canvas.lastVisibleBar();
```

**Quem deveria ter pegado:** ninguém, e essa é a resposta.
`grep -rl OverlayLegend src/test/java` devolve **zero arquivos**. As 632 linhas
do `OverlayLegend` não têm um único teste — nem antes nem depois da correção.
Apagar a linha 208 e passar `under` direto a `paintRow` deixa os 449 testes
verdes. O comentário de `OverlayTest.outsideTheSeriesIsNaN:88` chega a nomear o
caso — *"the legend asks for the hovered bar, and that is -1 when the mouse is
off the chart"* — e depois testa `MovingAverage.valueAt(-1)`, que é o outro lado
da fronteira. O teste garante que a legenda **não estoure**; nada garante que
ela **mostre um número**. Ver A8b-6, que continua ALTA: a correção existe e
nada a segura no lugar.

### 3. Nenhuma decimação em `LineStyle:54` e no varrimento do canvas

`LineStyle.paint:46-63` aloca `int[to - from]` e percorre bar a bar; nada
amostra.

**Quem deveria ter pegado:** `grep -rl LineStyle src/test/java` devolve **zero
arquivos**. `CandleBodyEdgeTest` pinta de verdade, mas numa imagem de 90×120
com **três barras**; `ChartCanvasTest` só toca aritmética estática
(`stretchForDrag`, `niceTimeStep`) e nunca pinta; `ChartGeometryTest` só mexe
em `Dimension`; `ViewportTest` usa fixtures de 3 a 8 barras. O maior fixture da
área inteira é `TimeAxisTest:122` com 130.000 barras — e ele não pinta, só
pergunta um booleano ao eixo. Não existe teste que meça custo de repintura, nem
fixture de 1 M de barras em nenhum caminho de desenho. Ver A8b-15.

### 4. `MovingAverage.valueAt:280` faz `bar - shift`, e o spinner aceita −500

`MovingAverage.java:280`: `int at = bar - shift;`. `setShift`
(`MovingAverage.java:193-195`) **não grampeia nada**. O spinner é
`new SpinnerNumberModel(average.shift(), -500, 500, 1)`
(`MovingAverageDialog.java:114`). Com shift −3, a barra `i` mostra a média
calculada em `i+3`.

**Quem deveria ter pegado:** `MovingAverageTest.shiftMovesTheLine:130`. Ele
chama `average.setShift(1)` — **só o sentido positivo** — e afirma
`assertEquals(20.0, average.valueAt(3)[0])`. Um deslocamento negativo nunca
entra no teste, e a leitura do futuro que ele produz não é afirmada em lugar
nenhum. Ver A8b-4.

### 5. O estilo e a espessura da linha do meio das bandas nunca desenham

`BollingerBands` sobrescreve `stroke()` (`BollingerBands.java:334`) e **não**
sobrescreve `strokes()`; herda o padrão de `Overlay.java:169`,
`List.of(stroke())`. Os irmãos `RelativeStrength:210` e `SlowStochastic:312`
sobrescrevem. `setMiddleLine`/`setMiddleThickness` existem e são guardados.

**Quem deveria ter pegado:** `BollingerBandsTest.settingsAreRemembered:214`,
que grava e relê `middleLine` e `middleThickness` (linhas 246-247). Ele prova
que o ajuste **sobrevive ao arquivo** e nada mais. Não existe no arquivo inteiro
uma chamada a `bands.strokes()` nem qualquer asserção que ligue o ajuste ao
desenho. Comparar: `MovingAverageTest.theStrokeIsItsOwn:215` faz exatamente
essa ponte para a média (`thick.stroke()`, largura, dash array) — o que mostra
que a asserção que falta nas bandas é conhecida do projeto. Ver A8b-13.

---

## Achados ALTA

### A8b-1 — "nenhuma banda sabe o que o mercado não disse" não pega a leitura do futuro

**Onde:** `ui/chart/overlay/BollingerBandsTest.java:180-210`

**Trecho:**
```java
    @Test
    @DisplayName("on its own scale, no band knows anything the market had not said")
    void ownScaleDoesNotReadTheFuture() {
        double[] rising = new double[30];

        for (int i = 0; i < rising.length; i++) {
            rising[i] = 100 + i;
        }

        PriceSeries series = closes(rising);
        BollingerBands bands = new BollingerBands(3);

        bands.setOwnPeriod("5m");
        bands.setInterpolated(false);
        bands.calculate(series);

        for (int bar = 0; bar < series.size(); bar++) {
            double middle = bands.valueAt(bar)[1];

            if (Double.isFinite(middle)) {
                assertTrue(middle <= series.closeAt(bar),
                        "bar " + bar + " drew a middle of " + middle
                                + ", which the market had not reached");
            }
        }
    }
```

**A mutação que passa:** trocar, em `BollingerBands.onOwnScale`
(`BollingerBands.java:403-405`), a chamada a `OwnScale.map` por um mapeamento
que usa a barra grossa **que contém** a barra fina — a mutação exata que este
teste diz existir para impedir.

A conta: preços `100+i`, barras grossas de 5 finas, `BollingerBands(3)`. Para a
barra fina `i` dentro da barra grossa `k` (fechamento `104+5k`), o meio correto
usa as três grossas fechadas `k-3..k-1` → média `94+5k`. O meio mutado, lendo a
barra que a contém, usa `k-2..k` → média `99+5k`. O fechamento da barra fina vai
de `100+5k` a `104+5k`. **Ambos são `<= closeAt(bar)`.** A asserção só tem teto,
e o valor mutado — que é uma leitura do futuro de até cinco minutos — cabe
folgado debaixo dele.

**Por que importa:** é o único teste de escala maior que sobrou depois que
`OwnPeriodTest.interpolationStaysBehind` foi declarado sem dentes na auditoria
anterior. Ele é o `@DisplayName` que dá licença a três indicadores, e não
distingue o mapeamento honesto do desonesto.

**Correção:** afirmar o **piso** junto com o teto. O meio correto é a média de
três fechamentos grossos que terminaram antes de `bar`, e sobre uma série que
sobe em passo constante esse número é calculável em fechado:

```java
int coarseIndex = bar / 5;               // a grossa que contém a barra
if (coarseIndex >= 4) {
    // as três grossas FECHADAS antes dela: k-3, k-2, k-1
    double honest = 94 + 5 * coarseIndex;
    assertEquals(honest, middle, 1e-9,
            "bar " + bar + " leu uma barra grossa que ainda não fechou");
}
```

Ou, sem depender da aritmética: rodar `calculate` sobre os primeiros `n` bares e
sobre a série inteira, e exigir que os `n` primeiros valores sejam idênticos —
um indicador que lê o futuro muda de resposta quando o futuro chega.

**Tentei refutar:** procurei em todo o conjunto por outra asserção de escala
maior sobre valores. `MovingAverageTest` e `RelativeStrengthTest` não têm
nenhuma (ver A8b-2 e A8b-3). `PaneSharingTest:119` chama `setOwnPeriod("5m")`
mas só para alimentar `StudyStack.fits`, que compara nomes e não calcula nada.
`OwnPeriodTest` está fora desta área e já foi reportado como sem dentes.
Nenhum irmão salva.

---

### A8b-2 — a média em escala maior não tem teste nenhum, e a interpolação nasce ligada

**Onde:** `ui/chart/overlay/MovingAverageTest.java` (arquivo inteiro, 226 linhas)

**Trecho:** o arquivo cobre aritmética, semente, ponderação, deslocamento
positivo, fonte, cor, round-trip e traço. A única entrada de escala é esta,
e ela nunca aparece:
```java
    private static MovingAverage over(PriceSeries series, int period) {
        MovingAverage average = new MovingAverage(period);

        average.calculate(series);

        return average;
    }
```
`setOwnPeriod` e `setInterpolated` não ocorrem no arquivo (verificado por grep
sobre a área inteira).

**A mutação que passa:** apagar o corpo inteiro de
`MovingAverage.onItsOwnPeriod` (`MovingAverage.java:337-366`) e substituí-lo por
`computeOver(series, values)` — isto é, **ignorar a escala própria por
completo** e desenhar a média do gráfico com outro nome. Toda a suíte fica
verde. A mutação menor — trocar `OwnScale.map` por um mapeamento que lê a barra
grossa que contém — também passa; e também a remoção do `if (interpolate)` em
`MovingAverage.java:363-365`, que é como o defeito de `OwnScale.smooth`
reapareceria por esta porta depois de corrigido pela outra.

**Por que importa:** a média com escala própria é o indicador multi-escala mais
usado do produto, a opção "Inclinar entre os pontos fechados" nasce ligada, e a
regra que ela tem de obedecer é a que o projeto já pagou uma vez
(`OwnScale.java:28-35`). Cobertura zero.

**Correção:** um teste espelho de A8b-1, sobre a média, com asserção de piso; e
um teste de **estabilidade prefixal**, que é o que prova "não olhar o futuro"
sem depender de aritmética fechada:

```java
@Test
@DisplayName("na escala maior, o valor de uma barra não muda quando chegam as seguintes")
void theFutureDoesNotChangeThePast() {
    MovingAverage curta = new MovingAverage(3);
    MovingAverage longa = new MovingAverage(3);

    curta.setOwnPeriod("5m");
    longa.setOwnPeriod("5m");
    curta.calculate(rising(12));      // até a barra 11
    longa.calculate(rising(30));      // as mesmas 12, mais 18 do futuro

    for (int bar = 0; bar < 12; bar++) {
        assertEquals(curta.valueAt(bar)[0], longa.valueAt(bar)[0], 1e-9,
                "a barra " + bar + " mudou quando o futuro chegou");
    }
}
```

**Tentei refutar:** `OverlayTest` também exercita `MovingAverage`, mas só na
escala do gráfico (`flat(...)`, `warmUpIsNaN`, `flatSeriesGivesTheSamePrice`).
`PlacementTest` e `OverlayOrderTest` usam a média como objeto, nunca calculam em
escala maior. `ChartLayoutTest` só faz round-trip de parâmetros. Nenhum irmão
salva.

---

### A8b-3 — o RSI em escala maior também não tem teste de cálculo

**Onde:** `ui/chart/study/rsi/RelativeStrengthTest.java:231-262`

**Trecho:** as duas únicas menções à escala própria no arquivo:
```java
        written.setInterpolated(false);
        written.setOwnPeriod("5m");
        ...
        assertFalse(read.isInterpolated(), "the interpolation flag came back on");
        assertEquals("5m", read.ownPeriod(), "the scale is what tells two of them apart");
```
Ambas dentro de `theAppearanceRoundTrip` — texto gravado e relido. `calculate`
nunca é chamado com `ownPeriod` diferente de nulo.

**A mutação que passa:** apagar o corpo de
`RelativeStrength.onItsOwnPeriod` (`RelativeStrength.java:332-360`) e cair no
cálculo da escala do gráfico. O RSI de 15 minutos desenharia o RSI de 1 minuto,
o rótulo diria "15m", e a suíte passa. Passa também trocar `OwnScale.map` pelo
mapeamento que lê a barra que contém.

**Por que importa:** o RSI é um dos três que chamam `smooth`, e é o que tem
`bounds()` fixo — logo é o que mais provavelmente vai dividir painel com o
estocástico em escalas diferentes (`StudyStack.fits`, primeiro "sim"). A
comparação entre escalas é o motivo de o painel existir, e as duas linhas que
seriam comparadas não têm um único teste de valor.

**Correção:** o mesmo teste de estabilidade prefixal de A8b-2, aplicado ao RSI;
mais a comparação `interpolate=true` × `interpolate=false`, que hoje é a única
asserção que pega o defeito 1.

**Tentei refutar:** `PaneSharingTest` e `PlacementTest` tocam o RSI só por
`fitsOnPrice()`/`nameKey()`. `OverlayCatalogTest.everyKindIsUsable:33` constrói
o RSI pelo catálogo, mas chama `valueAt(0)` **antes de qualquer `calculate`**.
Nenhum irmão salva.

---

### A8b-4 — o deslocamento só é testado para a frente; para trás ele lê o futuro

**Onde:** `ui/chart/overlay/MovingAverageTest.java:128-138`

**Trecho:**
```java
    @Test
    @DisplayName("the shift moves the line sideways")
    void shiftMovesTheLine() {
        MovingAverage average = new MovingAverage(3);

        average.setShift(1);
        average.calculate(bars(10, 20, 30, 40));

        // What was at bar 2 is now read at bar 3.
        assertEquals(20.0, average.valueAt(3)[0], 1e-9);
    }
```

**A mutação que passa:** já está no produto. `setShift`
(`MovingAverage.java:193-195`) é `this.shift = value;`, sem grampo, e o spinner
oferece de −500 a 500 (`MovingAverageDialog.java:114`). Com shift = −3,
`valueAt(3)` devolve `values[6]` — a média calculada três barras à frente. A
mutação de teste equivalente: **grampear `shift` a `Math.max(0, value)`** faria
o teste passar do mesmo jeito, e **remover** qualquer grampo (o estado atual)
também. O teste não distingue nada sobre o sinal negativo.

**Por que importa:** é leitura do futuro alcançável por um clique no spinner,
num indicador que nasce no preço e cuja linha o leitor usa para decidir. Fora
disto, `valueAt` é a mesma porta que a legenda lê.

**Correção:** decidir a regra (grampear em 0, ou aceitar e marcar) e afirmá-la:
```java
    @Test
    @DisplayName("um deslocamento negativo não pode adiantar a linha")
    void aNegativeShiftDoesNotReadAhead() {
        MovingAverage average = new MovingAverage(3);

        average.setShift(-1);
        average.calculate(bars(10, 20, 30, 40));

        // A barra 2 não pode mostrar a média que só existe na barra 3.
        assertNotEquals(30.0, average.valueAt(2)[0], 1e-9);
    }
```

**Tentei refutar:** procurei `setShift` em toda a área — ocorre uma única vez,
aqui. `MovingAverageTest.appearanceRoundTrip:164` não grava deslocamento;
`parameters()` (`MovingAverage.java:266-276`) só inclui o shift quando ele não é
zero, e nenhum teste passa um shift não-zero por ali. Nenhum irmão salva.

---

### A8b-5 — "diz se veio de ticks ou de candles" passa com os dois rótulos trocados

**Onde:** `ui/chart/SeriesSummaryTest.java:133-148`

**Trecho:**
```java
        String ticks = valueOf(SeriesSummary.rowsFor(series, "winfull-1m", "55R", true),
                "summary.source");
        String candles = valueOf(SeriesSummary.rowsFor(series, "winfull-1m", "55R", false),
                "summary.source");

        assertTrue(ticks != null && !ticks.equals(candles),
                "a chart built from ticks and one built from candles read the same");
```

**A mutação que passa:** inverter o ternário em `SeriesSummary.java:75`:
```java
Messages.get(fromTicks ? "summary.source.candles" : "summary.source.ticks")
```
Os dois valores continuam diferentes e não nulos. **O teste passa.** O resumo
passa a dizer "de candles" exatamente quando o gráfico foi construído de ticks,
e vice-versa.

**Por que importa:** o comentário do próprio teste diz que esta é *"the line that
matters most"* e que **nada mais na tela distingue as duas fontes** — medido em
8% a 27% de diferença na contagem de tijolos (mesma medição citada em
`TickRenkoOnChartTest:46-49` e em `RenkoSourceTest:133`). Um rótulo invertido é
pior do que rótulo nenhum: dá ao leitor uma resposta confiante e errada sobre a
proveniência do gráfico de onde ele tira número.

**Correção:** afirmar qual é qual, e não só que diferem:
```java
        assertEquals(Messages.get("summary.source.ticks"), ticks);
        assertEquals(Messages.get("summary.source.candles"), candles);
```

**Tentei refutar:** `grep -rn "summary.source" src/test/java` devolve só estas
duas linhas. `TickRenkoOnChartTest` afirma `canvas.isFromTicks()`, que é o
booleano **antes** do rótulo — não passa pelo `rowsFor`. `RenkoSourceTest` para
em `RenkoSource.allows`. Nenhum irmão salva.

---

### A8b-6 — a legenda não tem um único teste, e a correção que acabou de entrar nela está solta

**Onde:** ausência. `src/main/.../ui/chart/OverlayLegend.java`, 632 linhas,
zero arquivos de teste a mencionam.

**Trecho (produção, já corrigida em `423d15a`):**
```java
            int under = canvas.hoveredBar();        // OverlayLegend.java:207
            int bar = under >= 0 ? under : canvas.lastVisibleBar();

            paintDrop(g, overlays.size());

            for (int i = 0; i < overlays.size(); i++) {
                paintRow(g, overlays.get(i), bar, i, i * ROW_HEIGHT + 2);
            }
```
o irmão que sempre fez certo:
```java
    private int readAt() {                          // StudyPane.java:702
        int under = canvas.barUnderCursor();

        return under >= 0 ? under : canvas.lastVisibleBar();
    }
```

**A mutação que passa:** qualquer uma. Apagar a linha 208 e passar `under`
direto — que é reverter a correção de ontem, exatamente o defeito do gabarito.
Ou trocar `hoveredBar()` por uma constante, por `-1`, por `0`, ou remover a
coluna de valor de `paintRow`. **Nada na suíte muda de cor.**

**Por que importa:** a legenda é onde o leitor lê **o número** do indicador.
Com o ponteiro fora do canvas — que é o estado normal, e a legenda fica ACIMA do
canvas, então o ponteiro cruza por ela na entrada — ela imprimia `—`. O defeito
sobreviveu porque ninguém pergunta, e a correção continua sobrevivendo pelo
mesmo motivo: um defeito corrigido sem teste é um defeito com data marcada para
voltar. Esta é a única das seis correções de `423d15a` que não ganhou asserção
nenhuma.

**Correção:** um teste que não precisa de tela — `OverlayLegend` lê do canvas
e o canvas já é construível sem janela (é o que `OverlayNoticeTest` e
`PlacementTest` fazem):
```java
@Test
@DisplayName("com o ponteiro fora do gráfico, a legenda mostra o último valor visível")
void withoutTheCursorItReadsTheLastBar() {
    ChartCanvas canvas = new ChartCanvas();

    canvas.setSeries(new RandomWalkSeries(200, 100.0));
    canvas.setSize(900, 500);
    canvas.addOverlay(new MovingAverage(9));

    // Sem mouse: o recuo é a última barra na tela, não -1.
    assertEquals(canvas.lastVisibleBar(), OverlayLegend.readAt(canvas),
            "a legenda ia imprimir um traço em vez do valor");
}
```
(exige extrair o `readAt` do `OverlayLegend` como o `StudyPane` já tem — o que
é a própria correção do defeito.)

**Tentei refutar:** `OverlayTest.outsideTheSeriesIsNaN:88` cobre o **outro
lado**: que `MovingAverage.valueAt(-1)` devolve NaN sem estourar. Isso garante
que a legenda não quebra a pintura; garante também que ela imprime nada.
`OverlayNoticeTest` conta avisos de repintura, não conteúdo.
`SegmentChipTest.theColourFollowsThePosition:166` é o único teste da área que
lê pixel de um componente de cabeçalho, e é sobre cor de ícone de menu.
Nenhum irmão salva.

---

## Achados MÉDIA

### A8b-7 — "mais largo dá passo mais fino" passa com o parâmetro ignorado

**Onde:** `ui/chart/ChartCanvasTest.java:89-98`

**Trecho:**
```java
        assertTrue(ChartCanvas.niceTimeStep(span, 20) <= ChartCanvas.niceTimeStep(span, 4),
                "more labels asked for produced a coarser step");
```

**A mutação que passa:** em `ChartCanvas.niceTimeStep:2094`, trocar
```java
long target = Math.max(1, spanMinutes / Math.max(1, wantedLabels));
```
por
```java
long target = Math.max(1, spanMinutes);
```
— **ignorar quantos rótulos cabem**. Os dois lados passam a devolver 720, e
`720 <= 720` é verdadeiro. `timeStepIsRound:72` continua verde (720 está em
`TIME_STEPS`) e `absurdSpanIsClamped:102` também (o clamp final não muda).
Resultado real: um gráfico largo e um estreito recebem o mesmo passo, e a
largura da janela deixa de decidir quantos rótulos aparecem.

**Correção:** exigir desigualdade estrita onde ela tem de valer —
`assertTrue(niceTimeStep(600, 20) < niceTimeStep(600, 4))` — e fixar um valor:
`assertEquals(30, niceTimeStep(600, 20))`.

**Tentei refutar:** os outros três métodos de `ChartCanvasTest` sobre o eixo
(`timeStepIsRound`, `absurdSpanIsClamped`) foram conferidos acima e passam com a
mutação. Nenhum irmão salva.

### A8b-8 — os limites do arrasto são afirmados só de um lado

**Onde:** `ui/chart/ChartCanvasTest.java:61-68`

**Trecho:**
```java
        assertTrue(ChartCanvas.stretchForDrag(1.0, 100_000) >= 0.1,
                "an enormous downward drag went below the floor");
        assertTrue(ChartCanvas.stretchForDrag(1.0, -100_000) <= 20.0,
                "an enormous upward drag went above the ceiling");
```

**A mutação que passa:** `MINIMUM_STRETCH` de `0.1` para `0.9`
(`ChartCanvas.java:113`). A primeira asserção (`0.9 >= 0.1`) passa;
`dragDirection:38` só pede `down < 1.0`, e `0.9 < 1.0` passa;
`dragIsMultiplicative:51` usa arrasto de −50 (para cima), fora do piso;
`zeroDragIsIdentity:109` usa 2,5, no meio. **Toda a classe fica verde** com o
gráfico impedido de achatar além de 10% — o leitor perde nove décimos da faixa
de escala e nada avisa.

O teto está protegido por acidente: reduzir `MAXIMUM_STRETCH` a 1,5 quebra
`dragIsMultiplicative`, porque o grampo entra numa das duas razões e não na
outra. O piso não tem essa sorte.

**Correção:** afirmar que os extremos são **alcançados**, não apenas
respeitados:
```java
        assertEquals(0.1, ChartCanvas.stretchForDrag(1.0, 100_000), 1e-12);
        assertEquals(20.0, ChartCanvas.stretchForDrag(1.0, -100_000), 1e-12);
```

**Tentei refutar:** conferido método a método, acima. Nenhum irmão salva o piso.

### A8b-9 — o limiar do eixo (2 dias) nunca é cercado

**Onde:** `ui/chart/TimeAxisTest.java:92-130` (a classe inteira, 3 testes)

**Trecho:** os três casos, e os vãos entre eles:
```java
        ChartCanvas canvas = showing(every(24 * 60, 250));       // 250 dias  -> true
        ChartCanvas canvas = showing(every(1, 540));             // 9 horas   -> false
        ... Viewport.of(canvas.series(), ..., 0, 300));          // 5 horas   -> false
        ... Viewport.of(canvas.series(), ..., 0, 130_000));      // 90 dias   -> true
```
A regra de produção é `ChartCanvas.java:2042`:
```java
return series.timeAt(last) - series.timeAt(first) > 2 * 24 * 60 * 60_000L;
```

**A mutação que passa:** trocar o `2` por qualquer número entre 1 e 89. Com
`60 * 24 * 60 * 60_000L` (sessenta dias) os três testes continuam verdes: 250
dias > 60 ✓, 9 h e 5 h ✗, 90 dias > 60 ✓. Um gráfico de uma semana passaria a
ser rotulado com o relógio — que é exatamente o defeito relatado no javadoc da
classe (*"09:00, 09:03, 09:02, the minute each session happened to open"*),
apenas numa faixa diferente.

**Por que importa:** o teste declara cobrir **o que decide**, e o vão entre os
seus casos vai de 5 horas a 90 dias. O limiar é o comportamento inteiro.

**Correção:** dois casos colados no limite — 47 h e 49 h de bares na tela, um
falso e um verdadeiro. Isso prende o `2` e prende o `>` contra `>=`.

**Tentei refutar:** nenhum outro teste da área chama `axisSpeaksInDaysFor`
(grep). `ChartCanvasTest` cobre `niceTimeStep`, que é o passo, não a linguagem
do eixo. Nenhum irmão salva.

### A8b-10 — `fits` casa duas faixas pela MESMA instância de array

**Onde:** `ui/chart/study/PaneSharingTest.java:96` e `:126-132`

**Trecho:**
```java
    private static final double[] OSCILLATOR = {0, 100};
    ...
    @Test
    @DisplayName("indicadores diferentes de faixa fixa igual cabem juntos")
    void differentIndicatorsOnTheSameFixedRange() {
        assertTrue(StudyStack.fits(
                List.of(new Fake("study.stochastic", OSCILLATOR)),
                new Fake("study.rsi", OSCILLATOR)),
                "nought to a hundred is nought to a hundred whoever is saying it");
    }
```

**A mutação que passa:** em `StudyStack.fits:191-192`, trocar a comparação
elemento a elemento
```java
            if (mine == null || theirs == null
                    || mine[0] != theirs[0] || mine[1] != theirs[1]) {
```
por identidade de referência
```java
            if (mine != theirs) {
```
O `Fake` devolve **a mesma instância** `OSCILLATOR` dos dois lados, então este
teste passa. `differentFixedRanges:135` (instâncias diferentes → recusa) passa.
`twoFittedIndicators:143` (nulos → recusa) passa. `everyoneAlreadyInside:153`
passa, porque o `macd` de faixa nula ainda recusa. `oneThatDoesNotFit:188` passa.
**Toda a classe fica verde.**

E a consequência é real, não teórica: `RelativeStrength.bounds():220` e
`SlowStochastic.bounds():319` fazem `return new double[]{0.0, 100.0};` — um
array **novo a cada chamada**. Com a mutação, um RSI e um estocástico, que são o
caso do próprio javadoc da regra (`StudyStack.java:168-171`), passariam a ser
recusados no mesmo painel.

**Por que importa:** o segundo "sim" da regra — o que permite dividir painel
entre indicadores diferentes — é testado só com fakes que compartilham a
constante do teste, e nunca com os dois indicadores reais que o javadoc nomeia.

**Correção:** dar instâncias distintas ao `Fake`
(`new Fake("study.rsi", new double[]{0, 100})`) e acrescentar o caso real:
```java
    assertTrue(StudyStack.fits(
            List.of(new SlowStochastic(8, 3)), new RelativeStrength(14)),
            "o estocástico e o IFR correm de zero a cem e têm de dividir o painel");
```

**Tentei refutar:** `PlacementTest` cobre `fitsOnPrice()`, não `fits`.
`PaneOrderTest` nunca chama `fits`. Não existe teste que ponha um
`RelativeStrength` real ao lado de um `SlowStochastic` real. Nenhum irmão salva.

### A8b-11 — "a legenda fica" é afirmada por um valor que nada no caminho pode mudar

**Onde:** `ui/shell/CollapsiblePaneTest.java:37-62`

**Trecho:**
```java
        pane.setFolded(true);

        assertTrue(pane.isFolded());
        assertFalse(content.isVisible(), "o conteudo continua visivel");
        assertTrue(pane.foldedHeight() > 0,
                "dobrado ele nao mede nada, entao nao sobra legenda pra clicar");
```

**A mutação que passa:** em `CollapsiblePane.apply():143-149`, dobrar também o
cabeçalho — `getComponent(0).setVisible(!folded);`. A legenda some, e não
sobra nada para clicar de volta: o painel fica fechado para sempre, que é
exatamente o cenário que o javadoc do teste diz evitar (*"A panel that vanished
entirely would need a menu to come back"*). **As três asserções continuam
passando.**

Porque `foldedHeight()` (`CollapsiblePane.java:139-141`) é
`getComponent(0).getPreferredSize().height`, e o cabeçalho tem a seta com
`arrow.setPreferredSize(new Dimension(20, 20))` fixado no construtor
(`CollapsiblePane.java:90`). O valor é `>= 20` desde a construção, dobrado ou
não, visível ou não — `getPreferredSize` de um componente invisível continua
devolvendo o tamanho preferido. **Nada em `setFolded` pode fazê-lo cair a zero.**

É a mesma forma do achado ALTA já conhecido em `StatusStripTest:77`: uma medida
geométrica escolhida entre as que o método sob teste não toca.

**Correção:** afirmar a visibilidade do cabeçalho, que é o que o nome promete:
```java
        assertTrue(pane.getComponent(0).isVisible(),
                "dobrado, a legenda sumiu junto e não sobra onde clicar");
```

**Tentei refutar:** `itReportsEveryChangeAndOnlyChanges:66` conta avisos;
`itRemembers:88` cobre a persistência. Nenhum dos dois olha o cabeçalho.
Nenhum irmão salva.

### A8b-12 — três asserções negativas do renko de ticks são guardadas por um `sleep` fixo

**Onde:** `ui/chart/TickRenkoOnChartTest.java:235`, `:263`, `:348`

**Trecho** (o padrão, repetido três vezes):
```java
        canvas.setPeriod(new Renko(55, 2), "55R", "55R");

        Thread.sleep(300);
        SwingUtilities.invokeAndWait(() -> { });

        assertFalse(canvas.isFromTicks(),
                "the chart built a renko from the one day that has ticks and drew it beside "
                        + "a day that has none");
```

**A mutação que passa:** não é do produto, é do relógio. `isFromTicks()` só fica
verdadeiro **depois** que a construção em segundo plano termina. Numa máquina
carregada — a suíte abre janelas reais, e este mesmo arquivo já documenta uma
intermitência de uma execução em quatro (`NavigatorTreeTest:340-345`) — a
construção não termina em 300 ms e a asserção passa **sem que a regra tenha sido
exercida**. O próprio teste diz: *"Without this test the coverage check could be
deleted and the suite would stay green"*. Com o `sleep` como único guarda, essa
garantia não é dada.

Contraste com o irmão positivo `settle()` (linha 175), que espera até 5 s por
uma condição em vez de dormir um prazo.

**Correção:** dar ao teste um controle positivo — esperar até que a construção
tenha **comprovadamente** rodado (por exemplo, um contador de tentativas de
construção exposto para teste, ou construir primeiro um caso que DEVE virar
ticks com `settle()` e só então trocar para o caso que deve recusar) antes de
afirmar a negativa.

**Tentei refutar:** `RenkoSourceTest` testa `RenkoSource.allows` diretamente e
com dentes (`oneSessionShortIsStillRefused:131` tem os dois lados). Isso salva a
**regra**, e é por isso que este achado é MÉDIA e não ALTA. O que fica sem rede é
a **ligação** entre a regra e o canvas — que `ChartCanvas` de fato consulta o
`RenkoSource` antes de trocar a série.

### A8b-13 — o traço da linha do meio das bandas nunca chega ao desenho

**Onde:** `ui/chart/overlay/BollingerBandsTest.java:212-254`

**Trecho:**
```java
        set.setMiddleLine(MovingAverage.Line.DOTTED);
        set.setMiddleThickness(2);
        ...
        assertEquals(MovingAverage.Line.DOTTED, read.middleLine());
        assertEquals(2, read.middleThickness());
```

**A mutação que passa:** já está no produto — `BollingerBands` não sobrescreve
`strokes()` e herda `Overlay.java:169`, `List.of(stroke())`, uma única entrada
para três linhas. O teste prova só que o ajuste sobrevive ao arquivo.

**Correção:** a mesma ponte que a média já tem
(`MovingAverageTest.theStrokeIsItsOwn:215`):
```java
    assertEquals(3, bands.strokes().size(), "três linhas, três traços");
    assertNotNull(((BasicStroke) bands.strokes().get(1)).getDashArray(),
            "o meio foi pedido pontilhado e sai sólido");
```

**Tentei refutar:** `OverlayCatalogTest.everyKindIsUsable:61` compara
`colours().size()` com `valueAt(0).length` — pega uma cor faltando, não um traço.
Nenhum teste da área chama `strokes()` sobre as bandas (`SlowStochasticTest:220`
chama sobre o estocástico). Nenhum irmão salva.

### A8b-14 — o tempo decorrido da régua só tem piso, nunca um valor

**Onde:** `ui/chart/MeasurementTest.java:86-91`

**Trecho:**
```java
        Measurement m = Measurement.between(withGap, 0, 100.0, 5, 100.0);

        assertEquals(6, m.bars(), "six candles were spanned");
        assertTrue(m.elapsed() / 60_000L > 900L,
                "the elapsed time must include the break, not just the bars");
```

**A mutação que passa:** o valor correto é 975 minutos (15×5 + 900). Uma mutação
que devolva `timeAt(hi) - timeAt(lo)` **mais** qualquer constante positiva, ou
que troque o fuso/unidade para cima, passa. E o irmão
`backwardsDragIsSymmetric:95` só afirma que o valor dos dois sentidos é
**igual**, não qual é — logo não prende nada.

**Por que importa:** o comentário diz que qualquer projeção traçada a partir da
régua fica errada pelo tamanho do intervalo. Nenhum teste da área fixa um
`elapsed` em número.

**Correção:** `assertEquals(975L, m.elapsed() / 60_000L);`

**Tentei refutar:** conferi os cinco métodos da classe. `barsAreInclusive:34` e
`outsideTheSeriesIsNull:119` prendem `bars()` e os limites com dentes;
`elapsed()` não é fixado em lugar nenhum. Nenhum irmão salva.

### A8b-15 — o caminho de pintura nunca vê mais de três barras

**Onde:** ausência, e as fixtures de `ui/chart/style/CandleBodyEdgeTest.java:51-56`

**Trecho:**
```java
    private static final int WIDTH = 90;

    private static final int HEIGHT = 120;

    /** Three bar-slots across the image, so the middle one is the subject. */
    private static final int BARS = 3;
```

**A mutação que passa:** qualquer coisa a respeito de custo. `LineStyle.paint`
(`LineStyle.java:46-63`) aloca dois `int[to - from]` e percorre bar a bar; o
`ChartCanvas` faz o mesmo. Transformar essa varredura em O(n²), ou alocar por
barra, não muda uma cor da suíte. E `LineStyle` não é mencionado por **nenhum**
arquivo de teste (grep).

**Por que importa:** 1 M de barras é o caso normal deste projeto, e a decimação
está entre os defeitos conhecidos desta região. O maior fixture da área que
chega perto de pintar é `PaneSharingTest`/`PlacementTest` com
`RandomWalkSeries(200..400)`; `ViewportTest` usa 3 a 8; `CandleBodyEdgeTest`
usa 3. O único fixture grande, `TimeAxisTest:122` com 130.000 barras, não
desenha nada.

**Correção:** um teste de orçamento sobre `LineStyle.paint` e sobre o desenho
de velas: 1 M de barras numa `BufferedImage` de largura conhecida, com um teto
de tempo generoso mas real (por exemplo, 200 ms), e — melhor ainda — uma
asserção de que o número de pontos entregues ao `drawPolyline` é da ordem da
largura em pixels, não do tamanho da série. A segunda não depende de relógio e
é a que prende a decimação.

**Tentei refutar:** procurei em toda a área por qualquer asserção de tempo, de
contagem de operações ou de tamanho de série no caminho de desenho. Não há
nenhuma. Nenhum irmão salva.

---

## Achados BAIXA

### A8b-16 — o grampo de desvio "impossível" só afirma o que já é igualdade

**Onde:** `ui/chart/overlay/BollingerBandsTest.java:272-288`

**Trecho:**
```java
        assertTrue(row[0] >= row[1], "the upper band went below the middle");
        assertTrue(Double.isFinite(row[2]), "the lower band became NaN and vanished");
```
`clampDeviation` (`BollingerBands.java:211-217`) devolve `0` para negativo, então
`row[0] == row[1]` exatamente — a asserção `>=` é satisfeita pela igualdade e
não distingue "grampeado em zero" de "grampeado em qualquer valor não-negativo".
O teto de 10 (`Math.min(value, 10)`) não é testado em lugar nenhum.

**Correção:** `assertEquals(row[1], row[0], 1e-9)` para o negativo, e um caso
para o teto: `setUpperDeviations(1000)` seguido de
`assertEquals(10.0, bands.upperDeviations(), 1e-9)`.

### A8b-17 — a cor automática é comparada consigo mesma

**Onde:** `ui/chart/overlay/MovingAverageTest.java:153-160`

**Trecho:**
```java
        assertEquals(new MovingAverage(9).colours(), new MovingAverage(9).colours());
        assertNotEquals(new MovingAverage(9).colours(), new MovingAverage(10).colours());
```
A primeira linha compara duas chamadas do mesmo construtor com o mesmo período:
não há mutação de `AUTOMATIC[Math.abs(period) % AUTOMATIC.length]`
(`MovingAverage.java:248`) que a faça falhar, exceto tornar a cor aleatória. A
segunda tem dentes (pega o "sempre a mesma cor"). O `@DisplayName` promete
"follows the period, not the order added", e a ordem de adição não entra no
teste — nem podia, porque a cor é função pura do período.

### A8b-18 — a média do estocástico é conferida numa única barra

**Onde:** `ui/chart/study/stochastic/SlowStochasticTest.java:206-207`

**Trecho:**
```java
        assertTrue(study.valueAt(39)[1] > study.valueAt(39)[0],
                "the average did not lag on the way down");
```
Um único ponto, o último. Uma mutação que inverta as duas linhas apenas na
subida (barras 0..19) passa. O javadoc diz que é o cruzamento que dá sentido ao
indicador; o cruzamento nunca é observado.

**Correção:** afirmar em toda a descida, e afirmar o oposto em toda a subida.

### A8b-19 — o primeiro ponto colorido do menu de segmento não é afirmado

**Onde:** `ui/chart/SegmentChipTest.java:179-181`

**Trecho:**
```java
        assertEquals(3, dots.size());
        assertEquals(SeriesColors.taken(0), dots.get(1));
        assertEquals(SeriesColors.taken(1), dots.get(2));
```
`dots.get(0)` — o ponto de "A série toda" — é contado e nunca conferido.
Qualquer cor ali passa, inclusive a cor de fundo (um ponto invisível).

### A8b-20 — `everyKindIsUsable` pergunta o valor antes de calcular

**Onde:** `ui/chart/OverlayCatalogTest.java:61-62`

**Trecho:**
```java
            assertEquals(overlay.colours().size(), overlay.valueAt(0).length,
                    kind.nameKey() + " draws a line it has no colour for, or the reverse");
```
`valueAt(0)` é chamado sobre um indicador que nunca recebeu `calculate`. O que é
comparado é o comprimento do array de "nada ainda", não o do array desenhado. Um
indicador cujo `valueAt` mude de comprimento depois do `calculate` — que é o
caso do `SlowStochastic` quando a média é desligada
(`SlowStochasticTest:218`) — passaria aqui e falharia na tela.

**Correção:** `overlay.calculate(new RandomWalkSeries(100, 100.0));` antes da
asserção.

---

## Componentes nunca dimensionados

Varri os testes desta área que afirmam qualquer coisa geométrica. **Não há
nenhum caso de asserção sobre um retângulo 0×0.** Onde há geometria, o
componente foi dimensionado de verdade:

| teste | como o componente foi dimensionado | verificado |
|---|---|---|
| `CandleBodyEdgeTest` | pinta numa `BufferedImage(90, 120)` com `Viewport.of(series, new Rectangle(0,0,90,120), 0, 3)` e lê o RGB de volta | sim — é medição de pixel real, o padrão mais forte da área |
| `PaneOrderTest` | `stack.setSize(600, 500)` + `stack.doLayout()` em `put()`; `Stacked.layoutContainer` (`StudyStack.java:479-508`) dá `setBounds` distinto a cada painel; o arrasto usa `panes().get(0).getY()` | sim — os Y são distintos |
| `PaneSharingTest` | `stack.setSize(900, 500)`; mas as asserções são de contagem e de identidade, não de coordenada | não aplicável |
| `TimeAxisTest` | `canvas.setSize(900, 500)`, e o `Viewport` é construído com um `Rectangle(0,0,900,400)` explícito, então o resultado não depende do layout | sim |
| `RulerModeTest:91` | `frame.pack()` com `assumeFalse(isHeadless())`; as asserções são de contagem de ouvintes e de modo | não aplicável |
| `SegmentChipTest:186-192` | pinta o ícone numa `BufferedImage(8, 8)` e lê o pixel (4,4) | sim |

**A quase-armadilha:** `CollapsiblePaneTest:56` (`foldedHeight() > 0`) não é um
retângulo 0×0, mas é a mesma doença por outra porta — uma medida que a operação
sob teste não pode alterar. Está reportada como A8b-11.

---

## Cobertura: o que NÃO tem teste nenhum

Classes de produção da área, sem um único arquivo de teste que as mencione
(`grep -rl` sobre `src/test/java`):

| classe | linhas | o que fica sem rede |
|---|---:|---|
| `ui/chart/OverlayLegend.java` | 632 | o que a legenda mostra, inclusive a correção recém-entrada em `:207-208`, que nada segura (A8b-6) |
| `ui/chart/style/LineStyle.java` | 76 | o desenho em linha e a ausência de decimação em `:46-63` (A8b-15) |
| `ui/chart/BarReadout.java` | 271 | a leitura O/H/L/C sob o cursor |
| `ui/chart/RulerReadout.java` | 172 | o que a régua escreve na tela (o `Measurement` tem teste, o desenho não) |
| `ui/chart/LayoutBar.java` | 503 | a barra de layouts |
| `ui/chart/LinePen.java` | 186 | o desenho à mão livre |
| `ui/chart/Forms.java` | 271 | os campos comuns dos diálogos |
| `ui/chart/PeriodDialog.java` | 211 | o `PeriodCatalog` é bem testado; o diálogo que o usa não |
| `ui/chart/MovingAverageDialog.java` | 529 | inclusive o spinner de −500 a 500 de `:114` (A8b-4) |
| `ui/chart/BollingerBandsDialog.java` | 395 | — |
| `ui/chart/study/rsi/RsiDialog.java` | 229 | — |
| `ui/chart/study/stochastic/StochasticDialog.java` | 281 | — |
| `ui/chart/ChartStyle.java` | 59 | a escolha entre vela e linha |

Comportamentos específicos sem teste, dentro de classes que **têm** teste:

- `MovingAverage.onItsOwnPeriod` (`MovingAverage.java:337-366`) — escala maior:
  nenhum teste. A8b-2.
- `RelativeStrength.onItsOwnPeriod` (`RelativeStrength.java:332-360`) — idem.
  A8b-3.
- `SlowStochastic.onOwnScale` (`SlowStochastic.java:480-508`) — idem; nota-se
  que este é o único dos quatro que **não** chama `OwnScale.smooth`, e nada no
  conjunto afirma se isso é regra ou esquecimento.
- `MovingAverage.setShift` com valor negativo (`MovingAverage.java:193`). A8b-4.
- `BollingerBands.strokes()` (herdado, `Overlay.java:169`). A8b-13.
- `ChartCanvas.axisSpeaksInDays` no limiar de 2 dias
  (`ChartCanvas.java:2042`). A8b-9.
- `StudyStack.fits` com dois indicadores **reais** de faixa fixa igual
  (`StudyStack.java:178-198`). A8b-10.
- `SeriesSummary` — qual rótulo corresponde a qual fonte
  (`SeriesSummary.java:75`). A8b-5.
- `Measurement.elapsed()` — nenhum valor fixado. A8b-14.
- Qualquer caminho de desenho com mais de 400 barras. A8b-15.

---

## O que está LIMPO

Testes que confirmei ter dentes, com a mutação que cada um pega:

**`ui/chart/ViewportTest.java`** — o mais forte da área, e por larga margem.
- `scaleUsesVisibleBarsOnly:40` pega trocar `firstBar..lastBar` por
  `0..size()`: o pico de 5.000 fora da janela dispararia
  `highestPrice() < 200.0`.
- `barAndPixelRoundTrip:60` pega o meio-pixel: se `x(bar)` devolver a borda
  esquerda em vez do centro, `barAt(x(bar))` cai na barra vizinha.
- `countsSlotsNotBars:104` pega justamente a mutação que já foi defeito
  (`barCount()` encolhendo para as barras existentes) — o comentário registra
  que a asserção **dizia o contrário** e que isso era o defeito.
- `spaceOnTheRightIsNotAZoom:118` pega remover a margem: `barWidth()` mudaria
  entre as duas viewports.
- `aboveOneStretches:169` prende o fator em igualdade exata
  (`automatic / 2.0`), não em desigualdade — não passa com um fator qualquer.
- `nonsenseFactorIsIgnored:184` e `slidingIgnoresNonsense:268` percorrem
  0, negativo, NaN e infinito.

**`ui/chart/LayoutOrderTest.java`** — `Reordering.move` é aritmética de
off-by-one, e o teste prende cada caso em igualdade de lista inteira. Pega
trocar `>` por `>=` no ajuste do índice (`draggingRight:49` vs
`draggingLeft:59` divergem), pega remover o caso "o buraco depois de si mesmo"
(`droppingWhereItAlreadyIs:82`) e pega remover os guardas
(`nonsenseIsRefused:88`, quatro combinações).

**`ui/chart/style/CandleBodyEdgeTest.java`** — lê pixel de uma imagem pintada,
que é a única forma de pegar o defeito que ele registra. `noThreadBelowATaillessBody:162`
pega devolver o retângulo à altura de antes (a fileira de baixo voltaria a ter
1 pixel em vez da largura do corpo), e `aRealTailSurvives:188` pega a
sobrecorreção (a cauda real perderia a largura de 1). Os dois lados, e nos dois
estados de `hollowCandles`. `theDojiSurvives:209` prende o caso de altura zero.

**`ui/chart/PeriodCatalogTest.java`** — `theBrickIsOneTickSmallerThanItsName:156`
prende três valores em fechado (20, 50, 10 pontos) **e** verifica que o número
chega ao `Renko` construído e ao título; pega exatamente a mutação
`n × tick` que o comentário diz ter sido o defeito. `renkoBounds:54` cerca os
dois limites por dentro e por fora (1, 2 → não; 3 → sim; 101 → sim; 102, 500 →
não), que é o padrão que faltou em A8b-9.

**`ui/chart/SessionsTest.java`** — `takesTheLastBarOfThePreviousDay:94` prende
o valor 12,0 e pega tomar a **primeira** barra do dia anterior (daria 10,0) ou a
última do dia corrente. `missingReferenceIsNotZero:128` e
`firstDayHasNoReference:112` prendem NaN contra zero nos três caminhos.

**`ui/chart/ChartGeometryTest.java`** — aritmética pura sobre `Dimension`, com
os quatro cruzamentos de `opensMaximised` afirmados
(`:76`, `:83`, `:91`, `:97`) e o caso 0×0 recusado explicitamente (`:70`).
Pega inverter qualquer um dos três booleanos.

**`ui/chart/OverlayNoticeTest.java`** — separa os dois avisos e conta os dois
em todos os caminhos, inclusive o "não avisar ninguém"
(`removingAStrangerIsSilent:106`). Pega unificar os dois avisos num só, que é o
defeito que o javadoc registra, nas duas direções.

**`ui/chart/ChartLayoutTest.java`** — `roundTrip:33` compara listas de `Entry`
inteiras (`assertEquals(entries, back)`), e `severalInOnePane:184` prende a
ordem dentro do painel, a altura e a flag de minimizado de **dois** painéis
distintos. Pega perder qualquer campo na formatação.

**`ui/chart/study/PaneOrderTest.java`** — a única classe da área que dirige um
arrasto de verdade sobre componentes com `setBounds` real, e prende a ordem
resultante em lista inteira nas duas metades (o arrasto e a volta pelo layout).
Pega o off-by-one do buraco (`droppingWhereItAlreadyIs:138`) e pega o arrasto
que nunca se moveu (`aDragThatNeverMoved:153`).

**`ui/chart/RenkoSourceTest.java`** — `oneSessionShortIsStillRefused:131` tem os
dois lados no mesmo método (dois dias exportados → sim; um faltando → não) e
`nothingOnScreenIsNotEverything:167` fecha o caso vazio, que é o que um
`allMatch` sobre lista vazia responderia errado. Pega remover a verificação de
cobertura.

**`ui/shell/NavigatorTreeTest.java`** — `oneInstrumentOneGroup:224` conta por
descida em vez de `getChildCount()`, e o comentário registra que a contagem
direta *"would say 1 for the two WIN bases and pass for the wrong reason"* —
isto é, o próprio teste já foi auditado contra esta armadilha.
`theLabelIsNotTheName:198` separa rótulo de nome e pega renomear o nó para
mostrar o rótulo. `theGuardRefusesToLeaveTheTemporaryFolder:437` testa a rede de
segurança das próprias fixtures nos dois sentidos.

**`ui/chart/study/stochastic/SlowStochasticTest.java`** (com a ressalva A8b-18) —
`theWarmUpIsAbsent:111` prende as três fases do aquecimento em barras
específicas (0, 6, 9, 29) e pega encurtar qualquer uma;
`aFlatWindowCarriesTheLastValue:127` prende o valor exato (100,0) e pega tanto
o NaN quanto o zero.

**`ui/chart/study/rsi/RelativeStrengthTest.java`** (com a ressalva A8b-3) — a
aritmética é excelente: `theFirstValueByHand:98` e
`theSecondValueTellsThemApart:112` vêm de conta feita à mão e distinguem as
duas suavizações; `whatLeavesTheWindow:195` monta uma série em que só a
diferença entre janela e suavização de Wilder produz respostas diferentes
(0,0 contra 100−700/27) e pega implementar uma no lugar da outra.

**`ui/chart/overlay/BollingerBandsTest.java`** (com as ressalvas A8b-1 e A8b-13) —
`theBandsAreWhereTheArithmeticSays:79` escolhe números em que o desvio
populacional e o amostral são visivelmente diferentes e afirma **contra os
dois**: pega a troca de convenção, que é a mutação que a maioria dos testes de
banda não pega. `theMiddleIsTheAverage:137` amarra o meio ao
`MovingAverage` de mesmos ajustes, e pega os dois divergirem.

**`ui/chart/MeasurementTest.java`** (com a ressalva A8b-14) —
`barsAreInclusive:34` pega o off-by-one da contagem (5 contra 6), e
`outsideTheSeriesIsNull:119` fecha os dois lados da fronteira.

**Um teste fraco salvo por um irmão, e por isso NÃO reportado:**

- `TickRenkoOnChartTest.theBricksComeFromTheTicks:199` afirma só
  `assertNotEquals(fromCandles, canvas.series().size())` — não diz qual dos dois
  lados é maior, e o comentário explica que isso é deliberado (a direção depende
  do mercado). Sozinho seria uma asserção fraca sobre a diferença que dá sentido
  ao recurso; mas `aLateBuildIsNotShown:314` prende a altura do tijolo em 55,0
  exatos, e `leavingRenkoGoesBackToMinutes:355` prende a contagem em `MINUTES`
  nos dois lados da troca de período. **Os dois irmãos cobrem a mutação** que
  faria a série "diferente" por acaso.
- `OverlayTest.warmUpIsNaN:37` usa uma série plana, onde a média exponencial e a
  aritmética coincidem — sozinho não distinguiria as duas. Mas
  `MovingAverageTest.exponentialSeed:103` e `weightedLeansRecent:117` fazem
  exatamente essa distinção sobre séries que se movem. **Irmão salva.**
- `PlacementTest.oneListAndEachAnswersForItself:87` monta um mapa e afirma
  quatro entradas nomeadas; parece frouxo por percorrer o catálogo inteiro e só
  conferir quatro. Mas `theChartRefusesIt:69` e `aLayoutCannotSmuggleOneIn:110`
  fecham o comportamento no canvas, que é onde a consequência mora.
  **Irmãos salvam.**
