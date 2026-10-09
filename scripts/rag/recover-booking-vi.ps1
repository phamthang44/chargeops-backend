#Requires -Version 7.0
[CmdletBinding()]
param([switch]$Create, [switch]$Check)

$ErrorActionPreference = 'Stop'
if ($Create -and $Check) { throw 'Use Create or Check separately.' }
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$outputDir = Join-Path $repoRoot 'target/bkg067-rag'
$utf8 = [Text.UTF8Encoding]::new($false, $true)
$source = Join-Path $outputDir 'import/booking-and-payment.vi.txt'
if (-not (Test-Path -LiteralPath $source)) { throw 'Run prepare-pilot.ps1 offline first.' }
$model = 'Qwen/Qwen3-Embedding-0.6B'
$provider = 'langgenius/siliconflow/siliconflow'
$retrieval = @{ search_method = 'semantic_search'; reranking_enable = $false
    top_k = 3; score_threshold_enabled = $false; score_threshold = 0.0 }
$createBody = @{ name = 'ChargeOps BKG067 VI Qwen3-0.6B recovery'
    description = 'Isolated recovery pilot: booking/payment VI only, source r1; fixed 1024-dimension model.'
    permission = 'only_me'; provider = 'vendor'; embedding_model = $model; embedding_model_provider = $provider }
$documentBody = @{ name = 'booking-and-payment.vi.txt'; text = [IO.File]::ReadAllText($source, $utf8)
    indexing_technique = 'high_quality'; doc_form = 'text_model'; doc_language = 'Vietnamese'
    embedding_model = $model; embedding_model_provider = $provider; retrieval_model = $retrieval
    process_rule = @{ mode = 'custom'; rules = @{ pre_processing_rules = @()
        segmentation = @{ separator = '<<<CHARGEOPS_CHUNK>>>'; max_tokens = 1024 } } } }
[IO.File]::WriteAllText((Join-Path $outputDir 'recovery-create-payload.json'),
    (ConvertTo-Json -InputObject $createBody -Depth 6), $utf8)
[IO.File]::WriteAllText((Join-Path $outputDir 'recovery-document-payload.json'),
    (ConvertTo-Json -InputObject $documentBody -Depth 8), $utf8)
if (-not $Create -and -not $Check) {
    Write-Output 'Prepared recovery payloads: one new private Dify dataset and booking-and-payment.vi.txt only.'
    Write-Output 'No API calls. -Create creates once; -Check checks the saved recovery dataset and tests retrieval.'
    return
}
$statePath = Join-Path $outputDir 'recovery-state.json'
if ($Create -and (Test-Path -LiteralPath $statePath)) { throw 'Recovery state already exists; use Check to avoid duplicate creation.' }
if ($Check -and -not (Test-Path -LiteralPath $statePath)) { throw 'No saved recovery state.' }
$key = [Environment]::GetEnvironmentVariable('DIFY_DATASET_API_KEY')
if (-not $key) {
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $repoRoot '.env'), $utf8)) {
        if ($line -match '^\s*DIFY_DATASET_API_KEY\s*=\s*(.*?)\s*$') { $key = $Matches[1].Trim('"', "'") }
    }
}
if (-not $key) { throw 'Missing local DIFY_DATASET_API_KEY.' }
$handler = [Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$http = [Net.Http.HttpClient]::new($handler)
$http.Timeout = [TimeSpan]::FromSeconds(60)
# Local helper block; endpoint is fixed and credentials never appear in the payload/report.
$send = {
    param($method, $path, $body)
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($method), "https://api.dify.ai/v1$path")
    $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $key)
    if ($null -ne $body) { $request.Content = [Net.Http.StringContent]::new(
        (ConvertTo-Json -InputObject $body -Depth 10 -Compress), [Text.Encoding]::UTF8, 'application/json') }
    $response = $null
    try {
        $response = $http.SendAsync($request).GetAwaiter().GetResult()
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        try { $json = ConvertFrom-Json $raw } catch { $json = $null }
        if (-not $response.IsSuccessStatusCode) {
            $message = if ($json.message) { [string]$json.message } else { 'No JSON error message.' }
            $message = $message.Replace($key, '[REDACTED]')
            throw "Dify HTTP $([int]$response.StatusCode): $($message.Substring(0, [Math]::Min(500, $message.Length)))"
        }
        if ($null -eq $json) { throw 'Invalid JSON from Dify.' }
        return $json
    } finally { if ($response) { $response.Dispose() }; $request.Dispose() }
}
try {
    if ($Create) {
        $dataset = & $send 'POST' '/datasets' $createBody
        if (-not $dataset.id) { throw 'Dify did not return dataset ID.' }
        $state = [ordered]@{ dataset_id = $dataset.id; name = $dataset.name; model = $model
            provider = $provider; document_id = $null; batch = $null; status = 'dataset-created' }
        # Save immediately so a later failure never automatically creates another dataset.
        [IO.File]::WriteAllText($statePath, (ConvertTo-Json -InputObject $state -Depth 6), $utf8)
        Write-Output (ConvertTo-Json -InputObject $state -Compress)
        $created = & $send 'POST' "/datasets/$($state.dataset_id)/document/create-by-text" $documentBody
        $state.document_id = $created.document.id; $state.batch = $created.batch
        $state.status = $created.document.indexing_status
        [IO.File]::WriteAllText($statePath, (ConvertTo-Json -InputObject $state -Depth 6), $utf8)
    } else { $state = ConvertFrom-Json ([IO.File]::ReadAllText($statePath, $utf8)) -AsHashtable }
    if (-not $state.batch -or -not $state.document_id) { throw 'Dataset exists but no indexing batch; inspect recovery state before another upload.' }
    foreach ($idField in @('dataset_id', 'document_id', 'batch')) {
        if ($state[$idField] -notmatch '^[a-zA-Z0-9-]+$') { throw 'Invalid saved recovery ID.' }
    }
    for ($poll = 0; $poll -lt 12; $poll++) {
        $statusResponse = & $send 'GET' "/datasets/$($state.dataset_id)/documents/$($state.batch)/indexing-status" $null
        $status = $statusResponse.data | Where-Object id -EQ $state.document_id | Select-Object -First 1
        if (-not $status) { throw 'Document missing from indexing status.' }
        $state.status = $status.indexing_status
        [IO.File]::WriteAllText($statePath, (ConvertTo-Json -InputObject $state -Depth 6), $utf8)
        Write-Output (ConvertTo-Json -InputObject ($status | Select-Object id, indexing_status, completed_segments, total_segments, error) -Compress)
        if ($status.indexing_status -eq 'error') { throw 'Recovery index failed; see sanitized API status.' }
        if ($status.indexing_status -eq 'completed') { break }
        if ($status.indexing_status -notin @('waiting', 'parsing', 'cleaning', 'splitting', 'indexing')) { throw 'Indexing paused/stopped or returned unknown status.' }
        Start-Sleep -Seconds 10
    }
    if ($state.status -ne 'completed') { throw 'Index still running; use Check later.' }
    $checks = @(
        @{ query = 'Thử thanh toán lại có kéo dài hạn giữ chỗ 10 phút không?'; rule = 'BP-02' },
        @{ query = 'Trang báo thanh toán thành công có chứng minh booking đã CONFIRMED không?'; rule = 'BP-03' },
        @{ query = 'Hạn giữ chỗ và ân hạn hủy bắt đầu từ hai thời điểm nào?'; rule = 'BP-05' }
    )
    $results = @()
    foreach ($probeCase in $checks) {
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $found = & $send 'POST' "/datasets/$($state.dataset_id)/retrieve" @{ query = $probeCase.query; retrieval_model = $retrieval }
        $timer.Stop()
        $hits = @($found.records | ForEach-Object {
            $heading = [regex]::Match([string]$_.segment.content, '(?m)^## (.+)$').Groups[1].Value
            [PSCustomObject]@{ segment_id = $_.segment.id; heading = $heading; score = $_.score
                document_id = $_.segment.document_id; document_name = $_.segment.document.name }
        })
        $matched = @($found.records | Where-Object { $_.segment.content -match ("(?m)^## " + [regex]::Escape($probeCase.rule) + '\b') }).Count -gt 0
        $result = [PSCustomObject]@{ query = $probeCase.query; expected_rule = $probeCase.rule
            expected_rule_in_top3 = $matched; elapsed_ms = $timer.ElapsedMilliseconds; hits = $hits }
        $results += $result
        Write-Output (ConvertTo-Json -InputObject $result -Depth 6 -Compress)
    }
    [IO.File]::WriteAllText((Join-Path $outputDir 'recovery-retrieval-report.json'),
        (ConvertTo-Json -InputObject $results -Depth 7), $utf8)
    if (@($results | Where-Object { -not $_.expected_rule_in_top3 }).Count) { throw 'Index completed but retrieval needs review.' }
} finally { $http.Dispose(); $handler.Dispose() }
