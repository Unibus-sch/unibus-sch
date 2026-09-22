package com.unibus.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

class WebPushSenderIntegrationTest {

    @Test
    void encryptsAndSendsPayloadAcrossTheHttpPushBoundary() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        AtomicReference<CapturedPush> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push/one", exchange -> capture(exchange, captured));
        server.start();
        try {
            KeyPair vapid = keyPair();
            KeyPair subscriber = keyPair();
            String vapidPublic = base64(Utils.encode((ECPublicKey) vapid.getPublic()));
            String vapidPrivate = base64(Utils.encode((ECPrivateKey) vapid.getPrivate()));
            String subscriberPublic = base64(Utils.encode((ECPublicKey) subscriber.getPublic()));
            byte[] authSecret = new byte[16];
            new SecureRandom().nextBytes(authSecret);
            String auth = base64(authSecret);
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/push/one";
            String payload = "{\"title\":\"통합 테스트\",\"body\":\"실제 암호화 전송\"}";

            WebPushSender sender = new WebPushSender(
                vapidPublic, vapidPrivate, "mailto:test@unibus.local"
            );
            int status = sender.send(
                new NotificationAdminRepository.Subscription(
                    java.util.UUID.randomUUID(), endpoint, subscriberPublic, auth
                ),
                payload
            );

            assertThat(status).isEqualTo(201);
            CapturedPush request = captured.get();
            assertThat(request).isNotNull();
            assertThat(request.authorization()).startsWith("vapid t=");
            assertThat(request.contentEncoding()).isEqualTo("aes128gcm");
            assertThat(request.contentType()).isEqualTo("application/octet-stream");
            assertThat(request.body()).isNotEmpty();
            assertThat(new String(request.body(), StandardCharsets.UTF_8)).doesNotContain("통합 테스트");
        } finally {
            server.stop(0);
        }
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", "BC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private void capture(HttpExchange exchange, AtomicReference<CapturedPush> captured)
        throws IOException {
        captured.set(new CapturedPush(
            exchange.getRequestHeaders().getFirst("Authorization"),
            exchange.getRequestHeaders().getFirst("Content-Encoding"),
            exchange.getRequestHeaders().getFirst("Content-Type"),
            exchange.getRequestBody().readAllBytes()
        ));
        exchange.sendResponseHeaders(201, -1);
        exchange.close();
    }

    private record CapturedPush(
        String authorization,
        String contentEncoding,
        String contentType,
        byte[] body
    ) {
    }
}
