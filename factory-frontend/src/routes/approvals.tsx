import { createFileRoute, Link } from "@tanstack/react-router";
import { usePendingApprovals, useWorkOrders } from "../api/queries";
import { ApprovalDecision } from "../components/Approvals";

export const Route = createFileRoute("/approvals")({ component: Approvals });

/** Everything waiting for a decision, oldest first. */
function Approvals() {
  const approvals = usePendingApprovals();
  const titles = new Map((useWorkOrders().data ?? []).map((w) => [w.id, w.title]));
  return (
    <div className="flex max-w-3xl flex-col gap-4 p-6">
      <h1 className="text-lg font-semibold">Approbations</h1>
      {approvals.data?.length === 0 && <p className="text-muted-foreground">Rien ne vous attend.</p>}
      {approvals.data?.map((approval) => (
        <section key={approval.id} className="flex flex-col gap-2">
          <Link to="/work-orders/$id" params={{ id: approval.workOrderId }} className="hover:underline">
            {titles.get(approval.workOrderId) ?? approval.workOrderId}
          </Link>
          <ApprovalDecision approval={approval} />
        </section>
      ))}
    </div>
  );
}
