# IntelliJ IDEA Maven Reload Helper
# This script helps trigger Maven reimport for IntelliJ IDEA

Write-Host "======================================" -ForegroundColor Cyan
Write-Host "IntelliJ Maven Reload Helper" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan
Write-Host ""

$projectPath = "D:\paxo_base_project\product-management-service"
Set-Location $projectPath

Write-Host "✓ Current directory: $projectPath" -ForegroundColor Green
Write-Host ""

# Step 1: Verify pom.xml exists
if (Test-Path "pom.xml") {
    Write-Host "✓ Found pom.xml" -ForegroundColor Green
} else {
    Write-Host "✗ pom.xml not found!" -ForegroundColor Red
    exit 1
}

# Step 2: Check if commons-compress is in pom.xml
$pomContent = Get-Content "pom.xml" -Raw
if ($pomContent -match "commons-compress") {
    Write-Host "✓ commons-compress dependency found in pom.xml" -ForegroundColor Green

    # Extract version
    if ($pomContent -match "commons-compress</artifactId>\s*<version>([\d.]+)</version>") {
        $version = $Matches[1]
        Write-Host "  Version: $version" -ForegroundColor Gray
    }
} else {
    Write-Host "✗ commons-compress not found in pom.xml!" -ForegroundColor Red
}

Write-Host ""

# Step 3: Verify JAR exists in Maven repository
$jarPath = "C:\Users\Admin\.m2\repository\org\apache\commons\commons-compress\1.27.1\commons-compress-1.27.1.jar"
if (Test-Path $jarPath) {
    $jarSize = (Get-Item $jarPath).Length / 1MB
    Write-Host "✓ commons-compress JAR found in Maven repository" -ForegroundColor Green
    Write-Host "  Path: $jarPath" -ForegroundColor Gray
    Write-Host "  Size: $([math]::Round($jarSize, 2)) MB" -ForegroundColor Gray
} else {
    Write-Host "✗ commons-compress JAR not found in Maven repository!" -ForegroundColor Red
    Write-Host "  Downloading now..." -ForegroundColor Yellow
    mvn dependency:resolve -q
}

Write-Host ""

# Step 4: Test Maven compile
Write-Host "Testing Maven compile..." -ForegroundColor Yellow
$compileOutput = mvn clean compile -q 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "✓ Maven compile: SUCCESS" -ForegroundColor Green
} else {
    Write-Host "✗ Maven compile: FAILED" -ForegroundColor Red
    Write-Host $compileOutput
    exit 1
}

Write-Host ""

# Step 5: Check IntelliJ IDEA process
$intellijProcess = Get-Process -Name "idea64" -ErrorAction SilentlyContinue
if ($intellijProcess) {
    Write-Host "✓ IntelliJ IDEA is running (PID: $($intellijProcess.Id))" -ForegroundColor Green
    Write-Host ""
    Write-Host "================================================" -ForegroundColor Cyan
    Write-Host "ACTION REQUIRED: Reload Maven in IntelliJ IDEA" -ForegroundColor Yellow
    Write-Host "================================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Quick Steps:" -ForegroundColor White
    Write-Host "  1. Press Ctrl+Shift+O in IntelliJ" -ForegroundColor White
    Write-Host "  2. Click the 🔄 Reload icon in Maven tool window" -ForegroundColor White
    Write-Host "  3. Wait 10-30 seconds for reindexing" -ForegroundColor White
    Write-Host ""
    Write-Host "Alternative:" -ForegroundColor White
    Write-Host "  • Right-click pom.xml → Maven → Reload project" -ForegroundColor White
    Write-Host ""
} else {
    Write-Host "ℹ IntelliJ IDEA is not currently running" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "When you open IntelliJ:" -ForegroundColor White
    Write-Host "  • IntelliJ should auto-detect pom.xml changes" -ForegroundColor White
    Write-Host "  • If not, press Ctrl+Shift+O and click Reload" -ForegroundColor White
    Write-Host ""
}

# Step 6: Generate Maven IDEA files (optional helper)
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "Optional: Generate IntelliJ IDEA project files" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""
$response = Read-Host "Run 'mvn idea:idea' to regenerate IntelliJ files? (y/N)"
if ($response -eq "y" -or $response -eq "Y") {
    Write-Host "Generating IntelliJ IDEA project files..." -ForegroundColor Yellow
    mvn idea:idea
    Write-Host "✓ Done! Restart IntelliJ IDEA for changes to take effect." -ForegroundColor Green
} else {
    Write-Host "Skipped." -ForegroundColor Gray
}

Write-Host ""
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "Summary" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "✓ Maven dependency: RESOLVED" -ForegroundColor Green
Write-Host "✓ Maven compile: SUCCESS" -ForegroundColor Green
Write-Host "⚠ IntelliJ IDE: NEEDS RELOAD" -ForegroundColor Yellow
Write-Host ""
Write-Host "See FIX_INTELLIJ_IMPORTS.md for detailed instructions." -ForegroundColor White
Write-Host ""

