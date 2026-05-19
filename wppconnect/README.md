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

3. **⚠ Primeira subida demora ~3–5 minutos** porque o Docker builda o
   wppconnect-server direto do GitHub (não tem imagem oficial publicada).
   Acompanhe em "Logs" — você vai ver o npm/yarn instalando dependências.
   Builds subsequentes usam cache e são rápidos.

4. **Confirma que subiu:** abre `https://wppconnect.wootchat.com.br/api-docs`
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

Stacks → seu stack WPPConnect → **Pull and redeploy**. Como o build é a
partir do GitHub, isso refaz o build pegando o último commit do branch.
Pra fixar uma versão, edite `WPP_BUILD_CONTEXT` no `.env` apontando pra
uma tag:
```
WPP_BUILD_CONTEXT=https://github.com/wppconnect-team/wppconnect-server.git#v2.8.6
```

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

- **"No log line matching the '' filter" no Portainer** → o container ainda
  não terminou de buildar. Primeiro deploy demora 3-5min. Confirma em
  Containers → wppconnect → status `Created/Building`.
- **Build falha com "context cannot be empty"** → seu Docker engine não
  suporta context de git remoto. Alternativa local:
  ```bash
  docker build -t wppconnect-local:latest \
    https://github.com/wppconnect-team/wppconnect-server.git#main
  ```
  Aí remove o bloco `build:` do compose e deixa só `image: wppconnect-local:latest`.
- **"Failed to launch the browser process"** no log → o `shm_size: 2gb` no
  compose resolve. Se persistir, sobe pra `4gb`.
- **QR não aparece** → veja os logs do container. Pode ser que a SECRET_KEY
  enviada pelo CRM esteja diferente da do `.env` aqui.
- **Sessions perdidas após `Pull and redeploy`** → volumes não foram
  montados. Verifica se `wpp_tokens` está nos volumes do compose.
