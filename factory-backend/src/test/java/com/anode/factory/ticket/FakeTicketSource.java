package com.anode.factory.ticket;

import com.anode.factory.platform.IntegrationCheck;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GitHub issues as the tests see them: issues they file, comments the factory posts. */
public class FakeTicketSource implements TicketSource, IntegrationCheck {

    private static final Pattern URL = Pattern.compile("https://github.test/(\\w+/\\w+)/issues/(\\d+)");

    /** Issues by key, newest last; labelled with their label. */
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, String> labels = new ConcurrentHashMap<>();
    private final List<String> comments = new CopyOnWriteArrayList<>();
    private final List<String> transitions = new CopyOnWriteArrayList<>();
    private volatile String outage;

    /** The integration check fails with {@code error} (null: works again). */
    public void outage(String error) {
        this.outage = error;
    }

    @Override
    public String name() {
        return type();
    }

    @Override
    public String check() {
        if (outage != null) {
            throw new IllegalStateException(outage);
        }
        return "Fake GitHub";
    }

    public Ticket file(String project, int number, String title, String label) {
        TicketRef ref = new TicketRef(type(), project, Integer.toString(number));
        Ticket ticket = new Ticket(ref, title, "Body of " + title,
                "https://github.test/" + project + "/issues/" + number);
        tickets.put(ref.key(), ticket);
        labels.put(ref.key(), label);
        return ticket;
    }

    /** "key: comment" in order. */
    public List<String> comments() {
        return comments;
    }

    /** "key: status" in order. */
    public List<String> transitions() {
        return transitions;
    }

    @Override
    public String type() {
        return "github";
    }

    @Override
    public Optional<TicketRef> parseUrl(String url) {
        Matcher m = URL.matcher(url);
        return m.matches() ? Optional.of(new TicketRef(type(), m.group(1), m.group(2))) : Optional.empty();
    }

    @Override
    public Optional<String> repositoryOf(TicketRef ref) {
        return Optional.of("github:" + ref.project());
    }

    @Override
    public Ticket read(TicketRef ref) {
        Ticket ticket = tickets.get(ref.key());
        if (ticket == null) {
            throw new IllegalStateException("No issue " + ref.key());
        }
        return ticket;
    }

    @Override
    public List<Ticket> search(String project, String filter, int limit) {
        if (filter.equals("broken")) {
            throw new IllegalStateException("GitHub is down");
        }
        return tickets.values().stream()
                .filter(t -> t.ref().project().equals(project) && filter.equals(labels.get(t.ref().key())))
                .sorted((a, b) -> Integer.parseInt(b.ref().id()) - Integer.parseInt(a.ref().id()))
                .limit(limit)
                .toList();
    }

    @Override
    public void comment(TicketRef ref, String body) {
        comments.add(ref.key() + ": " + body);
    }

    @Override
    public void transition(TicketRef ref, String status) {
        transitions.add(ref.key() + ": " + status);
    }
}
