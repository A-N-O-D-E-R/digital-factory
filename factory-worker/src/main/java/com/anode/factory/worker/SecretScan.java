package com.anode.factory.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Secrets in the lines a diff adds: the shape of well-known credentials, not entropy. */
// ponytail: a handful of rules; run gitleaks in the evidence sandbox if they miss real leaks
final class SecretScan {

    record Finding(String path, int line, String rule) {}

    private static final Map<String, Pattern> RULES = Map.of(
            "private-key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            "aws-access-key", Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"),
            "github-token", Pattern.compile("\\b(gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,})\\b"),
            "gitlab-token", Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}\\b"),
            "anthropic-key", Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{20,}"),
            "slack-token", Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}"),
            "generic-secret", Pattern.compile(
                    "(?i)\\b(password|passwd|secret|api[_-]?key|token)\\b\\s*[:=]\\s*[\"'][^\"'\\s]{12,}[\"']"));

    private static final Pattern FILE = Pattern.compile("^\\+\\+\\+ b/(.+)$");
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    private SecretScan() {}

    static List<Finding> scan(String unifiedDiff) {
        List<Finding> findings = new ArrayList<>();
        String path = null;
        int line = 0;
        for (String text : unifiedDiff.split("\n", -1)) {
            Matcher file = FILE.matcher(text);
            Matcher hunk = HUNK.matcher(text);
            if (file.find()) {
                path = file.group(1);
            } else if (text.startsWith("+++ ")) {
                path = null; // a deleted file
            } else if (hunk.find()) {
                line = Integer.parseInt(hunk.group(1));
            } else if (path != null && text.startsWith("+")) {
                for (var rule : RULES.entrySet()) {
                    if (rule.getValue().matcher(text).find()) {
                        findings.add(new Finding(path, line, rule.getKey()));
                    }
                }
                line++;
            } else if (!text.startsWith("-")) {
                line++; // context
            }
        }
        return findings;
    }
}
