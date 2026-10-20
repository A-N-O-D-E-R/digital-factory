import { useState } from "react";
import type { Approval } from "../api/client";
import { useCan, useDecide } from "../api/queries";
import { GATE_LABELS } from "../lib/status";

/** A pending gate: approve or reject, the comment kept on the work order's timeline. */
export function ApprovalDecision({ approval }: { approval: Approval }) {
  const can = useCan("approval:decide");
  const decide = useDecide();
  const [comment, setComment] = useState("");
  const send = (decision: "APPROVED" | "REJECTED") =>
    decide.mutate({ workOrderId: approval.workOrderId, approvalId: approval.id, decision, comment: comment || undefined });
  return (
    <div className="flex flex-col gap-2 rounded-lg border p-3">
      <div className="font-medium">Barrière « {GATE_LABELS[approval.gate] ?? approval.gate} » — étape {approval.stepId}</div>
      {can && (
        <>
          <textarea aria-label="Commentaire" value={comment} onChange={(e) => setComment(e.target.value)}
                    placeholder="Commentaire (facultatif)" className="min-h-16 rounded-md border bg-transparent p-2" />
          <div className="flex gap-2">
            <button disabled={decide.isPending} onClick={() => send("APPROVED")}
                    className="rounded-md bg-done px-3 py-1.5 text-white disabled:opacity-50">Approuver</button>
            <button disabled={decide.isPending} onClick={() => send("REJECTED")}
                    className="rounded-md border px-3 py-1.5 disabled:opacity-50">Rejeter</button>
          </div>
        </>
      )}
    </div>
  );
}
