import { useState } from "react";
import type { Approval } from "../api/client";
import { useCan, useDecide } from "../api/queries";
import { GATE_LABELS } from "../lib/status";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";

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
          <Textarea aria-label="Commentaire" value={comment} onChange={(e) => setComment(e.target.value)}
                    placeholder="Commentaire (facultatif)" className="min-h-16" />
          <div className="flex gap-2">
            <Button variant="ghost" disabled={decide.isPending} onClick={() => send("APPROVED")}
                    className="bg-done text-white">Approuver</Button>
            <Button variant="outline" disabled={decide.isPending} onClick={() => send("REJECTED")}
>Rejeter</Button>
          </div>
        </>
      )}
    </div>
  );
}
