import { Trash2 } from "lucide-react";
import { useState } from "react";
import {
  ACTIONS, type Condition, describe, FACTS, renameStep, ROLES, type Step, TERMINALS, type Transition, typed, updateStep, what, without,
  type WorkflowDefinition,
} from "../lib/workflow";
import { Button } from "@/components/ui/button";

const input = "rounded-md border bg-transparent px-2 py-1";
const PARALLEL = "run-tests + secret-scan";

/** A condition as a fact compared to a value, or as JSON for combinations (allOf, anyOf, not). */
function ConditionField({ value, onChange, label }: { value?: Condition; onChange: (c?: Condition) => void; label: string }) {
  const simple = !value || ("fact" in value && ("equals" in value || "in" in value));
  const [raw, setRaw] = useState<string>();
  if (!simple || raw !== undefined) {
    const text = raw ?? JSON.stringify(value);
    const parsed = (() => { try { return JSON.parse(text) as Condition; } catch { return undefined; } })();
    return (
      <input aria-label={label} value={text} spellCheck={false} onChange={(e) => setRaw(e.target.value)}
             onBlur={() => { if (parsed) { onChange(parsed); setRaw(undefined); } }}
             className={`${input} font-mono text-xs ${parsed ? "" : "border-failed"}`} />
    );
  }
  const fact = value && "fact" in value ? value : undefined;
  const set = (f: string, op: string, v: string) => onChange(op === "in"
    ? { fact: f, in: v.split(",").map((x) => typed(x.trim())) as unknown[] }
    : { fact: f, equals: typed(v) });
  const op = fact?.in ? "in" : "equals";
  const shown = fact?.in ? fact.in.join(", ") : String(fact?.equals ?? "");
  return (
    <div className="flex gap-1" aria-label={label} role="group">
      <select aria-label="Fait" value={fact?.fact ?? ""} onChange={(e) => set(e.target.value, op, shown)} className={`${input} min-w-0 flex-1`}>
        {!fact && <option value="">toujours</option>}
        {FACTS.map((f) => <option key={f}>{f}</option>)}
      </select>
      {fact && (
        <>
          <select aria-label="Opérateur" value={op} onChange={(e) => set(fact.fact, e.target.value, shown)} className={input}>
            <option value="equals">=</option><option value="in">∈</option>
          </select>
          <input aria-label="Valeur" defaultValue={shown} key={`${fact.fact}${op}`} onBlur={(e) => set(fact.fact, op, e.target.value)}
                 className={`${input} w-20`} />
        </>
      )}
      <Button variant="outline" size="sm" title="Éditer en JSON" onClick={() => setRaw(JSON.stringify(value ?? { allOf: [] }))} className="font-mono text-xs">{"{}"}</Button>
    </div>
  );
}

/** Everything about one step: what it does, where it goes next and when. Every change is a new definition. */
export function StepPanel({ definition, id, onChange, onRemove, onSelect }: {
  definition: WorkflowDefinition; id?: string; onChange: (d: WorkflowDefinition, renamed?: [string, string]) => void;
  onRemove: (id: string) => void; onSelect: (id: string) => void;
}) {
  const step = definition.steps.find((s) => s.id === id);
  if (!step) {
    return (
      <div className="flex flex-col gap-2">
        <p className="text-xs text-muted-foreground">Cliquer une étape pour l'éditer ; tirer d'une poignée à une autre pour la relier.</p>
        {definition.steps.map((s) => (
          <button key={s.id} onClick={() => onSelect(s.id)} className="rounded-lg border p-2 text-left hover:bg-accent">
            <div className="font-medium">{s.id} <span className="font-normal text-muted-foreground">· {what(s)}</span></div>
            <ul className="text-xs text-muted-foreground">
              {s.next.map((t, i) => (
                <li key={i}>→ <span className="text-foreground">{t.to}</span> si {describe(t.when)}
                  {t.maxVisits != null && ` (au plus ${t.maxVisits}×, puis ${t.onExhausted ?? "@blocked"})`}</li>
              ))}
            </ul>
          </button>
        ))}
      </div>
    );
  }
  const change = (c: (s: Step) => Step) => onChange(updateStep(definition, step.id, c));
  const setNext = (i: number, c: (t: Transition) => Transition) => change((s) => ({ ...s, next: s.next.map((t, j) => (j === i ? c(t) : t)) }));
  const targets = [...definition.steps.map((s) => s.id).filter((s) => s !== step.id), ...TERMINALS];
  return (
    <div key={step.id} className="flex flex-col gap-3 text-sm">
      <label className="flex flex-col gap-1 text-xs">Identifiant
        <input aria-label="Identifiant" defaultValue={step.id} className={input} onBlur={(e) => {
          const to = e.target.value.trim();
          if (to === step.id) return;
          if (!/^[a-z][a-z0-9-]{0,62}$/.test(to) || definition.steps.some((s) => s.id === to)) { e.target.value = step.id; return; }
          onChange(renameStep(definition, step.id, to), [step.id, to]);
        }} />
      </label>

      {step.kind === "agent" && (
        <>
          <fieldset className="flex flex-col gap-1 text-xs"><legend className="mb-1">Rôles (en parallèle)</legend>
            <div className="flex flex-wrap gap-1">
              {[...new Set([...ROLES, ...(step.roles ?? [])])].map((r) => {
                const on = step.roles?.includes(r) ?? false;
                return (
                  <button key={r} aria-pressed={on} onClick={() => change((s) => {
                    const roles = on ? (s.roles ?? []).filter((x) => x !== r) : [...(s.roles ?? []), r];
                    return roles.length ? { ...s, roles } : s; // an agent step keeps a role
                  })} className={`rounded-full border px-2 py-0.5 ${on ? "border-working bg-working/10" : "text-muted-foreground"}`}>{r}</button>
                );
              })}
            </div>
          </fieldset>
          {(step.roles?.length ?? 0) > 1 && (
            <label className="flex flex-col gap-1 text-xs">Verdicts combinés
              <select value={step.join ?? "all"} onChange={(e) => change((s) => ({ ...s, join: e.target.value }))} className={input}>
                <option value="all">tous</option><option value="majority">majorité</option><option value="any">au moins un</option>
              </select>
            </label>
          )}
        </>
      )}

      {step.kind === "factory" && (
        <label className="flex flex-col gap-1 text-xs">Action
          <select value={step.actions ? PARALLEL : step.action} className={input} onChange={(e) => change((step) => { const s = without(step, "action", "actions");
            return e.target.value === PARALLEL ? { ...s, actions: ["run-tests", "secret-scan"] } : { ...s, action: e.target.value }; })}>
            {ACTIONS.map((a) => <option key={a}>{a}</option>)}
            <option>{PARALLEL}</option>
          </select>
        </label>
      )}

      {step.kind === "gate" && (
        <>
          <label className="flex flex-col gap-1 text-xs">Décision humaine
            <select value={step.gate} onChange={(e) => change((s) => ({ ...s, gate: e.target.value }))} className={input}>
              <option value="plan">plan</option><option value="final">PR finale</option>
            </select>
          </label>
          <div className="flex flex-col gap-1 text-xs">Attend seulement si
            <ConditionField label="Condition de la barrière" value={step.when}
                            onChange={(when) => change((s) => (when ? { ...s, when } : without(s, "when")))} />
          </div>
        </>
      )}

      <div className="flex flex-wrap gap-3 text-xs">
        <label className="flex items-center gap-1.5">
          <input type="checkbox" checked={step.enabled !== false}
                 onChange={(e) => change((s) => (e.target.checked ? without(s, "enabled") : { ...s, enabled: false }))} /> Active
        </label>
        {definition.start !== step.id && (
          <Button variant="ghost" onClick={() => onChange({ ...definition, start: step.id })} className="underline">Définir comme départ</Button>
        )}
      </div>

      <fieldset className="flex flex-col gap-2 text-xs"><legend className="mb-1 font-medium">Ensuite (première condition vraie)</legend>
        {step.next.map((t, i) => {
          const fallback = i === step.next.length - 1;
          return (
            <div key={i} className="flex flex-col gap-1 rounded-md border p-2">
              <div className="flex items-center gap-1">
                <span>vers</span>
                <select aria-label={`Destination ${i + 1}`} value={t.to} onChange={(e) => setNext(i, (x) => ({ ...x, to: e.target.value }))}
                        className={`${input} min-w-0 flex-1`}>
                  {[...new Set([t.to, ...targets])].map((x) => <option key={x}>{x}</option>)}
                </select>
                {step.next.length > 1 && (
                  <Button variant="ghost" size="icon-sm" aria-label="Retirer la transition" onClick={() => change((s) => ({ ...s, next: s.next.filter((_, j) => j !== i) }))}
><Trash2 className="size-3.5" /></Button>
                )}
              </div>
              {fallback && !t.when ? <span className="text-muted-foreground">sinon (toujours)</span> : (
                <ConditionField label={`Condition ${i + 1}`} value={t.when}
                                onChange={(when) => setNext(i, (x) => (when ? { ...x, when } : without(x, "when")))} />
              )}
              <div className="flex items-center gap-1">
                <span>au plus</span>
                <input type="number" min={1} max={20} aria-label={`Passages max ${i + 1}`} value={t.maxVisits ?? ""} placeholder="∞"
                       onChange={(e) => setNext(i, (x) => (e.target.value ? { ...x, maxVisits: Number(e.target.value) } : without(x, "maxVisits", "onExhausted")))}
                       className={`${input} w-14`} />
                {t.maxVisits != null && (
                  <>
                    <span>×, puis</span>
                    <select value={t.onExhausted ?? "@blocked"} onChange={(e) => setNext(i, (x) => ({ ...x, onExhausted: e.target.value }))}
                            className={`${input} min-w-0 flex-1`}>
                      {targets.map((x) => <option key={x}>{x}</option>)}
                    </select>
                  </>
                )}
              </div>
            </div>
          );
        })}
        <Button variant="outline" size="sm" onClick={() => change((s) => ({ ...s, next: [...s.next.slice(0, -1), { to: "@done", when: { fact: "verdict", equals: "approved" } }, ...s.next.slice(-1)] }))}
                className="border-dashed">+ transition</Button>
      </fieldset>

      <button onClick={() => onRemove(step.id)} className="flex items-center justify-center gap-1.5 rounded-md border border-failed/40 py-1.5 text-failed hover:bg-failed/10">
        <Trash2 className="size-4" /> Supprimer l'étape
      </button>
    </div>
  );
}
