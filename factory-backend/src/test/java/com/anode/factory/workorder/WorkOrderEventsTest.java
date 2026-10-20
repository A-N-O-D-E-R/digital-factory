package com.anode.factory.workorder;

import static com.anode.factory.FakeWorker.Outputs.classification;
import static com.anode.factory.Users.developer;
import static com.anode.factory.Users.viewer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.anode.factory.FakeWorker;
import com.anode.factory.IntegrationTest;
import com.anode.factory.TestRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class WorkOrderEventsTest {

    private static final AtomicInteger WORKERS = new AtomicInteger();

    @Autowired MockMvcTester mvc;
    @Autowired MockMvc mockMvc;
    final JsonMapper json = JsonMapper.builder().build();
    FakeWorker worker;

    @BeforeEach
    void setUp() {
        TestRepository.register(mvc);
        worker = new FakeWorker(mvc, "events-worker-" + WORKERS.incrementAndGet());
        while (!worker.hasNothingToClaim()) {
            // other tests' leftovers
        }
    }

    UUID create() {
        var result = mvc.post().uri("/api/work-orders").with(developer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"repository\":\"%s\",\"title\":\"Fix rounding\",\"description\":\"d\"}"
                        .formatted(TestRepository.KEY))
                .exchange();
        assertThat(result).hasStatus(201);
        return UUID.fromString(json.readTree(result.getResponse().getContentAsByteArray()).get("id").asString());
    }

    /** "id type status-or-task" of the server-sent events written so far. */
    static List<String> events(MockHttpServletResponse stream) throws Exception {
        List<String> events = new ArrayList<>();
        String id = null;
        String type = null;
        for (String line : stream.getContentAsString().split("\n", -1)) {
            if (line.startsWith("id:")) {
                id = line.substring(3);
            } else if (line.startsWith("event:")) {
                type = line.substring(6);
            } else if (line.startsWith("data:")) {
                JsonNode data = JsonMapper.builder().build().readTree(line.substring(5)).get("data");
                events.add(id + " " + type + " " + (type.equals("run") ? data.get("task").asString() + " "
                        + data.get("status").asString() : data.get("status").asString()));
            }
        }
        return events;
    }

    @Test
    void aWorkOrdersTimelineIsStreamedLiveAndResumable() throws Exception {
        UUID id = create();
        MockHttpServletResponse stream = mockMvc.perform(get("/api/work-orders/{id}/events", id).with(viewer()))
                .andReturn().getResponse();
        // the timeline so far, then what happens
        await().atMost(Duration.ofSeconds(5)).until(() -> events(stream).stream().anyMatch(e -> e.endsWith("run classifier QUEUED")));
        worker.complete(worker.claim(), classification("BUG", "LOW"));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> events(stream).stream().anyMatch(e -> e.endsWith("run classifier SUCCEEDED")));
        List<String> live = events(stream);
        assertThat(live.getFirst()).endsWith("work-order RUNNING");
        assertThat(live).anyMatch(e -> e.endsWith("run classifier RUNNING"));

        // resuming after an event sends what came after it
        String classified = live.stream().filter(e -> e.endsWith("run classifier RUNNING")).findFirst().orElseThrow();
        MockHttpServletResponse resumed = mockMvc.perform(get("/api/work-orders/{id}/events", id).with(viewer())
                .header("Last-Event-ID", classified.substring(0, classified.indexOf(' ')))).andReturn().getResponse();
        await().atMost(Duration.ofSeconds(5)).until(() -> !events(resumed).isEmpty());
        assertThat(events(resumed)).first().asString().endsWith("run classifier SUCCEEDED");

        assertThat(events(stream).stream().map(e -> e.substring(0, e.indexOf(' ')))).as("each event once")
                .doesNotHaveDuplicates();
        JsonNode timeline = json.readTree(mvc.get().uri("/api/work-orders/{id}/timeline", id).with(viewer())
                .exchange().getResponse().getContentAsByteArray());
        assertThat(timeline.get(0).get("data").get("status").asString()).isEqualTo("RUNNING");
        assertThat(timeline.size()).isGreaterThanOrEqualTo(live.size());
    }

    @Test
    void theGlobalStreamTellsAboutEveryWorkOrderFromNowOn() throws Exception {
        UUID before = create();
        Thread.sleep(1000); // streamed already when the subscriber comes
        MockHttpServletResponse stream = mockMvc.perform(get("/api/events").with(viewer())).andReturn().getResponse();
        Thread.sleep(1000); // a poll, so that the stream is live
        UUID after = create();
        await().atMost(Duration.ofSeconds(5)).until(() -> stream.getContentAsString().contains(after.toString()));
        assertThat(stream.getContentAsString()).doesNotContain(before.toString());
    }
}
