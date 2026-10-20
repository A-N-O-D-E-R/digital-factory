import { describe as suite, expect, it } from "vitest";
import { describe } from "./workflow";

suite("conditions in words", () => {
  it("reads facts, sets and combinations", () => {
    expect(describe(undefined)).toBe("sinon");
    expect(describe({ allOf: [{ fact: "pr.event", equals: "checks_passed" }, { fact: "autonomy.allows", equals: true }] }))
      .toBe("pr.event = checks_passed et autonomy.allows = true");
    expect(describe({ not: { fact: "risk", in: ["HIGH", "MEDIUM"] } })).toBe("non (risk ∈ {HIGH, MEDIUM})");
  });
});
