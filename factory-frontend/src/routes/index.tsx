import { createFileRoute, Link } from "@tanstack/react-router";
import { formatDistanceToNow } from "date-fns";
import { fr } from "date-fns/locale";
import { useState } from "react";
import type { WorkOrder } from "../api/client";
import { useWorkOrders } from "../api/queries";
import { AgentStateDot } from "../components/AgentStateDot";
import { ImportTicket } from "../components/ImportTicket";
import { NewWorkOrder } from "../components/NewWorkOrder";
import { type AgentState, agentState, STATE_LABELS } from "../lib/status";

/** {@code action}: what to open on arrival (from the command palette). */
type BoardSearch = { action?: "new-work-order" | "import-ticket" };

export const Route = createFileRoute("/")({
  component: Board,
  validateSearch: (search: Record<string, unknown>): BoardSearch =>
    search.action === "new-work-order" || search.action === "import-ticket" ? { action: search.action } : {},
});

const COLUMNS: AgentState[] = ["waiting", "working", "blocked", "failed", "done", "idle"];

/** The work orders by what the factory is doing about them: where the flow is stuck shows. */
function Board() {
  const workOrders = useWorkOrders().data ?? [];
  const { action } = Route.useSearch();
  const [byStep, setByStep] = useState(false);
  return (
    <div className="flex h-full flex-col gap-4 p-6">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold">Tableau</h1>
        <div className="flex gap-2">
          <button onClick={() => setByStep(!byStep)} aria-pressed={byStep} className="rounded-md border px-3 py-1.5 hover:bg-accent">
            {byStep ? "Par état" : "Par étape"}
          </button>
          <ImportTicket key={`import-${action}`} initiallyOpen={action === "import-ticket"} />
          <NewWorkOrder key={`new-${action}`} initiallyOpen={action === "new-work-order"} />
        </div>
      </div>
      {byStep ? <ByStep workOrders={workOrders} /> : (
      <div className="grid flex-1 grid-cols-6 gap-3">
        {COLUMNS.map((state) => {
          const column = workOrders.filter((w) => agentState(w.status) === state);
          return (
            <section key={state} aria-label={STATE_LABELS[state]} className="flex min-w-0 flex-col gap-2 rounded-lg bg-muted p-2">
              <h2 className="flex items-center gap-2 px-1 text-xs font-medium text-muted-foreground">
                <AgentStateDot state={state} /> {STATE_LABELS[state]} <span className="ml-auto">{column.length}</span>
              </h2>
              {column.map((w) => (
                <Link key={w.id} to="/work-orders/$id" params={{ id: w.id }}
                      className="flex flex-col gap-1 rounded-md border bg-background p-2 hover:border-foreground/30">
                  <span className="line-clamp-2">{w.title}</span>
                  <span className="truncate text-xs text-muted-foreground">
                    {w.repository}{w.currentStep ? ` · ${w.currentStep}` : ""}
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {formatDistanceToNow(new Date(w.updatedAt), { addSuffix: true, locale: fr })}
                  </span>
                </Link>
              ))}
            </section>
          );
        })}
      </div>
      )}
    </div>
  );
}

/** The running work orders by the step they are at: where the flow piles up. */
function ByStep({ workOrders }: { workOrders: WorkOrder[] }) {
  const running = workOrders.filter((w) => w.currentStep);
  const steps = Object.entries(Object.groupBy(running, (w) => w.currentStep!));
  if (steps.length === 0) {
    return <p className="text-muted-foreground">Aucun work order en cours.</p>;
  }
  return (
    <div className="flex flex-1 gap-3 overflow-x-auto">
      {steps.map(([step, items]) => (
        <section key={step} aria-label={`Étape ${step}`} className="flex w-56 shrink-0 flex-col gap-2 rounded-lg bg-muted p-2">
          <h2 className="px-1 font-mono text-xs text-muted-foreground">{step} <span className="float-right">{items!.length}</span></h2>
          {items!.map((w) => (
            <Link key={w.id} to="/work-orders/$id" params={{ id: w.id }}
                  className="flex items-center gap-2 rounded-md border bg-background p-2 hover:border-foreground/30">
              <AgentStateDot state={agentState(w.status)} /><span className="line-clamp-2">{w.title}</span>
            </Link>
          ))}
        </section>
      ))}
    </div>
  );
}
