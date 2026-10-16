# Dragon Rage 0.4.0

A grande: **jogo online**, **movimento suave (60/120 imagens por segundo)**, seletor de **present mode**, **imagem que se ajusta à tela**, menu in-game refeito e vários extras. O motor agora está em dia com a versão mais recente do port de PC.

## Destaques

### Jogo online
- O modo online do port de PC agora roda no Android: **rollback netplay** com **código de sala**. Um jogador cria a sala e recebe um código; o outro entra com ele. Abra pela entrada **Dragon Net Battle** do menu principal.
- O pareamento usa o serviço do port de PC; quando dois celulares não se alcançam direto, é usado um relay.
- Foi preciso reescrever em arm64 o salvar/restaurar o estado do jogo (a versão de PC usava chamadas que o Android não tem).
- Os primeiros testes são Android ↔ Android. Android ↔ PC ainda não foi verificado.

### Movimento suave
- O jogo calcula a luta 30 vezes por segundo, como no PS2. Com **Movimento suave** ligado, são desenhadas imagens intermediárias (interpolação de quadros): **60** ou **120** por segundo. A luta em si, os replays e o online não mudam.
- Custa cerca do dobro de GPU, então é para GPUs fortes. É **experimental**: em cortes de câmera não há imagem intermediária.
- No app: **Ajustes > Desempenho**. No jogo: aba **Video** (lá também há 240).

![Ajustes > Desempenho](docs/images/v0.4-settings-performance.jpg)

### Present mode
- Novo seletor: **Automático**, **FIFO (VSync)**, **Mailbox**, **Immediate**. O Automático usa Mailbox onde o aparelho tiver.
- Escolha no app, ou no menu do jogo, onde vale **na hora** (sem reiniciar).

### Imagem que se ajusta à tela
- A imagem agora toma **o formato da sua tela** (novo padrão, "Tela"), sem achatar para 4:3: a luta mostra mais cenário dos lados em vez de esticar.
- Os formatos fixos continuam: 4:3, 16:10, 16:9, **20:9** (novo), 21:9, 32:9. O menu do jogo tem todos também.

### Menu do jogo
- Tem todas as opções do app: **Smooth motion**, **Present mode** e o **formato da tela** (com Tela e 20:9).
- **START + SELECT** abre e fecha o menu, em controle físico **e** no controle na tela (toque nos dois).
- **Quit to launcher**: sai do jogo e volta ao menu do app sem precisar fechar o app na mão.

### Controles
- Em **Controles** dá para escolher entre o **estilo do jogo** (0.3.0) e o **estilo clássico** (controle de console genérico). Cada um guarda suas posições.
- **Menu / luta automático**: o controle mostra os botões de menu nos menus e troca sozinho para os botões de luta quando a partida começa (e volta quando termina). O botão MENU / LUTA continua funcionando na mão, para o menu de pausa em uma luta por exemplo; dá para desligar em **Controles**.
- Os botões do modo menu não têm mais texto (OK, Voltar, …): só o símbolo, mais limpo.
- **Um botão nos clashes**: num clash de raios ou de combos, aperte **Quadrado** (soco) várias vezes em vez de girar o analógico. Vale nos controles na tela e em controle físico, vem ligado e dá para desligar em **Controles**.
- Novo visual dos botões na tela: translúcidos, com a ação desenhada em cada um.

![Layout de combate](docs/images/v0.4-touch-controls-combat.jpg)

### Launcher
- **Atalho Jogar**: em **Início** dá para pôr na tela inicial um ícone que abre o jogo direto. Segurando o ícone do app também aparece **Jogar**.
- A janela de atualização agora mostra o changelog formatado, em vez de texto puro.
- Antes, neste ciclo: download de pacotes de textura dentro do app, instalação de atualizações do GitHub sem sair do app e link do repositório em Sobre.

## Por dentro
- Em dia com o port de PC mais recente: movimento suave, online, atalhos, opção de filtro 2D e muitas correções.
- Fechar o jogo não derruba mais o processo do app na saída.
- A thread de vigilância e a checagem dos dados do disco ficam desligadas no Android por padrão (menos custo; o app já confere a imagem do disco ao instalar).

## Desempenho
- Aparelhos com Mali-G52 ficam limitados pela GPU em lutas pesadas (cerca de dois terços da velocidade). Não achamos nada seguro para mudar isso nesta versão; continua na lista.

## Limites conhecidos
- O online é novo: pode haver arestas. Se houver dessincronização, mande o log (**Ajustes > Salvar log**).
- O movimento suave é experimental e precisa de GPU forte.
- O menu dentro do jogo ainda está em inglês.

## Notas de atualização
- Instala por cima da versão anterior e mantém dados do jogo, ajustes e saves.
- O app ainda precisa da sua própria imagem de disco da versão USA (SLUS-21678); esta versão não distribui dados do jogo.
