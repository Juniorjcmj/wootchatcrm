# WPPConnect Server — Deploy no Portainer

Stack para subir o [WPPConnect Server](https://github.com/wppconnect-team/wppconnect-server)
em `wppconnect.wootchat.com.br` atrás do Traefik (rede `OrionNet`), pronto pra
ser consumido pelo Wootchat CRM.

## Como subir

1. **DNS:** aponta `wppconnect.wootchat.com.br` pro IP do host onde o Traefik roda.

2. **No Portainer → Stacks → Add stack:**
   - Modo: *Repository* (puxa o `docker-compose.yml` deste diretório) ou *Web editor*.
   - Em **Environment variables**, cola o conteúdo do seu `.env`
     (baseado em `.env.example`). Itens mínimos:
     ```
     SECRET_KEY=...   # openssl rand -hex 32
     ```
   - Deploy.

3. **Confirma que subiu:** abre `https://wppconnect.wootchat.com.br/api-docs`
   no navegador — deve aparecer o Swagger UI.

## Como conectar o CRM

No CRM, tela de **Conexões → Nova conexão → WPPConnect**:

| Campo            | Valor                                       |
|------------------|---------------------------------------------|
| Nome amigável    | qualquer (ex.: "WhatsApp Pós-venda")        |
| URL              | `https://wppconnect.wootchat.com.br`        |
| SECRET_KEY       | mesmo valor do `.env` do wppconnect-server  |
| Nome da sessão   | qualquer identificador (ex.: `posvenda`)    |

Ao criar, o CRM:
1. Chama `POST /api/{session}/{SECRET_KEY}/generate-token` e guarda o Bearer.
2. Chama `POST /api/{session}/start-session` com webhook apontando pra
   `https://api.wootchat.com.br/api/v1/webhooks/wppconnect/{connectionId}`.
3. Clica em **Conectar** → o modal mostra o QR (vindo via webhook ou polling).

## Atualizar

Stacks → seu stack WPPConnect → **Pull and redeploy** (puxa nova versão da imagem
e recria o container preservando os volumes `wpp_tokens` e `wpp_userdata`).

## Volumes (não apague)

- `wpp_tokens`   — tokens de cada sessão. Apagar = todas as sessões precisam de novo QR.
- `wpp_userdata` — perfil do Chromium/Puppeteer (cookies, localStorage, etc.).

## Diferenças vs WAHA

| Aspecto             | WAHA                           | WPPConnect                         |
|---------------------|--------------------------------|------------------------------------|
| Auth                | X-Api-Key único                | SECRET_KEY → gera Bearer por sessão|
| Engine              | whatsapp-web.js                | WPPConnect (fork) ou Venom         |
| RAM mínima          | ~512MB                         | ~1.5GB (Chromium)                  |
| Plus/Pago?          | Sim (mais features)            | Tudo open-source                   |
| Multi-device nativo | Plus apenas                    | Sim, no padrão                     |

## Troubleshooting

- **"Failed to launch the browser process"** no log → o `shm_size: 2gb` no
  compose resolve. Se persistir, sobe pra `4gb`.
- **QR não aparece** → veja os logs do container. Pode ser que a SECRET_KEY
  enviada pelo CRM esteja diferente da do `.env` aqui.
- **Sessions perdidas após `Pull and redeploy`** → volumes não foram
  montados. Verifica se `wpp_tokens` está nos volumes do compose.
