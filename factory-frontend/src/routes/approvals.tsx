import { createFileRoute, Link } from "@tanstack/react-router";
import { usePendingApprovals, useWorkOrder, useWorkOrders } from "../api/queries";
import { ApprovalDecision } from "../components/Approvals";
import { Evidence, Plan } from "../components/Evidence";

export const Route = createFileRoute("/approvals")({ component: Approvals });

/** Everything waiting for a decision, oldest first, with what it is decided on: plan, diff, tests, verdicts. */
function Approvals() {
  const approvals = usePendingApprovals();
  const titles = new Map((useWorkOrders().data ?? []).map((w) => [w.id, w.title]));
  return (
    <div className="flex flex-col gap-6 p-6">
      <h1 className="text-lg font-semibold">Approbations</h1>
      {approvals.data?.length === 0 && <p className="text-muted-foreground">Rien ne vous attend.</p>}
      {approvals.data?.map((approval) => (
        <section key={approval.id} className="flex flex-col gap-2">
          <Link to="/work-orders/$id" params={{ id: approval.workOrderId }} className="hover:underline">
            {titles.get(approval.workOrderId) ?? approval.workOrderId}
          </Link>
          <div className="grid grid-cols-[1fr_20rem] gap-4">
            <Proof workOrderId={approval.workOrderId} />
            <ApprovalDecision approval={approval} />
          </div>
        </section>
      ))}
    </div>
  );
}

function Proof({ workOrderId }: { workOrderId: string }) {
  const detail = useWorkOrder(workOrderId).data;
  if (!detail) {
    return null;
  }
  const plan = detail.workOrder.facts["analysis.valid"] !== undefined;
  return (
    <div className="flex min-w-0 flex-col gap-3">
      <p className="text-muted-foreground">
        {detail.workOrder.repository} · {String(detail.workOrder.facts.type ?? "?")} · risque {String(detail.workOrder.facts.risk ?? "?")}
      </p>
      {plan && <Plan workOrderId={workOrderId} runs={detail.runs} />}
      <Evidence workOrderId={workOrderId} runs={detail.runs} />
    </div>
  );
}

