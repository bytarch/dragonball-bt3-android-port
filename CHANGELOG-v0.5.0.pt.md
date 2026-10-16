# Dragon Rage 0.5.0

Uma continuação da 0.4.0, feita a partir dos relatos do primeiro dia: **pacotes de textura HD em `.dds` agora funcionam
no celular**, um **renderizador OpenGL ES** escolhido sozinho para GPUs **Mali**, **acabou a tela preta ao voltar do
segundo plano**, e os logs agora dizem o que um relato precisa.

## Destaques

### Pacotes de textura HD (.dds) no celular
- As GPUs de celular (Adreno e Mali) não leem DXT (BC1–BC3), o formato dos `.dds` dos pacotes. Essas texturas saíam
  **brancas**, e o jogo relia os arquivos do armazenamento a cada quadro. Agora elas são descompactadas uma vez na CPU e
  aparecem como devem, no Vulkan e no OpenGL ES. Pacotes em `.png` já funcionavam.
- O cache de texturas respeita um **limite de memória** (768 MB), para pacotes HD grandes (10 GB ou mais) não lotarem a
  memória da GPU; texturas que ficam um tempo sem uso são liberadas e carregadas de novo quando precisa.
- **Importar um pacote** ficou mais robusto: o zip é reconhecido pelo conteúdo (não pelo nome), zips feitos no Windows
  com nomes acentuados são lidos, há mensagens claras para zips que o app não lê (Deflate64, LZMA, senha) e para zips
  sem nomes de textura do PCSX2, o pacote é descompactado direto no mesmo armazenamento (não ocupa o espaço duas vezes)
  e cada passo vai para o log.
- Dica: um pacote também pode ser copiado como pasta para `Android/data/com.dfdx047.dragonrage/files/textures/` (por
  exemplo com `adb push`); o app e o jogo o encontram lá. Pacotes que vêm com opções (fontes, layout de botões) pedem
  que elas sejam copiadas para a pasta `replacements` antes, como dizem as instruções deles.

### Renderizador OpenGL ES (experimental), automático na Mali
- O renderizador OpenGL do port de PC agora roda no Android como **OpenGL ES 3.2**.
- **Ajustes › Desempenho › Renderizador**: **Automático** (OpenGL ES nas GPUs Mali, Vulkan nas outras; a Adreno roda
  melhor em Vulkan), **Vulkan** ou **OpenGL ES**. O app identifica a GPU sozinho e mostra com qual renderizador o jogo vai
  abrir. O menu do jogo também tem a opção.
- Se o renderizador escolhido não abrir, o jogo abre com o outro.
- O OpenGL ES não tem: depth clamp (o que chega muito perto da câmera pode ser cortado) e os filtros de tela (FXAA,
  FSR, GSR). No present mode, FIFO liga o VSync e os outros desligam.

### Correções
- **Tela preta ao voltar para o jogo** depois de ir para o segundo plano: o jogo agora solta a superfície da tela quando o
  app sai e pega a nova quando ele volta.
- O launcher não pergunta mais ao engine, duas vezes por segundo, por uma função que não existe na prévia dos Controles
  (log cheio e trabalho à toa).

### Cache de texturas menor, menos engasgos
- Sem pacote de textura, o cache guarda 3.072 texturas no Android em vez de 8.192: menos memória da GPU, e a limpeza que
  roda quando ele enche é mais curta (antes era um engasgo de milhares de texturas de uma vez).

### Relatos e online
- O log agora traz, a cada poucos segundos, a **velocidade** do jogo (100% é a velocidade cheia), as imagens por segundo
  e para onde vai o tempo de um quadro. Com isso um relato de lentidão já diz qual é o gargalo.
- Online: erros, respostas do serviço de pareamento e o estado do relay agora vão para o log. Um arquivo `match_url.txt`
  (uma linha, `https://...`) na pasta do jogo pode apontar para outro serviço de pareamento sem precisar de nova build.

## Desempenho
- O que os logs mostraram: Snapdragon 8 Elite / 8s Gen 4 (Adreno 830 / 825) e Dimensity 8400 (Mali-G720) rodam na
  velocidade cheia, também em 2x e 3x. O Exynos 1380 (Mali-G68) roda as lutas a cerca de um quarto da velocidade em 2x:
  em GPUs dessa classe use **1x** e experimente o OpenGL ES (agora o padrão nelas).

## Notas de atualização
- Instala por cima da 0.4.0 e mantém dados do jogo, ajustes, saves e pacotes de textura.
- O app ainda precisa da sua própria imagem de disco da versão USA (SLUS-21678); esta versão não distribui dados do jogo.
