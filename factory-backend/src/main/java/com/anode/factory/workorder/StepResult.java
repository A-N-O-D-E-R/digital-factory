package com.anode.factory.workorder;

import com.anode.workflow.entities.steps.responses.StepResponseType;
import com.anode.workflow.entities.steps.responses.TaskResponse;

/** How a step's execution ends for now: proceed to its transitions, or wait to be resumed. */
enum StepResult {
    PROCEED,
    WAIT;

    TaskResponse response() {
        return this == PROCEED
                ? new TaskResponse(StepResponseType.OK_PROCEED, "", "")
                // "execute on resume": the step runs again when the work order is resumed
                : new TaskResponse(StepResponseType.OK_PEND_EOR, "", "factory");
    }
}
