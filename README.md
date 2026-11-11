# PrintScript Engine

Microservicio de Snippet Searcher que ejecuta, lintea, formatea y valida código PrintScript.
Lee los snippets y las reglas del Asset Service (bucket) a partir de una key y delega la lógica del
lenguaje en las librerías de PrintScript (`com.printscript:*`, publicadas en GitHub Packages).

No maneja permisos ni metadata: eso lo resuelve snippet-service antes de llamarlo.

Además de la API HTTP, consume por **Redis Streams** los pedidos de lint y format que publica
snippet-service cuando un usuario cambia sus reglas, y le responde por otro stream (ver
[Redis Streams](#redis-streams)).

## Stack

- Kotlin 2.2 + Spring Boot 3.5 (Java 21)
- Librerías de PrintScript 1.2.0 (lexer, parser, semantic, interpreter, linter, formatter)
- Spring Data Redis (Redis Streams) para los pedidos asíncronos de lint/format
- Tests: JUnit 5, MockMvc, Testcontainers (Redis), JaCoCo (mínimo 80 % de líneas) y detekt

## Endpoints

Todos son `POST` con body JSON. Las versiones soportadas son `1.0` y `1.1`.

| Endpoint | Body | Respuesta |
|---|---|---|
| `/printscript/interpret` | `{ snippetKey, version, inputs? }` | `{ output: [], errors: [] }` |
| `/printscript/lint` | `{ snippetKey, version, rulesKey? }` | `{ issues: [{ rule, severity, message, line, column }], errors: [] }` |
| `/printscript/format` | `{ snippetKey, version, rulesKey? }` | `{ snippetKey, formattedKey, errors: [] }` |
| `/printscript/validate` | `{ snippetKey, version }` | `{ valid, errors: [{ rule, message, line, column }] }` |
| `GET /actuator/health` | — | `{ "status": "UP" }` |

Cómo leer las respuestas:

- **Errores del código del usuario** (no compila, falla al ejecutar, no cumple reglas) vuelven con **200**
  dentro de `errors` o `issues`.
  - `lint`: sin `issues` el snippet cumple; con `issues` no cumple; con `errors` no compila.
  - `validate`: `rule` es `lexical`, `syntax` o `semantic`.
- **`format`** guarda el resultado en `{snippetKey}.formatted` y nunca pisa la original. Si el snippet no compila
  no escribe nada y `formattedKey` viene en `null`.
- **`interpret`**: corta a los 10 segundos y a las 10.000 líneas de salida. `readEnv` no tiene acceso a las
  variables de entorno del contenedor.
- **Sin `rulesKey`** se usan las reglas por defecto.

### Reglas

Las reglas son un JSON guardado en el bucket, referenciado por `rulesKey`.

- **Lint:** `identifier-format` (`camel-case` o `snake-case`), `mandatory-variable-or-literal-in-println`,
  `mandatory-variable-or-literal-in-readInput`.
- **Format:** `enforce-spacing-before-colon-in-declaration`, `enforce-spacing-after-colon-in-declaration`,
  `enforce-spacing-around-equals`, `enforce-no-spacing-around-equals`, `mandatory-single-space-separation`,
  `line-breaks-after-println`, `indent-inside-if`, `if-brace-same-line`, `if-brace-below-line`.

### Errores del servicio

Responden `{ "error": "CODIGO", "message": "..." }`.

| Status | Código | Cuándo |
|---|---|---|
| 400 | `INVALID_REQUEST` | Body inválido o sin un campo obligatorio |
| 400 | `UNSUPPORTED_VERSION` | Versión de PrintScript no soportada |
| 400 | `INVALID_LINT_CONFIG` / `INVALID_FORMAT_CONFIG` | JSON de reglas mal formado o con valores inválidos |
| 404 | `FILE_NOT_FOUND` | No existe la key en el bucket |
| 404 / 405 | `NOT_FOUND` / `METHOD_NOT_ALLOWED` | Ruta o método inexistente |
| 503 | `STORAGE_UNAVAILABLE` | El bucket no responde |
| 504 | `EXECUTION_TIMEOUT` | La ejecución superó los 10 segundos |
| 500 | `INTERNAL_ERROR` | Error inesperado (se loguea, no se exponen detalles) |

## Correr en local

Las librerías de PrintScript están en GitHub Packages, así que hace falta un token con `read:packages`.
Configuralo en `~/.gradle/gradle.properties`:

```properties
gpr.user=tu-usuario-de-github
gpr.key=tu-token
```

Tests, coverage y análisis estático (lo mismo que corre CI):

```bash
./gradlew check detekt test
```

detekt 1.23 no corre con Java 25. Si tu Gradle usa Java 25, corrélo con un JDK 21 (`JAVA_HOME=...`).
El reporte de coverage queda en `build/reports/jacoco/test/html/index.html`.

Con Docker (el token entra como secret de build y no queda en la imagen):

```bash
GITHUB_USER=tu-usuario GITHUB_TOKEN=tu-token docker compose up -d --build
curl http://localhost:8080/actuator/health
```

El compose levanta también el Asset Service (`asset_service`) y Azurite (el bucket emulado).
Puertos: engine en `ENGINE_HOST_PORT` (8080) y Asset Service en `ASSET_HOST_PORT` (8081, Swagger en
`/swagger-ui`). Para probar un snippet de punta a punta:

```bash
curl -X PUT --data-binary 'println("hola");' http://localhost:8081/v1/asset/snippets/hola.ps
curl -X POST -H 'Content-Type: application/json' \
  -d '{"snippetKey":"hola.ps","version":"1.1"}' http://localhost:8080/printscript/interpret
```

### Asset Service

`AssetServiceFileRepository` implementa `FileRepository` con `GET`/`PUT /v1/asset/{container}/{key}`.

| Variable / property | Default | Para qué |
|---|---|---|
| `ASSET_SERVICE_URL` (`asset.url`) | `http://localhost:8081` | URL del Asset Service |
| `ASSET_SERVICE_CONTAINER` o `ASSET_CONTAINER` (`asset.container`) | `snippets` | Container donde viven snippets y reglas |
| `asset.connect-timeout` / `asset.read-timeout` | `2s` / `10s` | Pasado eso, responde 503 |

Un 404 del Asset Service se devuelve como `FILE_NOT_FOUND`. Cualquier otro error (5xx, conexión rechazada o
timeout) se devuelve como `STORAGE_UNAVAILABLE`. Las keys no pueden tener `/`.

## Redis Streams

Cuando un usuario cambia sus reglas, snippet-service publica un pedido por cada snippet. El engine lo
procesa con la misma lógica que `/lint` y `/format` y publica la respuesta:

```text
snippet-service ──XADD──▶ lint-requests / format-requests ──▶ engine (grupo printscript-engine)
snippet-service ◀──────── lint-results  / format-results  ◀──XADD── engine
```

- **Contrato v1:** los mensajes son mapas planos `string → string`. Hay ejemplos y reglas en
  [`src/test/resources/contracts/`](src/test/resources/contracts/README.md); el mismo contrato tiene que
  estar en snippet-service.
- **Lint** responde `COMPLIANT` (sin issues), `NOT_COMPLIANT` (con issues, que van en `issues`) o `FAILED`
  (no compila, reglas inválidas, versión no soportada o snippet borrado).
- **Format** responde `FORMATTED` con el código en `formatted`, o `FAILED`. En este flujo el engine no
  escribe en el Asset Service: el formateado lo guarda snippet-service.
- **Ack manual:** se hace ack solo después de publicar la respuesta. Si el Asset Service está caído, el
  pedido queda pendiente y `PendingReclaimer` lo reintenta. Después de `streams.max-deliveries` entregas
  va a `{stream}-dlq` con un `reason`, y se responde `FAILED` para que el snippet no quede en `pending`.
  Si una respuesta sale dos veces, snippet-service la descarta por `requestId`.
- Redis no afecta a `/actuator/health`: si se cae, la API HTTP sigue funcionando y los listeners se
  reconectan solos.

| Variable / property | Default | Para qué |
|---|---|---|
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | En el Swarm es `redis:6379` |
| `spring.data.redis.password` | sin password | En el Swarm llega como Docker Secret (configtree) |
| `STREAMS_ENABLED` (`streams.enabled`) | `true` | `false` apaga listeners y reclaimer |
| `HOSTNAME` (`streams.consumer`) | `printscript-engine-local` | Nombre del consumer, único por réplica |
| `streams.max-deliveries` | `3` | Entregas antes de mandar el pedido a la DLQ |
| `streams.reclaim-idle` / `streams.reclaim-interval` | `60s` / `PT30S` | Cuándo un pedido sin ack se reintenta y cada cuánto se revisa |
| `streams.max-length` | `10000` | `MAXLEN ~` de los streams que escribe el engine |

Para probarlo a mano con el compose levantado:

```bash
curl -X PUT --data-binary 'let mi_variable: number = 5;' http://localhost:8081/v1/asset/snippets/s1
curl -X PUT --data-binary '{"identifier-format":"camel-case"}' http://localhost:8081/v1/asset/snippets/rules.u1.lint
docker compose exec redis redis-cli XADD lint-requests '*' schemaVersion 1 requestId r1 snippetId s1 \
  rulesKey rules.u1.lint language printscript version 1.1
docker compose exec redis redis-cli XRANGE lint-results - +
```

Los tests de integración (`RedisStreamsIntegrationTest`) levantan un Redis real con Testcontainers, así
que necesitan Docker corriendo.

## Estructura

```
src/main/kotlin/com/printscript/
├── controller/   endpoints REST, requests y manejo de errores
├── service/      orquesta: lee del bucket y llama al engine
├── domain/       engine de PrintScript, resultados, excepciones e interfaces
├── messaging/    Redis Streams: pedidos de lint/format, respuestas, reintentos y DLQ
└── repository/   FileRepository: acceso al bucket
```

## CI/CD

`.github/workflows/pipeline.yml` corre en PRs y pushes a `dev` y `prod`:

1. **check**: `./gradlew check detekt test` con Java 21.
2. **publish** (solo push): construye y publica `ghcr.io/printscript-ingsis-vlt/print-script-engine` con el
   workflow común.
3. **notify** (solo push): avisa a `snippet-searcher-infra` con el digest de la imagen.

Necesita los secrets `GPR_TOKEN` e `INFRA_DISPATCH_TOKEN` en el repo.

## Hooks de git

```bash
git config core.hooksPath .githooks
```

- `pre-commit` corre `./gradlew check`.
- `commit-msg` exige el formato `tipo(scope): descripción`. Tipos válidos: `feat`, `fix`, `docs`, `style`,
  `refactor`, `test`, `chore`, `build`.
