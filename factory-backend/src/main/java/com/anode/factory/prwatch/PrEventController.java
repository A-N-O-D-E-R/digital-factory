package com.anode.factory.prwatch;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The conversation between the forge and the factory, for the work-order timeline. */
@RestController
class PrEventController {

    record PrEventView(String id, String kind, String author, boolean trusted, String ignoredBecause, String body,
                       String url, Instant receivedAt, Instant consumedAt) {
        static PrEventView of(ForgeEvent e) {
            return new PrEventView(e.id(), e.kind(), e.author(), e.trusted(), e.ignoredBecause(), e.body(), e.url(),
                    e.receivedAt(), e.consumedAt());
        }
    }

    private final PrWatch watch;

    PrEventController(PrWatch watch) {
        this.watch = watch;
    }

    @GetMapping("/api/work-orders/{id}/pr-events")
    List<PrEventView> of(@PathVariable UUID id) {
        return watch.of(id).stream().map(PrEventView::of).toList();
    }
}
