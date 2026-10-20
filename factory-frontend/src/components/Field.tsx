import type { InputHTMLAttributes, ReactNode } from "react";

/** A labelled input, the forms' one building block. */
export function Field({ label, children, ...input }: { label: string; children?: ReactNode } & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label className="flex flex-col gap-1 text-xs">
      {label}
      {children ?? <input {...input} className="rounded-md border bg-transparent px-2 py-1.5 text-sm" />}
    </label>
  );
}

/** The fields of a submitted form, as strings (empty ones left out). */
export function formValues(form: HTMLFormElement): Record<string, string> {
  return Object.fromEntries([...new FormData(form)].map(([k, v]) => [k, String(v).trim()]).filter(([, v]) => v !== ""));
}
