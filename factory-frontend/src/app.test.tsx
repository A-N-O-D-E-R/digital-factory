import { fireEvent, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, renderApp, workOrder } from "./test-utils";

const user = (permissions: string[]) => ({ name: "dev", roles: ["DEVELOPER"], permissions });
const gate = { id: "ap-1", workOrderId: "wo-1", stepId: "plan-gate", visit: 1, gate: "plan", decision: "PENDING",
  decidedBy: null, comment: null, createdAt: "2026-10-20T10:01:00Z", decidedAt: null };
const detail = (approvals: unknown[]) => ({
  workOrder: workOrder({ status: "WAITING_FOR_HUMAN", currentStep: "plan-gate" }), costUsd: 0.12, runs: [], approvals,
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("the factory UI", () => {
  it("shows the work orders on the board by what the factory is doing", async () => {
    fakeApi({
      "GET /api/user": user(["work-order:read"]),
      "GET /api/work-orders": [workOrder(), workOrder({ id: "wo-2", title: "Add docs", status: "BLOCKED" })],
      "GET /api/approvals": [],
    });
    await renderApp("/");
    const working = await screen.findByRole("region", { name: "Au travail" });
    expect(await within(working).findByText("Fix rounding")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Bloqué" })).getByText("Add docs")).toBeInTheDocument();
    expect(screen.queryByText("Nouveau work order")).not.toBeInTheDocument(); // no work-order:write
  });

  it("lets a developer decide a gate, the CSRF token sent back", async () => {
    document.cookie = "XSRF-TOKEN=token-123";
    const calls = fakeApi({
      "GET /api/user": user(["work-order:read", "work-order:write", "approval:decide"]),
      "GET /api/work-orders": [workOrder({ status: "WAITING_FOR_HUMAN" })],
      "GET /api/approvals": [gate],
      "GET /api/work-orders/wo-1": detail([gate]),
      "GET /api/work-orders/wo-1/timeline": [],
    });
    await renderApp("/work-orders/wo-1");
    fireEvent.change(await screen.findByLabelText("Commentaire"), { target: { value: "Plan clair" } });
    fireEvent.click(screen.getByRole("button", { name: "Approuver" }));
    await vi.waitFor(() => expect(calls.some((c) => c.what === "POST /api/work-orders/wo-1/approvals/ap-1")).toBe(true));
    const decision = calls.find((c) => c.what === "POST /api/work-orders/wo-1/approvals/ap-1")!;
    expect(decision.body).toEqual({ decision: "APPROVED", comment: "Plan clair" });
    expect(decision.headers.get("X-XSRF-TOKEN")).toBe("token-123");
  });

  it("hides the decisions from a viewer", async () => {
    fakeApi({
      "GET /api/user": user(["work-order:read"]),
      "GET /api/work-orders": [workOrder({ status: "WAITING_FOR_HUMAN" })],
      "GET /api/approvals": [gate],
      "GET /api/work-orders/wo-1": detail([gate]),
      "GET /api/work-orders/wo-1/timeline": [],
    });
    await renderApp("/work-orders/wo-1");
    expect(await screen.findByText(/Barrière « Plan »/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approuver" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Annuler" })).not.toBeInTheDocument();
  });

  it("lets an admin drain a worker and see a failing integration", async () => {
    document.cookie = "XSRF-TOKEN=t";
    const calls = fakeApi({
      "GET /api/user": { name: "ada", roles: ["ADMIN"], permissions: ["work-order:read", "settings:write"] },
      "GET /api/work-orders": [],
      "GET /api/approvals": [],
      "GET /api/workers": [{ id: "w-1", name: "builder-1", active: true, draining: false, version: "0.1.0", slots: 2, busy: 1,
        lastHeartbeatAt: "2026-10-20T10:00:00Z", createdAt: "2026-10-01T10:00:00Z", createdBy: "ada" }],
      "GET /api/integrations": [{ name: "github", configured: true, ok: false, detail: null, error: "401 Bad credentials",
        checkedAt: "2026-10-20T10:00:00Z" }, { name: "jira", configured: false, ok: null, detail: null, error: null, checkedAt: null }],
    });
    await renderApp("/admin/workers");
    expect(await screen.findByText("1/2")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Vider" }));
    await vi.waitFor(() => expect(calls.map((c) => c.what)).toContain("POST /api/workers/w-1/drain"));

    await renderApp("/admin/integrations");
    expect(await screen.findByText("En échec : 401 Bad credentials")).toBeInTheDocument();
    expect(screen.getByText("Non configurée")).toBeInTheDocument();
  });

  it("shows the evidence: tests, verdicts and the diff side by side", async () => {
    const run = (id: string, task: string, kind: string) => ({ id, stepId: task, visit: 1, kind, task, status: "SUCCEEDED",
      attempt: 1, error: null, costUsd: 0.01, createdAt: "2026-10-20T10:00:00Z", finishedAt: "2026-10-20T10:01:00Z" });
    fakeApi({
      "GET /api/user": user(["work-order:read"]),
      "GET /api/work-orders": [workOrder()],
      "GET /api/approvals": [],
      "GET /api/work-orders/wo-1": { workOrder: workOrder(), costUsd: 0.02, approvals: [],
        runs: [run("r-1", "run-tests", "EVIDENCE"), run("r-2", "reviewer", "AGENT")] },
      "GET /api/work-orders/wo-1/timeline": [],
      "GET /api/work-orders/wo-1/runs/r-1": { run: run("r-1", "run-tests", "EVIDENCE"), output: {
        diff: { files: 1, insertions: 1, deletions: 1 },
        tests: { total: 10, failed: 1, errors: 0, skipped: 0, failures: ["RoundingTest.halfEven: expected 2"] },
        patch: "diff --git a/a.js b/a.js\n--- a/a.js\n+++ b/a.js\n@@ -1 +1 @@\n-old line\n+new line\n" } },
      "GET /api/work-orders/wo-1/runs/r-2": { run: run("r-2", "reviewer", "AGENT"), output: {
        verdict: "REVISE", summary: "Round half-even", comments: [{ path: "a.js", line: 1, body: "Use HALF_EVEN" }] } },
    });
    await renderApp("/work-orders/wo-1");
    expect(await screen.findByText(/Tests : 9\/10 ok/)).toBeInTheDocument();
    expect(screen.getByText("RoundingTest.halfEven: expected 2")).toBeInTheDocument();
    expect(await screen.findByText("À revoir")).toBeInTheDocument();
    const row = screen.getByText("old line").closest("tr")!;
    expect(within(row).getByText("new line")).toBeInTheDocument(); // face to face
  });
});
