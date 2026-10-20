import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { api, type Schemas } from "../../api/client";

export const Route = createFileRoute("/admin/repositories")({ component: Repositories });

// ponytail: read-only; registering stays on POST /api/repositories until the form is worth it
function Repositories() {
  const repositories = useQuery({ queryKey: ["repositories"], queryFn: () => api.get("repositories").json<Schemas["RepositoryView"][]>() });
  return (
    <table className="w-full max-w-5xl text-left text-xs">
      <thead className="text-muted-foreground">
        <tr><th className="py-1">Dépôt</th><th>Branche</th><th>Tests</th><th>Rapports</th><th>Image</th><th>Actif</th></tr>
      </thead>
      <tbody>
        {repositories.data?.map((r) => (
          <tr key={r.key} className="border-t">
            <td className="py-1.5">{r.key}</td><td>{r.defaultBranch}</td><td className="font-mono">{r.testCommand}</td>
            <td className="font-mono">{r.testReports}</td><td className="font-mono">{r.sandboxImage}</td><td>{r.active ? "oui" : "non"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
