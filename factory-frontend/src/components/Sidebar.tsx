import { Link } from "@tanstack/react-router";
import { GitBranch, Inbox, LayoutGrid, Settings } from "lucide-react";
import { useCurrentUser, usePendingApprovals, useWorkOrders } from "../api/queries";
import { agentState } from "../lib/status";
import { AgentStateDot } from "./AgentStateDot";
import { ThemeToggle } from "./ThemeToggle";

/** Every work order with what the factory is doing about it; what waits for you first. */
export function Sidebar() {
  const workOrders = useWorkOrders();
  const approvals = usePendingApprovals();
  const sorted = [...(workOrders.data ?? [])].sort(
    (a, b) => Number(b.status === "WAITING_FOR_HUMAN") - Number(a.status === "WAITING_FOR_HUMAN")
      || b.updatedAt.localeCompare(a.updatedAt),
  );
  const pending = approvals.data?.length ?? 0;
  const user = useCurrentUser().data;
  return (
    <nav aria-label="Work orders" className="flex w-72 shrink-0 flex-col border-r">
      <div className="flex flex-col gap-0.5 p-2">
        <Link to="/" className="flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-accent [&.active]:bg-accent">
          <LayoutGrid className="size-4" /> Tableau
        </Link>
        <Link to="/approvals" className="flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-accent [&.active]:bg-accent">
          <Inbox className="size-4" /> Approbations
          {pending > 0 && <span className="ml-auto rounded-full bg-waiting px-1.5 text-xs text-black">{pending}</span>}
        </Link>
        <Link to="/workflows" className="flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-accent [&.active]:bg-accent">
          <GitBranch className="size-4" /> Workflows
        </Link>
      </div>
      <ul className="flex-1 overflow-y-auto border-t p-2">
        {sorted.map((w) => (
          <li key={w.id}>
            <Link to="/work-orders/$id" params={{ id: w.id }}
                  className="flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-accent [&.active]:bg-accent">
              <AgentStateDot state={agentState(w.status)} />
              <span className="truncate">{w.title}</span>
            </Link>
          </li>
        ))}
        {workOrders.data?.length === 0 && <li className="px-2 py-1.5 text-muted-foreground">Aucun work order</li>}
      </ul>
      <div className="flex items-center gap-1 border-t p-2">
        {user?.roles.includes("ADMIN") && (
          <Link to="/admin/integrations" aria-label="Administration" className="rounded-md p-1.5 hover:bg-accent">
            <Settings className="size-4" />
          </Link>
        )}
        <span className="truncate px-1 text-xs text-muted-foreground">{user?.name}</span>
        <span className="ml-auto text-xs text-muted-foreground"><kbd>Ctrl</kbd>+<kbd>K</kbd></span>
        <ThemeToggle />
      </div>
    </nav>
  );
}
