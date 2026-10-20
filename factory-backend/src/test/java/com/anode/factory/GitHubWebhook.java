package com.anode.factory;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Deliveries to the GitHub webhook endpoint, signed as GitHub does (secret of application-test.yaml). */
public final class GitHubWebhook {

    public static final String SECRET = "test-webhook-secret";

    private GitHubWebhook() {}

    public static MvcTestResult deliver(MockMvcTester mvc, String event, String delivery, String payload, String secret) {
        return mvc.post().uri("/webhooks/github")
                .header("X-GitHub-Event", event)
                .header("X-GitHub-Delivery", delivery)
                .header("X-Hub-Signature-256", sign(payload, secret))
                .contentType(MediaType.APPLICATION_JSON).content(payload).exchange();
    }

    public static MvcTestResult deliver(MockMvcTester mvc, String event, String payload) {
        return deliver(mvc, event, UUID.randomUUID().toString(), payload, SECRET);
    }

    static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String checksCompleted(long number, String conclusion) {
        return """
                {"action":"completed","repository":{"full_name":"acme/api"},
                 "check_suite":{"conclusion":"%s","pull_requests":[{"number":%d}]}}""".formatted(conclusion, number);
    }

    public static String closed(long number, boolean merged) {
        return closed(number, merged, null);
    }

    public static String closed(long number, boolean merged, String mergeCommit) {
        return """
                {"action":"closed","repository":{"full_name":"acme/api"},"pull_request":{"number":%d,"merged":%s%s}}"""
                .formatted(number, merged, mergeCommit == null ? "" : ",\"merge_commit_sha\":\"" + mergeCommit + "\"");
    }
}
