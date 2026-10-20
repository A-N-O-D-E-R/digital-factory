import { createFileRoute } from "@tanstack/react-router";
import { format } from "date-fns";
import { fr } from "date-fns/locale";
import { useState } from "react";
import type { Run, TimelineEvent } from "../api/client";
import { useLiveEvents } from "../api/live";
import { useCan, useCancel, useRework, useTimeline, useWorkOrder } from "../api/queries";
import { ApprovalDecision } from "../components/Approvals";
import { AgentStateDot } from "../components/AgentStateDot";
import { Evidence } from "../components/Evidence";
import { agentState, STATUS_LABELS } from "../lib/status";

export const Route = createFileRoute("/work-orders/$id")({ component: WorkOrderPage });

const TERMINAL = ["DONE", "BLOCKED", "FAILED", "CANCELLED"];

function WorkOrderPage() {
  const { id } = Route.useParams();
  useLiveEvents(id);
  const detail = useWorkOrder(id);
  const timeline = useTimeline(id);
  if (!detail.data) {
    return <div className="p-6 text-muted-foreground">{detail.isError ? "Work order introuvable." : "Chargement…"}</div>;
  }
  const { workOrder, runs, approvals, costUsd } = detail.data;
  const pending = approvals.filter((a) => a.decision === "PENDING");
  return (
    <div className="flex max-w-5xl flex-col gap-6 p-6">
      <header className="flex flex-col gap-1">
        <div className="flex items-center gap-2">
          <AgentStateDot state={agentState(workOrder.status)} />
          <h1 className="text-lg font-semibold">{workOrder.title}</h1>
        </div>
        <p className="text-muted-foreground">
          {STATUS_LABELS[workOrder.status]}
          {workOrder.currentStep && ` · étape ${workOrder.currentStep} (passage ${workOrder.currentVisit})`}
          {" · "}{workOrder.repository} · workflow {workOrder.workflow} v{workOrder.workflowVersion}
          {" · "}{formatUsd(costUsd)}
        </p>
        {(workOrder.outcomeReason || workOrder.pauseReason) && (
          <p className="text-muted-foreground">{workOrder.pauseReason ?? workOrder.outcomeReason}</p>
        )}
        <Actions id={workOrder.id} status={workOrder.status} />
      </header>

      {pending.map((approval) => <ApprovalDecision key={approval.id} approval={approval} />)}

      <section className="flex flex-col gap-2">
        <h2 className="font-medium">Demande</h2>
        <p className="whitespace-pre-wrap text-muted-foreground">{workOrder.description}</p>
      </section>

      <Evidence workOrderId={workOrder.id} runs={runs} />

      <section className="flex flex-col gap-2">
        <h2 className="font-medium">Runs</h2>
        <RunsTable runs={runs} />
      </section>

      <section className="flex flex-col gap-2">
        <h2 className="font-medium">Faits</h2>
        <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 font-mono text-xs">
          {Object.entries(workOrder.facts).filter(([, v]) => typeof v !== "object").map(([k, v]) => (
            <div key={k} className="contents"><dt className="text-muted-foreground">{k}</dt><dd>{String(v)}</dd></div>
          ))}
        </dl>
      </section>

      <section className="flex flex-col gap-2">
        <h2 className="font-medium">Chronologie</h2>
        <ol className="flex flex-col gap-1">
          {(timeline.data ?? []).map((event) => <TimelineEntry key={event.id} event={event} />)}
        </ol>
      </section>
    </div>
  );
}

function Actions({ id, status }: { id: string; status: string }) {
  const can = useCan("work-order:write");
  const cancel = useCancel();
  const rework = useRework();
  const [instructions, setInstructions] = useState("");
  if (!can) {
    return null;
  }
  return (
    <div className="mt-2 flex flex-col gap-2">
      {!TERMINAL.includes(status) && (
        <button onClick={() => cancel.mutate(id)} disabled={cancel.isPending}
                className="w-fit rounded-md border px-3 py-1.5 hover:bg-accent disabled:opacity-50">Annuler</button>
      )}
      {status === "BLOCKED" && (
        <form className="flex flex-col gap-2" onSubmit={(e) => { e.preventDefault(); rework.mutate({ id, instructions }); }}>
          <textarea aria-label="Instructions de reprise" required value={instructions}
                    onChange={(e) => setInstructions(e.target.value)} placeholder="Ce qui manquait pour avancer…"
                    className="min-h-20 rounded-md border bg-transparent p-2" />
          <button type="submit" disabled={rework.isPending}
                  className="w-fit rounded-md bg-foreground px-3 py-1.5 text-background disabled:opacity-50">Reprendre</button>
        </form>
      )}
    </div>
  );
}

function RunsTable({ runs }: { runs: Run[] }) {
  return (
    <table className="w-full text-left text-xs">
      <thead className="text-muted-foreground">
        <tr><th className="py-1">Étape</th><th>Tâche</th><th>Essai</th><th>Statut</th><th>Coût</th><th>Fin</th></tr>
      </thead>
      <tbody>
        {runs.map((run) => (
          <tr key={run.id} className="border-t" title={run.error ?? undefined}>
            <td className="py-1">{run.stepId} #{run.visit}</td><td>{run.task}</td><td>{run.attempt}</td>
            <td>{run.status}</td><td>{run.costUsd == null ? "—" : formatUsd(run.costUsd)}</td>
            <td>{run.finishedAt ? format(new Date(run.finishedAt), "HH:mm:ss") : "—"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function TimelineEntry({ event }: { event: TimelineEvent }) {
  const data = event.data as Record<string, unknown>;
  const what = event.type === "run" ? `run ${data.task} (${data.step}) : ${data.status}`
    : event.type === "approval" ? `barrière ${data.gate} : ${data.decision}${data.by ? ` par ${data.by}` : ""}`
    : `${data.status}${data.step ? ` · ${data.step}` : ""}${data.reason ? ` — ${data.reason}` : ""}`;
  return (
    <li className="flex gap-3 text-xs">
      <time className="shrink-0 text-muted-foreground">{format(new Date(event.at), "d MMM HH:mm:ss", { locale: fr })}</time>
      <span>{what}</span>
    </li>
  );
}

function formatUsd(value: number): string {
  return new Intl.NumberFormat("fr-FR", { style: "currency", currency: "USD", maximumFractionDigits: 3 }).format(value);
}
