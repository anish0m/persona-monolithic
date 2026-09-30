package com.persona.config;

import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and validates JWTs. <b>Day-06, piece 1.</b>
 *
 * <p>This class knows two things and nothing else: how to turn an email into
 * a signed string, and how to turn that string back into an email — or refuse
 * to, if it was tampered with or has expired. It does not know what a
 * {@code User} is, does not touch the database, and does not know Spring
 * Security exists. Deliberately narrow, for the same reason
 * {@link SecurityConfig} stayed narrow through Day-05: the filter that reads
 * the {@code Authorization} header is piece 3, and it will depend on this
 * class rather than the other way round.
 *
 * <h2>What the token carries, on purpose</h2>
 *
 * <p>Only the subject ({@code sub} — here, the user's email) and an
 * expiration ({@code exp}). No role, no name, no id. Not an oversight: piece 1
 * is "prove a login happened and let it be checked later", and a role claim
 * belongs to authorization, which has not been built yet. Adding it now would
 * mean this class outgrows what it was asked to do before the concept it
 * depends on — roles — exists anywhere else in the codebase.
 *
 * <h2>Why the payload is not a secret</h2>
 *
 * <p>{@code Jwts.builder()...compact()} produces three Base64Url segments
 * joined by dots. Base64 is an <em>encoding</em>, not encryption — anyone who
 * has the token can decode the payload and read the email in it directly.
 * That is fine, and is exactly what {@link #extractEmail} does on the
 * receiving end without needing the key at all. What the signature protects
 * is not secrecy, it is <b>integrity</b>: changing one character of the
 * payload and re-encoding it produces a token whose signature no longer
 * matches, and {@link #isValid} catches that.
 */
@Component
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    /**
     * {@code @Value} reads {@code persona.jwt.secret} / {@code -expiration-ms}
     * from {@code application.properties} rather than the fields being
     * hard-coded — the same reasoning as {@link SecurityConfig}'s BCrypt cost
     * living in one configured place instead of scattered through call sites.
     *
     * <p>{@link Keys#hmacShaKeyFor} — not {@code new SecretKeySpec} directly —
     * because it validates the key is long enough for the algorithm
     * <em>at startup</em>. A 256-bit HMAC-SHA256 key that is too short fails
     * immediately and by name, rather than producing tokens that a security
     * scanner flags six months later.
     */
    public JwtService(
            @Value("${persona.jwt.secret}") String secretHex,
            @Value("${persona.jwt.expiration-ms}") long expirationMs) {
        byte[] keyBytes = hexToBytes(secretHex);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
    }

    private static byte[] hexToBytes(String hex) {
        int length = hex.length();
        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return bytes;
    }

    /**
     * Builds a signed token for {@code email}, expiring
     * {@code persona.jwt.expiration-ms} milliseconds from now.
     *
     * <p>Takes a {@code String}, not a {@code User} — the same instinct as
     * {@code UserService.register} taking a {@code CreateUserRequest} rather
     * than a {@code User}. This method does not need the whole entity, and a
     * narrower parameter is a narrower thing to get wrong.
     */
    public String issueToken(String email) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(email)

                // A UNIQUE TOKEN ID, and it is not decoration — leaving it out
                // was a real bug, found by logging out and immediately logging
                // back in.
                //
                // Every claim in this token is otherwise a function of (email,
                // current second): `iat` and `exp` have one-second resolution,
                // so two logins by the same user within the same second
                // produced BYTE-IDENTICAL tokens. Which meant that after a
                // logout put one on the deny list, the next login handed back
                // a token that was already revoked — a user who logged out and
                // straight back in could not get in, and the behaviour
                // depended on sub-second timing, so it looked intermittent.
                //
                // `jti` (JWT ID) is the standard claim for exactly this: a
                // nonce that makes each issued token distinct even when
                // everything else about it matches.
                .id(java.util.UUID.randomUUID().toString())

                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Reads the email out of a token <b>without</b> checking the signature.
     *
     * <p>This exists as a separate method from {@link #isValid} because they
     * answer different questions. This one answers "what does the token
     * claim", cheaply. Callers that need to trust the answer must call
     * {@link #isValid} first — piece 3's filter will do exactly that, in that
     * order, and never the reverse.
     */
    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * When this token stops being valid on its own. <b>Day-06, logout.</b>
     *
     * <p>{@link TokenDenyList} needs this so a revoked token can be forgotten
     * once it would have expired anyway — without it, the deny list grows
     * forever. Same caveat as {@link #extractEmail}: this reads the payload,
     * so callers must have established the token is valid first.
     */
    public java.time.Instant extractExpiry(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    /**
     * True if the signature verifies and the token has not expired.
     *
     * <p>Both checks happen inside {@code Jwts.parser()...parseSignedClaims},
     * which is why this method is a {@code try/catch} rather than two
     * separate {@code if}s: jjwt throws a subtype of {@link JwtException} for
     * a bad signature, a different one for expiry, and others for malformed
     * input. This class does not need to distinguish them — "not valid" is
     * one outcome from the caller's point of view — so one catch covers the
     * whole hierarchy rather than three branches that would drift out of sync
     * with jjwt's exception types on a future library upgrade.
     */
    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
