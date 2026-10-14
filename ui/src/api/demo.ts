import { get } from "./http.ts";
import type { ChatSessionVO } from "./api.ts";
import type { ChatMessageVO } from "../types";

let visitorBootstrap: Promise<void> | undefined;

// All public UI consumers mount after this response establishes the HttpOnly
// visitor cookie. Share the request across mounts; failed attempts can be retried.
export function initializeDemoVisitor(): Promise<void> {
  visitorBootstrap ??= get("/agents", undefined, { cache: "no-store" })
    .then(() => undefined)
    .catch((error: unknown) => {
      visitorBootstrap = undefined;
      throw error;
    });
  return visitorBootstrap;
}

export interface DemoShowcase extends ChatSessionVO {
  chatMessages: ChatMessageVO[];
}

export function getDemoShowcases(): Promise<{ showcases: ChatSessionVO[] }> {
  return get("/showcase");
}

export function getDemoShowcase(id: string): Promise<DemoShowcase> {
  return get(`/showcase/${encodeURIComponent(id)}`);
}
