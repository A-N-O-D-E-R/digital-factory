import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import type { FormEvent } from "react";
import { api, type Schemas } from "../../api/client";
import { useAdminChange } from "../../api/useAdminChange";
import { Field, formValues } from "../../components/Field";
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/native-select";
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from "@/components/ui/table";

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
        <Field label="Forge"><NativeSelect size="sm" name="forge"><option>GITHUB</option><option>GITLAB</option></NativeSelect></Field>
        <Field label="Chemin" name="path" required placeholder="acme/api" />
        <Field label="Branche par défaut" name="defaultBranch" required defaultValue="main" />
        <Field label="Commande de test" name="testCommand" required defaultValue="mvn -B verify" />
        <Field label="Rapports JUnit" name="testReports" required defaultValue="**/target/surefire-reports/*.xml" />
        <Field label="Image sandbox" name="sandboxImage" required defaultValue="factory-sandbox" />
        <Button className="col-span-3 w-fit">Enregistrer</Button>
      </form>
      <Table className="w-full max-w-5xl text-left text-xs">
        <TableHeader className="text-muted-foreground">
          <TableRow><TableHead className="py-1">Dépôt</TableHead><TableHead>Branche</TableHead><TableHead>Tests</TableHead><TableHead>Rapports</TableHead><TableHead>Image</TableHead><TableHead /></TableRow>
        </TableHeader>
        <TableBody>
          {repositories?.map((r) => (
            <TableRow key={r.key} className={`border-t ${r.active ? "" : "opacity-60"}`}>
              <TableCell className="py-1.5">{r.key}</TableCell><TableCell>{r.defaultBranch}</TableCell><TableCell className="font-mono">{r.testCommand}</TableCell>
              <TableCell className="font-mono">{r.testReports}</TableCell><TableCell className="font-mono">{r.sandboxImage}</TableCell>
              <TableCell><Button variant="outline" size="sm" onClick={() => update.mutate(r)}>{r.active ? "Désactiver" : "Activer"}</Button></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </section>
  );
}
