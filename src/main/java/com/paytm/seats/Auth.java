package com.paytm.seats;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Token -> principal. The user id used anywhere in the service comes from here and NEVER from a request body.
 * Tokens: admin token | v1.&lt;b64url(user)&gt;.&lt;hmac&gt; (issued by POST /auth/token) | user:&lt;id&gt; (dev, toggleable).
 */
@Component
public class Auth {
    public static final Pattern USER_ID = Pattern.compile("^[A-Za-z0-9_.@-]{1,64}$");

    public record Principal(boolean admin, String userId) {}

    private final String adminToken;
    private final byte[] secret;
    private final boolean allowDev;

    public Auth(@Value("${app.admin-token}") String adminToken,
                @Value("${app.token-secret}") String secret,
                @Value("${app.allow-dev-tokens}") boolean allowDev) {
        this.adminToken = adminToken;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.allowDev = allowDev;
    }

    public String issue(String userId) {
        return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(userId.getBytes(StandardCharsets.UTF_8))
                + "." + sign(userId);
    }

    private String sign(String userId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(userId.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean eq(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private Principal authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ")) return null;
        String tok = header.substring(7).trim();
        if (tok.isEmpty()) return null;
        if (eq(tok, adminToken)) return new Principal(true, null);
        if (tok.startsWith("v1.")) {
            String[] p = tok.split("\\.");
            if (p.length != 3) return null;
            try {
                String user = new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8);
                if (USER_ID.matcher(user).matches() && eq(p[2], sign(user))) return new Principal(false, user);
            } catch (IllegalArgumentException ignored) { }
            return null;
        }
        if (allowDev && tok.startsWith("user:")) {
            String user = tok.substring(5);
            if (USER_ID.matcher(user).matches()) return new Principal(false, user);
        }
        return null;
    }

    public String requireUser(String header) {
        Principal p = authenticate(header);
        if (p == null) throw new DomainException(401, "unauthorized", "missing or invalid bearer token");
        if (p.admin()) throw new DomainException(403, "forbidden", "user token required");
        return p.userId();
    }

    public void requireAdmin(String header) {
        Principal p = authenticate(header);
        if (p == null) throw new DomainException(401, "unauthorized", "missing or invalid bearer token");
        if (!p.admin()) throw new DomainException(403, "forbidden", "admin token required");
    }
}
