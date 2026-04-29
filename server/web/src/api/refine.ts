import { streamGenerate } from "./inference";
import type { Message } from "./sync";

/**
 * Mirrors `SummarisationPrompts.buildProjectContextRefinePrompt` from the Android app so refined
 * outputs look the same regardless of where the user pressed the button.
 */
const REFINE_SYSTEM = `You rewrite project context blocks. The user supplies a free-form set of \
notes the assistant should always know about a project. Your job: tighten it, remove duplication, \
group related facts under short headings, and drop filler. Preserve every concrete fact, name, \
and constraint. Output the rewritten block only — no preamble, no markdown fences, no commentary.`;

const REFINE_USER_TMPL = (raw: string) => `Refine this project context:\n\n${raw}`;

const COMPRESS_SYSTEM = `You compress a chat transcript into project memory: durable facts, \
decisions, and preferences worth remembering across future chats. Skip pleasantries and the \
content of one-off questions. Output a short markdown block with bullet points grouped under \
2-4 short headings. No preamble, no closing line.`;

const COMPRESS_USER_TMPL = (raw: string) => `Compress this for memory:\n\n${raw}`;

export async function refineText(opts: {
  raw: string;
  model: string;
  numCtx?: number;
  signal?: AbortSignal;
  onProgress?: (partial: string) => void;
  kind?: "context" | "memory";
}): Promise<string> {
  const isMemory = opts.kind === "memory";
  const fakeMsg: Message = {
    remote_id: "tmp",
    chat_remote_id: "tmp",
    role: "user",
    content: isMemory ? COMPRESS_USER_TMPL(opts.raw) : REFINE_USER_TMPL(opts.raw),
    token_count: 0,
    created_at: Date.now(),
    deleted_at: null,
  };
  let buf = "";
  await streamGenerate(
    {
      systemPrompt: isMemory ? COMPRESS_SYSTEM : REFINE_SYSTEM,
      messages: [fakeMsg],
      model: opts.model,
      numCtx: opts.numCtx,
      applyDefaultPreamble: false,
      maxTokens: 2000,
    },
    (token) => {
      buf += token;
      opts.onProgress?.(buf);
    },
    () => {},
    opts.signal,
  );
  return buf.trim();
}
