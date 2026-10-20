import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRouter, RouterProvider } from "@tanstack/react-router";
import { render } from "@testing-library/react";
import { vi } from "vitest";
import { routeTree } from "./routeTree.gen";

/** Requests the app made: "METHOD /path" and their headers and body. */
export type Call = { what: string; headers: Headers; body: unknown };

/** The API as the app sees it: {"GET /api/user": {...}}; anything else is a 404. */
export function fakeApi(responses: Record<string, unknown>): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: Request) => {
    const url = new URL(input.url);
    const what = `${input.method} ${url.pathname}`;
    const text = await input.text();
    calls.push({ what, headers: input.headers, body: text ? JSON.parse(text) : undefined });
    return what in responses
      ? new Response(JSON.stringify(responses[what]), { status: 200, headers: { "Content-Type": "application/json" } })
      : new Response(null, { status: what.startsWith("GET") ? 404 : 204 });
  }));
  vi.stubGlobal("EventSource", class { addEventListener() {} close() {} });
  return calls;
}

/** The app at {@code path}, its route (code-split) loaded. */
export async function renderApp(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createRouter({ routeTree, context: { queryClient }, history: createMemoryHistory({ initialEntries: [path] }) });
  await router.load();
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

export const workOrder = (over: Record<string, unknown> = {}) => ({
  id: "wo-1", repository: "github:acme/api", title: "Fix rounding", description: "Totals are off by a cent",
  workflow: "default", workflowVersion: 1, status: "RUNNING", currentStep: "implement", currentVisit: 1,
  outcomeReason: null, pauseReason: null, facts: { type: "BUG" }, createdBy: "dev",
  createdAt: "2026-10-20T10:00:00Z", updatedAt: "2026-10-20T10:05:00Z", ...over,
});
