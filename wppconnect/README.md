# WPPConnect Server — Deploy no Portainer

Stack para subir o [WPPConnect Server](https://github.com/wppconnect-team/wppconnect-server)
em `wppconnect.wootchat.com.br` atrás do Traefik (rede `OrionNet`), pronto pra
ser consumido pelo Wootchat CRM.

## Como funciona

O wppconnect-server **não publica imagem Docker oficial** (nem no Docker Hub,
nem no GHCR), e a config dele é via `src/config.ts` (TypeScript) que é
compilado no build — não tem suporte nativo a env vars.

Esta stack contorna isso:
1. `Dockerfile` clona o repo do GitHub (na branch/tag definida em `WPP_REF`),
   instala dependências com yarn e roda `yarn build`.
2. `docker-entrypoint.sh` usa `sed` em `dist/config.js` na hora do start
   para substituir `secretKey`, `deviceName`, `host`, `port` pelos
   valores das envs.

## Como subir

1. **DNS:** aponta `wppconnect.wootchat.com.br` pro IP do host onde o Traefik roda.

2. **No Portainer → Stacks → Add stack:**
   - Modo **Repository**: aponta pro repositório `Juniorjcmj/wootchatcrm`
     e em "Compose path" coloca `wppconnect/docker-compose.yml`.
     ✅ Recomendado — o Portainer pega o Dockerfile + entrypoint junto.
   - Modo *Web editor* **não funciona** aqui (precisa do contexto do
     build local), use Repository.
   - Em **Environment variables**, cola o conteúdo do seu `.env`
     (baseado em `.env.example`). Itens mínimos:
     ```
     SECRET_KEY=...   # openssl rand -hex 32
     WPP_HOST=https://wppconnect.wootchat.com.br
     ```
   - Deploy.

3. **⚠ Primeira subida demora 3–5 minutos** — Portainer faz o `docker build`
   (clone + yarn install + yarn build). Acompanhe em Containers → wppconnect
   → Logs. Builds subsequentes usam cache.

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
Pra fixar uma versão, edite `WPP_REF` no `.env`:
```
WPP_REF=v2.10.0
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
  Containers → wppconnect → status `Created/Building`. Se ficar nesse
  estado por >10min, abre Stack → Logs (build logs) pra ver erros do yarn.
- **"failed to solve: error reading from server"** no build → falha de
  rede do Docker daemon ao clonar o GitHub. Verifica conectividade do host.
- **"Failed to launch the browser process"** no runtime → o `shm_size: 2gb`
  no compose resolve. Se persistir, sobe pra `4gb`.
- **`SECRET_KEY` aparece como `THISISMYSECURETOKEN` mesmo com env setado**
  → o entrypoint não rodou ou o sed falhou. Verifica logs do container
  pelas linhas `[wppconnect-entrypoint]`.
- **QR não aparece** → veja os logs. Pode ser que a SECRET_KEY enviada
  pelo CRM esteja diferente da do `.env` aqui.
- **Sessions perdidas após redeploy** → volumes não foram montados.
  Verifica `wpp_tokens` no Portainer → Volumes.
