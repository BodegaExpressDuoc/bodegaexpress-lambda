package cl.duoc.bodegaexpress.lambda;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsumidorArticulosTests {

    private final ConsumidorArticulos consumidor = new ConsumidorArticulos(5);

    private static String evento(String operacion, int stock) {
        return "{\"operacion\":\"" + operacion + "\",\"articuloId\":7,\"nombre\":\"Caja\",\"stock\":" + stock
                + ",\"fecha\":\"2026-11-26T12:00:00Z\"}";
    }

    private static SQSEvent.SQSMessage mensaje(String id, String cuerpo) {
        SQSEvent.SQSMessage m = new SQSEvent.SQSMessage();
        m.setMessageId(id);
        m.setBody(cuerpo);
        return m;
    }

    @Test
    void alertaCuandoElStockQuedaBajoElUmbral() throws Exception {
        assertEquals("ALERTA STOCK BAJO: artículo 7 \"Caja\" quedó con stock 2 (umbral 5) tras ACTUALIZACION",
                consumidor.evaluar(evento("ACTUALIZACION", 2)).orElseThrow());
        assertTrue(consumidor.evaluar(evento("CREACION", 0)).isPresent());
    }

    @Test
    void sinAlertaConStockSuficienteOEliminacion() throws Exception {
        assertTrue(consumidor.evaluar(evento("ACTUALIZACION", 5)).isEmpty());
        assertTrue(consumidor.evaluar(evento("CREACION", 100)).isEmpty());
        assertTrue(consumidor.evaluar(evento("ELIMINACION", 1)).isEmpty());
    }

    @Test
    void informaSoloLosMensajesInvalidosComoFallidos() {
        SQSEvent lote = new SQSEvent();
        lote.setRecords(List.of(
                mensaje("ok-1", evento("ACTUALIZACION", 1)),
                mensaje("malo-1", "no es json"),
                mensaje("malo-2", "{\"operacion\":\"CREACION\",\"articuloId\":1}"),
                mensaje("ok-2", evento("CREACION", 50))));

        SQSBatchResponse respuesta = consumidor.handleRequest(lote, null);

        assertEquals(List.of("malo-1", "malo-2"), respuesta.getBatchItemFailures().stream()
                .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier).toList());
    }
}
