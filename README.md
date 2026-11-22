# BoasNotas ✍️

Caderno digital para **tablet Android com caneta** (feito pensando no Samsung Galaxy Tab com S Pen),
no estilo do GoodNotes.

## Baixar e instalar

Abra este link **no tablet**:

**https://github.com/izabelle02/BoasNotas/releases/latest/download/BoasNotas.apk**

1. Toque no arquivo baixado.
2. Se o Android avisar sobre "fontes desconhecidas", toque em **Configurações** e ative
   **Permitir desta fonte** para o navegador (só na primeira vez).
3. Toque em **Instalar**.

Cada vez que o código muda, o GitHub gera um APK novo nesse mesmo link. É só baixar e
instalar por cima: **os cadernos continuam lá**.

## O que ele faz

| | |
|---|---|
| **Cadernos** | Estante com capas coloridas e miniatura da 1ª página. Renomear, trocar a cor, apagar (toque longo). |
| **Papel** | Em branco, pautado, quadriculado ou pontilhado — dá para trocar por página. |
| **Caneta** | Traço suave que fica mais grosso quanto mais força você faz (pressão da S Pen). 8 cores, 3 espessuras. |
| **Marca-texto** | Transparente, 5 cores, 3 espessuras. |
| **Borracha** | Apaga só onde passa, ou o traço inteiro (escolha em Configurações). 3 tamanhos. |
| **Laço** | Circule o que escreveu para **mover**, **duplicar**, **mudar a cor** ou **apagar**. |
| **Botão da S Pen** | **Segure o botão = borracha**; soltou, volta para a caneta. (Ou: cada apertada alterna caneta ⇄ borracha.) |
| **Rejeição da palma** | Só a caneta escreve; a mão apoiada na tela é ignorada. Dedos rolam e dão zoom. |
| **Gestos** | Pinça para zoom, arrastar para rolar, **toque com 2 dedos desfaz**, com 3 refaz. |
| **Páginas** | Rolagem contínua, grade de miniaturas, nova página, duplicar, apagar. |
| **PDF** | Importe um PDF (botão no topo, ou "Compartilhar → BoasNotas" em qualquer app) e escreva por cima. |
| **Exportar** | Gera um PDF do caderno para compartilhar (WhatsApp, Drive, e-mail…) ou salvar no tablet. |
| **Texto** | Ferramenta **T**: toque na página e digite. Toque num texto para editar ou apagar. 3 tamanhos, 8 cores. |
| **Imagens** | Botão de imagem: escolha uma foto da galeria; ela entra selecionada para mover e redimensionar. |
| **Formas automáticas** | Desenhe e **segure a caneta parada** meio segundo: vira linha, círculo, retângulo, triângulo ou polígono perfeito. Ou ligue o botão de formas para todo traço virar forma. |
| **Redimensionar** | Com o laço, arraste a bolinha do canto da seleção para aumentar/diminuir (traços, textos e imagens). |
| **Busca na letra** | Lupa na tela inicial: procura nos títulos, nas caixas de texto e na sua **letra** (reconhecida no próprio tablet). |
| **Google Drive** | Salva sozinho cada caderno como **PDF com a sua letra** na pasta "BoasNotas" do Drive, mais uma cópia de segurança para restaurar em outro tablet. |
| **Salvamento** | Automático, enquanto você escreve. |

### Botão da S Pen

Em **⋮ → Configurações → Botão da S Pen**:

- **Segurar o botão = borracha** (padrão): enquanto o botão estiver apertado, a caneta apaga.
- **Apertar o botão alterna**: cada apertada troca entre caneta e borracha.
- **Não fazer nada**.

> Aperte o botão com a **ponta encostada na tela**. Se, com a caneta no ar, o botão abrir o menu
> "Comandos aéreos" da Samsung, você pode desligar isso em **Configurações do tablet → Recursos
> avançados → S Pen → Comandos aéreos**.

## Ligar o Google Drive (uma vez só)

O Google só deixa um app mexer no seu Drive se ele estiver registrado no **Google Cloud**.
É gratuito e leva uns 10 minutos. Dá para fazer no navegador do próprio tablet:

1. Entre em **https://console.cloud.google.com** com a sua conta Google e aceite os termos.
2. No topo, clique em **Selecionar projeto → Novo projeto**. Nome: `BoasNotas` → **Criar**.
   Confira se o projeto `BoasNotas` está selecionado no topo.
3. Menu ☰ → **APIs e serviços → Biblioteca** → procure **Google Drive API** → **Ativar**.
4. Menu ☰ → **APIs e serviços → Tela de permissão OAuth** (ou "Google Auth Platform") → **Começar**:
   - Nome do app: `BoasNotas`; e-mail de suporte: o seu.
   - Público: **Externo**. Dados de contato: o seu e-mail. Aceite e **Criar**.
   - Em **Público**, clique em **Publicar app** (o app só pede acesso aos arquivos que ele mesmo cria,
     então não precisa de verificação do Google).
5. Menu ☰ → **APIs e serviços → Credenciais → Criar credenciais → ID do cliente OAuth**:
   - Tipo de aplicativo: **Android**
   - Nome do pacote: `com.boasnotas`
   - Impressão digital do certificado SHA-1:
     `89:04:C6:C0:8C:C0:D6:F5:30:5F:8D:B6:92:64:62:8F:F0:AF:04:E3`
   - **Criar**.
6. No tablet, abra o BoasNotas → toque na **nuvem ☁** → **Conectar** → escolha a sua conta → **Permitir**.

Pronto: cada caderno vai para a pasta **BoasNotas** do Drive como PDF alguns segundos depois que você
sai dele (precisa de internet; sem internet, ele envia quando a conexão voltar). Na nuvem ☁ também
dá para **Salvar tudo agora** e **Restaurar do Drive** (traz para o tablet cadernos salvos de outro aparelho).

> Apagar um caderno no app **não** apaga os arquivos dele no Drive.

## Para quem for mexer no código

App Android nativo em **Kotlin**, sem bibliotecas de desenho de terceiros.

```
app/src/main/java/com/boasnotas/
├── model/      dados puros (traço, página, caderno), borracha/laço, desfazer, formato do arquivo
├── data/       onde os cadernos são salvos; configurações; Google Drive; busca na letra
├── render/     desenho do traço com pressão, papel, PDF (fundo e exportação)
└── ui/         telas: estante (LibraryActivity), caderno (EditorActivity), a folha (NoteCanvasView)
```

- `NoteCanvasView` é o coração: recebe os toques, separa caneta de dedo, trata o botão da S Pen
  (`BUTTON_STYLUS_PRIMARY`, ponta-borracha e os códigos antigos da Samsung 211–214) e usa
  previsão de movimento (`androidx.input:input-motionprediction`) para a tinta acompanhar a ponta.
- Os traços ficam em pontos de página (1/72 pol.), então o PDF exportado sai vetorial.
- Cada caderno fica em `files/notebooks/<id>/` (`meta.json`, `pages.bin`, `source.pdf`, `thumb.png`).

Montar no computador (precisa do Android SDK / Android Studio):

```bash
./gradlew testDebugUnitTest assembleDebug
```

O APK de cada versão é gerado pelo GitHub Actions (`.github/workflows/apk.yml`).

A chave `app/boasnotas.keystore` (senha pública `boasnotas`) existe só para que cada APK novo
instale por cima do anterior sem apagar os cadernos. Para publicar na Play Store, crie uma chave
própria e guarde-a em segredo.
