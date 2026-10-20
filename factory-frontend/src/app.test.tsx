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
});
