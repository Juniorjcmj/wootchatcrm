# Backend Architecture — CRM WhatsApp
## Stack: Java 21 + Spring Boot 3.x | Deploy: Portainer + GitHub Actions

---

## 1. STACK TECNOLÓGICA

| Camada | Tecnologia | Justificativa |
|---|---|---|
| Linguagem | Java 21 (LTS) | Virtual Threads (Project Loom) — ideal para I/O intenso de webhooks |
| Framework | Spring Boot 3.3 | Ecossistema maduro, autoconfiguration, Actuator |
| API REST | Spring Web (MVC) | Controllers, DTOs, validação |
| WebSocket | Spring WebSocket + STOMP | Chat em tempo real no multiatendimento |
| Segurança | Spring Security + JWT | Auth stateless, multi-tenant por tenant_id no token |
| ORM | Spring Data JPA + Hibernate | + QueryDSL para filtros complexos de leads |
| Banco principal | PostgreSQL 16 | Multi-tenant shared schema com tenant_id |
| Cache | Redis 7 | Sessões, rate limiting, filas de webhook, pub/sub |
| Fila de mensagens | Redis Streams ou RabbitMQ | Processar webhooks WhatsApp de forma assíncrona |
| Migrations | Flyway | Versionamento de schema |
| Build | Maven ou Gradle (Gradle recomendado) | Builds incrementais mais rápidos |
| Containerização | Docker + docker-compose | Deploy via Portainer stacks |
| CI/CD | GitHub Actions | Build → Test → Push image → Deploy via Portainer webhook |
| Monitoramento | Spring Actuator + Prometheus + Grafana | Métricas de filas, webhooks, latência |
| Logs | Logback + Loki (opcional) | Structured logging em JSON |

---

## 2. ESTRUTURA DE MÓDULOS (Monorepo modular)

```
crm-backend/
├── crm-core/               # Domínio principal (entidades, repositórios, serviços)
├── crm-api/                # Controllers REST + WebSocket
├── crm-whatsapp/           # Adaptadores dos providers WhatsApp
├── crm-automation/         # Engine de automações
├── crm-analytics/          # Relatórios e BI
├── crm-notifications/      # Notificações internas (sino) + e-mail
└── crm-infra/              # Config, segurança, multi-tenant, Docker
```

> Alternativa mais simples para MVP: **monólito modular por pacotes** dentro de um único projeto Spring Boot. Migra para módulos separados quando o time crescer.

---

## 3. ESTRUTURA DE PACOTES (dentro de crm-core / MVP monolítico)

```
com.seucrm/
├── config/
│   ├── SecurityConfig.java
│   ├── WebSocketConfig.java
│   ├── RedisConfig.java
│   ├── TenantContext.java          # ThreadLocal com tenant_id atual
│   └── MultiTenantFilter.java      # Extrai tenant do JWT e seta contexto
│
├── domain/
│   ├── tenant/
│   │   ├── Tenant.java             # Empresa/workspace
│   │   └── TenantRepository.java
│   ├── user/
│   │   ├── User.java               # Membro da equipe
│   │   ├── UserRole.java           # ADMIN, PIPELINE_ADMIN, AGENT, SUPERVISOR
│   │   └── UserRepository.java
│   ├── lead/
│   │   ├── Lead.java
│   │   ├── LeadTag.java
│   │   ├── LeadNote.java
│   │   ├── LeadActivity.java
│   │   └── LeadRepository.java
│   ├── pipeline/
│   │   ├── Pipeline.java
│   │   ├── PipelineStage.java
│   │   ├── Deal.java               # Negócio vinculado ao lead
│   │   ├── DealLostReason.java
│   │   └── PipelineRepository.java
│   ├── conversation/
│   │   ├── Conversation.java       # Thread de atendimento
│   │   ├── Message.java            # Mensagem individual
│   │   ├── ConversationStatus.java # PENDING, OPEN, BOT, FINISHED, FAILED
│   │   ├── MessageType.java        # TEXT, AUDIO, IMAGE, DOCUMENT, STICKER
│   │   └── ConversationRepository.java
│   ├── connection/
│   │   ├── WhatsAppConnection.java # Conexão configurada (Z-API, WAHA etc.)
│   │   ├── ConnectionProvider.java # Enum: ZAPI, WAHA, META_BSP, EVOLUTION
│   │   └── ConnectionRepository.java
│   └── automation/
│       ├── Automation.java
│       ├── AutomationTrigger.java  # STAGE_ENTERED, MESSAGE_RECEIVED, NO_REPLY
│       ├── AutomationAction.java   # SEND_MESSAGE, MOVE_STAGE, ASSIGN_AGENT
│       └── AutomationRepository.java
│
├── api/
│   ├── auth/
│   │   ├── AuthController.java     # POST /auth/login, /auth/refresh
│   │   └── AuthService.java
│   ├── lead/
│   │   ├── LeadController.java     # CRUD + filtros + bulk actions
│   │   └── LeadService.java
│   ├── pipeline/
│   │   ├── PipelineController.java
│   │   ├── DealController.java
│   │   └── PipelineService.java
│   ├── conversation/
│   │   ├── ConversationController.java
│   │   ├── MessageController.java
│   │   └── ConversationService.java
│   ├── webhook/
│   │   ├── ZApiWebhookController.java      # POST /webhooks/zapi/{connectionId}
│   │   ├── WahaWebhookController.java      # POST /webhooks/waha/{connectionId}
│   │   ├── MetaBspWebhookController.java   # POST /webhooks/meta/{connectionId}
│   │   ├── EvolutionWebhookController.java # POST /webhooks/evolution/{connectionId}
│   │   └── WebhookDispatcher.java          # Normaliza payload → evento interno
│   ├── automation/
│   │   ├── AutomationController.java
│   │   └── AutomationEngine.java
│   └── analytics/
│       └── AnalyticsController.java
│
├── websocket/
│   ├── ChatWebSocketController.java   # /app/send, /topic/conversation/{id}
│   ├── NotificationWebSocketController.java
│   └── WebSocketSessionManager.java
│
├── integration/
│   ├── whatsapp/
│   │   ├── WhatsAppGateway.java        # Interface comum para todos os providers
│   │   ├── ZApiAdapter.java
│   │   ├── WahaAdapter.java
│   │   ├── MetaBspAdapter.java
│   │   └── EvolutionAdapter.java
│   └── WhatsAppMessage.java            # DTO normalizado (provider-agnóstico)
│
└── shared/
    ├── dto/                            # Request/Response DTOs
    ├── exception/                      # GlobalExceptionHandler
    ├── pagination/                     # PageRequest padronizado
    └── audit/                          # CreatedAt, UpdatedAt, CreatedBy
```

---

## 4. MODELO DE DADOS PRINCIPAL (PostgreSQL)

```sql
-- MULTI-TENANT BASE
tenants (id, name, slug, plan, created_at)
users   (id, tenant_id, name, email, password_hash, role, avatar_url, active)

-- LEADS
leads (
  id, tenant_id, name, phone, email,
  document, origin, channel,           -- origin: ad/site/referral; channel: WHATSAPP/INSTAGRAM
  assigned_to (FK users),
  score, temperature,                  -- para IA futura
  created_at, updated_at
)
lead_tags        (lead_id, tag_id)
tags             (id, tenant_id, name, color)
lead_notes       (id, lead_id, user_id, content, created_at)
lead_activities  (id, lead_id, user_id, type, title, due_at, done_at)

-- PIPELINE
pipelines        (id, tenant_id, name, is_default)
pipeline_stages  (id, pipeline_id, name, order_index, color, sla_hours)
deals (
  id, tenant_id, lead_id, pipeline_id, stage_id,
  title, value, currency,
  assigned_to (FK users),
  status,                              -- OPEN, WON, LOST
  lost_reason_id,
  entered_stage_at,                    -- para calcular tempo na etapa
  created_at, updated_at
)
deal_lost_reasons (id, tenant_id, name)

-- CONVERSAS / ATENDIMENTO
whatsapp_connections (
  id, tenant_id, name, provider,       -- ZAPI/WAHA/META_BSP/EVOLUTION
  credentials_json,                    -- encrypted
  phone_number, active
)
conversations (
  id, tenant_id, lead_id,
  connection_id (FK whatsapp_connections),
  channel,                             -- WHATSAPP/INSTAGRAM/FACEBOOK
  status,                              -- PENDING/OPEN/BOT/FINISHED/FAILED
  assigned_to (FK users),
  last_message_at,
  unread_count,
  created_at, updated_at
)
messages (
  id, conversation_id, tenant_id,
  direction,                           -- INBOUND/OUTBOUND
  sender_type,                         -- LEAD/AGENT/BOT
  sender_id,
  type,                                -- TEXT/AUDIO/IMAGE/DOCUMENT/STICKER/LOCATION
  content,                             -- texto ou caption
  media_url, media_mime,
  external_id,                         -- ID da mensagem no provider
  status,                              -- SENT/DELIVERED/READ/FAILED
  created_at
)

-- AUTOMAÇÕES
automations (
  id, tenant_id, name, description,
  trigger_type, trigger_config_json,
  active, created_at
)
automation_conditions (id, automation_id, field, operator, value)
automation_actions    (id, automation_id, order_index, type, config_json)

-- ANALYTICS (tabelas de agregação)
pipeline_snapshots (id, tenant_id, pipeline_id, stage_id, deal_count, total_value, snapshot_date)
conversation_metrics (id, tenant_id, connection_id, date, total, avg_response_time_s, finished_count)
```

---

## 5. ENDPOINTS REST PRINCIPAIS

```
# AUTH
POST   /api/v1/auth/login
POST   /api/v1/auth/refresh
POST   /api/v1/auth/logout

# LEADS
GET    /api/v1/leads                    # Lista com filtros (tag, pipeline, atendente, origem, período)
POST   /api/v1/leads
GET    /api/v1/leads/{id}
PUT    /api/v1/leads/{id}
DELETE /api/v1/leads/{id}
POST   /api/v1/leads/bulk               # Ações em massa (atribuir, adicionar tag, exportar)
GET    /api/v1/leads/{id}/timeline      # Linha do tempo completa

# PIPELINE
GET    /api/v1/pipelines
POST   /api/v1/pipelines
GET    /api/v1/pipelines/{id}/board     # Retorna todas as etapas + deals agrupados
POST   /api/v1/pipelines/{id}/stages
PUT    /api/v1/deals/{id}/move          # Mover deal entre etapas (drag & drop)
POST   /api/v1/deals/{id}/won
POST   /api/v1/deals/{id}/lost

# CONVERSAS / MULTIATENDIMENTO
GET    /api/v1/conversations            # Lista com filtros (status, conexão, atendente)
GET    /api/v1/conversations/{id}/messages
POST   /api/v1/conversations/{id}/messages    # Enviar mensagem
PUT    /api/v1/conversations/{id}/assign      # Atribuir atendente
PUT    /api/v1/conversations/{id}/status      # Mudar status
POST   /api/v1/conversations/{id}/finish

# WEBHOOKS (recebimento dos providers)
POST   /api/v1/webhooks/zapi/{connectionId}
POST   /api/v1/webhooks/waha/{connectionId}
POST   /api/v1/webhooks/meta/{connectionId}
POST   /api/v1/webhooks/evolution/{connectionId}

# AUTOMAÇÕES
GET    /api/v1/automations
POST   /api/v1/automations
PUT    /api/v1/automations/{id}
POST   /api/v1/automations/{id}/toggle

# ANALYTICS
GET    /api/v1/analytics/pipeline/{id}        # Funil com conversão por etapa
GET    /api/v1/analytics/conversations        # Métricas de atendimento
GET    /api/v1/analytics/agents               # Performance por atendente
```

---

## 6. WEBSOCKET — FLUXO DO CHAT EM TEMPO REAL

```
Cliente conecta em: ws://api.seucrm.com/ws
Headers: Authorization: Bearer {jwt}

Subscrições do cliente:
  /user/queue/notifications          ← notificações pessoais (novo lead atribuído etc.)
  /topic/conversation/{id}           ← mensagens da conversa aberta
  /topic/conversations/updates       ← atualizações de status (badge de não lido)

Envio do cliente:
  /app/conversation/{id}/send        → envia mensagem pelo provider
  /app/conversation/{id}/typing      → indicador "digitando..."

Fluxo de mensagem inbound (webhook → WebSocket):
  Provider → POST /webhooks/zapi/{id}
           → WebhookDispatcher normaliza payload
           → Publica em Redis Stream "messages:inbound"
           → MessageConsumer processa (salva no banco, atualiza conversa)
           → SimpMessagingTemplate.convertAndSend("/topic/conversation/{id}", messageDto)
           → Frontend recebe em tempo real
```

---

## 7. ADAPTADORES WHATSAPP — INTERFACE COMUM

```java
public interface WhatsAppGateway {
    // Envio
    SendResult sendText(String connectionId, String to, String text);
    SendResult sendMedia(String connectionId, String to, MediaMessage media);
    SendResult sendTemplate(String connectionId, String to, String templateName, List<String> params);

    // Gestão
    ConnectionStatus getStatus(String connectionId);
    QrCode getQrCode(String connectionId);   // para WAHA/Evolution
    void disconnect(String connectionId);
}

// Cada provider implementa essa interface:
// ZApiAdapter, WahaAdapter, MetaBspAdapter, EvolutionAdapter

// Factory para instanciar o correto:
@Service
public class WhatsAppGatewayFactory {
    public WhatsAppGateway getGateway(ConnectionProvider provider) {
        return switch (provider) {
            case ZAPI      -> zapiAdapter;
            case WAHA      -> wahaAdapter;
            case META_BSP  -> metaBspAdapter;
            case EVOLUTION -> evolutionAdapter;
        };
    }
}
```

---

## 8. PIPELINE DE WEBHOOKS (processamento assíncrono)

```
[Provider HTTP POST]
       ↓
[WebhookController]          ← valida HMAC/token do provider
       ↓
[Redis Stream: webhooks:raw] ← persiste imediatamente (não perde evento)
       ↓
[WebhookConsumer Worker]     ← processa em background (Virtual Thread / @Async)
       ↓
[WebhookNormalizer]          ← transforma payload específico → InboundMessageEvent
       ↓
[ConversationService]        ← cria/atualiza conversa + mensagem no banco
       ↓
[AutomationEngine]           ← dispara automações se houver gatilhos
       ↓
[WebSocket Broadcast]        ← envia para frontend em tempo real
```

---

## 9. MULTI-TENANT

Estratégia: **Shared Database, Shared Schema** com `tenant_id` em todas as tabelas.

```java
// TenantContext — ThreadLocal
public class TenantContext {
    private static final ThreadLocal<String> current = new ThreadLocal<>();
    public static void set(String tenantId) { current.set(tenantId); }
    public static String get() { return current.get(); }
    public static void clear() { current.remove(); }
}

// Filter que extrai tenant do JWT e seta no contexto
public class MultiTenantFilter extends OncePerRequestFilter {
    protected void doFilterInternal(request, response, chain) {
        String tenantId = jwtService.extractTenantId(token);
        TenantContext.set(tenantId);
        try { chain.doFilter(request, response); }
        finally { TenantContext.clear(); }
    }
}

// Repository base que sempre filtra por tenant
@Query("SELECT e FROM #{#entityName} e WHERE e.tenantId = :#{T(TenantContext).get()}")
```

---

## 10. SEGURANÇA — ROLES E PERMISSÕES

```
ROLE_ADMIN                 → acesso total
ROLE_PIPELINE_ADMIN        → gerencia pipelines e deals
ROLE_PIPELINE_MEMBER       → visualiza e move deals
ROLE_AUTOMATION_ADMIN      → cria/edita automações
ROLE_LEADS_ADMIN           → gerencia todos os leads
ROLE_LEADS_MEMBER          → vê apenas leads atribuídos a si
ROLE_SUPERVISOR            → vê todas as conversas, relatórios
ROLE_AGENT                 → vê apenas conversas atribuídas a si
```

Implementar com Spring Security `@PreAuthorize` + Method Security.

---

## 11. CI/CD — GITHUB ACTIONS → PORTAINER

```yaml
# .github/workflows/deploy.yml
name: Build and Deploy

on:
  push:
    branches: [main]

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Build with Maven
        run: mvn clean package -DskipTests

      - name: Build Docker image
        run: docker build -t ghcr.io/${{ github.repository }}/crm-backend:${{ github.sha }} .

      - name: Push to GitHub Container Registry
        run: |
          echo ${{ secrets.GITHUB_TOKEN }} | docker login ghcr.io -u ${{ github.actor }} --password-stdin
          docker push ghcr.io/${{ github.repository }}/crm-backend:${{ github.sha }}

      - name: Deploy via Portainer Webhook
        run: |
          curl -X POST "${{ secrets.PORTAINER_WEBHOOK_URL }}" \
            -H "Content-Type: application/json" \
            -d '{"image": "ghcr.io/${{ github.repository }}/crm-backend:${{ github.sha }}"}'
```

```dockerfile
# Dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseVirtualThreads", "-jar", "app.jar"]
```

---

## 12. DOCKER COMPOSE (Portainer Stack)

```yaml
version: '3.9'
services:
  backend:
    image: ghcr.io/seu-usuario/crm-backend:latest
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/crmdb
      SPRING_DATASOURCE_USERNAME: ${DB_USER}
      SPRING_DATASOURCE_PASSWORD: ${DB_PASS}
      SPRING_REDIS_HOST: redis
      JWT_SECRET: ${JWT_SECRET}
      ALLOWED_ORIGINS: ${FRONTEND_URL}
    depends_on:
      - postgres
      - redis
    networks:
      - crm-net

  postgres:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: crmdb
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASS}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    networks:
      - crm-net

  redis:
    image: redis:7-alpine
    restart: unless-stopped
    command: redis-server --appendonly yes
    volumes:
      - redis_data:/data
    networks:
      - crm-net

volumes:
  postgres_data:
  redis_data:

networks:
  crm-net:
    driver: bridge
```

---

## 13. ORDEM DE DESENVOLVIMENTO RECOMENDADA

### Sprint 1 — Fundação (2 semanas)
- [ ] Setup projeto Spring Boot 3 + estrutura de pacotes
- [ ] Flyway migrations — tabelas base (tenants, users, leads, pipelines)
- [ ] Auth JWT com multi-tenant
- [ ] CRUD de Leads com filtros
- [ ] CRUD de Pipeline + Stages + Deals (incluindo move)
- [ ] Dockerfile + docker-compose + GitHub Actions básico

### Sprint 2 — Conversas (2 semanas)
- [ ] Modelo de Conversation + Message
- [ ] Adaptador Evolution API (mais simples para começar)
- [ ] Webhook receiver + normalizer + Redis Stream
- [ ] WebSocket com STOMP (broadcast de mensagens)
- [ ] Endpoint de envio de mensagem

### Sprint 3 — Multiatendimento (1 semana)
- [ ] Filtros de conversa (status, conexão, atendente)
- [ ] Assign/unassign de atendente
- [ ] Mudança de status (PENDING → OPEN → FINISHED)
- [ ] Adaptador Z-API
- [ ] Adaptador WAHA

### Sprint 4 — Automações básicas (1 semana)
- [ ] Engine de automação (trigger: stage_entered, no_reply_N_hours)
- [ ] Ação: enviar mensagem WhatsApp
- [ ] Ação: mover etapa
- [ ] Ação: atribuir atendente

### Sprint 5 — Analytics + Meta BSP (1 semana)
- [ ] Endpoints de analytics (funil, atendimento, agentes)
- [ ] Adaptador Meta BSP (API oficial)
- [ ] Métricas de SLA e tempo por etapa

---

## 14. DEPENDÊNCIAS MAVEN PRINCIPAIS

```xml
<dependencies>
  <!-- Spring -->
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-websocket</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>

  <!-- JWT -->
  <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-api</artifactId><version>0.12.5</version></dependency>
  <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-impl</artifactId><version>0.12.5</version></dependency>

  <!-- Database -->
  <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>
  <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>

  <!-- QueryDSL (filtros avançados) -->
  <dependency><groupId>com.querydsl</groupId><artifactId>querydsl-jpa</artifactId></dependency>

  <!-- Utilities -->
  <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></dependency>
  <dependency><groupId>org.mapstruct</groupId><artifactId>mapstruct</artifactId><version>1.5.5.Final</version></dependency>

  <!-- HTTP Client (para chamar APIs dos providers) -->
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webflux</artifactId></dependency>

  <!-- Tests -->
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
</dependencies>
```

---

## 15. ESTIMATIVA DE RECURSOS (VPS mínima para produção)

| Recurso | Mínimo | Recomendado |
|---|---|---|
| CPU | 2 vCPU | 4 vCPU |
| RAM | 4 GB | 8 GB |
| Disco | 40 GB SSD | 80 GB SSD |
| Breakdown | Backend 500MB + Postgres 512MB + Redis 256MB + SO 1GB | + margem para crescimento |

Contabo VPS S (4 vCPU / 8 GB / 200 GB) — ~€5/mês — suficiente para até ~500 empresas no plano compartilhado.
