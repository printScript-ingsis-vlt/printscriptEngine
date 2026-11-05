# PrintScript Engine

Microservicio de Snippet Searcher que ejecuta, lintea, formatea y valida código PrintScript.
Lee los snippets y las reglas del Asset Service (bucket) a partir de una key y delega la lógica del
lenguaje en las librerías de PrintScript (`com.printscript:*`, publicadas en GitHub Packages).

No maneja permisos ni metadata: eso lo resuelve snippet-service antes de llamarlo.

## Stack

- Kotlin 2.2 + Spring Boot 3.5 (Java 21)
- Librerías de PrintScript 1.2.0 (lexer, parser, semantic, interpreter, linter, formatter)
- Tests: JUnit 5, MockMvc, JaCoCo (mínimo 80 % de líneas) y detekt

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

El puerto se cambia con `ENGINE_HOST_PORT`.

## Estructura

```
src/main/kotlin/com/printscript/
├── controller/   endpoints REST, requests y manejo de errores
├── service/      orquesta: lee del bucket y llama al engine
├── domain/       engine de PrintScript, resultados, excepciones e interfaces
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

## Pendiente

- Implementación de `FileRepository` contra el Asset Service. Sin ella el servicio no levanta fuera de los tests.
