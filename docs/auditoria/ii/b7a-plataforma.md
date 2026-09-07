# B7a — a plataforma

Auditoria II, 07/09/2026.

## O que foi lido

Todas as linhas de `src/main/java/br/com/jorge/reis/endeavourneo/platform/` e do
`Launcher`. Conferido com `wc -l`: **10 arquivos, 2.731 linhas**.

| arquivo | linhas |
|---|---|
| `platform/SeriesCatalog.java` | 795 |
| `platform/JobService.java` | 465 |
| `platform/Settings.java` | 406 |
| `platform/Segmentation.java` | 273 |
| `platform/Appearance.java` | 200 |
| `platform/Messages.java` | 160 |
| `Launcher.java` | 154 |
| `platform/Theme.java` | 118 |
| `platform/Language.java` | 108 |
| `platform/Progress.java` | 52 |
| **total** | **2.731** |

Lidos como apoio, para confirmar ou derrubar achados, e **não** auditados:
`ui/replay/ReplayFeed.java`, `ui/shell/MainWindow.java`,
`ui/shell/StatusBar.java`, `ui/chart/ChartPreferences.java`,
`domain/market/MarketFile.java`, `domain/market/Sessions.java`,
`architecture/LayerBoundaryTest.java`, os oito testes de
`src/test/.../platform/` e `pom.xml`.

**Resumo:** 3 ALTA, 13 MÉDIA, 8 BAIXA.

---

# ALTA

### B7a-1. `openUntil` devolve a série inteira e ignora o `upTo` quando ela está no cache

`platform/SeriesCatalog.java:719-744`

```java
    public static Optional<PriceSeries> openUntil(String name, long upTo, int bars)
            throws IOException {
        if (bars <= 0) {
            return open(name);
        }

        SoftReference<PriceSeries> held = LOADED.get(name);
        PriceSeries whole = held == null ? null : held.get();

        if (whole != null && whole.size() <= bars) {
            return Optional.of(whole);
        }
```

**Problema.** O atalho foi copiado de `open(String, int)`, onde é correto — lá a
janela é ancorada no FIM do arquivo, e devolver o arquivo inteiro quando ele é
menor que a janela dá exatamente o mesmo resultado. Aqui a janela é ancorada em
`upTo`, e o atalho **descarta o argumento**: devolve todas as barras do arquivo,
inclusive as posteriores ao fim do segmento.

Não é hipotético. O `LOADED` é preenchido pelo `open(String)` sem janela, e o
próprio `Launcher` chama esse método para **todas** as séries no arranque:

`Launcher.java:122-126`

```java
            for (String name : br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.names()) {
                try {
                    br.com.jorge.reis.endeavourneo.domain.market.Sessions.of(
                            br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.open(name)
                                    .orElse(null));
```

E o único chamador de `openUntil` é o gráfico de segmento, com `bars` vindo da
preferência:

`ui/shell/MainWindow.java:522-524`

```java
                    : SeriesCatalog.openUntil(name, endOf(segment),
                            br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.window());
```

`ChartPreferences.WINDOW_DEFAULT = 100_000` e `MOST_WINDOW = 500_000`
(`ui/chart/ChartPreferences.java:172,179`). Qualquer série com menos barras que
a janela dispara o atalho: uma exportação de um ano em 5m (~20 mil barras), uma
série curta importada, ou a fonte de 825 mil barras com a janela levantada para
500 mil — não a fonte com o padrão, mas todas as outras.

**Consequência.** Leitura do futuro, no sentido exato que este projeto usa. Um
gráfico do segmento **busca** desenha também os anos de **teste**, em silêncio,
e o console confirma um número de barras maior sem que nada ligue os dois
fatos. É precisamente o erro que `Segmentation.segmentsOnly` existe para
impedir — `platform/Segmentation.java:199-215` chama isso de "the cheapest
defence there is against the expensive mistake: looking at test data without
noticing". A defesa é contornada por baixo. Pior: como o cache é
`SoftReference`, o defeito aparece e desaparece conforme a pressão de memória.

**Correção.** Remover o atalho de `openUntil`, ou condicioná-lo a
`whole.timeAt(whole.size() - 1) <= upTo`. O caminho lento já está correto
(`Math.max(0, end - bars)`, `Math.min(bars, end)`) e custa vinte seeks.

**Tentei refutar assim.** (a) Procurei um corte no chamador: `MainWindow:507-535`
passa a série adiante sem filtrar por data. (b) Procurei o teste que deveria
pegar: `SeriesCatalogTest:305-344` (`aWindowCanBeAnchoredAtASegmentsEnd`) usa
arquivo de 1.000 barras com `bars = 100`, então `whole.size() <= bars` é falso e
o `LOADED` está vazio — **o teste nunca entra neste ramo**, e passaria com o
atalho inteiro deletado ou mantido. (c) Verifiquei se o `LOADED` poderia estar
sempre vazio na prática: não, o `Launcher` o preenche para todas as séries antes
da primeira janela. Não caiu.

---

### B7a-2. Arquivo de configuração ilegível é esvaziado e depois SOBRESCRITO — perda total

`platform/Settings.java:146-168`

```java
        try (InputStream in = Files.newInputStream(file);
                Reader reader = new InputStreamReader(in, decoder)) {
            values.load(reader);
        } catch (IOException e) {
            // Unreadable settings are the same as none: the application opens
            // with its defaults rather than refusing to open at all. Losing a
            // theme is a smaller harm than losing the program.
            values.clear();

            return;
        }
```

**Problema.** O comentário descreve uma degradação suave e ela não é suave. O
`load()` só corre no construtor, o mapa fica vazio, e **o próximo `put` grava o
arquivo inteiro** a partir do mapa vazio:

`platform/Settings.java:341-349`

```java
    public void put(String key, String value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.setProperty(key, value);
        }

        save();
    }
```

`save()` abre com `Files.newBufferedWriter(file, StandardCharsets.UTF_8)` —
`CREATE`+`TRUNCATE_EXISTING` por omissão (`Settings.java:278`). O arquivo do
leitor é truncado e reescrito com o único par que acabou de ser posto.

E não é preciso esperar por uma ação do leitor: o `Launcher` grava logo na
segunda instrução útil, sempre, mesmo quando nada mudou:

`Launcher.java:74`

```java
        theme.remember();
```

O mesmo vale, sem exceção nenhuma, para o caminho em que o arquivo existe mas
`Files.isRegularFile` responde `false` por falta de permissão
(`Settings.java:147`) — aí nem sequer há `IOException` para registar.

**Consequência.** Perda de dado. Uma leitura que falhe por qualquer motivo
transitório — outro processo com o arquivo aberto sem partilha, antivírus a
varrer, home em rede indisponível por um segundo, um segundo Endeavour aberto —
apaga permanentemente **todas** as escolhas do leitor, e o `workspace.properties`
inteiro: gráficos abertos, layouts, divisórias, e os `segments.*` e
`segmentsOnly.*` da `Segmentation`, que é onde vive a fronteira busca/teste.

**Correção.** Distinguir "não há arquivo" de "não consegui ler". No segundo
caso, marcar a instância como não-gravável e recusar `save()` até que alguém
diga o contrário — ou renomear o arquivo para `.corrupt-<data>` antes de
qualquer escrita. E dizer o caminho do arquivo em vez de engolir a exceção.

**Tentei refutar assim.** (a) Procurei um guarda no `save()` que impedisse
gravar sobre um `load()` falhado: `Settings.java:270-297` não tem nenhum, só o
`try/catch` da própria escrita. (b) Procurei se `SETTINGS` e `WORKSPACE` são
recarregados alguma vez depois do construtor: `load()` é privado e só chamado em
`Settings.java:113`. (c) Procurei o teste: `SettingsTest` tem
`"a missing file is the same as an empty one"` (linha 123), que cobre o arquivo
ausente — o caso benigno — e nenhum para o arquivo ilegível. Não caiu.

---

### B7a-3. Um `\uXXXX` malformado impede a aplicação de arrancar, ao contrário do que o javadoc promete

`platform/Settings.java:155-165`

```java
        try (InputStream in = Files.newInputStream(file);
                Reader reader = new InputStreamReader(in, decoder)) {
            values.load(reader);
        } catch (IOException e) {
```

**Problema.** `Properties.load(Reader)` não lança apenas `IOException`: o seu
`loadConvert` lança `IllegalArgumentException("Malformed \\uxxxx encoding.")`,
que é não verificada e passa ao lado deste `catch`. Ela sobe pelo construtor
(`Settings.java:109-114`) e, como as duas instâncias são estáticas —

`platform/Settings.java:85-89`

```java
    private static final Settings SETTINGS = new Settings("settings.properties",
            "Endeavour Neo -- what you chose. Safe to copy to another machine.");

    private static final Settings WORKSPACE = new Settings("workspace.properties",
            "Endeavour Neo -- what the application was doing. Delete this to reset the layout.");
```

— rebenta o inicializador estático da classe. O `Launcher` toca em `Settings` na
primeira linha do `main` (`Theme.remembered()` → `Theme.PREFS`), portanto o
programa morre com `ExceptionInInitializerError` antes de existir janela.

Isto contradiz frontalmente o comentário três linhas abaixo, que promete o
oposto: *"the application opens with its defaults rather than refusing to open
at all"*. É um guarda que protege menos do que diz.

**Consequência.** Travamento no arranque, sem janela e sem mensagem que nomeie o
arquivo — o stack trace aponta para `Settings.<clinit>`. O caminho é alcançável
porque o formato **é feito para ser editado à mão**: é o argumento inteiro da
secção *"Why files and not java.util.prefs"* (`Settings.java:57-63`), e a mesma
classe já antecipa o erro de digitação em `getInt` (linha 367: *"A hand-edited
file with a typo in it"*). Basta um `\u12` truncado ou um `\uZZZZ`.

**Correção.** `catch (IOException | IllegalArgumentException e)`, e tratar o
caso como B7a-2 manda tratar: não gravar por cima.

**Tentei refutar assim.** (a) Verifiquei se o próprio `escape()` poderia produzir
um `\u` cru: não — `Settings.java:312-313` escapa a barra invertida primeiro
(`\\`), logo um valor que contenha `\u` sai como `\\u` e volta literal. O
caminho é só o da edição manual, que é o caso declarado. (b) Procurei um
`try/catch` mais acima, no `Launcher` ou num `Thread.UncaughtExceptionHandler`:
não há nenhum. (c) Verifiquei se `HOME` poderia impedir a leitura de um arquivo
do leitor durante a suíte: `pom.xml:86` define `endeavourneo.home`, mas isso não
muda nada em produção. Não caiu.

---

# MÉDIA

### B7a-4. `SeriesCatalog.forget()` não tem nenhum chamador de produção: o gancho `whenForgotten` é código morto

`platform/SeriesCatalog.java:761-794`

```java
    public static void forget() {
        LOADED.clear();

        for (Runnable each : FORGETFUL) {
            each.run();
        }
    }
```

**Problema.** `grep -rn "forget" src/main/java` devolve, em todo o `src/main`,
uma única referência ao método: a sua própria declaração e o `{@link}` do
javadoc de `whenForgotten`. Os únicos chamadores estão nos testes
(`SeriesCatalogTest:50`, `ReplayBase:101,121`, `ReplayHousekeepingTest:116`).

O javadoc, porém, narra o defeito no passado, como se tivesse sido corrigido:

`platform/SeriesCatalog.java:765-770`

```
     * <p>Whoever else caches something derived from these files is told, and
     * the interface is where those live -- the playable-days calendar above
     * all. Its own javadoc says it is dropped "for when the series on disk
     * change", and that moment used to call it from nowhere at all: importing a
     * session, exporting a tape or rebuilding a series left the calendar
     * showing yesterday for the life of the run.</p>
```

O "used to" é falso: continua a ser chamado de lugar nenhum. E o único ouvinte
registado nunca corre em produção:

`ui/replay/ReplayFeed.java:191`

```java
        SeriesCatalog.whenForgotten(ReplayFeed::forget);
```

**Consequência.** Um mecanismo de invalidação completo — lista, registo,
notificação — que nunca dispara. Hoje o dano é limitado porque nada em
`src/main` reescreve uma série (`MarketFile.write` só é usado por testes, e o
seu próprio javadoc diz *"Nothing else in this application writes a base"*), mas
o comentário diz que o problema está resolvido, e a próxima pessoa a acrescentar
importação de sessão vai acreditar nele. Já há hoje dois caminhos que mudam o
que o catálogo deve mostrar e não avisam ninguém: `setFolder` e `setRetired`
(ver B7a-6) limpam o `LOADED` à mão e deixam o `ReplayFeed.KNOWN` com os dias da
pasta anterior.

**Correção.** Ou chamar `forget()` de onde o disco muda, ou dizer no javadoc que
ainda não há de onde. E `setFolder`/`setRetired` deviam chamar `forget()` em vez
de `LOADED.clear()`.

**Tentei refutar assim.** Procurei chamada por nome qualificado
(`br.com...SeriesCatalog.forget`), por referência de método
(`SeriesCatalog::forget`) e por qualquer token `forget` em `src/main` — só as
ocorrências acima e homónimos não relacionados (`RulerMode.forget`,
`ChartPreferences.forget`, `ReplaySession.forget`, `TickLibrary.forget`,
`Appearance.forgetPalettes`). Procurei também um `Timer` ou `WatchService` que o
disparasse: não existe. Não caiu.

---

### B7a-5. `forget()` corre os ouvintes na thread de quem chamou, sem guarda de exceção e sem forma de cancelar registo

`platform/SeriesCatalog.java:775-794`

```java
        for (Runnable each : FORGETFUL) {
            each.run();
        }
    }

    /**
     * @param listener told whenever {@link #forget} drops what is held
     *
     * <p>For a cache built out of these files that has no way of knowing they
     * changed. Registered once at startup and never removed, so a list and not
     * a map: there is nothing to unregister.</p>
     */
    public static void whenForgotten(Runnable listener) {
        if (listener != null) {
            FORGETFUL.add(listener);
        }
    }
```

**Problema.** Três coisas, respondendo ao que foi pedido olhar:

1. **A ordem está certa, o resto não.** `LOADED.clear()` antes dos ouvintes é
   correto — um ouvinte que reabra uma série durante a notificação recebe o
   arquivo, não o cache velho. Isso não está dito em lado nenhum, e é a única
   parte que não pode ser trocada sem consequência.
2. **Um ouvinte que lance interrompe os seguintes.** Não há `try/catch` dentro
   do laço. Com um único ouvinte é inofensivo; com dois, o segundo cache fica
   sujo e ninguém sabe.
3. **Registo duplo é aceite e irreversível.** `whenForgotten` não deduplica e
   não há `stopForgetting`. Hoje não acontece, mas só porque o **chamador**
   se protege, e com um teste-e-age sobre um `volatile`:

   `ui/replay/ReplayFeed.java:177-193`
   ```java
        if (following) {
            return;
        }

        following = true;

        SeriesCatalog.whenForgotten(ReplayFeed::forget);
   ```

   Duas threads em `warm()` ao mesmo tempo registam duas vezes. O javadoc afirma
   *"Registered once at startup and never removed"* como se fosse uma
   propriedade desta classe; é uma propriedade da disciplina de um chamador.

**Consequência.** Uma classe `platform` que garante menos do que o javadoc diz.
O risco fica adormecido até ao segundo ouvinte — e o segundo ouvinte é o motivo
de a lista existir.

**Correção.** `if (!FORGETFUL.contains(listener))` no registo, `try/catch` por
ouvinte no laço, e dizer no javadoc que a ordem `LOADED` → ouvintes é
obrigatória.

**Tentei refutar assim.** (a) Verifiquei se `CopyOnWriteArrayList` já dá alguma
proteção contra exceção do ouvinte: dá contra modificação concorrente durante a
iteração, não contra a exceção. (b) Procurei um teste que registasse dois
ouvintes ou um que lançasse: os `@DisplayName` de `SeriesCatalogTest` não têm
nenhum sobre `whenForgotten` — o gancho não é testado de todo. Não caiu.

---

### B7a-6. `setFolder` e `setRetired` não são chamados por ninguém; `data.directory` e `data.retired` são lidas e nunca escritas

`platform/SeriesCatalog.java:161-169` e `509-511`

```java
    public static void setFolder(Path folder) {
        Path absolute = folder.toAbsolutePath();

        Settings.settings().put(KEY, absolute.toString());
```

```java
    public static void setRetired(Set<String> names) {
        Settings.settings().put(RETIRED_KEY, String.join(",", names));
    }
```

**Problema.** Nenhum dos dois tem chamador — nem em `src/main`, nem em
`src/test`. `data.directory` é lida em `folder()` (linha 136) e `data.retired`
em `retired()` (linha 494), ambas sem escritor. É o caso exato de "chave lida e
nunca escrita".

E há javadoc a descrever telas que não existem:

`platform/SeriesCatalog.java:171-177`
```
     * <p>Apart from {@link #setFolder}, which decides and persists. This one is
     * for looking: a settings page previewing another folder, and the tests,
```

`platform/SeriesCatalog.java:492`
```java
    /** @return the names not offered, which the reader may change */
```

Não há página de configurações que preveja outra pasta, e o leitor **não** pode
mudar a lista de reformadas por dentro do programa. O mesmo se aplica a
`data.roles`, `data.groups`, `data.scales` (`ROLES_KEY`, `GROUPS_KEY`,
`SCALES_KEY`) e a `data.zone` (`Launcher.java:84`): todas lidas, nenhuma escrita
em lado nenhum.

**Consequência.** Seis chaves que só existem por edição manual de um arquivo que
nada documenta, e dois métodos públicos mortos que a próxima leitura do código
vai tomar por caminho ativo. O `retired()` na prática é uma constante disfarçada
de preferência.

**Correção.** Ou ligar os dois métodos à interface, ou apagá-los e dizer no
javadoc que as chaves são de edição manual — e listá-las em algum lado.

**Tentei refutar assim.** (a) Procurei uso por nome qualificado
(`br.com...SeriesCatalog.setFolder`): nada. (b) Verifiquei se o
`SeriesCatalogTest` reforma pelo método: não — `aRetiredBaseIsHiddenNotDeleted`
(linha 123) apoia-se no valor de omissão `RETIRED_BY_DEFAULT`, e nunca chama
`setRetired`. (c) Procurei uma janela de preferências que os usasse:
`MainWindow.openPreferences` existe, mas nenhuma chamada a `setFolder` aparece
em todo o `src`. Não caiu.

---

### B7a-7. `data.groups`, `data.scales` e `data.roles` substituem os valores de omissão em vez de os completar

`platform/SeriesCatalog.java:249-261`

```java
    private static Map<String, String> stated(String key, String fallback) {
        Map<String, String> pairs = new LinkedHashMap<>();

        for (String each : Settings.settings().get(key, fallback).split(",")) {
```

**Problema.** O `fallback` só é usado quando a chave está **ausente**. Escrita a
chave — que é o que a documentação da própria classe convida a fazer, chamando-a
de "A setting, so the roles move as the work does" (linha 230) — o valor de
omissão desaparece por inteiro.

`GROUPS_BY_DEFAULT` (linha 288) carrega o conhecimento que a classe descreve em
dez linhas de javadoc como tendo custado um defeito:

```java
    private static final String GROUPS_BY_DEFAULT =
            "winn=win,winfut=win,winfull=win,win=win,btcusdt=btcusdt";
```

Quem acrescentar `data.groups = ouro=ouro` perde `winn`, `winfut`, `winfull` e
`win` de uma vez, e as quatro séries do WIN partem-se em quatro mercados na
árvore — exatamente o defeito que o javadoc de `groups()` diz ter sido
corrigido: *"it took the text up to the first dash, which makes winn, winfut and
winfull three different markets when they are three exports of one"*.

**Consequência.** A árvore mostra o mesmo mercado repartido, e `relativeTo` passa
a calcular caminhos noutras pastas — `winfull-1m` deixa de ser procurado em
`win/1m/` e passa a `winfull/1m/`, ou seja, a série deixa de ser encontrada.

**Correção.** Partir do mapa de omissão e sobrepor o que a chave disser, em vez
de substituir.

**Tentei refutar assim.** Procurei uma fusão no chamador: `roles()`, `groups()` e
`scaleOf` usam o resultado de `stated` diretamente (linhas 238, 293, 421). Não há
segunda leitura. Não caiu.

---

### B7a-8. Nenhum dos dois arquivos carrega versão de formato: não há de onde migrar

`platform/Settings.java:270-289`

```java
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write("# " + banner);
                out.newLine();
                out.write("# " + LocalDateTime.now());
```

**Problema.** O cabeçalho tem um banner e uma data e nenhuma versão. Não há
chave `version` nem equivalente em nenhum dos dois arquivos, e nenhuma rotina de
migração em lado nenhum de `platform`.

Já houve pelo menos duas mudanças de formato que uma versão teria tornado
triviais e que foram resolvidas por adivinhação sobre o conteúdo: a reparação de
codificação (`Settings.repair`, linhas 184-255, que tenta desfazer até oito
rondas de dano e decide por análise dos bytes se o texto está partido) e a
mudança do nome da série de omissão de `winn-1m` para `winfull-1m`
(`SeriesCatalog.java:74-82`), que deixa qualquer workspace antigo a apontar para
uma série que continua a existir mas já não é a fonte.

**Consequência.** Dívida que já custou uma vez. `repair()` são setenta linhas e
uma heurística documentada como "not a guess" a fazer o trabalho que um número
de versão faria em três.

**Correção.** Uma chave `format = 1` gravada por `save()` e lida por `load()`,
com a migração a correr entre as duas.

**Tentei refutar assim.** Procurei `version`, `format`, `migrate`, `upgrade` em
`platform/`: nada além do `VERSION` do `MarketFile`, que é do formato binário e
não deste. Não caiu.

---

### B7a-9. Falha de escrita é engolida por inteiro; `_unsaved` é escrita, nunca lida e nunca limpa

`platform/Settings.java:291-296`

```java
        } catch (IOException e) {
            // Nothing useful to do and nowhere useful to say it: the reader is
            // mid-click, and a dialog about a settings file would interrupt the
            // thing they were actually doing.
            values.putIfAbsent("_unsaved", "true");
        }
```

**Problema.** Três defeitos numa linha:

1. `grep -rn "_unsaved" src/` devolve **só esta linha**. Ninguém lê a chave. É
   o caso exato de "chave escrita e nunca lida".
2. Ela nunca é removida. Uma falha transitória põe `_unsaved` no mapa, a
   gravação seguinte tem êxito, e `_unsaved=true` fica no arquivo do leitor para
   sempre — a dizer uma coisa que já não é verdade.
3. A exceção não é registada em lado nenhum, e a mensagem que não existe não
   diria o caminho do arquivo. O disco cheio, a home em rede fora, a pasta sem
   permissão: nada disto produz um único caractere de saída, e o leitor descobre
   quando reabre o programa e o layout voltou ao princípio.

O argumento do comentário — não interromper o leitor com uma caixa de diálogo —
é bom, e não obriga a silêncio absoluto: há um console na janela principal, e o
`JobService` já mostra que a saída padrão é capturada para lá.

**Consequência.** Perda de configuração sem aviso, e uma chave suja permanente
no arquivo que o javadoc da classe promete ser legível e copiável entre
máquinas.

**Correção.** Escrever a falha no `System.err` uma vez por sessão, nomeando
`file`, e remover `_unsaved` na primeira gravação com êxito — ou apagar a chave,
já que ninguém a lê.

**Tentei refutar assim.** (a) Procurei leitura em `get`, `getBoolean`, na
interface e nos testes: nenhuma. (b) Procurei se `SettingsTest` cobre a falha de
escrita: os oito `@DisplayName` não têm nenhum sobre disco recusado. Não caiu.

---

### B7a-10. Cada `put` grava o arquivo inteiro, na EDT, e não atomicamente

`platform/Settings.java:341-349, 270-278`

```java
    public void put(String key, String value) {
        ...
        save();
    }
```

```java
        try {
            Files.createDirectories(file.getParent());

            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
```

**Problema.** Não há lote. Gravar N segmentos custa 3N+1 reescritas completas do
`workspace.properties`:

`platform/Segmentation.java:133-148`

```java
        workspace.removeStartingWith(PREFIX + series + ".");

        for (int at = 0; at < segments.size(); at++) {
            Segment each = segments.get(at);

            workspace.put(keyOf(series, at, "name"), each.name());
            workspace.put(keyOf(series, at, "from"), each.from().toString());

            if (each.to() != null) {
                workspace.put(keyOf(series, at, "to"), each.to().toString());
            }
        }
```

O mesmo padrão em `MainWindow.rememberCharts` (2N+1 gravações, uma por gráfico
aberto) e em `ChartHolder:861-864` (quatro gravações seguidas ao mover uma
janela flutuante). Tudo isto corre na EDT — são ouvintes de janela e de diálogo.

Além do custo, `Files.newBufferedWriter` trunca antes de escrever: **não é
atómico**. Uma queda a meio de qualquer uma das dezenas de gravações por sessão
deixa o arquivo cortado, e um arquivo cortado leva ao B7a-2 ou ao B7a-3 no
arranque seguinte.

**Consequência.** Trabalho de arquivo na thread da interface, contra a
convenção; e uma janela de corrupção proporcional ao número de gravações, que é
grande de propósito ("Written on every change rather than at exit",
`Settings.java:64`).

**Correção.** Escrever para `<file>.tmp` e `Files.move(..., ATOMIC_MOVE,
REPLACE_EXISTING)`. E um `putAll(Map)` que grave uma vez, usado por
`Segmentation.set` e `rememberCharts`.

**Tentei refutar assim.** (a) Verifiquei se `save()` já adia por temporizador ou
por thread: não, é síncrono no `put`. (b) Verifiquei se `Segmentation.set` corre
fora da EDT: o chamador é `ui/series/SegmentDialog`, que é um diálogo. (c) Pesei
o tamanho: o arquivo é pequeno e num disco local a gravação é sub-milissegundo —
o custo em tempo é discutível, a não-atomicidade não é. Reduzi a severidade de
ALTA para MÉDIA por isso. Não caiu.

---

### B7a-11. O carimbo de data em cada gravação anula o motivo declarado de ordenar as chaves

`platform/Settings.java:257-268` e `281`

```
     * <p>Written by hand rather than with {@link Properties#store}, which writes
     * in hash order — the same settings come out in a different order every time
     * and a diff is useless.</p>
```

```java
                out.write("# " + LocalDateTime.now());
```

**Problema.** A ordenação existe para que uma mudança seja uma linha num diff. A
linha 2 é a hora do relógio no momento da gravação, então **toda** gravação
produz um diff, e o `Launcher` grava sempre no arranque (`theme.remember()`,
linha 74) mesmo quando o tema não mudou. O arquivo muda todos os dias sem que
nenhuma preferência tenha mudado.

Secundariamente, `LocalDateTime.now()` grava hora local sem fuso nem
deslocamento — ambígua na hora que se repete na mudança de horário, num programa
que trata fuso como assunto sério (`Launcher.java:78-97`).

**Consequência.** A propriedade que a classe diz garantir não se verifica; o
diff útil que justificou setenta linhas de escrita manual não existe.

**Correção.** Tirar a data, ou movê-la para depois das chaves, ou não gravar
quando nada mudou (comparar antes de escrever). E `theme.remember()` só quando
o tema veio da linha de comando.

**Tentei refutar assim.** Verifiquei se `SettingsTest` afirma alguma coisa sobre
o diff: `"the file comes out sorted, so a change is one line in a diff"`
(linha 106) afirma a ordenação, não a estabilidade do arquivo — passa com o
carimbo lá. Não caiu.

---

### B7a-12. `stage` e `fraction` são do serviço, não do trabalho: dois jobs sobrepõem-se

`platform/JobService.java:301-303, 377-397`

```java
    private volatile String stage = "";

    private volatile double fraction = -1.0;
```

```java
    private Progress progressFor(Handle<?> handle) {
        return new Progress() {

            @Override
            public void report(double value) {
                fraction = value;
```

**Problema.** O pool tem `Math.max(2, cores - 1)` threads (linha 280) e nada
impede dois trabalhos ao mesmo tempo — o `Launcher` já submete um ("sessions")
enquanto a janela está a abrir e o leitor pode começar outro. Os dois escrevem no
mesmo `stage` e no mesmo `fraction`. A barra de estado mostra o estágio de um com
a fração do outro, e não há como saber qual.

Pior no fim: `Launcher.java:365-368` — o reposicionamento só acontece quando a
lista fica vazia,

```java
                if (running.isEmpty()) {
                    stage = "";
                    fraction = -1.0;
                }
```

portanto o job que acaba primeiro deixa o seu estágio a mentir sobre o que ainda
corre.

**Consequência.** Duas respostas para a mesma pergunta, no lugar cujo javadoc diz
existir para responder "why is this slow" (linha 46-48).

**Correção.** `stage` e `fraction` no `Handle`, e a barra de estado a escolher —
o mais recente, ou o primeiro da lista.

**Tentei refutar assim.** (a) Verifiquei se algo serializa as submissões: não,
`submit` chama `pool.submit` diretamente. (b) Verifiquei o teste: `"the service
reports what is running, so the status bar can say"`
(`JobServiceTest:301`) verifica `runningNames()`, que é por trabalho e está
correto — a fração e o estágio não são testados com dois trabalhos. Não caiu.

---

### B7a-13. `onChange` não tem forma de cancelar registo, e a mudança de idioma reconstrói a janela

`platform/JobService.java:399-402`

```java
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }
```

**Problema.** Não há `removeChange`. O único chamador liga-se na construção da
barra de estado:

`ui/shell/StatusBar.java:153`

```java
        service.onChange(this::refresh);
```

E a janela principal é reconstruída, com o **mesmo** serviço, sempre que o
idioma muda:

`ui/shell/MainWindow.java:1041-1049`

```java
        closeCharts();
        dispose();

        br.com.jorge.reis.endeavourneo.platform.Language.install();
        ...
        MainWindow fresh = new MainWindow(Messages.get("app.title"), jobs);
```

**Consequência.** Recurso que vaza: cada troca de idioma deixa mais um
`StatusBar::refresh` na lista, e através dele a `MainWindow` descartada inteira
fica alcançável e não pode ser recolhida. E cada mudança de trabalho passa a
correr `refresh()` — com `setVisible` e `revalidate()` — sobre componentes de
janelas já `dispose()`d, uma vez por janela morta.

Isto contradiz a convenção de que quem abre fecha, e o próprio `Language` chama
a reconstrução de barata (*"the machinery to avoid it was already there"*,
`Language.java:29-34`).

**Correção.** `public void stopWatching(Runnable listener)` e uma chamada em
`MainWindow.prepareToLeave`. `ChartPreferences.forget(Runnable)` e
`RulerMode.forget(Runnable)` já são exatamente esse par nesta base — o
`JobService` é o que não o tem.

**Tentei refutar assim.** (a) Procurei remoção da lista: `listeners` só aparece
em `listeners.add` (linha 401) e no laço de `notifyListeners` (linha 432). (b)
Verifiquei se `relaunch()` cria um `JobService` novo, o que tornaria o velho
recolhível: não — passa `jobs`, o mesmo campo. Não caiu.

---

### B7a-14. Uma falha sem ouvinte é silenciosa durante toda a sessão, e o javadoc diz o contrário

`platform/JobService.java:51-54`

```
     *   <li><b>Failures that surface.</b> An exception inside {@code
     *       SwingWorker.doInBackground} is <i>swallowed</i> until someone calls
     *       {@code get()} — a job dies and the interface shows nothing at all. Here
     *       a failure always reaches a handler.</li>
```

**Problema.** "Always reaches a handler" não é o que o código faz. Sem
`whenFailed`, `deliver()` fica pendente de propósito (linhas 219-222) e o único
relato acontece em `close()`:

`platform/JobService.java:447-452`

```java
    public void close() {
        for (Handle<?> handle : unclaimed) {
            handle.reportIfUnclaimed();
        }
```

`close()` está ligado ao gancho de encerramento da JVM (`Launcher.java:104`),
portanto o relato chega **no fim do programa**, e não quando o trabalho morreu.
Entre uma coisa e outra o trabalho desapareceu de `runningNames()`, a barra
limpou-se, e o leitor viu um trabalho a terminar normalmente.

E o próprio `Launcher` é o caso: o único trabalho que ele submete não regista
ouvinte de falha nenhum.

`Launcher.java:109`

```java
        jobs.submit("sessions", progress -> {
```

Se `ReplayFeed.warm()` rebentar — por exemplo com `OutOfMemoryError`, que é a
falha que o `catch (Throwable)` da linha 341 existe para apanhar — os calendários
de replay ficam vazios sem que nada diga porquê, até ao encerramento.

**Consequência.** Exatamente o silêncio que a classe diz eliminar, no seu único
chamador de produção.

**Correção.** Ou relatar imediatamente e deixar um `whenFailed` tardio ainda
receber (o `delivered` já distingue os dois), ou dar `whenFailed` ao trabalho do
`Launcher`. As duas.

**Tentei refutar assim.** (a) Verifiquei se `unclaimed` é drenada noutro lado:
só em `close()` (linhas 448-452). (b) Verifiquei o teste
`theLastChanceReportSurvivesTheRedirect` (`JobServiceTest:199`): ele afirma que
o relato **não** foi para o fluxo redirecionado (`assertEquals(0,
swallowed.size())`) — prova a negativa, não que o relato chegou a algum lado, e
não diz nada sobre o momento. (c) Verifiquei se o `Launcher` trata a falha de
outra maneira: o `try/catch` interno da linha 127 só apanha `IOException` por
série, e não cobre o `warm()`. Não caiu.

---

### B7a-15. Javadoc colado no membro errado, em três lugares

`platform/Messages.java:81-110`

```java
    /**
     * @param key the key in the bundle
     * @param fallback what to show when the bundle has no such key
     * @return the text, or the fallback
     * ...
     */
    /**
     * @param instrument the market as it is stored, which is a folder name
     * ...
     */
    public static String market(String instrument) {
```

`platform/SeriesCatalog.java:296-333`

```java
    /**
     * @return the market a series belongs to
     *
     * <p>What lets the tree put the exports of one market together instead of
     * listing five files flat.</p>
     */
    /**
     * @param name a series as it is stored, which is a file name
     * ...
     */
    public static String displayOf(String name) {
```

`platform/Appearance.java:100-118`

```java
    /**
     * Tells FlatLaf to also read the {@code .properties} files in this package.
     * ...
     * @return whether it worked; false when FlatLaf or the file is absent
     */
    /**
     * Unregisters every palette any theme may have registered.
     * ...
     */
    private static void forgetPalettes() {
```

**Problema.** Três blocos órfãos, cada um a descrever o método que vem depois na
classe. Os métodos que eles descrevem ficaram sem javadoc nenhum:
`Messages.orElse` (linha 110), `SeriesCatalog.groupOf` (linha 372) e
`Appearance.registerPalette` (linha 134). O javac ignora o primeiro bloco.

**Consequência.** `forgetPalettes`, que devolve `void`, aparece na leitura com
um `@return whether it worked` por cima. `displayOf`, que devolve um rótulo de
ecrã, aparece com `@return the market a series belongs to`. Documentação que
mente sobre o contrato, nos três casos.

**Correção.** Mover cada bloco para o seu método.

**Tentei refutar assim.** Verifiquei se algum deles era javadoc de classe
interna ou de campo entre os dois: não, os blocos são consecutivos e nada os
separa. Não caiu.

---

### B7a-16. `displayOf` não retira a escala quando ela não é o sufixo — `btcusdt-1m-1y` sai como "Bitcoin-1M1Y"

`platform/SeriesCatalog.java:342-344`

```java
        if (!scale.isEmpty() && rest.endsWith("-" + scale)) {
            rest = rest.substring(0, rest.length() - scale.length() - 1);
        }
```

**Problema.** `scaleOf` lê a **primeira** parte que é escala, deliberadamente e
com teste (`SeriesCatalogTest:179`, `assertEquals("1m",
SeriesCatalog.scaleOf("btcusdt-1m-1y"))`). `displayOf` retira-a assumindo que é
o **sufixo**. Para `btcusdt-1m-1y` as duas leituras discordam e a escala fica.

Traçado: `scale = "1m"`; `endsWith("-1m")` é falso, `rest` fica
`"btcusdt-1m-1y"`; `instrument = "btcusdt"`; o prefixo sai e `rest = "-1m-1y"`;
`tail = "1M1Y"`; `market = "Bitcoin"` (`messages.properties:60`,
`navigator.group.btcusdt = Bitcoin`). Resultado: **`Bitcoin-1M1Y`**.

**Consequência.** Texto errado no ecrã, e no caso exato que o javadoc vizinho
nomeia. O javadoc de `displayOf` (linhas 305-309) justifica-se assim: *"winfull-1m
is read under WINFUT and under '1 minuto', so repeating either is saying it three
times"* — e é isso que este rótulo faz, repete a escala pela qual a série já está
pendurada. O rótulo correto é `Bitcoin-1Y`.

**Correção.** Retirar a ocorrência de `"-" + scale` onde ela estiver, não só no
fim.

**Tentei refutar assim.** (a) Verifiquei se `btcusdt-1m-1y` é um nome real ou
inventado para o teste: `GROUPS_BY_DEFAULT` inclui `btcusdt=btcusdt`
(linha 289) e os dois bundles têm `navigator.group.btcusdt` — é um nome que o
programa espera. (b) Procurei teste de `displayOf` para ele:
`noSeriesWearsTheMarketsName` (`SeriesCatalogTest:183`) cobre `win-1m`,
`winfut-1m`, `winn-1m`, `winfull-1m` e `ouro`, todos com a escala em sufixo — o
caso do meio não é testado. Não caiu.

---

### B7a-17. `namesIn` engole a falha de disco e devolve lista vazia

`platform/SeriesCatalog.java:525-561`

```java
    public static List<String> namesIn(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }

        try (Stream<Path> files = Files.walk(folder, 3)) {
            ...
        } catch (IOException e) {
            return List.of();
        }
    }
```

**Problema.** "Não há séries" e "não consegui ler a pasta" saem pela mesma porta,
sem mensagem e sem nomear a pasta. É o oposto da distinção que a mesma classe faz
de propósito trinta linhas acima, em `open`:

`platform/SeriesCatalog.java:630-634`
```
     * <p>Empty and an exception mean different things on purpose. No such series
     * is an ordinary answer — the reader asked for a name that is not there.
     * A series that exists and will not read is a fault worth showing
```

O resultado propaga-se: `holdsABase` (linha 205) fica falso, e `folder()`
continua a procurar noutro candidato ou cai em `candidates().get(0)` (linha 152)
— ou seja, uma pasta sem permissão faz o programa apontar silenciosamente para
outra. Que é o mesmo tipo de substituição silenciosa que o javadoc de
`candidates()` (linhas 186-193) diz ter sido eliminado: *"A series that is not
found must say so, not be replaced by a different one."*

**Consequência.** Árvore vazia, ou pior, a pasta errada, sem uma palavra. O
leitor vê "nenhuma série" e não tem por onde começar.

**Correção.** Registar a exceção com o caminho, uma vez, no `System.err` que o
console captura.

**Tentei refutar assim.** Procurei registo no chamador: `names()` (linha 210) e
`ReplayFeed.available()` usam o resultado diretamente; `MainWindow` só escreve
no console quando `open` falha, não quando a listagem vem vazia. Não caiu.

---

### B7a-18. `namesIn` reanalisa as configurações uma vez por arquivo

`platform/SeriesCatalog.java:539` e `555`

```java
                    .filter(name -> !retired().contains(name))
```
```java
                    .filter(name -> Files.isRegularFile(folder.resolve(relativeTo(name))))
```

**Problema.** `retired()` (linha 493) lê a configuração, faz `split(",")` e
constrói um `LinkedHashSet` novo — **por arquivo**. `relativeTo` chama `scaleOf`
e `groupOf`, e cada um chama `stated()`, que lê a configuração, faz `split(",")`
e constrói um `LinkedHashMap` novo — outras duas vezes por arquivo. São quatro
análises de texto e quatro coleções alocadas por candidato, mais o
`MarketFile::isSeries` que abre cada arquivo.

`displayOf` tem o mesmo padrão e é chamado a desenhar a árvore e o título do
gráfico.

**Consequência.** Alocação por elemento em laço quente, que a convenção proíbe
por escrito. Hoje são poucas séries, mas `names()` é chamado no arranque para
todas, em `ReplayFeed.available()`, e a cada reconstrução do modelo da árvore.

**Correção.** Levantar `retired()` e os mapas de `stated()` para fora do
`stream`, para variáveis locais.

**Tentei refutar assim.** Verifiquei se `Settings.get` já guarda alguma coisa em
cache: não, é `Properties.getProperty` direto, mas o `split` e as coleções são
construídos a cada chamada de qualquer modo. Não caiu.

---

# BAIXA

### B7a-19. O javadoc de `Theme.PREFS` fala de um mecanismo que a classe já não usa

`platform/Theme.java:55-63`

```
     * The preferences node, ONE for the whole application.
     *
     * <p>Calling {@code userNodeForPackage} from each class creates one node per
     * package: what the menu in {@code ui} writes, the startup code in {@code
     * app} never reads.
```

**Problema.** `Settings` não usa `java.util.prefs`; o seu próprio javadoc
dedica uma secção a explicar porque saiu de lá (`Settings.java:57-63`). Também
não há pacote `app`. O comentário descreve um defeito de um mecanismo abandonado.

**Consequência.** Quem ler a classe procura um `Preferences` que não existe.

**Correção.** Reescrever, guardando a razão (uma instância para toda a aplicação)
e largando o `userNodeForPackage`.

**Tentei refutar assim.** Procurei `java.util.prefs` e `userNodeForPackage` em
`src/main`: nenhuma ocorrência. Não caiu.

---

### B7a-20. `secondsOf` pode lançar dentro de um comparador

`platform/SeriesCatalog.java:437-475`

```java
        for (int i = 0; i < part.length() - 1; i++) {
            if (!Character.isDigit(part.charAt(i))) {
                return false;
            }
        }
```
```java
        long count = Long.parseLong(scale.substring(0, scale.length() - 1));
```

**Problema.** `isScale` aceita qualquer quantidade de dígitos; `Long.parseLong`
não. Um nome com muitos dígitos passa o guarda e rebenta na conversão. Como
`secondsOf` é chamado de dentro de `coarsestFirst()` (linha 485), a exceção sai
do meio de uma ordenação. `isScale("0m")` também passa, e `secondsOf` devolve 0,
o mesmo que `TICKS`.

**Consequência.** Nome patológico de arquivo derruba a construção da árvore. Não
acontece hoje.

**Correção.** Limitar o número de dígitos no `isScale`, ou envolver o `parseLong`
e devolver -1, que é o valor que a classe já reserva para "não sei ler".

**Tentei refutar assim.** Verifiquei se o nome do arquivo é validado antes:
`namesIn` só filtra por sufixo `.bin` e por cabeçalho. Não caiu.

---

### B7a-21. Um `cancel()` que chega depois do fim deixa o `Handle` na lista para sempre

`platform/JobService.java:246-259` e `362-363`

```java
        public void cancel() {
            cancelled.add(this);
```
```java
                running.remove(handle);
                cancelled.remove(handle);
```

**Problema.** A limpeza está no `finally` da tarefa. Um `cancel()` posterior a
esse `finally` — o botão de parar carregado no instante em que o trabalho
termina, ou o `cancelAll()` de `close()` a apanhar um trabalho que acabou entre
a iteração e o `add` — insere o handle numa lista que ninguém mais varre.
`isCancelled()` fica verdadeiro para sempre nesse handle, e a lista cresce ao
longo da sessão.

O mesmo em `unclaimed`, que recebe todo trabalho falhado (linha 359) e só é
esvaziada em `close()`, mesmo quando o ouvinte tratou a falha.

**Consequência.** Vazamento pequeno e limitado à sessão, e um `isCancelled()`
que responde sobre um trabalho que já não existe.

**Correção.** Guardar o estado de cancelamento num campo do `Handle` em vez de
numa lista do serviço.

**Tentei refutar assim.** Verifiquei se `CopyOnWriteArrayList.remove` tem
semântica que resolvesse a corrida: não, é remoção por igualdade e não impede
o `add` posterior. Não caiu.

---

### B7a-22. Estado global mutável sem `volatile`

`platform/Messages.java:50` e `platform/Segmentation.java:70`

```java
    private static ResourceBundle bundle = ResourceBundle.getBundle(BASE);
```
```java
    private static Settings store;
```

**Problema.** `Messages.bundle` é trocado por `setLocale` na EDT (chamado por
`Language.install`) e lido de threads do pool — o trabalho "sessions" do
`Launcher` chega a `Messages.market` via `ReplayFeed.label()`. `Segmentation.store`
é trocado por `useForTest` na thread do teste e lido onde for. Nenhum é
`volatile`. Compare-se com `SeriesCatalog.folder`, que é (linha 117).

**Consequência.** Sem barreira de memória a troca pode não ser vista; na prática
a `invokeLater` do arranque fornece uma. Corrida real, dano improvável.

**Correção.** `volatile` nos dois.

**Tentei refutar assim.** Confirmei que há mesmo leitura fora da EDT:
`Launcher.java:110` chama `ReplayFeed.warm()` numa thread do pool, e
`ReplayFeed.label()` (linha 240) chama `Messages.market`. Não caiu.

---

### B7a-23. O javadoc de `LayerBoundaryTest` descreve uma isenção que já não existe

`architecture/LayerBoundaryTest.java:55-60`

```
 * <p><b>The composition root is exempt, and only it.</b> {@code Main} exists to
 * wire the layers together, so it necessarily touches all of them. Every other
 * class in {@code app} is a platform service and must stay clean. Once the
 * planned reorganisation moves the entry point out of {@code app}, this
 * exemption disappears and the rule becomes purely positional.
```

**Problema.** A reorganização já aconteceu: não há pacote `app` nem classe
`Main`, e o `Launcher` está na raiz — exatamente como o seu próprio javadoc
descreve (`Launcher.java:32-36`). O teste não tem isenção nenhuma no código
(`isInnerLayer` = `platform` ou `domain`), e portanto a regra já é posicional.

**Consequência.** Documentação a descrever um estado anterior num arquivo cuja
função é ser lido para se perceber a regra. A regra, essa, está correta e o
teste tem dentes (`theScanHasTeeth`, `theScanFindsFiles`).

**Correção.** Reescrever o parágrafo no presente.

**Tentei refutar assim.** Procurei um pacote `app` ou uma classe `Main`:
`src/main/java/br/com/jorge/reis/endeavourneo/` tem `domain`, `platform`, `ui`,
`Launcher.java` e nada mais. Não caiu.

---

### B7a-24. `followTheCatalog` protege o registo com teste-e-age

`ui/replay/ReplayFeed.java:177-193`

```java
        if (following) {
            return;
        }

        following = true;
```

**Problema.** `volatile` garante visibilidade, não atomicidade. Duas chamadas
concorrentes a `warm()` — o `Launcher` e um teste, ou dois testes em paralelo —
registam o ouvinte duas vezes, e não há como o retirar (ver B7a-5).

**Consequência.** `ReplayFeed.forget()` corre duas vezes; é idempotente
(`KNOWN.clear()`), portanto hoje é inofensivo.

**Correção.** `AtomicBoolean.compareAndSet`, ou deduplicar em `whenForgotten`.

**Tentei refutar assim.** Verifiquei se `warm()` é sincronizado ou chamado de um
só sítio em produção: não é sincronizado; em produção é chamado uma vez, nos
testes várias. Não caiu.

---

### B7a-25. `endeavourneo.home` só é definida pelo surefire

`pom.xml:86`

```xml
                        <endeavourneo.home>${project.build.directory}/test-home</endeavourneo.home>
```

`platform/Settings.java:73-79`

```
     * <p><b>{@code endeavourneo.home} overrides the home directory</b>, and the
     * suite sets it.
```

**Problema.** "A suíte define-a" só é verdade quando a suíte corre pelo Maven. O
caminho de compilação e execução descrito nas convenções da casa é `javac` do
JBR mais um `JupiterRunner` que recebe nomes de classe, e essa invocação não
passa a propriedade. Corrida assim, a suíte volta a escrever o
`~/.endeavourneo/workspace.properties` real — que é precisamente o defeito que o
comentário do `pom.xml` diz ter sido corrigido.

**Consequência.** O guarda existe e está armado só num dos dois caminhos de
execução.

**Correção.** Pôr a propriedade também no invólucro do `JupiterRunner`, ou fazer
`Settings` recusar-se a escrever quando detetar JUnit no classpath sem a
propriedade definida.

**Tentei refutar assim.** Procurei a propriedade em todo o repositório: só em
`Settings.java:82` (a leitura) e `pom.xml:86` (a escrita). Não há script nem
`.idea/runConfiguration` que a defina. Não caiu.

---

### B7a-26. `theme.remember()` grava a cada arranque, mesmo sem argumento

`Launcher.java:57-76`

```java
        Theme theme = Theme.remembered();
        ...
        theme.remember();
```

**Problema.** Sem argumento, isto lê o tema e volta a gravar o mesmo valor —
uma reescrita completa do `settings.properties` em todo o arranque. É também o
que torna o B7a-2 uma certeza em vez de uma possibilidade: basta uma leitura
falhada para que a primeira gravação da sessão apague o arquivo.

**Consequência.** Escrita desnecessária no arranque, e a janela do B7a-2 aberta
sempre.

**Correção.** Gravar só quando o argumento mudou o tema.

**Tentei refutar assim.** Verifiquei se `remember()` compara antes de gravar:
`Theme.java:97-99` chama `PREFS.put` diretamente, sem comparação —
`ChartPreferences.setWindow` faz essa comparação, o que mostra que o padrão
existe na base e não foi usado aqui. Não caiu.

---

# LIMPO

O que foi conferido e está certo, e como.

**`Settings.escape` e a ida-e-volta do formato.** Segui carácter a carácter
(`Settings.java:306-333`) contra o que `Properties.load` desfaz: `\\`, `\n`,
`\r`, `\t`, `=`, `:`, `#`, `!` e o espaço só onde é significativo. A regra do
espaço (chave inteira, ou primeira posição de um valor) está certa e a razão
está comentada. `Segmentation.MARK` é `#`, é escapado, e volta inteiro —
confirmado com o teste `"a name may contain anything, including the separators"`
(`SegmentationTest:84`).

**A não-recursão de `save`.** O comentário das linhas 260-268 descreve uma
armadilha real de `Properties` — subclasse com `entrySet` ordenado que chama
`putAll` que chama `entrySet` — e o código evita-a corretamente, construindo a
lista de chaves à parte. O teste `"saving does not recurse -- the defect that
shipped"` (`SettingsTest:66`) tem dentes.

**A reparação de codificação.** Li `repair`, `repair(String)` e `undo`
(`Settings.java:184-255`). O critério de deteção está correto e é o que o
comentário diz: um texto que só tem caracteres `<= 0xFF` cujos bytes ISO-8859-1
formam UTF-8 válido. Verifiquei que `ção` em ISO-8859-1 é de facto uma sequência
UTF-8 malformada, portanto o `undo` para. O limite de oito rondas é arbitrário
mas está declarado e o pior caso é benigno. `SettingsEncodingTest` cobre os dois
lados (linhas 85 e 103), incluindo "texto nunca danificado fica intacto", que é o
lado que faltaria.

**A fronteira de camadas.** `grep "endeavourneo.ui"` sobre
`platform/` não devolve nada — nem `import`, nem nome qualificado. Confirmei que
a regra é vigiada de verdade: `LayerBoundaryTest.scan` lê **todas** as linhas de
código e não só os `import`, e tem dois testes do próprio guarda
(`theScanHasTeeth`, `theScanFindsFiles`). `Launcher` importa `ui` e está na raiz,
fora do alcance de `isInnerLayer`, com a razão escrita.

**`Messages`, chave em falta.** `get` devolve `!chave!` e não vazio nem exceção
(linha 64), como a convenção manda. `orElse` existe para o texto vindo de dados e
a distinção está certa. `mnemonic` filtra o `!` e o vazio (linha 125), portanto
uma chave em falta não vira um mnemónico absurdo. `BundleKeysTest` verifica que
os dois bundles dizem as mesmas chaves e que nenhuma é definida duas vezes.

**`Messages.setLocale` sem recuo para o locale por omissão.** Li o comentário e
a chamada (linhas 141-154): `getNoFallbackControl(FORMAT_PROPERTIES)` é de facto
o que resolve "pedir inglês numa máquina brasileira dá português", e
`LanguageTest:86` prova-o.

**`Language.install`.** Chama sempre, incluindo `SYSTEM` (linha 96), e mexe no
`Locale.setDefault` além do bundle (linha 106) — as duas coisas estão comentadas
com a razão medida e testadas (`LanguageTest:61`, `:118`). O `of(String)`
desconhecido cai em `SYSTEM`, que é a resposta certa.

**`Appearance` e a ordem do look-and-feel.** `forgetPalettes()` antes de tudo
(linha 68), depois o registo da paleta, depois `setLookAndFeel`. A verificação de
que o `.properties` existe antes de anunciar o tema (linhas 140-145) fecha a
falha silenciosa que o comentário descreve. Carregamento por reflexão com queda
para o look-and-feel do sistema, como a convenção exige. `ThemeSwitchTest` cobre
os quatro casos, incluindo "night reports itself as night, not as dark with a
missing palette".

**`openUntil`, caminho lento.** Conferi a aritmética: `from = max(0, end - bars)`,
`count = min(bars, end)`. Testei mentalmente `end` maior que `bars`, menor que
`bars` e zero — os três dão a janela certa, e os testes
`aWindowCanBeAnchoredAtASegmentsEnd` e `aStretchBeforeTheFileIsEmpty` fixam os
dois extremos. O defeito B7a-1 está no atalho de cache, não aqui.

**`open(String, int)` não sobre-lê.** Suspeitei da assimetria — `openUntil` faz
`Math.min(bars, end)` e `open(name, bars)` não. Fui ver `MarketFile.read`
(`domain/market/MarketFile.java:176-177`), que faz o seu próprio
`Math.min((long) wantedCount, head.count - first)`. Refutado: não há sobre-leitura.

**`MarketFile` verifica o tamanho contra o cabeçalho** antes de ler
(linhas 168-177), com mensagem que nomeia os dois números e o arquivo. É o
contraste que torna o silêncio de `namesIn` (B7a-17) um achado.

**`Segmentation.overlap`.** Conferi a fórmula
`!one.from().isAfter(otherEnd) && !other.from().isAfter(oneEnd)` com
`LocalDate.MAX` para o fim aberto: fechado-fechado, portanto segmentos que se
tocam num dia sobrepõem-se, e segmentos adjacentes por um dia não. É o que os
testes afirmam (`SegmentationTest:151`, `:164`).

**`Segmentation.segmentIn` devolve `null` na direção segura.** Um nome que aponta
para um segmento removido vira pedido pela série inteira, que uma série trancada
recusa em voz alta — a alternativa (mostrar tudo em silêncio) é o defeito. O
raciocínio está no javadoc (linhas 172-179) e o teste existe
(`"um segmento que sumiu vira pedido pela serie inteira"`).

**A chave da tranca fora do prefixo dos segmentos.** `ONLY = "segmentsOnly."` e
não `segments.<serie>.only`, porque `set` limpa tudo abaixo do prefixo antes de
escrever. A razão está comentada (linhas 260-266) e o teste
`"a trava sobrevive a gravar segmentos"` bate na coisa certa.

**`JobService`, a corrida do resultado.** Li `settle`, `deliver`, `whenDone`,
`whenFailed` e `whenStopped`. A ideia — guardar o desfecho e deixar que o
segundo dos dois eventos o entregue, uma vez só, sob `delivered` — está
implementada corretamente nos três desfechos, e todos os métodos que tocam o
estado são `synchronized`. O cancelamento ficar pendente até haver
`whenStopped` é deliberado e está comentado.

**`catch (Throwable)` e não `catch (Exception)`** na tarefa (linha 341), com a
razão medida no comentário: `OutOfMemoryError` escapava e fazia o trabalho
assentar como sucesso com `null`. O teste `"running out of memory is a FAILURE,
not a success carrying null"` (`JobServiceTest:113`) tem dentes.

**`settle` antes de sair da lista de correndo** (linha 356), para que um ouvinte
acordado pela mudança já veja um desfecho coerente. Ordem certa, comentada.

**As threads do pool.** Nomeadas e daemon (linhas 308-316), com as duas razões
escritas. `close()` pede paragem, `shutdown()`, espera dois segundos e
`shutdownNow()`, repondo o interrompido — fecho correto de quem abriu.

**`FAILURES = System.err` capturado no carregamento da classe** (linha 277).
Confirmei que a ordem em produção sustenta a afirmação: `Launcher` constrói o
`JobService` na linha 99 e só captura a saída padrão na linha 145.

**`Progress`, contrato cooperativo.** O javadoc explica porque não há paragem
forçada (`Thread.stop` liberta cadeados a meio) e onde chamar `cancelled()`.
`cancel()` usa `future.cancel(false)`, com a razão escrita. Coerente.

**`SeriesCatalog.folder()` não escreve como efeito de procurar** (linhas 106-116).
Confirmei: o ramo da procura só atribui ao campo, e `Settings.put` só aparece em
`setFolder`. É o que impede um teste que lista séries de alterar a máquina onde
correu.

**`relativeTo` separado de `fileOf`** (linhas 601-613), para que uma pasta que
não é a corrente possa ser interrogada sem apontar o programa inteiro para ela.
O comentário de `namesIn` (linhas 541-554) descreve a corrida que isto substituiu
— um teste que escreveu um cabeçalho de 24 bytes sobre 90 MB de ticks — e a
correção está de facto aplicada: o caminho é construído a partir do `folder` em
mão.

**Cache por `SoftReference`** (linha 103) em vez de forte: a memória volta à
máquina sob pressão e a próxima leitura paga o disco, em vez de a aplicação
morrer com um heap que se recusa a largar. Comentado e correto.

**`Theme.of` e o argumento da linha de comando.** `Theme.of` cai em `LIGHT` para
o que não conhece, e o `Launcher` (linhas 60-72) defende-se disso comparando o
rótulo devolvido com o texto pedido, para que uma bandeira errada não troque o
tema em silêncio. Guarda correto, com a razão escrita.

**A ordem do arranque.** Conferi que o fuso é fixado (`Launcher:84-97`) **antes**
de o trabalho "sessions" ser submetido (linha 109), que por sua vez chama
`Sessions.of`, que usa `Timeframe.defaultZone()`. A ordem está certa e o
comentário sobre "antes de qualquer coisa dobrar uma barra" é verdadeiro.
Verifiquei também que todo o Swing nasce dentro do `invokeLater` (linha 137) e
que o look-and-feel é instalado antes (linha 76), como a convenção manda.

**`Sessions` não precisa de invalidação por `forget`.** Suspeitei de um cache
paralelo ao do `SeriesCatalog`, mas `Sessions.ANSWERED` é um `WeakHashMap` com o
`PriceSeries` por chave (`domain/market/Sessions.java:102`), portanto a entrada
sai quando a série sai. Refutado como achado.
