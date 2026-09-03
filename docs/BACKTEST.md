# O painel de backtest

**Status: proposta, aguardando aprovação.** Nada disto está construído.

Diagrama da tela: [backtest-panel.svg](backtest-panel.svg)

---

## De onde veio cada peça

| Fonte | O que ela resolveu, e nós aproveitamos |
|---|---|
| **ta4j** (MIT, clonado em `referencias/`) | O motor. Execução na abertura seguinte já é o padrão, modelos de custo, 31 critérios de análise prontos. |
| **MetaTrader 5** | O **teste à frente embutido na própria rodada** (1/2, 1/3, 1/4), os modos de modelagem nomeados pela fidelidade, distribuição por hora/dia/mês, saldo × patrimônio como duas curvas. |
| **Profit** | A forma do painel: seções recolhíveis *Execução*, *Abertura de Posição*, *Risco*, *Custos*. E `OHLC` × `Tick a Tick` como escolha explícita. |
| **endeavour** | Os erros já pagos: custo de 6,5 pontos medido, trivial **por fatia**, série **crua**, e rótulo que não pode supor o que o motor não executa. |

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

## A decisão de maior consequência: usar o ta4j

Eu ia propor motor próprio. Depois de ler o ta4j, isso seria refazer, pior, o que
já está pronto e testado. Mas há uma restrição dura de versão:

| Versão | Java exigido | Traz |
|---|---|---|
| **0.17** (set/2024) | **11** ✅ | `BarSeriesManager` com `TradeOnNextOpenModel` **por padrão**, modelos de custo, 31 critérios |
| 0.19–0.22.1 | 21 | — |
| 0.22.4+ | 25 | `WalkForwardEngine`, `StrategyWalkForwardExecutor`, `PositionSizer` |

Verificado, não suposto: o `ta4j-core-0.17.jar` resolve do Maven Central e seu
bytecode é **major 55 (Java 11)** — roda no nosso 17. Esta máquina não tem JDK 25,
e a IDE é de 2023; subir o projeto para 25 por causa do arcabouço de
andar-para-frente não se paga, porque **fatiar a série e rodar o motor por fatia
são umas cinquenta linhas nossas**, não um arcabouço.

**Proposta: ta4j 0.17 para o motor; o fatiamento e o veredito são nossos.**

A divisão é limpa e vale dizer em voz alta: **o ta4j executa as operações, nós
somos donos da conclusão.** Tudo que o projeto anterior aprendeu — quanto custa
de verdade, contra o que comparar, quando um resultado não replica — é
interpretação, e nenhuma biblioteca faz isso por você.

Licença: ta4j é MIT, compatível com a GPL v2 deste projeto.

---

## As cinco regras que decidem se o número é verdade

### 1. Sinal no fechamento, ordem na abertura seguinte
Decidir e executar no mesmo fechamento usa um preço que ainda não existia. É a
forma mais comum de um backtest inventar vantagem. **O ta4j já faz isso por
padrão** (`TradeOnNextOpenModel`); o `TradeOnCurrentCloseModel` existe e **não
vamos oferecer**.

### 2. A fidelidade tem nome na tela
O MetaTrader acerta em nomear o que está sendo simulado: *todos os ticks*, *OHLC
em M1*, *apenas preços de abertura*. Nós temos barras de 1 minuto e **medimos que
tick não acrescenta** (0,001 de R² sobre a volatilidade). Então a tela diz
**`OHLC em M1`** e mais nada — e diz também **quantas operações tocaram stop e
alvo na mesma barra**, que é o único lugar onde a falta de tick pode doer. No
projeto anterior foram 0,1%; se subir muito, o resultado depende da regra de
desempate e não da estratégia.

Regra de desempate: **o stop primeiro**. Não por ser verdade, por ser o lado
conservador.

### 3. O custo é fixo em dinheiro, e a medição é em pontos
6,5 pontos por operação completa — medido. Só vale na série **crua**. A tela
mostra o custo sempre e **avisa quando ele é irreal**; o caso perigoso não é o
custo zerado, é **um** dos dois zerado: parece normal e não é.

### 4. O teste à frente é parte da rodada, não um segundo passo
Roubado inteiro do MetaTrader, e é a melhor ideia das três telas. O período se
divide em **amostra** e **à frente** (`1/2`, `1/3`, `1/4` ou data escolhida), a
fronteira aparece como **linha vertical no gráfico**, e o relatório traz **as
duas colunas lado a lado**.

Isto não é conveniência. No projeto anterior a carteira tinha `t = +3,75` na
amostra e `+0,93` fora — e só se descobriu porque alguém foi conferir depois.
Se as duas colunas nascem juntas, ninguém precisa lembrar de conferir.

### 5. O resultado é por fatia, e o trivial é o da fatia
Um total no período inteiro esconde a estratégia que ganhou tudo em 2022. E
comparar o acerto de uma fatia com o trivial **global** inventa vantagem — o
trivial muda de fatia para fatia.

---

## A tela

O painel entra **abaixo das abas de layout**, dentro da janela do gráfico, e tem
duas partes.

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

| | Etapa | Entrega |
|---|---|---|
| 1 | `PriceSeries` desce para `domain.market`; fronteira ampliada | nada visível; tudo depois depende disso |
| 2 | ta4j 0.17 no `pom`; adaptador `PriceSeries` → `BarSeries` | motor disponível, com teste do adaptador |
| 3 | `Strategy` nossa (posição desejada) + uma: cruzamento de médias | primeira rodada, sem tela |
| 4 | Fatiamento e teste à frente | o que torna o resultado confiável — **antes** da tela |
| 5 | ① linha de comando + `Resumo` | primeiro resultado visível |
| 6 | Marcas no gráfico + `Operações` | o que torna o painel útil |
| 7 | `Curva` e `Distribuição` | |

A etapa 4 vem **antes** da tela de propósito. Uma tela que mostra um número
único é uma tela que ensina a olhar o número errado, e depois é tarde.

---

## O que eu preciso que você decida

1. **ta4j 0.17, ou motor próprio?** (sugiro ta4j — a alternativa é refazer pior)
2. **Tamanho da posição:** 1 contrato fixo por ora? (sugiro sim; sizing é outra
   camada de decisão e contamina a medição da regra)
3. **Comprado e vendido, ou só comprado?** (sugiro os dois)
4. **A estratégia declara stop/alvo já na primeira versão, ou só a posição
   desejada?** (sugiro **só a posição**; stop entra junto com a contagem de
   barras ambíguas que o valida — senão repete-se o "rótulo supõe stop que o
   motor não executa")
5. **Fatias:** por trimestre? (com 4 anos dá 16 — consistência visível sem virar
   ruído)
6. **Padrão do "à frente":** `1/3`? (o MetaTrader oferece 1/2, 1/3, 1/4)
