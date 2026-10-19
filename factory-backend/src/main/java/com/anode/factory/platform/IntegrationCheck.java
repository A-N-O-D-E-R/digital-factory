package com.anode.factory.platform;

/** An external system the factory talks to, as the integrations panel shows it. */
public interface IntegrationCheck {

    /** {@code github}, {@code jira}...: one per configured integration. */
    String name();

    /** What was checked (e.g. the account); throws when the integration does not work. */
    String check() throws Exception;
}
