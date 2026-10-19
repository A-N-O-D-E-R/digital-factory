package com.anode.factory.ticket;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import notion.api.v1.NotionClient;
import notion.api.v1.http.JavaNetHttpClient;
import notion.api.v1.model.blocks.Block;
import notion.api.v1.model.common.PropertyType;
import notion.api.v1.model.common.RichTextType;
import notion.api.v1.model.databases.DatabaseProperty;
import notion.api.v1.model.databases.query.filter.PropertyFilter;
import notion.api.v1.model.databases.query.filter.condition.CheckboxFilter;
import notion.api.v1.model.databases.query.filter.condition.SelectFilter;
import notion.api.v1.model.databases.query.filter.condition.StatusFilter;
import notion.api.v1.model.databases.query.sort.QuerySort;
import notion.api.v1.model.databases.query.sort.QuerySortDirection;
import notion.api.v1.model.databases.query.sort.QuerySortTimestamp;
import notion.api.v1.model.pages.Page;
import notion.api.v1.model.pages.PageParent;
import notion.api.v1.model.pages.PageParentType;
import notion.api.v1.model.pages.PageProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rows of Notion databases (notion-sdk-jvm). A ticket's project is the database id, its id the page id
 * ({@code notion:<database>#<page>}, without dashes). A rule's filter, and a configured transition, is
 * {@code Property = Value} on a status, select or checkbox property.
 */
@Component
@ConditionalOnProperty(prefix = "anode.notion", name = "token") // as the starter's NotionClient
class NotionPages implements TicketSource {

    private static final Pattern URL = Pattern.compile("https://(?:www\\.)?notion\\.so/(?:[^?#]*[/-])?([0-9a-f]{32})(?:[?#].*)?");
    private static final Pattern CONDITION = Pattern.compile("\\s*(.+?)\\s*=\\s*(.+?)\\s*");

    private final NotionClient notion;

    NotionPages(NotionClient notion) {
        // ponytail: the SDK's default HttpURLConnection client PATCHes by reflection into java.net, refused
        // on Java 25; move this to the starter's NotionAutoConfiguration
        notion.setHttpClient(new JavaNetHttpClient());
        this.notion = notion;
    }

    @Override
    public String type() {
        return "notion";
    }

    /** A page URL names no database: the page's parent does. */
    @Override
    public Optional<TicketRef> parseUrl(String reference) {
        Matcher m = URL.matcher(reference);
        if (!m.matches()) {
            return Optional.empty();
        }
        Page page = notion.retrievePage(m.group(1), null);
        if (page.getParent().getType() != PageParentType.DatabaseId) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, reference + " is not a database row");
        }
        return Optional.of(ref(page));
    }

    @Override
    public Optional<String> repositoryOf(TicketRef ref) {
        return Optional.empty();
    }

    private TicketRef ref(Page page) {
        return new TicketRef(type(), bare(page.getParent().getDatabaseId()), bare(page.getId()));
    }

    private static String bare(String id) {
        return id.replace("-", "");
    }

    @Override
    public Ticket read(TicketRef ref) {
        return ticket(notion.retrievePage(ref.id(), null), true);
    }

    /** {@code withBody}: the page's top-level blocks as text; search results are titles only until read. */
    private Ticket ticket(Page page, boolean withBody) {
        String title = page.getProperties().values().stream()
                .filter(p -> p.getType() == PropertyType.Title)
                .findFirst()
                .map(p -> text(p.getTitle()))
                .orElse("");
        String body = withBody ? body(page.getId()) : "";
        return new Ticket(ref(page), title, body, page.getUrl());
    }

    // ponytail: first 100 top-level blocks, text blocks only; paginate and recurse into children if pages get long
    private String body(String pageId) {
        return notion.retrieveBlockChildren(pageId, null, 100).getResults().stream()
                .map(NotionPages::line)
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private static String line(Block block) {
        return switch (block.getType()) {
            case Paragraph -> text(block.asParagraph().getParagraph().getRichText());
            case HeadingOne -> text(block.asHeadingOne().getHeading1().getRichText());
            case HeadingTwo -> text(block.asHeadingTwo().getHeading2().getRichText());
            case HeadingThree -> text(block.asHeadingThree().getHeading3().getRichText());
            case BulletedListItem -> "- " + text(block.asBulletedListItem().getBulletedListItem().getRichText());
            case NumberedListItem -> "- " + text(block.asNumberedListItem().getNumberedListItem().getRichText());
            case ToDo -> "- " + text(block.asToDo().getToDo().getRichText());
            case Quote -> text(block.asQuote().getQuote().getRichText());
            case Code -> text(block.asCode().getCode().getRichText());
            case null, default -> "";
        };
    }

    private static String text(List<PageProperty.RichText> rich) {
        return rich == null ? "" : rich.stream().map(PageProperty.RichText::getPlainText).collect(Collectors.joining());
    }

    @Override
    public List<Ticket> search(String project, String filter, int limit) {
        Map.Entry<String, String> condition = condition(filter);
        PropertyFilter query = new PropertyFilter();
        query.setProperty(condition.getKey());
        switch (kind(notion.retrieveDatabase(project).getProperties().get(condition.getKey()), filter)) {
            case "status" -> {
                StatusFilter equals = new StatusFilter();
                equals.setEquals(condition.getValue());
                query.setStatus(equals);
            }
            case "select" -> {
                SelectFilter equals = new SelectFilter();
                equals.setEquals(condition.getValue());
                query.setSelect(equals);
            }
            default -> {
                CheckboxFilter equals = new CheckboxFilter();
                equals.setEquals(Boolean.parseBoolean(condition.getValue()));
                query.setCheckbox(equals);
            }
        }
        QuerySort newest = new QuerySort(null, QuerySortTimestamp.CreatedTime, QuerySortDirection.Descending);
        return notion.queryDatabase(project, query, List.of(newest), null, limit).getResults().stream()
                .map(page -> ticket(page, false))
                .toList();
    }

    @Override
    public void comment(TicketRef ref, String body) {
        notion.createComment(PageParent.page(ref.id()), // the String overload is a discussion's
                List.of(new PageProperty.RichText(RichTextType.Text, new PageProperty.RichText.Text(body))));
    }

    /** {@code status}: {@code Property = Value}, as a rule's filter. */
    @Override
    public void transition(TicketRef ref, String status) {
        Map.Entry<String, String> condition = condition(status);
        DatabaseProperty property = notion.retrieveDatabase(ref.project()).getProperties().get(condition.getKey());
        String kind = kind(property, status);
        PageProperty value = new PageProperty(property.getId()); // the SDK's default id is a random one
        switch (kind) {
            case "status" -> value.setStatus(new DatabaseProperty.Status.Option(null, condition.getValue(), null, null));
            case "select" -> value.setSelect(new DatabaseProperty.Select.Option(null, condition.getValue(), null, null));
            default -> value.setCheckbox(Boolean.parseBoolean(condition.getValue()));
        }
        notion.updatePage(ref.id(), Map.of(condition.getKey(), value), null, null, null);
    }

    static Map.Entry<String, String> condition(String condition) {
        Matcher m = CONDITION.matcher(condition);
        if (!m.matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected 'Property = Value', not: " + condition);
        }
        return Map.entry(m.group(1), m.group(2));
    }

    /** By the field set rather than the type: the SDK's PropertyType has no status. */
    private static String kind(DatabaseProperty property, String condition) {
        if (property != null && property.getStatus() != null) {
            return "status";
        }
        if (property != null && property.getSelect() != null) {
            return "select";
        }
        if (property != null && property.getCheckbox() != null) {
            return "checkbox";
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                "Not a status, select or checkbox property: " + condition);
    }
}
