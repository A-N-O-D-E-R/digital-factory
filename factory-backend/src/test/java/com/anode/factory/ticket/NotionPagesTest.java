package com.anode.factory.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import notion.api.v1.NotionClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Against a stub of the Notion API: what the SDK sends and parses, not only what we call. */
class NotionPagesTest {

    static final String DB = "0123456789abcdef0123456789abcdef";
    static final String PAGE = "fedcba9876543210fedcba9876543210";
    static final String PAGE_JSON = """
            {"object":"page","id":"fedcba98-7654-3210-fedc-ba9876543210","url":"https://www.notion.so/Fix-rounding-%s",
             "parent":{"type":"database_id","database_id":"01234567-89ab-cdef-0123-456789abcdef"},
             "properties":{"Name":{"id":"title","type":"title","title":[{"type":"text","plain_text":"Fix rounding"}]},
                           "Status":{"id":"s","type":"status","status":{"name":"Ready"}}}}
            """.formatted(PAGE);

    /** "METHOD path body" in order. */
    final List<String> requests = new CopyOnWriteArrayList<>();
    HttpServer server;
    NotionPages notion;

    @BeforeEach
    void start() throws IOException {
        Map<String, String> responses = Map.of(
                "GET /v1/pages/" + PAGE, PAGE_JSON,
                "GET /v1/blocks/" + "fedcba98-7654-3210-fedc-ba9876543210" + "/children", """
                        {"object":"list","has_more":false,"results":[
                         {"object":"block","id":"b1","type":"paragraph","paragraph":{"rich_text":[
                           {"type":"text","plain_text":"Totals are "},{"type":"text","plain_text":"off by one."}]}},
                         {"object":"block","id":"b2","type":"bulleted_list_item","bulleted_list_item":{"rich_text":[
                           {"type":"text","plain_text":"in invoices"}]}},
                         {"object":"block","id":"b3","type":"divider","divider":{}}]}
                        """,
                "GET /v1/databases/" + DB, """
                        {"object":"database","id":"%s","properties":{
                         "Name":{"id":"title","name":"Name","type":"title","title":{}},
                         "Status":{"id":"s","name":"Status","type":"status","status":{"options":[]}},
                         "Factory":{"id":"f","name":"Factory","type":"checkbox","checkbox":{}}}}
                        """.formatted(DB),
                "POST /v1/databases/" + DB + "/query", """
                        {"object":"list","has_more":false,"results":[%s]}""".formatted(PAGE_JSON),
                "POST /v1/comments", "{\"object\":\"comment\",\"id\":\"c1\"}",
                "PATCH /v1/pages/" + PAGE, PAGE_JSON);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requests.add(route + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responses.getOrDefault(route, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responses.containsKey(route) ? 200 : 404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        NotionClient client = new NotionClient("secret-test-token");
        client.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/v1");
        notion = new NotionPages(client);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aRowIsATicketWithItsTextBlocksAsBody() {
        TicketRef ref = notion.parseUrl("https://www.notion.so/acme/Fix-rounding-" + PAGE + "?pvs=4").orElseThrow();
        assertThat(ref.key()).isEqualTo("notion:" + DB + "#" + PAGE);
        Ticket ticket = notion.read(ref);
        assertThat(ticket.title()).isEqualTo("Fix rounding");
        assertThat(ticket.body()).isEqualTo("Totals are off by one.\n- in invoices");
        assertThat(ticket.url()).isEqualTo("https://www.notion.so/Fix-rounding-" + PAGE);
        assertThat(notion.parseUrl("https://github.com/acme/api/issues/1")).isEmpty();
    }

    @Test
    void rulesFilterOnAStatusNewestFirst() {
        List<Ticket> tickets = notion.search(DB, "Status = Ready", 10);
        assertThat(tickets).extracting(Ticket::title).containsExactly("Fix rounding");
        String query = requests.stream().filter(r -> r.startsWith("POST /v1/databases/")).findFirst().orElseThrow();
        assertThat(query.replace(" ", "")).contains("\"property\":\"Status\"", "\"status\":{\"equals\":\"Ready\"}",
                "\"timestamp\":\"created_time\"", "\"direction\":\"descending\"", "\"page_size\":10");

        assertThatThrownBy(() -> notion.search(DB, "Name = x", 10)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> notion.search(DB, "Ready", 10)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void progressIsACommentAndTransitionsSetTheProperty() {
        TicketRef ref = new TicketRef("notion", DB, PAGE);
        notion.comment(ref, "The factory started work order 42.");
        notion.transition(ref, "Status = In progress");
        notion.transition(ref, "Factory = false");

        assertThat(requests.stream().filter(r -> r.startsWith("POST /v1/comments")).findFirst().orElseThrow()
                .replace(" ", "")).contains("\"page_id\":\"" + PAGE + "\"", "Thefactorystartedworkorder42.");
        List<String> updates = requests.stream().filter(r -> r.startsWith("PATCH")).map(r -> r.replace(" ", "")).toList();
        assertThat(updates.get(0)).contains("\"Status\":{\"id\":\"s\",\"status\":{\"name\":\"Inprogress\"}");
        assertThat(updates.get(1)).contains("\"Factory\":{\"id\":\"f\",\"checkbox\":false}");
    }
}
