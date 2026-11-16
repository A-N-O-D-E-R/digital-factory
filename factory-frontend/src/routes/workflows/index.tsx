import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { api, type Schemas } from "../../api/client";
import type { WorkflowDefinition } from "../../lib/workflow";

export const Route = createFileRoute("/workflows/")({ component: Workflows });

function Workflows() {
  const workflows = useQuery({ queryKey: ["workflows"], queryFn: () => api.get("workflows").json<Schemas["VersionView"][]>() });
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const valid = /^[a-z][a-z0-9-]{0,62}$/.test(name) && !workflows.data?.some((w) => w.name === name);
  return (
    <div className="flex max-w-4xl flex-col gap-4 p-6">
      <div className="flex items-center gap-2">
        <h1 className="text-lg font-semibold">Workflows</h1>
        <form className="ml-auto flex gap-2" onSubmit={(e) => { e.preventDefault(); if (valid) void navigate({ to: "/workflows/$name", params: { name } }); }}>
          <input aria-label="Nom du nouveau workflow" value={name} onChange={(e) => setName(e.target.value.toLowerCase())}
                 placeholder="nom-du-workflow" className="rounded-md border bg-transparent px-2 py-1" />
          <button disabled={!valid} className="rounded-md border px-3 py-1.5 hover:bg-accent disabled:opacity-50">Nouveau workflow</button>
        </form>
      </div>
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
