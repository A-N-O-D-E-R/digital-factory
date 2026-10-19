package com.anode.factory.worker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Test results from JUnit XML reports (surefire, vitest, node --test, pytest): counted from their
 * test cases, never from console output. Reports come from code the agent wrote: no DTD, no entity.
 */
final class JUnitReports {

    /** At most this many failures named in the evidence. */
    private static final int MAX_FAILURES = 50;

    record Suite(int total, int failed, int errors, int skipped, List<String> failures) {}

    private JUnitReports() {}

    /** The report files under {@code root} matching {@code globs} (comma-separated, relative to the root). */
    static List<Path> find(Path root, String globs) {
        List<PathMatcher> matchers = Arrays.stream(globs.split(","))
                .map(String::strip)
                .filter(g -> !g.isEmpty())
                .map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g))
                .toList();
        try (Stream<Path> files = Files.walk(root)) { // walk does not follow links
            return files.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                    .filter(f -> matchers.stream().anyMatch(m -> matches(m, root.relativize(f))))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** With "./" too: a leading **&#47; matches at the root, as in git. */
    private static boolean matches(PathMatcher matcher, Path relative) {
        return matcher.matches(relative) || matcher.matches(Path.of(".").resolve(relative));
    }

    static Suite parse(List<Path> reports) {
        int total = 0;
        int failed = 0;
        int errors = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        DocumentBuilder parser = parser();
        for (Path report : reports) {
            NodeList cases;
            try {
                cases = parser.parse(report.toFile()).getElementsByTagName("testcase");
            } catch (SAXException | IOException e) {
                throw new IllegalStateException("Unreadable test report " + report.getFileName() + ": " + e.getMessage());
            }
            for (int i = 0; i < cases.getLength(); i++) {
                Element testCase = (Element) cases.item(i);
                total++;
                String outcome = has(testCase, "failure") ? "failure" : has(testCase, "error") ? "error" : null;
                if (outcome != null) {
                    if (outcome.equals("failure")) {
                        failed++;
                    } else {
                        errors++;
                    }
                    if (failures.size() < MAX_FAILURES) {
                        Element detail = (Element) testCase.getElementsByTagName(outcome).item(0);
                        String message = detail.getAttribute("message");
                        failures.add(name(testCase) + (message.isBlank() ? "" : ": " + truncate(message)));
                    }
                } else if (has(testCase, "skipped")) {
                    skipped++;
                }
            }
        }
        return new Suite(total, failed, errors, skipped, failures);
    }

    private static boolean has(Element testCase, String child) {
        return testCase.getElementsByTagName(child).getLength() > 0;
    }

    private static String name(Element testCase) {
        String className = testCase.getAttribute("classname");
        return className.isBlank() ? testCase.getAttribute("name") : className + "." + testCase.getAttribute("name");
    }

    private static String truncate(String message) {
        String line = message.strip().lines().findFirst().orElse("");
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }

    private static DocumentBuilder parser() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }
}
