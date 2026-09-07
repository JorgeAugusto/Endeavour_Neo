# A7b — casca, preferências e janela de séries

**Data:** 06/09/2026
**Área:** `ui/shell/`, `ui/series/`, `ui/settings/` — 18 arquivos, 4.868 linhas de produção
**Testes lidos (parte da área):** `ui/shell/MainWindowTest` (241), `ui/shell/NavigatorTreeTest` (379),
`ui/shell/StatusStripTest` (139), `ui/shell/CollapsiblePaneTest` (75), `ui/series/SegmentPickingTest` (163).
Não existe teste algum para `ui/settings/` — nenhuma das cinco páginas de preferências, nem o
`SettingsDialog`, é exercitada por qualquer teste do repositório.

**Lidos integralmente:** `MainWindow` (1.041), `SegmentDialog` (580), `SeriesWindow` (549),
`Navigator` (383), `StatusBar` (332), `RangeBar` (319), `SeriesMap` (303), `Icons` (215),
`SettingsDialog` (204), `Segmentable` (159), `AppearancePage` (157), `CollapsiblePane` (150),
`GeneralPage` (145), `Console` (132), `ChartPage` (125), `SeriesColors` (125), `ReplayPage` (104),
`SettingsPage` (48).

**Lidos como contrato (não auditados):** `platform/Messages`, `platform/Settings`,
`platform/SeriesCatalog`, `platform/Segmentation`, `platform/JobService`, `platform/Language`,
`platform/Theme`, `domain/market/Sessions`, `domain/market/Segment`, `ui/chart/ChartHolder` (só o
`close()`), `ui/replay/ReplayFeed` (só o cache de sessões), `ui/replay/ReplayPreferences`, `Launcher`.

**O que foi conferido:** os dois caminhos de saída da janela; o que cada saída faz com a lista de
gráficos a reabrir; menus, aceleradores e ouvintes; as 88 chaves de bundle usadas nesta área,
inclusive as montadas por concatenação, contra os dois arquivos `messages*.properties`; a aritmética
completa do `Strip` (posição e largura de cada campo, do `jobArea` e da mensagem, em janela larga e
estreitíssima); o ciclo carregar/editar/cancelar/aplicar de cada uma das quatro páginas de
preferências; o caminho abrir série → escolher segmento → cancelar; o custo por evento de cada
manipulador; e, para cada teste, qual mutação do código ele deixaria passar.

---

## Achados ALTA

### A7b-1 — Sair pelo menu apaga a lista de gráficos a reabrir

**Onde:** `ui/shell/MainWindow.java:1035`

**Trecho:**
```java
    private void exit() {
        closeCharts();
        storeLayout();
        dispose();
        System.exit(0);
    }
```

Comparado com o outro caminho de saída, `windowClosing` (`MainWindow.java:202`):

```java
            public void windowClosing(WindowEvent e) {
                prepareToLeave();
                closeCharts();
                storeLayout();
            }
```

**Problema:** `exit()` não chama `prepareToLeave()`, e é justamente `prepareToLeave()` que grava a
lista e depois liga o `leaving`. Sem ele, `closeCharts()` (`MainWindow.java:544`) fecha cada gráfico,
cada `ChartHolder.close()` roda o `onClosed` (confirmado em `ui/chart/ChartHolder.java:578`), e o
`onClosed` desta janela é

```java
        ChartHolder holder = new ChartHolder(title, shown, desktop, this, () -> {
            charts.remove(title);
            rememberCharts();
        });
```

`rememberCharts()` (`MainWindow.java:252`) só se cala quando `leaving || restoring`; com `leaving`
falso ele reescreve `chart.open.*` um gráfico mais curto a cada fechamento, terminando vazio. O
comentário de `rememberCharts` descreve exatamente este defeito como já corrigido — mas a correção
está só num dos dois caminhos.

**Consequência:** o usuário abre cinco gráficos, sai por **Arquivo → Sair** (ou Ctrl+Q, que é o
mesmo `item("action.exit", KeyEvent.VK_Q, this::exit)` da linha 695) e na próxima abertura a área de
trabalho vem vazia. Fechar pelo X da janela funciona. A diferença entre os dois é invisível para
quem usa.

**Correção:** `exit()` deve começar por `prepareToLeave()`, como `windowClosing`. Melhor ainda:
`exit()` deveria disparar `WindowEvent.WINDOW_CLOSING` na própria janela, para que exista **um** só
caminho de saída e a próxima adição de comportamento não precise ser escrita duas vezes.

**Tentei refutar:** procurei um `prepareToLeave()` em algum gancho de desligamento —
`Runtime.addShutdownHook` só existe em `Launcher.java:83` e fecha o `JobService`, não toca no
workspace; e mesmo que tocasse, `System.exit(0)` roda o gancho **depois** de `closeCharts()` já ter
esvaziado a lista. Procurei também se `dispose()` dispara `windowClosing` — não dispara (só
`windowClosed`), e não há `windowClosed` registrado aqui. O defeito é real.

---

### A7b-2 — O teste do "toda janela volta" só exercita a saída que funciona

**Onde:** `src/test/java/.../ui/shell/MainWindowTest.java:220`

**Trecho:**
```java
                // The application's own exit, not an approximation of it:
                // writing the list and freezing it are one step, and a test
                // that did only the first was testing a sequence nothing runs.
                window.prepareToLeave();
                window.closeCharts();
```

**Problema:** o comentário afirma que esta é "a própria saída da aplicação". São **duas**: o
`windowClosing` — que o teste reproduz — e o `exit()` do menu, que roda `closeCharts()` **sem**
`prepareToLeave()` (A7b-1). O teste dá licença exatamente ao caminho quebrado: enquanto ele passa,
ninguém procura o outro.

**Consequência:** um defeito de perda de dado sobreviveu porque a suíte estava verde. É o terceiro
caso desta auditoria.

**Correção:** ou o teste chama o caminho real (disparar `WINDOW_CLOSING` na janela), ou existem dois
testes, um por saída. Se A7b-1 for corrigido unificando as saídas, um teste basta — mas ele tem que
passar pelo mesmo método que o menu chama.

**Tentei refutar:** procurei em toda a suíte por outro teste que exercite `exit()` — não há nenhuma
referência a `exit` fora do próprio `MainWindow`. Procurei se `exit()` seria inalcançável (código
morto): está ligado em `file.add(item("action.exit", KeyEvent.VK_Q, this::exit))`, linha 695, com
acelerador. É alcançável por menu e por teclado.

---

### A7b-3 — Varredura de 824.881 barras na EDT, dentro do ouvinte do combo (a quinta ocorrência)

**Onde:** `ui/series/Segmentable.java:122` chamado de `ui/series/SeriesWindow.java:392`, que é
chamado do ouvinte de `ui/series/SeriesWindow.java:148`

**Trecho:** o ouvinte do combo:
```java
        series.addActionListener(e -> {
            save();
            load();
        });
```

o que `load()` faz:
```java
        editing = String.valueOf(series.getSelectedItem());

        // The counts go blank when this comes back empty; the segments are
        // still listed and still editable, which is what this window is for.
        days = Segmentable.sessionsOf(editing);
```

e o que `sessionsOf` faz para uma série de barras:
```java
        try {
            PriceSeries bars = SeriesCatalog.open(key).orElse(null);

            return bars == null || bars.size() == 0 ? new TreeSet<>() : Sessions.of(bars);
        } catch (IOException e) {
            return new TreeSet<>();
        }
```

**Problema:** `Sessions.of` percorre a série inteira, barra a barra. O javadoc do próprio
`domain/market/Sessions.java:44` mede: *"824.881 bars walked in 39-102 ms to find 1.494 sessions.
Fine once; not fine per repaint. Whoever asks should hold the answer rather than ask again."* Nada
aqui segura a resposta: cada troca de item no combo, e cada abertura da janela, refaz a varredura na
thread de interface.

Pior: **o cache já existe no repositório e esta janela não o usa.**
`ui/replay/ReplayFeed.java:140` guarda exatamente este mapa —

```java
    private static final Map<String, NavigableSet<LocalDate>> KNOWN = new ConcurrentHashMap<>();
```

— e `ReplayFeed.warm()` (`ReplayFeed.java:183`) é aquecido fora da EDT em `Launcher.java:88`, com o
comentário: *"it opens each series and walks it, which is a tenth of a second each and would be a
visible stall if it happened when a combo changed"*. É o mesmo combo. A ferramenta de defesa foi
escrita, documentada, aquecida no arranque — e a janela de séries passa ao largo dela.

**Consequência:** a janela **Ferramentas → Séries** trava por ~100 ms ao abrir e outros ~100 ms a
cada série escolhida no combo, com a interface inteira congelada. Com a base de seis anos é um
soluço; com a base de 1 M de barras que o projeto considera normal, é o dobro. Para uma fonte de
ticks nada disso acontece (é listagem de diretório), o que faz o travamento parecer aleatório para
quem usa.

**Correção:** `Segmentable.sessionsOf` deve memorizar por chave, como `ReplayFeed.KNOWN`, e ser
aquecida no mesmo trabalho de arranque; o ideal é extrair o cache que já existe em `ReplayFeed` para
um lugar que os dois usem, porque hoje são duas cópias da mesma pergunta com respostas de custo
diferente. Enquanto isso não acontece, `load()` deveria rodar num `JobService.submit` e devolver o
resultado à EDT — a janela tem um `JobService` a um `MainWindow` de distância e não o usa para nada.

**Tentei refutar:** verifiquei se `SeriesCatalog.open` já devolveria as sessões prontas —
`SeriesCatalog.java:601` guarda a **série** numa `SoftReference` (`LOADED`), não as sessões; a
segunda abertura evita a leitura do arquivo mas **não** a caminhada. Verifiquei se
`Sessions.of` seria O(sessões) e não O(barras): o laço é `for (int i = 0; i < series.size(); i++)`,
com uma comparação por barra — é O(barras); só a inserção no conjunto é que acontece uma vez por
pregão. Verifiquei se a janela seria aberta uma única vez por sessão de trabalho: é aberta por menu,
quantas vezes se quiser, e o combo é o modo normal de navegar entre séries.

---

### A7b-4 — Uma série que existe e falha ao ler vira passeio aleatório desenhado com o nome dela

**Onde:** `ui/shell/MainWindow.java:352`

**Trecho:**
```java
    private PriceSeries seriesFor(String name, String title) {
        try {
            java.util.Optional<PriceSeries> series = SeriesCatalog.open(name);

            if (series.isPresent()) {
                ...
                return series.get();
            }
        } catch (java.io.IOException e) {
            console.write(Messages.get("console.seriesFailed", name, String.valueOf(e.getMessage())));
            status.say(Messages.get("console.seriesFailed", name, String.valueOf(e.getMessage())));
        }

        console.write(Messages.get("console.seriesMissing", SeriesCatalog.folder().toString()));

        return new RandomWalkSeries(2_000, 135_000.0);
    }
```

**Problema:** o javadoc imediatamente acima deste método (linhas 345-350) diz o contrário do que o
código faz: *"It is never the answer when a series exists and fails to read: that says so out loud,
because prices that are not the market's, drawn without a word, are the one thing a chart must never
do."* O `catch` escreve a mensagem e **cai** para o `return new RandomWalkSeries(...)` — não há
`throw`, não há retorno antecipado. Falha de leitura e ausência de arquivo terminam no mesmo lugar.

**Consequência:** um arquivo truncado, um disco de rede que caiu, um `.bin` com cabeçalho corrompido
— e a janela abre com o título `WINFUT-FULL · 1m` mostrando 2.000 barras de ruído em torno de
135.000, indistinguíveis de mercado à primeira vista. As duas linhas no console e no rodapé são
varridas pela próxima mensagem. É a regra que o projeto considera inegociável, violada pelo método
que a enuncia.

**Correção:** o `catch` tem de terminar em `return null` (e `open()` desistir de abrir a janela,
como já faz no caso da série trancada, linha 404-410) ou em uma série vazia. O passeio aleatório é
resposta apenas para `series.isEmpty()` — nenhum arquivo — e mesmo aí o título precisa dizer que é
sintético.

**Tentei refutar:** procurei se `RandomWalkSeries` se identifica no gráfico — `ChartHolder` recebe
`shown = SeriesCatalog.displayOf(name)`, o nome do instrumento; nada na janela diz "sintético".
Procurei se `SeriesCatalog.open` embrulharia a falha em `Optional.empty()` em vez de exceção: ela
declara `throws IOException` e o `catch` aqui existe justamente porque ela lança. Procurei um guarda
no chamador: `open()` chama `seriesFor(name, title)` direto dentro de `SegmentedSeries.of(...)`,
linha 436, sem verificar nada.

---

### A7b-5 — O ternário de dois ramos iguais: a janela fica com o nome que foi pedido, não com o da série que abriu

**Onde:** `ui/shell/MainWindow.java:420`

**Trecho:**
```java
        String name = SeriesCatalog.has(asked) ? asked : SeriesCatalog.defaultName();

        // ALWAYS a new chart, never fronting an existing one. ...
        String title = uniqueTitle(SeriesCatalog.has(name) ? series : series);
```

**Problema:** os dois ramos do ternário são a mesma expressão, `series`. A condição não decide nada:
o título é sempre o nome **pedido**, mesmo quando `name` caiu para `SeriesCatalog.defaultName()`.
Isso contradiz palavra por palavra o comentário de abertura do método (linhas 391-396): *"A name that
is not a series opens the default one instead, and is titled after it. Workspaces written before
there was a series hold names like 'Sem título', and showing prices under a title that names no
instrument is worse than quietly correcting it -- which also repairs the entry, since what is open is
what gets remembered."*

Nada é corrigido e nada é reparado: `rememberCharts()` grava `seriesOf(title)` (linha 270), que
devolve o título sem o "(2)" — ou seja, grava `Sem título` de novo. A entrada ruim é imortal.

**Consequência:** uma janela intitulada `Sem título` — ou `One`, `Two`, `Chart`, os nomes que os
próprios testes usam — mostrando os preços de `winfull-1m`. Preços reais sob um rótulo que não nomeia
instrumento nenhum, e que volta assim a cada arranque.

**Correção:** `uniqueTitle(SeriesCatalog.has(asked) ? series : name)` — e, quando houver segmento,
recompor o `#segmento` sobre `name`, senão o segmento se perde no título.

**Tentei refutar:** procurei se `uniqueTitleOf` (linha 373), que parece uma tentativa anterior de
resolver isto, seria chamado em algum lugar — busquei no `src` inteiro: **nenhum chamador**, é código
morto. Procurei se algum teste fixaria o título esperado: `MainWindowTest:151` e `:99` usam
deliberadamente o título devolvido (`String title = window.open("Chart")`), com um comentário que
afirma a mesma regra que o código não cumpre; nenhuma asserção compara o título com o nome da série.
Nada derruba o achado — pelo contrário, o comentário do teste mostra que a intenção documentada é a
que descrevi.

---

### A7b-6 — Duas janelas de séries sobre a mesma série: a última a fechar apaga o que a outra gravou

**Onde:** `ui/series/SeriesWindow.java:190` e `ui/shell/MainWindow.java:831`

**Trecho:**
```java
    /** @param whenChanged run after every write, so a listing elsewhere can follow */
    public static void open(Window owner, Runnable whenChanged) {
        SeriesWindow window = new SeriesWindow(owner);

        window.onChanged = whenChanged == null ? () -> { } : whenChanged;
        window.setVisible(true);
    }
```

```java
    private void openSeries() {
        br.com.jorge.reis.endeavourneo.ui.series.SeriesWindow.open(this,
                () -> navigator.setModel(Navigator.treeModel()));
    }
```

e o que cada janela faz ao fechar (`SeriesWindow.java:172`):
```java
            public void windowClosing(java.awt.event.WindowEvent e) {
                // Saved on the way out, not on an OK button. ...
                save();
            }
```
com
```java
    private void save() {
        if (editing != null) {
            Segmentation.set(editing, model.segments);
```

**Problema:** a janela é `ModalityType.MODELESS` (linha 126) e `open` constrói uma nova a cada
chamada, sem registro nem verificação. Duas invocações do menu **Ferramentas → Séries** dão duas
janelas independentes, cada uma com sua cópia de `model.segments`, ambas gravando com
`Segmentation.set`, que é uma substituição total (`removeStartingWith` + regravação,
`Segmentation.java:136`). Não há releitura, nem detecção de conflito, nem aviso.

**Consequência:** janela A cria o segmento "treino" e grava. Janela B, aberta antes e mostrando a
lista antiga, é fechada — e "treino" desaparece do disco, sem uma linha no console. A árvore do
navegador é reconstruída pelo `onChanged` da B e passa a mostrar a lista sem "treino", de modo que
até a evidência na tela concorda com a perda.

**Correção:** guardar a instância, como `MainWindow` já faz com o `ReplayWindow`
(`MainWindow.java:790-799`): se existe, `toFront()`; senão constrói. Uma janela por aplicação é o
certo aqui — a segunda não oferece nada que a primeira não tenha.

**Tentei refutar:** procurei um registro de instância dentro de `SeriesWindow` — não há campo
estático algum. Procurei se a janela seria modal e portanto irreabrível: é `MODELESS`, explicitamente.
Procurei se `save()` mesclaria em vez de substituir: `Segmentation.set` apaga tudo o que começa com o
prefixo da série antes de escrever. Procurei se `windowClosing` só dispararia com edição pendente:
dispara sempre, mesmo sem nada ter sido tocado (ver A7b-13).

---

### A7b-7 — Teste sem dentes: "o mapa desenha os segmentos" não verifica desenho nenhum

**Onde:** `src/test/java/.../ui/series/SegmentPickingTest.java:189`

**Trecho:**
```java
    @Test
    @DisplayName("o mapa desenha os segmentos que ja existem sem reclamar dos abertos")
    void anOpenEndedSegmentIsDrawnToTheEnd() {
        ...
        // Painting is what would throw, so paint it.
        java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(
                520, 60, java.awt.image.BufferedImage.TYPE_INT_ARGB);

        map.paint(canvas.getGraphics());

        assertEquals(10, map.days().size());
    }
```

**Problema:** a única asserção é o tamanho da lista de dias — que foi posta por `showSeries` três
linhas antes e que nada no caminho de pintura pode alterar. O nome do teste promete que o segmento
aberto é desenhado até o fim da série; nada verifica isso. Mutações que passam intactas: `paintFresh`
retornar sem desenhar; `paintSegments` calcular `to = from` para o aberto em vez de
`days.size() - 1` (`SeriesMap.java:187`); `x()` devolver sempre `SIDE`; o desenho do `clashing` usar
a cor errada. O teste é um teste de fumaça — "pintar não estoura" — com o nome de um teste de
comportamento.

**Consequência:** a regra do segmento aberto (`isOpenEnded()` → desenhar até o último pregão), que é
a única lógica não trivial de `paintSegments`, está sem rede. Foi a mesma forma de licença falsa que
deixou passar os dois casos já registrados nesta auditoria.

**Correção:** o `SeriesMap` já expõe `edgeOf(int)` para o teste vizinho; dá para afirmar os limites
sem pixels — por exemplo, tornar visível o par (primeiro, último) que `paintSegments` calcula para
cada segmento e afirmar que o aberto termina em `days.size() - 1`. Se a afirmação tiver de ser sobre
pixels, ler o `BufferedImage` na coluna do último pregão e exigir que ela **não** seja a cor de
fundo — um piso, não um teto.

**Tentei refutar:** procurei outro teste que cubra o segmento aberto — `SegmentPickingTest` é o único
arquivo de teste de `ui/series`, e os demais métodos tratam de `sessionsBetween` e do `RangeBar`.
`sessionsBetween` nem chega a exercitar `isOpenEnded`, porque recebe duas datas concretas. Confirmei
que `map.days()` devolve a lista interna (`SeriesMap.java:293`), de modo que a asserção é literalmente
"o que eu pus continua lá".

---

### A7b-8 — Teste sem dentes: o rodapé é medido por posição, nunca por largura

**Onde:** `src/test/java/.../ui/shell/StatusStripTest.java:77` e `:141`

**Trecho:**
```java
    private List<String> onScreen(int width) {
        bar.setSize(width, 24);
        bar.doLayout();

        List<String> found = new ArrayList<>();

        for (Component each : bar.getComponents()) {
            if (each instanceof JLabel label && each.getX() >= 0
                    && !label.getText().isBlank()) {
                found.add(label.getText());
            }
        }

        return found;
    }
```
```java
    @Test
    @DisplayName("a mensagem nunca some, por mais estreito que fique")
    void theMessageStays() {
        assertTrue(onScreen(120).stream().anyMatch(each -> each.startsWith("Segmento")),
                "the message was dropped, and it is the one thing this bar is for");
    }
```

**Problema:** "estar na tela" é aferido só por `getX() >= 0`. Nenhum dos seis testes olha a largura.
A mutação que faz `Strip.layoutContainer` terminar em

```java
        message.setBounds(left, top, 0, height);
```

deixa a mensagem invisível — zero pixel de largura — e **passa nos seis**, inclusive no que diz "the
message was dropped, and it is the one thing this bar is for". O defeito que este teste existe para
pegar é de piso (a mensagem tem de ter largura), e a asserção é de posição.

Não é hipotético: com a área de trabalho visível e a janela estreita, a mensagem realmente vai a zero
— ver A7b-12, que descreve o caso e que estes testes não alcançam porque nunca deixam o `jobArea`
visível.

**Consequência:** toda a aritmética de `Strip` que decide **largura** — a única parte que pode estar
errada — está sem cobertura, no arquivo que foi reescrito por último.

**Correção:** `onScreen` deve exigir `each.getX() >= 0 && each.getWidth() > 0`, e `theMessageStays`
deve afirmar um piso explícito (`message.getWidth() >= MESSAGE_FLOOR`, ou pelo menos `> 0`).
Acrescentar um teste com o `jobArea` visível, que é onde a largura fica apertada.

**Tentei refutar:** rodei a aritmética de `layoutContainer` à mão para ver se a largura seria
consequência forçada da posição — não é: os campos recebem `setBounds(..., 0, height)` no primeiro
laço (linha 310) e só os mostrados são reposicionados depois, então largura e posição são escritas em
momentos diferentes e uma não garante a outra. Verifiquei se algum outro teste da suíte mede largura
de componente do rodapé: `StatusStripTest` é o único arquivo que instancia `StatusBar`.

---

## Achados MÉDIA

### A7b-9 — O log dobrado volta para onde estava no fechamento anterior, não para onde o leitor deixou

**Onde:** `ui/shell/MainWindow.java:977`

**Trecho:**
```java
    private void followConsoleFold() {
        if (consolePane.isFolded()) {
            consoleWasAt = bottomDivider.getDividerLocation();

            consoleWasAt = PREFS.getInt(BOTTOM_DIVIDER, -1);
            bottomDivider.setDividerLocation(bottomDivider.getHeight()
                    - consolePane.foldedHeight() - bottomDivider.getDividerSize());
```

**Problema:** a primeira atribuição é sobrescrita pela segunda antes de ser lida. A posição real do
divisor — a que o leitor acabou de escolher — é jogada fora e substituída pela que estiver gravada em
`Preferences`, que só é escrita em `storeLayout()`, ou seja, no fechamento anterior da aplicação. O
javadoc do método promete o oposto: *"And puts it back where the reader had it. A fold that reopened
at some default height would cost them the size they chose every time they peeked at the desktop."*

**Consequência:** o leitor arrasta o divisor, dobra o log para ver mais candles, desdobra — e o log
volta com outra altura. Na primeira execução da aplicação (sem a chave gravada) `getInt` devolve -1 e
o desdobrar cai no `0,68 * altura` do ramo de emergência, ainda mais longe do que ele tinha.

**Correção:** apagar a linha 981. A linha 979 já é o comportamento documentado.

**Tentei refutar:** verifiquei se `PREFS.getInt(BOTTOM_DIVIDER, -1)` poderia estar atualizado no
momento do dobrar — `storeLayout()` (linha 1021) só roda em `windowClosing`, `exit()` e `relaunch()`;
nada grava essa chave durante a sessão. Verifiquei se a leitura seria proposital para o caso de a
janela ainda não ter sido disposta (divisor em 0): nesse caso `getDividerLocation()` seria pequeno e
o ramo `consoleWasAt > 0` já protege o desdobrar. A linha é morta e o efeito é errado.

### A7b-10 — Duplo clique em "Sem título", sob Estudos, abre um gráfico da série padrão

**Onde:** `ui/shell/Navigator.java:132` com `ui/shell/Navigator.java:191`

**Trecho:**
```java
    static String nameOf(DefaultMutableTreeNode node) {
        Object held = node.getUserObject();

        if (held instanceof Leaf entry) {
            return entry.name();
        }

        return node.isLeaf() ? String.valueOf(held) : null;
    }
```
```java
        DefaultMutableTreeNode studies =
                new DefaultMutableTreeNode(Messages.get("navigator.studies"));
        studies.add(new DefaultMutableTreeNode(Messages.get("document.untitled")));
```

**Problema:** a folha sob "Estudos" carrega uma `String` traduzida como `userObject`, não um `Leaf`.
`nameOf` cai no ramo final e devolve o próprio texto traduzido como se fosse nome de série. O
comentário do ouvinte de duplo clique (linhas 89-91) afirma justamente o contrário: *"A leaf with no
name -- a tick session, a message -- opens nothing."* As folhas de ticks estão protegidas porque
carregam `Leaf(null, ...)`; esta não.

**Consequência:** duplo clique em "Sem título" chama `MainWindow.open("Sem título")`, que — por
A7b-5 — abre a série padrão com o título "Sem título" e ainda o grava no workspace, de onde ele volta
a cada arranque. É exatamente o cenário que o comentário de `MainWindow.open` descreve como já
acontecido.

**Correção:** a folha de Estudos deve ser `new Leaf(null, Messages.get("document.untitled"))`, como
as de ticks; ou `nameOf` deve devolver `null` para qualquer folha que não seja um `Leaf`, o que é a
regra mais segura e a que o comentário já enuncia.

**Tentei refutar:** procurei um teste que cubra este nó — `NavigatorTreeTest.walk` (linha 183) coleta
todas as folhas, inclusive esta, mas todos os testes filtram por nome de série (`"winfull-1m".equals`,
etc.), então a folha de Estudos passa despercebida em todos. Verifiquei se `SeriesCatalog.has("Sem
título")` bloquearia: não bloqueia, `open()` cai no `defaultName()` e abre.

### A7b-11 — O rodapé continua nomeando um gráfico que já foi fechado

**Onde:** `ui/shell/StatusBar.java:215`, e a ausência de chamador em `ui/shell/MainWindow.java:544`

**Trecho:**
```java
    /** Clears the chart fields, for when there is no chart under the pointer. */
    public void noChart() {
        chart("", "", "");
    }
```

**Problema:** busquei `noChart()` em todo o `src`: o único chamador é
`StatusStripTest.java:167`. A aplicação nunca limpa os campos. `MainWindow.report`
(`MainWindow.java:654`) mantém deliberadamente o nome do gráfico quando o ponteiro sai — *"The
pointer left. The chart's NAME stays"* — e `closeCharts()` e o `onClosed` de cada gráfico não avisam o
rodapé.

**Consequência:** fechados todos os gráficos, o rodapé continua exibindo
`WINFUT-FULL · 5m | 05/09 03:47 100,42 | Medindo`, apontando para uma janela que não existe mais. Numa
ferramenta cujo rodapé é a leitura sob o cursor, isso é um número que parece atual e não é.

**Correção:** chamar `status.noChart()` no `onClosed` do `ChartHolder` quando `charts.isEmpty()`, e em
`closeCharts()`.

**Tentei refutar:** verifiquei se o `ChartCanvas` limparia o rodapé ao ser destruído — `onCursorChanged`
é o único caminho até `report`, e um canvas fechado não emite mais nada; o último valor fica. Verifiquei
se `say()` sobrescreveria os campos: `say` só toca o rótulo `message`, que é outro componente.

### A7b-12 — Em janela estreita a mensagem vai a zero e o botão Cancelar sai de vista

**Onde:** `ui/shell/StatusBar.java:271` e `:313`

**Trecho:**
```java
        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(MESSAGE_FLOOR, preferredLayoutSize(parent).height);
        }
```
```java
            message.setBounds(left, top,
                    Math.max(0, width - forJob - forFields), height);

            int at = left + Math.max(0, width - forJob - forFields);
            ...
            if (jobArea.isVisible()) {
                jobArea.setBounds(at + GAP, top,
                        Math.max(0, parent.getWidth() - edge.right - at - GAP), height);
            }
```

**Problema:** `minimumLayoutSize` declara 140 px como largura mínima, ignorando o `jobArea` — que a
própria documentação da classe declara intocável: *"**The job area never drops**: it is the only part
of this bar that can be the reason the program is slow, and hiding it in a small window would hide the
cancel button with it."* Quando `width - forJob < 0`, o `Math.max(0, ...)` zera a mensagem (o "piso"
prometido some) e o `jobArea` recebe `width - GAP`, menos que sua largura preferida; como ele é um
`FlowLayout` (linha 95), o último componente — o botão Cancelar — quebra para uma segunda linha, fora
da altura visível da barra.

Com `jobName` + barra de 160 px + botão, a largura preferida do `jobArea` fica em torno de 400 px; a
`MainWindow` não define `setMinimumSize`, então a janela pode ser estreitada bem abaixo disso.

**Consequência:** justo enquanto um trabalho longo roda — o único momento em que essa área existe — uma
janela estreita esconde o botão que o interrompe e apaga a mensagem. As duas promessas escritas no
cabeçalho da classe quebram no mesmo pixel.

**Correção:** `minimumLayoutSize` deve devolver `MESSAGE_FLOOR + forJob` (calculado como em
`layoutContainer`); e o `jobArea` deve receber `Math.max(preferido, disponível)` para nunca quebrar
linha, ainda que transborde à direita.

**Tentei refutar:** refiz a aritmética do caso largo e ela fecha **exata** — `jobArea.x = edge.left +
width - preferido` e `jobArea.width = preferido`, apesar do `+ GAP` no x e do `- GAP` na largura, que
se cancelam com o `GAP` embutido em `forJob`. Cheguei a considerar esse par de `GAP` um defeito e o
derrubei com a conta. O problema é só o caso estreito, e ele é real porque `minimumLayoutSize` mente
para o `BorderLayout` da janela.

### A7b-13 — A janela de séries grava mesmo quando nada foi editado, e a gravação apaga o que ela não soube ler

**Onde:** `ui/series/SeriesWindow.java:404` chamado de `:180` e `:149`

**Trecho:**
```java
    private void save() {
        if (editing != null) {
            Segmentation.set(editing, model.segments);

            onChanged.run();
        }
    }
```

**Problema:** `save()` roda ao fechar a janela e a cada troca do combo, com ou sem edição. E
`model.segments` veio de `Segmentation.of` (`Segmentation.java:105`), que **descarta** silenciosamente
qualquer entrada com data malformada:

```java
            } catch (DateTimeParseException | IllegalArgumentException e) {
                // A hand-edited file. The entry is skipped rather than the whole
                // segmentation refused ...
                continue;
            }
```

Como `Segmentation.set` apaga tudo com o prefixo da série antes de reescrever, o ciclo
ler → descartar → regravar torna definitiva no disco uma perda que era só de leitura.

**Consequência:** abrir **Ferramentas → Séries**, olhar, e fechar — sem tocar em nada — apaga do
arquivo qualquer segmento cuja data não parseou. O `workspace.properties` é editável à mão por
desenho, então o caso não é teórico.

**Correção:** só gravar quando houver edição (uma marca posta por `model.add/remove/replace` e pelo
checkbox), o que também resolve metade de A7b-6.

**Tentei refutar:** verifiquei se `Segmentation.of` preservaria a entrada ruim em algum lugar — o
`continue` a descarta e o índice segue; nada guarda o texto original. Verifiquei se `set` faria fusão
em vez de substituição: `removeStartingWith(PREFIX + series + ".")` na linha 136.

### A7b-14 — Sem série nenhuma no disco, a janela grava segmentos sob a chave literal "null"

**Onde:** `ui/series/SeriesWindow.java:388`

**Trecho:**
```java
    private void load() {
        editing = String.valueOf(series.getSelectedItem());
```

**Problema:** com `Segmentable.keys()` vazio — máquina sem pasta de dados, que é um estado que a
aplicação trata explicitamente em outros lugares — `getSelectedItem()` devolve `null` e
`String.valueOf` o converte na **string** `"null"`. O guarda de `save()`, `if (editing != null)`,
nunca dispara, porque `"null"` não é nulo.

**Consequência:** `Segmentation.set("null", ...)` e `Segmentation.segmentsOnly("null")` escrevem
chaves `segment.null.*` no workspace do usuário, que ficam lá para sempre. E o rótulo da janela mostra
a contagem de uma série chamada "null".

**Correção:** `Object picked = series.getSelectedItem(); editing = picked == null ? null :
String.valueOf(picked);` — e desabilitar os botões quando `editing == null`.

**Tentei refutar:** verifiquei se o combo garantiria pelo menos um item: `Segmentable.keys()` (linha
66) parte de `SeriesCatalog.names()` e só acrescenta fontes de ticks que existam; com a pasta de dados
ausente devolve lista vazia — o mesmo estado que `Navigator` trata com a folha
`navigator.noSeries`, prova de que o caso é previsto em outro lugar do código.

### A7b-15 — Depois de trocar o idioma, tudo que vai para a saída padrão cai numa janela destruída

**Onde:** `ui/shell/MainWindow.java:809` com `Launcher.java:99`

**Trecho:**
```java
    private void relaunch() {
        prepareToLeave();
        storeLayout();
        ...
        closeCharts();
        dispose();

        br.com.jorge.reis.endeavourneo.platform.Language.install();

        MainWindow fresh = new MainWindow(Messages.get("app.title"), jobs);

        fresh.setVisible(true);
    }
```
e, no arranque, a única captura:
```java
            // Order matters: capture standard output only once the console
            // exists, otherwise the first lines are lost.
            window.getConsole().captureStandardOutput();
```

**Problema:** `Console.captureStandardOutput` (`Console.java:90`) faz `System.setOut`/`setErr` para um
`PrintStream` que escreve **naquele** `JTextArea`. `relaunch()` descarta a janela inteira e constrói
outra sem refazer a captura. `System.out` continua apontando para o console da janela destruída.

**Consequência:** depois de trocar o idioma nas preferências, nada que a aplicação ou uma biblioteca
escreva em `System.out`/`System.err` volta a aparecer — inclusive rastros de exceção, que é
exatamente o que o console existe para mostrar. E o `PrintStream` estático segura para sempre o
`JTextArea`, o `Console` e, por ele, a `MainWindow` antiga: vazamento de uma janela inteira por troca
de idioma.

**Correção:** `relaunch()` deve chamar `fresh.getConsole().captureStandardOutput()` logo depois de
construir a janela — e o `Console` deve guardar os fluxos originais para poder devolvê-los ao ser
descartado.

**Tentei refutar:** verifiquei se `Console` teria um método de liberação — não tem; `captureStandardOutput`
não guarda o `System.out` anterior. Verifiquei se `Launcher` seria reexecutado na troca de idioma:
`relaunch` constrói a `MainWindow` diretamente, sem passar pelo `Launcher`.

### A7b-16 — Diálogos nunca são descartados, e o troca-tema percorre todos eles

**Onde:** `ui/settings/SettingsDialog.java:104`, `ui/series/SeriesWindow.java:190`,
`ui/series/SegmentDialog.java:246`, com `ui/settings/AppearancePage.java:123`

**Trecho:**
```java
    /** Opens the dialog and blocks until it is dismissed. */
    public static void show(Window owner, List<SettingsPage> pages) {
        new SettingsDialog(owner, pages).setVisible(true);
    }
```
```java
        // Every window, not just the dialog: the main window behind it, and any
        // panel that may later be torn off onto a second monitor.
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
        }
```

**Problema:** nenhum dos três diálogos define `setDefaultCloseOperation(DISPOSE_ON_CLOSE)`, e o padrão
do `JDialog` é `HIDE_ON_CLOSE`. Fechar pelo X apenas esconde; a janela permanece em
`Window.getWindows()` para sempre. `SegmentDialog` e `SettingsDialog` chamam `dispose()` nos botões,
mas não no X; `SeriesWindow` não chama em lugar nenhum.

**Consequência:** cada abertura das preferências, da janela de séries ou do diálogo de segmento fechada
pelo X deixa um diálogo vivo com toda a sua árvore de componentes. Depois de uma sessão longa, aplicar
um tema percorre dezenas de janelas invisíveis — `updateComponentTreeUI` em cada uma — e a troca de
tema fica progressivamente mais lenta, além do consumo de memória.

**Correção:** `setDefaultCloseOperation(DISPOSE_ON_CLOSE)` nos três. Em `SeriesWindow` o
`windowClosing` que grava continua disparando antes do descarte.

**Tentei refutar:** confirmei que `Window.getWindows()` só exclui janelas efetivamente descartadas —
uma janela escondida continua listada. Confirmei que `SegmentDialog` fechado pelo X ainda devolve
`Optional.empty()` corretamente (o campo `chosen` continua nulo), então o vazamento é o único efeito
ali.

### A7b-17 — Data invertida digitada no diálogo: o campo mostra uma coisa, o segmento salvo é outra

**Onde:** `ui/series/SegmentDialog.java:483` com `ui/series/RangeBar.java:139`

**Trecho:**
```java
    private void followDates() {
        ...
        echoing = true;

        try {
            range.setRange(days.size(), indexOf(first), indexOf(last));
        } finally {
            echoing = false;
        }

        describe();
    }
```
e o que `setRange` faz com a inversão:
```java
        if (to < from) {
            to = from;
        }
```

**Problema:** quando a data final digitada é anterior à inicial, o `RangeBar` colapsa o intervalo
(`to = from`), mas `followDates` não reescreve os `DatePicker` — o guarda `echoing` impede a volta, e
`describe()` só atualiza mapa, contadores e frase. O campo "até" continua exibindo a data antiga.
`current()` (linha 567) constrói o segmento a partir do `range`, não dos campos.

**Consequência:** o leitor digita 05/01/2026 em "até" com 10/03/2026 em "de", vê o campo "até" com a
data que digitou, e salva um segmento de **um** pregão. A frase e os contadores mostram o valor
colapsado, mas o campo — que é onde ele acabou de digitar e para onde está olhando — mostra o outro.

**Correção:** `followDates` deve reescrever os dois `DatePicker` a partir do `range` depois de
`setRange`, dentro do mesmo `echoing`, como `followHandles` já faz no sentido oposto.

**Tentei refutar:** verifiquei se `Segment` recusaria a inversão e daria um erro visível —
`Segment.java` lança `IllegalArgumentException` para `to.isBefore(from)`, mas nunca chega lá, porque o
`RangeBar` já colapsou; o resultado é silencioso, não uma exceção. Verifiquei se o botão ficaria
desabilitado nesse estado: `create.setEnabled(!clash || readOnly)` só olha sobreposição.

### A7b-18 — Teste de intervalo que passaria mesmo se `setRange` não fizesse nada

**Onde:** `src/test/java/.../ui/series/SegmentPickingTest.java:107`

**Trecho:**
```java
    @Test
    @DisplayName("as alcas nao se cruzam")
    void theHandlesDoNotCross() {
        RangeBar bar = bar();

        bar.setRange(10, 8, 3);

        assertTrue(bar.to() >= bar.from(),
                "the end went in front of the start, which is not a range");
    }
```

**Problema:** `bar()` já devolve a barra em `(2, 6)`, que satisfaz `to >= from`. Se `setRange` fosse um
`return` imediato, a asserção passaria. Mais importante: o teste não fixa **qual** dos dois lados cede
— o código colapsa o fim sobre o começo (`to = from`, resultado `(8, 8)`), e é justamente essa escolha
não fixada que produz A7b-17, onde o campo de data e o intervalo salvo divergem.

**Consequência:** a regra fica sem contrato escrito, e a consequência visível dela — o campo que não
acompanha — atravessou a suíte.

**Correção:** `assertEquals(8, bar.from()); assertEquals(8, bar.to());` — pinar os valores, não só a
relação entre eles.

**Tentei refutar:** confirmei que a mutação "remover o `if (to < from)`" **é** pega por este teste
(daria `to = 3 < from = 8`), então ele não é inútil; e que a mutação "`setRange` não faz nada" seria
pega por `neitherHandleLeavesTheSeries`. Por isso é MÉDIA e não ALTA: a suíte inteira cobre, o método
sozinho não — e a lacuna específica (qual lado cede) não é coberta por nenhum dos dois.

### A7b-19 — Com dez ou mais gráficos, a ordem de restauração é alfabética, não numérica

**Onde:** `ui/shell/MainWindow.java:301` com `platform/Settings.java:369`

**Trecho:**
```java
        for (String key : workspace.keysStartingWith("chart.open.")) {
```
e, do outro lado do contrato:
```java
    /** @return every key starting with that prefix, sorted */
    public List<String> keysStartingWith(String prefix) {
        ...
        Collections.sort(found);
```

**Problema:** a ordenação é lexicográfica sobre chaves com índice sem preenchimento:
`chart.open.10.series` vem antes de `chart.open.2.series`. `rememberCharts` (linha 269) grava com
`at++` puro.

**Consequência:** com dez ou mais gráficos abertos, eles voltam em outra ordem. Como o sufixo "(2)"
é atribuído pela ordem de abertura (`uniqueTitle`, linha 485) e a geometria de cada janela é guardada
pelo título, cada janela reabre com a posição e o tamanho de outra — que é exatamente o dano que o
javadoc de `uniqueTitle` diz querer evitar.

**Correção:** gravar o índice com preenchimento (`String.format("%03d", at)`) ou ordenar
numericamente ao restaurar.

**Tentei refutar:** verifiquei se `keysStartingWith` ordenaria numericamente — é `Collections.sort`
sobre `String`. Verifiquei se dez gráficos seriam absurdo para esta aplicação: `tileCharts` e
`tileBounds` são escritos e testados para nove, e o teste vai até `count = 9`; dez está a um gráfico
de distância.

### A7b-20 — A árvore é reconstruída, com I/O, dentro de manipuladores de evento

**Onde:** `ui/shell/Navigator.java:157` chamado de `ui/shell/MainWindow.java:833`

**Trecho:**
```java
    private void openSeries() {
        br.com.jorge.reis.endeavourneo.ui.series.SeriesWindow.open(this,
                () -> navigator.setModel(Navigator.treeModel()));
    }
```
e o que `treeModel()` faz por instrumento (`Navigator.java:340`):
```java
        for (br.com.jorge.reis.endeavourneo.domain.market.TickSource source
                : br.com.jorge.reis.endeavourneo.domain.market.TickSource.values()) {
            List<java.time.LocalDate> days =
                    new br.com.jorge.reis.endeavourneo.domain.market.TickLibrary(
                            folder, instrument, source).exported();
```

**Problema:** `treeModel()` lista diretórios (`Files.isDirectory`, `TickLibrary.exported()`) e lê o
workspace uma vez por série (`Segmentation.segmentsOnly`, `Segmentation.of`). Ela roda no construtor do
`Navigator`, na EDT, e de novo a **cada** `onChanged` da janela de séries — isto é, a cada segmento
adicionado, removido, editado, e a cada clique no checkbox de trancar.

**Consequência:** cada edição de segmento congela a interface pelo tempo de varrer as pastas de ticks
de todos os instrumentos. Com uma pasta de ticks grande (o projeto fala em 90 MB por sessão) e um
disco de rede, é perceptível. `TickLibrary` também é criada e nunca fechada aqui — compare com
`Segmentable.daysOfTicks` (`Segmentable.java:131`), que usa `try/finally` com `library.close()`.

**Correção:** fechar a `TickLibrary` (`try/finally`, como o `Segmentable` já faz) e reconstruir a
árvore fora da EDT, ou pelo menos atualizar só o ramo da série alterada em vez do modelo inteiro.

**Tentei refutar:** verifiquei se `TickLibrary.exported()` seria uma leitura barata — o comentário de
`Segmentable.java:136` afirma que é só listagem de diretório, o que é verdade; o custo aqui é o número
de chamadas (instrumentos × fontes × cada edição), não cada uma isolada. Isso é o que o mantém em
MÉDIA e não em ALTA. O `close()` ausente, porém, é objetivo.

### A7b-21 — Os testes escrevem nas preferências e no workspace reais do usuário

**Onde:** `src/test/java/.../ui/shell/CollapsiblePaneTest.java:35` e
`src/test/java/.../ui/shell/MainWindowTest.java:257`

**Trecho:**
```java
    private static final String KEY = "test-pane-" + System.nanoTime();
```
```java
            List<String> saved = br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
                    .keysStartingWith("chart.open.").stream()
```

**Problema:** `CollapsiblePane` grava em `Preferences.userNodeForPackage` (linha 56) e o teste usa uma
chave nova a cada execução, que nunca é removida: o nó de preferências do usuário cresce em três
chaves por execução da suíte, para sempre. `MainWindowTest` opera sobre `Settings.workspace()` real —
abre gráficos e afirma sobre `chart.open.*` —, ou seja, **destrói a lista de gráficos a reabrir do
usuário toda vez que a suíte roda**. `NavigatorTreeTest` mostra como se faz certo: `isolateSegments`
(linha 108) aponta a `Segmentation` para um `@TempDir`.

**Consequência:** rodar os testes muda o estado da aplicação do desenvolvedor e polui o registro. E o
próprio `MainWindowTest` fica dependente de estado externo.

**Correção:** dar ao `Settings.workspace()` e ao `CollapsiblePane` o mesmo gancho de teste que a
`Segmentation` já tem (`useForTest` / `stopUsingTestStore`), e usá-lo em `@BeforeEach`.

**Tentei refutar:** verifiquei se `Settings.workspace()` já teria isolamento — a `Segmentation` tem
(`useForTest`, linha 96), mas `MainWindowTest` chama `Settings.workspace()` diretamente, que é o
arquivo real. Verifiquei se `CollapsiblePaneTest` limparia as chaves em `@AfterEach` — não há
`@AfterEach` no arquivo.

### A7b-22 — Cada teste da janela principal deixa um `restoreCharts` na fila, para uma janela já descartada

**Onde:** `ui/shell/MainWindow.java:200` com `src/test/java/.../MainWindowTest.java:269`

**Trecho:**
```java
        javax.swing.SwingUtilities.invokeLater(this::restoreCharts);
```
```java
            SwingUtilities.invokeAndWait(() -> {
                MainWindow window = new MainWindow("test", jobs);

                try {
                    test.accept(window);
                } finally {
                    window.dispose();
                }
            });
```

**Problema:** o construtor agenda `restoreCharts` para depois; o `invokeAndWait` do teste devolve
antes disso, com a janela já descartada. O `restoreCharts` enfileirado então roda sobre uma janela
morta, abre gráficos num `JDesktopPane` sem tamanho e reescreve `chart.open.*` (o mesmo workspace real
do achado anterior), depois do fim do teste.

**Consequência:** trabalho e escritas fora do escopo do teste, com a ordem entre testes dependendo da
fila da EDT. É um candidato natural a teste intermitente — e o próprio repositório já registra, em
dois comentários de `NavigatorTreeTest` (linhas 336-339 e 407-411), uma falha "uma execução em quatro"
cuja causa "nunca foi encontrada", numa suíte que "também abre janelas reais".

**Correção:** drenar a fila antes de descartar (um segundo `invokeAndWait(() -> { })` dentro do bloco),
ou dar à `MainWindow` uma forma de o teste construir sem a restauração automática.

**Tentei refutar:** verifiquei se `dispose()` cancelaria o `invokeLater` — não cancela; a `Runnable`
já está na fila e roda. Verifiquei se `restoreCharts` seria inofensiva com o workspace vazio: ela
termina em `rememberCharts()` (linha 337), que grava de qualquer forma.

### A7b-23 — "a" escrito em português dentro do código

**Onde:** `ui/shell/Navigator.java:269`

**Trecho:**
```java
    private static String labelOf(Segment segment) {
        return segment.name() + "  ·  " + segment.from()
                + (segment.isOpenEnded()
                        ? "  " + Messages.get("series.onwards") : "  a  " + segment.to());
    }
```

**Problema:** o ramo aberto vai ao bundle (`series.onwards`) e o ramo fechado tem a preposição
**"a"** escrita no Java. É texto de interface fora do bundle, na única linha da árvore que descreve um
segmento fechado.

**Consequência:** em inglês, a árvore mostra `treino · 2020-09-01 a 2023-12-29`.

**Correção:** uma chave `series.range` com `{0} a {1}` / `{0} to {1}`, formatada por
`Messages.get`.

**Tentei refutar:** conferi as 88 chaves usadas nesta área contra os dois bundles — todas existem,
inclusive as montadas por concatenação (`navigator.scale.*`, `navigator.role.*`,
`navigator.tickSource.*`, `settings.language.*`, `theme.*`). Este literal é a única exceção de texto
de interface que encontrei em toda a área, junto com o `"0,0%"` de A7b-31.

---

## Achados BAIXA

### A7b-24 — Javadoc pregado no membro errado, seis vezes
`MainWindow.java:215-245` — três blocos empilhados (um documentando `open`, outro `rememberCharts`,
outro `prepareToLeave`) todos antes de `prepareToLeave`; `MainWindow.java:133-151` — o bloco que
descreve o mapa `charts` está pregado no campo `restoring`; `MainWindow.java:524-542` — o javadoc de
`openCharts` está sobre `chartNamed`, e o de `closeCharts` sobre o campo `leaving`, declarado no meio
dos métodos; `MainWindow.java:773-790` — o javadoc de `openPreferences` está sobre `openReplay`;
`Navigator.java:115-131` — dois blocos, o primeiro com um `@param name` que não existe na assinatura
de `nameOf`; `Icons.java:47-51` — o javadoc do glifo "arrange the windows" (que é `tile`) está sobre
`candle`. A ferramenta de javadoc vai publicar todos eles no lugar errado.

### A7b-25 — Código morto
`MainWindow.uniqueTitleOf` (linha 373) — sem chamador algum no `src`.
`SeriesWindow.open(Window)` (linha 185) — sobrecarga sem chamador; só a de dois argumentos é usada.
`SeriesWindow.ZONE` (linha 76) e os imports `PriceSeries` (20), `SeriesCatalog` (24), `IOException`
(31), `Instant` (32) — nenhum é referenciado no corpo da classe; confirmei por busca linha a linha.
`MainWindow.seriesFor` recebe `String title` (linha 352) e nunca o usa.

### A7b-26 — Cores fixas onde a área toda lê o tema
`MainWindow.java:168` — `desktop.setBackground(java.awt.Color.DARK_GRAY)`: cinza escuro fixo atrás dos
gráficos, inclusive no tema claro.
`SeriesWindow.java:160` — `warning.setForeground(new java.awt.Color(0xB0, 0x6A, 0x2E))`: o aviso de
sobreposição tem cor fixa, enquanto `SeriesColors.clash()` existe ao lado e alterna com o tema.
`SeriesMap.java:207` — `g.setColor(Color.WHITE)` para o nome do segmento dentro do bloco: as cores de
`SeriesColors.TAKEN` são escurecidas no tema noturno (linha 111) mas o texto continua branco puro, o
que já é o contraste mais frágil do par.

### A7b-27 — Coleção mutável escapando pelo getter
`SeriesMap.java:293`:
```java
    /** @return the sessions this map is showing */
    List<LocalDate> days() {
        return days;
    }
```
Devolve a lista interna. O único chamador hoje é o teste, mas ele poderia limpá-la. `List.copyOf` ou
`Collections.unmodifiableList`.

### A7b-28 — `record` sem invariante no construtor compacto
`Icons.java:173` — `private record Painted(int size, Glyph glyph) implements Icon`. Nada valida
`size > 0` nem `glyph != null`. `Icons.tile(0)` devolve um ícone que faz
`graphics.create(x, y, 0, 0)`; um `glyph` nulo estoura só na primeira pintura, longe da chamada.
A convenção da casa pede a validação no construtor compacto.

### A7b-29 — Estado empacotado num inteiro para detectar mudança
`RangeBar.java:222`:
```java
        int before = from * 100000 + to;
```
Funciona para as ~1.500 sessões de hoje e passa a colidir (ou a transbordar o `int`) para índices
maiores. Comparar os dois campos é mais curto e não tem limite: `int wasFrom = from, wasTo = to; ...
if (wasFrom != from || wasTo != to)`.

### A7b-30 — "0,0%" escrito no código, e formatação dependente do locale padrão
`SegmentDialog.java:559`:
```java
    private String share(int sessions) {
        if (days.isEmpty()) {
            return "0,0%";
        }

        return String.format("%.1f%%", 100.0 * sessions / days.size());
    }
```
A vírgula decimal está no literal; o `String.format` sem `Locale` explícito usa o padrão da máquina, de
modo que os dois ramos podem discordar entre si (`0,0%` fixo contra `0.0%` formatado) numa máquina com
locale inglês. O mesmo vale para os `String.format("%,d", ...)` de `SeriesWindow.java:397` e
`SegmentDialog.java:534`, que seguem o locale da JVM e não o idioma escolhido nas preferências.

### A7b-31 — Comentário que conta cinco onde há seis
`SegmentDialog.java:372`: *"Five cells of equal width"*, sobre um método que percorre
`SUMMARY.length`, e `SUMMARY` (linha 138) tem seis entradas — anos, meses, dias, calendário, pregões,
fatia. O comentário narra o código **e** erra a contagem.

### A7b-32 — O console arrasta o cursor para o fim a cada linha
`Console.java:73`: `text.setCaretPosition(text.getDocument().getLength())` em toda escrita. Quem
rolou para cima para ler uma exceção é levado de volta ao fim na próxima linha que chegar — e, com a
saída padrão capturada, elas chegam sozinhas. O comum é só rolar quando o cursor já estava no fim.

### A7b-33 — O painel dobrável não tem teclado, e engole o foco ao dobrar
`CollapsiblePane.java:101` — a única forma de dobrar é `mousePressed` sobre o cabeçalho; o cabeçalho
não é focável e não há ação de teclado. E `apply()` (linha 143) faz `content.setVisible(false)` sem
verificar se o foco está dentro do conteúdo: dobrar o console com o cursor dentro dele deixa a janela
sem dono de foco. Um `setFocusable(true)` no cabeçalho com uma ação de ESPAÇO/ENTER, e um
`requestFocusInWindow()` no cabeçalho antes de esconder, resolvem os dois.

### A7b-34 — Botões habilitados que não fazem nada quando a série não lê
`SeriesWindow.java:241` habilita **Ver**, **Editar** e **Remover** com qualquer linha selecionada, mas
`openSelected` (linha 311) sai calado quando `days.isEmpty()`:
```java
        if (row < 0 || days.isEmpty()) {
            return;
        }
```
O comentário três linhas acima dos botões diz: *"A button that is always enabled and sometimes does
nothing teaches the reader to distrust every button beside it."* Com uma série ilegível — o caso que
a janela trata explicitamente com `series.unreadable` — é isso que acontece com dois dos quatro botões.

### A7b-35 — `IOException` engolida sem deixar rastro
`Segmentable.java:126`:
```java
        } catch (IOException e) {
            return new TreeSet<>();
        }
```
A escolha de devolver vazio é defensável e está documentada, mas a mensagem da exceção se perde por
completo: nem console, nem `status.say`, nada. A janela dirá "ilegível" sem dizer por quê — permissão,
arquivo truncado, disco de rede fora do ar. Uma linha em `console.write` (como
`MainWindow.seriesFor` faz) custa nada e é a diferença entre diagnosticar e adivinhar.

---

## `SeriesMap` e `RangeBar` decimam?

**Sim, e da melhor forma: elas nunca chegam a ver uma barra.** Este é o achado limpo importante, e é o
modelo para corrigir as quatro ocorrências já registradas (`ChartCanvas:1206`, `ChartHeader:359`,
`LineStyle:54`, `StudyStack:121`).

**Como.** O eixo dos dois componentes é o **índice de pregão**, não o tempo nem o índice de barra:

- `SeriesMap.java:77` guarda `private final transient List<LocalDate> days` — uma entrada por pregão.
  Para a fonte de seis anos são 1.494 entradas contra 824.881 barras: uma redução de **552×**, feita
  uma vez, fora do desenho.
- `SeriesMap.java:115` converte índice em pixel por aritmética pura,
  `SIDE + (double) index / days.size() * width`, sem tocar em dado nenhum.
- `SeriesMap.java:126` localiza uma data por **busca binária** escrita à mão (`while (low < high)`),
  O(log n). `paintSegments` (linha 181) é portanto O(segmentos × log dias) — na prática, três a seis
  buscas binárias por pintura, e **nenhum** laço sobre a série.
- `RangeBar.java:71` guarda apenas `int sessions`, `int from`, `int to`. Não tem lista nenhuma.
  `edgeOf` (linha 185) e `at` (linha 198) são uma multiplicação e uma divisão. `paintComponent` (linha
  277) desenha dois retângulos arredondados e dois círculos — custo constante, independente do tamanho
  da série.
- E os dois compartilham a constante `SIDE` (`SeriesMap.java:74`, `RangeBar.java:68`) para que as duas
  escalas coincidam ao pixel — com `SegmentPickingTest.bothMeasureTheSame` (linha 168) verificando
  `map.edgeOf(i) == bar.edgeOf(i)` para todo i. É a única promessa geométrica desta área que tem
  teste com dentes.

**A única varredura por pintura** é `SeriesMap.paintYears` (linha 246), que percorre `days` inteiro
para achar as viradas de ano — O(1.494) por pintura, com alocação apenas nas ~6 viradas
(`String.valueOf(year)`). É aceitável na escala de pregões e seria inaceitável na de barras; vale
anotar como dívida se um dia a lista virar tickagem.

**E o alerta.** A decimação está certa no **desenho** e ausente na **aquisição**: a lista de dias é
produzida por `Segmentable.sessionsOf` → `Sessions.of`, que caminha as 824.881 barras na EDT toda vez
que o combo muda (A7b-3). O padrão a copiar é, então:

> índice de pregão como eixo + busca binária para localizar + aritmética para posicionar +
> **a lista de pregões memorizada, calculada uma vez fora da EDT** (o que `ReplayFeed.KNOWN` já faz e
> `Segmentable` ainda não).

---

## O que está LIMPO

**`Console` tem teto, e escreve na EDT.** `Console.java:50-53` — `LINE_CAP = 5_000`,
`TRIM_BLOCK = 1_000`, com `trim()` (linha 113) descartando por bloco e caindo em `setText("")` se o
`getLineEndOffset` falhar. Todas as escritas passam por `onEdt` (linha 125). **Tentei derrubar** a
suspeita de corrida em `captureStandardOutput`: o `StringBuilder pending` da `OutputStream` anônima
não é sincronizado, mas todo caminho até ele passa por `PrintStream.write`/`writeln`, que são
`synchronized (this)` no JDK, e `OutputStream.write(byte[],int,int)` se resolve em chamadas a
`write(int)` sob o mesmo bloqueio — serializado, com happens-before. Não é achado. Também descartei a
recursão infinita (uma exceção na EDT indo para `System.err` capturado): o retorno passa por
`invokeLater`, não reentra.

**A cobertura de bundle desta área é total.** Extraí as 88 chaves usadas em
`Messages.get/orElse/mnemonic` nos 18 arquivos e conferi contra `messages.properties` e
`messages_pt_BR.properties`. As quatro "faltantes" do primeiro passe eram prefixos de concatenação;
conferi cada uma pelos valores possíveis: `navigator.scale.{1s,5s,1m,5m,15m,1h,1d}`,
`navigator.role.{source,export,search,test,merged}`, `navigator.tickSource.{metatrader,profit}`,
`settings.language.{system,pt-BR,en}` e `theme.{light,dark,night}` com seus `.hint` — **todas
presentes nos dois arquivos**. As três primeiras famílias ainda usam `Messages.orElse` com fallback,
o que é a decisão certa para texto vindo de dado. O único literal de interface que sobrou é o `"  a  "`
de A7b-23 e o `"0,0%"` de A7b-30.

**O `StatusBar` marshaliza sozinho, e o `JobService` também.** `say`, `chart` e `noChart` passam por
`onEdt` (`StatusBar.java:226`). **Tentei derrubar** o `refresh()`, que toca componentes sem se
marshalizar: ele só é chamado de `bind` (na construção, na EDT) e do callback do `JobService`; conferi
`JobService.java:366-380` — `notifyListeners` embrulha tudo em `onEdt`. O contrato está honrado dos
dois lados.

**A aritmética larga do `Strip` fecha exata.** Suspeitei de um `GAP` a mais: `jobArea` é posicionado
em `at + GAP` e recebe largura `parent.getWidth() - edge.right - at - GAP`, com `forJob` já contendo
um `GAP`. Refiz a conta: `at = edge.left + width - preferido - 10`, logo `x = edge.left + width -
preferido` e `largura = preferido`. Os dois `GAP` se cancelam com o de `forJob`; o `jobArea` recebe
exatamente sua largura preferida e encosta na borda direita. **Não é achado** — o único problema é o
caso estreito (A7b-12).

**Cancelar cancela mesmo, nas três páginas de preferências que têm estado.** Conferi o ciclo inteiro:
`SettingsDialog` chama `page.load()` no construtor (linha 85) e `page.apply()` só em Aplicar e OK
(linhas 163 e 167); Cancelar e ESC apenas fecham (linhas 160 e 194-202). `ChartPage.load` (linha 109),
`ReplayPage.load` (linha 94), `GeneralPage.load` (linha 128) e `AppearancePage.load` (linha 103) leem
o estado atual a cada abertura, não uma vez na construção — e `MainWindow.openPreferences` (linha 840)
constrói páginas novas a cada abertura, de modo que o estado não sobrevive a um Cancelar. **Tentei
derrubar** o `JSpinner` do `ReplayPage`, que é a armadilha clássica (texto digitado e não confirmado
sendo descartado por `getValue()`): o `JFormattedTextField` interno tem `focusLostBehavior` =
`COMMIT_OR_REVERT` por padrão e os botões do diálogo são focáveis, então clicar em OK confirma a
edição; e ENTER dentro do campo é consumido por `notify-field-accept`, que também confirma. **Tentei
derrubar** o `load()` do `ReplayPage` com valor fora da faixa do `SpinnerNumberModel` (que lançaria
`IllegalArgumentException` e impediria o diálogo de abrir): `ReplayPreferences.historyDays()` e
`windowDays()` (linhas 73 e 82) já limitam na leitura, com `clamp(…, MAX_…)`. Nenhum dos dois vira
achado. A ressalva que fica é de cobertura, não de defeito: **não há um único teste** sobre
`ui/settings`.

**O `SegmentDialog` cancelado não aplica nada.** `chosen` só é escrito no ouvinte do botão de
confirmar (linha 430), e apenas se `!readOnly`; Cancelar (linha 429) e o X apenas fecham, e
`show` devolve `Optional.ofNullable(dialog.chosen)` (linha 251). Os chamadores usam `ifPresent`
(`SeriesWindow.java:278` e `:328`), então cancelar não toca no modelo. O modo VIEW é bloqueado em
quatro pontos (linhas 185-189) e o `create` de VIEW nunca escreve. **Tentei derrubar** com o
segmento fora da faixa: `indexOf` (linha 458) limita com `Math.min(days.size() - 1, Math.max(0, ...))`
e `show` recusa a série vazia antes de construir (linha 242). Série vazia e segmento fora da faixa
estão cobertos. O que sobra é A7b-17, que é o caminho inverso (data digitada), não o cancelamento.

**A recursão entre alças e datas está fechada.** O par `followHandles`/`followDates` com o campo
`echoing` (`SegmentDialog.java:99, 465, 483`) faz o que o javadoc promete; segui os dois sentidos e
não há caminho que escape do guarda. `describe()` fica fora do `try/finally`, o que é correto: ele
não reescreve nem as alças nem as datas.

**O `Icons` não devolve `null` em lugar nenhum**, e trata a ausência de cor do tema com fallback
(`Icons.java:193-203`). Não há cache — cada chamada cria um `record` novo —, o que descarta o risco de
cache crescendo sem limite que a pauta levantou; o custo é uma alocação por chamada, e as chamadas são
de montagem de barra de ferramentas, não de pintura por barra. `SeriesColors.taken` (linha 102) usa
`Math.floorMod`, então índice negativo ou acima de seis não estoura e as cores repetem de seis em
seis, como documentado — **tentei derrubar** procurando colisão por índice e a repetição é
deliberada e explicada.

**As duas classes utilitárias recusam instanciação** — `Icons.java:43`, `SeriesColors.java:52`,
`Segmentable.java:61` — todas com `throw new AssertionError`, como a convenção pede.

**Nenhum import de `ui/` no `domain/`**, e nenhuma leitura do futuro nesta área: o único cálculo
temporal é `Sessions.of`, que caminha para frente com comparação ao dia anterior, e
`SeriesMap.indexOf`/`lastIndexOf`, que são busca binária sobre dias já conhecidos.

**Sobre o fuso.** As três derivações de `LocalDate` a partir de epoch com `ZoneId.systemDefault()` que
tocam esta área ficam em `domain/market/Sessions.java:58` (fora da minha área) e nas duas chamadas que
partem daqui — `MainWindow.java:438`, `SegmentedSeries.of(..., ZoneId.systemDefault())`, e
`Segmentable.java:125`, `Sessions.of(bars)`, que usa a sobrecarga de zona padrão. As duas são
**consistentes entre si**: a janela de séries conta pregões no mesmo fuso em que o gráfico corta o
segmento, então um segmento não muda de tamanho entre a janela onde foi escolhido e o gráfico onde é
usado. O que registro como vestígio é o campo `ZoneId ZONE` de `SeriesWindow.java:76`, declarado e
nunca usado (A7b-25): alguém pretendeu tornar o fuso explícito aqui e não terminou. Se o projeto vier
a fixar o fuso do pregão, estes três pontos têm de mudar juntos.
