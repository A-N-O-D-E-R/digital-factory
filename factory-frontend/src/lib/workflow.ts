/** The workflow definition as the backend's schema has it (contracts/workflow-definition.schema.json). */
export type Condition =
  | { fact: string; equals?: unknown; in?: unknown[] }
  | { allOf: Condition[] }
  | { anyOf: Condition[] }
  | { not: Condition };
export type Transition = { when?: Condition; to: string; maxVisits?: number; onExhausted?: string };
export type Step = {
  id: string; kind: "agent" | "factory" | "gate"; roles?: string[]; join?: string; action?: string;
  gate?: string; when?: Condition; next: Transition[];
};
export type WorkflowDefinition = { description?: string; start: string; steps: Step[] };

/** A condition in words, for reading a workflow at a glance. */
export function describe(condition: Condition | undefined): string {
  if (!condition) return "sinon";
  if ("allOf" in condition) return condition.allOf.map(describe).join(" et ");
  if ("anyOf" in condition) return condition.anyOf.map(describe).join(" ou ");
  if ("not" in condition) return `non (${describe(condition.not)})`;
  if (condition.in) return `${condition.fact} ∈ {${condition.in.join(", ")}}`;
  return `${condition.fact} = ${String(condition.equals)}`;
}

export function what(step: Step): string {
  return step.kind === "agent" ? `agent ${step.roles?.join(" + ")}${step.join && step.join !== "all" ? ` (${step.join})` : ""}`
    : step.kind === "factory" ? `action ${step.action}`
    : `barrière ${step.gate}${step.when ? ` si ${describe(step.when)}` : ""}`;
}
