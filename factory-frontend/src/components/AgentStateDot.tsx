import { type AgentState, STATE_LABELS } from "../lib/status";

const COLORS: Record<AgentState, string> = {
  working: "bg-working animate-pulse",
  waiting: "bg-waiting",
  blocked: "bg-blocked",
  failed: "bg-failed",
  done: "bg-done",
  idle: "bg-idle",
};

export function AgentStateDot({ state }: { state: AgentState }) {
  return (
    <span role="img" aria-label={STATE_LABELS[state]} title={STATE_LABELS[state]}
          className={`inline-block size-2 shrink-0 rounded-full ${COLORS[state]}`} />
  );
}
