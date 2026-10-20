package com.anode.factory.forge;

import java.util.List;

/** A failed CI check of a PR, with the end of its log: what a fix iteration starts from. */
public record FailedCheck(String name, String url, String summary, String log) {

    private static final int MAX_LINES = 150;
    private static final int MAX_CHARS = 20_000;

    /** The last lines of a CI log, colours removed. */
    static String tail(String log) {
        if (log == null) {
            return null;
        }
        List<String> lines = log.replaceAll("\u001B\\[[;\\d]*[A-Za-z]", "").lines().toList();
        String tail = String.join("\n", lines.subList(Math.max(0, lines.size() - MAX_LINES), lines.size()));
        return tail.length() > MAX_CHARS ? tail.substring(tail.length() - MAX_CHARS) : tail;
    }
}
