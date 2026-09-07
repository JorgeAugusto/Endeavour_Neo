"""Confronta a auditoria I com a auditoria II, por arquivo e por linha.

A pergunta da fase 3 nao e "quantos achados" -- e **o que uma viu e a outra
nao**, que e a unica coisa que duas passadas independentes podem dizer e uma
sozinha nao pode.

Tres respostas interessam, e nesta ordem:

  SO NA I    ou foi corrigido entre as duas (bom), ou a II passou por cima
             (a II falhou naquele ponto)
  SO NA II   ou e codigo novo desta noite (esperado), ou a I passou por cima
  NAS DUAS   sobrevive a duas leituras independentes: e o mais confiavel que
             este processo produz

Uso:

    python docs/auditoria/cruzar.py
"""
import os
import re
import sys

AQUI = os.path.dirname(os.path.abspath(__file__))

# "- **A1-4** `TickPath.java:44-46` -- titulo" e tambem os cabecalhos
# "### B1-4. titulo" seguidos de uma linha "`arquivo:linha`".
LINHA_INDICE = re.compile(r'^-\s+\*\*([A-Z]\d+[a-z]?-\d+)\*\*\s+`([^`]+)`\s+—\s+(.*)$')
CABECALHO = re.compile(r'^###\s+([A-Z]\d+[a-z]?-\d+)[.\s]\s*(.*)$')
REFERENCIA = re.compile(r'^`([^`]+)`\s*$')


def arquivo_de(referencia):
    """@return so o nome do arquivo, sem caminho nem linha."""
    corte = referencia.split(':')[0].strip()
    corte = corte.replace('\\', '/').rsplit('/', 1)[-1]

    return corte


def achados_do_indice(caminho):
    """Le 00-achados.md, que e o indice da auditoria I."""
    encontrados = {}

    if not os.path.exists(caminho):
        return encontrados

    with open(caminho, encoding='utf-8') as arquivo:
        gravidade = '?'

        for linha in arquivo:
            if linha.startswith('## '):
                gravidade = linha[3:].split('(')[0].strip()

                continue

            achado = LINHA_INDICE.match(linha.rstrip())

            if achado:
                ident, referencia, titulo = achado.groups()
                encontrados[ident] = (arquivo_de(referencia), gravidade, titulo)

    return encontrados


def achados_dos_relatorios(pasta):
    """Le os relatorios de area, que e o formato da auditoria II."""
    encontrados = {}

    if not os.path.isdir(pasta):
        return encontrados

    for nome in sorted(os.listdir(pasta)):
        if not nome.endswith('.md') or nome.startswith('00-'):
            continue

        with open(os.path.join(pasta, nome), encoding='utf-8') as arquivo:
            linhas = arquivo.read().splitlines()

        gravidade = '?'

        for at, linha in enumerate(linhas):
            if linha.startswith('## '):
                texto = linha[3:].upper()

                for nivel in ('ALTA', 'MÉDIA', 'MEDIA', 'BAIXA'):
                    if nivel in texto:
                        gravidade = 'MÉDIA' if nivel == 'MEDIA' else nivel

                continue

            achado = CABECALHO.match(linha.rstrip())

            if not achado:
                continue

            ident, titulo = achado.groups()
            referencia = ''

            for adiante in linhas[at + 1:at + 6]:
                encontrado = REFERENCIA.match(adiante.strip())

                if encontrado:
                    referencia = encontrado.group(1)

                    break

            encontrados[ident] = (arquivo_de(referencia), gravidade, titulo)

    return encontrados


def main():
    uma = achados_do_indice(os.path.join(AQUI, '00-achados.md'))
    duas = achados_dos_relatorios(os.path.join(AQUI, 'ii'))

    if not duas:
        print('A auditoria II ainda nao escreveu relatorio nenhum em', AQUI + '/ii')

        return 1

    print('auditoria I : %d achados' % len(uma))
    print('auditoria II: %d achados' % len(duas))
    print()

    por_arquivo_um = {}
    por_arquivo_dois = {}

    for ident, (nome, gravidade, titulo) in uma.items():
        por_arquivo_um.setdefault(nome, []).append((ident, gravidade, titulo))

    for ident, (nome, gravidade, titulo) in duas.items():
        por_arquivo_dois.setdefault(nome, []).append((ident, gravidade, titulo))

    todos = sorted(set(por_arquivo_um) | set(por_arquivo_dois))

    print('%-28s %8s %8s' % ('arquivo', 'I', 'II'))
    print('-' * 48)

    for nome in todos:
        print('%-28s %8d %8d' % (nome, len(por_arquivo_um.get(nome, [])),
                                 len(por_arquivo_dois.get(nome, []))))

    print()
    print('=== arquivos que SO a I aponta ===')
    print('  (corrigido entre as duas, ou a II passou por cima)')

    for nome in todos:
        if nome not in por_arquivo_dois:
            for ident, gravidade, titulo in por_arquivo_um[nome]:
                print('  %-6s %-8s %-24s %s' % (ident, gravidade, nome, titulo[:70]))

    print()
    print('=== arquivos que SO a II aponta ===')
    print('  (codigo novo desta noite, ou a I passou por cima)')

    for nome in todos:
        if nome not in por_arquivo_um:
            for ident, gravidade, titulo in por_arquivo_dois[nome]:
                print('  %-6s %-8s %-24s %s' % (ident, gravidade, nome, titulo[:70]))

    return 0


if __name__ == '__main__':
    sys.exit(main())
