# ============================================================
# Wootchat CRM Frontend — React via Babel-standalone (sem bundler)
# Servido por nginx-alpine. config.js é gerado em runtime a partir
# das variáveis de ambiente, permitindo apontar para qualquer API.
# ============================================================

FROM nginx:1.27-alpine

# Tools usados pelo entrypoint
RUN apk add --no-cache gettext bash tzdata
ENV TZ=America/Sao_Paulo

# Limpa o default do nginx
RUN rm -rf /usr/share/nginx/html/*

# Copia TODO o build context para o html (assets + arquivos de config).
# Em seguida movemos os arquivos de config para os caminhos certos e
# limpamos os que não devem ser servidos pela web.
COPY . /usr/share/nginx/html/

RUN mkdir -p /etc/wootchat \
 && mv /usr/share/nginx/html/nginx.conf            /etc/nginx/conf.d/default.conf \
 && mv /usr/share/nginx/html/config.template.js    /etc/wootchat/config.template.js \
 && mv /usr/share/nginx/html/docker-entrypoint.sh  /usr/local/bin/docker-entrypoint.sh \
 && chmod +x /usr/local/bin/docker-entrypoint.sh \
 && rm -f /usr/share/nginx/html/Dockerfile \
          /usr/share/nginx/html/.dockerignore

EXPOSE 80

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
