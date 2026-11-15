# Browser-mode captures of the work copy's page (2560x1440, Edge headless). The assets are copied to a temp folder and served from there.
#   cap.ps1 -Shots 'name=query;name2=query2'      -> <scratch>\cap_<name>.png
param([string]$Shots, [string]$Dom = '')
$py = "$env:LOCALAPPDATA\Programs\Python\Python313\python.exe"
$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
$assets = Join-Path $PSScriptRoot '..\signal-probe-fullhmi\app\src\main\assets'
$scratch = "$env:LOCALAPPDATA\Temp\claude\8640deec67143bd7\1be1ec12-7a95-4d8a-ab3a-2b4393a8ea1c\scratchpad"
$srvdir = "$scratch\srv_aurora"
robocopy $assets $srvdir /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
$srv = Start-Process $py -ArgumentList @('-m', 'http.server', '8897', '--bind', '127.0.0.1', '--directory', $srvdir) -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 2; $stamp = [DateTime]::Now.Ticks
foreach ($s in $Shots.Split(';')) {
    if (-not $s) { continue }
    $name, $query = $s.Split('=', 2)
    $out = "$scratch\cap_$name.png"
    $p = Start-Process $edge -ArgumentList @('--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run', "--user-data-dir=$env:TEMP\edge_headless_prof_$stamp", '--window-size=2560,1440', '--virtual-time-budget=3500', "--screenshot=$out", "http://127.0.0.1:8897/index.html?$query&_nc=$stamp") -PassThru -WindowStyle Hidden
    if (-not $p.WaitForExit(40000)) { $p.Kill() }
    "{0}  {1}" -f (Split-Path $out -Leaf), (Get-Item $out -ErrorAction SilentlyContinue).Length
}
if ($Dom) {
    $domOut = "$scratch\cap_dom.html"
    Start-Process $edge -ArgumentList @('--headless=new', '--disable-gpu', '--no-first-run', "--user-data-dir=$env:TEMP\edge_headless_prof_$stamp", '--virtual-time-budget=3500', '--dump-dom', "http://127.0.0.1:8897/index.html?$Dom&_nc=$stamp") -Wait -WindowStyle Hidden -RedirectStandardOutput $domOut
    "dom bytes: " + (Get-Item $domOut).Length
}
Stop-Process -Id $srv.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1; Remove-Item -Recurse -Force "$env:TEMP\edge_headless_prof_$stamp" -ErrorAction SilentlyContinue   # a fresh browser profile per run: pictures are never taken from a cache
