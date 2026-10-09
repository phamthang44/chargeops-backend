#Requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$ProbeEmbedding,
    [ValidateSet('Qwen/Qwen3-Embedding-8B', 'Qwen/Qwen3-Embedding-4B', 'Qwen/Qwen3-Embedding-0.6B')]
    [string]$Model = 'Qwen/Qwen3-Embedding-8B',
    [switch]$InspectDify,
    [switch]$ListDatasets,
    [ValidatePattern('^[a-zA-Z0-9-]+$')]
    [string]$DatasetId
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$sourceDir = Join-Path $repoRoot 'docs/ChargeOps/Knowledge/Published'
$outputDir = Join-Path $repoRoot 'target/bkg067-rag'
$importDir = Join-Path $outputDir 'import'
$null = New-Item -ItemType Directory -Force -Path $importDir
$utf8 = [Text.UTF8Encoding]::new($false, $true)
$separator = '<<<CHARGEOPS_CHUNK>>>'
$reports = @()
$originals = @{}
$prepared = @{}

# Fixed pilot allowlist: never send the vault, plans, credentials or SQL as corpus.
foreach ($name in @('booking-and-payment.vi.md', 'booking-and-payment-en-version.md',
        'cancellation-and-refunds.vi.md', 'cancellation-and-refunds-en-version.md')) {
    $sourcePath = Join-Path $sourceDir $name
    $bytes = [IO.File]::ReadAllBytes($sourcePath)
    $original = $utf8.GetString($bytes).TrimStart([char]0xFEFF)
    if ($original.Contains([char]0) -or $original.Contains([char]0xFFFD)) {
        throw "Invalid Unicode content in $name; fix source before exporting."
    }
    $normalized = $original.Replace("`r`n", "`n").Normalize([Text.NormalizationForm]::FormC)
    $frontmatter = [regex]::Match($normalized, '\A---\n(?<meta>.*?)\n---\n', 'Singleline')
    if (-not $frontmatter.Success) { throw "Missing frontmatter in $name" }
    $meta = @{}
    foreach ($line in ($frontmatter.Groups['meta'].Value -split "`n")) {
        if ($line -match '^([a-z_]+):\s*(.+)$') { $meta[$Matches[1]] = $Matches[2].Trim().Trim('"') }
    }
    foreach ($field in @('doc_id', 'locale', 'revision', 'policy_baseline', 'title', 'status')) {
        if (-not $meta[$field]) { throw "Missing $field in $name" }
    }
    if ($meta['status'] -ne 'published') { throw "Unpublished source: $name" }
    if ($meta['locale'] -notin @('vi', 'en')) { throw "Unsupported locale in $name" }
    $body = $normalized.Substring($frontmatter.Length).Trim()
    $navigation = if ($meta['locale'] -eq 'vi') { 'Căn cứ và điều hướng' } else { 'Sources and navigation' }
    $body = [regex]::Replace($body, '(?ms)^## ' + [regex]::Escape($navigation) + '\n.*\z', '').Trim()
    # Keep the visible text of inline references; no private Obsidian URLs in retrieval.
    $body = [regex]::Replace($body, '\[\[([^\]|]+)\|([^\]]+)\]\]', '$2')
    $body = [regex]::Replace($body, '\[\[([^\]]+)\]\]', '$1')
    if ($body.Contains($separator)) { throw "Reserved delimiter in $name" }
    $sections = @([regex]::Matches($body, '(?ms)^## (?<heading>[^\n]+)\n(?<body>.*?)(?=^## |\z)'))
    if ($sections.Count -eq 0) { throw "No policy sections in $name" }
    $firstSection = $sections[0].Index
    $intro = $body.Substring(0, $firstSection).Trim()
    $chunks = @()
    $headings = @()
    foreach ($section in $sections) {
        $heading = $section.Groups['heading'].Value.Trim()
        $text = $section.Groups['body'].Value.Trim()
        if (-not $text) { throw "Empty section '$heading' in $name" }
        $header = "doc_id: $($meta['doc_id']) | locale: $($meta['locale']) | revision: $($meta['revision']) | policy_baseline: $($meta['policy_baseline'])"
        $chunks += "$header`n`n$intro`n`n## $heading`n`n$text"
        $headings += $heading
    }
    $export = ($chunks -join "`n`n$separator`n`n") + "`n"
    $outputName = [IO.Path]::ChangeExtension($name, '.txt')
    [IO.File]::WriteAllText((Join-Path $importDir $outputName), $export, $utf8)
    $hash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash
    $reports += [PSCustomObject]@{
        source = $name; output = $outputName; source_sha256 = $hash
        doc_id = $meta['doc_id']; locale = $meta['locale']; revision = $meta['revision']
        source_bytes = $bytes.Length; source_characters = $original.Length
        source_is_nfc = $original.IsNormalized(); chunks = $chunks.Count
        max_chunk_characters = ($chunks | ForEach-Object { $_.Length } | Measure-Object -Maximum).Maximum
        headings = $headings
    }
    $originals[$name] = $original
    $prepared[$name] = $chunks
}
foreach ($group in ($reports | Group-Object doc_id)) {
    if ($group.Count -ne 2 -or @($group.Group.locale | Sort-Object -Unique).Count -ne 2 -or
            @($group.Group.revision | Sort-Object -Unique).Count -ne 1) {
        throw "Bilingual source mismatch for $($group.Name)"
    }
}
[IO.File]::WriteAllText((Join-Path $outputDir 'corpus-report.json'),
    (ConvertTo-Json -InputObject $reports -Depth 5), $utf8)
$reports | Format-Table source, chunks, max_chunk_characters, source_is_nfc
Write-Output "Import directory: $importDir"
Write-Output "General mode; delimiter $separator; overlap 0; preview before indexing."
Write-Output 'Character counts are not model token counts. Offline export does not prove live indexing.'

if (-not $ProbeEmbedding -and -not $InspectDify) { return }

# Credentials stay local. Only named variables are read, never dumped to output.
$credentials = @{}
foreach ($keyName in @('SILICONFLOW_API_KEY', 'DIFY_DATASET_API_KEY', 'DIFY_DATASET_ID')) {
    $credentials[$keyName] = [Environment]::GetEnvironmentVariable($keyName)
}
$envPath = Join-Path $repoRoot '.env'
if (Test-Path -LiteralPath $envPath) {
    foreach ($line in [IO.File]::ReadAllLines($envPath, $utf8)) {
        if ($line -match '^\s*(SILICONFLOW_API_KEY|DIFY_DATASET_API_KEY|DIFY_DATASET_ID)\s*=\s*(.*?)\s*$') {
            $keyName = $Matches[1]
            if (-not $credentials[$keyName]) { $credentials[$keyName] = $Matches[2].Trim('"', "'") }
        }
    }
}
if ($ProbeEmbedding -and -not $credentials['SILICONFLOW_API_KEY']) {
    throw 'Set SILICONFLOW_API_KEY in local .env or process environment. Do not paste it into chat.'
}
if (-not $DatasetId -and $credentials['DIFY_DATASET_ID']) { $DatasetId = $credentials['DIFY_DATASET_ID'] }
if ($InspectDify -and -not $credentials['DIFY_DATASET_API_KEY']) {
    throw 'InspectDify needs DIFY_DATASET_API_KEY (Knowledge Service API key, not the app key).'
}
if ($InspectDify -and $DatasetId -and $DatasetId -notmatch '^[a-zA-Z0-9-]+$') { throw 'Invalid dataset ID.' }
$handler = [Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$http = [Net.Http.HttpClient]::new($handler)
$http.Timeout = [TimeSpan]::FromSeconds(60)
$requests = @()
if ($ProbeEmbedding) {
    # Three serial probes, scoped to the explicitly authorized failing VI file.
    # No English/refund documents, retries or chat-model calls are sent.
    $requests += @(
        @{ name = 'first-booking-vi-rule'; input = $prepared['booking-and-payment.vi.md'][0] },
        @{ name = 'whole-booking-vi'; input = $originals['booking-and-payment.vi.md'] },
        @{ name = 'batch-three-booking-vi-rules'; input = @($prepared['booking-and-payment.vi.md'][0..2]) }
    ) | ForEach-Object {
        @{ name = $_.name; url = 'https://api.siliconflow.com/v1/embeddings'; method = 'POST'
            key = $credentials['SILICONFLOW_API_KEY']; payload = @{ model = $Model; input = $_.input; encoding_format = 'float' }
            expected = @($_.input).Count }
    }
}
if ($InspectDify) {
    if ($DatasetId -and -not $ListDatasets) {
        $requests += @{ name = 'dify-config'; url = "https://api.dify.ai/v1/datasets/$DatasetId"
            method = 'GET'; key = $credentials['DIFY_DATASET_API_KEY'] }
    }
    $readUrl = if ($DatasetId -and -not $ListDatasets) { "https://api.dify.ai/v1/datasets/$DatasetId/documents?page=1&limit=100" } else { 'https://api.dify.ai/v1/datasets?page=1&limit=100' }
    $requests += @{ name = if ($DatasetId -and -not $ListDatasets) { 'dify-documents' } else { 'dify-datasets' }; url = $readUrl
        method = 'GET'; key = $credentials['DIFY_DATASET_API_KEY'] }
}
$liveReport = @()
try {
    foreach ($probe in $requests) {
        $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($probe.method), $probe.url)
        $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $probe.key)
        if ($probe.method -eq 'POST') {
            $request.Content = [Net.Http.StringContent]::new(
                (ConvertTo-Json -InputObject $probe.payload -Depth 5 -Compress), [Text.Encoding]::UTF8, 'application/json')
        }
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $entry = [ordered]@{ probe = $probe.name; model = if ($probe.method -eq 'POST') { $Model } else { $null }
            status = $null; elapsed_ms = 0; result = 'transport-error' }
        $response = $null
        try {
            $response = $http.SendAsync($request).GetAwaiter().GetResult()
            $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $entry.status = [int]$response.StatusCode
            try { $json = ConvertFrom-Json $raw } catch { $json = $null }
            if (-not $response.IsSuccessStatusCode) {
                $entry.result = switch ($entry.status) {
                    400 { 'input-or-model-error' }; 401 { 'key-or-endpoint-error' }
                    403 { 'access-error' }; 404 { 'model-or-route-not-found' }
                    429 { 'rate-or-quota-limit' }; 503 { 'provider-overloaded' }
                    504 { 'provider-timeout' }; default { 'http-error' }
                }
                $message = if ($json.error.message) { [string]$json.error.message } elseif ($json.message) { [string]$json.message } else { 'No JSON error message; inspect provider/Dify UI.' }
                foreach ($secret in $credentials.Values) { if ($secret) { $message = $message.Replace($secret, '[REDACTED]') } }
                $entry.error = $message.Substring(0, [Math]::Min(1000, $message.Length))
            } elseif ($probe.method -eq 'POST') {
                $vectors = @($json.data)
                $expectedDimension = switch ($Model) {
                    'Qwen/Qwen3-Embedding-8B' { 4096 }; 'Qwen/Qwen3-Embedding-4B' { 2048 }; default { 1024 }
                }
                $valid = ($vectors.Count -eq $probe.expected)
                for ($index = 0; $index -lt $vectors.Count; $index++) {
                    $vector = $vectors[$index]
                    if ($vector.index -ne $index -or @($vector.embedding).Count -ne $expectedDimension) { $valid = $false }
                    foreach ($value in $vector.embedding) {
                        if ($null -eq $value -or $value -is [string] -or [double]::IsNaN([double]$value) -or [double]::IsInfinity([double]$value)) { $valid = $false }
                    }
                }
                $entry.result = if ($valid) { 'valid-vectors' } else { 'invalid-response' }
                $entry.vectors = $vectors.Count; $entry.dimension = $expectedDimension
                $entry.prompt_tokens = $json.usage.prompt_tokens
            } elseif ($probe.name -eq 'dify-config') {
                $entry.result = if ($json.id) { 'config-read' } else { 'invalid-response' }
                $entry.dataset = $json | Select-Object id, name, document_count, indexing_technique,
                    embedding_model, embedding_model_provider, embedding_available, retrieval_model_dict, doc_form
            } elseif ($probe.name -eq 'dify-datasets') {
                $entry.result = if ($null -ne $json.data) { 'datasets-read' } else { 'invalid-response' }
                $entry.has_more = $json.has_more
                $entry.datasets = @($json.data | Select-Object id, name, document_count, indexing_technique,
                    embedding_model, embedding_model_provider)
            } else {
                $entry.result = if ($null -ne $json.data) { 'documents-read' } else { 'invalid-response' }
                $entry.has_more = $json.has_more
                $entry.documents = @($json.data | ForEach-Object {
                    $errorText = [string]$_.error
                    foreach ($secret in $credentials.Values) { if ($secret) { $errorText = $errorText.Replace($secret, '[REDACTED]') } }
                    [PSCustomObject]@{ id = $_.id; name = $_.name; indexing_status = $_.indexing_status
                        error = $errorText.Substring(0, [Math]::Min(1000, $errorText.Length))
                        tokens = $_.tokens; enabled = $_.enabled }
                })
            }
        } catch {
            # Do not log raw request/response bodies, headers, vectors or credentials.
            $entry.result = if ($_.Exception.ToString() -match 'Timeout|TaskCanceled') { 'transport-timeout' } else { 'transport-error' }
        } finally {
            $timer.Stop(); $entry.elapsed_ms = $timer.ElapsedMilliseconds
            if ($response) { $response.Dispose() }
            $request.Dispose()
        }
        $liveReport += [PSCustomObject]$entry
        Write-Output (ConvertTo-Json -InputObject $entry -Depth 6 -Compress)
    }
} finally { $http.Dispose(); $handler.Dispose() }
[IO.File]::WriteAllText((Join-Path $outputDir 'live-report.json'),
    (ConvertTo-Json -InputObject $liveReport -Depth 7), $utf8)
if (@($liveReport | Where-Object { $_.result -notin @('valid-vectors', 'documents-read', 'datasets-read', 'config-read') }).Count) { exit 1 }
