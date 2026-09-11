# O backtest

**Status: a linguagem e o motor estão construídos. A tela é proposta.**

| | | |
|---|---|---|
| `6b8f7ba` | a linguagem de execução | `domain.trading.order` — 11 testes |
| `f9ab9a3` | o motor | `domain.trading` — 22 testes |

Diagrama da tela: [backtest-panel.svg](backtest-panel.svg)

---

## De onde veio cada peça

| Fonte | O que ela resolveu, e nós aproveitamos |
|---|---|
| **Manual do NTSL** (seções 11, 13 e 15) | **O modelo inteiro.** Os doze verbos de ordem, e a semântica de execução — quando a decisão vale, o que acontece com a ordem que não executou, o que uma cobertura pode e não pode fazer. |
| **Os 45 robôs em `RoboMateus1`** | O que o manual permite e ele **realmente usa**: ordem limitada em repouso, escada de contratos, saída parcial em vários preços. Foi isso que refutou o vocabulário que eu tinha esboçado. |
| **MetaTrader 5** | O **teste à frente embutido na própria rodada** (1/2, 1/3, 1/4), distribuição por hora/dia/mês, saldo × patrimônio como duas curvas. |
| **Profit** | A forma do painel: seções recolhíveis *Execução*, *Abertura de Posição*, *Risco*, *Custos*. |
| **endeavour** | Os erros já pagos: custo de 6,5 pontos medido, trivial **por fatia**, série **crua**. |
| **ta4j** | Nada, e a próxima seção explica por quê. |

---

## Os dois modos, e por que são coisas diferentes

| | **Simulador** | **Backtest** |
|---|---|---|
| Quem decide | você | a estratégia |
| Como o tempo anda | barra a barra, no seu ritmo | de uma vez |
| O que responde | "eu teria conseguido operar isso?" | "esta regra tem vantagem?" |
| Viés próprio | você já sabe o que veio depois | a regra foi escolhida olhando esses dados |

Viés opostos, então **não compartilham relatório**. Este documento trata só do
backtest.

---

## A decisão que virou: o motor é nosso

A versão anterior deste documento propunha o **ta4j 0.17**, e o argumento era
bom: por que refazer, pior, o que já está pronto e testado?

O argumento caiu quando os robôs dele foram lidos. Um `Strategy` do ta4j é
`shouldEnter(i)` / `shouldExit(i)` devolvendo um booleano, sobre **uma** posição
por vez. Os robôs dele fazem três coisas que não cabem nisso:

- **Apregoam ordem limitada e esperam.** A entrada do canônico é um
  `SellShortLimit` a 15 pontos antes da banda — a "pescaria" — que fica lá
  enquanto os filtros permitirem. Um motor que só negocia na abertura seguinte
  não roda isso.
- **A quantidade é a decisão.** Entrada inicial, acumulação, núcleo, e um `N`
  calculado por sinal. Não é um contrato fixo.
- **Saem em pedaços, em vários preços ao mesmo tempo.** Dois
  `SellToCoverLimit` em alvos diferentes, apregoados juntos.

O `StopLimitExecutionModel` que existe no ta4j de hoje não resolve: ele deriva o
stop e o limite de **razões sobre o preço do sinal**, não aceita um preço
absoluto escolhido pela estratégia. E ele exige Java 25, que a IDE 2022.2.3 não
reconhece.

**O motor que roda um cruzamento de médias não é o motor que roda os robôs
dele.** Escrevemos o nosso: 9 classes, e a parte que o ta4j faria de graça — os
critérios de análise — é a parte fácil.

---

## O modelo de execução, e de onde cada regra saiu

Cinco regras do manual, e duas nossas. As nossas estão marcadas, porque a
diferença importa.

### Do manual

1. **O código roda no fechamento do candle, e nada executa antes da abertura
   seguinte.** O outro modo do NTSL — ordens assim que a condição é satisfeita —
   **não é modelado**, e não por escolha nossa: o manual diz que aquele modo "não
   é compatível com o backtest da aplicação".
2. **O livro é refeito a cada fechamento.** Ordem de entrada que não executou até
   o fim do candle seguinte "será cancelada ou editada quando o próximo candle
   finalizar, de acordo com a estratégia do usuário" — ou seja, **não reemitir é
   como se cancela**. É por isso que os robôs dele guardam o nível do stop numa
   `var` e reemitem `*ToCoverStop` em todo candle.
3. **Ordem de abertura no lado errado cobre**, automaticamente, e inverte se for
   maior que o que está aberto.
4. **Cobertura nunca inverte**, e contra posição zerada ou do lado errado é
   **ignorada** — não é erro, é silêncio.
5. **As coberturas são uma OCO**: no máximo uma executa por barra. É assim que a
   saída parcial funciona — o primeiro alvo executa, a OCO limpa o resto, e no
   fechamento seguinte o código reapregoa o que ainda quer.

### Nossas, porque o OHLC não responde

6. **As ordens executam da mais perto da abertura para a mais longe.** O preço
   não se afasta da abertura monotonicamente, mas quatro números por minuto não
   dizem mais que isso.
7. **Stop e alvo alcançáveis na mesma barra: ganha o stop.** Não porque seja
   verdade — porque é o lado conservador.

O que torna a regra 7 honesta não é acertar, é **contar**:
`Result.ambiguousBars()` diz quantas barras foram decididas pelo desempate. No
projeto anterior foi 0,1% das operações. Se subir, o resultado é um fato sobre o
desempate e não sobre a estratégia, e o número está lá para mostrar isso.

---

## As regras que decidem se o número é verdade

### 1. Sinal no fechamento, ordem na abertura seguinte ✅
Decidir e executar no mesmo fechamento usa um preço que ainda não existia. É a
forma mais comum de um backtest inventar vantagem. **Construído**, e é a primeira
prova de dentes do motor: trocar as duas linhas de lugar faz sete testes cair.

### 2. A fidelidade tem nome na tela ✅
A tela tem o **modo de execução** do próprio Profit, com os nomes dele:
`OHLC` e `Tick a Tick`, este o padrão. E o quadro diz qual dos dois rodou.

O que muda entre os dois não é precisão, é uma pergunta que some. Dentro de um
minuto a máxima e a mínima aconteceram as duas, e o OHLC não diz em que ordem:
um stop e um alvo dentro do mesmo minuto são decididos por uma **regra** — a
nossa toma o stop, que é o lado conservador — e a regra decide parte de todo
resultado que toca. Andando um caminho de ticks a pergunta nunca é feita.

Então, dito honestamente: **o OHLC dá um resultado mais um desempate, e os ticks
dão um resultado mais um caminho inventado cujas estatísticas foram medidas.**
Os dois são aproximações, de coisas diferentes — que é por isso que a escolha
está na tela em vez de decidida no código.

**Quando a série é de ticks reais, anda sobre eles.** A fita do Profit alcança
oito pregões; o que vier antes só existe como minuto, e o minuto é caminhado
pelo `SyntheticTicks`, cujas constantes foram medidas contra essa mesma fita
(97,1% dos passos são um tick, 17,6% seguem a direção do anterior). O quadro
separa os dois casos porque **não são a mesma evidência**: um stop batido na
fita foi batido; um batido no caminho sintético foi batido por um dos caminhos
que aquele minuto poderia ter tomado.

A lista de escala é **desligada** no modo tick a tick, e não ignorada em
silêncio: o caminho foi medido contra a forma de um *minuto*, e gerá-lo dentro
de uma barra de cinco minutos é aplicar aquelas estatísticas a algo que elas
nunca mediram.

Custo, medido na base dele: 1.500 ticks por minuto. Um dia vira 986.840 ticks
(93 ms), uma semana 4,5 milhões (274 ms), um mês 17 milhões (963 ms) — e é por
isso que o recorte se escolhe **antes** da rodada.

O quadro continua dizendo **quantas barras foram decididas pelo desempate**,
que no modo OHLC é o único lugar onde a falta de tick pode doer.

### 3. O custo é fixo em dinheiro, e a medição é em pontos ✅
6,5 pontos por giro completo de **um contrato**, cobrado metade em cada ponta,
por contrato — então a escada que entra em três e sai em três paga 19,5.
**Construído.** Só vale na série **crua**; a tela precisa dizer em que série
rodou, porque nenhuma trava no código consegue impedir isso.

### 4. O teste à frente é parte da rodada, não um segundo passo
Roubado inteiro do MetaTrader. O período se divide em **amostra** e **à frente**
(`1/2`, `1/3`, `1/4` ou data escolhida), a fronteira aparece como **linha
vertical no gráfico**, e o relatório traz **as duas colunas lado a lado**.

No projeto anterior a carteira tinha `t = +3,75` na amostra e `+0,93` fora — e só
se descobriu porque alguém foi conferir depois. Se as duas colunas nascem juntas,
ninguém precisa lembrar de conferir.

### 5. O resultado é por fatia, e o trivial é o da fatia
Um total no período inteiro esconde a estratégia que ganhou tudo em 2022. E
comparar o acerto de uma fatia com o trivial **global** inventa vantagem.

---

## As estratégias que existem

**Cruzamento de médias** — duas médias, período e tipo na tela própria dela. É a
estratégia de referência: simples o bastante para se conferir à mão.

**Range 90** — a do `ESPECIFICACAO_ESTRATEGIA_RANGE90_PULLBACK.md`. Ela existe
menos pelo resultado e mais pela **máquina**: livro de lotes, stop individual por
lote, parcial de metade por lote, e um seletor walk-forward que decide toda manhã
se opera e se o alvo é 1,5R ou 2R. Eram quatro coisas que o motor nunca tinha
sido obrigado a fazer.

Ela **já está refutada** — a nota `range-de-abertura-e-bussola` a registra
negativa nas três células da base cega, e os R$ 148.811 da §19 da especificação
vêm da base em que a estratégia foi procurada.

Três coisas que é preciso saber para usá-la:

- **Recorte curto não gera operação nenhuma.** O seletor precisa de 20 pregões
  recentes e 20 amostras estruturais *dentro do trecho que roda*; um mês não tem
  história dentro de si. O quadro avisa quando isso acontece, em vez de mostrar
  uma tabela de zeros.
- **O alvo não está na tela dela**, de propósito: quem escolhe entre 1,5R e 2R é
  o seletor, com os pregões anteriores. Pô-lo na tela seria deixar escolher o
  alvo depois de ver o resultado.
- **Os números não batem com o Python**, e não devem bater. Quatro diferenças,
  todas o mesmo caso — a implementação de referência lendo algo que este motor
  não deixa uma estratégia ler: a entrada é uma ordem em repouso e não um preço
  escolhido depois do candle fechar; o lote acrescentado paga o gap; o dia cujos
  dois lados romperam dentro de *um* minuto é entrado aqui; e vários lotes só
  saem dentro do mesmo minuto no modo tick a tick, porque as coberturas são uma
  OCO no Profit. Fazer o motor bater com o Python seria fazer o motor mentir.

### O livro de lotes precisou de uma leitura nova

`Market.filled()` entrega as execuções da própria barra — e o NTSL não tem isso,
porque estratégia de NTSL não mantém livro de lotes. A nossa mantém, e pela
posição líquida não há como saber **qual** lote acabou de fechar. As duas saídas
eram adivinhar pelos preços — refazendo os desempates do motor dentro de cada
estratégia, onde iam divergir dele na primeira mudança de qualquer um dos dois —
ou perguntar. Uma mesa de verdade sabe as próprias execuções.

Não vaza futuro: o laço executa as ordens em repouso **antes** de entregar a
barra à estratégia, então o que está ali já aconteceu, em preços que a estratégia
pediu no fechamento anterior.

---

## A tela

**Construída.** O painel mora dentro da janela do gráfico, encaixado como uma
janela normal e com controle para soltá-lo, e tem duas partes.

**① A linha de comando** — sempre visível, uma linha só:
chave `Simulador`/`Backtest` · estratégia ▾ · ⚙ · período ▾ · à frente ▾ ·
custo · ▶ Rodar · progresso.

**② A área de resultado** — recolhível, em abas:
`Resumo` · `Operações` · `Curva` · `Distribuição`.

O detalhe fica atrás da ⚙, num diálogo com as quatro seções do Profit:
*Execução*, *Abertura de Posição*, *Risco*, *Custos*. É a diferença entre a
janela do Profit e a nossa: lá o backtest ocupa a tela toda e cabe um trilho
lateral; aqui ele divide espaço com o gráfico, que pode estar num quarto da
tela.

**As marcas no gráfico** — ▲ entrada, ▼ saída, ligadas por linha verde ou
vermelha, mais a linha vertical do início do "à frente" — são a razão de o
painel morar dentro da janela do gráfico. O número diz *quanto*; só o desenho
diz *onde*, e é olhando onde que se descobre que a estratégia só ganha na
primeira hora do pregão.

Do MetaTrader vem ainda a aba **Distribuição** (por hora, dia da semana, mês) e
a **curva dupla**: saldo (fecha operação) e patrimônio (marca a mercado). A
diferença entre as duas é o rombo dentro da operação aberta, que a curva de
saldo esconde.

---

## Ordem de construção

| | Etapa | Estado |
|---|---|---|
| 1 | `PriceSeries` em `domain.market`; fronteira ampliada | ✅ feito antes |
| 2 | **A linguagem de execução** — 12 verbos + 3 comandos | ✅ `6b8f7ba` |
| 3 | **O motor** — livro em repouso, execução intrabarra, operações | ✅ `f9ab9a3` |
| 4 | Uma estratégia clássica: cruzamento de médias | ✅ |
| 5 | **Recorte** (13 entradas, contadas em pregões) | ✅ `12cfe07` |
| 6 | ① linha de comando + quadro de resultado | ✅ |
| 7 | Marcas no gráfico + tabela de operações | ✅ |
| 8 | Curva de patrimônio, saldo e custo | ✅ |
| 9 | **Modo de execução**: OHLC ou tick a tick, fita real quando existe | ✅ `6447490` |
| 10 | **Range 90** — livro de lotes, parciais e seletor walk-forward | ✅ `72a092c` |
| 11 | Teste à frente dentro da rodada (amostra × fora) | ⬜ |
| 12 | Distribuição por hora, dia da semana e mês | ⬜ |
| 13 | *(depois)* Tradutor para NTSL, precedido de **uma tradução à mão** | ⬜ |

A etapa 11 continua aberta e continua sendo a que mais importa. O recorte já
existe, mas a rodada ainda dá **um número só**: quem quiser saber se a
estratégia sobrevive fora da amostra tem que rodar duas vezes e comparar à mão
— e foi exatamente assim que a carteira do projeto anterior passou de `t=+3,75`
para `+0,93` sem ninguém perceber na hora.

---

## As decisões

Quatro das seis se resolveram sozinhas — não por acordo, por evidência.

| | Pergunta | Resposta |
|---|---|---|
| 1 | ta4j ou motor próprio? | **Motor próprio.** Os robôs dele não cabem no ta4j. |
| 2 | 1 contrato fixo? | **Não.** A quantidade é parte da decisão, em toda ordem. |
| 3 | Comprado e vendido? | **Os dois**, e a inversão também. |
| 4 | Stop e alvo já na v1? | **Sim.** O motor os executa agora — o que resolve a ressalva antiga de que "o motor não executa stop", registrada na memória `rotulo-e-motor-discordam`. |

Duas continuam abertas, e travam a etapa 5:

5. **Fatias: por trimestre?** (com 4 anos dá 16 — consistência visível sem virar
   ruído)
6. **Padrão do "à frente": `1/3`?** (o MetaTrader oferece 1/2, 1/3, 1/4)

---

## A linguagem portável

O vocabulário de execução **já existe** e está em `domain.trading.order` — foi
extraído do manual e conferido contra os robôs, não desenhado. O que continua
adiado é o **tradutor** para NTSL e MQL5, e o motivo está em
[EXECUCAO-PORTAVEL.md](EXECUCAO-PORTAVEL.md): a tradução manual de uma estratégia
é o que revela se o vocabulário está certo, não o contrário.
