# A5 — indicadores, estudos, painéis e diálogos

**Data:** 06/09/2026 · **Auditor:** agente A5 · **Commit base:** `d815164` (branch `ferramentas_01`)

## Arquivos lidos (integralmente, salvo nota)

| arquivo | linhas |
|---|---:|
| `ui/chart/study/StudyPane.java` | 888 |
| `ui/chart/overlay/BollingerBands.java` | 658 |
| `ui/chart/study/stochastic/SlowStochastic.java` | 606 |
| `ui/chart/overlay/MovingAverage.java` | 522 |
| `ui/chart/study/StudyStack.java` | 510 |
| `ui/chart/study/rsi/RelativeStrength.java` | 448 |
| `ui/chart/InsertOverlayDialog.java` | 417 |
| `ui/chart/study/stochastic/StochasticDialog.java` | 281 |
| `ui/chart/study/rsi/RsiDialog.java` | 229 |
| `ui/chart/Overlay.java` | 220 |
| `ui/chart/LinePen.java` | 186 |
| `ui/chart/OwnScale.java` | 157 |
| `ui/chart/OverlayCatalog.java` | 137 |

`find` sob `study/` e `overlay/` não revelou nenhum `.java` fora da lista: os oito arquivos
daqueles dois pacotes são exatamente `RelativeStrength`, `RsiDialog`, `SlowStochastic`,
`StochasticDialog`, `StudyPane`, `StudyStack`, `BollingerBands` e `MovingAverage`.

**Lidos parcialmente, só para confirmar chamador** (não auditados): `ChartCanvas.paintOverlays`
e `replaceOverlay`, `OverlayLegend.edit`, `ChartLayout.Pane.build`/`Entry.build`,
`ChartHolder` (linhas 185-210 e 390-420), `MovingAverageDialog:114`,
`BollingerBandsDialog:129`, `PeriodCatalog.byCode`/`forText`, `Forms.named`/`lineStyles`,
`Timeframe:205-211`.

**Testes lidos:** `OwnPeriodTest`, `RelativeStrengthTest`, `SlowStochasticTest`,
`PaneSharingTest`, mais varredura de `BollingerBandsTest` e `MovingAverageTest`.

## O que foi conferido

- A regra do `OwnScale` (último candle FECHADO) em cada um dos quatro consumidores.
- A aritmética de RSI, estocástico, média e bandas — conta na mão, não só a forma.
- `StudyStack.fits` contra as duas respostas "sim" e contra os casos de borda.
- `fitsOnPrice()` em todos os quatro implementadores.
- Formato salvo (`appearance`) dos quatro: campo a menos, campo a mais, separador no valor.
- Chaves de bundle: **36 chaves** usadas na área, todas presentes em `messages.properties` e
  em `messages_pt_BR.properties`.
- A extração do `LinePen`, contra o `Pen` interno removido em `a80f7ea`.
- Alocação por barra na pintura, varredura de série em manipulador de evento, EDT.

---

## Achados ALTA

### A5-1 — `OwnScale.smooth` nunca interpola: o resultado é sempre idêntico ao do `map`

**Onde:** `ui/chart/OwnScale.java:136-155`

**Trecho:**

```java
public static void smooth(PriceSeries fine, PriceSeries coarse, double[] slow, double[] into) {
    for (int i = 0; i < into.length; i++) {
        int closed = indexOfClosed(fine, coarse, i);
        ...
        long from = coarse.timeAt(closed);
        long to = closed + 1 < coarse.size() ? coarse.timeAt(closed + 1) : from;
        long span = to - from;
        ...
        double along = Math.max(0.0, Math.min(1.0, (fine.timeAt(i) - from) / (double) span));

        into[i] = slow[closed - 1] + (slow[closed] - slow[closed - 1]) * along;
    }
}
```

**Problema:** `along` é sempre ≥ 1, portanto sempre grampeado em 1,0, portanto
`into[i]` é sempre `slow[closed]` — exatamente o que o `map` já tinha escrito.

Prova, em uma linha. Por definição de `indexOfClosed` (linha 86):

```java
if (middle + 1 < coarse.size() && coarse.timeAt(middle + 1) <= when) {
```

o índice `c` devolvido satisfaz `coarse.timeAt(c+1) <= fine.timeAt(i)`. O `smooth` monta
`from = timeAt(c)` e `span = timeAt(c+1) - timeAt(c)`. Logo

```
fine.timeAt(i) - from  >=  timeAt(c+1) - timeAt(c)  =  span
```

ou seja `along = (t - from)/span >= 1` para **todo** `i`, e `Math.min(1.0, ...)` o zera como
variável. Substituindo em `slow[c-1] + (slow[c] - slow[c-1]) * 1` sobra `slow[c]`.

O erro é de uma barra: o intervalo em que o valor `slow[c]` está em vigor é
`[timeAt(c+1), timeAt(c+2))`, não `[timeAt(c), timeAt(c+1))`. A interpolação honesta desce
de `slow[c-1]` a `slow[c]` ao longo da barra grossa `c+1`, que é a que está se formando.

Confirmado numericamente com o cenário do próprio `OwnPeriodTest` (15 barras de 1m desde
09:00, escala 5m, `slow = {5, 10, 15}`): na barra 11 (09:11) o `indexOfClosed` devolve 1,
`from = 09:05`, `span = 5 min`, `t - from = 6 min`, `along = 1,2 → 1,0`, resultado 10,0 —
o mesmo 10,0 que o `map` escreveu. Não consegui rodar `javac` neste ambiente (só há JRE 8
instalado; sem JDK 25 e sem toolchain), por isso a verificação foi aritmética e não
executada.

**Consequência:** a caixa *"Inclinar entre os pontos fechados"*
(`overlay.ma.interpolate`), ligada **por padrão** na média (`MovingAverage:147`), no RSI
(`RelativeStrength:110`) e nas bandas (via `basis`), não faz nada. Toda média, RSI ou
Bollinger em escala maior sai em degraus, marcada e salva como "inclinada". O usuário vê
uma opção que responde com silêncio; a medição vê uma linha em escada onde o código diz
que há rampa.

**Correção:** ancorar a rampa na barra grossa em formação:

```java
long from = coarse.timeAt(closed + 1);
long to = closed + 2 < coarse.size() ? coarse.timeAt(closed + 2) : from + (from - coarse.timeAt(closed));
```

Mantém a honestidade — `slow[closed]` e `slow[closed-1]` já estão fechados — e passa a
produzir valores diferentes do `map`. Junto com a correção, um teste com dentes (ver A5-2).

**Tentei refutar:** (a) procurei tratamento no chamador — os três consumidores só chamam
`map` e depois `smooth` sobre o mesmo array, sem ajustar índice
(`MovingAverage:361-365`, `RelativeStrength:356-360`, `BollingerBands:403-411`); (b) tentei
achar um caso em que `along < 1` — só existiria se `timeAt` não fosse monótono, e
`Timeframe:211` escreve `times[out] = startOf(...)`, estritamente crescente; (c) procurei
teste que derrubasse — `OwnPeriodTest.interpolationStaysBehind` é justamente o teste sem
dentes de A5-2; (d) considerei que `span <= 0` desviasse o caso — não desvia, o `continue`
só cobre barras grossas de duração nula.

---

### A5-2 — O teste da interpolação só afirma teto, e o defeito é de piso

**Onde:** `src/test/java/.../ui/chart/overlay/OwnPeriodTest.java:138-158`

**Trecho:**

```java
@DisplayName("interpolation slopes between closed points and adds nothing new")
void interpolationStaysBehind() {
    MovingAverage sloped = new MovingAverage(1);
    sloped.setOwnPeriod("5m");
    sloped.setInterpolated(true);
    sloped.calculate(minutes());
    ...
    for (int bar = 0; bar < series.size(); bar++) {
        double value = sloped.valueAt(bar)[0];

        if (Double.isFinite(value)) {
            assertTrue(value <= series.closeAt(bar),
                    "interpolation reached bar " + bar + " with " + value);
        }
    }
}
```

**Problema:** o nome do teste promete duas coisas — *"slopes between closed points"* e
*"adds nothing new"* — e ele só verifica a segunda. A asserção é um teto
(`value <= closeAt(bar)`), e um teto é satisfeito por qualquer implementação que não leia
o futuro, **inclusive por uma que não interpole nada**. Como A5-1 mostra que `smooth` é
exatamente isso, este teste passa hoje sobre um método que não faz o que o teste diz
testar; passaria igual se `OwnScale.smooth` fosse apagado e a chamada removida.

**Consequência:** licença falsa. O único teste de interpolação do projeto declara verde
sobre uma funcionalidade inexistente, e foi ele que deixou A5-1 chegar até aqui.

**Correção:** o teste tem que exigir o piso — que a linha inclinada seja **diferente** da
em degraus, e no sentido certo:

```java
MovingAverage stepped = new MovingAverage(1);
stepped.setOwnPeriod("5m");
stepped.setInterpolated(false);
stepped.calculate(minutes());

assertNotEquals(stepped.valueAt(12)[0], sloped.valueAt(12)[0],
        "a interpolação não mudou nada: é a mesma escada");
assertTrue(sloped.valueAt(11)[0] < sloped.valueAt(12)[0],
        "a rampa tem que subir entre dois pontos fechados");
```

mantendo o teto atual como segunda asserção.

**Tentei refutar:** procurei outro teste que cobrisse a inclinação — `grep` por
`setInterpolated` em `src/test` encontra este, o `theChoiceIsRemembered` do mesmo arquivo
(que só verifica a ida-e-volta do flag) e `RelativeStrengthTest:243` (idem). Nenhum compara
inclinado contra em degraus. `SlowStochasticTest` e `BollingerBandsTest` não tocam no
assunto.

---

### A5-3 — Deslocamento negativo faz a média ler barras à direita

**Onde:** `ui/chart/overlay/MovingAverage.java:279-283`, alcançável de
`ui/chart/MovingAverageDialog.java:114`

**Trecho:**

```java
@Override
public double[] valueAt(int bar) {
    int at = bar - shift;

    return new double[]{at >= 0 && at < values.length ? values[at] : Double.NaN};
}
```

e o controle que alimenta `shift`:

```java
this.shift = new JSpinner(new SpinnerNumberModel(average.shift(), -500, 500, 1));
```

**Problema:** com `shift = -3`, `valueAt(bar)` devolve `values[bar + 3]` — um valor
calculado sobre as barras `bar+1`, `bar+2`, `bar+3`. É a forma `i+1` da regra 2, com
constante maior. Nada no código barra o negativo: `setShift` (linha 193) aceita qualquer
inteiro, o construtor variádico (`MovingAverage:160`) copia `settings[1]` sem filtro, e
`parameters()` grava o shift no layout, de modo que a leitura do futuro sobrevive a um
salvar-e-abrir. O javadoc da classe (linhas 49-51) só descreve o caso positivo — *"A
positive shift pushes it into the future"* — e não diz uma palavra sobre o negativo.

**Consequência:** o cruzamento do preço com a média, o valor sob o cursor na legenda e o
`BarReadout` passam a mostrar, na barra `i`, um número que só existia na barra `i+3`. Numa
ferramenta cujo propósito declarado é medir, esse é o defeito que faz uma regra parecer
lucrativa.

**Correção:** ou o piso do spinner vira `0` e `setShift` faz `Math.max(0, value)`, ou —
se a média centrada for desejada de propósito — o javadoc diz isso em voz alta, a legenda
marca a linha como deslocada para trás, e um teste fixa que ela nunca é lida por nada que
não seja pintura.

**Tentei refutar:** (a) a média centrada é técnica legítima em análise gráfica, e é
possível que o `-500` seja deliberado — mas não achei nenhum comentário, javadoc ou teste
que afirme isso, e a convenção da casa é justamente que o porquê fica escrito;
(b) verifiquei se `valueAt` é consumido só por pintura — `BollingerBands.computeOver:432`
também o chama, porém sobre uma `MovingAverage` recém-criada com `shift` zero, então esse
caminho está limpo; (c) procurei teste — `MovingAverageTest:129-133` só exercita
`setShift(1)`, o caso positivo.

---

### A5-4 — Varredura da série inteira dentro de manipulador de evento, na EDT

**Onde:** `ui/chart/study/StudyStack.java:431-439`, `:120-131`, `:143-153`, `:226-229`

**Trecho:**

```java
public void recalculate() {
    for (StudyPane pane : panes()) {
        for (Overlay study : pane.studies()) {
            study.calculate(canvas.source());
        }
    }

    repaint();
}
```

e o gatilho, em `ui/chart/ChartHolder.java:198-201`:

```java
canvas.onSeriesChanged(() -> {
    // Before anything reads them: a study still holding the values of
    // the series before would draw a shape that never happened.
    body.recalculate();
```

**Problema:** `calculate` é a varredura completa. Para um estocástico em escala própria ela
é: `scale.apply(series)` (uma passada sobre a série toda, alocando quatro `double[]` do
tamanho da série grossa), mais `computeOver` que é O(n × period) — `SlowStochastic:527-530`
percorre `period` barras para trás em cada uma das `n` —, mais dois `OwnScale.map`. Para as
bandas, `BollingerBands:449-453` é outro O(n × period). Nada disso é despachado para
`SwingWorker`, `JobService` ou qualquer outra coisa: roda inteiro no callback de
`setSeries`, que por sua vez é chamado de ações de menu (`MainWindow:436`) e da entrada e
saída de replay (`ChartHolder:299` e `:330`) — todos na EDT.

Os outros três caminhos são iguais: `show()` (linha 121) e `addTo()` (linha 148) chamam
`calculate` no OK do diálogo de inserção; `settingsFor()` (linha 227) chama no OK do
diálogo de configuração.

**Consequência:** com 1 milhão de barras — que a convenção da casa fixa como o caso
NORMAL — trocar o segmento, entrar em replay ou apenas confirmar o diálogo do estocástico
congela a janela inteira por segundos, sem cursor de espera e sem nada na tela dizendo o
que está acontecendo. Três indicadores num painel multiplicam por três.

**Correção:** `calculate` para um `JobService`/`SwingWorker`, com o `repaint` no
`done()`; enquanto não voltar, o painel desenha o que tinha e marca-se como desatualizado.
Como salvaguarda mínima e barata, `StudyStack.recalculate` pode ao menos coalescer chamadas
sucessivas para o mesmo `canvas.source()`.

**Tentei refutar:** (a) procurei um `SwingWorker` ou `invokeLater` no caminho —
`ChartHolder` só tem um `invokeLater`, na linha 489, longe daqui; (b) verifiquei se
`canvas.source()` já vem pré-calculado ou memoizado — `calculate` sempre realoca
(`new double[size]` em todos os quatro indicadores) e recomputa; (c) verifiquei se
`setSeries` alguma vez roda fora da EDT, o que trocaria o achado por outro (Swing fora da
EDT) — os três chamadores são de UI.

---

## Achados MÉDIA

### A5-5 — O estocástico não tem interpolação; o RSI tem. Confirmado, e não é só a linha

**Onde:** `SlowStochastic.java:506-507` contra `RelativeStrength.java:356-360`

**Trecho — RSI:**

```java
// The last CLOSED coarse bar, never the one containing this one. See
// OwnScale, where the rule and the reason live.
OwnScale.map(series, coarse, over, values);

if (interpolate) {
    OwnScale.smooth(series, coarse, over, values);
}
```

**Trecho — estocástico, no mesmo ponto:**

```java
// The last CLOSED coarse bar, never the one containing this one. See
// OwnScale, where the rule and the reason live.
OwnScale.map(series, coarse, coarseSlow, slow);
OwnScale.map(series, coarse, coarseSignal, signal);
```

**Problema:** confirmado nas linhas exatas. O `SlowStochastic` não tem o campo
`interpolate` (compare `RelativeStrength:110` — `private boolean interpolate = true;` —
com a lista de campos do estocástico, linhas 74-115, onde ele não existe), não chama
`OwnScale.smooth`, e a diferença se propaga por mais três lugares:

1. **Formato salvo.** `RelativeStrength.appearance():250-251` grava seis campos, o quinto
   sendo `interpolate`:
   `smoothing;line;colour;width;interpolate;scale`. `SlowStochastic.appearance():370-375`
   grava quinze, sem nenhum equivalente — a escala é o campo 14 e o arquivo termina ali.
   Acrescentar `interpolate` depois exigirá o campo 15, e o formato posicional do
   estocástico não tem defesa contra inserção no meio (a das bandas tem: `name=value`).
2. **Aba do diálogo.** O `RsiDialog` monta três abas (`:100-104`), a terceira sendo
   `study.tab.scale`, e é lá que moram o botão de escala e a caixa
   `overlay.ma.interpolate` (`:162-167`), com o acerto de habilitá-la só quando há escala
   própria (`:192`). O `StochasticDialog` monta duas (`:132-135`) e enfia a escala no fim
   da aba de parâmetros (`:183-185`), sem caixa nenhuma. São dois diálogos com layouts
   diferentes para a mesma pergunta.
3. **A chave `study.tab.scale` existe no bundle e só um diálogo a usa** — a terceira aba
   está pronta esperando o estocástico.

**Consequência:** com A5-1 em pé, o efeito visível hoje é nenhum: as duas linhas saem em
escada. Corrigido A5-1, a diferença aparece imediatamente — RSI inclinado ao lado de
estocástico em degraus, no mesmo painel, sobre a mesma escala, que é exatamente o par que
`StudyStack.fits` existe para permitir.

**Correção:** subir `interpolate` para onde as duas classes o compartilhem (o candidato
óbvio é um campo em `OwnScale` ou um `default` em `Overlay` ao lado de `ownPeriod()`),
acrescentar o campo 15 ao `appearance` do estocástico com leitura tolerante ao ausente
(o `at(fields, 15)` já devolve `null` e o `readBoolean` já cai no padrão — a via está
aberta), e mover a escala do estocástico para uma terceira aba `study.tab.scale`, igual à
do RSI.

**Tentei refutar:** procurei o campo com `grep -n "interpolate" SlowStochastic.java` —
zero ocorrências; procurei no diálogo — zero; procurei se `ChartLayout` compensasse na
leitura — `Pane.build()` só chama `applyAppearance`.

---

### A5-6 — Estilo e espessura da linha do meio das bandas não desenham nada

**Onde:** `ui/chart/overlay/BollingerBands.java:325-336` (o que a classe oferece) contra
`ui/chart/ChartCanvas.java:1781` (o que a pintura usa)

**Trecho — a classe devolve três cores e UM traço:**

```java
@Override
public List<Color> colours() {
    // Three lines, in the order valueAt returns them. ...
    return List.of(bandColour(), centreColour(), bandColour());
}

@Override
public Stroke stroke() {
    return line.stroke(thickness);
}
```

Não há `strokes()` sobrescrito — o `default` de `Overlay:169-171` devolve
`List.of(stroke())`, de tamanho 1. E a pintura do preço nem consulta `strokes()`:

```java
g.setStroke(overlay.stroke());
```

**Problema:** `middleLine` e `middleThickness` são campos reais (`:108`, `:110`), com
setters (`:288`, `:296`), gravados no `appearance` (`:556-557`), lidos de volta
(`:596-597`), testados na ida-e-volta (`BollingerBandsTest:246-247`), e oferecidos no
diálogo com combo, spinner e amostra própria (`BollingerBandsDialog:83-86`, `:247-249`,
`:349-350`). Nada disso chega a um `Graphics2D`: as três linhas saem com
`line.stroke(thickness)`, o traço das bandas.

**Consequência:** o leitor escolhe "meio pontilhado, espessura 2", vê a amostra do diálogo
mudar, confirma, e o gráfico continua igual. É a categoria de defeito que a documentação do
próprio `ChartCanvas` diz querer evitar, duas linhas acima do bug: *"a single stroke set for
all of them would make every one of those settings do nothing"*.

**Correção:** sobrescrever `strokes()` em `BollingerBands` devolvendo
`List.of(stroke(), middleLine.stroke(middleThickness), stroke())`, e fazer
`ChartCanvas.paintOverlays` usar `strokes()` por linha com recuo para `stroke()`, do jeito
que `StudyPane.paintLines:801` já faz.

**Tentei refutar:** procurei um segundo caminho de pintura para as bandas —
`paintUnder` (`:482`) só pinta o preenchimento e não desenha linha; procurei
`strokes()` em todo `src/main`: só `RelativeStrength:210`, `SlowStochastic:312`,
o `default` da interface e o consumo em `StudyPane:797`. A pintura do preço nunca o chama.

---

### A5-7 — `LinePen` arredonda a espessura para inteiro, e explode fora de 1..8

**Onde:** `ui/chart/LinePen.java:218` e `:279-281`

**Trecho:**

```java
thickness = new JSpinner(new SpinnerNumberModel(Math.round(width), 1, 8, 1));
```

```java
public float width() {
    return ((Number) thickness.getValue()).floatValue();
}
```

**Problema:** dois defeitos no mesmo `SpinnerNumberModel`.

1. **Quantização silenciosa.** O padrão de todas as linhas é `1.4f`
   (`RelativeStrength:105`, `SlowStochastic:102-106`). `Math.round(1.4f)` é 1. Abrir o
   diálogo do RSI e confirmar sem mexer em nada troca 1,4 por 1,0 — a linha fica mais fina
   por ter sido olhada. O mesmo com 2,5, que o `RelativeStrengthTest:243` grava e lê de
   volta intacto no `appearance`: o formato preserva o float, o diálogo o destrói.
2. **Exceção.** `SpinnerNumberModel(value, min, max, step)` lança
   `IllegalArgumentException` quando `value` está fora de `[min, max]`. `setWidth` não tem
   teto: `RelativeStrength:181-183` e `SlowStochastic:254-256` só fazem
   `Math.max(0.5f, value)`. Um `appearance` com `width` 9 — vindo de arquivo editado à mão
   ou de versão futura — passa pelo `readFloat`, chega ao construtor do `LinePen` e derruba
   o diálogo com uma exceção não tratada dentro do handler do botão de engrenagem.

O mesmo padrão está no `BollingerBandsDialog:129`
(`new SpinnerNumberModel(bands.middleThickness(), 1, 8, 1)`) enquanto
`BollingerBands.setThickness:281` e `setMiddleThickness:297` grampeiam em **10** — dois
valores legais, 9 e 10, que o diálogo recusa com exceção.

**Consequência:** o usuário perde a espessura que escolheu; ou, no caso 2, clica na
engrenagem e nada abre (o *stack trace* vai para o console, que ninguém está lendo).

**Correção:** o modelo do spinner tem que aceitar o mesmo domínio que o setter aceita —
`new SpinnerNumberModel((double) Math.max(1f, Math.min(8f, width)), 1.0, 8.0, 0.1)`, com
passo fracionário — e `setWidth` ganha o teto correspondente (`Math.min(8f, ...)`), para
que os dois lados concordem em uma faixa só.

**Tentei refutar:** procurei clamp no chamador — `RsiDialog:98` e `StochasticDialog:126-130`
passam `study.width()` cru; procurei se `Math.round` estava compensado em `apply()` —
`RsiDialog:227` e `StochasticDialog:266` gravam `pen.width()` direto; verifiquei se o
defeito nasceu na extração — não nasceu, `git show a80f7ea` mostra a mesma linha no `Pen`
interno anterior.

---

### A5-8 — `setBorder` dentro de `paintComponent`

**Onde:** `ui/chart/LinePen.java:299-317`

**Trecho:**

```java
@Override
protected void paintComponent(Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();

    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        setBorder(BorderFactory.createLineBorder(ChartColors.grid()));
```

**Problema:** `JComponent.setBorder` dispara `firePropertyChange("border", ...)`, além de
`revalidate()` e `repaint()`. Mudar estado do componente e pedir novo layout de dentro da
própria pintura é o caminho canônico para repintura em laço; aqui ele não fecha o ciclo
porque a borda criada é sempre "igual" na prática, mas cada pintura ainda aloca um
`LineBorder` novo e marca o componente para revalidar.

**Consequência:** amostra do diálogo repintando mais do que precisa, e uma armadilha
esperando a primeira vez que `ChartColors.grid()` mudar entre pinturas (troca de tema).

**Correção:** a borda é fixa por instância — vai para o construtor. Se tiver que seguir o
tema, entra no `updateUI()`/no ouvinte de tema, nunca na pintura.

**Tentei refutar:** verifiquei se `LineBorder` é cacheado por `BorderFactory` —
`createLineBorder(Color)` constrói novo a cada chamada; verifiquei se o defeito é da
extração — não é, veio verbatim do `Sample` interno anterior (`git show a80f7ea`).

---

### A5-9 — O "visível" de um estudo dentro de painel é gravado como literal `true`

**Onde:** `ui/chart/study/StudyStack.java:241-244`

**Trecho:**

```java
for (Overlay study : pane.studies()) {
    inside.add(new br.com.jorge.reis.endeavourneo.ui.chart.ChartLayout.Entry(
            study.nameKey(), study.parameters(), true, study.appearance()));
}
```

**Problema:** o terceiro componente de `ChartLayout.Entry` é `visible` (o javadoc do record
diz `@param visible whether the eye is open`). Aqui vai a constante `true` em vez de
`study.isVisible()`. E a outra ponta tem o defeito espelhado: `ChartLayout.Pane.build()`
(linhas 79-93) chama `study.applyAppearance(entry.appearance())` e **não** chama
`setVisible(entry.visible())` — enquanto `ChartLayout.Entry.build()`, o caminho dos
overlays de preço, chama (`overlay.setVisible(visible)`).

**Consequência:** um estudo escondido volta visível depois de fechar e reabrir o gráfico —
e o pintor já honra o flag hoje (`StudyPane.paintLines:792`: `if (!study.isVisible())
continue;`), então basta existir um gesto de esconder para o defeito virar visível. O
`Overlay.setVisible` é público e testado.

**Correção:** trocar o literal por `study.isVisible()` e acrescentar
`study.setVisible(entry.visible())` em `Pane.build()`, ao lado do `applyAppearance` — as
duas metades juntas, ou nenhuma delas resolve.

**Tentei refutar:** procurei um botão de olho no cabeçalho do painel — `StudyPane` desenha
só engrenagem e cruz (`paintSettings`/`paintClose`, `:587-588`), então hoje nenhum estudo
fica invisível pela interface, e o defeito é latente. Não o rebaixei a BAIXA porque as duas
metades erradas se cancelam: quem consertar uma sozinha vai ver o comportamento mudar sem
entender por quê.

---

### A5-10 — `drop()` esvazia a lista de estudos e deixa os retângulos do cabeçalho para trás

**Onde:** `ui/chart/study/StudyPane.java:331-348`, `:147-148`, `:205-206`

**Trecho:**

```java
public void drop(Overlay study) {
    if (!studies.remove(study)) {
        return;
    }

    hovered = -1;
    ...
    onChanged.run();
    repaint();
}
```

`entries` é a lista paralela usada pelo teste de acerto do mouse, e só é reconstruída na
pintura:

```java
/** Hit areas of the entries, rebuilt on every paint. */
private final transient List<Rectangle> entries = new ArrayList<>();
```

```java
int entry = entryAt(e.getX(), e.getY());

if (entry >= 0 && crossAt(e.getX(), e.getY(), entry)) {
    drop(studies.get(entry));
```

**Problema:** entre o `drop` e a próxima pintura, `entries.size()` é N e `studies.size()` é
N-1. `entryAt` pode devolver N-1, e `studies.get(N-1)` estoura
`IndexOutOfBoundsException`. `hovered = -1` protege o desenho, não o acerto do mouse —
`entryAt` percorre `entries`, não `hovered`.

**Consequência:** dois cliques rápidos na cruz do último indicador de um painel com três
podem derrubar o handler do mouse com exceção. Perde-se o clique e, dependendo do
`EventDispatchThread`, aparece o diálogo de erro não tratado.

**Correção:** `entries.clear()` dentro do `drop()`, junto com `hovered = -1` — a lista é
válida só até a próxima pintura de qualquer jeito, e limpá-la faz `entryAt` devolver -1 na
janela de risco.

**Tentei refutar:** (a) verifiquei se `repaint()` garante a pintura antes do próximo evento
de mouse — não garante: `repaint` posta um `InvocationEvent` na fila, e eventos de mouse
nativos já enfileirados são despachados antes; (b) verifiquei se `crossAt` sozinho já
filtraria — não, ele só compara coordenadas contra `entries.get(entry)`, que continua
existindo; (c) o caso análogo em `OverlayLegend` foi tratado explicitamente
(`hovered = -1` com o comentário *"the next click would act on whatever slid into its
place"*), o que mostra que o problema é conhecido no projeto — e ali a lista de botões
também é reconstruída na pintura.

---

### A5-11 — Alocação por barra dentro da pintura, e a série lida duas vezes por linha

**Onde:** `ui/chart/study/StudyPane.java:750-757`, `:806-807`, `:619-628`, `:885-887`

**Trecho — varredura de valores para achar a faixa, dentro do `paintComponent`:**

```java
for (int bar = viewport.firstBar(); bar < viewport.lastBar(); bar++) {
    for (double each : study.valueAt(bar)) {
```

**Trecho — a mesma coisa por linha desenhada:**

```java
for (int line = 0; line < colours.size(); line++) {
    ...
    for (int bar = viewport.firstBar(); bar < viewport.lastBar(); bar++) {
        double[] values = study.valueAt(bar);
```

**Trecho — e um `DecimalFormat` novo por número escrito:**

```java
private static DecimalFormat format() {
    return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.getDefault()));
}
```

chamado dentro do laço de `numbersOf` (`:624`).

**Problema:** `valueAt` aloca um `double[]` novo em todas as quatro implementações
(`MovingAverage:282`, `RelativeStrength:227`, `SlowStochastic:337-342`,
`BollingerBands:351-358`). O laço de `paintLines` chama `valueAt` uma vez por barra
**por linha**, então um estocástico com média custa dois arrays por barra visível por
pintura, e as bandas custam três — sempre descartando dois terços do que alocou.
`DecimalFormatSymbols.getInstance` é caro e é chamado uma vez por número no cabeçalho.

**Consequência:** lixo proporcional a (barras visíveis × linhas × pinturas por segundo),
gerado enquanto o mouse se move sobre o gráfico. Não trava, mas é exatamente o padrão que a
casa proíbe, e é o que faz um gráfico "parecer pesado sem nada obviamente errado no
código" — frase do javadoc de `Overlay:34-37`, escrita a respeito de outro caso.

**Correção:** o `DecimalFormat` vira campo estático (não é *thread-safe*, mas tudo aqui é
EDT); e o laço de linhas passa a chamar `valueAt` uma vez por barra, guardando o array e
percorrendo as linhas por dentro — uma alocação por barra em vez de três. Melhor ainda
seria `valueAt(int bar, double[] into)` no contrato, mas isso é mudança de interface e não
cabe neste achado.

**Tentei refutar:** verifiquei se `firstBar/lastBar` são limitados pelos pixels e não pelo
tamanho da série — são (`Viewport`), então isto **não** é a varredura de 1 milhão de barras
e por isso não é ALTA; verifiquei se algum implementador devolve array cacheado — nenhum
devolve.

---

### A5-12 — A suavização exponencial do estocástico não tem aquecimento, contra o próprio javadoc

**Onde:** `ui/chart/study/stochastic/SlowStochastic.java:546-574`

**Trecho — o que o javadoc promete:**

```java
/**
 * Averages one line into another, over {@link #average()} points.
 *
 * <p>Warm-up stays NaN and is not counted: an average of three that met two
 * numbers is an average of two wearing the wrong name.</p>
 */
private void smooth(double[] from, double[] into) {
```

**Trecho — o que o ramo exponencial faz:**

```java
if (kind == MovingAverage.Kind.EXPONENTIAL) {
    previous = Double.isNaN(previous) ? from[i]
            : from[i] * weight + previous * (1.0 - weight);
    into[i] = previous;

    continue;
}
```

**Problema:** o ramo exponencial escreve valor já no primeiro ponto finito, semeando com
`from[i]` — um único número. O ramo aritmético logo abaixo (`:583-586`) mantém NaN até a
janela encher, que é o que o javadoc descreve. E a `MovingAverage.exponential:411-416`, a
implementação de referência do mesmo conceito no mesmo projeto, semeia com a **média
aritmética da primeira janela**, com comentário dizendo por quê: *"Starting from the first
price instead leaves a visible hook at the left edge"*. São três comportamentos para uma
ideia só, e o javadoc descreve o que apenas um deles faz.

**Consequência:** com `kind = EXPONENTIAL`, o estocástico lento começa a desenhar `average`
barras antes do que deveria, com um gancho na borda esquerda, e a segunda linha (sinal, que
é `smooth` do `smooth`) herda o gancho. Um leitor comparando com o Profit vê duas linhas
que não batem no começo da série — e o javadoc diz que batem.

**Correção:** ou o ramo exponencial passa a semear com a média aritmética das primeiras
`average` amostras (igualando-se ao `MovingAverage`) e mantém NaN até lá, ou o javadoc
para de afirmar o contrário. A primeira é a correção; a segunda é a desculpa.

**Tentei refutar:** procurei teste que fixasse o aquecimento exponencial —
`SlowStochasticTest.theWarmUpIsAbsent` (`:106-119`) só exercita o padrão, `ARITHMETIC`;
nenhum teste da suíte chama `setKind(EXPONENTIAL)` no estocástico.

---

### A5-13 — O `appearance` do estocástico é o único sem teste de ida-e-volta, e descarta a cor de venda

**Onde:** `ui/chart/study/stochastic/SlowStochastic.java:399-403`

**Trecho:**

```java
setLevelLine(readEnum(MovingAverage.Line.class, at(fields, 7),
        MovingAverage.Line.DASHED));
setBuyColour(readColour(at(fields, 8), buyColour));
setSellColour(buyColour);
setLevelWidth(readFloat(at(fields, 9), levelWidth));
```

**Problema:** duas coisas.

1. `sellColour` é campo próprio (`:94`), com setter público (`:222`), usado em
   `levels():331` para pintar a linha de venda — e **nunca é gravado**: `appearance():373`
   escreve só `hex(buyColour)`. Na volta, a linha 402 força `setSellColour(buyColour)`. A
   API oferece duas cores e o formato só sabe guardar uma; qualquer valor distinto posto
   por `setSellColour` some no primeiro salvar. Hoje o `StochasticDialog:278-279` grava as
   duas iguais de propósito (com comentário explicando), então nada quebra pela interface —
   mas o par setter público / campo morto é armadilha esperando o próximo chamador.
2. **Não existe teste de ida-e-volta do `appearance` do estocástico.**
   `grep -c appearance SlowStochasticTest.java` devolve **0**. O RSI tem
   (`RelativeStrengthTest:229-251`), as bandas têm (`BollingerBandsTest:246-247`), a média
   tem (`OwnPeriodTest:174-188`). O único formato posicional de **quinze** campos do
   projeto é justamente o que ninguém testa — e é o que vai crescer para dezesseis quando
   A5-5 for corrigido.

**Consequência:** um campo inserido no meio dos quinze reinterpreta silenciosamente todo
layout já salvo, e nenhum teste avisa. Some cor, some nível, some escala.

**Correção:** o teste de ida-e-volta, com os quinze campos setados a valores distintos dos
padrões (é o teste que teria pego o `setSellColour(buyColour)`); e ou `sellColour` ganha seu
campo no formato, ou o setter some e o par de níveis passa a ter uma cor só, declarada.

**Tentei refutar:** procurei o teste com outros nomes (`roundTrip`, `daVolta`,
`remembered`) em toda a suíte — o `PaneSharingTest.theyComeBackTogether:216-238` chega
perto, mas só compara `parameters().get(0)` depois do `restore`; ele passaria com o
`appearance` inteiro perdido.

---

### A5-14 — As bandas alocam um array por barra durante o cálculo

**Onde:** `ui/chart/overlay/BollingerBands.java:431-434`

**Trecho:**

```java
for (int i = 0; i < mid.length; i++) {
    double centre = line.valueAt(i)[0];

    mid[i] = centre;
```

**Problema:** `MovingAverage.valueAt` aloca `new double[]{...}` a cada chamada (`:282`).
Aqui ele é chamado uma vez por barra para extrair um único `double`, e o array é descartado
na mesma linha. Com 1 milhão de barras, é 1 milhão de arrays de um elemento por
`calculate` — e `calculate` roda a cada troca de série (A5-4).

**Consequência:** pressão de GC exatamente no momento em que a EDT já está bloqueada.

**Correção:** o `MovingAverage` pode expor um `double valueOf(int bar)` sem embrulho (o
`valueAt` do contrato passa a chamá-lo), e as bandas usam esse. É mudança local, sem tocar
na interface `Overlay`.

**Tentei refutar:** verifiquei se a JIT elimina a alocação por *escape analysis* — pode
eliminar, e é justamente por isso não classifiquei como ALTA; mas o array é passado por
`valueAt` que é polimórfico (quatro implementações, chamada megamórfica no mesmo processo),
o que costuma impedir a *inlining* de que a análise depende.

---

## Achados BAIXA

### A5-15 — Código morto em `OwnScale.map`

**Onde:** `ui/chart/OwnScale.java:121-123`

```java
if (closed < 0 && coarse.size() > 1 && coarse.timeAt(1) <= fine.timeAt(i)) {
    closed = 0;
}
```

Inalcançável. O laço logo acima já cobre `j = 0`: com `closed == -1` sua condição vira
`0 < coarse.size() - 1` (isto é, `size > 1`) e `coarse.timeAt(1) <= fine.timeAt(i)` — as
mesmas duas condições do `if`. Se o `if` pudesse disparar, o laço já teria avançado.
Verificado passo a passo com a série do `OwnPeriodTest` (15 barras de 1m, coarse de 3
barras de 5m): na barra 5 o laço já põe `closed = 0`. **Correção:** apagar. Um guarda que
não guarda nada é um guarda que o próximo leitor vai tentar entender.

### A5-16 — Onze imports mortos no `StochasticDialog`, sobra da extração do `LinePen`

**Onde:** `ui/chart/study/stochastic/StochasticDialog.java:20-49`

`ChartCanvas`, `ChartColors`, `Overlay`, `java.awt.Color`, `Dimension`, `Graphics`,
`Graphics2D`, `RenderingHints`, `BorderFactory`, `JColorChooser` e `JComponent` aparecem
**exatamente uma vez** cada no arquivo — na própria linha de import. Todos serviam ao `Pen`
e ao `Sample` internos que foram para o `LinePen` em `a80f7ea`. **Correção:** apagar as onze
linhas.

### A5-17 — `average.setEnabled(true)` incondicional

**Onde:** `ui/chart/study/stochastic/StochasticDialog.java:205-206`

```java
private void refreshEnabled() {
    average.setEnabled(true);
```

Instrução sem efeito, dentro do método chamado por três ouvintes. Resíduo de quando o campo
seguia `showAverage`. Hoje o comportamento correto é justamente ele ficar sempre ligado
(o javadoc de `SlowStochastic.parameters():289-300` explica por quê) — então a instrução
some, não vira condição.

### A5-18 — O javadoc do construtor variádico da média descreve outra ordem

**Onde:** `ui/chart/overlay/MovingAverage.java:157-161`

```java
/** @param settings period, kind, shift -- the shape, as a layout stores it */
public MovingAverage(int... settings) {
    this.period = settings.length > 0 ? Math.max(1, settings[0]) : 9;
    this.shift = settings.length > 1 ? settings[1] : 0;
}
```

O javadoc diz `period, kind, shift`; o código lê `settings[1]` como **shift**, e `kind` não
entra por aqui de jeito nenhum (vem do `appearance`). Como `parameters()` grava
`[period, shift]`, o código está certo e o comentário está errado — o pior dos dois arranjos,
porque o comentário é o que o próximo leitor vai acreditar.

### A5-19 — Filtrar NaN desalinha o número da sua cor no cabeçalho

**Onde:** `ui/chart/study/StudyPane.java:619-628` e `:579-583`

```java
for (double each : study.valueAt(bar)) {
    if (!Double.isNaN(each)) {
        found.add(format().format(each));
    }
}
```

e, na pintura, `colours.get(n)` com `n` sendo a posição na lista **já filtrada**. Se a linha
0 for NaN e a 1 finita, o valor da linha 1 sai pintado com a cor da linha 0. Não acontece
hoje (no estocástico o sinal é NaN sempre que o principal é; nas bandas a do meio é a do
índice 1 e só some por `showMiddle`, caso em que a cor 1 continua na lista) — mas o
acoplamento é acidental e some no primeiro indicador com aquecimentos diferentes por linha.
**Correção:** guardar o índice original junto do texto, ou emitir string vazia em vez de
pular.

### A5-20 — Records da área sem construtor compacto

**Onde:** `ui/chart/OverlayCatalog.java:51-52`, `ui/chart/Overlay.java:180`,
`ui/chart/InsertOverlayDialog.java:94`

```java
public record Kind(String nameKey, List<Integer> defaults, int minimum, int maximum,
                   Function<int[], Overlay> factory) {
```

```java
record Level(double at, Color colour, java.awt.Stroke stroke) { }
```

```java
public record Placement(Overlay indicator, StudyPane pane, boolean onPrice) {
```

Nenhum valida a própria invariante, contra a convenção da casa. `Kind` não checa
`minimum <= maximum`, nem `defaults` não-nula, nem cópia defensiva da lista (`List.copyOf`);
`Level` aceita `at` NaN, que vai direto para `Math.round(y(...))` em
`StudyPane.paintLevels:772`; `Placement` aceita o estado contraditório
`onPrice == true && pane != null`, que `inNewPane()` então lê como "não é painel novo" — o
chamador (`ChartHolder:407-414`) testa `onPrice()` primeiro e escapa, mas por sorte da ordem.

### A5-21 — `fits` supõe que `bounds()` tem dois elementos

**Onde:** `ui/chart/study/StudyStack.java:188-195`

```java
double[] mine = wanted.bounds();
double[] theirs = each.bounds();

if (mine == null || theirs == null
        || mine[0] != theirs[0] || mine[1] != theirs[1]) {
```

O contrato de `Overlay.bounds()` (`:145-147`) diz `{low, high}` ou null, mas nada obriga: um
implementador que devolva `new double[]{0}` derruba `fits` com
`ArrayIndexOutOfBoundsException` no meio do diálogo de inserção. Também é a comparação de
ponto flutuante por `!=` que a lista da casa cita — **aqui ela está certa**, porque a
pergunta é "faixa fixa e IGUAL", e faixa fixa é literal (0,0 e 100,0 em ambos), não
resultado de conta; deixo registrado só para que não seja "corrigido" com épsilon por
engano. **Correção:** validar o comprimento (`mine.length < 2` → recusa), ou trocar
`double[]` por um record `Bounds(double low, double high)` com invariante no construtor
compacto — o que também resolve A5-20 e o array mutável escapando do getter.

### A5-22 — `InsertOverlayDialog.edit` devolve um indicador sem a aparência do que substituiu

**Onde:** `ui/chart/InsertOverlayDialog.java:189-196` e `ui/chart/ChartCanvas.java:713-726`

```java
public static Overlay edit(Window owner, Overlay overlay) {
    InsertOverlayDialog dialog = new InsertOverlayDialog(owner, overlay, List.of());
    ...
    return dialog.chosen == null ? null : dialog.chosen.indicator();
}
```

O substituto vem da fábrica do catálogo, com cor, traço, escala própria e interpolação nos
padrões. `replaceOverlay` copia `isVisible()` e recalcula, mas nunca chama
`applyAppearance(existing.appearance())`. **Não é alcançável hoje** — `OverlayLegend.edit`
desvia a média e as bandas para os diálogos próprios (`:588-612`), e só média e bandas
respondem `fitsOnPrice() == true`, então nenhum overlay do preço chega a esta linha.
Fica como armadilha para o terceiro indicador de preço, que perderá a aparência ao ter o
período editado. **Correção:** uma linha em `replaceOverlay`:
`replacement.applyAppearance(existing.appearance());` antes do `calculate`.

### A5-23 — Javadoc órfão em `Overlay`

**Onde:** `ui/chart/Overlay.java:83-112`

```java
    /**
     * @return how the line is drawn: thickness and dash pattern
     *
     * <p>A default, so an indicator that has nothing to say about its own
     * appearance says nothing. ...</p>
     */
    /**
     * Paints anything that is an AREA rather than a line, under the lines.
```

Dois blocos de javadoc seguidos: o segundo documenta `paintUnder`, e o primeiro — o do
`stroke()` — não documenta nada. O `stroke()` de fato (`:109-112`) ficou sem javadoc. Erro
de edição, e o javadoc perdido é justamente o que explica por que existe um `default`.
**Correção:** mover o primeiro bloco para junto do `stroke()`, oito linhas abaixo.

### A5-24 — Texto de interface montado em Java

**Onde:** `ui/chart/study/StudyPane.java:601-603` e `:615`

```java
g.setColor(blend(1));
g.drawString("+" + left, at, baseline);
```

```java
return scale == null || scale.isBlank() ? name : name + " · " + scale;
```

Concatenação de literais para a tela. O `"+" + left` é o marcador de "há mais N
indicadores" e o `" · "` é o separador da escala no rótulo; nenhum dos dois passa pelo
bundle. São glifos e não frases, o que os deixa perto da fronteira — mas a regra da casa não
tem exceção escrita, e os títulos dos diálogos fazem o mesmo (`RsiDialog:84`,
`StochasticDialog:109`: `Messages.get(...) + " [" + study.period() + "]"`).
**Correção:** chaves `study.more = +{0}` e `study.scaleSeparator`, ou uma exceção escrita
para separadores tipográficos.

---

## O que está LIMPO

Cada item abaixo foi **conferido**, com o método, e o que tentei derrubar e não caiu.

**A regra do último candle fechado — `OwnScale.map` e `indexOfClosed` estão corretos.**
Percorri o `map` símbolo a símbolo com a série do `OwnPeriodTest` (15 barras de 1m desde
09:00, coarse de 3 barras de 5m) e conferi que o ponteiro corrente `closed` avança para `j`
exatamente quando `coarse.timeAt(j+1) <= fine.timeAt(i)`, que é a definição de "a barra
grossa `j` já fechou". Tentei quebrar com: série grossa de 1 barra (nunca fecha → NaN em
tudo, correto), série grossa de 2 (só a primeira pode fechar, e o laço para em
`closed + 1 < size - 1`, correto), e a barra fina caindo exatamente sobre a abertura da
grossa seguinte (o `<=` marca a anterior como fechada — que é o que o teste
`neverTheBarStillForming` fixa em 09:10). Confirmei também que `timeAt` das séries grossas é
a **abertura** do balde (`Timeframe:211` — `times[out] = startOf(source.timeAt(i), at)`),
que é o que a regra pressupõe. **O `map` não lê o futuro.**

**Os quatro consumidores do `OwnScale` usam a regra, e nenhum improvisa outra.**
`MovingAverage:361`, `RelativeStrength:356`, `SlowStochastic:506-507` e
`BollingerBands:403-405`. Procurei um cálculo paralelo — `grep` por `timeAt` fora do
`OwnScale` nos quatro: zero ocorrências. Tentei achar o padrão errado (barra grossa que
CONTÉM a fina) em algum deles: não existe. As bandas mapeiam **as três** séries pela mesma
regra em vez de mapear só o meio e derivar as bandas na série fina — o comentário nas
linhas 399-402 diz por quê, e a alternativa citada seria de fato pior.

**`fitsOnPrice()` — as quatro respostas estão certas.** `MovingAverage:259-263` true (é
preço), `BollingerBands:314-318` true (três linhas em pontos), `RelativeStrength:193-197`
false, `SlowStochastic:282-287` false. Tentei achar o erro que a interface existe para
impedir — um indicador de 0-100 dizendo que cabe no preço: não há. Confirmei também que os
únicos implementadores em `src/main` são esses quatro (`grep "implements Overlay"`), então
não há um quinto que tenha herdado um `default` — que é justamente o motivo pelo qual o
método não tem `default`.

**`StudyStack.fits` implementa exatamente as duas respostas "sim".** Segui a lógica caso a
caso: lista vazia → laço não roda → `true`; `wanted` já na lista → mesmo `nameKey` →
`continue` → `true`; mesmo indicador em outra escala → mesmo `nameKey` → `true`; diferentes
com `bounds()` fixos e iguais → `true`; diferentes com um `bounds()` nulo → `false` (as duas
direções, porque testa `mine == null || theirs == null`); diferentes com faixas fixas
distintas → `false`; e `wanted == null` → `false` na guarda de entrada. E — o caso que eu
esperava ver falhar — **um que cabe no primeiro e não no segundo é recusado**, porque o
`return false` está dentro do laço e não depois dele. O `PaneSharingTest` fixa os sete casos
com um `record Fake` que é só nome e faixa, incluindo `everyoneAlreadyInside:145-153`. Não
consegui construir entrada que passasse errado, exceto o `bounds()` de comprimento 1 de
A5-21.

**A aritmética do RSI está certa, inclusive nos dois casos de borda.** Refiz na mão
`bars(10, 11, 10.5, 12)` com período 3: subidas +1, 0, +1,5 → média 0,8333; quedas 0, 0,5, 0
→ média 0,1666; razão 5; `100 - 100/6 = 83,333` — bate com o código e com
`RelativeStrengthTest:94-105`. Conferi a semente (média simples da primeira janela para
**ambas**, `:394-399`, que é como Wilder começa), o alisamento clássico
(`up * (period-1) + rises[i]) / period`, `:410`) e a janela simples (`mean`, `:422-430`,
que soma `period` entradas terminando em `i` — **N barras para uma janela de N**, conferido
com aritmética de índices: em `i = period+1` toca `rises[2..period+1]`, nunca o
`rises[0]` que é sempre zero por não existir variação antes da primeira barra). Os dois
casos de borda do enunciado: **nada caiu → 100** (`reading:439-444`, e é correto, não é
divisão por zero escapando) e **nada se moveu → carrega o anterior, 50 na ausência de
anterior** (`up <= 0.0 ? carried`), com o `carried` propagado corretamente pelo
`into[i] = carried = reading(...)`. Tentei achar o `off-by-one` do primeiro valor: `size <=
period` devolve tudo NaN e o primeiro valor sai em `into[period]`, que é a primeira barra
com `period` variações completas — certo, e `beforeTheFirstWindow:167-179` fixa.

**A aritmética do estocástico está certa.** A janela de `%K` (`:527-530`) percorre
`i-period+1 .. i`, ou seja `period` barras; o `span <= 0` carrega o anterior em vez de
dividir por zero (`:537`), com 50 como primeiro `carried`; a primeira barra da série é NaN
por `i < period - 1`; e `smooth(fast, intoSlow)` seguido de `smooth(intoSlow, intoSignal)` é
a definição de "lento" (a média aplicada duas vezes). Tentei achar NaN escapando para a
pintura: o `smooth` reinicia janela, soma e `previous` a cada NaN (`:559-565`), então um
buraco no meio não contamina o que vem depois — e `paintLines:809` quebra a linha em NaN em
vez de plotar zero.

**A aritmética das bandas está certa.** Desvio populacional (`sum / period`, `:455`,
dividido por `n` e não por `n-1`, como o javadoc `:56-63` declara e justifica), medido em
torno da **linha do meio efetivamente desenhada** e não da média simples — o que importa
quando o meio é exponencial, e está documentado em `:45-54`. O aquecimento é NaN nas duas
bandas enquanto o centro for NaN **ou** `i < period - 1` (`:436`), que é a guarda dupla
correta. `clampDeviation:211-217` rejeita NaN, infinito e negativo — a única validação
defensiva de entrada de arquivo que encontrei em toda a área, e ela está certa.

**Formato salvo: as quatro voltas fecham.** Conferi campo a campo. As bandas usam
`name=value` (`:544-570`), que tolera campo ausente, campo novo e reordenação, com o
javadoc `:534-542` explicando por que quinze campos posicionais seriam uma armadilha —
raciocínio correto. Os três posicionais (média 7 campos, RSI 6, estocástico 15) leem por
`at(fields, i)` / `fields.length > i`, então **um arquivo com um campo a menos é tolerado**
e cada campo mantém o padrão da instância. Tentei quebrar com separador dentro do valor: os
valores gravados são nomes de enum (sem `;`), hex de cor, `Float.toString`,
`Double.toString` e códigos de escala como `5m` / `11R` — nenhum pode conter `;` nem `=`.
Tentei quebrar a cor: `Integer.toHexString(rgb & 0xFFFFFF)` perde zeros à esquerda mas
`Integer.parseInt(text, 16)` os recupera, e preto vira `"0"` e volta como preto. Tentei o
código de escala vazio: `PeriodCatalog.byCode` trata `null` e `isBlank` (`:163-166`),
devolvendo null, e o chamador cai na escala do gráfico — que é o comportamento documentado
em `OwnScale.of:52-59` e fixado por `OwnPeriodTest.unknownPeriodFallsBack`.

**Chaves de bundle: 36 de 36 presentes, nos dois arquivos.** Extraí todas as
`Messages.get("...")` da área e conferi uma a uma contra `messages.properties` e
`messages_pt_BR.properties`; conferi à parte as quatro que a extração automática não pega
por serem parametrizadas ou ternárias (`overlay.where.notOnPrice`, `overlay.where.pane`,
`overlay.insertTitle`, `overlay.editTitle`) e os prefixos de enum usados por
`Forms.named` (`overlay.ma.kind.ARITHMETIC|EXPONENTIAL|WEIGHTED` e
`study.rsi.smoothing.CLASSIC|SIMPLE`) — todos presentes. **Nenhuma chave ausente.**

**A extração do `LinePen` foi fiel.** Comparei linha a linha contra o `Pen` interno
removido em `a80f7ea` (`git show`): combo de estilo com `Forms.lineStyles()`, spinner
`(Math.round(width), 1, 8, 1)`, botão-amostra com `setOpaque`/`setBorderPainted(false)`,
`JColorChooser` com a mesma chave `overlay.ma.colour`, `addTo` com as mesmas cinco linhas
na mesma ordem, `setEnabled` propagando aos quatro, `line()` com recuo para `SOLID`,
`width()` como `floatValue`, e o `Sample` verbatim. **Nada de comportamento se perdeu na
extração** — o que ficou para trás foram os onze imports de A5-16, e os dois defeitos que
vieram junto (A5-7 e A5-8) já existiam antes dela. Os dois diálogos usam o `LinePen` da
mesma maneira; a única diferença é o `RsiDialog` não chamar `setEnabled` no seu único pen,
o que é correto (a linha do RSI está sempre visível), contra os três pens do
`StochasticDialog` cujo habilitar segue `showAverage` e `showLevels` (`:213-214`).

**Nenhum dos dois diálogos aplica valores quando é cancelado.** Em ambos, `apply()` só é
chamado dentro do `ActionListener` do OK (`RsiDialog:203-209`,
`StochasticDialog:238-244`); cancelar e fechar pelo X só fazem `dispose()`, `accepted`
fica `false`, e `StudyStack.settingsFor:226` não recalcula. Tentei achar o vazamento
óbvio — o `JColorChooser` do `LinePen` grava `chosen` na hora — mas `chosen` é estado do
pen, e o estudo só o recebe em `apply()`. Confirmei também que a escolha de escala
(`pickScale`) grava em `periodCode`, campo do diálogo, e não no estudo.

**Estado reinicializado entre reconfigurações.** `calculate` realoca os arrays em todos os
quatro (`values = new double[size]`), então mudar período ou escala não deixa cauda do
cálculo anterior; `computeOver` preenche com NaN antes de escrever
(`RelativeStrength:370`); e `MovingAverage.computeOver:304-320` faz a troca temporária do
campo `values` com restauração em `finally` — construção incomum, que examinei procurando
vazamento entre a série grossa e a fina, e que está correta: `into != keep` é a condição
exata para restaurar, e o caminho normal (`into == values`) não restaura porque não deve.

**Ouvintes.** Nenhum registrado e nunca removido. `StudyPane` registra o `MouseAdapter` em
si mesmo (morre com o componente); `onSettings` e `onArrangement` são campos de um único
slot, não listas; `canvas.follow(pane)` tem `unfollow` correspondente nos três caminhos que
tiram um painel (`hide:418`, `restore:262`, e `close()` do painel via `hide`). Tentei achar
o desbalanceamento em `moveTo:385-391`, que remove e readiciona todos os painéis do
container — mas ele não mexe na lista do canvas, e os objetos são os mesmos.

**Fronteira de camadas.** Nenhum arquivo da área é importado por `domain/`; a dependência
corre no sentido certo (`ui.chart` importa `domain.market.PriceSeries` e
`domain.market.Aggregation`). O `LayerBoundaryTest` já cobre isso como propriedade.

**Classes utilitárias recusam instanciação.** `OwnScale:48-50` e `OverlayCatalog:102-104`
têm construtor privado que lança `AssertionError`.
