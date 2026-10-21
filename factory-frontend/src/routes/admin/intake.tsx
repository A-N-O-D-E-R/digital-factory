import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { format } from "date-fns";
import type { FormEvent } from "react";
import { Field, formValues } from "../../components/Field";
import { RepositoryChoice } from "../../components/ImportTicket";
import { api, type Schemas } from "../../api/client";
import { useAdminChange } from "../../api/useAdminChange";

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
        <input name="ticket" required placeholder="github:acme/api#12, OPS-12 ou URL du ticket" aria-label="Ticket"
               className="w-96 rounded-md border bg-transparent px-2 py-1.5" />
        <RepositoryChoice repositories={repositories} />
        <button className="rounded-md border px-3 py-1.5 hover:bg-accent">Importer</button>
      </form>
      <h2 className="font-medium">Règles</h2>
      <form onSubmit={createRule} aria-label="Nouvelle règle" className="grid max-w-5xl grid-cols-4 gap-2 rounded-lg border p-3">
        <Field label="Nom" name="name" required pattern="[a-z][a-z0-9\-]{0,62}" />
        <Field label="Source"><select name="source" className="rounded-md border bg-transparent px-2 py-1.5">
          <option>github</option><option>gitlab</option><option>jira</option><option>notion</option></select></Field>
        <Field label="Projet" name="project" required placeholder="acme/api, OPS, id de base Notion" />
        <Field label="Filtre" name="filter" required placeholder="label, JQL, Statut = Prêt" />
        <Field label="Dépôt cible" name="repository" required placeholder="github:acme/api" />
        <Field label="Workflow" name="workflow" placeholder="default" />
        <Field label="Lus au plus" name="maxRead" type="number" min={1} max={100} defaultValue={50} />
        <Field label="Créés au plus par passe" name="maxCreated" type="number" min={1} max={20} defaultValue={5} />
        <button className="col-span-4 w-fit rounded-md bg-foreground px-3 py-1.5 text-background">Créer la règle</button>
      </form>
      <table className="w-full text-left text-xs">
        <thead className="text-muted-foreground"><tr><th className="py-1">Règle</th><th>Source</th><th>Filtre</th><th>Dépôt</th><th>Dernière passe</th><th /></tr></thead>
        <tbody>
          {rules?.map((r) => (
            <tr key={r.id} className="border-t">
              <td className="py-1.5">{r.name}{!r.enabled && <span className="text-muted-foreground"> (désactivée)</span>}</td>
              <td>{r.source}:{r.project}</td><td className="font-mono">{r.filter}</td><td>{r.repository}</td>
              <td className={r.lastError ? "text-failed" : ""}>{r.lastError ?? (r.lastPolledAt ? format(new Date(r.lastPolledAt), "d MMM HH:mm") : "jamais")}</td>
              <td className="flex gap-1 py-1">
                <button onClick={() => poll.mutate(r.id)} className="rounded-md border px-2 py-0.5 hover:bg-accent">Lancer une passe</button>
                <button onClick={() => save.mutate({ id: r.id, ...ruleSettings(r), enabled: !r.enabled })}
                        className="rounded-md border px-2 py-0.5 hover:bg-accent">{r.enabled ? "Désactiver" : "Activer"}</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <h2 className="font-medium">Tickets importés</h2>
      <ul className="flex flex-col divide-y rounded-lg border text-xs">
        {tickets?.map((t) => (
          <li key={t.ticket} className="flex gap-3 p-2">
            <a href={t.url} target="_blank" rel="noreferrer" className="font-mono hover:underline">{t.ticket}</a>
            <Link to="/work-orders/$id" params={{ id: t.workOrderId }} className="hover:underline">work order</Link>
            <span className="ml-auto text-muted-foreground">{format(new Date(t.importedAt), "d MMM HH:mm")}</span>
            <button onClick={() => confirm(`Réimporter ${t.ticket} ? Un nouveau work order est créé.`) && importTicket.mutate({ ticket: t.ticket, reimport: true })}
                    className="rounded-md border px-2 py-0.5 hover:bg-accent">Réimporter</button>
          </li>
        ))}
      </ul>
    </section>
  );
}
