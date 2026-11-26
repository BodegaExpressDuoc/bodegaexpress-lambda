#!/bin/bash
# Crea o actualiza en AWS los recursos de las funciones Lambda de BodegaExpress:
#   1. Cola SQS de eventos de artículos (si no existe).
#   2. Lambda "alerta de stock bajo" (consumidor de la cola).
#   3. Lambda "autorizador JWT" (Lambda Authorizer del API Gateway).
#   4. Conexión cola -> Lambda de alerta (event source mapping).
# Es idempotente: se puede ejecutar muchas veces.
#
# Uso: bash scripts/desplegar.sh   (desde la raíz del repo, después de ./mvnw package)
# Variables requeridas: JWT_SECRET. Opcionales: AWS_REGION, UMBRAL_STOCK.
# Learner Lab no permite crear roles IAM: las funciones usan el rol existente LabRole.
set -euo pipefail

: "${JWT_SECRET:?Falta JWT_SECRET (el mismo secreto que usa ms-usuarios)}"
REGION="${AWS_REGION:-us-east-1}"
UMBRAL_STOCK="${UMBRAL_STOCK:-5}"
COLA="bodegaexpress-eventos-articulos"
FN_ALERTA="bodegaexpress-alerta-stock"
FN_AUTORIZADOR="bodegaexpress-autorizador-jwt"
JAR="target/bodegaexpress-lambda.jar"
RUNTIME="java17"

[ -f "$JAR" ] || { echo "No existe $JAR: ejecuta primero ./mvnw package"; exit 1; }

CUENTA=$(aws sts get-caller-identity --query Account --output text)
ROL="arn:aws:iam::${CUENTA}:role/LabRole"

echo "== Cola SQS $COLA"
# El visibility timeout debe ser mayor que el timeout de la Lambda (30 s)
COLA_URL=$(aws sqs create-queue --region "$REGION" --queue-name "$COLA" \
  --attributes VisibilityTimeout=60 --query QueueUrl --output text)
COLA_ARN=$(aws sqs get-queue-attributes --region "$REGION" --queue-url "$COLA_URL" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
echo "URL: $COLA_URL"

# Crea la función si no existe; si existe, actualiza el código y la configuración.
desplegar_funcion() {
  local nombre="$1" handler="$2" variables="$3"
  echo "== Lambda $nombre"
  if aws lambda get-function --region "$REGION" --function-name "$nombre" > /dev/null 2>&1; then
    aws lambda update-function-code --region "$REGION" --function-name "$nombre" \
      --zip-file "fileb://$JAR" --query LastModified --output text
    aws lambda wait function-updated --region "$REGION" --function-name "$nombre"
    aws lambda update-function-configuration --region "$REGION" --function-name "$nombre" \
      --handler "$handler" --runtime "$RUNTIME" --timeout 30 --memory-size 512 \
      --environment "$variables" --query LastModified --output text
    aws lambda wait function-updated --region "$REGION" --function-name "$nombre"
  else
    aws lambda create-function --region "$REGION" --function-name "$nombre" \
      --runtime "$RUNTIME" --handler "$handler" --role "$ROL" \
      --zip-file "fileb://$JAR" --timeout 30 --memory-size 512 \
      --environment "$variables" --query FunctionArn --output text
    aws lambda wait function-active-v2 --region "$REGION" --function-name "$nombre"
  fi
}

desplegar_funcion "$FN_ALERTA" "cl.duoc.bodegaexpress.lambda.ConsumidorArticulos::handleRequest" \
  "Variables={UMBRAL_STOCK=$UMBRAL_STOCK}"
desplegar_funcion "$FN_AUTORIZADOR" "cl.duoc.bodegaexpress.lambda.AutorizadorJwt::handleRequest" \
  "Variables={JWT_SECRET=$JWT_SECRET}"

echo "== Conexión $COLA -> $FN_ALERTA"
MAPEO=$(aws lambda list-event-source-mappings --region "$REGION" --function-name "$FN_ALERTA" \
  --event-source-arn "$COLA_ARN" --query 'EventSourceMappings[0].UUID' --output text)
if [ "$MAPEO" = "None" ] || [ -z "$MAPEO" ]; then
  aws lambda create-event-source-mapping --region "$REGION" --function-name "$FN_ALERTA" \
    --event-source-arn "$COLA_ARN" --batch-size 10 \
    --function-response-types ReportBatchItemFailures --query UUID --output text
else
  echo "Ya existe ($MAPEO)"
fi

echo "== Listo"
echo "Cola: $COLA_URL  (configúrala como SQS_COLA_URL de ms-articulos)"
