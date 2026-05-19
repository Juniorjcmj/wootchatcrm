# ============================================================
# WPPConnect Server — build local + injeção de SECRET_KEY em runtime
#
# O projeto wppconnect-server não publica imagem Docker oficial e
# usa um config.ts (TypeScript) compilado em build. Aqui:
#   1. Clonamos a fonte do repo oficial (tag fixada por WPP_REF).
#   2. Buildamos (yarn build → dist/).
#   3. No entrypoint, fazemos sed em dist/config.js para sobrescrever
#      o SECRET_KEY default pelo valor do env, antes de subir o node.
# ============================================================

# ── Stage 1: build do wppconnect-server ─────────────────────
FROM node:22-alpine AS build

ARG WPP_REF=main
ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true

WORKDIR /usr/src/wpp-server

RUN apk add --no-cache \
        git vips vips-dev fftw-dev gcc g++ make libc6-compat pkgconfig python3 \
 && git clone --depth 1 --branch ${WPP_REF} https://github.com/wppconnect-team/wppconnect-server.git . \
 && corepack enable \
 && corepack prepare yarn@4.14.1 --activate \
 && yarn install --immutable \
 && yarn build

# ── Stage 2: runtime enxuto ─────────────────────────────────
FROM node:22-alpine

ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium-browser

WORKDIR /usr/src/wpp-server

# chromium é o engine usado pelo Puppeteer/WPPConnect; vips/fftw são deps de sharp.
# bash necessário pro entrypoint; tini gerencia sinais.
RUN apk add --no-cache chromium vips fftw bash tini ca-certificates ttf-freefont

# Copia tudo do build (node_modules, dist/, package.json, etc.)
COPY --from=build /usr/src/wpp-server /usr/src/wpp-server

# Entrypoint que injeta envs no config compilado
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 21465

ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/docker-entrypoint.sh"]
CMD ["node", "dist/server.js"]
