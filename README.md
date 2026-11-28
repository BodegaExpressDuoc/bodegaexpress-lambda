# bodegaexpress-lambda

Funciones **AWS Lambda en Java 17** (sin Spring) del sistema BodegaExpress. Un solo
proyecto Maven genera un jar con dos handlers:

| Función AWS | Handler | Qué hace |
|---|---|---|
| `bodegaexpress-alerta-stock` | `cl.duoc.bodegaexpress.lambda.ConsumidorArticulos::handleRequest` | Consume la cola SQS `bodegaexpress-eventos-articulos`. Si un artículo se crea o actualiza con stock menor que `UMBRAL_STOCK` (por defecto 5), registra `ALERTA STOCK BAJO` en CloudWatch Logs. |
| `bodegaexpress-autorizador-jwt` | `cl.duoc.bodegaexpress.lambda.AutorizadorJwt::handleRequest` | Lambda Authorizer del AWS API Gateway (HTTP API, payload 2.0, respuesta simple). Valida el header `Authorization: Bearer <token>` emitido por ms-usuarios. |

## Flujo

```
Postman ──► API Gateway ──(autorizador JWT)──► ms-articulos ──► SQS ──► Lambda alerta de stock ──► CloudWatch Logs
```

1. El cliente hace login en `POST /api/v1/auth/login` (ms-usuarios) y recibe un JWT.
2. Cada petición protegida pasa por el API Gateway, que invoca `bodegaexpress-autorizador-jwt`.
3. ms-articulos publica en SQS un evento al crear, actualizar o eliminar un artículo:
   ```json
   {"operacion":"ACTUALIZACION","articuloId":7,"nombre":"Caja","stock":2,"fecha":"2026-11-26T12:00:00Z"}
   ```
4. `bodegaexpress-alerta-stock` consume el evento y, si corresponde, registra la alerta.

## Por qué un Lambda Authorizer y no el autorizador JWT nativo

ms-usuarios firma los tokens con **HS256** (secreto compartido). El autorizador JWT
nativo de HTTP API solo valida tokens firmados con claves públicas publicadas por un
emisor OIDC (JWKS), así que se usa un Lambda Authorizer que valida el token con el
mismo `JWT_SECRET`.

## Variables de entorno

| Función | Variable | Descripción |
|---|---|---|
| autorizador | `JWT_SECRET` | Mismo secreto que ms-usuarios (mínimo 32 caracteres) |
| alerta de stock | `UMBRAL_STOCK` | Opcional, por defecto `5` |

## Compilar y probar

```powershell
.\mvnw.cmd verify      # Windows
```
```bash
./mvnw verify          # Linux / GitHub Actions
```

Genera `target/bodegaexpress-lambda.jar` con todas las dependencias (maven-shade-plugin).

## Despliegue (CI/CD)

El workflow `.github/workflows/ci-cd.yml`:

- en cada push y pull request: compila y ejecuta las pruebas;
- en push a `main`: ejecuta `scripts/desplegar.sh`, que de forma idempotente:
  1. crea la cola SQS `bodegaexpress-eventos-articulos` si no existe;
  2. crea o actualiza las dos funciones Lambda (runtime `java17`, rol `LabRole`);
  3. conecta la cola con la función de alerta (event source mapping con `ReportBatchItemFailures`).

Secrets requeridos en GitHub: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
`AWS_SESSION_TOKEN` (AWS Academy, cambian en cada sesión) y `JWT_SECRET`.

La rama `main` está protegida: solo cambia mediante pull request desde `develop`.

## Versiones

Cada entrega se marca con un tag anotado en `main`:

| Versión | Tag | Entrega | Contenido |
|---|---|---|---|
| 0.2.0 | `v0.2.0` | Evaluación Parcial 3 | Primera versión: autorizador JWT del API Gateway, alerta de stock bajo desde SQS y despliegue con `scripts/desplegar.sh` |

## Verificar en AWS

```bash
# Enviar un evento de prueba a la cola
aws sqs send-message --queue-url <URL-de-la-cola> \
  --message-body '{"operacion":"ACTUALIZACION","articuloId":1,"nombre":"Caja","stock":2,"fecha":"2026-11-26T12:00:00Z"}'

# Ver la alerta en los logs
aws logs tail /aws/lambda/bodegaexpress-alerta-stock --since 5m
```
