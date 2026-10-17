package com.anode.factory.run;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

public class RunNotRunningException extends ErrorResponseException {
    public RunNotRunningException(UUID runId, RunStatus status) {
        super(HttpStatus.CONFLICT);
        getBody().setDetail("Run " + runId + " is " + status + ", not RUNNING");
    }
}
