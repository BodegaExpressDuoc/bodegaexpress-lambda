package cl.duoc.bodegaexpress.lambda;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AutorizadorJwtTests {

    private static final String SECRETO = "secreto-de-prueba-de-al-menos-32-caracteres-xx";
    private final AutorizadorJwt autorizador = new AutorizadorJwt(SECRETO);

    /** Genera un token igual al que emite ms-usuarios (HS256, subject = email). */
    private static String token(String secreto, Instant expiracion) {
        return Jwts.builder()
                .subject("ana@bodega.cl")
                .issuedAt(Date.from(Instant.now().minusSeconds(60)))
                .expiration(Date.from(expiracion))
                .signWith(Keys.hmacShaKeyFor(secreto.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private static Map<String, Object> eventoCon(String authorization) {
        Map<String, Object> headers = new HashMap<>();
        if (authorization != null) {
            headers.put("authorization", authorization);
        }
        return Map.of("headers", headers);
    }

    @Test
    void tokenValidoAutorizaYPasaElEmail() {
        String t = token(SECRETO, Instant.now().plusSeconds(600));
        Map<String, Object> respuesta = autorizador.handleRequest(eventoCon("Bearer " + t), null);
        assertEquals(true, respuesta.get("isAuthorized"));
        assertEquals(Map.of("email", "ana@bodega.cl"), respuesta.get("context"));
    }

    @Test
    void rechazaSinHeaderOFormatoIncorrecto() {
        assertEquals(false, autorizador.handleRequest(eventoCon(null), null).get("isAuthorized"));
        assertEquals(false, autorizador.handleRequest(eventoCon("Bearer "), null).get("isAuthorized"));
        assertEquals(false, autorizador.handleRequest(eventoCon("Basic abc"), null).get("isAuthorized"));
        assertEquals(false, autorizador.handleRequest(Map.of(), null).get("isAuthorized"));
    }

    @Test
    void rechazaTokenExpiradoFirmadoConOtroSecretoOMalformado() {
        String expirado = token(SECRETO, Instant.now().minusSeconds(10));
        String otroSecreto = token("otro-secreto-distinto-de-al-menos-32-caracteres", Instant.now().plusSeconds(600));
        for (String t : new String[]{expirado, otroSecreto, "no.es.jwt"}) {
            assertEquals(false, autorizador.handleRequest(eventoCon("Bearer " + t), null).get("isAuthorized"), t);
        }
    }

    @Test
    void exigeElSecreto() {
        assertThrows(IllegalStateException.class, () -> new AutorizadorJwt(" "));
    }
}
