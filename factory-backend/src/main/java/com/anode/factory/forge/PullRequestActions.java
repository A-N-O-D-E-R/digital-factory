package com.anode.factory.forge;

import com.anode.factory.repository.CodeRepository;
import com.anode.factory.repository.Repositories;
import com.anode.factory.workorder.FactoryAction;
import com.anode.factory.workorder.FactoryAction.Result;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The workflow actions on the work order's pull request. The PR is remembered in the facts. */
@Configuration(proxyBeanMethods = false)
class PullRequestActions {

    private static final Logger log = LoggerFactory.getLogger(PullRequestActions.class);

    private final Repositories repositories;
    private final Forges forges;

    PullRequestActions(Repositories repositories, Forges forges) {
        this.repositories = repositories;
        this.forges = forges;
    }

    private final class Action implements FactoryAction {

        private final String name;
        private final BiFunction<Context, CodeRepository, Result> body;

        Action(String name, BiFunction<Context, CodeRepository, Result> body) {
            this.name = name;
            this.body = body;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Result execute(Context context) {
            CodeRepository repository = repositories.get(context.repository());
            try {
                return body.apply(context, repository);
            } catch (RuntimeException e) {
                // ponytail: a forge error fails the work order; retry with backoff (Spring @Retryable) if flaky
                log.warn("{} failed on {}", name, repository.key(), e);
                return Result.fail(name + " failed on " + repository.key() + ": " + e.getMessage());
            }
        }
    }

    private Forge forge(CodeRepository repository) {
        return forges.of(repository);
    }

    private static PullRequest pullRequest(FactoryAction.Context context) {
        Object number = context.facts().get("pr.number");
        if (number == null) {
            throw new IllegalStateException("no pull request opened yet");
        }
        return new PullRequest(((Number) number).longValue(), (String) context.facts().get("pr.url"),
                context.branch(), false);
    }

    @Bean
    FactoryAction openDraftPr() {
        return new Action("open-draft-pr", (context, repository) -> {
            PullRequest pr = forge(repository).openDraft(repository, context.branch(), context.title(), body(context));
            return Result.proceed(Map.of("pr.open", true, "pr.number", pr.number(), "pr.url", pr.url()));
        });
    }

    @Bean
    FactoryAction markPrReady() {
        return new Action("mark-pr-ready", (context, repository) -> {
            forge(repository).markReady(repository, pullRequest(context));
            return Result.proceed();
        });
    }

    @Bean
    FactoryAction requestReview() {
        return new Action("request-review", (context, repository) -> {
            forge(repository).requestReview(repository, pullRequest(context));
            return Result.proceed();
        });
    }

    @Bean
    FactoryAction merge() {
        return new Action("merge", (context, repository) -> {
            if (!Boolean.TRUE.equals(context.facts().get("autonomy.allows"))) {
                return Result.fail("the autonomy policy does not allow merging this work order");
            }
            if (!Boolean.TRUE.equals(context.facts().get("pr.checks.passed"))) {
                return Result.fail("the forge's checks have not passed");
            }
            forge(repository).merge(repository, pullRequest(context), context.title());
            return Result.proceed();
        });
    }

    /** The evidence the factory established, so that review on the forge is self-contained. */
    // ponytail: facts only; add the plan and the reviewers' summaries once artifacts reach actions
    static String body(FactoryAction.Context context) {
        Map<String, Object> facts = context.facts();
        return """
                Opened by the Digital Factory for work order `%s`.

                | Evidence | |
                |---|---|
                | Files changed | %s |
                | Tests passed | %s |
                | Acceptance tests passed | %s |
                | Secrets found | %s |
                | Review verdict | %s |
                """.formatted(context.workOrderId(), facts.getOrDefault("diff.files", "?"),
                facts.getOrDefault("tests.passed", "?"), facts.getOrDefault("acceptance.passed", "?"),
                facts.getOrDefault("secrets.found", "?"), facts.getOrDefault("verdict", "?"));
    }
}
