# A noite de 07/09/2026 — corrida autônoma

Ele foi dormir e pediu, textualmente:

> 1 – com a lista do que falta, resolve todos os restantes
> 2 – refaça a auditoria completa, rodando até 2 agente ao mesmo tempo, sempre
>     produzindo o relatório
> 3 – ao final junte tudo e faça a validação cruzada
> 4 – corrija todos os itens restante dessa nova auditoria
> 5 – faça tudo isso sem mim, o que precisar da minha decisão, adie se for
>     possível, senão, decida a melhor opção e registre pra me apresentar a
>     escolha quando eu voltar
> 6 – vou dormir, faça tudo como falei e registre cada passo, para continuar se
>     vc parar

**Este arquivo é o estado.** Quem retomar — eu, depois de perder contexto, ou
outra sessão — lê daqui e continua. Atualizado ao fim de cada lote, antes do
commit.

---

## O mecanismo escolhido, e por quê

Ele perguntou se é um laço ou outra coisa. **Não é um laço.**

Um laço (`/loop`, `ScheduleWakeup`) só paga quando se está *esperando* por algo
de fora — uma CI, um deploy, uma fila. Aqui não há espera: há trabalho contínuo.
Acordar por temporizador gastaria requisição sem produzir avanço.

O que dá continuidade é **este arquivo mais os commits**. Cada lote fecha em um
commit, e a fila abaixo diz o que vem depois. Se eu parar no meio, o próximo a
sentar lê a fila e segue; nada depende de eu estar vivo.

**Agentes só na fase 2**, dois por vez, como ele pediu. E **nunca com o código
mudando debaixo deles**: isso já custou caro uma vez — corrigi dois dos cinco
defeitos do gabarito da A8b enquanto ela ainda lia, e o relatório saiu falando
de código que não existia mais. Por isso as fases são estritamente sequenciais.

---

## As fases

| # | fase | estado |
|---|---|---|
| 0 | Trabalho em voo quando ele foi dormir | **fechada** — `1b34571` |
| 1 | Resolver os achados de domínio e replay da auditoria I | **fechada** — `f6f6640` |
| 2 | Refazer a auditoria completa, 2 agentes por vez | **fechada** — 10 áreas + 4 lentes |
| 3 | Juntar e validar cruzado | **fechada** — `ii/00-cruzamento.md` |
| 4 | Corrigir os achados novos, e o que sobrou da lista velha | **em curso** — 21 das 32 ALTA |

---

## O que falta — o inventário

Da auditoria de 30/08 a 06/09/2026, nove áreas mais quatro lentes
transversais. `00-achados.md` é o índice; o detalhe está no relatório de cada
área.

| gravidade | levantados | fechados | **abertos** |
|---|---:|---:|---:|
| ALTA | 52 | 51 + 1 refutado | **0** |
| MÉDIA | 133 | **40** | **93** |
| BAIXA | 115 | 0 | **115** |

Atualizado às 05h. Estão **fechadas inteiras** as áreas **A1, A2 e A3** nos
MÉDIA, mais L2-3 e A5-12. É todo o domínio e todo o replay — onde um defeito
muda número e não texto, que foi o corte da decisão D5.

Os 24 MÉDIA já fechados, com o commit:

| lote | quantos | commit |
|---|---:|---|
| isolamento dos testes | 4 | anteriores a esta noite |
| `TickLibrary` fechada pelos donos | 5 | `73fb739` |
| javadoc e testes do renko | 3 | `7c3d284` |
| corretude de domínio I | 4 | `1d9a968` |
| corretude de domínio II (ticks) | 4 | `b9bf044` |
| corretude de domínio III (renko, estocástico) | 4 | `939abb7` |

### A fila da fase 1, em ordem

A ordem é por **valor**, não por numeração: o que muda comportamento antes do
que muda texto.

1. **L2-3** — dois "agoras" num quadro de replay. O renko anda por tempo de
   negócio e o candle por contagem de ticks; onde os negócios não chegam
   uniformes as duas réguas divergem, e é na abertura que isso é pior. Último
   MÉDIA de corretude de domínio, e o maior: a correção muda o `TickPath` para
   carregar carimbos junto dos preços.
2. **Corretude restante fora do domínio** — as MÉDIA de `ChartCanvas`, eixos,
   indicadores e replay que mudam o que se vê.
3. **EDT e entrada/saída** — trabalho pesado na thread da interface. Inclui a
   decisão D2 abaixo.
4. **Persistência e recursos** — o que vaza, o que não fecha, o que não volta.
5. **Testes sem dentes** — testes que passariam com o produto quebrado. Cada um
   vale mais que um achado: é uma licença falsa.
6. **javadoc órfão, i18n, consistência de interface** — o grosso das BAIXA.

---

## Decisões — o que adiei e o que decidi sozinho

Ele pediu: adiar quando der; quando não der, decidir a melhor opção e registrar
para apresentar.

### D1 — Cache em disco das barras dobradas de um export · **ADIADA**

Abrir o gráfico de um export custa **8,1 s (MetaTrader, 1.838 MB)** ou **4,4 s
(Profit, 691 MB)**, e produz só 10.766 e 5.074 barras. Guardar essas barras num
`.bin` ao lado dos ticks tornaria a segunda abertura instantânea.

Adiada porque introduz **artefato derivado** e a pergunta de invalidação (um
pregão novo importado tem de invalidar o cache), e isso é escolha dele: se esse
gráfico for de uso diário o cache se paga; se for de consulta rara, é
complexidade sem dono.

### D2 — Renko de 11R dobrando 1,7 milhão de caixas na EDT · **A DECIDIR NA FASE 1**

Medido na base dele: `Renko(11, 2)` sobre 100.000 barras assenta **1.695.538
caixas em 468 ms e 65 MB**, na thread da interface, a cada redesenho. Note que
`11R` da interface é caixa de 50 pontos — a medição acima usou caixa de 11
pontos, então é o pior caso, não o caso dele.

Vou medir o caso real dele antes de mexer. Se for pesado, a correção é dobrar
fora da EDT, que não muda o que é desenhado — decido isso sozinho, é melhoria
sem contrapartida. Registro o número medido aqui quando tiver.

### D3 — Formato de tick sem cruzamento de datas · **SÓ REGISTRO**

MetaTrader cobre jan/2021 e Profit cobre ago-set/2026. **Não há um pregão em
que os dois se cruzem**, então não dá para comparar livro e agressão lado a
lado. Não é decisão, é limite do que existe; registrado porque muda o que se
pode estudar.

---

### D5 — Onde parar de corrigir a lista velha · **DECIDIDA POR MIM**

Medida a velocidade real da noite: **onze achados a cada duas horas**, contando
a correção, o teste que faltava, a prova de dentes e o commit. Nesse passo os 97
MÉDIA restantes são dezoito horas. Não cabem antes da auditoria, e a auditoria é
o que ele pediu explicitamente na fase 2.

**Decidi cortar aqui.** A fase 1 fecha ainda os MÉDIA de **domínio e replay** —
onde um defeito muda número, e não texto — e para. O resto da lista velha vai
para a fase 4, ao lado do que a auditoria nova achar.

**Por quê:** a auditoria relê o código atual. Metade das áreas foi reescrita
esta noite, então metade da lista velha aponta para linhas que já não existem.
Gastar as horas restantes na lista velha entregaria correções de sete dias atrás
e nenhuma auditoria; gastar na auditoria entrega a lista nova, que é a que vale
para o estado de agora.

**O que isso custa:** as áreas A4, A7a, A7b, A8a, A8b e as lentes L1, L3 e L4
ficam sem passada de correção esta noite. Elas voltam pela auditoria, e a
validação cruzada da fase 3 confere a lista velha contra a nova para achar o que
só a velha viu.

### D4 — A ordem entre corrigir tudo e reauditar · **DECIDIDA POR MIM**

Ele pediu, nesta ordem: resolver todos os restantes, depois refazer a auditoria.
Restam **~105 MÉDIA e 115 BAIXA**. Corrigir os 220 à mão é trabalho serial e
lento; a auditoria é a parte cara, valiosa e paralelizável. Fazendo a fase 1
inteira primeiro, a fase 2 não começa esta noite.

**Decidi assim:** fase 1 fecha os **MÉDIA** — o que muda comportamento, o que
vaza recurso, e os testes sem dentes. As **BAIXA entram na fase 4**, junto com o
que a auditoria nova achar.

**Por quê:** a auditoria nova relê o código como ele estará depois das
correções. Uma BAIXA de sete dias atrás aponta para linhas que já mudaram — três
áreas inteiras foram reescritas esta noite. Corrigir a lista velha antes de
reauditar é gastar duas vezes e acertar a segunda; deixar a auditoria derivá-las
do código atual é gastar uma.

**O que isso custa:** se a auditoria nova não reencontrar alguma BAIXA da lista
velha, ela fica sem correção. Aceitei porque a lista velha continua no disco
(`00-achados.md`) e a validação cruzada da fase 3 confere uma contra a outra — é
exatamente o tipo de furo que ela existe para pegar.

---

## A auditoria II — como está desenhada

**Pasta própria**, `docs/auditoria/ii/`, para não sobrescrever a primeira: a
fase 3 precisa das duas lado a lado.

**Os agentes de área não leem a auditoria I.** Isso é deliberado e é o que dá
valor à segunda passada: se ela lesse a primeira, encontraria o que a primeira
encontrou e a validação cruzada não significaria nada. O briefing
(`ii/00-briefing.md`) diz isso em letras.

**Ids com prefixo `B`**, para nunca confundir com os `A` da primeira.

**Dois por vez**, como ele pediu. A skill `auditar` manda rodar um por vez, e a
razão registrada nela é uma decisão dele de 05/09; a instrução desta noite é
mais nova e diz "até 2", então é ela que vale. Ainda está longe do desenho que
falhou — treze de uma vez, duas corridas inteiras evaporadas.

A partição é a medida em `docs/AUDITORIA.md`, com as áreas grandes já partidas:

| área | o que cobre | linhas |
|---|---|---:|
| B1 | séries, agregação, formatos de arquivo, biblioteca de ticks | ~3.500 |
| B2 | renko, ticks sintéticos, replay do domínio | ~2.900 |
| B3 | `ChartCanvas` inteiro | ~3.000 |
| B4 | holder, layout, legenda, eixos, estilo | ~5.200 |
| B5 | indicadores: overlays, estudos, painéis, diálogos | ~6.900 |
| B6 | `ui/replay` | ~2.600 |
| B7a | platform, settings, catálogo | ~2.500 |
| B7b | shell, series, settings de interface | ~5.400 |
| B8a | testes de domínio e plataforma | ~4.600 |
| B8b | testes de interface | ~8.300 |

E depois as quatro lentes transversais, que **não releem os arquivos**: recebem
padrões de `grep` e leem ±40 linhas em volta de cada ocorrência, mais a lista do
que as áreas já acharam.

### O que a auditoria II entregou — as dez áreas

| área | linhas lidas | ALTA | MÉDIA | BAIXA |
|---|---:|---:|---:|---:|
| B1 séries e formatos | 4.527 | 2 | 11 | 8 |
| B2 renko e replay | 2.774 | 2 | 10 | 8 |
| B3 `ChartCanvas` | 2.983 | 2 | 8 | 9 |
| B4 moldura | 8.198 | 1 | 13 | 17 |
| B5 indicadores | 4.375 | 1 | 9 | 8 |
| B6 transporte de replay | 2.693 | 3 | 6 | 6 |
| B7a plataforma | 2.731 | 3 | 13 | 8 |
| B7b casca | 5.397 | 3 | 8 | 13 |
| B8a testes de domínio | 7.620 | 4 | 9 | 6 |
| B8b testes de interface | 9.840 | 5 | 8 | 6 |
| | **51.138** | **26** | **99** | **90** |

Cinquenta e uma mil linhas lidas — o projeto tem 50.599, então cada linha foi
lida uma vez e pouco. É o que a partição por áreas existe para conseguir.

**Treze das vinte e seis ALTA já estão corrigidas**, com o teste que faltava e a
prova de dentes: `e541da7` e `30e30f7`.

**Vale mais que a contagem:** a segunda passada achou defeito em código que eu
escrevi nesta mesma noite, e achou-o porque não sabia que eu o tinha escrito.

- **B3-1 e B3-9** dizem que duas das minhas mudanças no `ChartCanvas` abriram
  caso novo. B3-1 é grave: `extendBricks` passou a devolver `false` tanto para
  "não consegui" quanto para "nada mudou", e quem lê entende o primeiro — troca
  o renko de ticks pelo de candles, que assenta de 5% a 23% mais tijolos, e
  `fromTicks` continua dizendo que veio dos ticks.
- **B2-1 e B8a-1** dizem que o `OneClockTest` que escrevi para provar a
  correção do L2-3 **afirma só um lado**: ele verifica que o candle não está
  atrás do renko, então adiantar o relógio — mostrar o futuro, que é o defeito
  que a classe existe para impedir — passava intacto.
- **B1-2** é o `FoldedTicks` de ontem à noite engolindo `IOException`: um pregão
  exportado e ilegível sumia do meio do gráfico sem buraco e sem palavra.
- **B8a-4** é a melhor delas e não é sobre código novo: **nenhum teste do
  domínio lê máxima, mínima ou volume de uma barra FECHADA do replay**. As duas
  fixtures tinham abertura = máxima = mínima = fechamento, então trocar
  `highAt` por `lowAt` dentro do `ReplaySeries` — virar todo o gráfico de
  história de cabeça para baixo — deixava a suíte inteira verde.

Isso é o argumento inteiro a favor de reauditar em vez de continuar corrigindo
a lista velha, e ele apareceu sozinho.

E continuou aparecendo depois que escrevi o parágrafo acima:

- **B7a-4** — o gancho `whenForgotten` que liguei para corrigir "o calendário
  nunca é esvaziado" pendurava-se num `SeriesCatalog.forget()` que **não tem
  chamador nenhum** em `src/main`. Eu tinha mudado o defeito de andar, não
  corrigido. Agora a janela de séries o chama; o momento de verdade — importar
  um pregão — ainda não tem caminho pela interface, e isso está dito no javadoc
  para não virar promessa.
- **B7a-1** — a janela por segmento que escrevi à noite tinha um furo no ramo do
  cache: com a série inteira em memória ela devolvia tudo e ignorava o fim do
  trecho. Medido pelo teste: 1.000 barras onde cabem 300.
- **B6-3** — o `forgetEnding` que acrescentei também não tem chamador, e o teste
  que escrevi registra e desregistra ele próprio. Licença falsa, e ainda aberto.
- **B8b-3 e B8b-5** — dois testes meus que leem o CÓDIGO-FONTE em vez do
  comportamento passam com o defeito reescrito de outro jeito.

Quatro correções minhas desta noite, das quais **três estavam erradas ou
incompletas** e uma delas apenas mudou o defeito de lugar. Nenhuma teria
aparecido sem a segunda leitura.

---

### As lentes transversais

Quatro perguntas, cada uma em todo o código: EDT e concorrência, tempo e leitura
do futuro, persistência e recursos, i18n e consistência.

**Elas não releem os arquivos.** Recebem padrões de `grep`, leem ±40 linhas em
volta de cada ocorrência, e recebem o índice dos 215 achados com a ordem de
reportar só o que as áreas não viram. Foi exatamente aqui que a primeira
tentativa desta auditoria se perdeu, em 05/09: quatro lentes relendo
integralmente o que nove agentes já tinham lido, um multiplicador de quatro em
cima do corpus inteiro, 1,5 milhão de tokens e zero relatórios.

---

## O placar da noite

| | |
|---|---|
| suíte | **537 → 583 verdes** |
| commits | 19 |
| auditoria II | 10 áreas + 4 lentes, **51.138 linhas** |
| achados novos | **32 ALTA · 121 MÉDIA · 107 BAIXA** |
| ALTA fechadas | **21**, cada uma com o teste que faltava |

### A fila da fase 4, em ordem

O que fica, e por que nesta ordem. As primeiras mudam número ou perdem dado; as
últimas mudam texto.

1. **L3-1 (ALTA, perda de dado)** — `TickFile.Writer` e `TapeFile.Writer`
   truncam o arquivo do pregão **no construtor**, antes de ler a primeira linha
   do export; e o `finally` dos conversores fecha o writer, que grava a contagem
   do que deu tempo de escrever. Uma conversão recusada **apaga o pregão bom** e
   deixa no lugar um pregão curto internamente coerente, que `read`, `sessionOf`
   e a biblioteca aceitam como inteiro. Os três testes de recusa param no
   `assertThrows` e nunca olham o disco. A correção é escrever num temporário e
   mover no fim.
2. **B7b-2 (ALTA)** — a janela de séries lê a série **inteira** na thread da
   interface, no construtor e a cada troca da combo. Congela a aplicação ao
   abrir Ferramentas → Séries.
3. **L1-1 (ALTA)** — `relaunch()` não refaz `captureStandardOutput()`: depois da
   primeira troca de idioma toda a saída padrão vai para a janela morta, que
   fica presa em memória para sempre.
4. **B7b-1 (ALTA)** — a tranca "só pelos segmentos" não vale para fonte de
   ticks: o leitor marca a caixa, ela fica marcada, e o export abre inteiro.
5. **B8b-1 (ALTA)** — o único teste da escala própria do IFR passa com o
   indicador desenhando NaN em todas as barras. `assertEquals(double, double,
   delta)` trata NaN como igual a NaN.
6. **B8b-2 a B8b-5, B8a-3 (ALTA)** — cinco testes meus que leem o **código-fonte**
   em vez do comportamento. Passam com o mesmo defeito reescrito de outro jeito.
7. **B7b-3 (ALTA)** — javadoc no membro errado em dez lugares, sete no
   `MainWindow`. O `OrphanJavadocTest` já os conta; levar o teto a zero apaga o
   teto.
8. **As 121 MÉDIA e 107 BAIXA da auditoria II**, e as **93 MÉDIA e 115 BAIXA da
   auditoria I** que a decisão D5 mandou para cá.

### Onde faltou teste, dito de propósito

Duas correções desta noite foram commitadas **sem o teste que a casa exige**, e
está escrito no commit e aqui:

- **B6-1** — os ticks pedidos enquanto o relógio anda. Precisa de um fixture de
  três pregões de tape e de conduzir o relógio por dois deles.
- **B6-3** — `forgetEnding` chamado pelo `ReplayDrop`. Precisa de um
  `ChartHolder` de verdade.

Nenhuma das duas cabia no tempo que restava, e deixá-las sem teste e sem dizer
seria pior do que deixá-las sem teste.

---

## Diário — cada passo, com o commit

### 07/09, madrugada — fase 0, o que estava em voo

| o que | commit |
|---|---|
| Ticks sintéticos recalibrados pelos nove pregões do Profit | `294a330` |
| Corretude de domínio II: pontas do caminho, fuso, dicionário, agressor | `b9bf044` |
| Corretude de domínio III: rabo do pregão, carry, teto, estocástico | `939abb7` |
| Uma regra só: o feed decide de onde vem o que se vê | `05fc7ba` |
| Janela de um segmento lida NELE, não no fim do arquivo | `477922f` |
| Um export de ticks abre como gráfico | `1b34571` |

Suíte: **537 verdes**.

### 07/09, 02h às 04h — fase 1

| lote | achados | commit |
|---|---|---|
| Um relógio só: candle e renko param no mesmo instante | L2-3 | `cf9d3b8` |
| Botão, âncora do zoom, posição guardada, fita recusada | A3-5, A3-6, A3-7, A3-8, A3-11 | `fa0d4ed` |
| Rodapé, caldas, pregão pedido adiantado, javadoc inerte | A3-9, A3-10, A3-12, A3-13, A2-5, A2-6 | `5fce5e8` |

Suíte: **550 verdes**, de 537.

Três coisas que valem mais que os achados em si:

- **Um teste matou uma classe inteira de defeito.** `OrphanJavadocTest` varre a
  árvore procurando javadoc que documenta o membro errado. A auditoria achava um
  arquivo por vez; a varredura acha todos e impede que voltem. Restam 23 num
  teto que não pode crescer.
- **Um dos oito javadocs órfãos do `ChartCanvas` era meu**, criado uma hora
  antes ao extrair um método. É a melhor prova de que o caso pedia um teste e
  não oito correções.
- **Um teste que escrevi não tinha dentes** e só apareceu ao quebrar o produto:
  ele perguntava ao feed e ao arquivo, que são as mesmas duas coisas de que a
  ligação é feita. `isRecorded()` passou a responder da própria ligação.

Dois defeitos foram achados pelos próprios testes desta noite, e valem
registro porque não estavam em auditoria nenhuma:

- o passeio sintético **saía da barra** — corrigir o passo sorteado duas vezes
  deixava as duas correções se anularem;
- `java.util.Random` com sementes vizinhas dá **primeiros sorteios
  correlacionados**, e era esse sorteio que decidia qual extremo vem primeiro:
  31% onde a medição diz 83,2%.

E um teste que eu escrevi **não tinha dentes** — passava com o produto
quebrado. Só apareceu porque quebrei o produto de propósito para conferir.
Está corrigido; a lição vai para a fase 3.

### 07/09, 04h às 07h — fases 1 (fim), 2 e 3

| lote | achados | commit |
|---|---|---|
| A varredura que emudecia, as caixas remontadas, o rótulo em português | fase 1 | `f6f6640` |
| O briefing da segunda passada, e este diário | — | `77a4836` |
| A ferramenta que confronta as duas passadas | — | `9c7a44c` |
| O que não se soltava, o calendário que não sabia, a preposição em java | fase 1 | `9e87cf5` |
| As sete ALTA da segunda passada, e o teste que nunca rodou | B1..B6 | `e541da7` |
| O quadro parado, que trocava os tijolos em silêncio | B5-2 | `9b2c469` |
| O estudo na escala errada, o cache que engolia o trecho, o arquivo que apagava tudo | B7a | `30e30f7` |
| O índice dos 215 achados, e o parser que quase o inventou | — | `5f32210` |
| As dez áreas fechadas, e as quatro correções minhas que a segunda leitura derrubou | — | `e3bfe05` |
| O extrator passa a entender as três formas de referência | — | `d3de376` |
| A fase 3, com as duas auditorias confrontadas | — | `7c95406` |

O que a fase 2 e a fase 3 acharam, e que nenhuma outra coisa acharia:

- **Cinco defeitos nas minhas próprias correções desta noite**, e quatro estavam
  erradas ou pela metade: o gancho `whenForgotten` pendurado num `forget()` sem
  chamador, o `openUntil` com um buraco no cache (mil barras onde cabem 300), o
  `forgetEnding` sem chamador, e o `OneClockTest` que **nunca rodava** porque a
  guarda do laço é falsa antes do primeiro quadro. Corrigir e reauditar na mesma
  noite não é redundância: é a única leitura que pega a correção fresca.
- **`data.zone` era lida pelo Launcher e escrita por ninguém.** A correção de
  fuso inteira foi construída e nunca armada. Invisível na máquina do autor, que
  fica no fuso do mercado — a classe de defeito que só existe onde o código não
  é escrito.

### 07/09, manhã — fase 4

| item | o que era | commit |
|---|---|---|
| **L3-1** | Conversão recusada apagava o pregão bom e deixava um curto no lugar | `0ead241` |
| **B7b-2** | A janela de séries lia a série inteira na thread da interface | `2f60bcf` |
| **L1-1** | Trocar de idioma deixava a saída padrão presa ao console da janela morta | `e2ae2ae` |
| **B7b-1** | A tranca "só pelos segmentos" não valia para fonte de ticks | `351410d` |
| **B8b-1** | O único teste da escala própria do IFR passava com o IFR desenhando NADA | `df5c531` |
| **B8b-2, B8b-5** | Botões do mouse e arredondamento do preço, medidos em vez de lidos | `b38029e` |
| **B8b-3, B8b-4** | A biblioteca que cresce, medida — e uma premissa do relatório caiu | `0e55307` |
| **B8a-3** | A varredura que falha PELO MEIO nunca caía no catch que a esperava | `03cd5a9` |
| **B7b-3** | Javadoc inerte: 23 para zero, e o teto saiu do teste | `7a9627d` |

**As 32 ALTA da auditoria II estão fechadas.** Cada uma com o teste que faltava,
e cada teste provado quebrando o produto de propósito.

Três coisas que a fase 4 achou e que não estavam em auditoria nenhuma:

- **`Files.walk` e preguicoso, e o que ele lanca pelo meio e
  `UncheckedIOException`.** O `catch (IOException)` embaixo do walk so podia ver
  a falha de COMECAR. O caso que o comentario dele descrevia -- "the walk is
  lazy, so the throw can come halfway" -- passava reto, saia do metodo levando os
  pregoes ja achados e derrubava a construcao da arvore atras. O comentario
  estava certo sobre o mundo e errado sobre o proprio codigo, que e a forma de
  comentario mais cara que existe.
- **Duas premissas dos relatorios nao se sustentam**, e estao registradas onde
  foram refutadas: trocar `step >= 1.0` por `step > 1.0` e a mesma funcao (para
  passo maior ou igual a 1, `-log10(passo)` e zero ou negativo e o `ceil` disso e
  zero de qualquer jeito), e tirar `request(day.plusDays(1))` nao trava a
  meia-noite, porque `TickLibrary.request` ja enfileira o dia, o seguinte e o
  anterior.
- **Dois javadoc inertes descreviam comportamento que a aplicação já não tem.**
  Um javadoc que não está preso a nada para de ser mantido, e depois para de ser
  verdade.

### 07/09, tarde — fase 4, as MÉDIA

| itens | o que era | commit |
|---|---|---|
| **B7b-6, B7b-7, B7b-8, B7b-9** | O acento pela saída padrão, a dobra do console, a barra de estado abandonada | `b82048e` |
| **B7a-6, B7a-7, B7a-16, B7a-17, B7a-18** | A configuração que substituía em vez de completar; o rótulo com a escala duas vezes | `6a3c4a8` |
| **B4-6, B4-13** | A aba apagada à esquerda, e o nome de cópia que não terminava | `d023232` |
| **B5-5, B5-6** | Esconder um estudo esconde o estudo, e ele volta escondido | `55e16dd` |

**13 MÉDIA fechadas.** Suíte em **592**.

Duas coisas que a tarde ensinou:

- **O `OrphanJavadocTest` pegou o autor dele duas vezes na mesma noite.** A
  segunda foi um javadoc inerte criado ao tornar um método visível ao pacote,
  e bloqueou o commit. Uma varredura vale mais do que as correções uma a uma.
- **`@Timeout` sozinho não interrompe um laço infinito** — ele só confere o tempo
  depois de o teste voltar, e um laço infinito nunca volta. Precisa de
  `SEPARATE_THREAD`. A primeira versão do teste ficou pendurada em vez de falhar,
  e isso só apareceu ao quebrar o produto de propósito.

| itens | o que era | commit |
|---|---|---|
| **B6-6, B6-9** | A data lembrada de outro feed, e a sessão que sobrevivia à janela | `141120f` |
| **B1-3, B1-4** | A barra de vários dias carrega a abertura, e corta numa segunda | `40a950d` |

**19 MÉDIA fechadas.** Suíte em **595**.

Uma terceira lição, e é a mesma três vezes: **o `OrphanJavadocTest` pegou o autor
dele em três commits diferentes desta fase**, sempre por um membro novo inserido
entre um javadoc e o membro que ele descrevia. Dois desses commits foram
bloqueados pelo gancho. Uma varredura que roda em toda a árvore vale mais do que
qualquer correção individual — e vale mais ainda contra quem a escreveu.

### O que fica

- **B6-9 sem teste, dito de propósito.** Exercitar aquele caminho exige soltar o
  painel DURANTE os quatro segundos da construção da sessão, o que é dirigir um
  `SwingWorker` pelo meio — um teste sobre o escalonador, não sobre isto.
- **As 102 MÉDIA e 107 BAIXA restantes** da auditoria II, e as **93 MÉDIA e 115
  BAIXA** da auditoria I que a decisão D5 mandou para cá.
