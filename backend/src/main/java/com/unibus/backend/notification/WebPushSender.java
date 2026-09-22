package com.unibus.backend.notification;

import java.security.Security;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.Header;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class WebPushSender {

    private final String publicKey;
    private final String privateKey;
    private final String subject;

    WebPushSender(
        @Value("${app.push.vapid-public-key:}") String publicKey,
        @Value("${app.push.vapid-private-key:}") String privateKey,
        @Value("${app.push.vapid-subject:mailto:admin@sch.ac.kr}") String subject
    ) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.subject = subject;
    }

    boolean isConfigured() {
        return !publicKey.isBlank() && !privateKey.isBlank();
    }

    int send(NotificationAdminRepository.Subscription subscription, String payload) throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        PushService service = new PushService(publicKey, privateKey, subject);
        Notification notification = new Notification(
            subscription.endpoint(), subscription.p256dh(), subscription.auth(), payload
        );
        HttpPost request = service.preparePost(notification, nl.martijndwars.webpush.Encoding.AES128GCM);
        normalizeVapidHeader(request);
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            return client.execute(request, response -> response.getStatusLine().getStatusCode());
        }
    }

    private void normalizeVapidHeader(HttpPost request) {
        Header authorization = request.getFirstHeader("Authorization");
        Header cryptoKey = request.getFirstHeader("Crypto-Key");
        if (authorization == null || cryptoKey == null
            || !authorization.getValue().startsWith("WebPush ")) {
            return;
        }
        String signingKey = null;
        for (String part : cryptoKey.getValue().split(";")) {
            String value = part.trim();
            if (value.startsWith("p256ecdsa=")) {
                signingKey = value.substring("p256ecdsa=".length());
                break;
            }
        }
        if (signingKey == null || signingKey.isBlank()) return;

        String token = authorization.getValue().substring("WebPush ".length());
        request.setHeader("Authorization", "vapid t=" + token + ", k=" + signingKey);
        request.removeHeaders("Crypto-Key");
    }
}
