/** The workflow definition as the backend's schema has it (contracts/workflow-definition.schema.json). */
export type Condition =
  | { fact: string; equals?: unknown; in?: unknown[] }
  | { allOf: Condition[] }
  | { anyOf: Condition[] }
  | { not: Condition };
export type Transition = { when?: Condition; to: string; maxVisits?: number; onExhausted?: string };
export type Step = {
  id: string; kind: "agent" | "factory" | "gate"; roles?: string[]; join?: string; action?: string; actions?: string[];
  gate?: string; when?: Condition; next: Transition[]; enabled?: boolean;
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
  return (step.enabled === false ? "désactivée · " : "") + (step.kind === "agent" ? `agent ${step.roles?.join(" + ")}${step.join && step.join !== "all" ? ` (${step.join})` : ""}`
    : step.kind === "factory" ? (step.actions ? `actions ${step.actions.join(" + ")} (en parallèle)` : `action ${step.action}`)
    : `barrière ${step.gate}${step.when ? ` si ${describe(step.when)}` : ""}`);
}

export const TERMINALS = ["@done", "@blocked", "@failed", "@cancelled", "@merged", "@closed"] as const;
export const ROLES = ["analyst", "classifier", "specifier", "implementer", "reviewer", "security-reviewer", "feedback-triage"];
export const ACTIONS = ["run-tests", "secret-scan", "push-branch", "update-branch", "select-workflow", "open-draft-pr",
  "mark-pr-ready", "request-review", "reply-feedback", "merge", "comment-ticket", "transition-ticket", "watch-pr"];
export const FACTS = ["type", "risk", "analysis.valid", "analysis.reproduced", "diff.empty", "diff.files", "diff.unchanged",
  "tests.passed", "acceptance.passed", "secrets.found", "verdict", "approval", "pr.open", "pr.event", "pr.checks.passed",
  "pr.mergeable", "branch.conflicted", "triage.changes", "autonomy.allows"];

export const blank = (): WorkflowDefinition => ({ start: "implement", steps: [
  { id: "implement", kind: "agent", roles: ["implementer"], next: [{ to: "verify" }] },
  { id: "verify", kind: "factory", action: "run-tests", next: [{ to: "@done" }] }] });

/** Columns by distance from the start (left to right, as the work flows); steps out of reach in a last column. */
export function layout(definition: WorkflowDefinition): Record<string, { x: number; y: number }> {
  const depth = new Map([[definition.start, 0]]);
  for (const queue = [definition.start]; queue.length;) {
    const id = queue.shift()!;
    for (const t of definition.steps.find((s) => s.id === id)?.next ?? []) {
      if (!depth.has(t.to)) { depth.set(t.to, depth.get(id)! + 1); queue.push(t.to); }
    }
  }
  const last = Math.max(0, ...depth.values()) + 1;
  const rows = new Map<number, number>();
  const at: Record<string, { x: number; y: number }> = {};
  for (const id of [...definition.steps.map((s) => s.id), ...TERMINALS]) {
    if (id.startsWith("@") && !depth.has(id)) continue; // outcomes only where reached
    const column = depth.get(id) ?? last;
    const row = rows.get(column) ?? 0;
    rows.set(column, row + 1);
    at[id] = { x: column * 280, y: row * 130 };
  }
  return at;
}

/** A new step id of that kind, free in the workflow. */
export function freeId(definition: WorkflowDefinition, base: string): string {
  for (let i = 1; ; i++) if (!definition.steps.some((s) => s.id === `${base}-${i}`)) return `${base}-${i}`;
}

export function addStep(definition: WorkflowDefinition, kind: Step["kind"]): [WorkflowDefinition, string] {
  const id = freeId(definition, kind === "agent" ? "agent" : kind === "factory" ? "action" : "gate");
  const step: Step = kind === "agent" ? { id, kind, roles: ["implementer"], next: [{ to: "@done" }] }
    : kind === "factory" ? { id, kind, action: "run-tests", next: [{ to: "@done" }] }
    : { id, kind, gate: "plan", next: [{ to: "@done" }] };
  return [{ ...definition, steps: [...definition.steps, step] }, id];
}

/** Without the step; transitions to it go with it (a step must keep one, else it ends done). */
export function removeStep(definition: WorkflowDefinition, id: string): WorkflowDefinition {
  const steps = definition.steps.filter((s) => s.id !== id).map((s) => {
    const next = s.next.filter((t) => t.to !== id);
    return { ...s, next: next.length ? next : [{ to: "@done" }] };
  });
  return { ...definition, steps, start: definition.start === id ? steps[0]?.id ?? "" : definition.start };
}

/** The step renamed everywhere it is named: start, transitions, exhaustion targets. */
export function renameStep(definition: WorkflowDefinition, from: string, to: string): WorkflowDefinition {
  const swap = (id: string | undefined) => (id === from ? to : id);
  return {
    ...definition, start: swap(definition.start)!,
    steps: definition.steps.map((s) => ({ ...s, id: swap(s.id)!,
      next: s.next.map((t) => ({ ...t, to: swap(t.to)!, ...(t.onExhausted && { onExhausted: swap(t.onExhausted) }) })) })),
  };
}

/**
 * A transition drawn from one step to another. The last one stays the unconditional fallback, so a new one goes
 * before it; it needs a condition, which the user then gives.
 */
export function connect(definition: WorkflowDefinition, from: string, to: string): WorkflowDefinition {
  return { ...definition, steps: definition.steps.map((s) => {
    if (s.id !== from || s.next.some((t) => t.to === to)) return s;
    const fallback = s.next.at(-1);
    return fallback && !fallback.when
      ? { ...s, next: [...s.next.slice(0, -1), { to, when: { fact: "verdict", equals: "approved" } }, fallback] }
      : { ...s, next: [...s.next, { to }] };
  }) };
}

export function updateStep(definition: WorkflowDefinition, id: string, change: (step: Step) => Step): WorkflowDefinition {
  return { ...definition, steps: definition.steps.map((s) => (s.id === id ? change(s) : s)) };
}

/** What the user typed as a value, typed as the fact expects: true/false, a number, or text. */
export function typed(value: string): string | number | boolean {
  return value === "true" ? true : value === "false" ? false : value.trim() !== "" && !Number.isNaN(Number(value)) ? Number(value) : value;
}

/** The object without those keys (optional fields are left out of the definition rather than set to undefined). */
export function without<T extends object, K extends keyof T>(o: T, ...keys: K[]): Omit<T, K> {
  const copy = { ...o };
  for (const k of keys) delete copy[k];
  return copy;
}
