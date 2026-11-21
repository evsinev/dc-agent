import com.sun.net.httpserver.HttpServer;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * A local GitLab for the smoke: OpenID discovery and JWKS on http://127.0.0.1:PORT/git, and ID tokens
 * signed with its key written to OUT_DIR — good.jwt (project 913, protected main, push),
 * other.jwt (project 914), old.jwt (issued two hours ago, still before exp).
 *   java OidcIssuer.java PORT OUT_DIR
 */
public class OidcIssuer {

    public static void main(String[] aArgs) throws Exception {
        int  port = Integer.parseInt(aArgs[0]);
        Path out  = Path.of(aArgs[1]);
        Files.createDirectories(out);
        String issuer = "http://127.0.0.1:" + port + "/git";

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair      pair = generator.generateKeyPair();
        RSAPublicKey key  = (RSAPublicKey) pair.getPublic();
        String jwks = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"smoke-kid\",\"e\":\"" + unsigned(key.getPublicExponent())
                + "\",\"n\":\"" + unsigned(key.getModulus()) + "\",\"use\":\"sig\",\"alg\":\"RS256\"}]}";
        String discovery = "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer + "/oauth/discovery/keys\","
                + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}";

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/git/.well-known/openid-configuration", exchange -> send(exchange, discovery));
        server.createContext("/git/oauth/discovery/keys", exchange -> send(exchange, jwks));
        server.start();

        long now = System.currentTimeMillis() / 1000;
        Files.writeString(out.resolve("good.jwt"), token(pair, issuer, now - 30, "913"));
        Files.writeString(out.resolve("other.jwt"), token(pair, issuer, now - 30, "914"));
        Files.writeString(out.resolve("old.jwt"), token(pair, issuer, now - 7200, "913"));
        System.out.println("issuer " + issuer + " ready");
    }

    private static void send(com.sun.net.httpserver.HttpExchange aExchange, String aBody) throws java.io.IOException {
        byte[] body = aBody.getBytes(StandardCharsets.UTF_8);
        aExchange.getResponseHeaders().add("Content-Type", "application/json");
        aExchange.sendResponseHeaders(200, body.length);
        aExchange.getResponseBody().write(body);
        aExchange.close();
    }

    private static String token(KeyPair aPair, String aIssuer, long aIat, String aProject) throws Exception {
        String header  = "{\"kid\":\"smoke-kid\",\"typ\":\"JWT\",\"alg\":\"RS256\"}";
        String payload = "{\"aud\":\"dc-agent:smoke\",\"iss\":\"" + aIssuer + "\",\"iat\":" + aIat + ",\"nbf\":" + (aIat - 5)
                + ",\"exp\":" + (aIat + 3 * 3600) + ",\"project_id\":\"" + aProject + "\",\"project_path\":\"smoke/app\""
                + ",\"ref\":\"main\",\"ref_type\":\"branch\",\"ref_protected\":\"true\",\"pipeline_source\":\"push\""
                + ",\"pipeline_id\":\"77\",\"job_id\":\"88\",\"runner_id\":26,\"user_login\":\"smoke\",\"jti\":\"jti-" + aProject + "-" + aIat + "\"}";
        String input = b64(header.getBytes(StandardCharsets.UTF_8)) + "." + b64(payload.getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(aPair.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64(signature.sign());
    }

    private static String b64(byte[] aBytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(aBytes);
    }

    private static String unsigned(BigInteger aValue) {
        byte[] bytes = aValue.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return b64(bytes);
    }
}
