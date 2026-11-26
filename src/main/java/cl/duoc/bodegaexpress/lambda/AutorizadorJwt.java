package cl.duoc.bodegaexpress.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Lambda Authorizer del AWS API Gateway (HTTP API, payload 2.0, respuesta simple).
 * <p>
 * Valida el header {@code Authorization: Bearer <token>} con el mismo secreto HS256
 * ({@code JWT_SECRET}) con que ms-usuarios firma los tokens en {@code POST /api/v1/auth/login}.
 * Si el token es válido y no ha expirado, responde {@code isAuthorized = true} y pasa el
 * email del usuario en el contexto; en cualquier otro caso, el Gateway responde 403.
 */
public class AutorizadorJwt implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String PREFIJO = "Bearer ";

    private final SecretKey clave;

    /** Constructor que usa AWS Lambda: lee el secreto desde la variable de entorno. */
    public AutorizadorJwt() {
        this(System.getenv("JWT_SECRET"));
    }

    public AutorizadorJwt(String secreto) {
        if (secreto == null || secreto.isBlank()) {
            throw new IllegalStateException("Falta la variable de entorno JWT_SECRET");
        }
        this.clave = Keys.hmacShaKeyFor(secreto.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> evento, Context context) {
        String token = extraerToken(evento);
        if (token == null) {
            System.out.println("Acceso denegado: falta el header Authorization Bearer");
            return Map.of("isAuthorized", false);
        }
        try {
            Claims claims = Jwts.parser().verifyWith(clave).build()
                    .parseSignedClaims(token).getPayload();
            String email = claims.getSubject();
            System.out.println("Acceso permitido para " + email);
            return Map.of("isAuthorized", true, "context", Map.of("email", email));
        } catch (JwtException | IllegalArgumentException e) {
            System.out.println("Acceso denegado: token inválido (" + e.getClass().getSimpleName() + ")");
            return Map.of("isAuthorized", false);
        }
    }

    @SuppressWarnings("unchecked")
    private static String extraerToken(Map<String, Object> evento) {
        if (evento == null || !(evento.get("headers") instanceof Map<?, ?> headers)) {
            return null;
        }
        // HTTP API entrega los nombres de los headers en minúsculas
        Object valor = ((Map<String, Object>) headers).get("authorization");
        if (!(valor instanceof String texto) || !texto.startsWith(PREFIJO)) {
            return null;
        }
        String token = texto.substring(PREFIJO.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
