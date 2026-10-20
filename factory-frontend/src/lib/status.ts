import type { WorkOrder } from "../api/client";

/** The one indicator of what the factory is doing about a work order. */
export type AgentState = "working" | "waiting" | "blocked" | "failed" | "done" | "idle";

export function agentState(status: WorkOrder["status"]): AgentState {
  switch (status) {
    case "RUNNING":
      return "working";
    case "WAITING_FOR_HUMAN":
      return "waiting";
    case "BLOCKED":
      return "blocked";
    case "FAILED":
      return "failed";
    case "DONE":
      return "done";
    default:
      return "idle"; // paused (budget, factory), cancelled
  }
}

export const STATUS_LABELS: Record<WorkOrder["status"], string> = {
  RUNNING: "En cours",
  WAITING_FOR_HUMAN: "En attente de vous",
  PAUSED: "En pause",
  DONE: "Terminé",
  BLOCKED: "Bloqué",
  FAILED: "Échoué",
  CANCELLED: "Annulé",
};

export const STATE_LABELS: Record<AgentState, string> = {
  working: "Au travail",
  waiting: "Vous attend",
  blocked: "Bloqué",
  failed: "Échoué",
  done: "Terminé",
  idle: "Inactif",
};

export const GATE_LABELS: Record<string, string> = { plan: "Plan", final: "PR finale" };
