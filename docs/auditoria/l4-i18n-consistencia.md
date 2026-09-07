# L4 — i18n e consistência

Lente transversal. Uma pergunta em todo o código: **o texto está todo no
bundle, e o que devia ser uma coisa só é uma coisa só?**

## O que rodou

| padrão | ocorrências | lidas (±40 linhas) |
|---|---|---|
| `setText\("` … `createTitledBorder\("` (11 construtores/setters de rótulo) | 12 | 12 |
| `showMessageDialog\|showConfirmDialog\|showInputDialog\|showOptionDialog` | 5 | 5 |
| `String\.format\(\s*"` | 12 | 12 |
| `Messages\.(get\|orElse\|mnemonic)\(` com literal | 223 chaves distintas | — (comparadas por script) |
| `Messages\.(get\|orElse\|mnemonic)\(` com expressão | 28 | 28 |
| `\+\s*"[^"]*[A-Za-zÀ-ÿ]{2,}[^"]*"` (concatenação com palavra) | 49 | 49 |
| separadores `" - "`, `" · "`, `" a "`, `" / "`, `" — "` | 68 | 68 |
| literais com espaço e letras em `ui/` + `platform/` (fora de comentário) | 13 | 13 |
| literais `"[A-ZÀ-Þ][a-zà-ÿ]{2,}"` (palavra capitalizada) em `ui/` + `platform/` | 1 | 1 |
| `static final <primitivo> CONSTANTE = …` | 214, 18 nomes repetidos | 18 |
| `DateTimeFormatter.ofPattern\|new DecimalFormat` | 22 | 22 |
| `Messages.mnemonic\|setMnemonic\|setDisplayedMnemonic` | 8 sítios | 8 |

Mais quatro varreduras por script sobre os dois bundles: aridade de
`MessageFormat`, aspas simples, chaves duplicadas, e mnemônico que não existe
no rótulo que ele sublinha.

A A7a já fechou a simetria dos dois arquivos (331 chaves em cada, diferença
zero). Confirmei o número de passagem e não o refiz: **332 linhas de chave,
331 distintas** — a diferença é `replay.speed`, já reportada (A6-17, A7a-21).

---

## Achados ALTA

Nenhum, e digo como cheguei a isso em vez de deixar a seção vazia.

As duas portas de ALTA da régua deste relatório foram checadas por script e
fecharam:

- **Chave usada e ausente do bundle: zero.** As quatro cadeias que a extração
  de `Messages.get("…")` devolveu e que não estão no bundle são
  `navigator.role.`, `navigator.scale.`, `navigator.tickSource.` e
  `settings.language.` — prefixos de concatenação, não chaves. Nenhuma
  chamada pode devolver `!chave!` em produção (a conta está adiante).
- **Constante duplicada com valores divergentes:** dos 18 nomes de constante
  repetidos entre arquivos, 17 são ou o mesmo valor para o mesmo conceito
  (`TICK = 5.0`, `VERSION = 1`, `HEADER_BYTES`, `BUTTON = 14`, `SLIP = 3`,
  `ALPHA = 0.94f`) ou o mesmo nome para conceitos diferentes por construção
  (`KEY`, `PADDING`, `CHUNK`, `RECORD_BYTES`, `PERIOD`, `HEIGHT`). Sobrou uma
  divergência real — `OFFSET`, L4-6 — cuja consequência medida é de dois
  pixels. A régua diz ALTA; o efeito não sustenta ALTA, e reportá-la ali
  faria as ALTAs de verdade deste índice valerem menos. Está em MÉDIA, com
  esta nota para quem quiser reordenar.

---

## Achados MÉDIA

### L4-1 — O catálogo de períodos escreve em português dentro do Java, e a busca por nome só funciona em português

**Onde:** `src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/PeriodCatalog.java:224-251`
(produto), `src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/PeriodDialog.java:202`
(consumidor)

**Trecho:**

```java
    private static String describe(Timeframe frame) {
        int minutes = frame.minutes();

        if (minutes == 0) {
            return "1 dia";
        }

        if (minutes == -1) {
            return "1 semana";
        }

        if (minutes == -2) {
            return "1 mês";
        }

        if (minutes == 1) {
            return "1 minuto";
        }

        if (minutes % 60 == 0) {
            int hours = minutes / 60;

            return hours + (hours == 1 ? " hora" : " horas")
                    + " (" + minutes + " minutos)";
        }

        return minutes + " minutos";
    }
```

E o que a coluna direita do diálogo de período desenha:

```java
                setText(String.format("%-6s   %s", choice.code(), choice.description()));
```

**Problema:** oito cadeias de interface em português, com singular e plural
resolvidos em Java, dentro de `ui/chart`. É a maior concentração de texto fora
do bundle no repositório — o resto do produto tem 331 chaves e treze literais
de rótulo, todos vazios ou pontuação.

Pior que o texto: **`byName` procura dentro dessa cadeia.**

```java
    /** @return the periods whose name contains the text, for typing "ren" or "dia" */
    private static List<Choice> byName(String text) {
        String wanted = text.toLowerCase(Locale.ROOT);
        ...
            if (choice.description().toLowerCase(Locale.ROOT).contains(wanted)
```

O javadoc diz literalmente que o leitor digita `dia`. Em inglês não existe
`dia` para digitar, e nada no diálogo diz isso.

**Consequência:** com o idioma em inglês — que o `GeneralPage` oferece e o
`LanguageTest` defende — o diálogo de período abre uma lista metade em
inglês (a coluna do código, e o cabeçalho vindo de `period.title`) e metade em
português. Digitar `day` não acha nada; digitar `dia` acha. É o único diálogo
do programa que não obedece à configuração de idioma.

**Correção:** seis chaves novas — `period.day`, `period.week`, `period.month`,
`period.minute`/`period.minutes`, `period.hour`/`period.hours` — no formato de
`ruler.day`/`ruler.days`, que já existe e já resolve singular e plural no
bundle (`Measurement.java:140` é o modelo pronto). `byName` passa a comparar
contra a descrição traduzida, o que a torna correta em qualquer idioma sem
mudar de forma.

**Tentei refutar:** (a) é `description()` mesmo mostrado, ou só um campo de
busca? `PeriodDialog.java:202` desenha `choice.description()` como a segunda
coluna da lista — é a única coisa que explica o código à esquerda. (b) é
`describe` alcançável, ou código morto? `common()` (linha 190) e `forText()`
(linha 140) o chamam, e `common()` é o que a lista mostra antes de qualquer
tecla. (c) o programa é brasileiro, isto é intencional? Não: o mesmo arquivo
tem `period.title`, `period.hint` e `period.nothing` no bundle, nos dois
idiomas. A intenção está registrada; a implementação é que ficou para trás.

---

### L4-2 — A unidade "pts" está no bundle numa tela e grudada no Java na outra

**Onde:** `src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/PeriodCatalog.java:104`,
`:148`, `:195` contra
`src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/RulerReadout.java:118`

**Trecho:** o que vai para o título do gráfico:

```java
        public String title() {
            if (aggregation instanceof Renko renko) {
                return code + " - " + trim(renko.brick()) + " pts";
            }
```

e, duas vezes, o que vai para a lista:

```java
            choices.add(new Choice(number + "R",
                    number + "R (renko " + trim(brickOf(number)) + " pts)",
```

contra o que a régua faz com a mesma unidade:

```java
        rows.add(new String[]{Messages.get("ruler.change"),
                sign + price.format(difference) + Messages.get("ruler.points"), mood});
```

com `ruler.points = pts` nos dois bundles (linha 154 de cada).

**Problema:** a mesma palavra, para a mesma grandeza, tem chave num lugar e é
literal em três outros. A chave existe e está a um `import` de distância.

**Consequência:** hoje é invisível, porque `ruler.points` vale `pts` nos dois
idiomas. No dia em que alguém traduzir para `pontos` ou `points`, a régua muda
e o título do gráfico não — e as duas telas passam a nomear a mesma coisa de
dois jeitos, com a divergência escondida atrás de uma tradução que parecia
inofensiva.

**Correção:** `Messages.get("ruler.points")` nos três sítios, ou renomear a
chave para `unit.points` já que ela deixou de ser só da régua.

**Tentei refutar:** `" pts"` é separador de formato salvo? Não —
`Choice.title()` alimenta `ChartCanvas.setPeriod(…, choice.title(), choice.code())`
(`ChartCanvas.java:903`), e o que é gravado e relido é `code()`, nunca
`title()`. O javadoc de `periodCode` (`ChartCanvas.java:316-320`) declara a
separação: *"the label is for reading and the code is for rebuilding"*.
`title()` é rótulo puro.

---

### L4-3 — Trocar o idioma não move o `Locale` da JVM, e metade do que o leitor vê não é do bundle

**Onde:** `src/main/java/br/com/jorge/reis/endeavourneo/platform/Language.java:96`

**Trecho:**

```java
    public static void install() {
        // Always, including SYSTEM -- whose locale() is the machine's. Doing it
        // only for the other two meant switching BACK to "follow the system"
        // left the previous choice in place until the next launch.
        Messages.setLocale(remembered().locale());
    }
```

**Problema:** `install()` troca o bundle e mais nada. Nove sítios lêem
`Locale.getDefault()` diretamente e não sabem que o leitor escolheu outra
coisa:

| onde | o que o leitor vê |
|---|---|
| `ui/chart/ChartCanvas.java:163` | `MMM/yy` no eixo de tempo: `set/26` |
| `ui/replay/DatePicker.java:183` | o mês por extenso no calendário: `setembro 2026` |
| `ui/replay/DatePicker.java:196` | as iniciais dos dias da semana: `S T Q Q S S D` |
| `ui/chart/BarReadout.java:256`, `ChartCanvas.java:647` e `:2289`, `OverlayLegend.java:314`, `RulerReadout.java:157`, `Sessions.java:118`, `study/StudyPane.java:886` | separador decimal e de milhar: `121.500,00` |

Some-se o que o Swing decide sozinho pelo mesmo `Locale.getDefault()`: os
botões dos quatro `JOptionPane` (`ChartCanvas.java:895`, `LayoutBar.java:401`,
`:444`, `:476`, `SeriesWindow.java:351`) e o `JColorChooser` de
`LinePen.java:99`. Numa máquina brasileira com o idioma em inglês, a pergunta
é *"Remove layout X?"* e os botões são **Sim** e **Não**.

**Consequência:** o `LanguageTest` prova que o bundle troca; nada prova que a
tela troca, e ela não troca inteira. É o defeito de forma idêntica ao que o
comentário de `Messages.setLocale` (`Messages.java:141-152`) descreve ter
corrigido uma vez — "It looked like the setting did nothing" — sobrevivendo na
metade do problema que aquela correção não olhou.

**Correção:** `Locale.setDefault(remembered().locale())` antes do
`Messages.setLocale`, no mesmo `install()`. O ponto está certo: `install()` já
é chamado antes da primeira janela e de novo a cada troca, e a janela já é
reconstruída depois.

**Tentei refutar:** (a) `Locale.getDefault()` nesses sítios é intencional,
"formato do sistema mesmo em inglês"? Nenhum dos nove tem comentário dizendo
isso, e este repositório comenta o porquê de tudo — `Sessions.java:118` e
`StudyPane.java:886` não têm uma linha de justificativa. (b) A A7b-30 já cobre
isto? A A7b-30 é `"0,0%" escrito no código, e formatação dependente do locale
padrão` — um sítio. O que reporto é a causa comum dos nove, e ela está numa
linha só.

---

### L4-4 — Dezoito linhas de bundle que ninguém lê, e uma delas é uma chave trocada sem apagar a antiga

**Onde:** `src/main/resources/messages.properties` (e as mesmas linhas em
`messages_pt_BR.properties`)

**Trecho:** as nove chaves de rótulo, com a linha exata:

```properties
75:  settings.appearance.theme = Theme
108: menu.chart = Chart
110: action.close = Close
114: view.documents = Documents
115: view.chartsOpenInWindows = Charts open in their own windows, so they can be spread across monitors.
141: chart.style.candleHollow = Candles (hollow up)
146: chart.measureMode = Measure mode (Ctrl)
239: replay.ticks = Ticks
241: replay.date = Date
```

e os nove mnemônicos mortos — quatro porque o rótulo morreu junto (`103`,
`105`, `107`, `117`, `119` continuam com rótulo vivo, mas o rótulo virou
`setToolTipText` e um *tooltip* não tem letra sublinhada):

```properties
103: chart.style.candle.mnemonic = C
105: chart.style.line.mnemonic = L
107: chart.resetScale.mnemonic = V
109: menu.chart.mnemonic = C
111: action.close.mnemonic = C
117: chart.dock.mnemonic = D
119: chart.float.mnemonic = F
142: chart.style.candleHollow.mnemonic = H
147: chart.measureMode.mnemonic = M
```

**Problema:** três mudanças de desenho deixaram o texto velho para trás.

1. **O menu do gráfico virou barra de ferramentas.** `ChartHolder.item(String, Runnable)`
   (`ChartHolder.java:734`) é o único sítio de `ChartHolder` que chama
   `Messages.mnemonic`, e ninguém o chama — a BAIXA do índice já registra que
   ele é morto. Os cinco botões que sobreviveram (`chart.style.candle`,
   `chart.style.line`, `chart.resetScale`, `chart.dock`, `chart.float`) só
   usam `setToolTipText` (`ChartHolder.java:708`, `:720`). O rótulo vive; o
   mnemônico não pode viver.
2. **`chart.style.candleHollow` virou preferência, e a chave velha ficou.**
   Este é o caso perigoso: o oco deixou de ser um *estilo* e virou
   `settings.chart.hollow` — `ChartPreferences.java:139` documenta a decisão
   ("A setting and not a drawing style of its own"). O rótulo novo está na
   linha 274, e o velho continua na 141, com a tradução em dia. Quem for
   traduzir amanhã traduz os dois e não descobre qual está ligado.
3. **`replay.date` foi partido em `replay.from`/`replay.to`** quando o replay
   ganhou intervalo, e `replay.ticks` foi substituído por `navigator.ticks`
   (`ReplayFeed.java:210`, `Segmentable.java:97`). As duas chaves antigas
   ficaram, com os nomes mais genéricos do arquivo — exatamente as que alguém
   reusaria por engano.

`view.documents` e `view.chartsOpenInWindows` são de um painel "Documents" que
não existe: `grep -rn 'documents\|Documents' src/main --include=*.java` não
devolve nada.

**Consequência:** 36 linhas de manutenção (18 × 2 arquivos) que custam
tradução e revisão e não pagam nada, e uma delas — a 141 — é uma armadilha:
duas chaves para o mesmo conceito, com a errada em cima.

**Correção:** apagar as 18 nos dois arquivos. Se algum dia o menu do gráfico
voltar, note que os mnemônicos ingleses já colidiam: `chart.style.candle = C`
e `action.close = C` no mesmo menu.

**Tentei refutar:** cada uma das nove foi passada por
`grep -rn '"<chave>"' src/` (main **e** test) e por
`grep -rnP '"(view|replay|settings|chart|menu|action)\.[a-zA-Z.]*"\s*\+'`
para descartar montagem por concatenação — nada. `LanguageTest.java:48` e
`:52` chegaram a parecer o dono de `menu.chart`, mas usam `settings.chart`, que
é o título da aba de preferências e está vivo (`ChartPage.java:100`). Os nove
mnemônicos foram confrontados com os oito únicos sítios de `setMnemonic` do
`src/main`.

---

### L4-5 — Quatro maneiras de escrever "enum → rótulo", e o `MovingAverageDialog` inteiro é uma cópia do `Forms`

**Onde:** `src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/MovingAverageDialog.java:296`,
`:316`, `:407`, `:426` contra
`src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/Forms.java:71`, `:91`,
`:135`, `:152`, `:195`

**Trecho:** `MovingAverageDialog.java:407-424` —

```java
    private javax.swing.ListCellRenderer<Object> named(String prefix) {
        return new javax.swing.DefaultListCellRenderer() {
            ...
                if (value instanceof Enum<?> item) {
                    setText(Messages.get(prefix + item.name()));
                }
```

`Forms.java:135-150` —

```java
    public static ListCellRenderer<Object> named(String prefix) {
        return new DefaultListCellRenderer() {
            ...
                if (value instanceof Enum<?> item) {
                    setText(Messages.get(prefix + item.name()));
                }
```

`GeneralPage.java:78-92`, uma terceira vez, à mão:

```java
        language.setRenderer(new javax.swing.DefaultListCellRenderer() {
            ...
                if (value instanceof br.com.jorge.reis.endeavourneo.platform.Language chosen) {
                    setText(Messages.get("settings.language." + chosen.code()));
                }
```

`AppearancePage.java:145-156`, uma quarta:

```java
    private static String key(Theme theme) {
        return "theme." + theme.name().toLowerCase(java.util.Locale.ROOT);
    }
```

**Problema:** `Forms` foi extraído de `MovingAverageDialog` e as cópias
originais nunca foram apagadas. Não é só o `named`: `group` (296 contra
`Forms:71`), `field` (316 contra `Forms:91`), `form` (288 contra `Forms:62`),
`LineRenderer` (426 contra `Forms.lineStyles`, 152) e `Swatch` (466 contra
`Forms.Swatch`, 195) são idênticos linha a linha, incluindo o comentário
*"Never shorter than a text field would be"* e o número mágico `150`. É a
mesma extração que a A5-16 registra pelo outro lado (onze imports mortos no
`StochasticDialog`).

**Consequência:** o `BollingerBandsDialog` usa `Forms`; o `MovingAverageDialog`
usa a sua cópia. Os dois desenham o mesmo formulário e são o mesmo diálogo
para o leitor. Uma correção de espaçamento, de renderer ou de chave aplicada
em `Forms` conserta um e não o outro, e não há nada na tela que revele qual
foi consertado. Hoje eles não divergiram — medi; é justamente o momento de
apagar a cópia, antes de custar a diferença.

**Correção:** apagar os cinco membros privados de `MovingAverageDialog` e
chamar `Forms`, como o `BollingerBandsDialog` já faz. `GeneralPage` e
`AppearancePage` passam a `Forms.named("settings.language.")` e
`Forms.named("theme.")` — este último exige que `Theme.name()` case com a
chave, que é a mesma cadeia em minúsculas; um `Forms.named(prefix, mapper)` ou
uma chave `theme.LIGHT` resolve sem inventar nada.

**Tentei refutar:** `Forms` é de outro pacote e não daria para usar? Os dois
estão em `ui.chart`, e o `MovingAverageDialog` já chama `Forms` indiretamente
pelo `LinePen`. Há divergência que justifique a cópia? Comparei os cinco pares
linha a linha: só mudam qualificações de import (`javax.swing.UIManager`
contra `UIManager`) e a visibilidade (`private static` contra `public static`).

---

### L4-6 — Os dois quadros flutuantes do gráfico compartilham três constantes e discordam na quarta

**Onde:** `src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/BarReadout.java:62-67`
contra `src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/RulerReadout.java:45-49`

**Trecho:**

```java
final class BarReadout {
    private static final int PADDING = 10;

    /** Gap between the cursor and the corner of the box. */
    private static final int OFFSET = 18;

    private static final float ALPHA = 0.94f;
```

```java
final class RulerReadout {
    private static final int PADDING = 10;

    private static final int OFFSET = 16;

    private static final float ALPHA = 0.94f;
```

**Problema:** os dois são o mesmo objeto de tela — uma caixa semitransparente
ancorada no cursor dentro do mesmo gráfico. `PADDING` e `ALPHA` batem;
`OFFSET`, que o javadoc de um deles define como *"gap between the cursor and
the corner of the box"*, difere em dois pixels. O único javadoc está na cópia
que tem 18; a de 16 não diz nada, o que sugere que a divergência é deriva e
não decisão.

**Consequência:** as duas caixas saltam dois pixels uma em relação à outra
quando o leitor entra e sai do modo régua, com o mouse parado. É cosmético — e
é por isso que está em MÉDIA e não em ALTA, contra a régua deste relatório;
veja a nota na seção ALTA.

**Correção:** um valor só, num lugar só. As três constantes descrevem a mesma
caixa e cabem num `Readouts` ao lado das duas classes, que são ambas
`package-private` e utilitárias.

**Tentei refutar:** os dois são âncoras diferentes de propósito? Os dois
`paint` recebem `Point anchor` e `Rectangle area` com a mesma assinatura e
posicionam do mesmo jeito. Nenhum comentário em nenhum dos dois arquivos
justifica dezesseis contra dezoito.

---

### L4-7 — O título do diálogo de indicador tem prefixo em dois deles e não tem nos outros dois

**Onde:** `ui/chart/MovingAverageDialog.java:117` e
`ui/chart/BollingerBandsDialog.java:141` contra
`ui/chart/study/rsi/RsiDialog.java:84` e
`ui/chart/study/stochastic/StochasticDialog.java:108`

**Trecho:**

```java
        setTitle(Messages.get("overlay.dialog.title",
                Messages.get(average.nameKey()) + " [" + average.period() + "]"));
```

```java
        super(owner, Messages.get("study.rsi") + " [" + study.period() + "]",
                ModalityType.APPLICATION_MODAL);
```

com `overlay.dialog.title = Indicators > {0}` / `Indicadores > {0}` (linha 290).

**Problema:** os quatro diálogos são a mesma coisa para o leitor — a caixa de
propriedades de um indicador, aberta do mesmo menu. Dois recebem o prefixo
`Indicadores > ` do bundle; dois montam o título direto em Java e não recebem
prefixo nenhum. O `" ["` e o `"]"` estão literais nos quatro.

**Consequência:** a barra de título diz `Indicadores > Média móvel [9]` num
caso e `IFR (RSI) [14]` no outro, na mesma sessão. E os dois que não usam a
chave não passam pelo bundle: se a convenção do prefixo mudar, ela muda pela
metade.

**Correção:** `Messages.get("overlay.dialog.title", Messages.get("study.rsi") + …)`
nos dois estudos — ou, melhor, uma chave `overlay.dialog.titleWithPeriod` que
carregue o colchete também, já que `" [" + n + "]"` está literal em quatro
sítios.

**Tentei refutar:** os estudos são deliberadamente "não-indicadores"? Não: os
quatro saem de `OverlayCatalog`/`StudyStack` pelo mesmo caminho, e
`InsertOverlayDialog.java:288` trata estudo e sobreposição como o mesmo tipo de
item, com `Messages.get("overlay.where.notOnPrice", kind.label())`.

---

### L4-8 — Catorze linhas do bundle brasileiro estão sem acento, não quatro

**Onde:** `src/main/resources/messages_pt_BR.properties`

**Trecho:** a BAIXA já no índice cita as linhas 203, 208, 210 e 211. As outras
dez:

```properties
152: ruler.interval = Intervalo
195: chart.renkoNeedsTicks = Nao ha ticks gravados para todos os pregoes na tela de {0}, e completar os que faltam esta desligado nas configuracoes. Renko montado de candles e outro grafico: medido no WINFUT em janeiro de 2021 com tijolo 55, os candles de um minuto dao 15.100 tijolos e os ticks da bolsa dao 11.886.
219: series.title = Series e segmentos
220: series.series = Serie
233: series.column.from = Inicio
235: series.column.sessions = Pregoes
236: action.series = Series e segmentos...
238: replay.series = Serie
327: overlay.bb.average = Media
328: overlay.bb.showMiddle = Mostrar Media
332: overlay.bb.tab.middleAppearance = Aparencia Media
```

(`ruler.interval = Intervalo` está certo — apareceu na varredura por ser
homógrafo e foi descartado. São treze.)

**Problema:** a linha 195 é o caso que muda a severidade: não é um rótulo de
coluna, é o parágrafo inteiro de um `JOptionPane` — o único texto longo que o
programa mostra ao recusar um renko — e não tem um acento sequer em cinco
ocorrências que os pedem (`Não há`, `pregões`, `está`, `configurações`, `é`,
`gráfico`, `dão`). O mesmo arquivo escreve `Cópia de {0}`, `Máxima`,
`Configurações...` e `Só permitir os segmentos desta série` corretamente, o
que descarta problema de codificação.

**Consequência:** o texto de erro mais visível do programa parece escrito às
pressas, e `action.series` — um item de menu permanente — diz "Series" onde
todo o resto do menu está acentuado.

**Correção:** acentuar as treze. E confirmei que o arquivo é lido como UTF-8:
`ResourceBundle` no Java 9+ lê `.properties` em UTF-8 por padrão, e as
acentuadas existentes provam que o caminho funciona.

**Tentei refutar:** codificação? Os dois arquivos decodificam como UTF-8 sem
erro e carregam `ó`, `á`, `ç`, `õ` corretamente na mesma varredura. Escolha de
estilo? Não sobrevive a `Máxima` e `Nao ha` no mesmo arquivo.

---

### L4-9 — Duas caixas de preferências convivendo, e o cabeçalho de uma delas mente

**Onde:** `ui/shell/MainWindow.java:95` e `ui/shell/CollapsiblePane.java:56-57`
contra os sete usuários de `Settings`; a afirmação está em
`platform/Settings.java:76`

**Trecho:**

```java
    private static final Preferences PREFS = Preferences.userNodeForPackage(MainWindow.class);
```

```java
    private static final Preferences PREFS =
            Preferences.userNodeForPackage(CollapsiblePane.class);
```

contra o cabeçalho que o `Settings` grava dentro de `workspace.properties`:

```java
            "Endeavour Neo -- what the application was doing. Delete this to reset the layout.");
```

**Problema:** `Theme`, `ChartHolder`, `ChartLayouts`, `ChartPreferences`,
`OverlayLegend`, `RulerMode` e `ReplayPreferences` migraram para `Settings` —
a A7a-22 registra o rastro dessa migração no javadoc do `Theme`, que ainda
descreve o nó de `Preferences`. `MainWindow` e `CollapsiblePane` não migraram.
O que ficou no registro do Windows é justamente a *disposição*: largura e
altura da janela (`window.width`, `window.height`), as duas divisórias
(`divider.left`, `divider.bottom`) e o estado dobrado de cada painel
(`<chave>.folded`).

**Consequência:** a frase gravada dentro do arquivo — que o leitor lê, porque
é um arquivo de texto que o programa o convida a apagar — é falsa. Apagar
`workspace.properties` não restaura a disposição: a janela volta do tamanho
que estava, as divisórias voltam onde estavam, e os painéis voltam dobrados.
Some-se a isso que este é o mesmo par de classes cujos testes escrevem nas
preferências reais do usuário (A7b-21) — o que é consequência de estarem fora
do `Settings`, e não causa independente.

**Correção:** migrar os dois para `Settings.workspace()`, que é onde o resto da
disposição já mora. Enquanto não migrar, o cabeçalho da linha 76 tem de dizer
o que é verdade.

**Tentei refutar:** (a) `Preferences` ali é intencional, "geometria de janela é
da máquina e não do espaço de trabalho"? Nenhum comentário nos dois arquivos
diz isso, e `ChartHolder` grava `<chave>.width`/`.height` da janela flutuante
em `Settings.workspace()` (`ChartHolder.java:853-854`) — a mesma grandeza, no
outro lado da fronteira. (b) A A7a-22 ou a A7b-21 já cobrem? A A7a-22 é o
javadoc do `Theme`; a A7b-21 é o teste. Nenhuma das duas nomeia as duas
classes que ficaram para trás nem a afirmação falsa da linha 76.

---

### L4-10 — Três javadocs ainda citam o tamanho de tijolo que o repositório corrigiu

**Onde:** `ui/chart/PeriodCatalog.java:97`, `ui/chart/ChartCanvas.java:320`,
`ui/chart/ChartHolder.java:207`

**Trecho:** o javadoc que fica **três linhas acima** do método que produz o
título:

```java
         * <p>Renko carries both numbers, because neither alone is enough:
         * <code>11R</code> is what was typed and what the reader will type
         * again, and <code>55 pts</code> is what a brick actually measures. The
```

```java
     * label is for reading and the code is for rebuilding. Restoring a window
     * from "11R - 55 pts" would mean parsing prose.</p>
```

```java
            // "1m" while the title bar already said "11R - 55 pts". Two labels
```

contra o que o mesmo arquivo declara trinta linhas antes:

```java
     * (5 x 5) - 5 = 20 points and 11R is 50 -- not 25 and 55. This program
     * computed n x tick and was one tick too big at every size
```

e contra `Renko.java:283` (`11R | 50`) e `PeriodCatalogTest.java:168`
(`assertEquals(50.0, PeriodCatalog.brickOf(11), …)`).

**Problema:** a correção da fórmula do tijolo — o defeito que fazia toda
contagem discordar do Profit — foi aplicada ao código e a um javadoc, e
deixou três citações do número velho. A pior é a de `PeriodCatalog.java:97`,
porque documenta `title()`, o método que escreve o número na tela, e afirma
que 11R mede 55.

**Consequência:** um leitor que abrir `title()` para entender o formato lê que
11R são 55 pontos, ao lado de um `trim(renko.brick())` que devolve 50. É o
mesmo modo de falha que o próprio comentário de `PeriodCatalog:64` descreve:
"Nothing on screen would have shown it".

**Correção:** trocar 55 por 50 nas três. É uma edição de comentário e não
custa nada — e é o único vestígio, no repositório, do número que a correção
existia para eliminar.

**Tentei refutar:** os três são de outro instrumento, com outro tick? Não: os
três dizem `11R`, e `brickOf` é a única conversão de nome para pontos, com
`TICK = 5.0`.

---

## Achados BAIXA

### L4-11 — O separador de campos tem três grafias

`"  ·  "` (dois espaços de cada lado) em `ui/replay/ReplayFeed.java:210`,
`:211`, `:217`, `:219`; `ui/series/Segmentable.java:97`, `:98`;
`ui/shell/Navigator.java:258`, `:271`, `:293`; `ui/shell/MainWindow.java:680`.

`"  ·  "` — o mesmo caractere, escapado — em `ui/shell/MainWindow.java:430`:

```java
                + (segment == null ? "" : "  ·  " + segment.name());
```

`" · "` (um espaço) em `ui/chart/study/StudyPane.java:615`:

```java
        return scale == null || scale.isBlank() ? name : name + " · " + scale;
```

Doze ocorrências do que devia ser uma constante, três grafias, e a de um
espaço aparece na mesma tela que as de dois. `StudyPane:615` já está sob a
A5-24 por outro motivo (texto de interface montado em Java); a divergência de
espaçamento não está.

**Tentei refutar:** `"  ·  "` e `"  ·  "` são o mesmo texto? Sim, U+00B7
nas duas — a diferença é só de fonte de código, o que a torna mais fácil de
perder numa busca por `·`.

### L4-12 — `action.preferences.mnemonic = P` não sublinha nada, em nenhum dos dois idiomas

`messages.properties:21-22` — `action.preferences = Settings...` com
`.mnemonic = P`. `messages_pt_BR.properties:21-22` —
`action.preferences = Configurações...` com `.mnemonic = P`. Não há `P` em
"Settings..." nem em "Configurações...".

Consequência: `Alt+A`, `P` não abre as preferências e o item aparece sem
sublinhado, enquanto os outros dois itens do menu Arquivo têm o seu. É o
resíduo de quando o rótulo era "Preferences" — o único mnemônico dos 20 vivos
que não existe no rótulo (checagem por script sobre os dois bundles: um
resultado, este). Correção: `S` em inglês, `C` em português.

### L4-13 — Dois itens de menu sem mnemônico enquanto os outros treze têm

`action.series` e `action.replay` — os dois itens do menu Ferramentas
(`MainWindow.java:711-712`) — não têm `.mnemonic` em nenhum dos dois bundles.
`Messages.mnemonic` devolve 0 e o Swing não sublinha nada; não estoura, mas o
menu Ferramentas é o único do programa em que o teclado não alcança nada.

### L4-14 — `dd/MM/yyyy` fixado em quatro lugares, e anunciado ao leitor inglês

`ui/chart/SeriesSummary.java:49`, `ui/replay/DatePicker.java:60`,
`ui/series/SeriesWindow.java:74` gravam `DateTimeFormatter.ofPattern("dd/MM/yyyy")`;
`ui/chart/BarReadout.java:60` e `ui/chart/ChartCanvas.java:173` gravam
`"dd/MM/yyyy HH:mm"`; `ChartCanvas.java:645` e `ReplaySession.java:115` gravam
`"dd/MM"` e `"dd/MM HH:mm:ss"`.

O mesmo formato escrito sete vezes, e o bundle inglês chega a documentá-lo:
`messages.properties:250` — `replay.badDate = Use dd/mm/yyyy`. Não é defeito
hoje (o produto é do mini-índice), mas é a mesma decisão tomada em sete
lugares, e o L4-3 mostra que o programa oferece inglês.

### L4-15 — `TICK = 5.0` declarado duas vezes

`ui/chart/PeriodCatalog.java:51` e `ui/replay/ReplaySession.java:95`, mesmo
valor, mesmo conceito. O javadoc do primeiro diz onde ele deveria morar: *"It
is a property of the instrument and belongs with the base once bases carry
their own metadata"*. O segundo não sabe que o primeiro existe. Inofensivo
enquanto os dois forem 5,0.

### L4-16 — Os defaults das divisórias escritos duas vezes

`ui/shell/MainWindow.java:1021` e `:1023` (`260` e `height * 0.68` como
*default* de `PREFS.getInt`) contra `:1035-1036` (`260` e `getHeight() * 0.68`
literais dentro de `defaultLayout`). Dois valores, dois pares de sítios. Mexer
no "Restaurar disposição" sem mexer no *default* de leitura, ou o contrário,
dá duas disposições padrão diferentes.

### L4-17 — `Navigator.displayOf` é um delegate de uma linha com o javadoc do delegado copiado

`ui/shell/Navigator.java:296-308` repete, quase palavra por palavra, o javadoc
de `SeriesCatalog.displayOf` (`SeriesCatalog.java:298-312`) para depois só
chamá-lo. Dois textos que descrevem a mesma regra e que precisam mudar juntos.

### L4-18 — `Appearance.install` devolve texto literal em três linhas, não uma

A A7a-19 aponta `platform/Appearance.java:78`. As irmãs `:94`
(`return "system (FlatLaf absent)";`) e `:97` (`return "Swing default";`) têm o
mesmo defeito e não estão nomeadas. As três caem na barra de status e no
console via `Launcher.java:102` e `MainWindow.java:851`.

---

## As contas

| | |
|---|---|
| Linhas de chave no bundle | **332** por arquivo, idêntico nos dois |
| Chaves distintas | **331** (`replay.speed` duas vezes — A6-17 / A7a-21) |
| Chaves citadas como literal em algum `.java` (main ou test) | **264** |
| Chaves resolvidas por concatenação | **29** (de 7 famílias que somam 36 chaves; as outras 7 aparecem literalmente em teste) |
| Mnemônicos lidos por `Messages.mnemonic(base)` com a base viva | **20** |
| **Usadas no Java e ausentes do bundle** | **0** |
| **No bundle e órfãs** | **18** = 9 rótulos + 9 mnemônicos (L4-4) |

`264 + 29 + 20 + 18 = 331`. O balanço fecha.

### Chaves montadas por concatenação — não são erro, são o padrão desta casa

Sete famílias, e os valores possíveis de cada uma:

| prefixo | montado em | valores no bundle | quem pode aparecer |
|---|---|---|---|
| `navigator.group.` | `Messages.market()`, `Messages.java:104` | `win`, `btcusdt` | qualquer pasta de mercado no disco — cai no `orElse` com o nome da pasta |
| `navigator.role.` | `Navigator.java:293` | `source`, `export`, `search`, `test`, `merged` | o que `data.roles` disser; `orElse` devolve o próprio papel |
| `navigator.scale.` | `Navigator.java:221`, `ReplayFeed.java:219` | `1s`, `5s`, `1m`, `5m`, `15m`, `1h`, `1d` | qualquer sufixo de escala no nome do arquivo; `orElse` |
| `navigator.tickSource.` | `ReplayFeed.java:212`, `Segmentable.java:98`, `Navigator.java:360` | `metatrader`, `profit` | os dois valores de `TickSource`; **cobertura total** |
| `overlay.ma.kind.` | `Forms.named`, 3 diálogos | `ARITHMETIC`, `EXPONENTIAL`, `WEIGHTED` | `MovingAverage.Kind`; **cobertura total** |
| `overlay.ma.source.` | `Forms.named`, 2 diálogos | `CLOSE`, `OPEN`, `HIGH`, `LOW`, `MEDIAN`, `TYPICAL` | `MovingAverage.Source`; **cobertura total** |
| `study.rsi.smoothing.` | `RsiDialog.java:91` | `CLASSIC`, `SIMPLE` | `RelativeStrength.Smoothing`; **cobertura total** |
| `settings.language.` | `GeneralPage.java:88` | `system`, `pt-BR`, `en` | `Language.values()`; **cobertura total** |
| `theme.` (+ `.hint`) | `AppearancePage.java:147` | `light`, `dark`, `night` | `Theme.values()`; **cobertura total** |

As quatro famílias que usam `Messages.get` (sem `orElse`) — `overlay.ma.kind.`,
`overlay.ma.source.`, `study.rsi.smoothing.`, `settings.language.`, `theme.` —
foram confrontadas com os `enum` que as alimentam, constante por constante.
**Nenhuma pode produzir `!chave!` na tela.** As que usam `orElse` são
alimentadas por nome de arquivo e por isso não podem, por construção.

---

## Texto visível fora do bundle

| arquivo:linha | o literal | o que o leitor vê |
|---|---|---|
| `ui/chart/PeriodCatalog.java:228` | `"1 dia"` | linha "1d — 1 dia" na lista de períodos |
| `ui/chart/PeriodCatalog.java:232` | `"1 semana"` | linha "1w — 1 semana" |
| `ui/chart/PeriodCatalog.java:236` | `"1 mês"` | linha "1M — 1 mês" |
| `ui/chart/PeriodCatalog.java:240` | `"1 minuto"` | linha "1m — 1 minuto" |
| `ui/chart/PeriodCatalog.java:246` | `" hora"` / `" horas"` | "1h — 1 hora (60 minutos)" |
| `ui/chart/PeriodCatalog.java:247` | `" minutos)"` | o parêntese da linha acima |
| `ui/chart/PeriodCatalog.java:250` | `" minutos"` | "15m — 15 minutos" |
| `ui/chart/PeriodCatalog.java:104` | `" - "` e `" pts"` | título do gráfico: "11R - 50 pts" |
| `ui/chart/PeriodCatalog.java:148` e `:195` | `"R (renko "` e `" pts)"` | "11R (renko 50 pts)" na lista |
| `ui/chart/study/rsi/RsiDialog.java:84` | `" ["` e `"]"` | barra de título: "IFR (RSI) [14]" |
| `ui/chart/study/stochastic/StochasticDialog.java:108` | `" ["` e `"]"` | "Estocástico Lento [8]" |
| `ui/chart/MovingAverageDialog.java:118` | `" ["` e `"]"` | "Indicadores > Média móvel [9]" |
| `ui/chart/BollingerBandsDialog.java:142` | `" ["` e `"]"` | "Indicadores > Bandas de Bollinger [20]" |
| `ui/shell/MainWindow.java:430` | `"  ·  "` | separador entre a série e o segmento no título |
| `ui/chart/study/StudyPane.java:615` | `" · "` | separador do cabeçalho do painel (um espaço, não dois) |
| `platform/Appearance.java:94` e `:97` | `"system (FlatLaf absent)"`, `"Swing default"` | barra de status e console no arranque |

**Já reportados, listados aqui só para o mapa ficar completo:**
`domain/market/Renko.java:196` (`" renko sem calda"`, A2-14),
`ui/replay/ReplaySession.java:556` (`" a "`, A6-18),
`ui/shell/Navigator.java:273` (`"  a  "`, A7b-23),
`ui/chart/study/StudyPane.java:601-603` (A5-24),
`platform/Appearance.java:78` (A7a-19).

---

## Já visto pelas áreas

| o que encontrei | id |
|---|---|
| `replay.speed` declarada duas vezes nos dois bundles | A6-17, A7a-21 |
| `" a "` cravado no título do replay | A6-18 |
| `"  a  "` cravado no rótulo de segmento do navegador | A7b-23 |
| `Renko.label()` devolvendo português de dentro de `domain/` | A2-14 |
| Texto de interface montado em Java no `StudyPane` | A5-24 |
| `Appearance.install` devolvendo inglês literal (linha 78 apenas) | A7a-19 |
| `ChartHolder.item(String, Runnable)` morto — a causa de 9 das 18 órfãs | BAIXA sem id, `ChartHolder.java:734` |
| `messages_pt_BR` sem acento nas linhas 203, 208, 210, 211 | BAIXA sem id — as outras 9 estão em L4-8 |
| `summary.years/months/days` só no plural | BAIXA sem id |
| Cópia de imports do `LinePen` sobrando no `StochasticDialog` | A5-16 — a outra ponta da mesma extração está em L4-5 |
| `Theme` documentando um nó de `Preferences` que sumiu | A7a-22 — o contexto da migração incompleta está em L4-9 |
| Testes escrevendo nas preferências reais | A7b-21 — consequência do mesmo L4-9 |
| `ui/chart/Sessions` duplicando o nome de `domain/market/Sessions` | A4-12 — conferi de novo que não divergiram |
| Formatação dependente do locale padrão (um sítio) | A7b-30 — a causa comum dos nove está em L4-3 |
| `Messages.mnemonic` devolvendo ponto de código como código de tecla | A7a-30 |
| Bundle estático não-volátil trocado em runtime | A7a-18 |

---

## O que está LIMPO

**Nenhum construtor de componente carrega texto.** Os 11 padrões
(`setText("`, `setToolTipText("`, `setTitle("`, `new JLabel("`, `new JButton("`,
`new JMenu("`, `new JMenuItem("`, `new JCheckBox("`, `new JRadioButton("`,
`addTab("`, `createTitledBorder("`) devolveram 12 ocorrências em todo o
`src/main`, e as 12 são `" "`, `"0"`, `"+"`, `"▾"`, `"--:--:--"` ou HTML de
embrulho. Nenhuma palavra. Isso é notável para uma base Swing deste tamanho.

**Os cinco diálogos modais passam pelo bundle**, título e corpo:
`ChartCanvas.java:895` (`chart.renkoNeedsTicks`), `LayoutBar.java:401`, `:444`
(`layout.namePrompt`), `:476` (`layout.confirmRemove` + `layout.remove`),
`SeriesWindow.java:351` (`series.removeAsk` + `series.removeTitle`).

**Zero chaves usadas e ausentes.** Extraí todo literal de
`Messages.get/orElse/mnemonic`, mais todo literal pontilhado do repositório
(306 cadeias), e comparei com as 331 chaves. As 41 cadeias pontilhadas que não
são chaves de bundle foram inspecionadas uma a uma: são nomes de classe do
FlatLaf, propriedades do sistema (`user.home`, `os.name`), chaves de
`Settings`/workspace (`chart.open.0.series`, `divider.bottom`, `replay.feed`,
`chart.ruler`) e falsificações de teste (`study.doesNotExist`,
`overlay.thatWentAway`, `study.macd`).

**Aridade de `MessageFormat`: perfeita nos dois sentidos.** 37 chaves têm
`{n}`; nenhuma delas é chamada por `Messages.get(chave)` sem argumento (o que
imprimiria `{0}` na tela). 31 chaves são chamadas com argumento; todas as 31
têm `{n}`. As 6 restantes são `ruler.day/days/hour/hours/minute/minutes`,
chamadas por chave variável em `Measurement.java:140`. E os placeholders são
idênticos entre os dois bundles em todas as 37.

**As três aspas simples do bundle são inofensivas** — e verifiquei em vez de
supor. `summary.source.ticks` (`the exchange's ticks`), `series.segmentsOnly`
(`Only allow this series' segments`) e `segment.range` (`the new segment's
range`) só perderiam a aspa se passassem por `MessageFormat`. Os três sítios
que os lêem (`SeriesSummary.java:75`, `SeriesWindow.java:96`,
`SegmentDialog.java:278`) usam a sobrecarga sem argumentos, que devolve o
`getString` cru.

**Nenhum valor de bundle vazio** nos dois arquivos.

**Os 20 mnemônicos vivos existem no rótulo que sublinham**, nos dois idiomas —
com uma exceção, o L4-12. E não há colisão de letra dentro de nenhum menu
vivo: barra (`A E X J F` em pt / `F V T R W` em en), Arquivo (`N P S` / `N P X`),
Exibir (`L R T Z O` / `C R A B G`), Executar (`R C` / `S C`), Ferramentas
(nenhum — L4-13). `settings.ruler.mnemonic = M` e
`settings.chart.periodLine.mnemonic = M` chegaram a parecer colisão, mas estão
em páginas diferentes do diálogo de preferências (`GeneralPage` e `ChartPage`),
onde só uma está visível por vez.

**O console é interface neste projeto, e o código o trata como tal.** Não
supus: os treze `console.write` e `status.say` do `src/main` passam todos por
`Messages.get`, e o bundle mantém uma família `console.*` própria
(`console.appearance`, `console.java`, `console.seriesLoaded`,
`console.seriesFailed`, `console.seriesMissing`, `console.locked`,
`console.opened`, `console.layoutReset`) e uma `status.*`. `Console.java` não
tem um literal de texto. A única exceção é o L4-18 / A7a-19, que é
precisamente o defeito de alguém ter escapado da regra.

**A queda de idioma funciona.** `Messages.setLocale` com
`getNoFallbackControl` desliga o *fallback pelo locale padrão* e não o
fallback para o bundle base — então inglês numa máquina brasileira chega em
`messages.properties`, e um idioma que não existe (um francês) também. O
`LanguageTest.englishIsReallyEnglish` cerca exatamente isso. É o único ponto
desta lente que está coberto por teste.

**Nenhuma constante de regra de negócio duplicada com valores divergentes.**
214 declarações `static final` de primitivo, 18 nomes repetidos entre arquivos,
todos os 18 lidos: um só divergiu (L4-6) e é geometria de tela. `TICK`,
`VERSION`, `HEADER_BYTES`, `ALPHA`, `BUTTON`, `SLIP`, `BUFFER` batem;
`PADDING`, `GAP`, `GRIP`, `OFFSET`, `KEY`, `CHUNK`, `RECORD_BYTES`, `PERIOD`,
`HEIGHT`, `SIDE`, `TICKS` são o mesmo nome para coisas diferentes, e a leitura
confirmou que são mesmo diferentes.

**`Sessions` continua sem divergir.** A A4-12 concluiu que os dois são dívida
de camada e não defeito; reconferi as constantes e a assinatura — `ui/chart/Sessions`
não tem constante nenhuma e as duas classes não compartilham um só método.

**`SeriesCatalog.displayOf` é o único lugar que nomeia uma série na tela.**
`Navigator.displayOf` delega (L4-17), e `Messages.market()` centraliza a
tradução do mercado com o javadoc explicando por quê — *"Here rather than at
each of the four places that show it, so they cannot drift apart"*. Este é o
padrão que o resto dos achados desta lente pede.
