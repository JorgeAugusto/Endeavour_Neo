# B5 — os indicadores

Auditoria II, 07/09/2026. Passada independente: não foram lidos
`docs/auditoria/*.md` nem os outros relatórios de `docs/auditoria/ii/`.

## O que foi lido

A área designada foi lida **inteira**, linha a linha. Conferido com `wc -l`:

| arquivo | linhas |
|---|---|
| `ui/chart/overlay/BollingerBands.java` | 658 |
| `ui/chart/overlay/MovingAverage.java` | 534 |
| `ui/chart/study/StudyPane.java` | 888 |
| `ui/chart/study/StudyStack.java` | 510 |
| `ui/chart/study/stochastic/SlowStochastic.java` | 638 |
| `ui/chart/study/stochastic/StochasticDialog.java` | 281 |
| `ui/chart/study/rsi/RelativeStrength.java` | 448 |
| `ui/chart/study/rsi/RsiDialog.java` | 229 |
| `ui/chart/OwnScale.java` | 189 |
| **total** | **4.375** |

O briefing falava em ~6.900 linhas; a soma real dos três caminhos nomeados é
4.375. A diferença provavelmente é o resto do ferramental de indicador na raiz
de `ui/chart/` (`Overlay`, `OverlayCatalog`, `OverlayLegend`,
`MovingAverageDialog`, `BollingerBandsDialog`, `InsertOverlayDialog` — 2.353
linhas juntas), que **não** estava no meu recorte. Desses li apenas
`Overlay.java` (220) e `OverlayCatalog.java` (137) por inteiro, como apoio.

Lidos como apoio, **não auditados**: `Overlay.java`, `OverlayCatalog.java`,
`ChartLayout.java`, `OwnPeriodTest.java`, `MovingAverageTest.java`,
`PaneSharingTest.java`, os dois `messages*.properties`, e trechos dirigidos de
`ChartCanvas.java` (linhas 296-306, 590, 732-764, 805-830, 1280-1360,
1440-1500, 1540-1620, 2050-2100).

**Não coube:** `BollingerBandsTest.java` (314) e `PaneOrderTest.java` (187) não
foram lidos — nenhum achado abaixo depende deles, e o único que poderia ser
derrubado por eles (B5-1) foi confirmado no código de produção, não no teste.

---

## ALTA

### B5-1. Os estudos são calculados sobre a série CRUA e desenhados nos índices da série FOLDADA

`ui/chart/study/StudyStack.java:121`, `:148`, `:227`, `:282`, `:434`

```java
public StudyPane show(Overlay study) {
    study.calculate(canvas.source());
```

```java
public void recalculate() {
    for (StudyPane pane : panes()) {
        for (Overlay study : pane.studies()) {
            study.calculate(canvas.source());
        }
    }
```

**Problema.** `ChartCanvas` tem duas séries, e o javadoc de cada uma diz
exatamente o que ela é:

`ui/chart/ChartCanvas.java:296-304`
```java
/**
 * The bars as they are STORED, before the period is applied.
 * ...
 */
private transient PriceSeries source = PriceSeries.empty();
```

`ui/chart/ChartCanvas.java:603-606`
```java
/** @return the bars this chart is drawing */
public PriceSeries series() {
    return series;
}
```

E a segunda nasce da primeira pela agregação da escala escolhida —
`ChartCanvas.java:1481` e `:1353`:

```java
this.series = period.apply(source);
```

Os cinco pontos do `StudyStack` chamam `calculate(canvas.source())`: os estudos
são computados sobre os índices da série **crua**. Mas o painel os desenha nos
índices da série **desenhada**, porque o viewport vem daí —
`ChartCanvas.java:1614`:

```java
private Viewport viewport() {
    return Viewport.of(series, plotBounds(), firstBar, visibleBars, stretch, priceOffset);
}
```

e `StudyPane` percorre exatamente esse viewport, `StudyPane.java:806`:

```java
for (int bar = viewport.firstBar(); bar < viewport.lastBar(); bar++) {
    double[] values = study.valueAt(bar);
```

O mesmo vale para o número do cabeçalho, `StudyPane.java:702-706`
(`canvas.barUnderCursor()` → `hoveredBar()` → `viewport().barAt(...)`, também
índice de `series`).

Os overlays de preço **não** têm esse problema: o próprio `ChartCanvas` os
calcula contra `this.series` (`:1288`, `:1356`, `:1481-1483`, `:1490`). São dois
caminhos, e só o dos estudos usa a série errada.

**Consequência.** Em qualquer gráfico cuja escala não seja a de armazenamento —
ou seja, todo gráfico de 5m, que é a escala de análise da casa, todo 15m e todo
renko — o estocástico e o IFR mostram, sob o candle *i*, o valor que o indicador
tinha na barra crua *i*, que é outro instante. Num 5m sobre 1m, o valor exibido é
o de aproximadamente cinco vezes mais atrás. E o erro **também anda para frente**:
num renko fino, em que o número de tijolos pode passar o número de minutos, o
tijolo *i* aconteceu ANTES do minuto *i*, e o painel passa a mostrar um valor que
o mercado ainda não tinha produzido — leitura do futuro, pela porta dos fundos.
Além disso `values.length == source.size() != series.size()`, então o fim do
painel fica em branco ou sobra série sem indicador, conforme o lado.

O caminho de escala maior herda o mesmo defeito: `scale.apply(series)` dentro de
cada indicador recebe a série crua, e `OwnScale.map` preenche um vetor indexado
pela série crua. A regra da última barra FECHADA continua certa dentro do seu
próprio espaço de índices — o que está errado é o espaço.

Piora: `ChartHolder.java:197-201` liga `onSeriesChanged` a `body.recalculate()`
com o comentário *"a study still holding the values of the series before would
draw a shape that never happened"*. O gancho existe, dispara na troca de escala,
e recalcula de novo contra a série errada.

**Correção.** Trocar `canvas.source()` por `canvas.series()` nos cinco pontos do
`StudyStack`. `source()` só deve ser lido por quem vai refoldar, que é o
`ChartCanvas`. Vale acrescentar um teste que ponha o canvas numa escala diferente
da de armazenamento antes de medir (ver abaixo por que os atuais não pegam).

**Tentei refutar assim.**

1. *Talvez `source` e `series` sejam o mesmo objeto.* Não são:
   `setSeries` (`:810-813`) atribui só `source` e chama `refold()`, que faz
   `series = period.apply(source)`. Coincidem em tamanho apenas quando `period`
   é a escala de armazenamento (`Timeframe.ONE_MINUTE`), que é o default.
2. *Talvez o `StudyPane` use um viewport sobre `source`.* Não: `plotViewport()`
   devolve `viewport()`, construído sobre `series`, e o javadoc de
   `plotViewport` (`:1540-1548`) diz que é justamente esse que é
   *"Shared with the panes below"* para que o x da barra seja o mesmo.
3. *Talvez algum outro lugar recalcule os estudos contra `series`.* Procurei
   `\.calculate\(` em toda a `ui/chart`: os únicos chamadores para estudos são
   os cinco do `StudyStack`; todos passam `canvas.source()`.
4. *Talvez os testes cubram isso e eu esteja lendo errado.* `PaneSharingTest`
   faz `canvas.setSeries(new RandomWalkSeries(300, 100.0))` e nunca chama
   `setPeriod`. Com a escala default, `source` e `series` têm o mesmo tamanho e
   a mesma indexação, e o defeito é invisível. `recalculatingReachesAll`
   (`PaneSharingTest.java:251`) afirma `second.valueAt(399)` não-NaN sobre uma
   série de 400 barras — passa exatamente porque as duas séries coincidem. É
   licença falsa, não cobertura.

Não consegui derrubar.

---

## MÉDIA

### B5-2. `OwnScale.indexOfClosed` não é chamado por ninguém, e os dois comentários sobre ele se contradizem

`ui/chart/OwnScale.java:74-95`

```java
public static int indexOfClosed(PriceSeries fine, PriceSeries coarse, int bar) {
    long when = fine.timeAt(bar);
    ...
    // A coarse bar is closed once the NEXT one has begun. Binary search on
    // that condition rather than a scan, because this is called per bar per
    // indicator and the chart repaints while the mouse moves.
```

E trinta linhas abaixo, `OwnScale.java:137-143`:

```java
// A RUNNING POINTER, the way map does it, and not a binary search per
// bar. indexOfClosed is the right answer to "which bar was closed at
// this instant" asked once; asked once per bar it walks the coarse
// series 825.000 times over.
```

**Problema.** `grep -rn "indexOfClosed" src/` devolve, fora da própria
declaração, só as duas menções em comentário. Nenhum chamador em produção,
nenhum em teste. O primeiro comentário afirma um regime de uso que não existe
("called per bar per indicator"); o segundo diz o contrário, no mesmo arquivo.

**Consequência.** É código público morto na única classe que guarda a regra de
não ler o futuro — a classe que o próprio javadoc declara ser *"The one place
this rule is written"*. Um segundo lugar onde a regra está escrita é a segunda
chance de escrevê-la errada, que é exatamente o que esse javadoc quer evitar. E
por não ter chamador nem teste, se alguém a usar amanhã ela entra sem prova. O
comentário que mente esconde precisamente isso.

**Correção.** Apagar `indexOfClosed` e a menção dela em `smooth`, ou — se for
para ficar como resposta canônica de uma pergunta pontual — corrigir o
comentário para dizer que não há chamador e dar-lhe um teste que a amarre a
`map`, provando que as duas concordam bar a bar.

**Tentei refutar assim.** Procurei também em `src/test/`: nada. Procurei por
referência via reflexão ou por nome em string: nada. A única outra ocorrência é
no cache do `graphify-out/`, que é artefato gerado e está desatualizado (registra
`OwnScale.java` sob `ui/chart/overlay/`, onde o arquivo não está mais).

### B5-3. `valueAt` aloca um `double[]` por barra, no laço de cálculo e no de pintura

`ui/chart/overlay/BollingerBands.java:431-432`

```java
for (int i = 0; i < mid.length; i++) {
    double centre = line.valueAt(i)[0];
```

`ui/chart/study/StudyPane.java:806-807`

```java
for (int bar = viewport.firstBar(); bar < viewport.lastBar(); bar++) {
    double[] values = study.valueAt(bar);
```

`ui/chart/study/StudyPane.java:750-751`

```java
for (int bar = viewport.firstBar(); bar < viewport.lastBar(); bar++) {
    for (double each : study.valueAt(bar)) {
```

**Problema.** Toda implementação de `valueAt` devolve um array novo
(`MovingAverage.java:294`, `BollingerBands.java:351,354`,
`RelativeStrength.java:227`, `SlowStochastic.java:337,341`). No Bollinger isso é
um `double[1]` por barra da série inteira, a cada recálculo. No `StudyPane` é um
array por barra visível **por linha** — o laço de `line` refaz a mesma chamada —
e a cada repaint, que acontece a cada movimento do mouse.

**Consequência.** É a alocação por elemento em laço quente que a convenção
proíbe, e o próprio projeto já pagou por ela: o comentário de
`SlowStochastic.smooth` (`:552-560`) conta que 1,65 milhão de `Double` boxados
por recálculo custaram 319 ms. Aqui é a mesma coisa, uma camada acima. Não muda
número na tela; muda a sensação de peso do gráfico, e sem nada obviamente errado
no código.

**Correção.** Ou uma sobrecarga `valueAt(int bar, double[] into)` no contrato,
ou — mais barato e local — no Bollinger ler `mid[i]` do vetor que a própria
`MovingAverage` acabou de preencher em vez de perguntar barra a barra, e no
`StudyPane` buscar `valueAt(bar)` uma vez por barra e percorrer as linhas dentro,
em vez de uma vez por linha.

**Tentei refutar assim.** Procurei um cache no `Overlay`: não há, e o javadoc do
contrato (`Overlay.java:33-37`) só promete que `valueAt` não recomputa a série —
promessa que é cumprida; a alocação é outra coisa. Verifiquei se o JIT poderia
escapar a alocação: `valueAt` é polimórfica sobre quatro implementações no mesmo
laço em `StudyPane`, o que impede a desvirtualização monomórfica de que a escape
analysis depende. `ChartCanvas.java:2075` faz o mesmo, mas é de outra área e não
entra aqui.

### B5-4. Todo recálculo de estudo acontece na EDT

`ui/chart/study/StudyStack.java:121`, `:148`, `:227`, `:282`, `:431-439`

```java
private void settingsFor(Overlay study) {
    ...
    if (changed) {
        study.calculate(canvas.source());
        relayout();
    }
}
```

**Problema.** Os cinco pontos que recalculam correm em resposta a um evento
Swing: abrir uma janela de ajustes, inserir um indicador, restaurar um layout,
trocar a série. Nada disso se encaminha por `SwingWorker` nem por
`invokeLater` com trabalho fora da EDT.

**Consequência.** O custo está medido, pelo próprio código, em dois lugares:
`OwnScale.java:139-142` — *"recalculating an average on its own scale took 183
ms, on the interface thread, once per indicator in the panel"* — e
`SlowStochastic.java:556-560` — *"319 ms to recalculate one stochastic"*. Com
três estudos num painel, apertar OK numa janela de ajustes congela a interface
por perto de um segundo, sem barra de progresso e sem nada na tela dizendo que
alguma coisa está acontecendo.

**Correção.** Levar o `calculate` para fora da EDT e voltar só para o
`relayout()`/`repaint()`, como o resto do projeto já faz com trabalho longo. A
convenção da casa diz que toda tarefa que passa de um segundo tem de aparecer no
rodapé; esta passa e não aparece.

**Tentei refutar assim.** Verifiquei se `calculate` já se encaminha sozinho:
nenhuma das quatro implementações toca em `SwingUtilities`. Verifiquei se algum
chamador acima já está fora da EDT: `ChartHolder.java:197` amarra
`onSeriesChanged` diretamente, e `settingsFor` roda depois de um diálogo modal,
que é EDT por definição. Considerei que os 183/319 ms fossem de uma versão
anterior mais lenta — mas o comentário de `smooth` diz que os 319 ms eram
*"Measured before"* a correção do anel de primitivos, enquanto o de `OwnScale`
descreve o estado atual; de qualquer modo o de `OwnScale` sozinho já sustenta o
achado.

### B5-5. A visibilidade de um estudo se perde na ida e volta pelo layout

`ui/chart/study/StudyStack.java:242-244`

```java
inside.add(new br.com.jorge.reis.endeavourneo.ui.chart.ChartLayout.Entry(
        study.nameKey(), study.parameters(), true, study.appearance()));
```

`ui/chart/ChartLayout.java:79-91` (o caminho de volta)

```java
public List<...Overlay> build() {
    ...
        if (study != null) {
            study.applyAppearance(entry.appearance());
            found.add(study);
        }
```

**Problema.** O terceiro campo de `Entry` é a visibilidade —
`ChartLayout.java:100-105`: *"@param visible whether the eye is open"*.
`remembered()` escreve `true` fixo em vez de `study.isVisible()`. E o caminho de
volta dos painéis, `Pane.build()`, aplica a aparência mas nunca chama
`setVisible(entry.visible())` — enquanto o caminho dos overlays de preço,
`Entry.build()` (`ChartLayout.java:127`), chama. Duas respostas para a mesma
pergunta, e a dos estudos é a errada nas duas pontas.

**Consequência.** Hoje é latente: só o `OverlayLegend` (`:582`) tem o olho que
alterna visibilidade, e ele cobre os overlays do preço, não os estudos do painel.
Mas `StudyPane.paintLines` (`:792`) **já** consulta `isVisible()`, ou seja, a
metade do desenho está pronta e esperando; no dia em que o olho aparecer no
cabeçalho do painel, esconder um estudo, salvar e reabrir vai trazê-lo de volta
aceso, e o defeito vai parecer da funcionalidade nova.

**Correção.** `study.isVisible()` no lugar do `true` em `remembered()`, e
`study.setVisible(entry.visible())` em `Pane.build()`, ao lado do
`applyAppearance` que já está lá.

**Tentei refutar assim.** Procurei se `Pane.build()` delega para `Entry.build()`
— não delega: chama `OverlayCatalog.build(...)` direto e monta à mão, sem a linha
do `setVisible`. Procurei um teste: `PaneSharingTest.theyComeBackTogether`
(`:215-234`) confere quantidade de painéis, quantidade de entradas e os períodos,
e nunca olha a visibilidade.

### B5-6. Só o desenho das linhas respeita `isVisible()`; cabeçalho, faixa e níveis ignoram

`ui/chart/study/StudyPane.java:791-792`

```java
for (Overlay study : studies) {
    if (!study.isVisible()) {
        continue;
    }
```

contra `StudyPane.java:740` (`range`), `:770` (`paintLevels`), `:851`
(`paintScale`) e `:543` (`paintHeader`), todos na forma

```java
for (Overlay study : studies) {
```

sem o teste.

**Problema.** Quatro laços sobre a mesma lista, um deles com guarda e três sem.

**Consequência.** Escondida a linha, o estudo continua esticando a escala
vertical do painel (`range` só ignora quem tem `bounds()` fixo, então isso morde
justamente os indicadores sem faixa fixa), continua desenhando seus níveis
horizontais de 20 e 80, continua ocupando lugar no cabeçalho e continua ditando
os números da régua da direita. Esconder um indicador que deforma a escala não
devolve a escala.

**Correção.** Uma resposta só para "este estudo participa do desenho": ou os
quatro laços consultam `isVisible()`, ou o painel filtra a lista uma vez e todos
percorrem a filtrada. O cabeçalho é o único caso em que ficar listado faz sentido
— é dali que se reacende —, e então ele deve mostrar o estado apagado, como o
`OverlayLegend` faz em `:284` e `:316`.

**Tentei refutar assim.** Procurei se `isVisible()` pode ser falso hoje para um
estudo: não pode, pelo motivo de B5-5, o que rebaixaria isto a inconsistência
morta. Não rebaixa porque `paintLines` já implementa a metade cara da regra: o
código afirma que a regra existe, e três dos quatro lugares não a cumprem — quem
ligar o olho vai encontrar o defeito pronto, não vai criá-lo.

### B5-7. Um `DecimalFormat` novo por número escrito na tela

`ui/chart/study/StudyPane.java:885-887`

```java
private static DecimalFormat format() {
    return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.getDefault()));
}
```

`ui/chart/study/StudyPane.java:619-628`

```java
for (double each : study.valueAt(bar)) {
    if (!Double.isNaN(each)) {
        found.add(format().format(each));
    }
}
```

**Problema.** `format()` constrói um formatador novo a cada chamada, e a chamada
está dentro do laço dos valores, que está dentro do laço dos estudos, que está
dentro de `paintHeader`. `paintScale` (`:845`) constrói mais um por pintura.

**Consequência.** Cada construção compila o padrão e resolve
`DecimalFormatSymbols` pelo `Locale`, o que é caro perto de escrever quatro
caracteres, e acontece a cada movimento do mouse — porque `mouseMoved`
(`:164-174`) repinta sempre que a entrada sob o ponteiro muda, e o número do
cabeçalho segue o cursor. É o mesmo padrão de B5-3, com um objeto mais pesado.

**Correção.** Um campo `static final` — `DecimalFormat` não é seguro entre
threads, mas todo uso aqui está na EDT, que é onde ele já é construído. Se a
troca de idioma em tempo de execução importar, reconstruir quando o `Locale`
mudar, não a cada número.

**Tentei refutar assim.** Verifiquei se algum uso sai da EDT: `paintComponent` e
`paintHeader` só correm na EDT, e `numbersOf` só é chamada de `paintHeader`.
Verifiquei se o `Locale` muda entre chamadas dentro de uma pintura: não muda —
se mudasse, o cabeçalho e a régua já estariam discordando entre si.

### B5-8. Duas cópias da regra "qual preço da barra é este `Source`"

`ui/chart/overlay/MovingAverage.java:387-396`

```java
private double priceAt(PriceSeries series, int bar) {
    return switch (source) {
        case OPEN -> series.openAt(bar);
        ...
        case TYPICAL -> (series.highAt(bar) + series.lowAt(bar) + series.closeAt(bar)) / 3.0;
        default -> series.closeAt(bar);
    };
}
```

`ui/chart/overlay/BollingerBands.java:462-471`

```java
private double priceAt(PriceSeries series, int bar) {
    return switch (source()) {
        case OPEN -> series.openAt(bar);
        ...
        case TYPICAL -> (series.highAt(bar) + series.lowAt(bar) + series.closeAt(bar)) / 3.0;
        default -> series.closeAt(bar);
    };
}
```

**Problema.** O mesmo `switch`, letra por letra, em duas classes — e as duas
terminam em `default ->`, que faz o compilador aceitar em silêncio uma constante
nova de `Source`.

**Consequência.** Acrescentar uma fonte de preço (a ponderada de fechamento, por
exemplo) faz a média usá-la e a banda cair no `default`, isto é, no fechamento. O
resultado é uma banda centrada numa linha que não é a que está desenhada — que é
exatamente o que o javadoc de `BollingerBands.basis` (`:82-91`) diz existir para
impedir: *"Two implementations of one idea drift"*. O comentário está certo sobre
a média e não percebeu que a segunda implementação é a dele mesmo, logo abaixo.

**Correção.** Um método na `MovingAverage.Source` (ou estático em
`MovingAverage`) que receba série e barra, e trocar o `default ->` por
`case CLOSE ->`, para que a próxima constante quebre a compilação em vez de
mentir.

**Tentei refutar assim.** Verifiquei se o Bollinger poderia usar o `priceAt` da
média: não pode hoje, é `private`. Verifiquei se o desvio poderia ser calculado
dentro da própria `MovingAverage`, tornando a cópia desnecessária: poderia, mas
seria outra mudança; a duplicação continua sendo a duplicação. Verifiquei se
`BollingerBands` guarda seu próprio `source`: não guarda, delega em `source()`
para `basis` — os dois `switch` leem o mesmo valor, o que confirma que são duas
respostas para a mesma pergunta.

### B5-9. O painel se remove do container de dentro do próprio `mousePressed`

`ui/chart/study/StudyPane.java:400-404`

```java
private void close() {
    if (getParent() instanceof StudyStack stack) {
        stack.hide(this);
    }
}
```

`ui/chart/study/StudyStack.java:417-421`

```java
public void hide(StudyPane pane) {
    canvas.unfollow(pane);
    remove(pane);
    relayout();
}
```

**Problema.** `close()` é chamado direto de `mousePressed` (`StudyPane.java:192`)
e de `drop()` (`:341`), que também vem de `mousePressed` (`:206`). Três métodos
adiante, o mesmo arquivo faz o oposto e diz por quê —
`StudyStack.java:340-342`:

```java
// After the release has finished being delivered. The move takes the
// pane out of this container and puts it back, and doing that to the
// component whose event is still on the stack is asking for trouble.
javax.swing.SwingUtilities.invokeLater(() -> moveTo(pane, gap));
```

**Consequência.** Duas respostas para a mesma pergunta no mesmo arquivo: o
reordenamento adia, o fechamento não. Depois do `remove(pane)` o `mouseReleased`
e o `mouseClicked` do mesmo gesto ainda são entregues a um componente já
destacado, que responde consultando `getParent()` — agora nulo — e caindo nos
`instanceof` (`:239`, `:262`, `:401`). Hoje isso resulta em silêncio, não em
exceção; é dívida com contorno acidental, não defeito visível.

Há um segundo efeito, mais frágil e por isso citado com ressalva: `entries` é
reconstruído a cada pintura (`:529`) e lido pelo mouse (`:425-437`), e `drop()`
encolhe `studies` sem tocar em `entries`. Enquanto o repaint pendente não roda,
`entryAt` pode devolver um índice que `studies.get(entry)` (`:206`, `:212`,
`:280`) não tem mais. Na prática a EDT quase sempre pinta entre dois cliques, o
que torna isso difícil de provocar — mas a janela existe e não há guarda.

**Correção.** `hide` e `drop` saírem por `SwingUtilities.invokeLater`, como
`endDrag` já faz e pelo motivo que ele já escreveu. E `entries.clear()` em
`drop()`/`add()`, para que a lista vazia signifique "ainda não pintei" em vez de
"pintei outra coisa".

**Tentei refutar assim.** Procurei uma guarda em `hide`: não há. Procurei se
`JComponent` protege contra remoção durante despacho: não protege — só deixa de
entregar eventos NOVOS; os já enfileirados do gesto corrente seguem. Sobre o
segundo efeito, tentei construir um caso certo (duplo clique no ✕ da última
entrada): o `mouseClicked` está protegido por `!crossAt(...)`, e o segundo
`mousePressed` depende do repaint não ter rodado — por isso está reportado como
janela, não como travamento garantido, e é o que rebaixa o achado a MÉDIA.

### B5-10. O javadoc do construtor de varargs descreve campos que ele não lê

`ui/chart/overlay/MovingAverage.java:157-161`

```java
/** @param settings period, kind, shift -- the shape, as a layout stores it */
public MovingAverage(int... settings) {
    this.period = settings.length > 0 ? Math.max(1, settings[0]) : 9;
    this.shift = settings.length > 1 ? settings[1] : 0;
}
```

**Problema.** O javadoc diz que a ordem é `period, kind, shift`; o código lê
`settings[1]` como `shift`, não como `kind`. E `parameters()` (`:278-288`), que é
o outro lado dessa ida e volta, escreve `[period]` ou `[period, shift]` — o
código concorda consigo mesmo, e só o comentário está fora.

**Consequência.** Este é o construtor por onde `OverlayCatalog`
(`OverlayCatalog.java:66-67`) e `ChartLayout.Entry.build()`
(`ChartLayout.java:124`) reconstroem toda média salva. Quem for acrescentar um
parâmetro seguindo o comentário vai pôr o novo campo na posição 1 e reinterpretar
silenciosamente o `shift` de todo layout já gravado — que é exatamente a falha
que a convenção "busque por chave, nunca por posição" existe para lembrar.

**Correção.** Trocar o javadoc para `period, shift`, e dizer ali que a ordem é a
de `parameters()` e que mudá-la reinterpreta layouts antigos.

**Tentei refutar assim.** Verifiquei se `kind` chega por outro caminho e o
comentário estaria falando dele: chega, mas por `appearance()`
(`MovingAverage.java:459`), que é uma linha de texto separada e nem sequer
posicionalmente vizinha. Verifiquei se algum chamador passa três inteiros:
nenhum passa mais de dois. O comentário está errado, não adiantado.

---

## BAIXA

### B5-11. Ramo inalcançável repetido em `map` e em `smooth`

`ui/chart/OwnScale.java:116-123` (e o gêmeo em `:147-153`)

```java
while (closed + 1 < coarse.size() - 1
        && coarse.timeAt(closed + 2) <= fine.timeAt(i)) {
    closed++;
}

if (closed < 0 && coarse.size() > 1 && coarse.timeAt(1) <= fine.timeAt(i)) {
    closed = 0;
}
```

**Problema.** Com `closed == -1` a condição do `while` já é
`0 < coarse.size() - 1 && coarse.timeAt(1) <= fine.timeAt(i)`, ou seja,
exatamente a do `if`. Se o `while` não avançou, o `if` também não avança; o bloco
nunca executa, nas duas cópias.

**Consequência.** Nenhuma no resultado. Custa leitura na classe mais delicada da
área: quem lê supõe que o `while` deixa um caso de fora e vai procurar qual é.

**Correção.** Apagar os dois `if`.

**Tentei refutar assim.** Testei mentalmente `coarse.size() == 1`: o `while` para
por `0 < 0`, e o `if` para por `coarse.size() > 1`. Testei
`coarse.timeAt(1) > fine.timeAt(i)`: os dois param pela mesma comparação. Não
achei entrada em que difiram.

### B5-12. Imports não usados

`ui/chart/study/StudyStack.java:22-26` — `Forms`, `PeriodCatalog`,
`PeriodDialog`, `Viewport`, nenhum referenciado no arquivo (o arquivo usa nomes
totalmente qualificados para `ChartLayout`, `Reordering`, `StochasticDialog` e
`RsiDialog`, e importados para os outros — as duas convenções convivem).

`ui/chart/study/stochastic/StochasticDialog.java:20-49` — `ChartCanvas`,
`ChartColors`, `Overlay`, `Color`, `Dimension`, `Graphics`, `Graphics2D`,
`RenderingHints`, `BorderFactory`, `JColorChooser`, `JComponent`: onze imports
sem uso, resto de quando o diálogo desenhava sua própria amostra.

**Correção.** Remover.

**Tentei refutar assim.** Procurei cada símbolo no corpo do respectivo arquivo,
inclusive dentro de javadoc com `{@link}`: nenhuma ocorrência.

### B5-13. `average.setEnabled(true)` incondicional

`ui/chart/study/stochastic/StochasticDialog.java:205-206`

```java
private void refreshEnabled() {
    average.setEnabled(true);
```

**Problema.** Todas as outras linhas do método ligam ou desligam conforme uma
caixa; esta liga sempre, e `refreshEnabled` é justamente o gancho de
`showAverage`. Parece resto de quando o campo dependia de `showAverage`.

**Consequência.** Nenhuma, e o comportamento está certo: o período de suavização
é usado mesmo com a linha de sinal escondida (`SlowStochastic.smooth` é chamada
duas vezes em `:542-543` de qualquer jeito). É um não-operação que sugere uma
regra que não existe.

**Correção.** Apagar a linha, ou comentar por que este é o único campo que
nenhuma caixa desliga.

**Tentei refutar assim.** Verifiquei se algo mais desabilita `average`: nada. Se
desabilitasse em outro lugar, a linha teria função de reativar.

### B5-14. `transient` em classes que não são serializáveis

`ui/chart/study/rsi/RelativeStrength.java:114`

```java
private transient double[] values = new double[0];
```

`ui/chart/study/stochastic/SlowStochastic.java:113-115`

```java
private transient double[] slow = new double[0];

private transient double[] signal = new double[0];
```

**Problema.** Nenhuma das duas implementa `Serializable` — `Overlay` também não.
`MovingAverage.values` e os três vetores de `BollingerBands`, que são a mesma
coisa, não são `transient`.

**Consequência.** Nenhuma em execução. Diz ao leitor que existe um caminho de
serialização que não existe, e as quatro classes irmãs discordam entre si sobre
a mesma decisão.

**Correção.** Tirar o `transient` das quatro, ou explicar a regra num lugar só.

**Tentei refutar assim.** Procurei `implements Serializable`, `writeObject` e
`readObject` nas quatro classes e em `Overlay`: nada. O `serialVersionUID` que
existe é dos componentes Swing (`StudyPane`, `StudyStack`), que herdam
`Serializable` de verdade e onde o `transient` faz sentido.

### B5-15. Dois javadoc empilhados: `stroke()` fica sem documentação e a dela vai parar em `paintUnder`

`ui/chart/Overlay.java:83-109` (fora do caminho nomeado, mas é o contrato de
todo indicador desta área)

```java
    /**
     * @return how the line is drawn: thickness and dash pattern
     * ...
     */
    /**
     * Paints anything that is an AREA rather than a line, under the lines.
     * ...
     */
    default void paintUnder(java.awt.Graphics2D g, Viewport viewport, int from, int to) {
```

**Problema.** O primeiro bloco descreve `stroke()`, mas o membro seguinte é
`paintUnder`. O javadoc de `stroke()` (`:109`) acabou órfão, e o compilador de
documentação vai atribuir o par ao `paintUnder`.

**Consequência.** Javadoc grudado no membro errado, no arquivo que os quatro
indicadores implementam. A explicação do default de 1,4 pixel — a única razão
registrada para esse número — some da documentação gerada.

**Correção.** Mover o primeiro bloco para junto de `stroke()`.

**Tentei refutar assim.** Reli para ver se o texto poderia descrever
`paintUnder`: fala de *"thickness and dash pattern"* e de *"One and a bit pixels,
solid, rounded"*, que é a assinatura de `stroke()` — não há leitura em que caiba
no outro.

### B5-16. Os padrões do catálogo repetem as constantes em vez de usá-las

`ui/chart/OverlayCatalog.java:80-92` (fora do caminho nomeado)

```java
new Kind("study.rsi",
        List.of(...RelativeStrength.PERIOD), 1, 2_000,
        numbers -> new ...RelativeStrength(first(numbers, 9))),

new Kind("study.stochastic",
        List.of(...SlowStochastic.PERIOD, ...SlowStochastic.AVERAGE), 1, 2_000,
        numbers -> new ...SlowStochastic(first(numbers, 8), second(numbers, 3))),
```

**Problema.** A lista de padrões usa as constantes; a fábrica, três linhas
abaixo, repete os mesmos valores como literais. `RelativeStrength.PERIOD` é 9,
`SlowStochastic.PERIOD` é 8 e `AVERAGE` é 3 — hoje batem.

**Consequência.** Mudar a constante muda o que o menu oferece e não muda o que a
fábrica assume quando a lista chega vazia. Números mágicos sem origem, com a
origem literalmente na linha acima.

**Correção.** `first(numbers, RelativeStrength.PERIOD)` e assim por diante.

**Tentei refutar assim.** Verifiquei se o `fallback` é alcançável: é, sempre que
`OverlayCatalog.build` recebe uma lista de parâmetros mais curta que a
esperada — que é justamente o caso de um layout escrito por outra versão, o
cenário que o javadoc de `build` (`:111-118`) diz querer sobreviver.

### B5-17. O nome do indicador na tela sai de `List.toString()`

`ui/chart/study/StudyPane.java:607-615`

```java
private static String labelOf(Overlay study) {
    String name = Messages.get(study.nameKey()) + " " + study.parameters();
```

contra `ui/chart/study/rsi/RsiDialog.java:84`

```java
super(owner, Messages.get("study.rsi") + " [" + study.period() + "]",
```

**Problema.** No cabeçalho do painel os colchetes e as vírgulas vêm do
`toString()` de `List<Integer>`; nos títulos dos dois diálogos vêm de
concatenação à mão. Nenhum dos dois formatos está no `ResourceBundle`.

**Consequência.** "Estocástico Lento [8, 3]" no painel e "Estocástico Lento [8]"
no diálogo — o separador, o espaço e quais parâmetros aparecem são decididos em
dois lugares por dois mecanismos. O texto traduzível está no bundle; a moldura
dele não está, e é ela que muda de idioma para idioma.

**Correção.** Uma chave com posicional — `study.title = {0} [{1}]` — e um só
método que monte o rótulo, usado pelo painel, pelos diálogos e pela legenda.

**Tentei refutar assim.** Verifiquei se `[8, 3]` é intencional e documentado:
`Overlay.parameters()` (`:50-55`) diz *"Shown as {@code [17 21 0 8 21]}"* —
separado por espaço, sem vírgula. O formato documentado não é o que
`List.toString()` produz, o que confirma que a moldura está solta e ninguém a
está governando.

### B5-18. `computeOver` troca o campo `values` por baixo de si mesma

`ui/chart/overlay/MovingAverage.java:316-332`

```java
private void computeOver(PriceSeries series, double[] into) {
    double[] keep = values;

    values = into;

    try {
        switch (kind) { ... }
    } finally {
        if (into != keep) {
            values = keep;
        }
    }
}
```

**Problema.** Os três métodos de cálculo escrevem no campo `values` em vez de
receberem o destino, então calcular sobre outra série exige apontar o campo para
outro vetor e devolvê-lo no `finally`.

**Consequência.** Correto hoje — verifiquei os dois chamadores e os dois casos do
`finally`. Mas `valueAt` lê esse mesmo campo, então qualquer futura pintura
concorrente com um cálculo (que é o que B5-4 pede como correção) leria a série
grossa como se fosse a fina.

**Correção.** `arithmetic`, `exponential` e `weighted` receberem `double[] into`
e `PriceSeries` como parâmetros, e o campo só ser atribuído no fim de
`calculate`.

**Tentei refutar assim.** Conferi que `calculate` chama `computeOver(series,
values)`, caso em que `into == keep` e o `finally` é inócuo, e que
`onItsOwnPeriod` chama `computeOver(coarse, slow)`, caso em que restaura. Não
achei caminho em que o campo fique apontando para o vetor errado hoje — por isso
é BAIXA e não MÉDIA.

---

## LIMPO

O que foi conferido e está certo, e como.

**A regra da última barra FECHADA, em `OwnScale`.** Verifiquei a aritmética dos
dois percursos, não só o comentário. Em `map` (`:112-127`) e em `smooth`
(`:146-187`) o ponteiro só avança para `c` quando
`coarse.timeAt(c + 1) <= fine.timeAt(i)`, isto é, quando a barra grossa `c` já
tinha fechado no instante em que a barra fina `i` começou. A condição do `while`
(`closed + 1 < coarse.size() - 1`) mantém `closed <= size - 2`, então a última
barra grossa — a que ainda está se formando — nunca é lida, mesmo quando por
acaso já esteja completa. Confere com `indexOfClosed`, que resolve a mesma
condição por busca binária: para o mesmo instante os dois devolvem o mesmo
índice. A interpolação (`:172-186`) mede a rampa de `timeAt(closed + 1)` — o
instante em que o valor passou a ser conhecível — até `timeAt(closed + 2)`, e
`along` fica em [0,1] por construção; o valor desenhado é sempre uma combinação
convexa de `slow[closed-1]` e `slow[closed]`, dois valores já fechados. Nada
adiante do mercado. `OwnPeriodTest` afirma isso como propriedade
(`nothingComesFromTheFuture`) e tem dentes de verdade: `interpolationStaysBehind`
foi reforçado com um `assertNotEquals` porque a versão anterior passava com a
interpolação arruinada — está escrito no próprio teste, `:152-157`.
**Ressalva:** essa cobertura existe só para a `MovingAverage`. O Bollinger, o IFR
e o estocástico repetem o mesmo par `map`/`smooth` sem teste próprio de escala
maior.

**O aquecimento dos seis cálculos.** Percorri barra a barra a fronteira de cada
um. `MovingAverage.arithmetic` (`:398-413`): soma corrente com subtração a partir
de `i >= period`, primeiro valor em `i == period - 1` com exatamente `period`
termos. `MovingAverage.exponential` (`:415-433`): NaN até `period - 2`, semente
em `period - 1` igual à média aritmética da primeira janela, recursão depois.
`MovingAverage.weighted` (`:435-453`): NaN até `period - 2`, divisor
`period(period+1)/2` conferido contra o laço. `BollingerBands.computeOver`
(`:431-459`): a linha central herda o NaN da média, e o desvio só é calculado com
a janela cheia — nunca um desvio parcial, que seria mais largo justamente na
borda esquerda. `RelativeStrength.computeOver` (`:367-419`): `size <= period`
devolve tudo NaN, a semente é a média simples das `period` variações que começam
em `i = 1`, primeiro valor em `into[period]` — que é onde Wilder o põe — e a
fórmula clássica `(up*(n-1) + rise)/n` confere. `SlowStochastic.computeOver`
(`:510-544`): %K é NaN até `period - 1`, e `smooth` (`:552-636`) só escreve
quando `held == average`, com semente exponencial igual à média da primeira
janela — idêntica, número por número, à de `MovingAverage.exponential`, como o
comentário de `:597-610` afirma; conferi as duas expressões lado a lado. O
aquecimento composto do estocástico (`period + average - 2` para a linha lenta,
mais `average - 1` para o sinal) cai onde deve.

**O anel de primitivos de `SlowStochastic.smooth`.** É a parte mais fácil de
errar da área e está certa. Verifiquei o ramo ponderado (`:618-632`): depois de
`next = (next + 1) % span` o índice `next` aponta para o elemento **mais velho**,
que é onde a próxima escrita vai cair, então `window[(next + at) % span]` com
peso `at + 1` de fato pesa mais o recente — que é o que o comentário promete. O
reinício em NaN (`:570-577`) zera `held`, `sum`, `next` e `previous` juntos, sem
deixar soma de uma janela contaminar a seguinte.

**Volume ausente e o piso de valor.** Nenhum dos quatro indicadores substitui
NaN por zero em lugar nenhum: conferi cada atribuição a vetor de saída nas seis
rotinas de cálculo. Todas as bordas escrevem `Double.NaN`, e `StudyPane`
(`:809`) e `numbersOf` (`:622-626`) pulam NaN em vez de desenhar zero.

**Divisão por zero tratada como significado, não como falha.**
`RelativeStrength.reading` (`:438-447`): sem quedas devolve 100; sem movimento
nenhum devolve o valor anterior, e 50 quando não há anterior. `SlowStochastic`
(`:537-539`): janela plana carrega o valor anterior. Os dois casos estão certos
e o javadoc de cada classe explica por quê, com a explicação batendo com o
código.

**A ida e volta de `appearance()`, campo a campo.** Comparei o que cada classe
escreve com o que cada uma lê. `MovingAverage` (`:458-507`): sete campos
posicionais, sete leituras, cada uma com queda própria — uma constante
desconhecida no campo 0 não custa a cor do campo 4, que é o que
`unknownAppearanceIsPartial` prova. `BollingerBands` (`:544-607`): dezesseis
pares `nome=valor`, todos lidos por nome, com a justificativa certa no javadoc
(`:534-542`) — quinze campos posicionais seriam um formato em que inserir um
ajuste no meio reinterpreta todo layout gravado. `RelativeStrength` (`:249-274`)
e `SlowStochastic` (`:370-413`): posicionais, com `at(fields, n)` devolvendo null
além do fim, de modo que uma linha curta de versão antiga mantém os padrões em
vez de falhar. Os campos que não estão em `appearance()` estão em `parameters()`,
e conferi que os construtores os leem na mesma ordem — inclusive o caso do
estocástico, que devolve os dois períodos **sempre**, mesmo com a linha de sinal
escondida, com o javadoc (`:289-300`) contando que a versão que encolhia a lista
perdia o período da média. A cor automática sobrevive como automática
(`"auto"`), o que `automaticStaysAutomatic` prova.

**O deslocamento não anda para a esquerda.** `setShift` (`:205-207`) trava em
zero, com o javadoc explicando que `valueAt` lê `values[bar - shift]` e que um
deslocamento negativo poria na barra `i` uma média feita com barras até
`i + |shift|`. `theShiftNeverGoesBackwards` testa com período 1, em que a média
É o fechamento, então qualquer vazamento da direita aparece como número errado e
não como suspeita. Bom teste.

**O texto de tela.** Extraí as 26 chaves usadas por `Messages.get` nos oito
arquivos da área e conferi uma a uma nos dois bundles: todas presentes em
`messages.properties` e em `messages_pt_BR.properties`, inclusive as das
constantes de enum (`overlay.ma.kind.*`, `study.rsi.smoothing.*`), que os
renderizadores de `JComboBox` montam por prefixo. Nenhum rótulo, aba, botão ou
dica literal no código — a única exceção é a moldura de B5-17, que é pontuação e
não frase.

**A regra de quem divide um painel.** `StudyStack.fits` (`:178-198`) diz sim ao
mesmo indicador em escalas diferentes e a indicadores diferentes de faixa fixa
igual, e não a todo o resto — inclusive ao caso mais escorregadio, dois
indicadores diferentes que se ajustam aos dados, que `bounds() == null` recusa.
`PaneSharingTest` cobre os cinco casos, e `everyoneAlreadyInside` fecha o buraco
de "coube no primeiro e passou pelo segundo". Verifiquei que `restore` (`:266-291`)
deliberadamente **não** reaplica a regra, com o motivo escrito: o que foi gravado
era legal quando foi escrito, e uma regra que endureceu depois não deve esvaziar
o painel de ninguém.

**A geometria do empilhamento.** `Stacked.layoutContainer` (`:479-508`) garante o
piso do gráfico antes de tudo e apara os painéis de baixo para cima; conferi que
`top >= CHART_FLOOR` vale mesmo quando a janela é menor que a soma dos painéis, e
que `left` nunca fica negativo por causa do `Math.max(0, left)`. O reordenamento
(`:372-395`) tira e recoloca **todos** os painéis em vez de mover um por cima dos
outros, com o comentário explicando que contar em volta do canvas é a aritmética
que fica certa até alguém acrescentar um terceiro tipo de filho — está certo.

**O que ficou de fora e por quê.** `ChartCanvas.java:2075` faz a mesma alocação
por barra de B5-3, e `ChartLayout.Pane.build()` é metade de B5-5 — os dois estão
citados como evidência mas pertencem a outras áreas e não entram na contagem
desta. Registro uma observação sem severidade, porque não é da minha área e não é
código: existe um diretório gerado `graphify-out/cache/` **dentro** de
`src/main/java/br/com/jorge/reis/endeavourneo/`, e o cache está desatualizado —
registra `OwnScale.java` sob `ui/chart/overlay/`, caminho em que o arquivo não
está. Artefato gerado dentro da árvore de fontes acaba compilado e empacotado
junto.
