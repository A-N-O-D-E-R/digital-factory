package com.anode.factory.settings;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Settings that would not be valid once merged; rendered as a 422 problem listing every error. */
public class InvalidSettingsException extends ErrorResponseException {

    public InvalidSettingsException(List<String> errors) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, problem(errors), null);
    }

    private static ProblemDetail problem(List<String> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "The settings would be invalid");
        problem.setTitle("Invalid settings");
        problem.setProperty("errors", errors);
        return problem;
    }
}
