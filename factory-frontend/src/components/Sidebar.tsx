import { Link } from "@tanstack/react-router";
import { useState } from "react";
import { GitBranch, Inbox, LayoutGrid, PanelLeftClose, PanelLeftOpen, Settings } from "lucide-react";
import { useCurrentUser, usePendingApprovals, useWorkOrders } from "../api/queries";
import { agentState } from "../lib/status";
import { AgentStateDot } from "./AgentStateDot";
import { Logo } from "./Logo";

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
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem("sidebar") === "collapsed");
  const toggle = () => setCollapsed((c) => {
    localStorage.setItem("sidebar", c ? "open" : "collapsed");
    return !c;
  });
  const hide = collapsed ? "hidden" : "";
  return (
    <nav aria-label="Work orders" className={`brand-gradient flex shrink-0 flex-col text-white transition-[width] ${collapsed ? "w-14" : "w-72"}`}>
      <Link to="/" className="flex flex-col items-center gap-1 p-3">
        <Logo size="size-8" />
        <span className={`text-[0.6rem] tracking-wider text-white/80 ${hide}`}>DIGITAL FACTORY</span>
      </Link>
      <div className="flex flex-col gap-0.5 p-2">
        <Link to="/" title="Tableau" className={`flex items-center gap-2 rounded-md px-2 py-1.5 ${collapsed ? "justify-center" : ""} hover:bg-white/10 [&.active]:bg-white/30 [&.active]:font-semibold`}>
          <LayoutGrid className="size-4" /> <span className={hide}>Tableau</span>
        </Link>
        <Link to="/approvals" title="Approbations" className={`flex items-center gap-2 rounded-md px-2 py-1.5 ${collapsed ? "justify-center" : ""} hover:bg-white/10 [&.active]:bg-white/30 [&.active]:font-semibold`}>
          <Inbox className="size-4" /> <span className={hide}>Approbations</span>
          {pending > 0 && <span className={`${collapsed ? "absolute -mt-4 ml-5 px-1 text-[0.6rem]" : "ml-auto px-1.5 text-xs"} rounded-full bg-waiting text-black`}>{pending}</span>}
        </Link>
        <Link to="/workflows" title="Workflows" className={`flex items-center gap-2 rounded-md px-2 py-1.5 ${collapsed ? "justify-center" : ""} hover:bg-white/10 [&.active]:bg-white/30 [&.active]:font-semibold`}>
          <GitBranch className="size-4" /> <span className={hide}>Workflows</span>
        </Link>
      </div>
      <ul className="flex-1 overflow-y-auto border-t border-white/20 p-2">
        {sorted.map((w) => (
          <li key={w.id}>
            <Link to="/work-orders/$id" params={{ id: w.id }} title={w.title}
                  className={`flex items-center gap-2 rounded-md px-2 py-1.5 ${collapsed ? "justify-center" : ""} hover:bg-white/10 [&.active]:bg-white/30 [&.active]:font-semibold`}>
              <AgentStateDot state={agentState(w.status)} />
              <span className={`truncate ${hide}`}>{w.title}</span>
            </Link>
          </li>
        ))}
        {workOrders.data?.length === 0 && <li className={`px-2 py-1.5 text-white/80 ${hide}`}>Aucun work order</li>}
      </ul>
      <div className={`flex items-center gap-1 border-t border-white/20 p-2 ${collapsed ? "flex-col" : ""}`}>
        {user?.roles.includes("ADMIN") && (
          <Link to="/admin/integrations" aria-label="Administration" className="rounded-md p-1.5 hover:bg-white/10">
            <Settings className="size-4" />
          </Link>
        )}
        <span className={`truncate px-1 text-xs text-white/80 ${hide}`}>{user?.name}</span>
        <span className={`ml-auto text-xs text-white/80 ${hide}`}><kbd>Ctrl</kbd>+<kbd>K</kbd></span>
        <button onClick={toggle} aria-label={collapsed ? "Déplier la barre latérale" : "Replier la barre latérale"}
                title={collapsed ? "Déplier" : "Replier"} className="rounded-md p-1.5 hover:bg-white/10">
          {collapsed ? <PanelLeftOpen className="size-4" /> : <PanelLeftClose className="size-4" />}
        </button>
      </div>
    </nav>
  );
}
