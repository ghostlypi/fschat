package dev.fschat.server.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.time.Instant;

/**
 * Issues and verifies HS256 JWTs. Claims: {@code sub} = userId, {@code username},
 * {@code iat}, {@code exp}.
 */
public final class Tokens {

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final long ttlSeconds;

    /**
     * @param secret     HMAC secret; must be at least 32 bytes
     * @param ttlSeconds token lifetime (may be negative in tests to force expiry)
     */
    public Tokens(String secret, long ttlSeconds) {
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
        }
        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm).build();
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(String userId, String username) {
        Instant now = Instant.now();
        return JWT.create()
                .withSubject(userId)
                .withClaim("username", username)
                .withIssuedAt(now)
                .withExpiresAt(now.plusSeconds(ttlSeconds))
                .sign(algorithm);
    }

    /** Verify a token, returning the identity it asserts. */
    public Principal verify(String token) {
        try {
            DecodedJWT jwt = verifier.verify(token);
            return new Principal(jwt.getSubject(), jwt.getClaim("username").asString());
        } catch (JWTVerificationException e) {
            throw new AuthException(AuthException.INVALID_TOKEN, "invalid or expired token", e);
        }
    }
}
