import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { InputHTMLAttributes, ReactNode } from "react";

/** A labelled input, the forms' one building block. */
export function Field({ label, children, ...input }: { label: string; children?: ReactNode } & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <Label className="flex-col items-start text-xs">
      {label}
      {children ?? <Input {...input} className="text-sm" />}
    </Label>
  );
}

/** The fields of a submitted form, as strings (empty ones left out). */
export function formValues(form: HTMLFormElement): Record<string, string> {
  return Object.fromEntries([...new FormData(form)].map(([k, v]) => [k, String(v).trim()]).filter(([, v]) => v !== ""));
}
