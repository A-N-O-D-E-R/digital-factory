import { createFileRoute, Link } from "@tanstack/react-router";
import { formatDistanceToNow } from "date-fns";
import { fr } from "date-fns/locale";
import { useWorkOrders } from "../api/queries";
import { AgentStateDot } from "../components/AgentStateDot";
import { NewWorkOrder } from "../components/NewWorkOrder";
import { type AgentState, agentState, STATE_LABELS } from "../lib/status";

export const Route = createFileRoute("/")({ component: Board });

const COLUMNS: AgentState[] = ["waiting", "working", "blocked", "failed", "done", "idle"];

/** The work orders by what the factory is doing about them: where the flow is stuck shows. */
function Board() {
  const workOrders = useWorkOrders().data ?? [];
  return (
    <div className="flex h-full flex-col gap-4 p-6">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold">Tableau</h1>
        <NewWorkOrder />
      </div>
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
    </div>
  );
}
