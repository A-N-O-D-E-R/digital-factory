import { describe as suite, expect, it } from "vitest";
import { addStep, connect, describe, layout, removeStep, renameStep, typed, type WorkflowDefinition } from "./workflow";

suite("conditions in words", () => {
  it("reads facts, sets and combinations", () => {
    expect(describe(undefined)).toBe("sinon");
    expect(describe({ allOf: [{ fact: "pr.event", equals: "checks_passed" }, { fact: "autonomy.allows", equals: true }] }))
      .toBe("pr.event = checks_passed et autonomy.allows = true");
    expect(describe({ not: { fact: "risk", in: ["HIGH", "MEDIUM"] } })).toBe("non (risk ∈ {HIGH, MEDIUM})");
  });
});

suite("editing a workflow on the canvas", () => {
  const mini = (): WorkflowDefinition => ({ start: "a", steps: [
    { id: "a", kind: "agent", roles: ["implementer"], next: [{ to: "b" }] },
    { id: "b", kind: "factory", action: "run-tests", next: [{ when: { fact: "tests.passed", equals: false }, to: "a", maxVisits: 2, onExhausted: "a" }, { to: "@done" }] },
    { id: "lost", kind: "gate", gate: "plan", next: [{ to: "@done" }] }] });

  it("lays steps out by distance from the start, outcomes where reached", () => {
    const at = layout(mini());
    expect(at.a.x).toBe(0);
    expect(at.b.x).toBeGreaterThan(at.a.x);
    expect(at["@done"].x).toBeGreaterThan(at.b.x);
    expect(at.lost.x).toBeGreaterThan(at["@done"].x - 1);
    expect(at["@failed"]).toBeUndefined();
  });

  it("renames a step everywhere it is named", () => {
    const renamed = renameStep(mini(), "a", "code");
    expect(renamed.start).toBe("code");
    expect(renamed.steps[1].next[0]).toMatchObject({ to: "code", onExhausted: "code" });
  });

  it("removes a step with the transitions to it, keeping a way out", () => {
    const left = removeStep(mini(), "b");
    expect(left.steps.map((s) => s.id)).toEqual(["a", "lost"]);
    expect(left.steps[0].next).toEqual([{ to: "@done" }]);
  });

  it("draws a new transition before the unconditional fallback", () => {
    const next = connect(mini(), "b", "lost").steps[1].next;
    expect(next.map((t) => t.to)).toEqual(["a", "lost", "@done"]);
    expect(next[1].when).toBeDefined();
    expect(connect(mini(), "b", "a")).toEqual(mini()); // already linked
  });

  it("adds steps under free ids", () => {
    const [once, first] = addStep(mini(), "agent");
    const [, second] = addStep(once, "agent");
    expect([first, second]).toEqual(["agent-1", "agent-2"]);
  });

  it("types what the user typed", () => {
    expect([typed("true"), typed("3"), typed("HIGH"), typed("")]).toEqual([true, 3, "HIGH", ""]);
  });
});
