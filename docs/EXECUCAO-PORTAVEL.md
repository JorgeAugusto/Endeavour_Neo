# A linguagem de execução das estratégias

**Status: registrado, nada decidido e nada construído.** Este documento existe
para o assunto estar claro quando chegarmos nele — depois do backtest, não
antes. Ver [BACKTEST.md](BACKTEST.md).

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

O NTSL é o mais pobre dos três, e já sabemos onde ele aperta: ordens a mercado,
inversão feita em **duas** ordens, sem parcial. Está registrado no porte da rede
para o Profit.

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

## O esboço, para discordar dele depois

Não é proposta; é o menor vocabulário que cobriria o que já existe.

```
Intent              o que diz                        NTSL   MQL5   nosso
------------------------------------------------------------------------
BeLong(size)        quero estar comprado             sim    sim    sim
BeShort(size)       quero estar vendido              sim    sim    sim
BeFlat()            quero estar zerado               sim    sim    sim

When.NEXT_OPEN      vale na abertura da barra seguinte
When.THIS_CLOSE     vale no fechamento desta barra
```

Três verbos e um momento. É quase exatamente o `Signal` que o `endeavour` já
tem — **e essa pobreza é a virtude**, não uma primeira versão a ser enriquecida.

Stop e alvo **ficam de fora por enquanto**, pela mesma razão registrada na
decisão 4 do backtest: o motor não os executa, e um vocabulário que os
declarasse repetiria o erro do rótulo que supõe o que o motor não faz.

---

## O risco de fazer isso cedo demais

Um vocabulário de execução desenhado **antes** de existirem estratégias é um
vocabulário desenhado contra estratégias imaginárias. Ele vai ter verbos que
ninguém usa e faltar o que a terceira estratégia real precisar.

Por isso este documento fica parado até termos: o backtest rodando, a família
clássica escrita, e pelo menos uma estratégia que **já tenha sido portada à
mão** para o NTSL. A tradução manual é o que revela o vocabulário — não o
contrário.
