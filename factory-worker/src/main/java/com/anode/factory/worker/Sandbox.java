package com.anode.factory.worker;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Where a run's commands execute, isolated from the worker: given directories of the host, nothing else. */
interface Sandbox {

    /** What a sandbox did; {@code logs}: the tail of its output, for errors. */
    record Exit(int code, boolean timedOut, String logs) {}

    /** A directory of the host the sandbox sees at {@code container}; what it writes comes back only if writable. */
    record Mount(Path host, String container, boolean writable) {}

    /** {@code command}: replaces the image's entrypoint; empty: the image's own. */
    record Spec(String image, List<String> command, Map<String, String> env, List<Mount> mounts, double cpus,
                int memoryMb, Duration timeout) {}

    /** Runs to the end, the timeout, or until {@code cancelled} says so. */
    Exit run(Spec spec, BooleanSupplier cancelled);
}
