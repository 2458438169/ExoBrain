# Guest (demo) path acceptance: public allowlist, read-only enforcement, body-only retrieval with
# citations for an unauthenticated visitor, and denial of private documents.
#
#   -Phase setup  : runs as the administrator to create the public knowledge base, its document and
#                   the demo assistant; writes their ids to acceptance/guest-state.json
#   -Phase guest  : runs with no session at all (visitor cookie only), after the application has been
#                   restarted with app.demo.agent-id / app.demo.public-kb-ids from that state file
param(
    [string]$BaseUrl = "http://127.0.0.1:18081",
    [string]$WorkDir = $env:PI_SCRATCH_DIR,
    [ValidateSet("setup", "guest")][string]$Phase = "guest"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$root = Join-Path $WorkDir "acceptance"
$api = Join-Path $root "api"
New-Item -ItemType Directory -Force -Path $api | Out-Null
$stateFile = Join-Path $root "guest-state.json"

$script:pass = 0
$script:fail = 0
function Check([string]$name, [bool]$ok, [string]$detail = "") {
    if ($ok) { $script:pass++; Write-Host ("PASS  " + $name + $(if ($detail) { "  [" + $detail + "]" } else { "" })) -ForegroundColor Green }
    else { $script:fail++; Write-Host ("FAIL  " + $name + $(if ($detail) { "  [" + $detail + "]" } else { "" })) -ForegroundColor Red }
}
function Ok($response) { return ($null -ne $response) -and ($null -ne $response.Json) -and ($response.Json.code -eq 200) }
function Short($response) {
    if ($null -eq $response -or -not $response.Raw) { return "" }
    $text = $response.Raw -replace "\s+", " "
    return $text.Substring(0, [Math]::Min(150, $text.Length))
}
function Invoke-Api {
    param([string]$Name, [string]$Method, [string]$Path, $Body = $null,
          [string]$CookieJar = $null, [string]$Csrf = $null, [string]$ExtraArgs = "")
    $bodyFile = Join-Path $api "$Name.json"
    if ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 8 -Compress }
        [System.IO.File]::WriteAllText($bodyFile, $json, (New-Object System.Text.UTF8Encoding($false)))
    }
    $arguments = @("-s", "-S", "-X", $Method, "$BaseUrl$Path", "-H", "Accept: application/json")
    if ($CookieJar) { $arguments += @("-b", $CookieJar, "-c", $CookieJar) }
    if ($Csrf) { $arguments += @("-H", "X-CSRF-Token: $Csrf") }
    if ($null -ne $Body) { $arguments += @("-H", "Content-Type: application/json", "--data-binary", "@$bodyFile") }
    if ($ExtraArgs) { $arguments += ($ExtraArgs -split " ") }
    $raw = & curl.exe @arguments
    $text = ($raw | Out-String).Trim()
    $parsed = $null
    if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { $parsed = $null } }
    return [pscustomobject]@{ CurlExit = $LASTEXITCODE; Raw = $text; Json = $parsed }
}

if ($Phase -eq "setup") {
    "== guest setup (administrator) =="
    $jar = Join-Path $root "cookies-admin-guest.txt"
    if (Test-Path $jar) { Remove-Item $jar }
    $login = Invoke-Api -Name "login-admin-guest" -Method POST -Path "/api/auth/login" -Body @{ username = "admin"; password = $env:EXOBRAIN_ADMIN_PASSWORD } -CookieJar $jar
    $csrf = $null
    if (Ok $login) { $csrf = $login.Json.data.csrfToken }
    Check "administrator login" ([bool]$csrf) (Short $login)

    $docPath = Join-Path $root "public-fact.md"
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# PUBLIC 演示资料")
    $lines.Add("")
    $lines.Add("这份文档用于游客路径验收，标题不含任何参数。")
    $lines.Add("")
    for ($i = 1; $i -le 35; $i++) { $lines.Add("第 $i 段普通说明，用来把关键事实放进正文中后部。") }
    $lines.Add("")
    $lines.Add("## PUBLIC 正文事实")
    $lines.Add("")
    $lines.Add("仅出现在正文的事实：PUBLIC 演示库的预检超时是 23 分钟，并发上限是 6 个。")
    $lines.Add("")
    for ($i = 1; $i -le 20; $i++) { $lines.Add("补充说明 $i。") }
    [System.IO.File]::WriteAllLines($docPath, $lines, (New-Object System.Text.UTF8Encoding($false)))

    $kb = Invoke-Api -Name "kb-public" -Method POST -Path "/api/knowledge-bases" -Body @{ name = "PUBLIC acceptance base"; description = "allowlisted for the guest path check" } -CookieJar $jar -Csrf $csrf
    $kbId = $null
    if (Ok $kb) { $kbId = $kb.Json.data.knowledgeBaseId }
    Check "public knowledge base created" ([bool]$kbId) (Short $kb)

    $upload = Invoke-Api -Name "upload-public" -Method POST -Path "/api/documents/upload" -CookieJar $jar -Csrf $csrf -ExtraArgs "-F kbId=$kbId -F file=@$docPath"
    $documentId = $null
    if (Ok $upload) { $documentId = $upload.Json.data.documentId }
    Check "public document uploaded and indexed" ([bool]$documentId) (Short $upload)

    $catalogue = (Invoke-Api -Name "models-list-guest" -Method GET -Path "/api/admin/models" -CookieJar $jar).Json.data
    if ($catalogue.models) { $catalogue = $catalogue.models }
    $llm = ($catalogue | Where-Object { $_.kind -eq "LLM" -and $_.enabled } | Select-Object -First 1)

    $agent = Invoke-Api -Name "agent-public" -Method POST -Path "/api/agents" -Body @{
        name = "PUBLIC 演示助手"; description = "guest path acceptance"
        systemPrompt = "你是公开演示助手。回答前先用 KnowledgeTool 检索允许的知识库，并在答复里说明来源；没有资料时明确说明。"
        model = $llm.id; allowedTools = @(); allowedKbs = @($kbId)
        chatOptions = @{ temperature = 0.7; topP = 1.0; messageLength = 10 }
    } -CookieJar $jar -Csrf $csrf
    $agentId = $null
    if (Ok $agent) { $agentId = $agent.Json.data.agentId }
    Check "demo assistant created" ([bool]$agentId) (Short $agent)

    $state = [pscustomobject]@{ kbId = $kbId; documentId = $documentId; agentId = $agentId }
    [System.IO.File]::WriteAllText($stateFile, ($state | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
    "state written to $stateFile"
    Write-Host ("checks passed: {0}, failed: {1}" -f $script:pass, $script:fail)
    if ($script:fail -gt 0) { exit 1 }
    exit 0
}

"== guest path (no session) =="
$state = Get-Content -Raw -Encoding UTF8 $stateFile | ConvertFrom-Json
$visitorJar = Join-Path $root "cookies-visitor.txt"
if (Test-Path $visitorJar) { Remove-Item $visitorJar }

$agents = Invoke-Api -Name "demo-agents" -Method GET -Path "/api/demo/agents" -CookieJar $visitorJar
$agentIds = @()
if (Ok $agents) { $agentIds = $agents.Json.data.agents | ForEach-Object { $_.id } }
Check "visitor sees exactly the configured demo assistant" (($agentIds -contains $state.agentId) -and (@($agentIds).Count -eq 1)) ("agents " + ($agentIds -join ","))

$bases = Invoke-Api -Name "demo-kbs" -Method GET -Path "/api/demo/knowledge-bases" -CookieJar $visitorJar
$kbIds = @()
if (Ok $bases) { $kbIds = $bases.Json.data.knowledgeBases | ForEach-Object { $_.id } }
Check "visitor sees the allowlisted knowledge base" ($kbIds -contains $state.kbId) ("kbs " + ($kbIds -join ","))

$documents = Invoke-Api -Name "demo-documents" -Method GET -Path "/api/demo/documents/kb/$($state.kbId)" -CookieJar $visitorJar
$documentIds = @()
if (Ok $documents) { $documentIds = $documents.Json.data.documents | ForEach-Object { $_.id } }
Check "visitor lists the published document" ($documentIds -contains $state.documentId) ("documents " + ($documentIds -join ","))

$content = Invoke-Api -Name "demo-content" -Method GET -Path "/api/demo/documents/$($state.documentId)/content" -CookieJar $visitorJar
Check "visitor reads the published Markdown source" (Ok $content) (Short $content)

$privateContent = Invoke-Api -Name "demo-private-content" -Method GET -Path "/api/demo/documents/00000000-0000-4000-8000-000000000000/content" -CookieJar $visitorJar
Check "visitor cannot read a non-published document" (-not (Ok $privateContent)) (Short $privateContent)

$uploadAttempt = Invoke-Api -Name "demo-upload" -Method POST -Path "/api/demo/documents/upload" -CookieJar $visitorJar -ExtraArgs "-F kbId=$($state.kbId)"
Check "visitor has no upload route" (-not (Ok $uploadAttempt)) (Short $uploadAttempt)

$deleteAttempt = Invoke-Api -Name "demo-delete-kb" -Method DELETE -Path "/api/demo/knowledge-bases/$($state.kbId)" -CookieJar $visitorJar
Check "visitor cannot delete the published knowledge base" (-not (Ok $deleteAttempt)) (Short $deleteAttempt)

$session = Invoke-Api -Name "demo-session" -Method POST -Path "/api/demo/chat-sessions" -Body @{ agentId = $state.agentId; title = "guest acceptance" } -CookieJar $visitorJar
$sessionId = $null
if (Ok $session) { $sessionId = $session.Json.data.chatSessionId }
Check "visitor creates a chat session" ([bool]$sessionId) (Short $session)

if ($sessionId) {
    $asked = Invoke-Api -Name "demo-ask" -Method POST -Path "/api/demo/chat-messages" -Body @{ agentId = $state.agentId; sessionId = $sessionId; role = "user"; content = "PUBLIC 演示库的正文里写的预检超时是多少分钟？并发上限是多少？" } -CookieJar $visitorJar
    Check "visitor question accepted" (Ok $asked) (Short $asked)

    $deadline = (Get-Date).AddMinutes(4)
    $assistant = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        $poll = Invoke-Api -Name "demo-messages" -Method GET -Path "/api/demo/chat-messages/session/$sessionId" -CookieJar $visitorJar
        if (Ok $poll) {
            $assistant = $poll.Json.data.chatMessages | Where-Object { $_.role -eq "assistant" -and $_.content -and $_.content.Trim().Length -gt 0 } | Select-Object -Last 1
            if ($assistant) { break }
        }
    }
    Check "visitor receives an answer" ($null -ne $assistant)
    if ($assistant) {
        $text = $assistant.content
        Check "guest answer uses the body-only fact (23 minutes)" (($text -match "23") -and ($text -match "6")) ($text.Substring(0, [Math]::Min(160, $text.Length)))
        $citations = $null
        if ($assistant.metadata) {
            $meta = $assistant.metadata
            if ($meta -is [string]) { $meta = $meta | ConvertFrom-Json }
            $citations = $meta.citations
        }
        Check "guest answer carries structured citations" ($null -ne $citations -and @($citations).Count -ge 1) ("count " + @($citations).Count)
        if ($citations) {
            $first = @($citations)[0]
            Check "guest citation points at the published document" ($first.source.documentId -eq $state.documentId) ("documentId " + $first.source.documentId)
            Check "guest citation carries body-v1 and a line range" (($first.indexVersion -eq "body-v1") -and ($null -ne $first.source.startLine)) ("indexVersion " + $first.indexVersion + " lines " + $first.source.startLine + "-" + $first.source.endLine)
        }
    }
}

Write-Host ("checks passed: {0}, failed: {1}" -f $script:pass, $script:fail)
if ($script:fail -gt 0) { exit 1 }
exit 0
