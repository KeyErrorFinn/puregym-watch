$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    npm.cmd ci
    if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed' }
    npm.cmd run typecheck
    if ($LASTEXITCODE -ne 0) { throw 'TypeScript check failed' }
    npm.cmd run lint
    if ($LASTEXITCODE -ne 0) { throw 'Lint failed' }
    npm.cmd test -- --watch=false
    if ($LASTEXITCODE -ne 0) { throw 'Phone tests failed' }
    & .\android\gradlew.bat -p android :protocol:testDebugUnitTest :wear:testDebugUnitTest :app:assembleRelease :wear:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build or tests failed' }
    New-Item -ItemType Directory -Force artifacts | Out-Null
    Copy-Item android/app/build/outputs/apk/release/app-release.apk artifacts/pg-access-phone.apk
    Copy-Item android/wear/build/outputs/apk/release/wear-release.apk artifacts/pg-access-watch.apk
} finally { Pop-Location }
