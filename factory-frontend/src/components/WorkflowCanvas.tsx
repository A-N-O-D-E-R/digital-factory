import {
  applyNodeChanges, Background, Controls, type Edge, Handle, MarkerType, type Node, type NodeChange, type NodeProps, Panel,
  Position, ReactFlow,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { Bot, Flag, Hand, Play, Wrench } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { describe, type Step, TERMINALS, what, type WorkflowDefinition } from "../lib/workflow";

type At = Record<string, { x: number; y: number }>;
type StepData = { step: Step; start: boolean };
type OutcomeData = { outcome: string };

const icons = { agent: Bot, factory: Wrench, gate: Hand };
const tints = { agent: "text-working", factory: "text-done", gate: "text-waiting" };

function StepNode({ data, selected }: NodeProps<Node<StepData>>) {
  const Icon = icons[data.step.kind];
  return (
    <div className={`w-52 rounded-lg border-2 bg-background px-3 py-2 shadow-sm ${selected ? "border-foreground" : "border-border"}
                     ${data.step.enabled === false ? "opacity-50" : ""}`}>
      <Handle type="target" position={Position.Left} className="!size-3 !border-2 !border-background !bg-muted-foreground" />
      <div className="flex items-center gap-2">
        <Icon className={`size-5 shrink-0 ${tints[data.step.kind]}`} />
        <span className="truncate font-medium">{data.step.id}</span>
        {data.start && <Play aria-label="Départ" className="ml-auto size-3.5 shrink-0 fill-working text-working" />}
      </div>
      <div className="truncate text-xs text-muted-foreground">{what(data.step)}</div>
      <Handle type="source" position={Position.Right} className="!size-3 !border-2 !border-background !bg-muted-foreground" />
    </div>
  );
}

function OutcomeNode({ data }: NodeProps<Node<OutcomeData>>) {
  return (
    <div className="flex items-center gap-1.5 rounded-full border bg-muted px-3 py-1 text-xs">
      <Handle type="target" position={Position.Left} className="!size-2.5 !bg-muted-foreground" />
      <Flag className="size-3.5" /> {data.outcome}
    </div>
  );
}

const nodeTypes = { step: StepNode, outcome: OutcomeNode };

/**
 * The workflow drawn as a graph: steps and the outcomes they reach, transitions as arrows (dashed when conditional).
 * The definition stays the truth; the canvas only reports what the user did to it.
 */
export function WorkflowCanvas({ definition, at, selected, walked, onSelect, onMove, onConnect, onRemoveStep, onRemoveTransition, onAdd }: {
  definition: WorkflowDefinition; at: At; selected?: string; walked: Set<string>;
  onSelect: (id?: string) => void; onMove: (id: string, position: { x: number; y: number }) => void;
  onConnect: (from: string, to: string) => void; onRemoveStep: (id: string) => void;
  onRemoveTransition: (from: string, index: number) => void; onAdd: (kind: Step["kind"]) => void;
}) {
  const wanted = useMemo(() => {
    const reached = new Set(definition.steps.flatMap((s) => s.next.map((t) => t.to)));
    return [
      ...definition.steps.map((step): Node => ({ id: step.id, type: "step", position: at[step.id] ?? { x: 0, y: 0 },
        data: { step, start: step.id === definition.start }, selected: step.id === selected })),
      ...TERMINALS.filter((o) => reached.has(o))
        .map((outcome): Node => ({ id: outcome, type: "outcome", position: at[outcome] ?? { x: 0, y: 0 }, data: { outcome }, deletable: false })),
    ];
  }, [definition, at, selected]);
  // what React Flow measured stays on the nodes; the rest comes from the definition
  const [nodes, setNodes] = useState<Node[]>(wanted);
  useEffect(() => setNodes((previous) => {
    const by = new Map(previous.map((n) => [n.id, n]));
    return wanted.map((n) => ({ ...by.get(n.id), ...n }));
  }), [wanted]);

  const edges = definition.steps.flatMap((step) => step.next.map((t, i): Edge => {
    const id = `${step.id}>${i}`;
    const label = [t.when && describe(t.when), t.maxVisits != null && `≤ ${t.maxVisits}×`].filter(Boolean).join(" · ");
    const hot = walked.has(id);
    return { id, source: step.id, target: t.to, label: label || undefined, animated: hot,
      markerEnd: { type: MarkerType.ArrowClosed },
      style: { strokeWidth: hot ? 2.5 : 1.5, strokeDasharray: t.when ? "5 4" : undefined, ...(hot && { stroke: "var(--working)" }) } };
  }));

  const changed = (changes: NodeChange[]) => {
    setNodes((n) => applyNodeChanges(changes.filter((c) => c.type !== "remove" && c.type !== "select"), n));
    for (const c of changes) {
      if (c.type === "position" && c.position) onMove(c.id, c.position);
      if (c.type === "remove" && !c.id.startsWith("@")) onRemoveStep(c.id);
    }
  };

  return (
    <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} onNodesChange={changed} minZoom={0.2}
               fitView fitViewOptions={{ maxZoom: 1, nodes: nodes.filter((n) => n.position.x < 4 * 280).map((n) => ({ id: n.id })) }}
               onNodeClick={(_, n) => onSelect(n.id.startsWith("@") ? undefined : n.id)} onPaneClick={() => onSelect(undefined)}
               onEdgeClick={(_, e) => onSelect(e.source)}
               onEdgesChange={(changes) => changes.forEach((c) => {
                 if (c.type === "remove") { const [from, i] = c.id.split(">"); onRemoveTransition(from, Number(i)); }
               })}
               onConnect={(c) => c.source !== c.target && onConnect(c.source, c.target)}
               isValidConnection={(c) => c.source !== c.target && !String(c.source).startsWith("@")}
               deleteKeyCode={["Backspace", "Delete"]} proOptions={{ hideAttribution: true }}>
      <Background gap={20} />
      <Controls showInteractive={false} />
      <Panel position="top-left" className="flex gap-1 rounded-lg border bg-background p-1 shadow-sm">
        {(["agent", "factory", "gate"] as const).map((kind) => {
          const Icon = icons[kind];
          return (
            <button key={kind} onClick={() => onAdd(kind)} className="flex items-center gap-1.5 rounded-md px-2 py-1 text-xs hover:bg-accent">
              <Icon className={`size-4 ${tints[kind]}`} /> {kind === "agent" ? "Agent" : kind === "factory" ? "Action" : "Barrière"}
            </button>
          );
        })}
      </Panel>
    </ReactFlow>
  );
}
