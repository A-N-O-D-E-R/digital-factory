package com.anode.factory.workflow;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** A workflow definition was refused; rendered as a 422 problem listing every error. */
public class InvalidWorkflowException extends ErrorResponseException {

    private final List<String> errors;

    public InvalidWorkflowException(List<String> errors) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, problem(errors), null);
        this.errors = List.copyOf(errors);
    }

    private static ProblemDetail problem(List<String> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "The workflow definition is invalid");
        problem.setTitle("Invalid workflow");
        problem.setProperty("errors", errors);
        return problem;
    }

    public List<String> errors() {
        return errors;
    }
}
