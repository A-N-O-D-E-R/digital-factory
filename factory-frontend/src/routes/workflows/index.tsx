import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { api, type Schemas } from "../../api/client";
import type { WorkflowDefinition } from "../../lib/workflow";

export const Route = createFileRoute("/workflows/")({ component: Workflows });

function Workflows() {
  const workflows = useQuery({ queryKey: ["workflows"], queryFn: () => api.get("workflows").json<Schemas["VersionView"][]>() });
  return (
    <div className="flex max-w-4xl flex-col gap-4 p-6">
      <h1 className="text-lg font-semibold">Workflows</h1>
      <ul className="flex flex-col divide-y rounded-lg border">
        {workflows.data?.map((w) => (
          <li key={w.name}>
            <Link to="/workflows/$name" params={{ name: w.name }} className="flex flex-col gap-0.5 p-3 hover:bg-accent">
              <span className="font-medium">{w.name} <span className="text-muted-foreground">v{w.version}</span></span>
              <span className="text-muted-foreground">{(w.definition as unknown as WorkflowDefinition).description ?? w.note}</span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
