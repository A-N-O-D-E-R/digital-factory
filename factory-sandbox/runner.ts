// The runner of agent sandboxes. Reads the job the worker wrote (/job/job.json), runs the role
// once with the Claude Agent SDK in /workspace, and leaves /out/result.json for the worker:
// {output, costUsd} or {error, costUsd}. Git, bundles and the control plane are the worker's.
import { query, type Options } from "@anthropic-ai/claude-agent-sdk";
import { readFileSync, writeFileSync } from "node:fs";

type Job = {
  task: string;
  input: {
    writesCode?: boolean;
    settings: { modelId: string; maxTurns: number; effort: Options["effort"] };
    prompt: { version: number; text: string };
    [key: string]: unknown;
  };
  outputSchema: Record<string, unknown>;
  budgetUsd: number;
};

type Result = { output: unknown; costUsd: number | null } | { error: string; costUsd: number | null };

const READ = ["Read", "Glob", "Grep"];

/** Tools per role: the classifier only reads; the others run commands; code writers edit. */
export function tools(job: Job): string[] {
  if (job.task === "classifier") return READ;
  return job.input.writesCode ? [...READ, "Bash", "Write", "Edit"] : [...READ, "Bash"];
}

export function options(job: Job, workspace: string): Options {
  const allowed = tools(job);
  return {
    cwd: workspace,
    settingSources: [], // nothing from the repository (CLAUDE.md, settings) configures the agent
    permissionMode: "dontAsk",
    tools: allowed,
    allowedTools: allowed,
    systemPrompt: job.input.prompt.text,
    model: job.input.settings.modelId,
    effort: job.input.settings.effort,
    maxTurns: job.input.settings.maxTurns,
    maxBudgetUsd: job.budgetUsd,
    outputFormat: { type: "json_schema", schema: forSdk(job.outputSchema) },
  };
}

/** Claude Code knows no 2020-12 meta-schema: the declaration goes; the backend validates the output in full. */
export function forSdk(schema: Record<string, unknown>): Record<string, unknown> {
  const { $schema: _, ...rest } = schema;
  return rest;
}

/** Everything but the prompt is data: the work order, the earlier steps' outputs, the facts. */
export function prompt(job: Job): string {
  const { prompt: _, settings: __, ...data } = job.input;
  return `<untrusted_input>\n${JSON.stringify(data, null, 2)}\n</untrusted_input>\n\n`
    + `Do the work of the ${job.task} in this repository, then answer with the structured output.`;
}

export async function run(job: Job, workspace: string): Promise<Result> {
  let cost: number | null = null;
  try {
    for await (const message of query({ prompt: prompt(job), options: options(job, workspace) })) {
      if (message.type !== "result") continue;
      cost = message.total_cost_usd ?? null;
      const structured = (message as { structured_output?: unknown }).structured_output;
      if (!message.is_error && structured != null) return { output: structured, costUsd: cost };
      const errors = (message as { errors?: { message?: string; type: string }[] }).errors ?? [];
      const text = (message as { result?: string }).result;
      return {
        error: `${job.task} ended without an output (${message.subtype})`
          + errors.map((e) => `: ${e.message ?? e.type}`).join("") + (text ? `: ${text.slice(0, 2000)}` : ""),
        costUsd: cost,
      };
    }
    return { error: `${job.task} ended without a result`, costUsd: cost };
  } catch (e) {
    return { error: `${job.task} failed: ${e instanceof Error ? e.message : String(e)}`, costUsd: cost };
  }
}

if (import.meta.main) {
  const job: Job = JSON.parse(readFileSync(`${process.env.JOB_DIR ?? "/job"}/job.json`, "utf8"));
  const result = await run(job, process.env.WORKSPACE ?? "/workspace");
  writeFileSync(`${process.env.OUT_DIR ?? "/out"}/result.json`, JSON.stringify(result));
}
