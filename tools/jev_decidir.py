"""Pergunta ao Jev, uma vez por estado, e grava as decisoes num CSV.

O passo do meio entre JevStates (que escreve os estados) e JevAdvice (que opera
as decisoes dentro do backtest). Existe como processo a parte, e em Python, por
tres razoes:

1. O backtest NAO pode chamar a rede. Uma chamada por barra e inviavel em
   escala, e -- o que decide -- um backtest que consulta um modelo
   probabilistico responde diferente a cada rodada. Um numero que muda sozinho
   entre duas leituras nao e medicao.

2. O neo nao tem dependencia de runtime e nao vai adquirir uma por isto. O SDK
   do TypeSafe e Python e JavaScript; nenhum e Java.

3. Separado, ele pode ser reexecutado, interrompido e retomado sem tocar no
   motor. Este script RETOMA: se o CSV de saida ja existe, ele pula os tempos
   que ja estao la.

USO
    set TYPESAFE_API_KEY=...        (ou use o typesafe-key.ps1)
    python jev_decidir.py estados.jsonl decisoes.csv

    --paralelas N   quantos pedidos ao mesmo tempo (padrao 12)
    --limite N      para depois de N estados, para provar o caminho barato
    --modelo NOME   padrao jev-latest

O QUE ELE PERGUNTA, e por que duas perguntas e nao uma: as duas viajam no MESMO
pedido e sao avaliadas em paralelo contra o mesmo estado, o que a documentacao
deles mede em 12,2x de economia. Entao pedir as duas custa quase o mesmo que
pedir uma, e a segunda -- a probabilidade de alta -- e a que se pode CALIBRAR
depois: se ela disser 0,70 e acertar 70% das vezes, o modelo sabe alguma coisa,
e isso se mede sem precisar que o historico seja fora da amostra.
"""
import argparse
import csv
import json
import os
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ENDERECO = 'https://api.typesafe.ai/v1/systemone'

# A PERGUNTA E CONGELADA. Mexer nela depois de olhar o resultado e ajustar o
# prompt a amostra -- a mesma doenca de ajustar um parametro a ela, com o
# agravante de que ninguem conta as tentativas.
INSTRUCAO_LADO = (
    'Este e o estado do mini-indice da B3 (WIN) neste instante do pregao. '
    'Olhando somente o que esta descrito, qual posicao tem o melhor resultado '
    'esperado nos proximos trinta a sessenta minutos?'
)

OPCOES_LADO = {
    'comprar': 'abrir comprado agora',
    'vender': 'abrir vendido agora',
    'fora': 'nao operar este momento',
}

INSTRUCAO_ALTA = (
    'O preco estara MAIS ALTO daqui a trinta minutos do que esta agora?'
)


# A SEGUNDA FORMULACAO. A primeira pergunta "que posicao tomar", que e um
# julgamento; esta pergunta pelo EVENTO que de fato esta sendo operado, com
# alvo e stop explicitos. Duas vantagens que a primeira nao tem: a resposta e
# diretamente acionavel -- com alvo 300, stop 200 e custo 6,5 o ponto de
# equilibrio e p > 0,413 -- e ela e CALIBRAVEL, porque "isso acontece 60% das
# vezes" tem uma frequencia observada para comparar. Lucro depende de haver
# sinal; calibracao nao.
SOBE = ('A partir do preco atual, e dentro do MESMO pregao, o preco vai SUBIR '
        '300 pontos ANTES de cair 200 pontos?')

CAI = ('A partir do preco atual, e dentro do MESMO pregao, o preco vai CAIR '
       '300 pontos ANTES de subir 200 pontos?')

# Acima disto uma ponta paga alvo 300 contra stop 200 com custo de 6,5:
# 500p - 200 - 6,5 > 0.
EQUILIBRIO = 0.413


def perguntas(qual):
    if qual == 'movimento':
        return {'sobe': {'type': 'noul', 'instructions': SOBE},
                'cai': {'type': 'noul', 'instructions': CAI}}

    return {'lado': {'type': 'choice', 'instructions': INSTRUCAO_LADO,
                     'criteria': OPCOES_LADO},
            'alta': {'type': 'noul', 'instructions': INSTRUCAO_ALTA}}


def perguntar(estado, chave, modelo, qual='lado'):
    """Um pedido, com as duas perguntas juntas. Devolve (tempo, lado, alta, confianca)."""
    corpo = json.dumps({
        'model': modelo,
        'state': estado,
        'questions': perguntas(qual),
    }).encode('utf-8')

    pedido = urllib.request.Request(
        ENDERECO, data=corpo, method='POST',
        headers={'Authorization': 'Bearer ' + chave,
                 'Content-Type': 'application/json'})

    try:
        with urllib.request.urlopen(pedido, timeout=90) as resposta:
            lido = json.loads(resposta.read().decode('utf-8'))
    except urllib.error.HTTPError as erro:
        corpo_erro = erro.read().decode('utf-8', 'replace')

        return ('ERRO', estado['tempo'], erro.code, corpo_erro[:300])
    except Exception as erro:  # noqa: BLE001 -- rede e timeout, e sao muitos
        return ('ERRO', estado['tempo'], 0, str(erro)[:300])

    respostas = lido.get('answers', {})
    usados = lido.get('usage', {}).get('input_tokens', 0)

    if qual == 'movimento':
        sobe = respostas.get('sobe', {}).get('noul', 0.0)
        cai = respostas.get('cai', {}).get('noul', 0.0)

        # O LADO SAI DA COMPARACAO, e so quando a ponta escolhida paga. Abaixo
        # do equilibrio nenhuma das duas cobre alvo, stop e custo, e a resposta
        # honesta e nao operar.
        melhor = max(sobe, cai)
        lado = 0 if melhor < EQUILIBRIO else (1 if sobe >= cai else -1)

        # "alta" guarda a probabilidade de subir 300 antes de cair 200, que e a
        # coluna que a calibracao vai conferir contra a frequencia observada.
        return ('OK', estado['tempo'], lado, sobe, melhor, usados)

    escolha = respostas.get('lado', {})
    lado = {'comprar': 1, 'vender': -1}.get(escolha.get('choice'), 0)

    return ('OK', estado['tempo'], lado,
            respostas.get('alta', {}).get('noul', 0.5),
            escolha.get('confidence', 0.0), usados)


def ja_feitos(caminho):
    """Os tempos que o CSV de saida ja tem, para o script poder ser retomado."""
    if not os.path.exists(caminho):
        return set()

    feitos = set()

    with open(caminho, encoding='utf-8') as arquivo:
        for linha in arquivo:
            linha = linha.strip()

            if not linha or linha.startswith('#') or linha.startswith('tempo'):
                continue

            partes = linha.split(';')

            if partes and partes[0].isdigit():
                feitos.add(int(partes[0]))

    return feitos


def main():
    analisador = argparse.ArgumentParser()
    analisador.add_argument('estados')
    analisador.add_argument('decisoes')
    analisador.add_argument('--paralelas', type=int, default=12)
    analisador.add_argument('--limite', type=int, default=0)
    analisador.add_argument('--modelo', default='jev-latest')
    analisador.add_argument('--pergunta', default='lado', choices=['lado', 'movimento'])
    argumentos = analisador.parse_args()

    chave = os.environ.get('TYPESAFE_API_KEY')

    if not chave:
        print('TYPESAFE_API_KEY nao esta no ambiente.')
        print('No Windows: rode o typesafe-key.ps1 e ABRA UM TERMINAL NOVO --')
        print('um processo ja aberto nao enxerga variavel criada depois dele.')

        return 1

    with open(argumentos.estados, encoding='utf-8') as arquivo:
        estados = [json.loads(linha) for linha in arquivo if linha.strip()]

    feitos = ja_feitos(argumentos.decisoes)
    faltam = [e for e in estados if e['tempo'] not in feitos]

    if argumentos.limite:
        faltam = faltam[:argumentos.limite]

    print('%d estados, %d ja feitos, %d a perguntar'
          % (len(estados), len(feitos), len(faltam)))

    if not faltam:
        return 0

    novo = not feitos
    tokens = 0
    erros = 0

    with open(argumentos.decisoes, 'a', encoding='utf-8', newline='') as saida:
        escritor = csv.writer(saida, delimiter=';', lineterminator='\n')

        if novo:
            escritor.writerow(['# jev', argumentos.modelo, argumentos.pergunta])
            escritor.writerow(['tempo', 'lado', 'alta', 'confianca'])

        with ThreadPoolExecutor(max_workers=argumentos.paralelas) as piscina:
            for i, r in enumerate(piscina.map(
                    lambda e: perguntar(e, chave, argumentos.modelo,
                                        argumentos.pergunta), faltam), 1):

                if r[0] == 'ERRO':
                    erros += 1

                    if erros <= 5:
                        print('  erro em %s: HTTP %s %s' % (r[1], r[2], r[3]))

                    continue

                _, tempo, lado, alta, confianca, usados = r
                tokens += usados

                # Virgula decimal, como todo arquivo deste projeto, para a
                # planilha nesta localidade abrir sem caixa de dialogo.
                escritor.writerow([tempo, lado,
                                   ('%.4f' % alta).replace('.', ','),
                                   ('%.4f' % confianca).replace('.', ',')])

                if i % 200 == 0:
                    saida.flush()
                    print('  %d/%d  %d tokens  US$ %.3f'
                          % (i, len(faltam), tokens, tokens * 42.0 / 1e9))

    print('pronto: %d erros, %d tokens de entrada, US$ %.3f'
          % (erros, tokens, tokens * 42.0 / 1e9))

    if erros:
        print('rode de novo para tentar so os que faltaram: ele retoma sozinho.')

    return 0


if __name__ == '__main__':
    sys.exit(main())
