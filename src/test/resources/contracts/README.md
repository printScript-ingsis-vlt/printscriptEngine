# Contrato de mensajes (Redis Streams) v1

Ejemplos de los mensajes que se intercambian con snippet-service. **El mismo archivo tiene que
existir en los dos repos**: si se cambia acá, hay que cambiarlo también allá.

| Archivo | Stream | Productor → consumidor |
|---|---|---|
| `rule-request.v1.json` | `lint-requests`, `format-requests` | snippet-service → engine |
| `lint-result.v1.json` | `lint-results` | engine → snippet-service |
| `format-result.v1.json` | `format-results` | engine → snippet-service |

- Todos los valores son strings (Redis Streams guarda pares string → string). `issues` y `errors`
  son listas codificadas como JSON dentro del string.
- Las reglas de cada usuario viven en el container `snippets` con keys `rules.{userId}.lint` y
  `rules.{userId}.format` (el Asset Service no acepta `/` en las keys).
- `rulesKey` vacío o ausente: se usan las reglas por defecto. Si se manda una key que no existe en el
  Asset Service, la respuesta es `FAILED`.
- `status` de lint: `COMPLIANT` | `NOT_COMPLIANT` | `FAILED`. De format: `FORMATTED` | `FAILED`.
- Los pedidos que no se pueden procesar terminan en `{stream}-dlq` con un campo `reason`.
