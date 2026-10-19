package com.anode.factory.ticket;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** The configured ticket sources. */
@Component
class TicketSources {

    private static final Pattern KEY = Pattern.compile("([a-z]+):(.+)#([^#\\s]+)");

    private final Map<String, TicketSource> byType;

    TicketSources(List<TicketSource> sources) {
        this.byType = sources.stream().collect(Collectors.toMap(TicketSource::type, Function.identity()));
    }

    TicketSource of(String type) {
        TicketSource source = byType.get(type);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "No " + type + " integration is configured");
        }
        return source;
    }

    /** A ticket key ({@code github:acme/api#12}) or a web URL of a configured source. */
    TicketRef parse(String reference) {
        Matcher key = KEY.matcher(reference.strip());
        if (key.matches() && byType.containsKey(key.group(1))) {
            return new TicketRef(key.group(1), key.group(2), key.group(3));
        }
        return byType.values().stream()
                .map(source -> source.parseUrl(reference.strip()))
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Not a ticket of a configured source: " + reference));
    }
}
