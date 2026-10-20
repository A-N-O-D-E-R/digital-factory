package com.anode.factory.forge;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Against a stub of GitHub's GraphQL endpoint: what is asked, and what is resolved. */
class GitHubGraphqlTest {

    final List<String> requests = new CopyOnWriteArrayList<>();
    HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/graphql", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
            String answer = body.contains("reviewThreads") ? """
                    {"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[
                      {"id":"T_other","isResolved":false,"comments":{"nodes":[{"databaseId":1}]}},
                      {"id":"T_ours","isResolved":false,"comments":{"nodes":[{"databaseId":4242}]}}]}}}}}"""
                    : "{\"data\":{\"resolveReviewThread\":{\"thread\":{\"id\":\"T_ours\"}}}}";
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theThreadStartedByTheCommentIsResolved() {
        GitHubGraphql graphql = new GitHubGraphql("http://localhost:" + server.getAddress().getPort(), "inst-token");
        assertThat(graphql.resolveThread("acme", "api", 7, "4242")).isTrue();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).startsWith("Bearer inst-token ").contains("\"number\":7", "\"owner\":\"acme\"");
        assertThat(requests.get(1)).contains("resolveReviewThread", "\"id\":\"T_ours\"");

        assertThat(graphql.resolveThread("acme", "api", 7, "999")).as("no thread starts with it").isFalse();
        assertThat(requests).hasSize(3);
    }
}
