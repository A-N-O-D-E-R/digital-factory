import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { format } from "date-fns";
import type { FormEvent } from "react";
import { api, type Schemas } from "../../api/client";
import { useAdminChange } from "../../api/useAdminChange";

export const Route = createFileRoute("/admin/intake")({ component: Intake });

/** Tickets become work orders: by hand from a reference, or found by a rule's pass. */
function Intake() {
  const rules = useQuery({ queryKey: ["intake"], queryFn: () => api.get("intake-rules").json<Schemas["RuleView"][]>() }).data;
  const tickets = useQuery({ queryKey: ["intake", "tickets"], queryFn: () => api.get("tickets").json<Schemas["ImportedView"][]>() }).data;
  const poll = useAdminChange(["intake"], (id: string) => api.post(`intake-rules/${id}/poll`), "Passe effectuée");
  const importTicket = useAdminChange(["intake"], (ticket: string) => api.post("tickets/import", { json: { ticket } }), "Ticket importé");
  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    importTicket.mutate(String(new FormData(e.currentTarget).get("ticket")));
  };
  return (
    <section className="flex flex-col gap-4">
      <form onSubmit={submit} className="flex gap-2">
        <input name="ticket" required placeholder="github:acme/api#12, OPS-12 ou URL du ticket" aria-label="Ticket"
               className="w-96 rounded-md border bg-transparent px-2 py-1.5" />
        <button className="rounded-md border px-3 py-1.5 hover:bg-accent">Importer</button>
      </form>
      <h2 className="font-medium">Règles</h2>
      <table className="w-full text-left text-xs">
        <thead className="text-muted-foreground"><tr><th className="py-1">Règle</th><th>Source</th><th>Filtre</th><th>Dépôt</th><th>Dernière passe</th><th /></tr></thead>
        <tbody>
          {rules?.map((r) => (
            <tr key={r.id} className="border-t">
              <td className="py-1.5">{r.name}{!r.enabled && <span className="text-muted-foreground"> (désactivée)</span>}</td>
              <td>{r.source}:{r.project}</td><td className="font-mono">{r.filter}</td><td>{r.repository}</td>
              <td className={r.lastError ? "text-failed" : ""}>{r.lastError ?? (r.lastPolledAt ? format(new Date(r.lastPolledAt), "d MMM HH:mm") : "jamais")}</td>
              <td><button onClick={() => poll.mutate(r.id)} className="rounded-md border px-2 py-0.5 hover:bg-accent">Lancer une passe</button></td>
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
          </li>
        ))}
      </ul>
    </section>
  );
}
