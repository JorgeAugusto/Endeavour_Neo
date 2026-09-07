# L4 — texto, idioma e consistência

Lente transversal da auditoria II. **Uma pergunta em todo o código**, não uma
área: onde a cadeia de tela nasce, em que língua, e quantas respostas diferentes
o programa dá para a mesma pergunta de texto.

## O que foi lido, e como

Partiu de `grep`, com leitura de ±40 linhas em volta de cada ocorrência. Nada foi
relido por inteiro. Os pontos examinados:

| o que | quantos | como |
|---|---|---|
| chamadas a `Messages.get/orElse/mnemonic/market` | 330, em 36 arquivos | `grep -c` por arquivo |
| linhas dos dois bundles | 383 + 383 | leitura integral dos dois `.properties` |
| chaves de bundle contra o fonte | 335 chaves × todo `src/main/java` | script PowerShell (casamento literal `"chave"`) |
| chaves literais usadas no código | 226 | regex sobre `Messages\.(get|orElse|mnemonic)\("…"` |
| sítios de formatação (`String.format`, `DecimalFormat`, `DateTimeFormatter.ofPattern`, `NumberFormat`) | 60 | `grep -n` |
| `toUpperCase`/`toLowerCase`/`Locale.*`/`equalsIgnoreCase` | 28 | `grep -n` |
| literais em construtores de componente (`new JLabel("…")` etc.) | 17 | `grep -n` |
| `setText`/`setTitle`/`setToolTipText`/`setMnemonic` sem `Messages.` | 39 | script PowerShell |
| `JOptionPane` (message/confirm/input) | 8 | `grep -n -C 5` |
| literais com cara de frase em `src/main/java` | 54 | script PowerShell |
| mnemónico × letra do rótulo | 41 pares `.mnemonic` × 2 bundles | script PowerShell |

Arquivos abertos em trecho: `Messages.java` (integral, 160), `Language.java`
(integral, 108), `Theme.java` 40-118, `Appearance.java` 55-98, `ChartCanvas.java`
148-192 e 970-986, `ChartPage.java` 106-130, `MainWindow.java` 418-442, 486-540,
560-609, 900-1015, 1076-1092, 1152-1196, `ChartLayouts.java` 56-168,
`Navigator.java` 49-296, `Measurement.java` 95-142, `SeriesSummary.java` 82-118,
`PeriodCatalog.java` 114-248, `ReplaySession.java` 118-138 e 698-731,
`ReplayFeed.java` 225-254, `Segmentable.java` 105-125, `StatusBar.java` 150-199,
`LayoutBar.java` 396-485, `SeriesWindow.java` 378-393, `Aggressor.java` 25-93,
`BundleKeysTest.java` (integral, 115).

**Aviso de linha:** vários arquivos sob `ui/` estavam a ser editados durante a
leitura. Todo achado carrega o **trecho literal**; se o número não bater, é por
isso — procure o trecho.

**Deixado para as outras lentes:** concorrência e EDT (L1), fuso e leitura do
futuro (L2), recurso que vaza (L3). Os achados abaixo não repetem nada do
`00-achados.md`; onde tocam um achado já reportado, isso está dito no próprio
achado.

---

## ALTA

Nenhum. Nenhum defeito de texto encontrado produz número de mercado errado,
perde dado, trava ou lê o futuro. O mais grave (L4-2) troca a língua da
aplicação inteira e tem contorno por reinício.

---

## MÉDIA

### L4-1. O rótulo "Barras que o gráfico carrega" aparece na tela com um `&` na frente, e o mnemónico não existe

`src/main/resources/messages.properties:277`
`src/main/resources/messages_pt_BR.properties:277`
`src/main/java/br/com/jorge/reis/endeavourneo/ui/settings/ChartPage.java:112,115`

```
settings.chart.window = &Bars a chart loads
settings.chart.window = &Barras que o gráfico carrega
```

```java
JLabel label = new JLabel(Messages.get("settings.chart.window"));

label.setLabelFor(window);
label.setDisplayedMnemonic(Messages.mnemonic("settings.chart.window"));
```

**Problema** — o `&` é a convenção de mnemónico de outro toolkit. Swing não a
conhece: `JLabel` desenha o texto como veio. E a convenção desta casa, escrita no
cabeçalho do próprio bundle e no javadoc de `Messages`, é uma chave irmã
`<chave>.mnemonic` — que **não existe** para esta chave em nenhum dos dois
arquivos. É a única chave dos dois bundles usada com `Messages.mnemonic()` e sem
irmã: o script que cruzou as 226 chaves literais contra as 335 do bundle devolveu
`settings.chart.window.mnemonic` como a única "usada no código, ausente do
bundle".

**Consequência** — a página *Gráfico* das preferências mostra literalmente
`&Barras que o gráfico carrega`. E `Messages.mnemonic` devolve `!settings.chart.
window.mnemonic!`, que começa por `!`, logo devolve 0 — `setDisplayedMnemonic(0)`
não sublinha letra nenhuma. O `&` foi escrito para pedir um sublinhado e produziu
o oposto: um caractere a mais e nenhum atalho.

**Correção** — tirar o `&` das duas linhas e acrescentar
`settings.chart.window.mnemonic = B` nas duas (a letra serve nas duas línguas).

**Tentei refutar assim** — procurei um `replace("&", "")` ou um utilitário de
mnemónico que interpretasse o `&`: `grep` por `setDisplayedMnemonic`,
`setDisplayedMnemonicIndex` e `'&'` em `src/main` devolve só esta linha e as de
`MainWindow.menu/item`, nenhuma delas tocando no `&`. Conferi que
`Messages.mnemonic` trata `!` como ausência (`letter.startsWith("!")` →
`return 0`), portanto nem sequer estoura. Não caiu.

---

### L4-2. Voltar para "Seguir o sistema" não devolve o idioma da máquina — e o comentário diz que devolve

`src/main/java/br/com/jorge/reis/endeavourneo/platform/Language.java:39,61-63,92-107`
`src/main/java/br/com/jorge/reis/endeavourneo/ui/shell/MainWindow.java:1043`

```java
    /** Whatever the operating system says, which is right far more often than not. */
    SYSTEM("system", null),
...
    public Locale locale() {
        return locale == null ? Locale.getDefault() : locale;
    }
...
    public static void install() {
        // Always, including SYSTEM -- whose locale() is the machine's. Doing it
        // only for the other two meant switching BACK to "follow the system"
        // left the previous choice in place until the next launch.
        Locale chosen = remembered().locale();

        Messages.setLocale(chosen);
        ...
        Locale.setDefault(chosen);
    }
```

**Problema** — `install()` corre mais de uma vez por execução: `Launcher:139` no
arranque e `MainWindow:1043` a cada troca de idioma (`openPreferences` →
`relaunch()` quando `Language.remembered() != before`). A primeira chamada com
PORTUGUESE executa `Locale.setDefault(pt-BR)`, que **apaga** o locale da máquina
do único sítio onde ele estava guardado. Na chamada seguinte, com SYSTEM,
`SYSTEM.locale()` lê `Locale.getDefault()` — que agora é `pt-BR`, não a máquina.
O comentário descreve exatamente este cenário como o defeito que a linha
"Always, including SYSTEM" corrigiu; ela não o corrige, porque o valor que SYSTEM
consulta é o que a própria linha destruiu.

**Consequência** — numa máquina em inglês: escolher *Português (Brasil)*, depois
*Seguir o sistema*, deixa a aplicação em português, para sempre, até fechar. A
janela é reconstruída (o utilizador vê que algo aconteceu) e volta na língua
errada, o que é pior do que não fazer nada. O javadoc da classe promete que
SYSTEM é "whatever the operating system says".

**Correção** — guardar o locale da máquina uma vez, num `static final Locale
MACHINE = Locale.getDefault()` inicializado antes do primeiro `setDefault`, e
`SYSTEM.locale()` devolver `MACHINE`. E corrigir o comentário, que descreve a
correção que não aconteceu.

**Tentei refutar assim** — procurei outra guarda do locale original:
`grep -n "Locale.setDefault\|Locale.getDefault"` em todo `src/main` devolve 12
linhas, e a única escrita é `Language:106`; nada mais guarda o valor de partida.
Verifiquei que `install()` é mesmo chamado duas vezes no mesmo JVM
(`Launcher:139` e `MainWindow:1043`, este dentro de `relaunch()` chamado por
`openPreferences:1090`). Confirmei que `Locale.setDefault(Locale)` escreve as
duas categorias (`DISPLAY` e `FORMAT`), portanto não sobra cópia. Não caiu.

---

### L4-3. O mês do eixo de tempo fica na língua antiga depois de trocar de idioma — e o comentário do `Language` nomeia justamente este caso como resolvido

`src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/ChartCanvas.java:161-163`
`src/main/java/br/com/jorge/reis/endeavourneo/platform/Language.java:100-106`

```java
    /** The band below it, then. */
    private static final DateTimeFormatter MONTH =
            DateTimeFormatter.ofPattern("MMM/yy", java.util.Locale.getDefault());
```

```java
        // The bundle is only half of it. Ten places read Locale.getDefault()
        // directly -- the calendar month, the MMM/yy of the time axis, the
        // decimal separator in seven readouts -- and Swing picks the words on
        // the JOptionPane buttons from the JVM's locale, not from the bundle.
```

**Problema** — `MONTH` é `static final`: o `Locale.getDefault()` é lido **uma vez,
na inicialização da classe**, e nunca mais. No arranque isso está certo, porque
`Launcher:139` chama `Language.install()` antes de construir a janela. Mas na
troca de idioma em tempo de execução — o caminho para o qual o comentário foi
escrito, e que o próprio bundle promete em `settings.language.hint` ("Aplicado na
hora: a janela é reconstruída") — `ChartCanvas` já está carregado, e
`Locale.setDefault` não volta a tocar num formatador já construído.

Os outros nove sítios que o comentário lista **não** têm este problema: todos
chamam `DecimalFormatSymbols.getInstance(Locale.getDefault())` ou
`getDisplayName(..., Locale.getDefault())` **dentro de um método**, a cada uso
(`BarReadout:256`, `RulerReadout:157`, `StudyPane:886`, `OverlayLegend:327`,
`ChartCanvas:2637`, `Sessions:118`, `DatePicker:206,219`). O `MONTH` é o único
capturado num campo estático — e é o único que o comentário nomeia pelo padrão.

**Consequência** — depois de trocar para inglês, a faixa de meses do gráfico
diário/semanal continua a dizer `set/26`, `out/26`; depois de trocar para
português, continua a dizer `Sep/26`. O comentário do `Language` afirma que a
linha `Locale.setDefault` cobre isto, o que faz com que ninguém volte a olhar.

**Correção** — construir o formatador no uso, ou guardá-lo num campo recriado por
`Language.install()`. O padrão que o resto do arquivo já usa (formatar no método)
resolve sem custo mensurável: é uma etiqueta por faixa de eixo, não por barra.

**Tentei refutar assim** — procurei um reinício de classe ou uma recriação do
formatador: `grep` por `MONTH` em `ChartCanvas` dá só a declaração e os usos de
leitura; não há setter nem `install`. Procurei se `relaunch()` recarrega classes —
`MainWindow:1040-1049` faz `dispose()` e `new MainWindow(...)` no mesmo
classloader, portanto `ChartCanvas` não é reinicializado. Confirmei que os outros
seis sítios de `Locale.getDefault()` estão dentro de métodos e por isso não
sofrem do mesmo. Não caiu.

---

### L4-4. Toda data da tela está fixada em `dd/MM`, enquanto todo número segue a língua escolhida

`src/main/java/br/com/jorge/reis/endeavourneo/ui/series/SeriesWindow.java:74`
`src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/ChartCanvas.java:156,173,183`
`src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/SeriesSummary.java:49`
`src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/BarReadout.java:60`
`src/main/java/br/com/jorge/reis/endeavourneo/ui/replay/DatePicker.java:67`
`src/main/java/br/com/jorge/reis/endeavourneo/ui/replay/ReplaySession.java:130,729`
`src/main/resources/messages.properties:249`

```java
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
```

```java
    static final DateTimeFormatter TYPED = DateTimeFormatter.ofPattern("dd/MM/yyyy");
```

```
replay.badDate = Use dd/mm/yyyy
```

**Problema** — nove formatadores de data em sete arquivos, todos com dia antes do
mês, nenhum recebendo `Locale`. Ao lado deles, **os números seguem a língua**:
`DecimalFormatSymbols.getInstance(Locale.getDefault())` em seis readouts, e
`String.format("%,d", …)` e `MessageFormat` a usarem o locale por omissão. O
programa dá duas respostas para "esta tela é localizada ou não": sim para o
separador decimal, não para a ordem da data.

Pior no ponto de entrada: `DatePicker.TYPED` é o formato que o utilizador tem de
**digitar**, e a mensagem de erro do bundle **inglês** manda escrever
`dd/mm/yyyy`. Um leitor de inglês que escreva `09/07/2026` a pensar em 7 de
setembro obtém 9 de julho, e o campo aceita — não há erro para mostrar.

**Consequência** — em inglês, `07/09/2026` na régua, no rodapé, no resumo da série
e no eixo é lido como 9 de julho. Nenhuma tela diz qual é a ordem, exceto uma
mensagem de erro que só aparece quando a data é impossível.

**Correção** — decisão de projeto, não conserto mecânico. Ou (a) assumir a ordem
brasileira em toda a aplicação, e então tirar `Locale.getDefault()` dos
formatadores de número para que a tela seja coerente; ou (b) derivar as datas do
locale (`DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)`) e mover
`replay.badDate` para um texto que mostre o exemplo formatado em vez de o
escrever à mão. O que não se sustenta é o meio-termo atual.

**Tentei refutar assim** — verifiquei se `Locale.setDefault` afeta um padrão
literal: não afeta; `ofPattern("dd/MM/yyyy")` sem locale usa o locale só para os
nomes textuais (`MMM`, `EEE`), nunca para a ORDEM dos campos, que o padrão fixa.
Procurei um formatador localizado em qualquer sítio (`grep` por
`ofLocalizedDate`, `FormatStyle`, `DateFormat.getDateInstance`): zero
ocorrências. Confirmei que os seis sítios de número passam mesmo
`Locale.getDefault()`. Não caiu.

---

### L4-5. `Appearance.install` devolve texto de tela escrito em inglês no código, e `Theme.getLabel()` é uma segunda resposta para "como se chama este tema"

`src/main/java/br/com/jorge/reis/endeavourneo/platform/Appearance.java:78,84,94,97`
`src/main/java/br/com/jorge/reis/endeavourneo/platform/Theme.java:43-53,88-90`
`src/main/java/br/com/jorge/reis/endeavourneo/ui/shell/MainWindow.java:1085-1086`
`src/main/java/br/com/jorge/reis/endeavourneo/ui/settings/AppearancePage.java:147`

```java
            return "FlatLaf dark (" + theme.getLabel() + " palette missing)";
...
            return "FlatLaf " + theme.getLabel();
...
            return "system (FlatLaf absent)";
        }

        return "Swing default";
```

```java
                    console.write(Messages.get("console.appearance", installed));
                    status.say(installed);
```

**Problema** — o javadoc de `install` diz, sem rodeios, `@return what was actually
installed, so the console and status bar can say`. É texto de tela, escrito em
inglês dentro de `platform`, e vai para dois sítios que o leitor vê: a linha do
console e a **barra de estado** (esta sem sequer passar por `Messages`). A
convenção da casa é que texto de interface nunca sai do código.

E há a segunda resposta: o nome do tema. A página de preferências constrói
`"theme." + theme.name().toLowerCase(ROOT)` (`AppearancePage:147`) e mostra
*Claro / Escuro / Noturno* vindos do bundle; o console e a barra de estado mostram
`theme.getLabel()`, que é `"light"/"dark"/"night"` — minúsculo, inglês, e ao mesmo
tempo a **chave de persistência** (`Theme.remember` grava `label`). Duas regras
para o mesmo nome, e uma delas não pode mudar sem partir o arquivo de
configurações.

**Consequência** — com a aplicação em português, escolher *Noturno* escreve na
barra de estado `FlatLaf night` e no console `aparência: FlatLaf night`. Sem o
FlatLaf no classpath, a barra diz `system (FlatLaf absent)` a um leitor que nunca
viu uma palavra em inglês nesta janela.

**Correção** — `install` devolver um enum ou uma chave (`appearance.flatlaf`,
`appearance.flatlaf.paletteMissing`, `appearance.system`, `appearance.swing`) e o
chamador resolver com `Messages.get`, passando o nome do tema já traduzido
(`Messages.get("theme." + theme.name().toLowerCase(ROOT))`). O `label` fica a ser
só chave de persistência, que é o papel que não pode perder.

**Tentei refutar assim** — verifiquei se o retorno é só de diagnóstico e não chega
à tela: `MainWindow:1085-1086` escreve-o no console E na barra de estado, e
`Launcher:148` no console no arranque. Procurei um `Messages` dentro de
`Appearance`: não há import de `Messages` no arquivo. Confirmei que
`AppearancePage` usa mesmo o bundle para os botões de opção, logo os dois nomes
convivem na mesma janela. Não caiu.

---

### L4-6. O nome do layout padrão é texto traduzido que vai para o disco — e congela na língua em que o gráfico nasceu

`src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/ChartLayouts.java:164,93,117-119`
`src/main/resources/messages.properties:177` / `messages_pt_BR.properties:177`

```java
        return new ChartLayout(Messages.get("layout.default"),
                List.of(new ChartLayout.Entry("overlay.movingAverage", List.of(17), true),
```

```java
            PREFS.put("layout." + i + ".name", layout.name());
...
    public static void remember(String chartKey, String layoutName) {
        PREFS.put("selected." + chartKey, layoutName);
    }
```

**Problema** — `layout.default` é uma cadeia de interface (`Padrão` / `Default`).
Ela vira o **nome** do layout, e o nome é persistido duas vezes: como
`layout.N.name` e, por valor, como `selected.<chartKey>`. A partir do primeiro
`save`, a língua em que o layout foi criado fica gravada no arquivo.

Repare no contraste dentro da mesma linha: `Entry("overlay.movingAverage", …)`
guarda a **chave** do bundle, não o texto — o arquivo já sabe distinguir chave de
texto. Só o nome do layout atravessou para o lado errado.

**Consequência** — o leitor troca para inglês; a barra de layouts continua a
mostrar a aba `Padrão`, sozinha entre menus e diálogos em inglês, e continuará a
mostrá-la para sempre. E se o layout ainda não tinha sido gravado (`count == 0`,
`all()` devolve `List.of(defaultLayout())` construído na hora), o `selected.
<chartKey> = "Padrão"` guardado antes deixa de casar com o layout agora chamado
`Default`: o gráfico volta no primeiro layout em vez de no que estava.

**Correção** — guardar o layout padrão sob um nome estável (`"default"`, ou uma
marca `isDefault`) e traduzir só na hora de desenhar a aba. Nomes que o leitor
escreve continuam a ser texto livre, como devem.

**Tentei refutar assim** — procurei uma normalização na leitura: `all()` (linhas
58-78) lê `layout.N.name` cru, sem comparar com o bundle. Procurei se
`selectedFor` faz correspondência tolerante: `PREFS.get("selected." + chartKey,
null)` devolve a cadeia e é comparada por nome noutro sítio. Confirmei que
`layout.default` tem valores diferentes nos dois bundles (`Default` / `Padrão`).
Não caiu.

---

### L4-7. `Segmentable.labelOf` e `ReplayFeed.label` montam a mesma etiqueta de sessão de ticks, com as mesmas chaves e o mesmo separador, em dois arquivos

`src/main/java/br/com/jorge/reis/endeavourneo/ui/series/Segmentable.java:115-125`
`src/main/java/br/com/jorge/reis/endeavourneo/ui/replay/ReplayFeed.java:239-245`

```java
    /** @return how the key is written on screen */
    public static String labelOf(String key) {
        if (!isTicks(key)) {
            return key;
        }

        String source = key.substring(key.indexOf(TICKS) + TICKS.length());

        return Messages.market(instrumentOf(key)) + "  ·  " + Messages.get("navigator.ticks")
                + "  ·  " + Messages.orElse("navigator.tickSource." + source, source);
    }
```

```java
        if (isTicks()) {
            return market + "  ·  " + Messages.get("navigator.ticks")
                    + "  ·  " + Messages.orElse(
                            "navigator.tickSource." + source.key(), source.key());
        }
```

**Problema** — três partes, três chaves, dois separadores, duas vezes. Nada as
prende. `Segmentable.labelOf` é `public static` e faz exatamente o que
`ReplayFeed` refaz à mão. O javadoc de `ReplayFeed.label` até declara a regra —
"Market, then scale, then — for ticks — which export, **which is the order the
tree already reads in**" — reconhecendo que está a copiar a regra de outro sítio
em vez de a chamar.

**Consequência** — acrescentar uma parte à etiqueta (a data, a contagem de
pregões) ou mudar o separador conserta um dos dois e deixa o outro. O leitor vê a
mesma sessão escrita de duas maneiras, uma na árvore e outra no transporte, sem
que nada falhe nem nenhum teste apite.

**Correção** — `ReplayFeed.label()` chamar `Segmentable.labelOf` para o ramo de
ticks (ou ambos chamarem um único formatador em `ui/series`). O ramo de séries de
`ReplayFeed` tem forma própria e fica onde está.

**Tentei refutar assim** — verifiquei se os argumentos diferem ao ponto de
impedir a chamada: `ReplayFeed` tem `instrument` e `source` separados e
`Segmentable.labelOf` recebe a chave inteira — mas `ReplayFeed` também sabe
montar a chave (`isTicks()` existe do mesmo lado), e o resultado é caractere a
caractere o mesmo. Comparei os dois trechos: mesmas três chaves, mesmo `"  ·  "`
de dois espaços de cada lado, mesma escolha `get` para `navigator.ticks` e
`orElse` para a fonte. Não caiu.

---

### L4-8. Só a régua distingue singular de plural; o resto da aplicação escreve "1 anos", "1 sessions", "1 tasks"

`src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/Measurement.java:118-126,139-141`
`src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/SeriesSummary.java:95-105`
`src/main/resources/messages.properties:49,94,136,213-215,221`

```java
        if (days > 0) {
            parts.add(unit(days, "ruler.day", "ruler.days"));
        }
...
    private static String unit(long amount, String singular, String plural) {
        return Messages.get(amount == 1 ? singular : plural, amount);
    }
```

```java
        if (span.getYears() > 0) {
            parts.add(Messages.get("summary.years", span.getYears()));
        }
...
        if (span.getDays() > 0 || parts.isEmpty()) {
            parts.add(Messages.get("summary.days", span.getDays()));
        }
```

**Problema** — `Measurement` tem seis chaves e uma função `unit` só para acertar o
plural. `SeriesSummary`, que faz exatamente a mesma coisa — uma duração em
palavras, montada de partes não nulas, juntas por vírgula — tem três chaves e
nenhuma. Duas respostas para a mesma pergunta, a três arquivos de distância, com
o javadoc de `spanBetween` a explicar o cuidado com as partes zero e a ignorar o
das partes iguais a um.

O mesmo em todas as contagens do bundle: `summary.years/months/days`,
`navigator.tickSessions = {0}: {1} sessions, {2} to {3}`, `series.about = {0} to
{1} — {2} sessions`, `status.jobs = {0} tasks`, `status.tiled = {0} windows
arranged`, `console.seriesLoaded = {0}: {1} bars read from disk`.

**Consequência** — uma série que cobre um ano e um dia é descrita como
`1 anos, 1 dias`. Uma exportação de um pregão aparece na árvore como
`WINFUT: 1 sessions, 05/09 to 05/09`. Uma tarefa a correr põe `1 tasks` na barra
de estado. Aparece sempre no caso mais pequeno, que é o caso de teste, que é o
que o autor vê mais vezes.

**Correção** — `MessageFormat` já resolve isto sem código novo, com `choice`:
`summary.days = {0,choice,1#{0} dia|1<{0} dias}`. Duas alternativas: aplicar
`choice` às oito chaves de contagem, ou estender o par singular/plural de
`Measurement` a elas. A primeira não toca em nenhum `.java`.

**Tentei refutar assim** — verifiquei se o valor 1 é inalcançável em
`spanBetween`: `span.getDays() > 0` deixa passar 1, e `Period.between` de datas a
um dia de distância devolve exatamente `P1D`. Verifiquei se `MessageFormat` já
trata plural sozinho: não trata; sem `choice` ou `plural` no padrão, `{0}` é só o
número. Conferi que `ruler.*` tem mesmo as duas formas nos dois bundles (linhas
157-162) e `summary.*` só uma (213-215). Não caiu.

---

### L4-9. Nove chaves definidas nos dois bundles e usadas em lugar nenhum — três delas prometem um menu inteiro que não existe

`src/main/resources/messages.properties:108-115,141-142,146-147,239,241,75`
(as mesmas linhas em `messages_pt_BR.properties`)

```
menu.chart = Chart
menu.chart.mnemonic = C
action.close = Close
action.close.mnemonic = C
...
view.documents = Documents
view.chartsOpenInWindows = Charts open in their own windows, so they can be spread across monitors.
...
chart.style.candleHollow = Candles (hollow up)
chart.style.candleHollow.mnemonic = H
...
chart.measureMode = Measure mode (Ctrl)
chart.measureMode.mnemonic = M
...
replay.ticks = Ticks
replay.date = Date
...
settings.appearance.theme = Theme
```

**Problema** — cruzei as 335 chaves do bundle contra o texto literal de todo
`src/main/java`. Descontando as que são compostas em tempo de execução por
prefixo (`navigator.role.*`, `navigator.scale.*`, `navigator.group.*`,
`navigator.tickSource.*`, `overlay.ma.kind.*`, `overlay.ma.source.*`,
`study.rsi.smoothing.*`, `theme.*`, `settings.language.*`), sobram nove chaves
cujo nome não aparece em sítio nenhum do produto:

`menu.chart`, `action.close`, `chart.measureMode`, `chart.style.candleHollow`,
`view.documents`, `view.chartsOpenInWindows`, `replay.date`, `replay.ticks`,
`settings.appearance.theme`.

`menu("menu.chart")` não existe — `MainWindow:912-939` monta cinco menus e nenhum
é o de gráfico. `ChartHolder:660-662` oferece dois estilos, `chart.style.candle` e
`chart.style.line`; o terceiro, `candleHollow`, ficou com rótulo e mnemónico e sem
botão (o oco é hoje uma caixa de verificação nas preferências,
`settings.chart.hollow`).

**Consequência** — texto morto que se lê como texto vivo. É o defeito que o
javadoc do `BundleKeysTest` descreve para chaves repetidas — "somebody edits it,
nothing changes, and there is nothing to look at" — e que o teste não apanha,
porque só compara os dois bundles um com o outro. Sete das nove estão nos dois
arquivos, portanto quem traduz também as traduz. Os quatro mnemónicos órfãos
(`C`, `C`, `M`, `H`) fazem pior: quem for acrescentar um menu novo vai supor que
as letras já estão tomadas.

**Correção** — apagar as nove das duas línguas, ou ligá-las ao que prometem.
Nenhuma delas guarda dado, portanto apagar não custa nada. E vale acrescentar ao
`BundleKeysTest` a terceira comparação que falta: chave do bundle × fonte, nos
dois sentidos (a metade "usada no código e ausente do bundle" apanhava L4-1
sozinha).

**Tentei refutar assim** — para cada uma das nove procurei o prefixo por que
poderia ser composta: `grep '"chart\.style\.|"menu\.|"view\.|"replay\.|"settings\.
appearance|"action\.close|"chart\.measureMode'` em `src/main/java` devolve 15
linhas, todas com a chave completa e nenhuma com concatenação. Confirmei que
`chart.style.candle` e `chart.style.line` chegam de `CandleStyle:71` e
`LineStyle:42` como constantes literais, sem terceiro irmão. Verifiquei os testes:
nenhuma das nove aparece em `src/test` (as que aparecem — `navigator.group.win`,
`navigator.scale.1m` e as outras — são as compostas, e ficaram de fora da lista).
Não caiu.

---

## BAIXA

### L4-10. O mnemónico de "Configurações..." é a letra `P`, que não está no rótulo — em nenhuma das duas línguas

`src/main/resources/messages.properties:21-22`
`src/main/resources/messages_pt_BR.properties:21-22`

```
action.preferences = Settings...
action.preferences.mnemonic = P
```

```
action.preferences = Configurações...
action.preferences.mnemonic = P
```

**Problema** — cruzei os 41 pares `<chave>` / `<chave>.mnemonic` dos dois bundles:
este é o único em que a letra não ocorre no rótulo. Sobrou do tempo em que o item
se chamava *Preferences* / *Preferências*. O cabeçalho do bundle diz que a chave
existe porque "the letter differs per language"; aqui não corresponde a nenhuma.

**Consequência** — `JMenuItem.setMnemonic('P')` sem letra correspondente não
desenha sublinhado nenhum. O atalho continua a funcionar com o menu aberto, mas
nada na tela o diz, e o item fica a ser o único do menu *Arquivo* sem letra
marcada — o que se lê como defeito de desenho.

**Correção** — `S` em inglês (*Settings*) e `C` em português (*Configurações*).
Nenhuma das duas choca com os irmãos do menu (`N`/`X` e `N`/`S`... o `S` do
inglês está livre; em português `S` é do *Sair*, por isso `C`).

**Tentei refutar assim** — confirmei que o item é montado por
`item("action.preferences", KeyEvent.VK_COMMA, this::openPreferences)`
(`MainWindow:915`), e que `item` faz `menuItem.setMnemonic(Messages.mnemonic(key))`
sem tocar em `setDisplayedMnemonicIndex`. Verifiquei que os outros 40 pares
passam. Não caiu.

### L4-11. O bundle português escreve a mesma palavra com e sem acento, às vezes a duas linhas de distância

`src/main/resources/messages_pt_BR.properties` — linhas 46 × 203/220/238,
221/235/338, 208/233 × 342, 296 × 329, 38/39/47/49, 193, 195

```
navigator.series = Séries
...
summary.name = Serie
series.series = Serie
replay.series = Serie
```

```
overlay.ma.average = Média
...
overlay.bb.average = Media
```

```
console.seriesFailed = {0} nao pode ser lido: {1}
console.seriesMissing = nenhuma serie encontrada em {0}; desenhando barras sinteticas
```

**Problema** — a convenção da casa manda comentário em português **sem** acento no
`endeavour`; no `endeavour_neo` o comentário é em inglês e o texto de interface
sai do bundle — onde o português é português. Metade do arquivo respeita isso
(`Configurações`, `pregões`, `Início`, `Média`, `não`) e a outra metade não
(`Serie`, `pregoes`, `Inicio`, `Media`, `nao`), sem padrão: `segment.from =
Início` e `summary.from = Inicio` descrevem o mesmo campo.

**Consequência** — a mesma palavra escrita de dois jeitos na mesma janela.
`chart.renkoNeedsTicks` (linha 195) é um parágrafo inteiro sem um único acento, ao
lado do seu próprio título, que os tem.

**Correção** — passar o arquivo a acento correto de uma vez. É `.properties` em
UTF-8 desde o Java 9 (o cabeçalho do arquivo já o diz), portanto não é preciso
escapar nada.

**Tentei refutar assim** — verifiquei se a ausência de acento é deliberada por
codificação: o cabeçalho do próprio arquivo (linhas 3-6) diz que é UTF-8 e que os
acentos "are safe as they are, with no \\uXXXX escaping needed" — e 200 linhas
acentuadas provam-no. Verifiquei se as linhas sem acento são de uma língua
diferente: são português, só sem acento. Não caiu.

### L4-12. Quatro grafias do mesmo separador de tela

`src/main/java/.../ui/shell/Navigator.java:258,271,293`
`src/main/java/.../ui/replay/ReplayFeed.java:243,244,250,252`
`src/main/java/.../ui/series/Segmentable.java:123,124`
`src/main/java/.../ui/shell/MainWindow.java:896`
`src/main/java/.../ui/chart/study/StudyPane.java:615`
`src/main/java/.../ui/chart/PeriodCatalog.java:104`
`src/main/java/.../ui/shell/StatusBar.java:173`

```java
        return scale == null || scale.isBlank() ? name : name + " · " + scale;
```

```java
                return code + " - " + trim(renko.brick()) + " pts";
```

```java
        jobName.setText(stage.isEmpty() ? label : label + " — " + stage);
```

**Problema** — a casa tem um separador: `"  ·  "`, ponto médio com dois espaços de
cada lado, em sete sítios (árvore, transporte, título de gráfico, etiqueta de
ticks). `StudyPane` usa o mesmo ponto com **um** espaço, `PeriodCatalog` usa
hífen, `StatusBar` usa travessão. Todos glue de tela escrito em Java, nenhum sob o
bundle.

**Consequência** — o mesmo par "nome · escala" alinha de maneira diferente
consoante apareça no título da janela (`MainWindow:896`) ou no cabeçalho do painel
de estudo (`StudyPane:615`); o javadoc de `StatusBar:195` chega a documentar o
formato como `WINFUT-FULL · 5m`, que é a grafia de nenhum dos dois.

**Correção** — uma constante em `ui` (ou uma chave `format.separator` no bundle,
já que a pontuação muda de tipografia entre línguas) e sete chamadas a ela.

**Tentei refutar assim** — verifiquei se as quatro grafias distinguem coisas
diferentes de propósito: `MainWindow:896` e `StudyPane:615` juntam exatamente o
mesmo par (nome do instrumento, escala) com espaçamentos diferentes, portanto não
distinguem nada. Não caiu.

### L4-13. `ReplaySession.endText()` compila três formatadores por atualização do transporte, e o `ChartCanvas` documenta por que isso é errado

`src/main/java/br/com/jorge/reis/endeavourneo/ui/replay/ReplaySession.java:127-130,726,729-730`
`src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/ChartCanvas.java:175-183`

```java
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final DateTimeFormatter DAY_AND_CLOCK =
            DateTimeFormatter.ofPattern("dd/MM HH:mm:ss");
```

```java
        if (!isRange()) {
            return close.format(DateTimeFormatter.ofPattern("HH:mm"));
        }

        return until.format(DateTimeFormatter.ofPattern("dd/MM"))
                + " " + close.format(DateTimeFormatter.ofPattern("HH:mm"));
```

```java
     * <p>Beside the other four rather than inline where it is used. It was the
     * fifth date pattern in this file and the only one written at the point of
     * use, which is how a set of formats drifts apart one edit at a time.</p>
```

**Problema** — a regra está escrita, com a razão, no arquivo ao lado; e este
arquivo, que já tem dois formatadores como constantes duzentas linhas acima,
escreve mais três no ponto de uso. `endText()` é chamado por
`ReplayPanel:738`, na mesma atualização que `clockText()` — ou seja, a cada
tique do transporte.

**Consequência** — `DateTimeFormatter.ofPattern` analisa a cadeia do padrão e
constrói a árvore de impressão a cada chamada. Não é caro ao pé de desenhar um
gráfico, mas é alocação por quadro no caminho da EDT, que a casa recusa por
regra. E `"dd/MM"` fica a ser a quinta cópia do mesmo padrão no produto.

**Correção** — duas constantes ao lado de `CLOCK` e `DAY_AND_CLOCK`: uma `HH:mm` e
uma `dd/MM`.

**Tentei refutar assim** — verifiquei se `endText()` é chamado uma só vez por
sessão: `ReplayPanel:738` está no mesmo bloco que `clock.setText(session.
clockText())` e que `scrubber.setValue(...)`, que é a atualização periódica do
transporte. Verifiquei se `ofPattern` guarda cache: não guarda, constrói um
`DateTimeFormatter` novo por chamada. Não caiu.

### L4-14. A tela escreve a palavra `null` quando a exceção não traz mensagem, e o console e a barra de estado dão duas versões da mesma falha

`src/main/java/br/com/jorge/reis/endeavourneo/ui/shell/MainWindow.java:427-428,496-497,534-535,1126-1127`

```java
                        console.write(Messages.get("console.seriesFailed", name,
                                String.valueOf(e.getMessage())));
```

```java
            console.write(Messages.get("job.failed", String.valueOf(error)));
            status.say(Messages.get("job.failed", error.getClass().getSimpleName()));
```

**Problema** — `String.valueOf(e.getMessage())` devolve a cadeia `"null"` quando a
exceção não tem mensagem, o que é o caso de `InterruptedException` (apanhada
explicitamente nas três) e de boa parte das de fim de arquivo. O resultado é
`win-1m nao pode ser lido: null`, que diz menos do que o nome da classe diria.

E as duas linhas 1126-1127 dão duas respostas à mesma pergunta com a **mesma
chave**: o console recebe `String.valueOf(error)` (classe qualificada e mensagem)
e a barra de estado `getSimpleName()` (só a classe). O leitor que olhe primeiro
para a barra vê `tarefa falhou: IllegalStateException` sem a razão que estava a
uma linha de distância.

**Consequência** — a mensagem não diz o arquivo, o valor nem a razão quando podia
dizer as três.

**Correção** — um auxiliar `reason(Throwable)` que devolva a mensagem quando
existe e `getClass().getSimpleName()` quando não; usá-lo nos quatro sítios, para
o console e para a barra.

**Tentei refutar assim** — procurei um tratamento a montante que garanta mensagem:
as três apanham `ExecutionException | InterruptedException`, e a segunda nunca
traz mensagem quando é lançada por `Future.get`. Verifiquei que `console.
seriesFailed = {0} nao pode ser lido: {1}` põe mesmo o argumento na tela. Não caiu.

### L4-15. A contagem de barras vai para o console como `String`, o que anula o agrupamento de milhares que a mesma classe usa três linhas adiante

`src/main/java/br/com/jorge/reis/endeavourneo/ui/shell/MainWindow.java:492-493,528-529,795`
`src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/SeriesSummary.java:152`

```java
                    console.write(Messages.get("console.seriesLoaded", name,
                            String.valueOf(bars.size())));
```

```java
        status.say(Messages.get("status.tiled", frames.size()));
```

```java
        return String.format("%,d", value);
```

**Problema** — `Messages.get(key, Object...)` passa por `MessageFormat`, que
formata um `Integer` com o `NumberFormat` do locale (com separador de milhar) e um
`String` tal e qual. `status.tiled` passa o `int` e sai agrupado; `console.
seriesLoaded` embrulha em `String.valueOf` e sai cru. `SeriesSummary` faz uma
terceira coisa: `String.format("%,d", …)`. Três regras para escrever um número
inteiro na tela, duas delas na mesma classe.

**Consequência** — o console diz `winfull-1m: 824881 barras lidas do disco` e o
resumo da mesma série, no mesmo instante, diz `824.881`.

**Correção** — passar `bars.size()` sem embrulhar. É tirar quinze caracteres em
dois sítios.

**Tentei refutar assim** — verifiquei se o `String.valueOf` protege de algum
`null`: `bars.size()` e `series.get().size()` são `int`, nunca nulos. Verifiquei o
comportamento do `MessageFormat`: com argumento `Number` e sem sub-formato no
padrão, aplica `NumberFormat.getInstance(locale)`, que agrupa. Não caiu.

### L4-16. O menu *Ferramentas* é o único sem mnemónico nos itens

`src/main/java/br/com/jorge/reis/endeavourneo/ui/shell/MainWindow.java:927-929`
`src/main/resources/messages.properties:218,236`

```java
        JMenu tools = menu("menu.tools");
        tools.add(item("action.series", 0, this::openSeries));
        tools.add(item("action.replay", 0, this::openReplay));
```

```
action.replay = Replay...
action.series = Series and segments...
```

**Problema** — `item()` faz sempre `menuItem.setMnemonic(Messages.mnemonic(key))`,
e nem `action.series` nem `action.replay` têm chave `.mnemonic` em nenhum dos dois
bundles; `Messages.mnemonic` devolve 0. Os outros quatro menus têm mnemónico em
todos os itens. E os dois itens também passam `0` como acelerador, portanto não há
nem tecla rápida a compensar.

**Consequência** — o menu que abre as duas janelas mais pesadas do programa é o
único que não se percorre pelo teclado.

**Correção** — `action.series.mnemonic` e `action.replay.mnemonic` nos dois
bundles (`S`/`R` em inglês, `S`/`R` em português — não chocam entre si).

**Tentei refutar assim** — confirmei pelo cruzamento de chaves que estas duas são
as únicas passadas a `item()` sem irmã `.mnemonic` (as restantes onze têm-na).
Confirmei que `menu.tools.mnemonic` existe (`T`/`F`), portanto é só o interior do
menu que está vazio. Não caiu.

### L4-17. O bundle atravessa para o canal errado: `document.untitled` chega a `MainWindow.open` como se fosse o nome de uma série

`src/main/java/br/com/jorge/reis/endeavourneo/ui/shell/Navigator.java:191-193,125-140`

```java
        DefaultMutableTreeNode studies =
                new DefaultMutableTreeNode(Messages.get("navigator.studies"));
        studies.add(new DefaultMutableTreeNode(Messages.get("document.untitled")));
```

```java
    static String nameOf(DefaultMutableTreeNode node) {
        Object held = node.getUserObject();

        if (held instanceof Leaf entry) {
            return entry.name();
        }

        return node.isLeaf() ? String.valueOf(held) : null;
    }
```

**Problema** — a folha de *Estudos* é um `DefaultMutableTreeNode` cru cujo
`userObject` é a **cadeia traduzida**; `nameOf` cai no ramo `String.valueOf(held)`
e devolve `Sem título` ou `Untitled` conforme a língua do momento. Todas as outras
folhas usam `Leaf(name, label)`, e o javadoc de `nameOf` diz que uma folha sem
nome — "a tick session, a message" — não abre nada. A folha de mensagem
(`Navigator:187-188`) respeita isso, com `new Leaf(null, …)`; esta não.

O que sai daqui vai parar a `MainWindow.open(String series)`, cujo comentário nas
linhas 577-581 descreve exatamente o estrago que isto já fez uma vez:
"Workspaces written before there was a series hold names like **Sem título**".

**Consequência** — hoje o estrago é contido, porque `open()` corrige o nome para a
série por omissão. Mas o canal de identificadores passa a receber texto
traduzido, e o que entra depende da língua em que a árvore foi construída. É o
tipo de acoplamento que reaparece assim que alguém guardar o valor antes da
correção.

**Nota de sobreposição** — B7b-10 reportou o sintoma ("a folha de *Estudos* abre um
gráfico"). Este achado é a causa do lado do texto, e a correção é a mesma linha.

**Correção** — `new DefaultMutableTreeNode(new Leaf(null, Messages.get("document.
untitled")))`, como a folha de mensagem ao lado.

**Tentei refutar assim** — verifiquei se a folha é apanhada antes de `nameOf`: o
tratador em `Navigator:86-97` chama `nameOf(leaf)` e só filtra `null`. Verifiquei
se `document.untitled` difere entre bundles: `Untitled` / `Sem título`, sim. Não
caiu.

### L4-18. Duas chaves com o mesmo texto, para o mesmo botão e para o valor por omissão que ele propõe

`src/main/resources/messages.properties:178-179` / `messages_pt_BR.properties:178-179`
`src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/LayoutBar.java:401-402`

```
layout.newDefault = New layout
layout.add = New layout
```

```java
        String name = JOptionPane.showInputDialog(this, Messages.get("layout.namePrompt"),
                Messages.get("layout.newDefault"));
```

**Problema** — `layout.add` é o rótulo do item de menu e `layout.newDefault` é o
nome que o diálogo propõe. São conceitos diferentes com o mesmo texto, o que está
certo — mas quem traduzir um e não o outro parte a coincidência sem perceber, e
quem só vir o arquivo não tem como saber que a igualdade é de propósito.

**Consequência** — dívida pequena. Vale um comentário no bundle a dizer que as
duas devem coincidir, ou o contrário: que não precisam.

**Correção** — uma linha de comentário acima das duas.

**Tentei refutar assim** — confirmei pelos usos que são mesmo dois papéis
(`LayoutBar:401` usa `newDefault` como valor inicial; `layout.add` é o rótulo do
item). Não é chave morta nem duplicada no sentido do `BundleKeysTest`, por isso
BAIXA e não MÉDIA.

### L4-19. As Bandas de Bollinger falam noutro registo que o resto do bundle, nas duas línguas

`src/main/resources/messages_pt_BR.properties:326-334`
`src/main/resources/messages.properties:326-334`

```
overlay.bb.upper = Desvio Superior
overlay.bb.lower = Desvio Inferior
overlay.bb.average = Media
overlay.bb.showMiddle = Mostrar Media
overlay.bb.fillGroup = Preenchimento
overlay.bb.fill = Preencher entre as bandas
overlay.bb.opacity = Transparencia
overlay.bb.tab.middleAppearance = Aparencia Media
```

**Problema** — quatro das oito estão em Caixa de Título (`Desvio Superior`,
`Mostrar Media`, `Aparencia Media`) onde as 25 chaves de `overlay.ma.*` logo acima
estão em caixa de frase (`Média móvel`, `Deslocamento`, `Inclinar entre os pontos
fechados`). E quatro estão sem acento (`Media`, `Transparencia`, `Aparencia`)
tendo `overlay.ma.average = Média` trinta linhas acima. O inglês tem o mesmo
desalinhamento ao contrário: `Upper deviation` em caixa de frase ao lado de
`Average appearance`, que também está — mas o português traduziu como se fosse
Caixa de Título.

**Consequência** — o diálogo de Bollinger, aberto a seguir ao de média móvel, lê
como se tivesse sido escrito por outra pessoa. É o indicador mais recente do
produto; a divergência vai crescer com o próximo.

**Correção** — caixa de frase e acento, alinhados com `overlay.ma.*`:
`Desvio superior`, `Desvio inferior`, `Média`, `Mostrar média`, `Transparência`,
`Aparência da média`.

**Tentei refutar assim** — verifiquei se as chaves `bb` são de um diálogo isolado
onde a diferença não se veria: `BollingerBandsDialog` tem 41 chamadas a `Messages`
e partilha as abas com `MovingAverageDialog` (`overlay.tab.parameters`,
`overlay.tab.appearance`, `dialog.ok`), portanto as duas grafias aparecem na mesma
janela. Não caiu.

---

## LIMPO

O que foi conferido e está certo, e como.

**Os dois bundles estão em paridade perfeita de chaves.** Script comparou as 335
chaves de cada um: zero em inglês sem português, zero em português sem inglês,
zero chaves repetidas dentro de qualquer um dos dois. `BundleKeysTest` cobre
exatamente isto e tem dentes para isto.

**Nenhum `toUpperCase()`/`toLowerCase()` sem `Locale`.** As cinco ocorrências em
`src/main` passam todas `Locale.ROOT` — `SeriesCatalog:353`, `Appearance:194`,
`AppearancePage:147`, `TickSource:95`, `PeriodCatalog:204,208,209`. É a escolha
certa: são todas comparações de identificador (nome de tema, chave de escala,
`os.name`), não de texto de leitor. O problema turco do `i` sem ponto não existe
aqui. `Messages.mnemonic:129` usa `Character.toUpperCase(char)`, que é
independente de locale por definição do JDK — não é o `String.toUpperCase()`
perigoso.

**Todos os oito diálogos `JOptionPane` tiram texto do bundle.** Conferi um a um
com `-C 5`: `ChartCanvas:978-981` (`chart.renkoNeedsTicks` + `.title`),
`SeriesWindow:383-387` (`series.removeAsk` + `series.removeTitle`),
`LayoutBar:401-402` (`layout.namePrompt` + `layout.newDefault`),
`LayoutBar:444-445`, `LayoutBar:476-478` (`layout.confirmRemove` +
`layout.remove`). Nenhum literal de mensagem nem de título. E o comentário de
`Language:100-106` está certo quanto aos botões: `Locale.setDefault` é mesmo a
única forma de o *Sim/Não* do `JOptionPane` seguir a escolha.

**Nenhum componente nasce com texto de tela literal.** Das 17 ocorrências de
`new JLabel("…")`, `new JButton("…")` e afins, todas são glifos ou marcadores de
espaço sem língua: `" "` (sete vezes, a reservar altura de linha), `"0"`, `"+"`,
`"▾"`, `"--:--:--"`, `"<html><body style='width:250px'>"`. Não há uma frase entre
elas.

**A única cadeia de tela em português dentro do código já estava reportada.**
`PeriodCatalog:228-247` (`"1 dia"`, `"1 semana"`, `"1 mês"`, `"1 minuto"`,
`" hora"/" horas"`, `" minutos"`) é B4-5, e `SegmentDialog` é B7b-18. Varri os 54
literais com forma de frase em `src/main/java` e os 39 `setText`/`setTitle`/
`setToolTipText` que não passam por `Messages`: fora estes dois arquivos e o
`Appearance` do L4-5, todo o resto é `AssertionError("Utility class must not be
instantiated")`, mensagem de exceção de domínio, ou valor calculado.

**As mensagens de exceção do domínio estão em inglês e são exceções, não tela.**
`PriceSeries:76-96`, `TapeFile:508,518`, `TickSeries:118-138`, `Renko:280`,
`ProfitTrades:261,297,326` — nenhuma chega a um componente. É o comportamento que
a convenção pede: o `domain` não conhece a interface.

**`Aggressor("Comprador"/"Vendedor"/"Leilão"/"Direto")` não é texto de tela.** São
as palavras que a exportação do Profit escreve no arquivo, lidas por
`Aggressor.of(String)`; o javadoc de `said()` di-lo — "the word the export uses".
Traduzi-las partiria a leitura do arquivo. Confirmei que nenhum `said()` chega a
um `setText`.

**Todos os separadores decimais e agrupamentos seguem a língua escolhida.** Os
seis sítios de `DecimalFormat` (`BarReadout:256`, `RulerReadout:157`,
`StudyPane:886`, `OverlayLegend:327`, `ChartCanvas:2637`, `Sessions:118`) passam
`DecimalFormatSymbols.getInstance(Locale.getDefault())` **dentro de um método**, a
cada uso, portanto respondem à troca de idioma — ao contrário do `MONTH` do L4-3.
(Que alguns os construam uma vez por número desenhado é problema de alocação já
reportado em B4-12, B5-7 e B4-9; do ponto de vista do idioma, estão certos.)

**Os 21 mnemónicos de menu e de caixa de verificação não chocam dentro do seu
container, e 40 dos 41 pares têm a letra no rótulo.** Barra de menus inglesa
`F V T R W`, portuguesa `A E F X J`; menu *Exibir* inglês `C R G B A`, português
`L R O Z T`; menu *Executar* `S C` / `R C`; página *Gráfico* `M H V O`. Zero
colisões. A única letra fora do rótulo é o `P` do L4-10.

**A troca de idioma reconstrói mesmo a janela.** `MainWindow.openPreferences:1077-
1091` guarda a escolha antes, compara depois e chama `relaunch()`, que faz
`Language.install()` **antes** de `new MainWindow(Messages.get("app.title"), …)` —
a ordem certa, e com o comentário a dizer porquê. O que o `install()` não cobre é
o que está em L4-2 e L4-3.

**O que ficou de fora de propósito:** concorrência e EDT (L1), fuso horário e
leitura do futuro (L2), recursos que vazam (L3), e os 215 achados de área do
`00-achados.md` — que li antes de começar, e cujos temas de texto (B4-5, B7b-18,
B8b-13, B4-13, B8a-11, B8a-18, B6-14, B4-28, B4-24, B5-17, B7a-15, B7a-22) não
estão repetidos acima.
