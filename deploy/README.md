# ExoBrain production deployment

Both access modes remain available:

- [Guest access / 游客免登录](https://exobrain.sharmoon.cc.cd/)
- [Account and password / 账号密码登录（管理模式）](https://exobrain.sharmoon.cc.cd/admin/)

The 2026-10-14 release uses application image
`exobrain:20261014-md-preview`, frontend release `20261014-md-preview`, Java 21,
and timezone `Asia/Shanghai`. The backend uses Spring Boot 3.5.8, Spring AI 1.1.0,
MyBatis and PostgreSQL 17 with pgvector; the UI uses React 19, TypeScript and
Ant Design. This guide contains reusable configuration, not production credentials.

The templates use `/opt/exobrain` as the installation directory; adjust matching
Nginx paths if choosing another directory. In the supplied configuration Nginx
and the application bind to loopback, and PostgreSQL has no published port.
Route the public hostname through the configured HTTPS gateway / Cloudflare
Tunnel; keep the internal application and database behind that gateway.

## Public experience and administration

The same UI source is built twice, with separate asset directories and API bases.

The password-protected mode uses **Nginx HTTP Basic**, normally shown as a browser
username/password prompt. It grants access to the shared management workspace;
it is not an application account system with registration, personal account
profiles or per-login data isolation. Different Basic usernames do not create
separate application data scopes. Visitor-cookie isolation does not limit an
authenticated administrator's management access.

| Route | Access | Purpose |
| --- | --- | --- |
| `/` | Public | Project demo, public knowledge-base overview, personal chats and example conversations |
| Explicit routes under `/api/demo/` | Public, subject to backend ownership and resource checks | Demo agent, public documents, visitor chats and curated read-only history |
| `GET /api/demo/documents/<document-uuid>/content` | Public knowledge bases only | Read-only Markdown source from an allowlisted knowledge base |
| `/sse/demo/connect/<session-uuid>` | Owning visitor only | Live updates for that visitor's editable chat |
| `/admin/` | HTTP Basic authentication | Administration UI; `/admin` redirects here |
| `/admin/api/`, `/admin/sse/` | HTTP Basic authentication | Administration UI's API and SSE routes |
| `/api/`, `/sse/` outside the published demo routes | HTTP Basic authentication | Existing administration API and SSE routes |

SSE proxy buffering is disabled. The application sends comment heartbeats every
15 seconds so slow model responses do not leave the Cloudflare connection idle.
Credentials are provisioned outside Git in the installation's `secrets/`
directory; do not include them in frontend builds or container images.
`application-dev.yaml` is excluded from packaged JARs. Enabling guest access
must not remove authentication from `/admin/` or the management API / SSE routes.

## Build

Use JDK 21 for both compilation and runtime; set `JAVA_HOME` and `PATH` to the
installed JDK 21 before running the included Maven wrapper (Maven 3.9.11).
The selection below is the reusable backend regression command. The separate
model example tests call external models and are not part of this routine build.
Previously recorded release results are listed under [Verification record](#verification-record);
the commands here are instructions, not a claim that tests were rerun during this
documentation update.

Run from the repository root in PowerShell:

```powershell
Set-Location jchatmind
.\mvnw.cmd clean package '-Dtest=JchatmindApplicationTests,JChatMindFactoryTest,SseServiceImplTest,Document*Test,RagServiceImplTest,ProductionToolsTest,Demo*Test'

Set-Location ../ui
npm ci
npm run lint

$env:VITE_PUBLIC_DEMO = 'true'
$env:VITE_API_BASE_URL = '/api/demo'
$env:VITE_SSE_BASE_URL = '/sse/demo'
npm run build -- --base=/ --outDir=dist-public

$env:VITE_PUBLIC_DEMO = 'false'
$env:VITE_API_BASE_URL = '/admin/api'
$env:VITE_SSE_BASE_URL = '/admin/sse'
npm run build -- --base=/admin/ --outDir=dist-admin
```

These `VITE_*` values are compiled into the assets. Changing a container
environment variable after building cannot switch a public build into an
administration build. The `/admin/` asset base also sets the router basename.

The `Document*Test` selection includes the document facade and preview tests;
`Demo*Test` includes the public route and resource-access checks. The results for
this release are recorded in the [verification record](#verification-record).

Build `exobrain:20261014-md-preview` using `deploy/Dockerfile` and a clean staging
context containing the resulting JAR as `app.jar`. The image runs Temurin 21 as
UID 10001. Install the contents of the two frontend output directories as follows:

```text
/opt/exobrain/releases/20261014-md-preview/
  ui/       <- ui/dist-public/ contents
  admin/    <- ui/dist-admin/ contents
```

The `current` symlink selects this release directory. Nginx serves the public UI
from `current/ui` and the administration UI from `current/admin`. Switch both
builds together to avoid mismatched assets and API routes.

Set `services.app.image` in the installation's Compose configuration to
`exobrain:20261014-md-preview`. Deploy the application image, updated Nginx path
allowlist and both frontend builds together so Markdown preview requests reach
the matching backend endpoints.

The session list, conversation header and curated examples display the Agent
associated with each saved `agentId`, using the current authorized Agent catalog,
on desktop and mobile. This is not a historical snapshot of the Agent's name or
configuration. Missing associations, unavailable Agents and catalog loading
failures have distinct labels; no default Agent is substituted. If an association
is missing or removed, history remains readable and the conversation cannot
submit messages with an empty Agent ID. A catalog-name lookup failure alone does
not disable a conversation that still has a saved Agent ID.

## Public resources and retained history

Configure the demo publication lists in the private production YAML. The UUIDs
below are placeholders: replace them with existing, deliberately published
records. This example contains no model, database or gateway credentials.

```yaml
app:
  demo:
    agent-id: "11111111-1111-4111-8111-111111111111"
    public-kb-ids:
      - "22222222-2222-4222-8222-222222222222"
    showcase-session-ids:
      - "33333333-3333-4333-8333-333333333333"
```

Start with `showcase-session-ids: []` until the selected sessions have completed
and been reviewed. The demo agent's `allowed_kbs` must be a subset of
`public-kb-ids`. Management selections in `allowed_tools` are preserved in the
database and continue to apply to administration runs. Public requests clear
only their freshly loaded runtime DTO's optional-tool list; the executor repeats
this restriction after loading its own configuration. Selecting database, email
or other tools in administration therefore does not disable the public agent or
enable those tools for visitors. The isolated demo executor registers only
`KnowledgeTool`, `getDate`, `directAnswer` and `terminate`; it does not register
file, database, email, city or weather tools.
The application and JVM timezone are `Asia/Shanghai`, including the date tool.

Create a dedicated knowledge base containing only reviewed, public Markdown
project documentation. The repository includes the three documents used by the
current public knowledge base:

- [Project overview](../docs/public-demo/01_exobrain_overview.md)
- [RAG principles](../docs/public-demo/02_rag_principles.md)
- [Java backend questions](../docs/public-demo/03_java_backend_faq.md)

Upload only these three content documents. The [test checklist](../docs/public-demo/99_test_questions.md)
and [demo Agent system prompt](../docs/public-demo/agent-system-prompt.md) are
maintenance references and must not be indexed as knowledge-base content. The
published baseline contains 18 indexed sections with 1024-dimensional vectors.
The current parser embeds section titles and stores their bodies; it does not
perform whole-document embedding or automatic PDF/Word parsing.

Do not copy private knowledge bases or production configuration into the public
knowledge base. Listing or uploading a file elsewhere does not publish it:
the knowledge base must be explicitly allowlisted. Public document **list**
responses contain identifiers, filenames, types and sizes; storage paths and
document metadata are omitted. The separate content endpoint below returns
Markdown source only for documents in an allowlisted public knowledge base.
Knowledge retrieval checks the public knowledge-base ID again when the tool
executes.

## Markdown preview

Click a Markdown filename or the preview action in a knowledge base's document
list to open the read-only viewer. Users can switch between rendered Markdown
and its source. Both access modes support original UTF-8 `.md` and `.markdown`
files, with case-insensitive extensions and a maximum file size of **2 MiB
(2,097,152 bytes)**. Preview does not edit the file or change its vector index.

- `GET /api/demo/documents/<document-uuid>/content` reads documents whose actual
  knowledge-base ID belongs to `app.demo.public-kb-ids`. The backend checks that
  membership before reading the stored file.
- `GET /api/documents/<document-uuid>/content` and its gateway alias
  `/admin/api/documents/<document-uuid>/content` use the existing HTTP Basic
  management access; they do not require publication in the guest knowledge base.

Successful responses use `ApiResponse` with
`data: {documentId, filename, content}`. `content` is the original Markdown text
after removal of an optional UTF-8 BOM, and `filename` is a display filename
without a storage path. Internal file paths
and document storage metadata are not exposed by these responses. Reading a
document by UUID does not grant guests access to private knowledge bases.

The HTTP status and JSON `code` agree on preview errors. Missing documents or
unavailable or unsafe source paths return 404; the public route also returns 404
for documents outside its publication list. Files larger than 2 MiB return 413,
unsupported formats return 415, and invalid UTF-8 returns 422.

The Nginx public allowlist includes only the exact
`/api/demo/documents/<document-uuid>/content` path for this feature, with a canonical
UUID in `8-4-4-4-12` hexadecimal form. Do not expose an upload route, a general
documents prefix or the document storage directory to enable preview.

## Visitor history and example conversations

Personal chat history is stored in PostgreSQL and scoped to the browser's
`exobrain_demo` cookie. The cookie is HttpOnly, uses Secure on HTTPS, uses
SameSite=Lax and lasts 30 days; the database stores its ownership hash. The public
UI establishes this cookie before mounting the consumers that load chat data.
The same cookie can reload its own history after an application restart. A
different browser, a cleared cookie or a new identity after expiry cannot list,
read, modify, execute or subscribe to those chats. Losing the cookie does not
itself delete the database records. Visitors' tests are not automatically
published to other visitors or converted into per-account private history.

The current guest limits include 20 retained sessions per visitor, messages up
to 4000 characters, one in-flight execution per session, and per-visitor message
rate limits of at least 3 seconds between sends and at most 60 per hour. Keep both
the application limits and the gateway message-rate limit when updating the
deployment.

To publish example conversations, create sessions through `/api/demo/chat-sessions`
and ask questions about the public project documents. Review the actual questions,
answers and tool results, then add only those session UUIDs to
`showcase-session-ids`. The public gallery labels these records “示例对话”
(example conversations); they demonstrate actual runs against the public project
documents. The session must belong to the
configured demo agent and have visitor ownership; administration-created history
cannot be published through this route.

- `GET /api/demo/showcase` returns `{showcases: [{id, agentId, title}]}`.
- `GET /api/demo/showcase/<session-uuid>` returns `{id, agentId, title, chatMessages}`.

Both responses use the normal `ApiResponse` envelope. System messages are
excluded. Tool calls and results remain visible only for the safe tool names,
and knowledge-tool arguments must reference an allowlisted public knowledge base.
Published examples are read-only, including for their original visitor: sending,
renaming, deletion and SSE subscription are denied. Start a new personal chat to
try an example yourself. These endpoints never expose a site-wide history list.

## Gateway path checks

Keep the strict checks in `nginx.conf` when deploying or changing routes. Nginx
and Tomcat normalize special URL paths differently. The gateway rejects matrix
parameters and dangerous encoded path forms, including `;`, `%3b`, `%5c` and
nested encoding via `%25`, before anonymous proxying. For example,
`/api/demo/..;/agents` must be rejected rather than reaching `/api/agents`.

Anonymous API and SSE requests must also match the explicit normalized-path
allowlists. The gateway forwards the normalized `$uri` and the query string;
unrecognized public paths return 404. UUID paths are restricted before they reach
the backend, which still enforces visitor ownership and publication lists.

**When adding a public API, update the corresponding Nginx path allowlist as well
as the backend route.** Keep `/api/demo/chat-messages` under its dedicated rate
limit. Do not replace the allowlist with an unrestricted anonymous prefix proxy.
Verify ordinary demo requests, unknown paths, encoded traversal attempts and
unauthenticated administration requests before publishing a gateway change.
For preview, also check public and private document UUIDs, malformed and missing
IDs, unsupported formats, invalid UTF-8, the 2 MiB boundary, and denied write
methods. Confirm that both management content routes still require authentication
and that errors do not disclose storage paths.

## Verification record

The `20261014-md-preview` release on 2026-10-14 completed the following checks:

- The selected backend regression suite reported 59 tests: 56 passed, no failures
  or errors, and 3 skipped because this Windows environment could not create
  symbolic links.
- A separate storage probe loaded the implementation from the actual release JAR
  in an isolated Linux Java 21 container. All 12 checks passed without skips,
  confirming `SecureDirectoryStream` support and covering UTF-8/BOM decoding,
  the exact 2 MiB limit and overflow, invalid UTF-8, traversal, absolute and
  cross-document paths, file and directory symbolic links, unsupported formats,
  and missing files.
- Frontend lint, TypeScript checks and both public / administration builds passed.
  Local browser fixtures checked the 375px layout, complete 2 MiB source display,
  rendered/source switching, safe HTML and link handling, canceled and late
  responses, error retry, and retained administration controls.
- The candidate gateway and the published gateway each passed 33 preview and
  access checks through loopback. All three public Markdown documents matched
  their original disk text through both the guest and authenticated management
  APIs. Existing messages and private runtime settings were verified unchanged.
- Public HTTPS verification passed all 33 preview checks, including exact source
  equality for all three public documents through guest and management APIs,
  `Cache-Control: no-store`, authentication and publication-list enforcement.
  The existing public-demo HTTPS regression suite was also rerun: all 35 checks
  passed, covering read-only examples, visitor isolation, private-history denial
  and administration authentication.
- Real Chrome checks passed for both guest and administration preview: rendered
  original Markdown, character-for-character equality between the source view
  and API content, Escape to close, and retained upload / delete controls in
  administration. The guest view had no horizontal overflow at 390 × 844. Both
  browser sessions recorded zero console errors and zero warnings.

No unpublished document instance existed in this deployment during verification,
so denial for an actual private document was not exercised live. Backend tests
cover that permission boundary.

The earlier 2026-10-14 deployment baseline and the subsequent
`20261014-agent-labels-r2` frontend update recorded these historical results:

- The selected backend regression suite passed 34 tests without failures or errors.
- Frontend lint, type checking and both public / administration builds passed.
- Gateway checks covered 26 cases; public HTTPS checks covered 32 cases, including
  authentication, visitor separation, restricted resources and read-only examples.
- Browser checks covered real knowledge retrieval, visible tool calls, history
  reload, curated examples, and a 390 × 844 mobile viewport.
- The Agent-label update additionally checked different saved Agent associations,
  missing-association labels and disabled sending, without a database migration.

Revalidate affected behavior after subsequent code or deployment changes using
the commands and route checks in this guide.

## Runtime and updates

`compose.yaml` starts PostgreSQL 17 with pgvector and the application. Separate
persistent volumes hold SQL data and documents. The application uses a
non-superuser database role and a non-root container user. The `prod` profile
disables demonstration city/weather tools, which return a fixed city and simulated
weather rather than live location or weather data. Optional database and email tools
remain administration capabilities, isolated from the public runtime.

Provision these files before the first start:

- `secrets/db-password`: PostgreSQL bootstrap password; root:999, mode 440.
- `secrets/app-db-password`: application DB password; root:999, mode 440.
- `secrets/application-prod.yaml`: private application, model and embedding configuration; 10001:10001, mode 400.
- `secrets/htpasswd`: administration gateway password hash; root:www-data, mode 640.
- `init.sql`: application schema from `db/jchatmind.sql`, with optional tools disabled in the seed agent.

Schema and role setup run only when the PostgreSQL data volume is first created.
Never re-run the seed SQL on an existing database or use `down -v` for updates.
The configured embedding model must produce the schema's 1024 dimensions.

Before updates, back up both PostgreSQL and the documents volume. Retain the
previous image, frontend release and gateway configuration for rollback;
rollback must preserve the existing database and document volumes.

```bash
cd /opt/exobrain
docker compose up -d
docker compose ps
nginx -t
# Reload only after nginx -t succeeds.
systemctl reload nginx
systemctl is-active cloudflared
```

After an update, verify the public home and demo catalog without credentials,
administration authentication, a real public knowledge search and date-tool
call, separate-cookie isolation, SSE, and the curated read-only history. Check
Markdown preview and source switching in both the public and management builds,
including rejection of private documents through the public content endpoint.
