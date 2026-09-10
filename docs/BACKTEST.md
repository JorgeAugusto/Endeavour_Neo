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

### 2. A fidelidade tem nome na tela
Temos barras de 1 minuto e **medimos que tick não acrescenta** (0,001 de R² sobre
a volatilidade). Então a tela diz **`OHLC em M1`** e mais nada — e diz também
**quantas barras foram decididas pelo desempate** (a regra 7 acima), que é o
único lugar onde a falta de tick pode doer.

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

## A tela

**Ainda proposta.** O painel entra **abaixo das abas de layout**, dentro da
janela do gráfico, e tem duas partes.

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
| 4 | Uma estratégia clássica: cruzamento de médias | ⬜ |
| 5 | **Fatiamento e teste à frente** | ⬜ **antes da tela** |
| 6 | ① linha de comando + `Resumo` | ⬜ |
| 7 | Marcas no gráfico + `Operações` | ⬜ |
| 8 | `Curva` e `Distribuição` | ⬜ |
| 9 | *(depois)* Tradutor para NTSL, precedido de **uma tradução à mão** | ⬜ |

A etapa 5 vem **antes** da tela de propósito. Uma tela que mostra um número
único é uma tela que ensina a olhar o número errado, e depois é tarde.

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
