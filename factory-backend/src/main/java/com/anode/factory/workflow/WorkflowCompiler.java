package com.anode.factory.workflow;

import com.anode.factory.workflow.WorkflowDefinition.Outcome;
import com.anode.factory.workflow.WorkflowDefinition.Step;
import com.anode.factory.workflow.WorkflowDefinition.Transition;
import com.anode.workflow.entities.steps.Branch;
import com.anode.workflow.entities.steps.Route;
import com.anode.workflow.entities.steps.Step.StepType;
import com.anode.workflow.entities.steps.Task;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a workflow into a definition of the workflow engine. Each step becomes an engine task run
 * by the {@value #STEP} bean, followed by a singular route run by the {@value #ROUTE} bean whose
 * branches are the step's transitions (and their exhausted alternatives); outcomes become tasks of
 * the {@value #OUTCOME} bean leading to the engine's end.
 */
public final class WorkflowCompiler {

    /** Bean names of the engine components (in the work order module). */
    public static final String START = "factoryStart";
    public static final String STEP = "factoryStep";
    public static final String ROUTE = "factoryRoute";
    public static final String OUTCOME = "factoryOutcome";

    private static final String STEP_PREFIX = "s:";
    private static final String ROUTE_SUFFIX = ":route";
    private static final String EXHAUSTED_SUFFIX = ":exhausted";

    private WorkflowCompiler() {}

    public static com.anode.workflow.entities.workflows.WorkflowDefinition compile(String name, WorkflowDefinition workflow) {
        return compile(name, workflow, workflow.start());
    }

    /** {@code start}: the step the case starts at (a reworked work order goes on from where it stopped). */
    public static com.anode.workflow.entities.workflows.WorkflowDefinition compile(String name, WorkflowDefinition workflow,
                                                                                   String start) {
        var engine = new com.anode.workflow.entities.workflows.WorkflowDefinition();
        engine.setName(name);
        engine.addStep(new Task("start", START, stepName(start), null));
        for (Step step : workflow.steps()) {
            engine.addStep(new Task(stepName(step.id()), STEP, routeName(step.id()), step.id()));
            Map<String, Branch> branches = new HashMap<>();
            List<Transition> next = step.next();
            for (int i = 0; i < next.size(); i++) {
                branches.put(branchName(i), new Branch(branchName(i), target(next.get(i).to())));
                if (next.get(i).maxVisits() != null) {
                    branches.put(exhaustedBranchName(i),
                            new Branch(exhaustedBranchName(i), target(next.get(i).exhaustedTarget())));
                }
            }
            engine.addStep(new Route(routeName(step.id()), ROUTE, step.id(), branches, StepType.S_ROUTE));
        }
        for (Outcome outcome : Outcome.values()) {
            engine.addStep(new Task(outcome.target(), OUTCOME, "end", outcome.name()));
        }
        engine.validate();
        return engine;
    }

    /** Workflow step id of an engine step name, or null for the factory's own steps. */
    public static String stepId(String engineStepName) {
        return engineStepName != null && engineStepName.startsWith(STEP_PREFIX)
                ? engineStepName.substring(STEP_PREFIX.length())
                : null;
    }

    public static String branchName(int transition) {
        return "t" + transition;
    }

    public static String exhaustedBranchName(int transition) {
        return "t" + transition + EXHAUSTED_SUFFIX;
    }

    private static String stepName(String id) {
        return STEP_PREFIX + id;
    }

    private static String routeName(String id) {
        return STEP_PREFIX + id + ROUTE_SUFFIX;
    }

    private static String target(String to) {
        return Outcome.isOutcome(to) ? to : stepName(to);
    }
}
