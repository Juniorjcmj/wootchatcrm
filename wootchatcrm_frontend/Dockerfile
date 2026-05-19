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

# Copia os assets estáticos do frontend
# (index.html + todos os .jsx/.js/.css)
COPY . /usr/share/nginx/html/

# Configuração customizada do nginx (SPA fallback + cache headers)
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Template do config.js — preenchido em runtime via envsubst
COPY config.template.js /etc/wootchat/config.template.js
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 80

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
