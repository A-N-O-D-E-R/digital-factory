package com.anode.factory.run;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

public class RunNotFoundException extends ErrorResponseException {
    public RunNotFoundException(UUID runId) {
        super(HttpStatus.NOT_FOUND);
        getBody().setDetail("No run " + runId);
    }
}
