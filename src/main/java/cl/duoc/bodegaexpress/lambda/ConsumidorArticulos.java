package cl.duoc.bodegaexpress.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Consumidor de la cola SQS de eventos de artículos (productor: ms-articulos).
 * <p>
 * Requerimiento funcional: <b>alerta de stock bajo</b>. Cuando un artículo se crea o
 * actualiza con un stock menor que el umbral ({@code UMBRAL_STOCK}, por defecto 5),
 * registra una alerta en CloudWatch Logs. Las eliminaciones no generan alerta.
 * <p>
 * Los mensajes que no se pueden leer se informan como fallidos (ReportBatchItemFailures)
 * para que SQS los reintente, sin reprocesar el resto del lote.
 */
public class ConsumidorArticulos implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int umbral;

    /** Constructor que usa AWS Lambda: lee el umbral desde la variable de entorno. */
    public ConsumidorArticulos() {
        this(leerUmbral(System.getenv("UMBRAL_STOCK")));
    }

    public ConsumidorArticulos(int umbral) {
        this.umbral = umbral;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent evento, Context context) {
        List<SQSBatchResponse.BatchItemFailure> fallidos = new ArrayList<>();
        for (SQSEvent.SQSMessage mensaje : evento.getRecords()) {
            try {
                evaluar(mensaje.getBody()).ifPresent(System.out::println);
            } catch (Exception e) {
                System.out.println("Mensaje " + mensaje.getMessageId() + " no válido: " + e.getMessage());
                fallidos.add(new SQSBatchResponse.BatchItemFailure(mensaje.getMessageId()));
            }
        }
        return new SQSBatchResponse(fallidos);
    }

    /**
     * Evalúa un mensaje y devuelve el texto de la alerta si corresponde.
     *
     * @throws Exception si el mensaje no es un JSON con los campos esperados
     */
    Optional<String> evaluar(String cuerpo) throws Exception {
        JsonNode nodo = JSON.readTree(cuerpo);
        String operacion = texto(nodo, "operacion");
        long articuloId = nodo.required("articuloId").asLong();
        String nombre = texto(nodo, "nombre");
        int stock = nodo.required("stock").asInt();

        if ("ELIMINACION".equals(operacion) || stock >= umbral) {
            System.out.println("Sin alerta: " + operacion + " del artículo " + articuloId + " (stock " + stock + ")");
            return Optional.empty();
        }
        return Optional.of(String.format(
                "ALERTA STOCK BAJO: artículo %d \"%s\" quedó con stock %d (umbral %d) tras %s",
                articuloId, nombre, stock, umbral, operacion));
    }

    private static String texto(JsonNode nodo, String campo) {
        JsonNode valor = nodo.required(campo);
        if (!valor.isTextual()) {
            throw new IllegalArgumentException("El campo " + campo + " debe ser texto");
        }
        return valor.asText();
    }

    private static int leerUmbral(String valor) {
        if (valor == null || valor.isBlank()) {
            return 5;
        }
        return Integer.parseInt(valor.trim());
    }
}
