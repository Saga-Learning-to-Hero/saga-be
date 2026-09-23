<#
Disposable MySQL 8.4 validation for the V32/V33 BYOK migrations. The database is created below
the current user's temporary directory, never in a shared MySQL instance. On Windows, invoke with
the Maven wrapper (or pass a Maven executable through -MavenCommand).
#>
param(
    [string]$MysqlVersion = "8.4.4",
    [int]$Port = 33071,
    [string]$Database = "saga_v33_verify",
    [string]$MavenCommand = ".\mvnw.cmd"
)

$ErrorActionPreference = "Stop"
$workDir = Join-Path ([System.IO.Path]::GetTempPath()) ("saga-v33-mysql-" + [guid]::NewGuid())
$mysqlProcess = $null

function Invoke-Maven([string[]]$Arguments) {
    & $MavenCommand @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Maven failed: $Arguments" }
}

try {
    New-Item -ItemType Directory -Path $workDir | Out-Null
    $zip = Join-Path $workDir "mysql.zip"
    $url = "https://cdn.mysql.com/archives/mysql-8.4/mysql-$MysqlVersion-winx64.zip"
    Write-Host "== downloading MySQL $MysqlVersion no-install ZIP (~250MB) =="
    & curl.exe -fsSL $url -o $zip
    if ($LASTEXITCODE -ne 0) { throw "Unable to download MySQL archive." }
    Expand-Archive -LiteralPath $zip -DestinationPath $workDir

    $mysqlHome = Join-Path $workDir "mysql-$MysqlVersion-winx64"
    $dataDir = Join-Path $workDir "data"
    $mysql = Join-Path $mysqlHome "bin\mysql.exe"
    $mysqlAdmin = Join-Path $mysqlHome "bin\mysqladmin.exe"
    $mysqld = Join-Path $mysqlHome "bin\mysqld.exe"
    New-Item -ItemType Directory -Path $dataDir | Out-Null

    Write-Host "== initializing disposable datadir (root, no password) =="
    & $mysqld "--initialize-insecure" "--datadir=$dataDir" "--basedir=$mysqlHome"
    if ($LASTEXITCODE -ne 0) { throw "MySQL initialization failed." }
    Write-Host "== starting mysqld on 127.0.0.1:$Port =="
    $mysqlProcess = Start-Process -FilePath $mysqld -ArgumentList @(
        "--datadir=$dataDir", "--basedir=$mysqlHome", "--port=$Port", "--bind-address=127.0.0.1",
        "--pid-file=$(Join-Path $workDir 'mysqld.pid')", "--log-error=$(Join-Path $workDir 'mysqld.err')"
    ) -PassThru -WindowStyle Hidden
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        & $mysqlAdmin --user=root --host=127.0.0.1 "--port=$Port" ping --silent
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw "Disposable MySQL did not become ready." }

    & $mysql --user=root --host=127.0.0.1 "--port=$Port" -e "CREATE DATABASE $Database;"
    if ($LASTEXITCODE -ne 0) { throw "Unable to create disposable database." }
    $jdbcUrl = "jdbc:mysql://127.0.0.1:$Port/$Database"
    $flywayArgs = @(
        "org.flywaydb:flyway-maven-plugin:12.4.0:migrate", "-Dflyway.url=$jdbcUrl", "-Dflyway.user=root",
        "-Dflyway.password=", "-Dflyway.locations=filesystem:src/main/resources/db/migration"
    )
    Write-Host "== 1: migrate V1 -> V33 =="
    Invoke-Maven $flywayArgs
    $flywayArgs[0] = "org.flywaydb:flyway-maven-plugin:12.4.0:validate"
    Write-Host "== 2: validate Flyway history =="
    Invoke-Maven $flywayArgs

    $count = (& $mysql --skip-column-names --user=root --host=127.0.0.1 "--port=$Port" $Database -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL;").Trim()
    if ($count -ne "33") { throw "Expected 33 versioned migrations, got $count." }
    Write-Host "== 3: verify BYOK DDL invariants =="
    & $mysql --user=root --host=127.0.0.1 "--port=$Port" $Database -e "SHOW CREATE TABLE ai_course_settings; SHOW CREATE TABLE ai_course_provider_credential; SHOW CREATE TABLE ai_analysis_provider_decision;"
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect BYOK tables." }

    Write-Host "== 4: Hibernate ddl-auto=validate through V33 =="
    Invoke-Maven @("test", "-Dtest=com.saga.be.repository.V33MysqlHibernateValidateIT", "-Dsaga.verify.mysql=true", "-Dsaga.verify.mysql.url=$jdbcUrl")
    Write-Host "PASS: V33 MySQL Hibernate validation"
}
finally {
    if ($mysqlProcess -and !$mysqlProcess.HasExited) { Stop-Process -Id $mysqlProcess.Id -Force }
    if (Test-Path -LiteralPath $workDir) { Remove-Item -LiteralPath $workDir -Recurse -Force }
}
