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

  it("simulates a workflow from what each step would learn", async () => {
    const definition = { start: "implement", steps: [
      { id: "implement", kind: "agent", roles: ["implementer"], next: [{ to: "verify" }] },
      { id: "verify", kind: "factory", action: "run-tests", next: [
        { when: { fact: "tests.passed", equals: false }, to: "implement", maxVisits: 1 }, { to: "@done" }] }] };
    const calls = fakeApi({
      "GET /api/user": user(["work-order:read", "work-order:write"]),
      "GET /api/work-orders": [],
      "GET /api/approvals": [],
      "GET /api/workflows/mini/versions": [{ name: "mini", version: 1, definition, note: "first", createdBy: "ada", createdAt: "2026-10-20T10:00:00Z" }],
      "POST /api/workflows/dry-run": { valid: true, errors: [], result: { outcome: "@done", reason: null, path: [
        { step: "implement", visit: 1, learnt: {}, next: "verify", reason: "unconditional" },
        { step: "verify", visit: 1, learnt: { "tests.passed": true }, next: "@done", reason: "unconditional" }] } },
    });
    await renderApp("/workflows/mini");
    expect(await screen.findByText(/au plus 1×, puis @blocked/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Simuler" }));
    const path = await screen.findByRole("list", { name: "Parcours simulé" });
    expect(within(path).getByText("verify #1 → @done (unconditional)")).toBeInTheDocument();
    expect(calls.find((c) => c.what === "POST /api/workflows/dry-run")!.body).toMatchObject({ definition, learnt: { verify: [{ "tests.passed": true }] } });
    expect(screen.queryByRole("button", { name: "Publier" })).not.toBeInTheDocument(); // not an admin
  });

  it("lets an admin pause the factory and change the settings with a reason", async () => {
    document.cookie = "XSRF-TOKEN=t";
    const admin = { name: "ada", roles: ["ADMIN"], permissions: ["work-order:read", "settings:write"] };
    const calls = fakeApi({
      "GET /api/user": admin, "GET /api/work-orders": [], "GET /api/approvals": [], "GET /api/repositories": [],
      "GET /api/budgets": { paused: false, dailyUsd: 20, workOrderUsd: 5, spentTodayUsd: 1.25 },
      "GET /api/settings": { scope: "global", document: { roles: {} }, effective: { roles: { default: {} } }, updatedBy: "ada", updatedAt: "2026-10-20T10:00:00Z" },
      "GET /api/settings/changes": [],
    });
    const factory = await renderApp("/admin/factory");
    expect(await screen.findByText("Dépensé aujourd'hui : 1.25 $ sur 20.00 $")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Mettre en pause" }));
    await vi.waitFor(() => expect(calls.map((c) => c.what)).toContain("POST /api/factory/pause"));
    factory.unmount();

    await renderApp("/admin/settings");
    fireEvent.change(await screen.findByLabelText("Couche"), { target: { value: '{"roles":{"default":{"effort":"medium"}}}' } });
    fireEvent.change(screen.getByLabelText("Raison"), { target: { value: "moins cher" } });
    fireEvent.click(screen.getByRole("button", { name: "Enregistrer" }));
    await vi.waitFor(() => expect(calls.find((c) => c.what === "PUT /api/settings")?.body)
      .toEqual({ document: { roles: { default: { effort: "medium" } } }, reason: "moins cher" }));
  });

  it("shows the plan next to the decision in the approvals inbox", async () => {
    const analyst = { id: "r-a", stepId: "analyze", visit: 1, kind: "AGENT", task: "analyst", status: "SUCCEEDED",
      attempt: 1, error: null, costUsd: 0.01, createdAt: "2026-10-20T10:00:00Z", finishedAt: "2026-10-20T10:01:00Z" };
    fakeApi({
      "GET /api/user": user(["work-order:read", "approval:decide"]),
      "GET /api/work-orders": [workOrder({ status: "WAITING_FOR_HUMAN" })],
      "GET /api/approvals": [gate],
      "GET /api/work-orders/wo-1": { ...detail([gate]), runs: [analyst],
        workOrder: workOrder({ status: "WAITING_FOR_HUMAN", facts: { type: "BUG", risk: "HIGH", "analysis.valid": true } }) },
      "GET /api/work-orders/wo-1/runs/r-a": { run: analyst, output: { valid: true, reproduced: true, risk: "HIGH",
        plan: "Round once, on the total.", acceptanceCriteria: ["Totals of 3 lines at 0.005 are 0.02"] } },
    });
    await renderApp("/approvals");
    expect(await screen.findByText("Round once, on the total.")).toBeInTheDocument();
    expect(screen.getByText("Totals of 3 lines at 0.005 are 0.02")).toBeInTheDocument();
    expect(screen.getByText(/risque HIGH/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approuver" })).toBeInTheDocument();
    const stack = screen.getByRole("complementary", { name: "Barrières en attente" });
    expect(within(stack).getByText("Fix rounding")).toBeInTheDocument(); // the gate waits, wherever you are
  });

  it("lets an admin register a repository and create an intake rule", async () => {
    document.cookie = "XSRF-TOKEN=t";
    const calls = fakeApi({
      "GET /api/user": { name: "ada", roles: ["ADMIN"], permissions: ["work-order:read", "settings:write"] },
      "GET /api/work-orders": [], "GET /api/approvals": [], "GET /api/repositories": [],
      "GET /api/intake-rules": [], "GET /api/tickets": [],
    });
    const page = await renderApp("/admin/repositories");
    fireEvent.change(await screen.findByLabelText("Chemin"), { target: { value: "acme/api" } });
    fireEvent.click(screen.getByRole("button", { name: "Enregistrer" }));
    await vi.waitFor(() => expect(calls.find((c) => c.what === "POST /api/repositories")?.body).toEqual({
      forge: "GITHUB", path: "acme/api", settings: { defaultBranch: "main", testCommand: "mvn -B verify",
        testReports: "**/target/surefire-reports/*.xml", sandboxImage: "factory-sandbox" } }));
    page.unmount();

    await renderApp("/admin/intake");
    const rule = await screen.findByRole("form", { name: "Nouvelle règle" });
    for (const [label, value] of [["Nom", "docs"], ["Projet", "acme/api"], ["Filtre", "factory-docs"], ["Dépôt cible", "github:acme/api"]]) {
      fireEvent.change(within(rule).getByLabelText(label), { target: { value } });
    }
    fireEvent.click(within(rule).getByRole("button", { name: "Créer la règle" }));
    await vi.waitFor(() => expect(calls.find((c) => c.what === "POST /api/intake-rules")?.body).toEqual({
      name: "docs", source: "github", project: "acme/api", filter: "factory-docs", repository: "github:acme/api", maxRead: 50, maxCreated: 5 }));
  });
});
