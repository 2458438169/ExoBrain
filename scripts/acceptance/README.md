# End-to-end acceptance scripts

These scripts drive the real HTTP API of a running ExoBrain instance. They use **synthetic
documents only**, read every credential from the environment, and write nothing outside the
acceptance work directory.

| Script | What it checks |
| --- | --- |
| `account-and-admin.ps1` | Administrator sign-in and model catalogue, two ordinary accounts with their own private knowledge bases, upload with real embeddings, private isolation in both directions (including the administrator), a body-only fact answer whose structured citation is re-read from the document API, insufficient-material behaviour, and knowledge-base deletion (history retained, source gone) |
| `guest.ps1 -Phase setup` | Creates the public knowledge base, its document and the demo assistant (run as administrator, before the guest restart) |
| `guest.ps1 -Phase guest` | Visitor-only checks: exactly the configured demo assistant, allowlisted knowledge base and document, readable Markdown source, denial of non-published documents and of upload/delete routes, and a guest answer with a verifiable citation |

## Environment

All values are read from the process environment; none of them belong in the repository.

- `EXOBRAIN_ADMIN_PASSWORD` — bootstrap administrator password of the instance under test.
- `EXOBRAIN_REAL_EMBEDDING_API_KEY` — key of the real Embedding endpoint (only used when the
  catalogue has no usable embedding model yet).
- `EXOBRAIN_ACCEPTANCE_LLM_API_KEY` — key of the real chat model (same condition).
- `EXOBRAIN_IT_*` — optional; used only when the scripts are pointed at the PostgreSQL integration
  database instead of a standalone instance.

The application itself needs the usual local configuration: a reachable PostgreSQL 17 + pgvector
database, `document.storage.base-path`, `app.models.encryption-key-file`,
`app.auth.bootstrap-admin-password-file`, `app.auth.secure-cookie=false` for plain HTTP, plus an
Embedding endpoint and a chat model in the catalogue. For the guest phase, set `app.demo.agent-id`
and `app.demo.public-kb-ids` to the values that `guest.ps1 -Phase setup` wrote to
`acceptance/guest-state.json`, then restart the application.

## Running

```powershell
# 1. start the application with the local configuration described above, then:
.\scripts\acceptance\account-and-admin.ps1 -BaseUrl http://127.0.0.1:18081 -WorkDir $env:PI_SCRATCH_DIR

# 2. guest phase
.\scripts\acceptance\guest.ps1 -Phase setup -BaseUrl http://127.0.0.1:18081 -WorkDir $env:PI_SCRATCH_DIR
#    restart the application with app.demo.agent-id / app.demo.public-kb-ids from guest-state.json
.\scripts\acceptance\guest.ps1 -Phase guest -BaseUrl http://127.0.0.1:18081 -WorkDir $env:PI_SCRATCH_DIR
```

Each script prints `PASS`/`FAIL` per check and exits non-zero if any check fails. The recorded
results of the first-phase run are in
[`docs/development/deliveries/acceptance-phase1.md`](../../docs/development/deliveries/acceptance-phase1.md).

Note for Windows PowerShell 5.1: `.ps1` files that contain Chinese text must keep a UTF-8 BOM,
otherwise Windows PowerShell reads them with the ANSI code page and fails to parse them.
