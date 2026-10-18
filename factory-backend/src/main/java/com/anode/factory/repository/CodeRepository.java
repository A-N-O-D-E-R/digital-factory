package com.anode.factory.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A repository the factory works on, and how to build and test it. Its key ({@code github:acme/api})
 * is how work orders refer to it.
 */
@Entity
@Table(name = "repositories")
public class CodeRepository {

    @Id private String key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ForgeType forge;

    /** owner/name on GitHub, group/project on GitLab. */
    @Column(nullable = false)
    private String path;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch;

    /** Runs the repository's tests from its root, in the sandbox. */
    @Column(name = "test_command", nullable = false)
    private String testCommand;

    /** Glob of the JUnit XML reports the test command writes. */
    @Column(name = "test_reports", nullable = false)
    private String testReports;

    /** Sandbox image with the repository's toolchain. */
    @Column(name = "sandbox_image", nullable = false)
    private String sandboxImage;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CodeRepository() {}

    CodeRepository(ForgeType forge, String path, String defaultBranch, String testCommand, String testReports,
                   String sandboxImage, Instant now) {
        this.key = key(forge, path);
        this.forge = forge;
        this.path = path;
        this.defaultBranch = defaultBranch;
        this.testCommand = testCommand;
        this.testReports = testReports;
        this.sandboxImage = sandboxImage;
        this.active = true;
        this.createdAt = now;
    }

    static String key(ForgeType forge, String path) {
        return forge.name().toLowerCase() + ":" + path;
    }

    void update(String defaultBranch, String testCommand, String testReports, String sandboxImage, boolean active) {
        this.defaultBranch = defaultBranch;
        this.testCommand = testCommand;
        this.testReports = testReports;
        this.sandboxImage = sandboxImage;
        this.active = active;
    }

    public String key() {
        return key;
    }

    public ForgeType forge() {
        return forge;
    }

    public String path() {
        return path;
    }

    /** owner (GitHub) or group (GitLab). */
    public String owner() {
        return path.substring(0, path.lastIndexOf('/'));
    }

    public String name() {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    public String defaultBranch() {
        return defaultBranch;
    }

    public String testCommand() {
        return testCommand;
    }

    public String testReports() {
        return testReports;
    }

    public String sandboxImage() {
        return sandboxImage;
    }

    public boolean active() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
