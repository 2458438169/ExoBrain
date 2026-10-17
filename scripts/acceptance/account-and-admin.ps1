# End-to-end acceptance for the ExoBrain first phase: account + admin + guest paths, private
# isolation, body-only fact retrieval with verifiable citations, and deletion semantics.
#
# Runs against a locally started application. Synthetic documents only; every credential comes from
# the process environment and nothing is written outside the acceptance work directory.
param(
    [string]$BaseUrl = "http://127.0.0.1:18081",
    [string]$WorkDir = $env:PI_SCRATCH_DIR
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$root = Join-Path $WorkDir "acceptance"
$api = Join-Path $root "api"
New-Item -ItemType Directory -Force -Path $api | Out-Null

$script:pass = 0
$script:fail = 0

function Check([string]$name, [bool]$ok, [string]$detail = "") {
    if ($ok) { $script:pass++; Write-Host ("PASS  " + $name + $(if ($detail) { "  [" + $detail + "]" } else { "" })) -ForegroundColor Green }
    else { $script:fail++; Write-Host ("FAIL  " + $name + $(if ($detail) { "  [" + $detail + "]" } else { "" })) -ForegroundColor Red }
}

function Ok($response) {
    return ($null -ne $response) -and ($null -ne $response.Json) -and ($response.Json.code -eq 200)
}

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

function Login([string]$user, [string]$password) {
    $jar = Join-Path $root "cookies-$user.txt"
    if (Test-Path $jar) { Remove-Item $jar }
    $response = Invoke-Api -Name "login-$user" -Method POST -Path "/api/auth/login" `
        -Body @{ username = $user; password = $password } -CookieJar $jar
    $csrf = $null
    if (Ok $response) { $csrf = $response.Json.data.csrfToken }
    return [pscustomobject]@{ Jar = $jar; Csrf = $csrf; Ok = ($null -ne $csrf); Response = $response }
}

function New-SyntheticDocument([string]$path, [string]$marker, [string]$fact, [string]$tailFact) {
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# $marker 概述")
    $lines.Add("")
    $lines.Add("$marker 的标题只说明这是一份 $marker 文档，不含具体参数。")
    $lines.Add("")
    for ($i = 1; $i -le 40; $i++) { $lines.Add("第 $i 段普通说明文字，用来把关键事实推到文档中后部（$marker 专属）。") }
    $lines.Add("")
    $lines.Add("## $marker 运行参数")
    $lines.Add("")
    $lines.Add("本节记录实际参数：$fact")
    $lines.Add("")
    for ($i = 1; $i -le 30; $i++) { $lines.Add("后续补充说明 $i（$marker 专属，不与其它文档重复）。") }
    $lines.Add("")
    $lines.Add("## $marker 尾部校验")
    $lines.Add("")
    $lines.Add("文档末尾的事实：$tailFact")
    [System.IO.File]::WriteAllLines($path, $lines, (New-Object System.Text.UTF8Encoding($false)))
}

"== 0. prerequisites =="
$adminPassword = $env:EXOBRAIN_ADMIN_PASSWORD
Check "administrator password available" ([bool]$adminPassword) "EXOBRAIN_ADMIN_PASSWORD"
Check "curl.exe available" ($null -ne (Get-Command curl.exe -ErrorAction SilentlyContinue))

"== 1. administrator sign-in and model catalogue =="
$admin = Login "admin" $adminPassword
Check "admin login returns a CSRF token" $admin.Ok (Short $admin.Response)

$modelList = Invoke-Api -Name "models-list" -Method GET -Path "/api/admin/models" -CookieJar $admin.Jar
Check "model catalogue readable" (Ok $modelList) (Short $modelList)
$catalogue = @()
if (Ok $modelList) {
    $catalogue = $modelList.Json.data
    if ($catalogue.models) { $catalogue = $catalogue.models }
}

$embeddingModel = ($catalogue | Where-Object { $_.kind -eq "EMBEDDING" -and $_.defaultModel -eq $true } | Select-Object -First 1)
$usableEmbedding = ($null -ne $embeddingModel) -and ($embeddingModel.baseUrl -match "emrank") 
$embeddingDetail = "none"
if ($embeddingModel) { $embeddingDetail = "$($embeddingModel.name) / $($embeddingModel.model) / $($embeddingModel.baseUrl) / $($embeddingModel.dimensions) dims" }
Check "default embedding model points at the real endpoint" $usableEmbedding $embeddingDetail

$llmModel = ($catalogue | Where-Object { $_.kind -eq "LLM" -and $_.enabled } | Select-Object -First 1)
$llmDetail = "none"
if ($llmModel) { $llmDetail = "$($llmModel.name) / $($llmModel.model) / $($llmModel.id)" }
Check "an enabled language model exists" ($null -ne $llmModel) $llmDetail

if (-not $llmModel) {
    $createdLlm = Invoke-Api -Name "model-llm" -Method POST -Path "/api/admin/models" -Body @{
        id = "acceptance-llm"; name = "acceptance-llm"; kind = "LLM"; protocol = "deepseek"
        baseUrl = "https://metapi.lilililwan.xyz/v1"; model = "deepseek-v4.1-flash"
        apiKey = $env:EXOBRAIN_ACCEPTANCE_LLM_API_KEY; enabled = $true; defaultModel = $true
    } -CookieJar $admin.Jar -Csrf $admin.Csrf
    Check "language model created" (Ok $createdLlm) (Short $createdLlm)
    $llmModel = $createdLlm.Json.data
}
$script:llmModelId = $null
if ($llmModel) { $script:llmModelId = $llmModel.id }
Check "language model id available for assistants" ([bool]$script:llmModelId) ("model id " + $script:llmModelId)

foreach ($account in @("accept-u1", "accept-u2")) {
    $user = Invoke-Api -Name "user-$account" -Method POST -Path "/api/admin/users" -Body @{
        username = $account; displayName = $account; role = "USER"; password = "Acceptance-$account-2026!"
    } -CookieJar $admin.Jar -Csrf $admin.Csrf
    Check "account $account created" (Ok $user) (Short $user)
}

"== 2. two private knowledge bases with their own bodies =="
$factOne = "仅出现在正文的事实：ALPHA 的就绪探针间隔是 37 秒，重试上限为 4 次。"
$factTwo = "仅出现在正文的事实：BETA 的就绪探针间隔是 91 秒，重试上限为 9 次。"
$tailOne = "ALPHA 文档末尾校验码是 T-121。"
$tailTwo = "BETA 文档末尾校验码是 T-980。"

$docOne = Join-Path $root "alpha.md"
$docTwo = Join-Path $root "beta.md"
New-SyntheticDocument $docOne "ALPHA" $factOne $tailOne
New-SyntheticDocument $docTwo "BETA" $factTwo $tailTwo

function New-PrivateKnowledge {
    param([string]$account, [string]$password, [string]$name, [string]$documentPath, [string]$marker)
    $session = Login $account $password
    Check "$account login" $session.Ok

    $kb = Invoke-Api -Name "kb-$account" -Method POST -Path "/api/knowledge-bases" -Body @{ name = $name; description = "$marker acceptance knowledge base" } -CookieJar $session.Jar -Csrf $session.Csrf
    $kbId = $null
    if (Ok $kb) { $kbId = $kb.Json.data.knowledgeBaseId }
    Check "$account creates a private knowledge base" ([bool]$kbId) (Short $kb)

    $upload = Invoke-Api -Name "upload-$account" -Method POST -Path "/api/documents/upload" -CookieJar $session.Jar -Csrf $session.Csrf -ExtraArgs "-F kbId=$kbId -F file=@$documentPath"
    $documentId = $null
    if (Ok $upload) { $documentId = $upload.Json.data.documentId }
    Check "$account uploads its own document" ([bool]$documentId) (Short $upload)

    $agent = Invoke-Api -Name "agent-$account" -Method POST -Path "/api/agents" -Body @{
        name = "$marker assistant"; description = "acceptance"
        systemPrompt = "你是知识助手。回答前先用 KnowledgeTool 检索允许的知识库，并在答复里说明来源。"
        model = $script:llmModelId; allowedTools = @(); allowedKbs = @($kbId)
        chatOptions = @{ temperature = 0.7; topP = 1.0; messageLength = 10 }
    } -CookieJar $session.Jar -Csrf $session.Csrf
    $agentId = $null
    if (Ok $agent) { $agentId = $agent.Json.data.agentId }
    Check "$account creates an assistant bound to its own knowledge base" ([bool]$agentId) (Short $agent)

    return [pscustomobject]@{ Session = $session; KbId = $kbId; DocumentId = $documentId; AgentId = $agentId }
}

$one = New-PrivateKnowledge "accept-u1" "Acceptance-accept-u1-2026!" "ALPHA private base" $docOne "ALPHA"
$two = New-PrivateKnowledge "accept-u2" "Acceptance-accept-u2-2026!" "BETA private base" $docTwo "BETA"

"== 3. isolation matrix =="
$u1List = Invoke-Api -Name "kb-list-u1" -Method GET -Path "/api/knowledge-bases" -CookieJar $one.Session.Jar
$u1Ids = @()
if (Ok $u1List) { $u1Ids = $u1List.Json.data.knowledgeBases | ForEach-Object { $_.id } }
Check "u1 lists only its own knowledge base" (($u1Ids -contains $one.KbId) -and (-not ($u1Ids -contains $two.KbId))) ("u1 sees " + ($u1Ids -join ","))

$u1ReadsU2Doc = Invoke-Api -Name "cross-doc-u1" -Method GET -Path "/api/documents/$($two.DocumentId)/content" -CookieJar $one.Session.Jar
Check "u1 cannot read u2 document content" (-not (Ok $u1ReadsU2Doc)) (Short $u1ReadsU2Doc)

$u1DeletesU2Kb = Invoke-Api -Name "cross-kb-delete-u1" -Method DELETE -Path "/api/knowledge-bases/$($two.KbId)" -CookieJar $one.Session.Jar -Csrf $one.Session.Csrf
Check "u1 cannot delete u2 knowledge base" (-not (Ok $u1DeletesU2Kb)) (Short $u1DeletesU2Kb)

$adminList = Invoke-Api -Name "kb-list-admin" -Method GET -Path "/api/knowledge-bases" -CookieJar $admin.Jar
$adminIds = @()
if (Ok $adminList) { $adminIds = $adminList.Json.data.knowledgeBases | ForEach-Object { $_.id } }
Check "administrator cannot list a private knowledge base" ((-not ($adminIds -contains $one.KbId)) -and (-not ($adminIds -contains $two.KbId))) ("admin sees " + ($adminIds -join ","))

$adminReadsPrivate = Invoke-Api -Name "admin-reads-private" -Method GET -Path "/api/documents/$($one.DocumentId)/content" -CookieJar $admin.Jar
Check "administrator cannot read another account's private document" (-not (Ok $adminReadsPrivate)) (Short $adminReadsPrivate)

"== 4. body-only fact retrieval and verifiable citations =="
function Ask-Assistant {
    param($Owner, [string]$Question, [string]$Label)
    $session = Invoke-Api -Name "chat-session-$Label" -Method POST -Path "/api/chat-sessions" -Body @{ agentId = $Owner.AgentId; title = "acceptance $Label" } -CookieJar $Owner.Session.Jar -Csrf $Owner.Session.Csrf
    $sessionId = $null
    if (Ok $session) { $sessionId = $session.Json.data.chatSessionId }
    Check "$Label session created" ([bool]$sessionId) (Short $session)

    $asked = Invoke-Api -Name "ask-$Label" -Method POST -Path "/api/chat-messages" -Body @{ agentId = $Owner.AgentId; sessionId = $sessionId; role = "user"; content = $Question } -CookieJar $Owner.Session.Jar -Csrf $Owner.Session.Csrf
    Check "$Label question accepted" (Ok $asked) (Short $asked)
    $deadline = (Get-Date).AddMinutes(4)
    $messages = $null
    $assistant = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        $poll = Invoke-Api -Name "messages-$Label" -Method GET -Path "/api/chat-messages/session/$sessionId" -CookieJar $Owner.Session.Jar
        if (Ok $poll) {
            $messages = $poll.Json.data.chatMessages
            $assistant = $messages | Where-Object { $_.role -eq "assistant" -and $_.content -and $_.content.Trim().Length -gt 0 } | Select-Object -Last 1
            if ($assistant) { break }
        }
    }
    return [pscustomobject]@{ SessionId = $sessionId; Messages = $messages; Assistant = $assistant }
}

$answerOne = Ask-Assistant $one "ALPHA 文档正文里给出的就绪探针间隔是多少秒？重试上限是几次？" "u1-alpha"
$assistantOne = $answerOne.Assistant
Check "u1 assistant answered" ($null -ne $assistantOne) ("session " + $answerOne.SessionId)
if ($assistantOne) {
    $answerText = $assistantOne.content
    Check "answer uses the body-only fact (37 seconds)" ($answerText -match "37") ($answerText.Substring(0, [Math]::Min(160, $answerText.Length)))
    $citations = $null
    if ($assistantOne.metadata) {
        $meta = $assistantOne.metadata
        if ($meta -is [string]) { $meta = $meta | ConvertFrom-Json }
        $citations = $meta.citations
    }
    Check "structured citations are present" ($null -ne $citations -and @($citations).Count -ge 1) ("count " + @($citations).Count)
    if ($citations) {
        $first = @($citations)[0]
        Check "citation points at the uploaded document" ($first.source.documentId -eq $one.DocumentId) ("documentId " + $first.source.documentId)
        Check "citation carries a real line range" (($null -ne $first.source.startLine) -and ($null -ne $first.source.endLine) -and ($first.source.startLine -ge 1)) ("lines " + $first.source.startLine + "-" + $first.source.endLine)
        Check "citation exposes body-v1 index version" ($first.indexVersion -eq "body-v1") ("indexVersion " + $first.indexVersion)
        $content = Invoke-Api -Name "citation-content" -Method GET -Path "/api/documents/$($first.source.documentId)/content" -CookieJar $one.Session.Jar
        $excerptOk = $false
        if (Ok $content) {
            $lines = $content.Json.data.content -split "`r?`n"
            $start = [int]$first.source.startLine
            $end = [Math]::Min([int]$first.source.endLine, $lines.Count)
            $slice = ($lines[($start - 1)..($end - 1)]) -join "`n"
            $excerptOk = $slice.Contains("37 秒")
        }
        Check "cited source range really contains the fact" $excerptOk
    }
}

"== 5. insufficient material is not invented =="
$offTopic = Ask-Assistant $two "BETA 文档里提到的 2026 年商业客户数量和检索准确率分别是多少？" "u2-nomatch"
$offAssistant = $offTopic.Assistant
if ($offAssistant) {
    $text = $offAssistant.content
    Check "insufficient material answered without invented numbers" (($text -match "没有") -or ($text -match "未提供") -or ($text -match "无法") -or ($text -match "资料不足") -or ($text -match "未提及")) ($text.Substring(0, [Math]::Min(160, $text.Length)))
} else {
    Check "insufficient material answered without invented numbers" $false "no assistant answer"
}

"== 6. deletion keeps history and reports the source as unavailable =="
$deleted = Invoke-Api -Name "delete-kb-u1" -Method DELETE -Path "/api/knowledge-bases/$($one.KbId)" -CookieJar $one.Session.Jar -Csrf $one.Session.Csrf
Check "u1 deletes its own knowledge base" (Ok $deleted) (Short $deleted)
$historyAfterDelete = Invoke-Api -Name "history-after-delete" -Method GET -Path "/api/chat-messages/session/$($answerOne.SessionId)" -CookieJar $one.Session.Jar
$kept = $false
if (Ok $historyAfterDelete) { $kept = @($historyAfterDelete.Json.data.chatMessages | Where-Object { $_.role -eq "assistant" -and $_.content }).Count -ge 1 }
Check "chat history survives knowledge-base deletion" $kept
$gone = Invoke-Api -Name "content-after-delete" -Method GET -Path "/api/documents/$($one.DocumentId)/content" -CookieJar $one.Session.Jar
Check "deleted source is no longer readable" (-not (Ok $gone)) (Short $gone)
$documentsLeft = Invoke-Api -Name "documents-after-delete" -Method GET -Path "/api/documents/kb/$($one.KbId)" -CookieJar $one.Session.Jar
Check "deleted knowledge base has no documents left" ((-not (Ok $documentsLeft)) -or (@($documentsLeft.Json.data.documents).Count -eq 0))

"== summary =="
Write-Host ("checks passed: {0}, failed: {1}" -f $script:pass, $script:fail)
if ($script:fail -gt 0) { exit 1 }
exit 0
