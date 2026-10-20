import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import type { FormEvent } from "react";
import { api, type Schemas } from "../../api/client";
import { useAdminChange } from "../../api/useAdminChange";
import { Field, formValues } from "../../components/Field";

export const Route = createFileRoute("/admin/repositories")({ component: Repositories });

type Repository = Schemas["RepositoryView"];

/** The repositories the factory works on: how to build and test them, in which sandbox image. */
function Repositories() {
  const key = ["repositories"] as const;
  const repositories = useQuery({ queryKey: key, queryFn: () => api.get("repositories").json<Repository[]>() }).data;
  const register = useAdminChange(key, (body: unknown) => api.post("repositories", { json: body }), "Dépôt enregistré");
  const update = useAdminChange(key, (r: Repository) => api.put("repositories", { json: {
    key: r.key, active: !r.active,
    settings: { defaultBranch: r.defaultBranch, testCommand: r.testCommand, testReports: r.testReports, sandboxImage: r.sandboxImage },
  } }), "Dépôt mis à jour");
  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const { forge, path, ...settings } = formValues(e.currentTarget);
    register.mutate({ forge, path, settings }, { onSuccess: () => (e.target as HTMLFormElement).reset() });
  };
  return (
    <section className="flex flex-col gap-4">
      <form onSubmit={submit} aria-label="Enregistrer un dépôt" className="grid max-w-4xl grid-cols-3 gap-2 rounded-lg border p-3">
        <Field label="Forge"><select name="forge" className="rounded-md border bg-transparent px-2 py-1.5"><option>GITHUB</option><option>GITLAB</option></select></Field>
        <Field label="Chemin" name="path" required placeholder="acme/api" />
        <Field label="Branche par défaut" name="defaultBranch" required defaultValue="main" />
        <Field label="Commande de test" name="testCommand" required defaultValue="mvn -B verify" />
        <Field label="Rapports JUnit" name="testReports" required defaultValue="**/target/surefire-reports/*.xml" />
        <Field label="Image sandbox" name="sandboxImage" required defaultValue="factory-sandbox" />
        <button className="col-span-3 w-fit rounded-md bg-foreground px-3 py-1.5 text-background">Enregistrer</button>
      </form>
      <table className="w-full max-w-5xl text-left text-xs">
        <thead className="text-muted-foreground">
          <tr><th className="py-1">Dépôt</th><th>Branche</th><th>Tests</th><th>Rapports</th><th>Image</th><th /></tr>
        </thead>
        <tbody>
          {repositories?.map((r) => (
            <tr key={r.key} className={`border-t ${r.active ? "" : "opacity-60"}`}>
              <td className="py-1.5">{r.key}</td><td>{r.defaultBranch}</td><td className="font-mono">{r.testCommand}</td>
              <td className="font-mono">{r.testReports}</td><td className="font-mono">{r.sandboxImage}</td>
              <td><button onClick={() => update.mutate(r)} className="rounded-md border px-2 py-0.5 hover:bg-accent">{r.active ? "Désactiver" : "Activer"}</button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
