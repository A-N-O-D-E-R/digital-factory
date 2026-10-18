package com.anode.factory.forge;

import java.time.Instant;

/**
 * Git-over-HTTPS access to one repository, given to a worker for one run. {@code password} is the
 * token; it is never logged.
 */
public record GitCredential(String cloneUrl, String username, String password, Instant expiresAt) {

    @Override
    public String toString() {
        return "GitCredential[" + cloneUrl + ", expiresAt=" + expiresAt + "]";
    }
}
