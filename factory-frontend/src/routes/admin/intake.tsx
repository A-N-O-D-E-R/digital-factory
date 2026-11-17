import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { format } from "date-fns";
import type { FormEvent } from "react";
import { Field, formValues } from "../../components/Field";
import { RepositoryChoice } from "../../components/ImportTicket";
import { api, type Schemas } from "../../api/client";
import { useAdminChange } from "../../api/useAdminChange";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from "@/components/ui/table";

export const Route = createFileRoute("/admin/intake")({ component: Intake });

/** Tickets become work orders: by hand from a reference, or found by a rule's pass. */
function Intake() {
  const rules = useQuery({ queryKey: ["intake"], queryFn: () => api.get("intake-rules").json<Schemas["RuleView"][]>() }).data;
  const repositories = useQuery({ queryKey: ["repositories"], queryFn: () => api.get("repositories").json<Schemas["RepositoryView"][]>() }).data;
  const tickets = useQuery({ queryKey: ["intake", "tickets"], queryFn: () => api.get("tickets").json<Schemas["ImportedView"][]>() }).data;
  const poll = useAdminChange(["intake"], (id: string) => api.post(`intake-rules/${id}/poll`), "Passe effectuée");
  const importTicket = useAdminChange(["intake"], (json: { ticket: string; repository?: string; reimport?: boolean }) =>
    api.post("tickets/import", { json }), "Ticket importé");
  const save = useAdminChange(["intake"], ({ id, ...body }: Record<string, unknown>) =>
    id ? api.put(`intake-rules/${id}`, { json: body }) : api.post("intake-rules", { json: body }), "Règle enregistrée");
  const ruleSettings = (r: Schemas["RuleView"]) => ({ source: r.source, project: r.project, filter: r.filter, repository: r.repository,
    workflow: r.workflow, maxRead: r.maxRead, maxCreated: r.maxCreated, enabled: r.enabled });
  const createRule = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const v = formValues(e.currentTarget);
    save.mutate({ ...v, maxRead: Number(v.maxRead), maxCreated: Number(v.maxCreated) }, { onSuccess: () => (e.target as HTMLFormElement).reset() });
  };
  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    importTicket.mutate({ ticket: String(form.get("ticket")), repository: String(form.get("repository")) || undefined });
  };
  return (
    <section className="flex flex-col gap-4">
      <form onSubmit={submit} className="flex gap-2">
        <Input name="ticket" required placeholder="github:acme/api#12, OPS-12 ou URL du ticket" aria-label="Ticket"
               className="w-96" />
        <RepositoryChoice repositories={repositories} />
        <Button variant="outline">Importer</Button>
      </form>
      <h2 className="font-medium">Règles</h2>
      <form onSubmit={createRule} aria-label="Nouvelle règle" className="grid max-w-5xl grid-cols-4 gap-2 rounded-lg border p-3">
        <Field label="Nom" name="name" required pattern="[a-z][a-z0-9\-]{0,62}" />
        <Field label="Source"><NativeSelect size="sm" name="source">
          <option>github</option><option>gitlab</option><option>jira</option><option>notion</option></NativeSelect></Field>
        <Field label="Projet" name="project" required placeholder="acme/api, OPS, id de base Notion" />
        <Field label="Filtre" name="filter" required placeholder="label, JQL, Statut = Prêt" />
        <Field label="Dépôt cible" name="repository" required placeholder="github:acme/api" />
        <Field label="Workflow" name="workflow" placeholder="default" />
        <Field label="Lus au plus" name="maxRead" type="number" min={1} max={100} defaultValue={50} />
        <Field label="Créés au plus par passe" name="maxCreated" type="number" min={1} max={20} defaultValue={5} />
        <Button className="col-span-4 w-fit">Créer la règle</Button>
      </form>
      <Table className="w-full text-left text-xs">
        <TableHeader className="text-muted-foreground"><TableRow><TableHead className="py-1">Règle</TableHead><TableHead>Source</TableHead><TableHead>Filtre</TableHead><TableHead>Dépôt</TableHead><TableHead>Dernière passe</TableHead><TableHead /></TableRow></TableHeader>
        <TableBody>
          {rules?.map((r) => (
            <TableRow key={r.id} className="border-t">
              <TableCell className="py-1.5">{r.name}{!r.enabled && <span className="text-muted-foreground"> (désactivée)</span>}</TableCell>
              <TableCell>{r.source}:{r.project}</TableCell><TableCell className="font-mono">{r.filter}</TableCell><TableCell>{r.repository}</TableCell>
              <TableCell className={r.lastError ? "text-failed" : ""}>{r.lastError ?? (r.lastPolledAt ? format(new Date(r.lastPolledAt), "d MMM HH:mm") : "jamais")}</TableCell>
              <TableCell className="flex gap-1 py-1">
                <Button variant="outline" size="sm" onClick={() => poll.mutate(r.id)}>Lancer une passe</Button>
                <Button variant="outline" size="sm" onClick={() => save.mutate({ id: r.id, ...ruleSettings(r), enabled: !r.enabled })}
>{r.enabled ? "Désactiver" : "Activer"}</Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <h2 className="font-medium">Tickets importés</h2>
      <ul className="flex flex-col divide-y rounded-lg border text-xs">
        {tickets?.map((t) => (
          <li key={t.ticket} className="flex gap-3 p-2">
            <a href={t.url} target="_blank" rel="noreferrer" className="font-mono hover:underline">{t.ticket}</a>
            <Link to="/work-orders/$id" params={{ id: t.workOrderId }} className="hover:underline">work order</Link>
            <span className="ml-auto text-muted-foreground">{format(new Date(t.importedAt), "d MMM HH:mm")}</span>
            <Button variant="outline" size="sm" onClick={() => confirm(`Réimporter ${t.ticket} ? Un nouveau work order est créé.`) && importTicket.mutate({ ticket: t.ticket, reimport: true })}
>Réimporter</Button>
          </li>
        ))}
      </ul>
    </section>
  );
}
