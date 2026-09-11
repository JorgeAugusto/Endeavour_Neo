# A linguagem de execução das estratégias

**Status: o vocabulário está construído; o tradutor não.** A camada de
execução vive em `domain.trading.order` desde `6b8f7ba`, extraída do manual do
NTSL e conferida contra os 45 robôs em `RoboMateus1`. O que continua parado é o
**tradutor** para NTSL e MQL5, e o motivo está no fim deste documento. Ver
[BACKTEST.md](BACKTEST.md).

---

## O que se quer

Toda estratégia nossa é, no fim, um robô. E um robô que só roda aqui não serve:
a estratégia que sobreviver às três camadas de validação vai ter que ser
traduzida para **NTSL** (Profit) ou **MQL5** (MetaTrader) para operar de
verdade.

Se cada estratégia inventar o seu jeito de pedir ordens, cada tradução é um
trabalho novo, e cada uma erra de um jeito diferente. Se todas falarem a mesma
língua na hora de executar, o tradutor se escreve **uma vez**.

Esse é o objetivo, e ele está certo.

---

## O que eu mudaria no formato

### 1. Não é uma DSL. É um vocabulário pobre.

Uma DSL é gramática, analisador, interpretador, mensagens de erro e ferramenta
de depuração — uma pilha inteira para manter. Nada disso é necessário aqui.

O que entrega o objetivo é **um conjunto fixo e pequeno de tipos Java** dizendo
o que a estratégia quer que aconteça no mercado. O valor está na **restrição**,
não em ser uma linguagem.

E há um efeito colateral perigoso em chamar de linguagem: linguagem convida a
ser expressiva, e expressividade aqui é exatamente o defeito.

### 2. Projetar para o alvo mais pobre, nunca para o mais rico

O que o vocabulário pode dizer é a **interseção** do que os alvos executam:

```
     NTSL  ∩  MQL5  ∩  nosso motor
```

O NTSL é o mais pobre dos três, e já sabemos onde ele aperta: ordens a mercado e
inversão feita em **duas** ordens. Está registrado no porte da rede para o
Profit.

**Corrigido em 12/09/2026: o "sem parcial" que estava escrito aqui é falso.** O
manual diz que a quantidade é opcional em toda ordem de cobertura e que, dada no
código, prevalece sobre a aba de Execução; e o `RoboPadraoTendencia` do acervo
emite `SellToCoverLimit(vAlvoParcial, vQtdParcial)` e
`SellToCoverLimit(vAlvoAtivo, vPosC - vQtdParcial)` lado a lado, em produção. A
frase vinha do porte da rede, onde a decisão de usar só ordens a mercado era da
*rede*, não do NTSL — e virou uma limitação da plataforma sem nunca ter sido uma.

O que o NTSL realmente exige, e que o acervo registra com todas as letras, é
**um call-site por ordem**: "alternar o tipo/tamanho no MESMO lugar faria o
Profit cancelar e recriar a ordem a cada passada". Um laço emitindo cinco preços
diferentes do mesmo ponto do código é exatamente isso. Quem for gerar NTSL tem
que desenrolar.

Dimensionado ao NTSL, traduzir é mecânico. Dimensionado ao MQL5, algumas
estratégias simplesmente não portam — e isso se descobre tarde, depois de a
estratégia ter passado por toda a validação.

**Regra:** um verbo só entra no vocabulário quando os três alvos o executam.

### 3. O que quebra tradução é o TEMPO, não o tipo de ordem

Este é o ponto que passa despercebido e custa caro.

| | quando a decisão vale |
|---|---|
| nosso motor | sinal no fechamento, ordem na **abertura seguinte** |
| NTSL | roda no fechamento de cada barra |
| MQL5 | tick a tick ou por barra, conforme o modo |

Duas traduções fiéis ao *tipo de ordem* e diferentes no *momento* produzem
listas de operações diferentes — e ninguém percebe, porque as duas parecem
certas.

**Portanto o momento em que a decisão passa a valer é parte do vocabulário**,
declarado, e não uma escolha do tradutor.

### 4. O que transforma portabilidade em fato é um teste de conformidade

Vocabulário comum dá a *sensação* de portabilidade. O que dá portabilidade é
rodar a mesma estratégia nos dois lados sobre as mesmas barras e **exigir que a
lista de operações bata**.

Já temos a técnica: a régua do renko foi validada exatamente assim, contra os
tooltips do Profit, até reproduzir três caixas na unha. E já levamos o prejuízo
do caso contrário — o limiar de confiança que ficava guardado no gráfico do
Profit e travava o robô em silêncio depois de recompilar.

Sem esse teste, o vocabulário comum é uma promessa.

### 5. Duas camadas, e só uma precisa portar

A separação que você já fez ao dizer *"quando se trata de execução de ordens"*
está certa, e vale deixar explícita:

| camada | o que é | precisa portar? |
|---|---|---|
| **condição** | como a estratégia decide — indicadores, regras, rede | não do mesmo jeito |
| **execução** | o que ela pede ao mercado | **sim, e é só isto** |

A camada de condição pode ser rica à vontade. A de execução é deliberadamente
pobre. Misturar as duas é o que torna uma estratégia intraduzível.

---

## O que ler quando chegarmos nisso, em ordem de valor

| fonte | o que tirar | valor |
|---|---|---|
| **Manual do NTSL / Profit** | a restrição que manda em tudo: o vocabulário se dimensiona por ele | **alto — ler primeiro** |
| **MQL5, modelo de ordens** | principalmente para saber o que **não** incluir | alto |
| **ta4j** | `Rule` e sua composição (`and`, `or`, `xor`, `negation`) para a camada de *condição*; a de execução dele é fina perto da nossa | médio |
| **Chartsy** | nada. É plataforma de gráfico; li o modelo de plugins dela e não há modelo de execução | **nenhum** |

---

## O esboço que eu tinha feito, e por que ele estava errado

Era este, e não é mais:

```
BeLong(size)        quero estar comprado
BeShort(size)       quero estar vendido
BeFlat()            quero estar zerado
```

Três verbos e um momento, e eu escrevi ali que **essa pobreza é a virtude**.
A ideia estava certa; o tamanho estava errado. Quando os robôs foram lidos,
**nenhum deles cabia nisso** — e não por sofisticação, por três hábitos
banais:

- **Ordem limitada em repouso.** A pescaria apregoa `SellShortLimit` a 15
  pontos antes da banda e espera. Não é "quero estar vendido" — é "quero estar
  vendido *ali*, se o mercado vier".
- **A quantidade é a decisão.** Escada que acumula, núcleo, `N` calculado por
  sinal. `BeLong(size)` só sabe dizer o tamanho final, não o pedido.
- **Saída parcial em vários preços ao mesmo tempo.** Dois `SellToCoverLimit` em
  alvos diferentes, vivos juntos.

A lição não é que pobreza seja ruim. É que **o piso do vocabulário não se
escolhe, se mede** — e a régua é o que o alvo mais pobre executa, não o que
parece elegante no papel.

## O vocabulário que ficou

Os doze verbos do NTSL não são doze ideias. São três perguntas feitas de uma
vez:

```
{Buy, SellShort, BuyToCover, SellToCover} × {AtMarket, Limit, Stop}
    = Side × Purpose × Trigger
```

Mais `ClosePosition`, `ReversePosition` e `CancelPendingOrders`. `Desk` é a
porta de entrada, com os nomes do Profit e a quantidade opcional exatamente
onde o NTSL a deixa opcional — então portar um robô é transcrição, não
interpretação.

Stop e alvo **entraram**, ao contrário do que este documento dizia. A ressalva
era que o motor não os executava; o motor de agora executa.

---

## O risco de fazer isso cedo demais — que se realizou

Este documento avisava: um vocabulário desenhado **antes** de existirem
estratégias é um vocabulário desenhado contra estratégias imaginárias. Foi
exatamente o que aconteceu com o esboço de três verbos.

O que o salvou não foi esperar — foi trocar a fonte. **Em vez de imaginar as
estratégias, lemos as que já existem**: o manual diz o que é executável, e os
45 robôs dizem o que ele de fato usa. Vocabulário lido não corre o risco de
vocabulário inventado.

**O tradutor continua parado**, e por uma razão que não mudou: a tradução
manual de uma estratégia é o que revela se o vocabulário está certo, não o
contrário. Ele espera o backtest rodando de ponta a ponta, a família clássica
escrita, e pelo menos uma estratégia **já portada à mão** para o NTSL.
