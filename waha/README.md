# WAHA — Deploy no Portainer

Stack para subir o [WAHA](https://waha.devlike.pro) em `waha.wootchat.com.br`
atrás do Traefik (rede `OrionNet`), pronto pra ser consumido pelo Wootchat CRM.

## Como subir

1. **DNS:** aponta `waha.wootchat.com.br` pro IP do host onde o Traefik roda.

2. **No Portainer → Stacks → Add stack:**
   - Modo: *Repository* (puxa o `docker-compose.yml` deste diretório) ou *Web editor* (cola o conteúdo).
   - Em **Environment variables**, cola o conteúdo do seu `.env`
     (baseado em `.env.example`). Itens mínimos:
     ```
     WHATSAPP_API_KEY=...           # openssl rand -hex 24
     WAHA_DASHBOARD_PASSWORD=...    # senha forte
     ```
   - Deploy.

3. **Confirma que subiu:** abre `https://waha.wootchat.com.br` no navegador.
   Vai pedir Basic Auth (usuário/senha do dashboard). Depois aparece o
   Swagger UI do WAHA.

## Como conectar o CRM

No CRM, tela de **Conexões → Nova conexão → WAHA**:

| Campo            | Valor                                |
|------------------|--------------------------------------|
| Nome amigável    | qualquer (ex.: "WhatsApp Suporte")  |
| URL do WAHA      | `https://waha.wootchat.com.br`       |
| API Key          | mesmo valor de `WHATSAPP_API_KEY`    |
| Nome da sessão   | qualquer identificador (ex.: `suporte`) |

Ao criar, o CRM dispara `POST /api/sessions/start` no WAHA com webhook
apontando pra `https://api.wootchat.com.br/api/v1/webhooks/waha/{connectionId}`.
Aí é só clicar em **Conectar** e escanear o QR.

## Atualizar

Stacks → seu stack WAHA → **Pull and redeploy** (puxa nova versão da imagem
e recria o container mantendo os volumes `waha_sessions` e `waha_files`).

## Volumes (não apague)

- `waha_sessions` — sessões pareadas com o WhatsApp. Apagar = reescanear QR.
- `waha_files`    — uploads/downloads de mídia em trânsito.

## Engines

- **WEBJS** (padrão): whatsapp-web.js. Estável, suportado no Core.
- **NOWEB**: motor mais novo, multi-device nativo. Só Plus.

Troque com `WHATSAPP_DEFAULT_ENGINE=NOWEB` no `.env` se estiver no Plus.

## Plus vs Core

- **Core** (`devlikeapro/waha:latest`) — gratuito, suficiente pra texto +
  mídia básica + 1 sessão por instância.
- **Plus** (`devlikeapro/waha-plus:latest`) — paga, recursos avançados
  (channels, story, várias sessões na mesma instância, etc.). Precisa de
  license key — segue a doc do projeto WAHA.
