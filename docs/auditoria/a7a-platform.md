# A7a — platform e Launcher

**Lidos integralmente** (10 arquivos de produção, 2.229 linhas):

| arquivo | linhas |
|---|---:|
| `platform/SeriesCatalog.java` | 626 |
| `platform/JobService.java` | 401 |
| `platform/Settings.java` | 393 |
| `platform/Segmentation.java` | 273 |
| `platform/Appearance.java` | 200 |
| `platform/Messages.java` | 160 |
| `platform/Theme.java` | 118 |
| `platform/Language.java` | 98 |
| `platform/Progress.java` | 52 |
| `Launcher.java` | 108 |

**Testes lidos integralmente** (a área inclui os testes): `platform/JobServiceTest.java`,
`platform/SeriesCatalogTest.java`, `platform/SegmentationTest.java`,
`platform/SettingsTest.java`, `platform/SettingsEncodingTest.java`,
`platform/LanguageTest.java`, `platform/ThemeSwitchTest.java`, e
`architecture/LayerBoundaryTest.java` (é o guarda da regra `platform` não importa `ui`,
que a auditoria mandou conferir).

**Recursos lidos:** `src/main/resources/messages.properties`,
`src/main/resources/messages_pt_BR.properties`.

**Lidos só como chamador/contrato, não auditados:** `ui/shell/MainWindow.java` (trechos
340-440 e 800-900), `ui/shell/StatusBar.java` (90-200), `ui/shell/Navigator.java`
(150-200, 250-310), `ui/replay/ReplayFeed.java` (70-190), `ui/series/Segmentable.java`
(55-105), `ui/settings/AppearancePage.java` (95-140), `domain/market/Segment.java`,
`domain/market/MarketFile.java` (91-215).

**O que foi conferido:** ciclo de vida e concorrência do `Settings` (a A4 já cobriu o
formato do layout — não repeti); `Preferences` do sistema ausente (não existe mais:
virou arquivo; o análogo é `user.home` ausente ou não gravável); varredura de disco,
cache, nome que não casa, arquivo que some entre listar e abrir, cru × ajustado no
`SeriesCatalog`; pool, `Future`, cancelamento, callback e EDT no `JobService`; troca de
idioma e de tema em tempo de execução; aritmética de borda da `Segmentation`; faixa e
segurança de thread do `Progress`; ordem de arranque do `Launcher`; contagem e diferença
das chaves dos dois bundles; fronteira de camadas; e, para cada teste, "que mutação do
código este teste NÃO pegaria".

---

## Achados ALTA

### A7a-1 — `Error` escapa do `try` e o trabalho é entregue como SUCESSO com `null`

**Onde:** `platform/JobService.java:282-292`

**Trecho:**
```java
            try {
                value = work.run(progressFor(handle));
                stopped = handle.isCancelled();
            } catch (Exception | StackOverflowError e) {
                // Caught here rather than left to the pool: an exception that
                // reaches the executor disappears without a trace.
                failure = e;
            } finally {
                // Settle BEFORE leaving the running list, so a listener woken
                // by the change already sees a consistent outcome.
                handle.settle(value, failure, stopped);
```

**Problema:** o `catch` pega `Exception` e um único `Error` — `StackOverflowError`.
Qualquer outro `Error` (`OutOfMemoryError`, `NoClassDefFoundError`, `LinkageError`,
`AssertionError`) atravessa o `catch`, o `finally` roda mesmo assim, e roda com
`failure == null`, `value == null`, `stopped == false`. Ou seja: `settle(null, null,
false)` → `deliver()` cai no ramo do sucesso → `onDone.accept(null)`. Depois disso o
`Error` continua subindo até o executor, onde o `FutureTask` o guarda num `Future` que
ninguém lê — exatamente o desaparecimento que o javadoc da classe (linhas 51-54) diz
existir para impedir.

`OutOfMemoryError` não é hipótese remota aqui: `MarketFile.read` (`domain/market/
MarketFile.java:107-113`) aloca `long[size]` mais cinco `double[size]` de uma vez; com
1 M de barras, que é o caso NORMAL, são ~48 MB por série num único bloco. E o próprio
projeto usa `AssertionError` nos construtores privados (`SeriesCatalog:116`,
`Segmentation:73`, `Appearance:52`, `Launcher:54`), então um erro de reflexão ou de
inicialização estática dentro de um job também cai neste buraco.

**Consequência:** o usuário vê o gráfico abrir vazio, ou uma `NullPointerException` num
lugar distante, e o log não diz nada. A barra de status volta para "pronto". A falta de
memória — o modo de falhar mais provável de um aplicativo que carrega 1 M de barras —
é a única que este serviço não sabe relatar.

**Correção:** `catch (Throwable e) { failure = e; }`. Se houver receio de engolir um
`Error` fatal, relançar depois de `settle`, mas nunca deixar o `finally` concluir com
`failure == null` quando algo foi lançado. Alternativa mínima: um `boolean threw` posto
no início do `try` e limpo no fim, e o `finally` decidindo por ele em vez de por
`failure != null`.

**Tentei refutar:** procurei um tratamento acima — o `Runnable` é submetido direto ao
`pool` (`:277`), o `Future` retornado é guardado em `handle.future` e **só** é usado para
`cancel(false)` (`:213`); nenhum `get()` em lugar nenhum do repositório (grep por
`.submit(` e `JobService` deu 15 ocorrências, todas listadas na seção do JobService
abaixo). Não há `UncaughtExceptionHandler` na `ThreadFactory` (`:252-261`) — e ele
tampouco ajudaria, porque o `FutureTask` captura o `Throwable` antes de a thread morrer.
Verifiquei também se `unclaimed` pegaria: `unclaimed.add(handle)` só acontece
`if (failure != null)` (`:294`), que é justamente o que está errado. Não caiu.

---

### A7a-2 — Trabalho cancelado nunca avisa ninguém, e `isCancelled()` volta a mentir `false`

**Onde:** `platform/JobService.java:165-176` e `:296-299`

**Trecho:**
```java
        private synchronized void deliver() {
            if (!settled || delivered) {
                return;
            }

            if (wasCancelled) {
                // Stopping on request is not a result and not a failure.
                delivered = true;

                return;
            }
```
e, no `finally` do `submit`:
```java
                running.remove(handle);
                cancelled.remove(handle);
```

**Problema:** ao cancelar, `deliver()` marca `delivered = true` e sai sem chamar
`onDone` nem `onFailed` — nenhum callback dispara, nem naquele momento nem depois
(qualquer `whenDone` encadeado mais tarde encontra `delivered == true` e volta calado,
`:166`). E o `finally` remove o handle da lista `cancelled`, de modo que
`handle.isCancelled()` — que é literalmente `cancelled.contains(this)` (`:219`) — passa a
devolver **`false`** assim que o job termina.

O resultado é que, depois de um cancelamento, o chamador não tem **nenhuma** maneira de
saber que o trabalho acabou: nenhum callback, `isBusy()` já é `false`, e
`isCancelled()` diz `false` — o mesmo que diria um job que ainda nem começou.

**Consequência:** quem espera o `whenDone` para fechar um diálogo, reabilitar um botão ou
restaurar a mensagem de status fica esperando para sempre. Já acontece hoje: em
`ui/shell/MainWindow.java:882-884` o `whenDone` do job de amostra faz
`status.say(Messages.get("status.ready"))`. Cancele pelo botão da barra de status
(`ui/shell/StatusBar.java:110-114` → `jobs.cancelAll()`) e a barra fica congelada na
última mensagem de etapa, porque o `whenDone` nunca roda. O usuário vê o aplicativo
dizendo que está fazendo algo que ele mandou parar.

**Correção:** ou entregar o cancelamento como um terceiro desfecho explícito
(`whenCancelled(Runnable)`, disparado uma vez), ou — mais barato — manter um campo
`wasCancelled` no próprio `Handle` (já existe, `:101`) e fazer `isCancelled()` lê-lo
depois de assentado, em vez de consultar a lista que o `finally` esvazia. Os dois são
necessários: só corrigir `isCancelled()` ainda deixa o chamador tendo que fazer polling.

**Tentei refutar:** li o `cancel()` (`:207-216`) procurando uma segunda via de aviso —
só há `future.cancel(false)`, que por contrato nem interrompe. Procurei um
`whenCancelled` ou equivalente: não existe. Verifiquei se `notifyListeners()` supriria —
ele avisa a barra de status de que a lista mudou (`:306`), mas é um `Runnable` sem
argumento, não sabe de qual handle se trata e não chega ao chamador que encadeou o
`whenDone`. Verifiquei se algum chamador atual faria polling de `isCancelled()`: nenhum
faz — o que só significa que o defeito ainda não foi observado, não que não exista.
Não caiu.

---

### A7a-3 — `JobServiceTest.cancellationIsCooperative` afirma uma disjunção que a linha seguinte já afirma sozinha — e é por isso que A7a-2 sobreviveu

**Onde:** `src/test/java/br/com/jorge/reis/endeavourneo/platform/JobServiceTest.java:138-149`

**Trecho:**
```java
            handle.whenDone(value -> finished.countDown());

            assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the job never started");

            handle.cancel();

            assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            || steps.get() < 1_000,
                    "the job ignored the cancellation and ran to the end");
            assertTrue(steps.get() < 1_000,
                    "the job completed all 1000 steps despite being cancelled after the first");
```

**Problema:** o `||` torna a primeira asserção vazia. O lado direito
(`steps.get() < 1_000`) é **exatamente** o que a asserção seguinte, na linha 147, afirma
sozinha. Logo a linha 144-146 não pode falhar sem que a 147 também falhe: ela não testa
nada.

E o lado esquerdo é justamente o que está quebrado hoje. Rastreando o código: o job
devolve `steps.get()` quando vê `progress.cancelled()`; de volta no `submit`,
`stopped = handle.isCancelled()` (`JobService:284`) ainda é `true` porque
`cancelled.remove(handle)` só acontece depois (`:299`); então `settle(value, null, true)`
→ `deliver()` cai no ramo `wasCancelled` e **larga o resultado**. O `finished.countDown()`
nunca roda. O `await` bloqueia os `TIMEOUT_SECONDS` inteiros — **10 segundos de espera a
cada execução da suíte** — e devolve `false`. O teste passa mesmo assim, pelo lado
direito.

**Consequência:** o teste dá licença falsa exatamente para A7a-2: o autor lê "cancelling
is seen by the job" verde e conclui que o cancelamento está coberto. Além disso a suíte
paga 10 segundos parada, e nada denuncia isso porque o teste é verde.

**Mutação que este teste não pegaria:** apagar todo o bloco `if (wasCancelled)` de
`deliver()` — ou, ao contrário, fazer `deliver()` engolir *qualquer* desfecho — continua
verde. Trocar `handle.cancel()` por uma linha em branco também: `steps.get()` só passaria
de 1.000 depois de 2 segundos de `Thread.sleep(2)`, e o teste esperaria 10 no `await`
antes de olhar, então até o cancelamento removido passaria pela primeira asserção — só a
segunda o pegaria.

**Correção:** separar em duas afirmações com dentes. Uma para "o job viu o pedido"
(`assertTrue(steps.get() < 1_000)`), outra para "o desfecho chegou a alguém" — que hoje
falha, e deve falhar até A7a-2 ser corrigido. Nunca `assertTrue(a || b)` quando `b` é
afirmado adiante.

**Tentei refutar:** verifiquei se o `whenDone` poderia disparar por outro caminho —
`deliver()` é o único lugar que chama os handlers (`:190` e `:204`), e o ramo
`wasCancelled` sai antes dos dois. Verifiquei se `stopped` poderia sair `false`: seria
preciso `handle.isCancelled()` virar `false` entre o `return` do job e a linha 284, e a
remoção da lista só acontece na linha 299, depois do `settle`. Verifiquei se o
`assumeTrue`/timeout do JUnit abortaria antes dos 10 s: não há `@Timeout` na classe.
Não caiu.

---

### A7a-4 — O guarda da fronteira de camadas só enxerga a palavra `import`, e o repositório escreve referências qualificadas

**Onde:** `src/test/java/br/com/jorge/reis/endeavourneo/architecture/LayerBoundaryTest.java:117-129`

**Trecho:**
```java
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.strip();

                    if (!trimmed.startsWith("import ")) {
                        continue;
                    }

                    for (String banned : forbidden) {
                        if (trimmed.contains(banned)) {
```

**Problema:** a varredura descarta toda linha que não comece com `import `. Uma
referência **totalmente qualificada** a `ui` dentro de `platform` ou de `domain` — sem
import nenhum — passa invisível.

Isso não é hipótese de laboratório: é o estilo que este repositório efetivamente usa para
cruzar pacotes. `Launcher.java:88-89` chama
`br.com.jorge.reis.endeavourneo.ui.replay.ReplayFeed.warm()` totalmente qualificado, sem
import; `MainWindow.java:397, 405, 821` chamam
`br.com.jorge.reis.endeavourneo.platform.Segmentation` e `...platform.Language` da mesma
forma; `SeriesCatalog.java:331` escreve
`br.com.jorge.reis.endeavourneo.platform.Messages.market(instrument)`. Basta um
`br.com.jorge.reis.endeavourneo.ui.shell.Navigator.algo()` numa classe de `platform` para
o teste continuar verde com a regra violada. O javadoc da própria classe (linhas 43-46)
argumenta que ler o `.java` é melhor que reflexão porque "bytecode perde os imports que o
compilador embutiu" — e a leitura do fonte perde o caso oposto, que é o que o repositório
pratica.

O teste companheiro `theScanFindsFiles` (linhas 136-157) protege contra um só modo de
falha — varrer zero arquivos — e nada contra este.

**Consequência:** a regra que sustenta rodar o motor sem tela pode ser quebrada sem
ninguém saber. O usuário não vê nada — até a noite em que o treino headless não roda
porque uma classe de `platform` precisa de um `JFrame`.

**Correção:** varrer o corpo do arquivo inteiro por
`br\.com\.jorge\.reis\.endeavourneo\.ui\.` além dos imports, ignorando comentários e
strings (ou, mais simples e igualmente eficaz, não ignorando: um comentário que cita
`...ui...` dentro de `platform` também merece ser olhado). E acrescentar um teste
negativo que prove os dentes: um arquivo temporário com a referência qualificada tem que
ser reportado como violação.

**Tentei refutar:** primeiro conferi se a violação já existe — grep por
`endeavourneo\.ui|javax\.swing|java\.awt` em todo o `platform`: só `javax.swing.
SwingUtilities` em `JobService:29` e `java.awt.Dimension/Font` + `javax.swing.UIManager/
UnsupportedLookAndFeelException` em `Appearance:20-25`, todos permitidos (a proibição de
Swing/AWT vale só para `domain`). Ou seja, **hoje a regra está cumprida** — o achado é o
buraco no guarda, não uma violação. Depois procurei um segundo guarda (Checkstyle,
ArchUnit, enforcer no `pom.xml`): não há. Não caiu.

---

### A7a-5 — A série AJUSTADA por razão é a única que aparece na tela com o nome puro do mercado

**Onde:** `platform/SeriesCatalog.java:314-335`, com `messages.properties` /
`messages_pt_BR.properties` (`navigator.group.win = WINFUT`)

**Trecho:**
```java
    public static String displayOf(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }

        String instrument = groupOf(name);
        String scale = scaleOf(name);
        String rest = name;

        if (!scale.isEmpty() && rest.endsWith("-" + scale)) {
            rest = rest.substring(0, rest.length() - scale.length() - 1);
        }

        if (rest.startsWith(instrument)) {
            rest = rest.substring(instrument.length());
        }

        String market = br.com.jorge.reis.endeavourneo.platform.Messages.market(instrument);

        return rest.isBlank() ? market
                : market + "-" + rest.replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }
```

**Problema:** rode as quatro séries do WIN por este método. `GROUPS_BY_DEFAULT` (`:284`)
manda todas para o instrumento `win`, e `Messages.market("win")` devolve `WINFUT`:

| arquivo | `rest` depois dos dois cortes | o que a tela mostra |
|---|---|---|
| `winfull-1m` (crua, SOURCE) | `full` | `WINFUT-FULL` |
| `winn-1m` (crua, export) | `n` | `WINFUT-N` |
| `winfut-1m` (crua, export) | `fut` | `WINFUT-FUT` |
| **`win-1m` (AJUSTADA por razão)** | **vazio** | **`WINFUT`** |

A série ajustada é a única cujo nome de arquivo coincide com o do instrumento, então é a
única em que o segundo corte zera o `rest` — e ela ganha o rótulo **limpo, sem sufixo**,
que é justamente o que se lê como "a série do WINFUT", enquanto as três cruas ficam com
um sufixo que parece uma variante.

O próprio arquivo documenta, em `RETIRED_BY_DEFAULT` (`:80-97`), por que essa série é
veneno: "in it a point was worth R$ 0,20 in 2026 and R$ 0,12 in 2022, so the adjustment
inflated the older years by up to 67%". E documenta que ela é escondida da **listagem**,
não do resto: "asked for by name it still opens". Isso é deliberado e certo — o problema
é que, quando ela é aberta por nome, o nome que vai para a tela é o mais canônico dos
quatro.

Ela chega à tela por dois caminhos verificados. (a) `ui/shell/MainWindow.java:412` —
`SeriesCatalog.has(asked)` consulta o arquivo, não a listagem, então um workspace salvo
antes da aposentadoria reabre `win-1m` normalmente, e a linha 423 titula o gráfico com
`SeriesCatalog.displayOf(name)`. (b) `ui/shell/Navigator.java:292-293` — basta o leitor
tirar `win-1m` de `data.retired` (`SeriesCatalog.setRetired`, `:474`) para o nó
`WINFUT` aparecer na árvore *debaixo do instrumento WINFUT*, ao lado de `WINFUT-FULL`.
E como `ROLES_BY_DEFAULT` (`:229-230`) não tem entrada para `win-1m`, `roleOf` devolve
`null` e o rótulo sai **sem** o sufixo de papel que as outras três exibem
(`· source`, `· export`) — nada distingue.

**Consequência:** o leitor mede num gráfico rotulado `WINFUT` e acredita estar na base
crua. É exatamente a "expensive mistake" que o javadoc de `ROLES_BY_DEFAULT` (`:222-224`)
diz que os papéis existem para impedir — e o mecanismo de nomes trabalha contra ele.

**Correção:** duas linhas de código e uma de bundle. Ou (i) `displayOf` devolve um
sufixo explícito quando o `rest` fica vazio mas o nome do arquivo **não** é o instrumento
canônico da fonte — por exemplo `WINFUT-AJUSTADA`, com a chave no bundle; ou (ii)
`ROLES_BY_DEFAULT` ganha `win-1m=adjusted` e `navigator.role.adjusted` entra nos dois
bundles, de modo que o rótulo carregue o aviso pelo caminho que já existe. A (ii) é mais
barata e usa o mecanismo que já foi construído para isso.

**Tentei refutar:** (1) procurei um guarda no ponto de abertura — `MainWindow:404-410` só
recusa por `segmentsOnly`, nada sobre série aposentada; nenhum aviso é escrito no console
ao abrir uma série que está em `data.retired`. (2) Verifiquei se `displayOf` não é usada
para o gráfico aposentado: é, `MainWindow:423`, sem condicional. (3) Verifiquei o valor
real do bundle nos dois idiomas — `navigator.group.win = WINFUT` idêntico em
`messages.properties` e `messages_pt_BR.properties`, portanto o rótulo é `WINFUT` em
qualquer idioma. (4) Verifiquei se `SeriesCatalogTest` cobre isso:
`aRetiredBaseIsHiddenNotDeleted` (linhas 121-142) confirma que `win-1m` abre por nome e
**não** olha para `displayOf` — o teste que existe é o que garante que o caminho
perigoso está aberto. Não caiu.

---

## Achados MÉDIA

### A7a-6 — Etapa e fração são um par único para o serviço inteiro; dois jobs simultâneos se sobrescrevem

**Onde:** `platform/JobService.java:245-247` e `:313-333`

**Trecho:**
```java
    private volatile String stage = "";

    private volatile double fraction = -1.0;
```
```java
            @Override
            public void report(double value) {
                fraction = value;
                notifyListeners();
            }
```

**Problema:** o pool tem `Math.max(2, cpus - 1)` threads (`:224-225`), portanto dois jobs
correm de fato ao mesmo tempo — e correm hoje: o `Launcher:88` submete `"sessions"` no
arranque, e nada impede o leitor de disparar outro logo em seguida. `progressFor` devolve
um `Progress` novo por handle, mas os dois escrevem nos **mesmos dois campos**. O último a
reportar vence.

Sobre "dois trabalhos do mesmo tipo enfileirados": não há deduplicação nenhuma — nem por
nome nem por chave. `submit` sempre cria um `Handle` novo e sempre enfileira. Dois cliques
no mesmo botão rodam o mesmo trabalho duas vezes, e `runningNames()` devolve o nome
repetido.

**Consequência:** `ui/shell/StatusBar.java:167-179` monta `label + " — " + stage` com
`stage` de um job e a contagem de outro, e move a barra de progresso com a fração de
quem escreveu por último. O usuário vê a barra andar para trás e o texto de uma etapa que
não pertence ao trabalho nomeado.

**Correção:** mover `stage` e `fraction` para dentro do `Handle` e a barra passar a ler
os do job que ela está mostrando (ou o agregado, explicitamente calculado). Se dois jobs
do mesmo nome não devem coexistir, `submit` precisa dizer isso — devolver o handle em
curso, ou cancelar o anterior.

**Tentei refutar:** conferi se `THREADS` poderia ser 1 (aí não haveria simultaneidade) —
`Math.max(2, ...)` garante no mínimo 2, e o comentário diz que é de propósito. Conferi se
a barra desambigua: `refresh()` só troca o rótulo por `status.jobs` com a contagem
(`:169-171`), e continua colando o `stage` global. Não caiu.

---

### A7a-7 — `onChange` não tem como desinscrever, e a troca de idioma vaza a janela inteira

**Onde:** `platform/JobService.java:233` e `:336-338`

**Trecho:**
```java
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
```
```java
    /** @param listener called on the interface thread whenever anything changes */
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }
```

**Problema:** não existe `removeChange`/`offChange`, e nada é removido em lugar nenhum
(grep por `listeners` no arquivo: só `add` em `:337` e a iteração em `:368`). O
`JobService` é criado uma vez em `Launcher:78` e vive a aplicação inteira.

O caminho concreto: `ui/shell/MainWindow.java:809-828` (`relaunch()`, o que roda quando o
leitor troca o idioma) faz `dispose()` da janela e constrói
`new MainWindow(Messages.get("app.title"), jobs)` — **com o mesmo `jobs`**. A nova
`StatusBar` chama `bind(service)` (`ui/shell/StatusBar.java:150-155`), que faz
`service.onChange(this::refresh)`. O `this::refresh` da barra antiga fica na lista para
sempre, e como é uma referência de método vinculada à instância, ele segura a `StatusBar`
morta, que segura a `MainWindow` descartada, que segura os `ChartHolder`, que seguram os
`ChartCanvas` e as séries derivadas.

**Consequência:** cada troca de idioma abandona uma janela principal inteira na memória —
com 1 M de barras é o caso normal, e o `PriceSeries` base está em `SoftReference`
(`SeriesCatalog:99`) mas os arrays derivados dentro dos canvas não estão. Além do
consumo, cada notificação de job passa a chamar `refresh()` em barras mortas, que fazem
`revalidate()` em componentes descartados. O usuário vê o aplicativo ficar mais lento e,
depois de algumas trocas numa sessão longa, ficar sem memória — o que, por A7a-1, chega
como um resultado `null` silencioso.

**Correção:** `onChange` devolver um `Runnable`/`AutoCloseable` de desinscrição, ou um
par `onChange`/`removeChange`, e `StatusBar` soltar o seu em `MainWindow.prepareToLeave()`
/ `relaunch()`.

**Tentei refutar:** procurei se `relaunch()` limparia — `prepareToLeave()`,
`storeLayout()`, `closeCharts()` e `dispose()` estão lá (`:810-819`), nenhum toca em
`jobs`. Procurei se `StatusBar.bind` seria chamado uma vez só por processo: não, é uma vez
por `MainWindow`, e `relaunch()` cria uma nova. Procurei se `listeners` seria de
referências fracas: é `CopyOnWriteArrayList<Runnable>`, forte. Não caiu.

---

### A7a-8 — `save()` trunca no lugar e reescreve o arquivo inteiro a cada chave

**Onde:** `platform/Settings.java:257-284` e `:328-336`

**Trecho:**
```java
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write("# " + banner);
```
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

**Problema:** dois defeitos no mesmo mecanismo.

(a) **Truncar no lugar.** `Files.newBufferedWriter` sem opções usa
`CREATE + TRUNCATE_EXISTING + WRITE`: o arquivo é zerado *antes* da primeira linha ser
escrita. Não há arquivo temporário nem `ATOMIC_MOVE`. Uma queda entre o truncamento e o
fechamento — queda de energia, `kill`, ou o próprio `System.exit` correndo com o gancho de
desligamento — deixa `settings.properties` ou `workspace.properties` vazio ou pela metade.
O javadoc da classe (`:64-66`) justifica gravar a cada mudança justamente porque "an
application that saves on the way out saves nothing when it does not get to leave" — o
raciocínio está certo e a implementação abre a janela de perda que ele quer fechar.

(b) **Amplificação de escrita.** Como `save()` está dentro do `put`, gravar N chaves são N
reescritas completas do arquivo, cada uma ordenando a lista de chaves (`:258-260`) e
escapando cada valor caractere a caractere (`:293-320`). `Segmentation.set`
(`Segmentation.java:133-148`) grava 1 (do `removeStartingWith`) + 3 por segmento: três
segmentos são **nove** reescritas de um `workspace.properties` que também guarda todos os
layouts de gráfico. Tudo isso na EDT, vindo de `ui/series/SeriesWindow.java:406`.

**Consequência:** (a) o leitor abre o aplicativo depois de um desligamento ruim e
encontrou tema, pasta de dados, papéis, aposentadorias e todos os segmentos zerados — sem
mensagem, porque um arquivo vazio é indistinguível de uma primeira execução
(`SettingsTest.missingFileIsEmpty`). (b) salvar uma segmentação trava a interface por
mais tempo do que precisa, e cresce com o tamanho do workspace.

**Correção:** (a) escrever em `file + ".tmp"` e `Files.move(tmp, file,
REPLACE_EXISTING, ATOMIC_MOVE)`. (b) um `putAll(Map)` / `beginBatch()`-`endBatch()` que
grave uma vez, usado por `Segmentation.set` e por `rememberCharts`.

**Tentei refutar:** conferi se `Properties`/`Files` dariam atomicidade de graça: não,
`newBufferedWriter` sem `StandardOpenOption` é CREATE+TRUNCATE por especificação. Conferi
se algum chamador já agrupa as escritas: `Segmentation.set` não, `removeStartingWith`
(`:385-392`) grava uma vez só e é o único que agrupa. Conferi se o `workspace` é
descartável a ponto de a perda não importar — é, e é o argumento da separação em dois
arquivos (`:53-55`); mas `settings.properties` **não** é, e sofre do mesmo `save()`. Não
caiu. (Não repeti os dois achados da A4: altura lida da última linha e teto de 40.)

---

### A7a-9 — `user.home` ausente derruba a classe `Settings` inteira, num inicializador estático

**Onde:** `platform/Settings.java:70`

**Trecho:**
```java
    private static final Path HOME = Path.of(System.getProperty("user.home"), ".endeavourneo");
```

**Problema:** `System.getProperty("user.home")` sem valor padrão. Se a propriedade não
estiver definida — JVM embarcada, serviço do Windows sem perfil carregado, contêiner,
ou simplesmente `-Duser.home=` — o retorno é `null`, `Path.of(null, ...)` lança
`NullPointerException` dentro do inicializador estático, a JVM converte em
`ExceptionInInitializerError`, e a partir daí **qualquer** toque em `Settings` devolve
`NoClassDefFoundError`. `Launcher:58` chama `Theme.remembered()` na terceira linha do
`main`, e `Theme` tem `private static final Settings PREFS = Settings.settings()`
(`Theme.java:63`), então isso acontece antes de existir janela: o processo morre com um
rastro de pilha que não menciona `user.home`.

A inconsistência é visível dentro da própria área: `SeriesCatalog.candidates()`
(`:191-199`) escreve `System.getProperty("user.home", ".")` e
`System.getProperty("user.dir", ".")`, com valor padrão, para o mesmo tipo de leitura.

Relacionado, no mesmo eixo do "e quando o armazenamento do sistema não está disponível":
se `HOME` existe mas não é gravável, `Files.createDirectories` (`:263`) lança `IOException`
que cai no `catch` de `:278` e vira o marcador `_unsaved` — ver A7a-10. O aplicativo roda,
nada persiste, e nada é dito ao usuário nem uma vez.

**Consequência:** num ambiente sem `user.home` o aplicativo não abre e o erro não aponta
para a causa. Num ambiente com `user.home` só de leitura, ele abre e perde tudo o que o
leitor escolher, em silêncio.

**Correção:** `System.getProperty("user.home", ".")`, alinhando com `SeriesCatalog`. E
para o caso não gravável, propagar uma vez o aviso para o console
(`MainWindow.getConsole()`) na primeira falha, em vez de só marcar `_unsaved`.

**Tentei refutar:** verifiquei se a especificação garante `user.home`: `System`
documenta-a como "geralmente" definida — não é obrigatória, e o próprio JDK a deixa
ausente em alguns embarcados. Verifiquei se algum `try/catch` acima protegeria:
`Launcher.main` não tem nenhum, e um inicializador estático não é recuperável de qualquer
forma. Verifiquei se `Path.of(null, x)` não seria tolerante: `Objects.requireNonNull` no
primeiro elemento — NPE. Não caiu.

---

### A7a-10 — Falha de gravação vira uma chave que ninguém lê, e que depois é persistida como se fosse configuração

**Onde:** `platform/Settings.java:278-283`

**Trecho:**
```java
        } catch (IOException e) {
            // Nothing useful to do and nowhere useful to say it: the reader is
            // mid-click, and a dialog about a settings file would interrupt the
            // thing they were actually doing.
            values.putIfAbsent("_unsaved", "true");
        }
```

**Problema:** o marcador `_unsaved` é escrito no **mapa em memória** e nada no repositório
o lê (grep por `_unsaved` em `src/`: uma única ocorrência, esta). Então a exceção é
engolida por completo — o comentário justifica não abrir um diálogo, o que é razoável,
mas o que foi feito no lugar não informa nada a ninguém.

Pior: como é escrito em `values`, ele passa a fazer parte do conteúdo. Se uma gravação
posterior tiver sucesso, `_unsaved=true` é escrito no arquivo do leitor de forma
permanente (nunca é removido), aparece em `keysStartingWith("")` e volta em todo
`load()`.

**Consequência:** o leitor perde o tema, a pasta de dados ou uma segmentação inteira e
não recebe nem uma linha de aviso; e ganha uma chave espúria no arquivo que o javadoc da
classe (`:57-62`) promete que é legível e copiável à mão.

**Correção:** manter um `boolean` transiente fora de `values` e expor um
`lastSaveFailed()` que a barra de status consulte uma vez; ou escrever a falha em
`System.err`, que o console já captura (`Launcher:99`,
`window.getConsole().captureStandardOutput()`). O que não se deve fazer é misturar
diagnóstico com dado do usuário.

**Tentei refutar:** procurei um leitor de `_unsaved` em produção e em teste — não existe.
Procurei se `save()` seria chamado de novo com sucesso e limparia: não há remoção em lugar
nenhum. Não caiu.

---

### A7a-11 — A varredura de disco engole o erro por um caminho e escapa por outro

**Onde:** `platform/SeriesCatalog.java:490-527`

**Trecho:**
```java
        try (Stream<Path> files = Files.walk(folder, 3)) {
            return files
                    .filter(file -> file.getFileName().toString().endsWith(SUFFIX))
                    .filter(MarketFile::isSeries)
                    ...
        } catch (IOException e) {
            return List.of();
        }
```

**Problema:** dois problemas complementares.

(a) `Files.walk` só lança `IOException` **na abertura**. Um erro durante a travessia — uma
subpasta sem permissão de leitura, um ponto de montagem que caiu, um link simbólico
quebrado — é embrulhado em `java.io.UncheckedIOException`, que é `RuntimeException` e
**não** é pego por `catch (IOException e)`. Ele sobe pela `namesIn`, pela `names()`, e sai
por `Navigator.treeModel()` (`ui/shell/Navigator.java:168`) na EDT.

(b) O `catch` que existe devolve `List.of()`. Uma pasta que existe e não pode ser lida
fica indistinguível de uma pasta sem séries — e o caminho de "sem séries"
(`Navigator:186-189`) mostra `navigator.noSeries` com o caminho, sugerindo ao leitor que
ele apontou para o lugar errado quando o problema é outro.

O javadoc de `candidates()` (`:184-189`) sustenta o princípio contrário e com todas as
letras: "A series that is not found must say so, not be replaced by a different one".

**Consequência:** (a) a árvore de navegação lança e a janela fica sem o nó de séries, ou o
arranque quebra; (b) o leitor procura um problema de configuração que não existe.

**Correção:** `catch (IOException | UncheckedIOException e)` e, nos dois casos, escrever a
causa (`e.getMessage()`) no console em vez de devolver a lista vazia calada. O sinal de
"pasta ilegível" e o de "pasta vazia" precisam ser diferentes na tela.

**Tentei refutar:** conferi na especificação de `Files.walk` que o `IOException` de
travessia é reembrulhado — é, e o `Stream` só o lança no terminal (`toList()`), que está
dentro do `try` mas não é alcançado pelo `catch` tipado. Conferi se algum chamador
protege: `Navigator.treeModel()`, `Segmentable.keys()` e `ReplayFeed.available()` chamam
`names()` sem `try`. Conferi se o `try-with-resources` fecharia o stream mesmo assim:
fecha — o recurso **está** correto e não é achado. Não caiu.

---

### A7a-12 — A listagem reabre as configurações e o cabeçalho de cada arquivo, e roda na EDT

**Onde:** `platform/SeriesCatalog.java:495-523`, com `:245-257`, `:381-399`, `:458-471`

**Trecho:**
```java
                    .filter(MarketFile::isSeries)
                    ...
                    .filter(name -> !retired().contains(name))
                    ...
                    .filter(name -> Files.isRegularFile(folder.resolve(relativeTo(name))))
```

**Problema:** cada um desses predicados roda **por arquivo**, e cada um custa I/O ou
reparse:

- `MarketFile::isSeries` abre um `FileChannel` e lê o cabeçalho de 24 bytes
  (`domain/market/MarketFile.java:197-209`) — para todo `.bin` encontrado até três níveis
  abaixo, inclusive as pastas de ticks;
- `retired()` (`:458`) refaz `Settings.get(...).split(",")` e monta um `LinkedHashSet`
  novo a cada chamada;
- `relativeTo(name)` (`:573`) chama `scaleOf` → `stated(SCALES_KEY, "")` e `groupOf` →
  `groups()` → `stated(GROUPS_KEY, ...)` — mais dois parses do mapa por arquivo;
- e mais um `Files.isRegularFile`.

E tudo isso corre na thread de interface: `ui/shell/Navigator.java:168`
(`List<String> available = SeriesCatalog.names();` dentro de `treeModel()`, chamado ao
construir a janela e de novo a cada `navigator.setModel(Navigator.treeModel())` em
`MainWindow:833`), `ui/series/Segmentable.java:67` e `:149`.

**Consequência:** com poucas séries é imperceptível; com uma pasta de exports de ticks
por mercado — que é para onde o projeto está indo (`ticksOf`, `:587`) — a árvore passa a
custar uma abertura de arquivo por sessão exportada, na EDT, toda vez que ela é
reconstruída. É a mesma família dos quatro achados de varredura na EDT que a auditoria já
tem.

**Correção:** hastear `retired()`, `groups()` e `stated(SCALES_KEY)` para fora do stream
(três variáveis locais antes do `try`) — corrige o reparse sem mudar semântica. E a
varredura em si deveria ir pelo `JobService` (ver a seção dedicada abaixo).

**Tentei refutar:** conferi se o stream é preguiçoso a ponto de os predicados rodarem uma
só vez: não — `filter` roda o predicado por elemento, e `retired()`/`stated()` não têm
memoização nenhuma (`stated` monta um `LinkedHashMap` novo em toda chamada, `:246`).
Conferi se `Navigator.treeModel()` já roda fora da EDT: é chamado do construtor da
`MainWindow` e do callback de `SeriesWindow.open` — EDT nos dois. Conferi se
`MarketFile.isSeries` lê o arquivo inteiro: não, só 24 bytes — por isso este achado é
MÉDIA e não ALTA. Não caiu.

---

### A7a-13 — `open()` é um verifica-depois-age: duas threads leem a mesma série duas vezes, e o cache nunca percebe o arquivo mudar

**Onde:** `platform/SeriesCatalog.java:601-620`

**Trecho:**
```java
    public static Optional<PriceSeries> open(String name) throws IOException {
        SoftReference<PriceSeries> held = LOADED.get(name);
        PriceSeries cached = held == null ? null : held.get();

        if (cached != null) {
            return Optional.of(cached);
        }

        Path file = fileOf(name);

        if (!MarketFile.isSeries(file)) {
            return Optional.empty();
        }

        PriceSeries series = MarketFile.read(file);

        LOADED.put(name, new SoftReference<>(series));
```

**Problema:** dois defeitos.

(a) O `ConcurrentHashMap` protege cada operação isolada, não a sequência. Duas threads que
chegam com o cache frio leem os 30 MB as duas, e a segunda `put` sobrescreve a primeira:
saem **dois** `PriceSeries` distintos para a mesma série, e o javadoc da classe promete o
contrário ("Read once", `:49-55`), e `SeriesCatalogTest.aBaseIsReadOnce` (linha 104)
afirma `assertSame`. O caminho existe: `ReplayFeed.warm()` roda `SeriesCatalog.open` numa
thread de job (`Launcher:88` → `ReplayFeed.java:164`) enquanto a EDT abre um gráfico
(`MainWindow:354`).

(b) O cache é invalidado só por `setFolder`, `useFolderForTest` e `forget()` — nunca por
mudança do arquivo. E reconstruir a fonte a partir dos exports crus é um fluxo declarado
do projeto (`:220-224`, "the source can be rebuilt from them"). Reconstruída com o
aplicativo aberto, todo gráfico continua desenhando a versão anterior, sem aviso.

**Consequência:** (a) 30 MB e ~1 s desperdiçados, e duas instâncias onde o código assume
uma; (b) o leitor reimporta, reabre o gráfico, e vê os dados antigos — o pior modo de
falhar de um cache, porque parece que a importação não funcionou.

**Correção:** (a) `LOADED.computeIfAbsent` não serve (seguraria o bin do mapa por um
segundo); o padrão certo é guardar um `SoftReference<CompletableFuture<PriceSeries>>` ou
sincronizar por nome. (b) guardar `Files.getLastModifiedTime` junto e conferir na leitura
do cache — é uma chamada de metadado, barata.

**Tentei refutar:** procurei sincronização no chamador — `MainWindow.seriesFor` (`:352`)
não sincroniza, `ReplayFeed.sessions()` usa `KNOWN.computeIfAbsent`
(`ReplayFeed.java:150`) que serializa por *feed*, não por série, e não impede a corrida
com a EDT. Procurei se `forget()` é chamado em algum ponto de reimportação: só em
`SeriesCatalogTest`. Não caiu.

---

### A7a-14 — `useFolderForTest` é pública e move o estado global que o próprio arquivo documenta ter destruído 90 MB

**Onde:** `platform/SeriesCatalog.java:167-178`, contra o comentário de `:505-519`

**Trecho:**
```java
    /**
     * Points at a folder without remembering it.
     *
     * <p>Apart from {@link #setFolder}, which decides and persists. This one is
     * for looking: a settings page previewing another folder, and the tests,
     * which must not write into the settings of whoever runs the suite.</p>
     */
    public static void useFolderForTest(Path folder) {
        SeriesCatalog.folder = folder;

        LOADED.clear();
    }
```

**Problema:** o javadoc oferece esta chamada a "a settings page previewing another
folder" — isto é, a código de produção. E o que ela faz é exatamente o que o comentário
de `namesIn` (`:505-519`) descreve como já tendo custado dados reais:

> "The first version answered that by pointing the catalog at it for the length of the
> walk and putting it back afterwards. That is a race, and it cost real data ... A test
> then wrote a twenty-four-byte header over ninety megabytes of exported ticks — because
> it asked where a file goes and was told the wrong place."

`namesIn(Path)` foi construída para não precisar disso. `fileOf(String)` (`:562`), porém,
continua lendo o campo global — de modo que uma página de configurações que "só está
espiando" outra pasta muda para onde **todos** os gráficos e todas as escritas apontam,
sem persistir e sem avisar ninguém. O nome `...ForTest` não impede o uso: é `public
static`, sem `@VisibleForTesting`, e o javadoc convida.

**Consequência:** o mesmo desastre de novo, com o mesmo mecanismo — só que agora ele tem
uma porta com nome e documentação.

**Correção:** ou apagar a frase "a settings page previewing another folder" do javadoc e
deixar claro que o único uso legítimo é o teste; ou, melhor, oferecer o que a página de
configurações realmente precisa — `namesIn(Path)` já é pública (`:490`) e responde sem
mover nada. Se a página precisar do caminho de um arquivo numa pasta arbitrária, expor
`fileIn(Path folder, String name)` construído sobre `relativeTo`, que já existe e já é
livre de estado global (`:573`).

**Tentei refutar:** confirmei por grep que nenhum código de produção chama hoje
`useFolderForTest` (só `SeriesCatalogTest`, linhas 48, 70, 87, 99, 112, 130, 153, 161) —
por isso é MÉDIA e não ALTA: o risco está documentado e aberto, não realizado. Confirmei
que `fileOf` de fato depende do campo global (`:563`, `folder().resolve(...)`). Não caiu.

---

### A7a-15 — Os testes do `SeriesCatalog` leem as configurações reais da máquina de quem roda a suíte

**Onde:** `src/test/java/.../platform/SeriesCatalogTest.java:121-142` e `215-234`, contra
`platform/SeriesCatalog.java:245-257`

**Trecho (o teste):**
```java
        assertEquals(List.of("winn-1m"), SeriesCatalog.names(),
                "the retired base was offered in the listing");
```
**Trecho (o que isso lê):**
```java
        for (String each : Settings.settings().get(key, fallback).split(",")) {
```

**Problema:** `SeriesCatalog` tem costura para a **pasta** (`useFolderForTest`) e nenhuma
para as **configurações**. `retired()`, `roles()`, `groups()` e `scaleOf()` leem
`Settings.settings()`, que é o `~/.endeavourneo/settings.properties` da máquina real. Um
leitor que tire `win-1m` de `data.retired` pela interface (`setRetired`, `:474`) quebra
`aRetiredBaseIsHiddenNotDeleted`; um que acrescente `data.groups` quebra
`aScaleIsNotAMarket`; um que acrescente `data.scales` quebra `theScaleComesFromTheName`.

É a mesma flakiness que o javadoc de `Segmentation.useForTest`
(`Segmentation.java:80-94`) descreve como "the flakiness this codebase has already paid
for once" — e a solução que existe lá (uma costura de armazenamento) não existe aqui.

**Consequência:** a suíte passa ou falha por causa do que estiver salvo na máquina, e a
falha aparece como "o SeriesCatalog quebrou" quando na verdade alguém mexeu numa
preferência. Pior: o teste passa na máquina do autor e falha na do outro, ou no CI.

**Correção:** dar ao `SeriesCatalog` a mesma costura que a `Segmentation` já tem — um
`private static Settings store` com `store()` devolvendo `Settings.settings()` por padrão,
e um `useForTest(Path)`. As nove chamadas a `Settings.settings()` no arquivo passam por
`store()`.

**Tentei refutar:** verifiquei se `useFolderForTest` isolaria as configurações também: não,
ela só mexe no campo `folder` e no `LOADED` (`:174-178`). Verifiquei se os defaults
tornariam a leitura inócua: `get(key, fallback)` só usa o `fallback` quando a chave está
**ausente** — bastando a chave existir, o valor da máquina vence. Verifiquei se o `@AfterEach`
protege: ele chama `useFolderForTest(null)` e `forget()`, nada sobre `Settings`. Não caiu.

---

### A7a-16 — `Segmentation.set` apaga tudo e só depois começa a escrever, uma gravação de arquivo por vez

**Onde:** `platform/Segmentation.java:133-148`

**Trecho:**
```java
    public static void set(String series, List<Segment> segments) {
        Settings workspace = store();

        workspace.removeStartingWith(PREFIX + series + ".");

        for (int at = 0; at < segments.size(); at++) {
            Segment each = segments.get(at);

            workspace.put(keyOf(series, at, "name"), each.name());
            workspace.put(keyOf(series, at, "from"), each.from().toString());

            if (each.to() != null) {
                workspace.put(keyOf(series, at, "to"), each.to().toString());
            }
        }
    }
```

**Problema:** `removeStartingWith` grava o arquivo (`Settings.java:389-391`) e cada `put`
grava de novo (`Settings.java:335`). Para três segmentos com data final: **dez** reescritas
completas do `workspace.properties`, cada uma truncando o arquivo no lugar (A7a-8a).

A janela de perda é concreta: a primeira gravação — a que **apaga** todos os segmentos —
já está comprometida em disco antes de a primeira chave nova ser escrita. Uma falha em
qualquer ponto entre a linha 136 e o fim do laço deixa a série com **zero** segmentos no
disco, e é justamente aí que a `segmentsOnly` (`:213`) transforma a série em
inabrível: nome sem `#` numa série trancada é recusado (`MainWindow:404-410`), e os nomes
com `#` que o workspace guardava agora apontam para segmentos que não existem
(`segmentIn` devolve `null`, `:196`).

**Consequência:** depois de uma falha durante o salvamento da segmentação, o leitor abre o
aplicativo e a série trancada não abre de jeito nenhum — nem inteira (a trava recusa) nem
por segmento (sumiram). Não há botão que resolva; é preciso editar o arquivo à mão.

**Correção:** um `Settings.putAll(Map)` que grave uma vez, ou um par
`beginBatch()`/`endBatch()`. Com a gravação atômica de A7a-8a, a operação inteira passa a
ser tudo-ou-nada.

**Tentei refutar:** conferi se `removeStartingWith` só grava quando algo muda —
`if (changed) save()` (`:389`), então numa série sem segmentos anteriores são 9 e não 10;
não muda o argumento. Conferi se a trava sobreviveria: sim, `ONLY` tem prefixo próprio
(`:260-268`) e é precisamente por isso que a série fica *trancada e vazia*, que é o pior
dos dois estados. Conferi se `SeriesWindow` reescreveria em seguida: `:406` chama `set`
uma vez, sem repetição de segurança. Não caiu.

---

### A7a-17 — O comentário promete pular uma entrada ruim; o código trunca a lista inteira

**Onde:** `platform/Segmentation.java:105-130`

**Trecho:**
```java
        for (int at = 0; ; at++) {
            String name = workspace.get(keyOf(series, at, "name"), null);
            String from = workspace.get(keyOf(series, at, "from"), null);

            if (name == null || from == null) {
                return found;
            }

            try {
                ...
            } catch (DateTimeParseException | IllegalArgumentException e) {
                // A hand-edited file. The entry is skipped rather than the whole
                // segmentation refused: losing one segment is recoverable by
                // typing it again, and losing all of them because of one bad
                // date is not what the reader would have chosen.
                continue;
            }
```

**Problema:** a tolerância só vale para **data mal formada**. Se a edição à mão apagar a
linha `segments.X.0.name` ou `segments.X.0.from` — o erro mais provável de todos, porque
apagar uma linha é mais fácil que digitar uma data inválida — a linha 110-113 dá `return`
e devolve a lista **vazia**, descartando em silêncio os segmentos 1, 2, 3… que estão
perfeitamente escritos logo abaixo. Exatamente o "losing all of them" que o comentário
diz não ser o que o leitor teria escolhido.

**Consequência:** o leitor apaga uma linha, reabre, e a segmentação inteira sumiu da
árvore e do diálogo — inclusive os segmentos que ele não tocou. E numa série com
`segmentsOnly` isso a torna inabrível (mesmo estado descrito em A7a-16).

**Correção:** o laço não deve terminar no primeiro índice vazio. Ou varrer as chaves de
fato existentes (`store().keysStartingWith(PREFIX + series + ".")`, que já existe em
`Settings:370`) e agrupar por índice, ou continuar até um número de faltas consecutivas
maior que um. A primeira é a correta, porque não inventa heurística.

**Tentei refutar:** verifiquei se `set` sempre grava índices contíguos — grava (`:138`),
então o defeito só aparece na edição à mão. Mas a edição à mão é o motivo declarado de o
armazenamento ser texto simples (`Settings.java:57-62`) e é o caso que o próprio `catch`
existe para tratar. Verifiquei se o `continue` causa laço infinito: não, o `continue`
executa o `at++` do `for`. Não caiu.

---

### A7a-18 — O bundle é um estático não-volátil trocado em tempo de execução e lido de threads de job

**Onde:** `platform/Messages.java:50` e `:141-154`

**Trecho:**
```java
    private static ResourceBundle bundle = ResourceBundle.getBundle(BASE);
```
```java
    public static void setLocale(Locale locale) {
        ...
        bundle = ResourceBundle.getBundle(BASE, locale,
                ResourceBundle.Control.getNoFallbackControl(
                        ResourceBundle.Control.FORMAT_PROPERTIES));
    }
```

**Problema:** `bundle` não é `volatile` e é reatribuído em tempo de execução.
`Language.install()` (`Language.java:96`) roda na EDT — do `Launcher:93` e do
`MainWindow.relaunch()` (`:821`). E `Messages.get` é chamado de dentro de um job, na
thread do pool: `ui/shell/MainWindow.java:873`,
`progress.say(Messages.get("job.sampleStage", i))`, executa em `job-N`.

Sem `volatile` não há relação de acontece-antes entre a escrita na EDT e a leitura na
thread de trabalho: a thread pode continuar lendo o bundle antigo indefinidamente, e — o
caso pior — pode observar a referência publicada de forma incompleta.

**Consequência:** o leitor troca o idioma com um job em curso e a barra de status continua
mostrando a etapa no idioma anterior enquanto o resto da janela já mudou. É pequeno hoje
porque só há um job de verdade; deixa de ser pequeno assim que o backtest for para o
`JobService` e começar a reportar etapas.

**Correção:** `private static volatile ResourceBundle bundle;`. Uma palavra.

**Tentei refutar:** verifiquei se as leituras de job são feitas na EDT antes de submeter —
`Messages.get("job.sample")` em `MainWindow:863` é, mas `Messages.get("job.sampleStage",
i)` em `:873` está **dentro** da lambda, ou seja, na thread do pool. Verifiquei se
`ResourceBundle` seria seguro por dentro: a *instância* é imutável e segura; a **variável
estática que aponta para ela** é que não está publicada com segurança. Não caiu.

---

### A7a-19 — `Appearance.install` devolve texto de interface em inglês literal, que vai para a barra de status

**Onde:** `platform/Appearance.java:78`, `:84`, `:94`, `:97`

**Trecho:**
```java
            return "FlatLaf dark (" + theme.getLabel() + " palette missing)";
        }

        if (apply(theme.getLookAndFeelClass())) {
            tightenSpacing();

            return "FlatLaf " + theme.getLabel();
        }
```
```java
        if (apply(UIManager.getSystemLookAndFeelClassName())) {
            return "system (FlatLaf absent)";
        }

        return "Swing default";
```

**Problema:** convenção da casa: texto de interface só no bundle. Estas quatro cadeias são
texto de interface — o `@return` da linha 60 diz "so the console and status bar can say",
e é o que acontece: `ui/shell/MainWindow.java:846` faz `status.say(installed)` **cru**, e
`:845` o interpola em `console.appearance`. `Launcher:102` faz o mesmo no arranque.
`theme.getLabel()` (`"light"`, `"dark"`, `"night"`) também é código interno indo direto
para a tela — o bundle já tem `theme.light`/`theme.dark`/`theme.night`, usados por
`AppearancePage` para os botões, de modo que a mesma coisa é escrita de dois jeitos na
mesma janela.

**Consequência:** o leitor em português troca o tema e a barra de status responde
"FlatLaf night"; se a paleta faltar, "FlatLaf dark (night palette missing)". Nenhuma das
duas é traduzível, e a segunda é uma frase de diagnóstico onde deveria haver uma mensagem.

**Correção:** `install` devolver um valor estruturado — o `Theme` mais um enum de desfecho
(`FLATLAF`, `FLATLAF_SEM_PALETA`, `SISTEMA`, `PADRÃO`) — e quem mostra montar o texto com
`Messages.get`. As quatro chaves entram nos dois bundles.

**Tentei refutar:** conferi se `installed` é só para o console (onde inglês seria
defensável): não, `MainWindow:846` e `AppearancePage:127` o mandam para
`status.say`, que é interface. Conferi se existe chave equivalente no bundle: existem
`theme.light/dark/night` e `console.appearance`, mas nenhuma para os quatro desfechos.
Não caiu.

---

## Achados BAIXA

### A7a-20 — Três javadocs órfãos: a documentação está colada no membro errado

**Onde:** `platform/SeriesCatalog.java:292-297`, `platform/Messages.java:81-91`,
`platform/Appearance.java:100-110`

**Trecho** (`Appearance`, o mais grave dos três):
```java
     * @return whether it worked; false when FlatLaf or the file is absent
     */
    /**
     * Unregisters every palette any theme may have registered.
     ...
     */
    private static void forgetPalettes() {
```

**Problema:** em Java só o **último** bloco javadoc antes de uma declaração é considerado.
Nos três casos há dois blocos empilhados, e o primeiro é descartado — junto com o membro
que ele realmente descrevia:

- `Appearance`: o `@return` de `registerPalette` sobrou sobre `forgetPalettes`, que é
  `void`; `registerPalette` (`:134`) ficou sem javadoc nenhum. Um `@return` sobre um
  método `void` é erro na ferramenta de javadoc do JDK — `mvn javadoc:javadoc` quebra.
- `Messages`: o bloco com `@param key/@param fallback` que descreve `orElse` está colado em
  `market` (`:106`); `orElse` (`:110`) ficou sem doc.
- `SeriesCatalog`: `@return the market a series belongs to`, que é de `groupOf`, está
  colado em `displayOf` (`:314`); `groupOf` (`:337`) ficou sem doc.

**Consequência:** a documentação gerada mente sobre três métodos, e a compilação de
javadoc não passa.

**Correção:** mover cada bloco para cima do método que ele descreve.

**Tentei refutar:** reli os três trechos para confirmar que são de fato dois blocos e não
um só quebrado — são: cada um fecha com `*/` e abre com `/**` de novo. Não caiu.

---

### A7a-21 — `replay.speed` está declarada duas vezes nos dois bundles

**Onde:** `src/main/resources/messages.properties:243` e `:256` (e as duas equivalentes em
`messages_pt_BR.properties`)

**Trecho:**
```
243: replay.speed = bars/s
256: replay.speed = speed
```

**Problema:** `Properties` não reclama de chave repetida — a última vence em silêncio. O
rótulo `bars/s` é código morto; quem escreveu a linha 243 acredita que ela está na tela.

**Consequência:** o leitor vê "speed" onde a intenção era "bars/s" (ou vice-versa), e
quem for corrigir vai editar a linha errada primeiro.

**Correção:** apagar a duplicata e, se as duas legendas são necessárias, dar-lhes chaves
distintas.

---

### A7a-22 — O javadoc do `Theme` ainda descreve o nó de `Preferences` que não existe mais

**Onde:** `platform/Theme.java:55-63`

**Trecho:**
```java
    /**
     * The preferences node, ONE for the whole application.
     *
     * <p>Calling {@code userNodeForPackage} from each class creates one node per
     * package: what the menu in {@code ui} writes, the startup code in {@code
     * app} never reads. ...
     */
    private static final Settings PREFS = Settings.settings();
```

**Problema:** não há nó de preferências nem `userNodeForPackage` no repositório — o
`Settings` explica em `:57-62` que tudo saiu do `java.util.prefs` justamente por causa do
registro do Windows. O comentário descreve uma implementação aposentada, e o campo se
chama `PREFS`. Convenção da casa: comentário explica o porquê; este explica o porquê de
outro código.

**Correção:** trocar o bloco por uma linha que diga o porquê que ainda vale (uma única
loja para toda a aplicação) e renomear o campo para `STORE` ou `SETTINGS`.

---

### A7a-23 — Toda gravação muda uma linha do arquivo, o que desfaz metade da razão de ordenar

**Onde:** `platform/Settings.java:266-269`, contra `:245-249`

**Trecho:**
```java
                out.write("# " + banner);
                out.newLine();
                out.write("# " + LocalDateTime.now());
                out.newLine();
```

**Problema:** o javadoc logo acima justifica escrever à mão em vez de usar
`Properties.store` porque "the same settings come out in a different order every time and
a diff is useless". O carimbo de data e hora recoloca uma linha que muda em **toda**
gravação — e as gravações são muitas (A7a-8b). Um `diff` de "mudou alguma coisa?" nunca
volta vazio.

**Correção:** tirar o carimbo, ou mantê-lo num comentário que só é reescrito quando o
conteúdo mudou de fato (comparar o texto gerado com o do arquivo antes de escrever — o que
também evitaria a maior parte das reescritas de A7a-8b).

*(Nota, não achado: `LocalDateTime.now()` usa o fuso do sistema, mas isso é um comentário
de cabeçalho, não uma agregação de dados — não é o problema de fuso que este projeto já
pagou.)*

---

### A7a-24 — Bandeira desconhecida no arranque é ignorada sem uma palavra, e o tema é regravado toda vez

**Onde:** `Launcher.java:60-74`

**Trecho:**
```java
        for (String arg : args) {
            if (arg.startsWith("--")) {
                String name = arg.substring(2);
                Theme requested = Theme.of(name);

                // Theme.of falls back to LIGHT for anything it does not know,
                // so an unrecognised flag would silently switch the theme.
                // Only accept the value when it really matched.
                if (requested.getLabel().equalsIgnoreCase(name)) {
                    theme = requested;
                }
            }
        }

        theme.remember();
```

**Problema:** o guarda está certo e é bem justificado — mas o `else` não existe.
`--darkk`, `--noite` ou `--help` são engolidos: o aplicativo abre no tema anterior e nada
diz. O javadoc da classe (`:39`) anuncia `Launcher [--light|--dark|--night]`, então há uma
interface de linha de comando declarada que não reporta erro de uso.

E `theme.remember()` grava incondicionalmente, mesmo quando nenhum argumento veio e nada
mudou: um `save()` completo do `settings.properties` (com o carimbo de A7a-23) a cada
arranque.

**Correção:** um `System.err.println` (que o console captura, `:99`) para a bandeira não
reconhecida; e `remember()` só quando `theme != Theme.remembered()`.

---

### A7a-25 — O javadoc do `Progress` diz que a barra some; ela fica indeterminada

**Onde:** `platform/Progress.java:34` contra `ui/shell/StatusBar.java:177-184`

**Trecho:**
```java
     * @param fraction from 0 to 1; values outside that range hide the bar
```
contra
```java
        if (fraction >= 0.0 && fraction <= 1.0) {
            progress.setIndeterminate(false);
            progress.setValue((int) Math.round(fraction * 100));
        } else {
            // No number reported: an indeterminate bar still says "alive",
            // which is the point. A bar stuck at zero says "hung".
            progress.setIndeterminate(true);
        }
```

**Problema:** o contrato publicado e o comportamento discordam. Quem escrever um job
lendo o javadoc vai reportar `-1` esperando esconder a barra e vai ganhar uma barra
correndo indefinidamente — que é o oposto do que ele quis dizer. Além disso, o parâmetro
se chama `fraction` na interface e `value` na implementação (`JobService:317`).

**Correção:** corrigir o javadoc para "values outside that range make the bar
indeterminate", que é o comportamento — e é o comportamento melhor.

---

### A7a-26 — O espaçamento apertado se perde quando o FlatLaf não está presente

**Onde:** `platform/Appearance.java:81-97`

**Trecho:**
```java
        if (apply(theme.getLookAndFeelClass())) {
            tightenSpacing();

            return "FlatLaf " + theme.getLabel();
        }

        return installWithoutFlatLaf();
```

**Problema:** `tightenSpacing()` é chamado nos dois ramos de FlatLaf e em nenhum dos dois
de `installWithoutFlatLaf`. As sete chaves que ele põe (`TabbedPane.tabHeight`,
`SplitPane.dividerSize`, `Table.intercellSpacing`…) são todas do `UIManager` e valem para
qualquer look and feel que as respeite. Pode ser deliberado, mas nada diz que é — e o
javadoc do método (`:170-175`) argumenta pelo espaçamento denso como identidade do
produto, sem condicionar ao FlatLaf.

**Correção:** ou chamar `tightenSpacing()` também no caminho sem FlatLaf, ou escrever uma
linha dizendo por que não.

---

### A7a-27 — `secondsOf` pode lançar de dentro de um comparador

**Onde:** `platform/SeriesCatalog.java:423-440` e `:449-455`

**Trecho:**
```java
        long count = Long.parseLong(scale.substring(0, scale.length() - 1));
```

**Problema:** `isScale` (`:402-414`) só verifica que todos os caracteres menos o último
são dígitos — não verifica quantos. Um nome de arquivo com uma parte como
`99999999999999999999s` passa por `isScale` e faz `Long.parseLong` lançar
`NumberFormatException`, que sai de dentro do `Comparator` devolvido por `coarsestFirst()`
— ou seja, no meio de um `sort`, na EDT, montando a árvore.

**Consequência:** um arquivo com nome estranho na pasta de dados derruba a árvore de
navegação em vez de simplesmente não aparecer. É um nome improvável; por isso é BAIXA.

**Correção:** `try { ... } catch (NumberFormatException e) { return -1; }` — o `-1` já é
o valor de "ilegível" e já tem tratamento no comparador (`:453`).

---

### A7a-28 — `monospaced` fixa "Consolas" sem conferir se existe

**Onde:** `platform/Appearance.java:193-199`

**Trecho:**
```java
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "Consolas"
                : Font.MONOSPACED;

        return new Font(name, Font.PLAIN, size);
```

**Problema:** `new Font` com uma família desconhecida não falha: devolve silenciosamente
`Dialog`, que é **proporcional**. O javadoc do método diz que a razão de existir é que
"numbers in a column only line up in a fixed-width font" — e o modo de falhar entrega
exatamente o oposto, sem sinal. Numa instalação do Windows sem Consolas (raro, mas o
código já assume "win" pelo nome do SO, que também vale para o Wine e para imagens
enxutas), o console e os números do gráfico ficam desalinhados e ninguém sabe por quê.

**Correção:** conferir contra
`GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()` e cair
para `Font.MONOSPACED` quando não estiver lá.

---

### A7a-29 — `ThemeSwitchTest` deixa o look and feel trocado para o resto da suíte

**Onde:** `src/test/java/.../platform/ThemeSwitchTest.java:50-115`

**Problema:** os quatro testes chamam `Appearance.install(...)` e nenhum restaura o estado
inicial; não há `@AfterEach`. `UIManager.setLookAndFeel` é global à JVM, e o Surefire roda
tudo no mesmo processo. Qualquer teste posterior que meça cor, tamanho preferido ou
inserções de borda (`ui/shell/StatusStripTest`, `CollapsiblePaneTest`,
`ui/chart/ChartCanvasTest`…) passa a depender da ordem de execução.

**Consequência:** falha que só aparece quando a ordem muda — o tipo mais caro de investigar.

**Correção:** um `@AfterEach` que reinstale `Theme.remembered()`, ou `@BeforeEach` que
capture e `@AfterEach` que restaure o look and feel anterior.

**Tentei refutar:** verifiquei se o JUnit isola por classe: não isola o `UIManager`;
`@Isolated`/fork por classe não estão configurados no `pom.xml`. Não caiu.

---

### A7a-30 — `mnemonic` devolve um ponto de código como se fosse um código de tecla

**Onde:** `platform/Messages.java:122-130`

**Trecho:**
```java
        return Character.toUpperCase(letter.charAt(0));
```

**Problema:** funciona por coincidência para A-Z, onde `KeyEvent.VK_A == 65 == 'A'`. Para
qualquer letra fora do ASCII — e o javadoc da classe fala explicitamente de mnemônicos que
"têm de diferir por idioma" — o valor não corresponde a nenhuma constante `VK_`, e
`setMnemonic` simplesmente não sublinha nada. Sem aviso.

**Correção:** documentar a restrição a A-Z/0-9 no javadoc do método (o mais barato), ou
usar `KeyEvent.getExtendedKeyCodeForChar(char)`.

---

## O JobService está sendo usado?

**Não, praticamente. Há exatamente dois `submit` no repositório inteiro, e um deles é uma
demonstração que o próprio comentário manda apagar.**

Grep por `\.submit\(|JobService` em `src/main/java` — 15 ocorrências, todas conferidas:

| onde | o que é |
|---|---|
| `Launcher.java:78` | `new JobService()` — a única instância |
| `Launcher.java:83` | gancho de desligamento que chama `close()` |
| **`Launcher.java:88`** | **`jobs.submit("sessions", ...)` → `ReplayFeed.warm()` — o ÚNICO trabalho real** |
| **`MainWindow.java:863`** | **`jobs.submit(Messages.get("job.sample"), ...)` — o `runSampleJob`, cujo javadoc em `:854-860` diz "A task that does nothing useful" e "Delete it once there is real work to run"** |
| `MainWindow.java:131, 161` | o campo e o parâmetro do construtor |
| `StatusBar.java:100, 150` | a barra que observa |
| `JobService.java:57, 249, 277` | a própria classe |

**E os caminhos pesados estão todos na EDT, ao lado dele:**

1. **`SeriesCatalog.open` — a leitura da série inteira.**
   `ui/shell/MainWindow.java:354`, dentro de `seriesFor`, chamado de `open(String)`
   (`:436-438`), que é acionado pelo botão da barra de ferramentas (`:896`), pelo duplo
   clique na árvore e pela restauração do workspace. `MarketFile.read`
   (`domain/market/MarketFile.java:107-113`) aloca `long[size]` + cinco `double[size]` e
   lê o arquivo inteiro: para 1 M de barras são ~48 MB e cerca de um segundo, **na thread
   de interface**. Restaurar N gráficos no arranque paga isso N vezes seguidas (o cache
   `LOADED` ajuda só a partir do segundo gráfico da mesma série).

2. **`SeriesCatalog.names` — a varredura do disco.**
   `ui/shell/Navigator.java:168` (dentro de `treeModel()`, chamada do construtor da
   `MainWindow` e de novo em `MainWindow:833` sempre que a `SeriesWindow` fecha);
   `ui/series/Segmentable.java:67` e `:149`. Abre o cabeçalho de todo `.bin` até três
   níveis abaixo (A7a-12).

3. **`ReplayFeed.available()` → `SeriesCatalog.names()`** (`ui/replay/ReplayFeed.java:100`)
   e `ReplayFeed.sessions()` → `SeriesCatalog.open` (`:164`). O `warm()` cobre o caso do
   arranque; a mesma `available()` chamada pela interface do replay não é coberta por nada.

O `JobService` **já oferece o caminho certo** e está provado por testes: o trabalho corre
fora da EDT (`JobServiceTest:49-66`), o resultado chega na EDT (`:70-86`), a falha chega a
um handler (`:90-110`), há progresso (`Progress`), cancelamento e nome na barra de status
(`:154-175`). A infraestrutura está pronta e o único trabalho de verdade que passa por
ela é o aquecimento de sessões de replay. Os quatro locais que a auditoria já achou
varrendo 1 M de barras na EDT (`ChartCanvas`, `ChartHeader`, `LineStyle`, `StudyStack`)
têm companhia: `MainWindow.seriesFor` é o quinto, e é o mais caro de todos, porque é a
leitura do arquivo em si.

**Recomendação, com as linhas:** `MainWindow.open(String)` (`:390-438`) deveria abrir o
gráfico vazio e submeter `seriesFor` a `jobs.submit(...).whenDone(canvas::setSeries)
.whenFailed(...)`; e `Navigator.treeModel()` (`:157`) deveria receber a lista pronta em
vez de chamar `SeriesCatalog.names()` de dentro. As duas mudanças usam apenas o que a
classe já expõe — e a primeira depende de A7a-1 estar corrigida, senão uma falta de
memória durante a leitura chega ao `whenDone` como `null`.

---

## As chaves dos dois bundles

Contadas por script sobre `src/main/resources/messages.properties` e
`messages_pt_BR.properties` (linhas que não começam com `#`/`!` e contêm `=`; a chave é o
que vem antes do primeiro `=`):

| | linhas com chave | chaves distintas |
|---|---:|---:|
| `messages.properties` (base, inglês) | 332 | 331 |
| `messages_pt_BR.properties` | 332 | 331 |

**Diferença entre os dois conjuntos: nenhuma.** Nem em um sentido nem no outro — todas as
331 chaves existem nos dois arquivos. Este é um ponto genuinamente limpo, e é raro.

**Uma duplicata, idêntica nos dois arquivos:** `replay.speed` aparece duas vezes
(linhas 243 e 256 do arquivo base). Ver A7a-21.

**Chaves usadas no código e ausentes do bundle: nenhuma.** Varri as 216 chaves literais em
`Messages.get(...)`/`Messages.orElse(...)` de todo o `src/main/java` contra o bundle base.
Os quatro "ausentes" que o script acusou são prefixos de concatenação, não chaves:
`navigator.role.`, `navigator.scale.`, `navigator.tickSource.` e `settings.language.` —
todos usados como `Messages.orElse("navigator.role." + role, role)`
(`ui/shell/Navigator.java:293`), isto é, com fallback declarado, que é precisamente o uso
para o qual `orElse` foi escrito (`Messages.java:81-91`).

---

## O que está LIMPO

**`platform/` não importa `ui/`.** Grep por `endeavourneo\.ui|javax\.swing|java\.awt` em
todo o pacote `platform`: as únicas ocorrências são `javax.swing.SwingUtilities`
(`JobService:29`) e `java.awt.Dimension`/`java.awt.Font`/`javax.swing.UIManager`/
`javax.swing.UnsupportedLookAndFeelException` (`Appearance:20-25`) — permitidas, porque a
proibição de Swing/AWT vale só para `domain`. Nenhuma referência a `ui`, nem por import nem
qualificada. Tentei derrubar procurando referências totalmente qualificadas (o buraco que
virou A7a-4): não há nenhuma em `platform`. A regra está cumprida hoje; o que está fraco é
o guarda, não o código.

**Fuso.** Procurei em todo o `platform` por `ZoneId`, `systemDefault`, `epoch`, `Instant` e
`LocalDate.`: as únicas quatro ocorrências são `LocalDate.parse` de texto
(`Segmentation:120-121`) e `LocalDate.MAX` como sentinela (`:254-255`). **Não há uma única
conversão de epoch millis para `LocalDate` em toda a área.** A conversão existe, mas em
`ui/shell/MainWindow.java:438`, passando `ZoneId.systemDefault()` para
`SegmentedSeries.of(...)` — fora da minha área, e vale a pena a A-do-domínio conferir se o
fuso certo é o de São Paulo e não o da máquina.

**Nunca olhar o futuro.** Não há nada nesta área que leia dado de mercado por índice ou
por tempo — `SeriesCatalog` entrega o `PriceSeries` inteiro, `Segmentation` guarda datas.
Nada a violar.

**`Segmentation.overlap` (`:253-257`).** Testei a aritmética de borda no papel contra os
testes: séries que se tocam (`to = 31/12`, `from = 01/01`) não se sobrepõem
(`touchingIsNotOverlapping`), aberto para frente alcança tudo depois (`onwardsReachesForward`),
e a fórmula `!one.from().isAfter(otherEnd) && !other.from().isAfter(oneEnd)` é a forma
canônica e correta de interseção de intervalos fechados. Tentei quebrar com um segmento de
um dia só (`from == to`): sobrepõe consigo mesmo, mas o laço duplo começa em `j = i + 1`
(`:239`) e nunca compara um segmento com ele próprio. Tentei quebrar com o segmento
degenerado de tamanho zero: `Segment` (`domain/market/Segment.java:54-57`) recusa
`to < from` no construtor compacto, e `Segmentation.of` pega o `IllegalArgumentException`
que isso lança. Não caiu.

**Segmento como `record` com invariante no construtor compacto.** `Segment` valida nome
não-vazio, `from` não-nulo, e `to >= from`, e faz `name = name.trim()` (`:45-60`) — segue a
convenção da casa. `Segmentation` depende disso e o `catch` de `:122` está correto ao
capturar `IllegalArgumentException` junto com `DateTimeParseException`.

**Divisão por frações fixas.** Conferi se `Segmentation` divide a série por proporções
(as frações fixas registradas no projeto): **não divide nada.** É só armazenamento e
consulta de fronteiras que o leitor escolheu por data. Não há aritmética de divisão para
errar. O javadoc `:29-37` ("A selector, not a guard") é honesto sobre isso.

**`Progress`.** É uma interface pura, sem aritmética — não há divisão por zero para
achar. A única implementação (`JobService:313-333`) escreve em campos `volatile`
(`:245-247`), portanto é segura para thread no sentido de visibilidade, e o único leitor
(`StatusBar:175-184`) valida a faixa antes de usar, incluindo `NaN` (que reprova em
`fraction >= 0.0` e cai no ramo indeterminado). Tentei derrubar procurando um
`(int) (fraction * 100)` sem guarda em outro lugar: não há outro leitor. Limpo, salvo o
javadoc de A7a-25.

**`Settings` — codificação e reparo.** É a parte mais bem construída da área. Li o
`repair`/`undo` (`:171-242`) procurando o falso positivo — texto português legítimo que o
reparo estragaria — e não achei: `undo` exige que **todos** os caracteres caibam num byte
**e** que a sequência de bytes ISO-8859-1 resultante seja UTF-8 válida em modo `REPORT`,
e "ção" em bytes Latin-1 (`E7 C3 A3` ... na verdade `E7 E3 6F`) é malformada porque `E7`
pede duas continuações `10xxxxxx` e `E3` não é uma. `SettingsEncodingTest.
healthyTextIsNotTouched` (linha 108) prova isso com a string certa. Tentei também derrubar
o par escapar/desescapar: `escape` (`:293-320`) escapa `\`, `\n`, `\r`, `\t`, `=`, `:`,
`#`, `!` e espaço em chave ou início de valor; `Properties.load(Reader)` desescapa `\\`,
`\n`, `\r`, `\t` e, para qualquer outro caractere precedido de barra, devolve o caractere
literal — cobrindo `\#`, `\!`, `\=`, `\:` e `\ `. O par fecha, e
`SettingsTest.awkwardCharactersSurvive` (linha 98) usa a string exata que testaria isso.
Não caiu.

**`Settings.load` com `values.clear()` no `catch`.** Tentei transformar em achado — um
`clear()` que apagasse valores já carregados. Não é: `load()` só é chamado do construtor
(`:100`), com `values` recém-criado. Refutado.

**`Settings.repair` e valores nulos.** Tentei uma NPE em `repair(value)` com valor nulo:
`stringPropertyNames()` só devolve chaves cuja chave **e** valor sejam `String`, então
`getProperty` nunca devolve `null` ali. Refutado.

**`escape` e `keysStartingWith` devolvendo coleção mutável.** `keysStartingWith`
(`:370-382`) constrói e devolve um `ArrayList` novo a cada chamada — não é a coleção
interna escapando. `Settings.values` é `private` e nunca é exposto. Refutado.

**`Messages.setLocale` e o `NoFallbackControl`.** Tentei derrubar o `LanguageTest`
mostrando que ele quebraria numa máquina com locale `en_US`: `getNoFallbackControl` só
anula o *fallback para o locale padrão*, e `getCandidateLocales` continua incluindo
`Locale.ROOT`, portanto `messages.properties` (que é a base em inglês) sempre é encontrado.
`setLocale(Locale.getDefault())` no `@AfterEach` (`LanguageTest:36`) não lança em locale
nenhum. Refutado — e o comentário de `:142-153`, que explica o defeito original, está certo.

**`Theme.of` e valor inválido na preferência.** Um `theme=` desconhecido no arquivo cai em
`LIGHT` (`:105-117`), documentado, e o `Launcher` acrescenta o guarda contra a bandeira de
linha de comando (`:68`). Tentei achar uma cor lida de preferência que não fosse cor
válida — o foco 4 da auditoria: **não existe nenhuma no `Theme` nem no `Appearance`.**
Cor lida de texto acontece em `ui/chart/overlay/MovingAverage.java:507`,
`BollingerBands.java:619`, `study/stochastic/SlowStochastic.java:441` e
`study/rsi/RelativeStrength.java:302` — `new Color(Integer.parseInt(text, 16))`, fora da
minha área (A5). Registro aqui só para que ninguém procure de novo no lugar errado.

**`Appearance.forgetPalettes` e a troca de tema em execução.** O ciclo
esquecer-registrar-instalar está correto e o `ThemeSwitchTest` o testa em **sequência**,
que é o único jeito de pegar o defeito que o motivou. Tentei encontrar um teste sem
dentes ali: `nightFindsItsPalette` parecia candidato, porque
`assumeTrue(installed.startsWith("FlatLaf"))` também é verdade para
`"FlatLaf dark (night palette missing)"` — mas isso é o que se quer: a suposição **não**
pula esse caso, e a asserção seguinte (`contains("night") && !contains("missing")`)
reprova. O teste tem dentes. Refutado. Tentei também `darkSurvivesNight`: sem FlatLaf, os
dois temas instalariam o look and feel do sistema e `assertNotEquals` reprovaria em voz
alta em vez de passar calado. Refutado.

**A ordem de arranque do `Launcher`.** Conferida contra o que o javadoc promete:
`Appearance.install` (`:76`) roda antes de qualquer componente existir; tudo que constrói
Swing está dentro do `invokeLater` (`:91-106`); `Language.install()` (`:93`) roda antes de
`new MainWindow` (`:95`), que é antes de o primeiro rótulo ser lido; e
`captureStandardOutput()` (`:99`) vem antes das três escritas no console (`:102-105`), na
ordem que o comentário justifica. **Não há `System.exit` em lugar nenhum do `Launcher`** —
grep confirmado — portanto não há trabalho pendente sendo engolido por saída forçada, e o
gancho de desligamento (`:83`) dá ao `JobService.close()` a chance de pedir parada e
esperar 2 segundos (`JobService:393`). Tentei derrubar por dois caminhos. (1) Exceção no
arranque deixando a aplicação meio viva: `Appearance.install` não lança — `apply` captura
`ReflectiveOperationException`, `UnsupportedOperationException` e
`UnsupportedLookAndFeelException` (`:164-167`), e `registerPalette`/`forgetPalettes`
capturam `ReflectiveOperationException`. O buraco real está antes, em `Theme.remembered()`
→ `Settings` (A7a-9), e é onde reportei. (2) `UIManager.setLookAndFeel` fora da EDT
(`:76`, na thread `main`): é tecnicamente uma chamada Swing fora da EDT, mas é o padrão
universalmente recomendado — precisa acontecer antes de qualquer componente, e nenhum
componente existe ainda, portanto nenhum listener de UI pode ser notificado. Refutado como
achado; o caso perigoso é a **reinstalação** em execução, e essa está na EDT, feita pelo
`AppearancePage.apply` (`ui/settings/AppearancePage.java:117-125`), que faz o
`updateComponentTreeUI` em todas as janelas logo depois — correto.

**`JobService` e as regras da EDT.** `onEdt` (`:374-380`) marshaliza corretamente e é
usado por `deliver` e por `notifyListeners`; a asserção de que o callback chega na EDT tem
teste com dentes (`JobServiceTest:70-86`). O `close()` (`:382-400`) desliga o pool,
espera, e chama `shutdownNow` com restauração da flag de interrupção — está certo, e o
pool **não** fica aberto: o único `JobService` da aplicação tem gancho de desligamento
(`Launcher:83`) e os testes usam `try-with-resources`. Refutei o achado "pool que nunca
fecha". Tentei um segundo: `deliver()` chama `onEdt` **segurando o monitor do `Handle`**
(`:165`, `synchronized`), e `onEdt` executa direto quando já está na EDT — então um
handler roda dentro do bloco sincronizado. Procurei um caminho de impasse real (EDT
segurando o monitor e esperando por uma thread de trabalho que precise do mesmo monitor):
`isCancelled()` e `cancel()` não são sincronizados, e `settle` só é chamado do `finally` da
tarefa, que não espera pela EDT. Não consegui construir o impasse, então **não reportei** —
mas é uma fragilidade que vai virar defeito no dia em que um handler bloquear.

**`Files.walk` fechado.** `SeriesCatalog.namesIn` usa `try-with-resources` (`:495`), e
`LayerBoundaryTest.scan` também (`:115`, `:145`, `:153`). O recurso está certo; o que está
errado é o tipo capturado (A7a-11).

**Duas séries com o mesmo nome em pastas diferentes.** Tentei transformar em achado: o
`.distinct()` (`:521`) vem **depois** do filtro `Files.isRegularFile(folder.resolve(
relativeTo(name)))` (`:520`), que só aceita o arquivo no lugar canônico. Uma cópia em
`data/btcusdt/1m/winfull-1m.bin` não cria uma segunda entrada — o `distinct` colapsa, e
`open`/`fileOf` abrem o canônico. Refutado. (O que sobra é que um arquivo **só** existente
no lugar errado desaparece da lista sem uma palavra; considerei reportar e achei fraco
demais para BAIXA, porque é o comportamento que o javadoc de `:483-488` descreve e
justifica.)

**Arquivo que some entre listar e abrir.** Tentei transformar em achado: `open`
(`:601-620`) refaz `MarketFile.isSeries(file)` antes de ler, e devolve `Optional.empty()`
se sumiu; o chamador (`MainWindow.seriesFor:356-369`) trata o vazio escrevendo
`console.seriesMissing` e desenhando o passeio aleatório — que é ruído, mas é rotulado no
console e nunca substitui uma série que falhou ao ler (essa lança e vira
`console.seriesFailed`, `:362-365`). A distinção "vazio é resposta, exceção é falha"
(javadoc `:591-599`) está implementada como está escrita. Refutado.

**`SeriesCatalog.folder()` e a corrida de inicialização.** `folder` é `volatile` (`:113`) e
duas threads que corram chegam ao mesmo valor a partir das mesmas entradas — a corrida é
benigna e a escrita não tem efeito colateral (a decisão de persistir está só em
`setFolder`, `:157`, exatamente como o javadoc de `:102-111` argumenta). Refutado.

**Classes utilitárias recusando instanciação.** `SeriesCatalog:115-117`,
`Segmentation:72-74`, `Appearance:51-53`, `Messages:52-54`, `Launcher:53-55` — todas com
construtor privado lançando `AssertionError`. `Settings` tem construtor privado de
conveniência e um de pacote para teste (`:96`), com justificativa escrita. `JobService` é
instanciável de propósito. Convenção cumprida em toda a área.

**`equals`/`hashCode`, comparação de ponto flutuante por `==`, coleção mutável escapando de
getter.** Procurados na área inteira: `Segment` é `record` (equals/hashCode gerados e
consistentes); não há um único `==` entre `double`/`float` no `platform` (a única
comparação de ponto flutuante é `fraction >= 0.0 && fraction <= 1.0` no `StatusBar`, que é
de faixa e não de igualdade); os getters que devolvem coleção — `SeriesCatalog.names()`
(`.toList()`, imutável), `roles()`/`groups()` (mapa novo por chamada), `retired()`
(conjunto novo), `JobService.runningNames()` (`.toList()`), `Settings.keysStartingWith`
(lista nova) — nenhum expõe estrutura interna. Nada a reportar.
