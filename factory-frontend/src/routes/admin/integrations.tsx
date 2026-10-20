import { useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { format } from "date-fns";
import { api, type Schemas } from "../../api/client";

export const Route = createFileRoute("/admin/integrations")({ component: Integrations });

/** Each integration connected or failing with its error; credentials never shown, only whether they work. */
function Integrations() {
  const client = useQueryClient();
  const integrations = useQuery({
    queryKey: ["integrations"],
    queryFn: () => api.get("integrations").json<Schemas["Status"][]>(),
  });
  const refresh = () => client.fetchQuery({
    queryKey: ["integrations"],
    queryFn: () => api.get("integrations", { searchParams: { refresh: true } }).json<Schemas["Status"][]>(),
  });
  return (
    <section className="flex max-w-3xl flex-col gap-3">
      <button onClick={() => void refresh()} className="w-fit rounded-md border px-3 py-1.5 hover:bg-accent">Vérifier à nouveau</button>
      <ul className="flex flex-col divide-y rounded-lg border">
        {integrations.data?.map((i) => (
          <li key={i.name} className="flex items-start gap-3 p-3">
            <span className={`mt-1 size-2 shrink-0 rounded-full ${!i.configured ? "bg-idle" : i.ok ? "bg-done" : "bg-failed"}`} />
            <div className="flex flex-col">
              <span className="font-medium capitalize">{i.name}</span>
              <span className="text-muted-foreground">
                {!i.configured ? "Non configurée" : i.ok ? i.detail : `En échec : ${i.error}`}
              </span>
            </div>
            {i.checkedAt && <time className="ml-auto text-xs text-muted-foreground">{format(new Date(i.checkedAt), "HH:mm:ss")}</time>}
          </li>
        ))}
      </ul>
    </section>
  );
}
