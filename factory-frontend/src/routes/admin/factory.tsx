import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import type { FormEvent } from "react";
import { api, type Schemas } from "../../api/client";
import { useAdminChange } from "../../api/useAdminChange";

export const Route = createFileRoute("/admin/factory")({ component: Factory });

const usd = (v: number | null | undefined) => (v == null ? "illimité" : `${v.toFixed(2)} $`);

/** Spending bounded (reached budgets pause work, never fail it) and the whole factory paused at once. */
function Factory() {
  const key = ["budgets"] as const;
  const state = useQuery({ queryKey: key, queryFn: () => api.get("budgets").json<Schemas["State"]>() }).data;
  const pause = useAdminChange(key, (paused: boolean) => api.post(paused ? "factory/pause" : "factory/resume"), "Usine mise à jour");
  const save = useAdminChange(key, (body: { dailyUsd: number | null; workOrderUsd: number | null }) => api.put("budgets", { json: body }), "Budgets enregistrés");
  if (!state) {
    return null;
  }
  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const value = (name: string) => (String(f.get(name)).trim() === "" ? null : Number(f.get(name)));
    save.mutate({ dailyUsd: value("dailyUsd"), workOrderUsd: value("workOrderUsd") });
  };
  return (
    <section className="flex max-w-xl flex-col gap-4">
      <div className="flex items-center gap-3 rounded-lg border p-3">
        <span className={`size-2 rounded-full ${state.paused ? "bg-waiting" : "bg-done"}`} />
        <span>{state.paused ? "Usine en pause : aucun run n'est distribué" : "Usine en marche"}</span>
        <button onClick={() => pause.mutate(!state.paused)} className="ml-auto rounded-md border px-3 py-1.5 hover:bg-accent">
          {state.paused ? "Reprendre" : "Mettre en pause"}
        </button>
      </div>
      <p className="text-muted-foreground">Dépensé aujourd'hui : {usd(state.spentTodayUsd)} sur {usd(state.dailyUsd)}</p>
      <form onSubmit={submit} className="flex flex-col gap-2">
        <label className="flex items-center justify-between gap-2">Budget quotidien ($)
          <input name="dailyUsd" type="number" min="0" step="0.01" defaultValue={state.dailyUsd ?? ""} className="w-32 rounded-md border bg-transparent p-1.5" />
        </label>
        <label className="flex items-center justify-between gap-2">Budget par work order ($)
          <input name="workOrderUsd" type="number" min="0" step="0.01" defaultValue={state.workOrderUsd ?? ""} className="w-32 rounded-md border bg-transparent p-1.5" />
        </label>
        <button className="w-fit self-end rounded-md bg-foreground px-3 py-1.5 text-background">Enregistrer</button>
      </form>
    </section>
  );
}
