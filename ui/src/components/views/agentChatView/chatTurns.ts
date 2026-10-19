import type { ChatMessageVO } from "../../../types/index.ts";
import type { ChatMessageMetadataWithRun, RunSummaryView } from "../../../types/execution.ts";

export interface ChatTurn {
  key: string;
  question?: ChatMessageVO;
  answers: ChatMessageVO[];
  traceMessages: ChatMessageVO[];
  tools: ChatMessageVO[];
  systems: ChatMessageVO[];
  run?: RunSummaryView;
}

export function isReadableAnswer(message: ChatMessageVO): boolean {
  return message.role === "assistant" && !!message.content?.trim();
}

/** Link current runs by server IDs, regardless of snapshot/SSE arrival order.
 * Unassociated legacy messages remain chronological; their tools are disclosed
 * separately instead of inventing a question/run association from adjacency. */
export function buildTurns(messages: ChatMessageVO[], runs: RunSummaryView[]): ChatTurn[] {
  const byMessage = new Map<string, RunSummaryView>();
  const byRun = new Map(runs.map((run) => [run.runId, run]));
  for (const run of runs) {
    if (run.triggeringMessageId) byMessage.set(run.triggeringMessageId, run);
    if (run.answerMessageId) byMessage.set(run.answerMessageId, run);
  }
  const byKey = new Map<string, { turn: ChatTurn; order: number }>();
  const indexes = new Map(messages.map((message, index) => [message.id, index]));
  const obtain = (key: string, order: number, run?: RunSummaryView) => {
    let entry = byKey.get(key);
    if (!entry) {
      entry = { turn: {key, answers: [], traceMessages: [], tools: [], systems: [], run}, order };
      byKey.set(key, entry);
    }
    return entry.turn;
  };
  messages.forEach((message, index) => {
    const metadataRunId = (message.metadata as ChatMessageMetadataWithRun | undefined)?.runId;
    const run = byMessage.get(message.id) ?? (metadataRunId ? byRun.get(metadataRunId) : undefined);
    const runId = run?.runId ?? metadataRunId;
    const key = run?.triggeringMessageId ? `turn-${run.triggeringMessageId}`
      : runId ? `run-${runId}` : `message-${message.id}`;
    const order = run?.triggeringMessageId ? indexes.get(run.triggeringMessageId) ?? index : index;
    const turn = obtain(key, order, run);
    if (message.role === "user") turn.question = message;
    else if (message.role === "system") turn.systems.push(message);
    else {
      if (isReadableAnswer(message)) turn.answers.push(message);
      const traceTurn = runId ? turn : obtain("unassociated-tools", messages.length);
      if (message.role === "tool") traceTurn.tools.push(message);
      else if (message.metadata?.toolCalls?.length) traceTurn.traceMessages.push(message);
    }
  });
  return [...byKey.values()].sort((a, b) => a.order - b.order).map(({turn}) => turn)
    .filter((turn) => turn.question || turn.answers.length || turn.tools.length || turn.traceMessages.length || turn.systems.length);
}
