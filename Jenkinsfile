pipeline {

    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    environment {
        SERVICE_ID = 'quarkus-ipscan-api'
        SERVICE_NAME = 'Quarkus IP Scan API'
        SERVICE_DESCRIPTION = 'Quarkus IP Scan API'

        DEPLOY_DIR = 'D:\\microservices\\quarkus-ipscan-api'
        PORT = '5054'

        // El proyecto Quarkus esta compilado para Java 17
        JAVA_HOME = 'D:\\java\\jdk-17.0.5+8'
        PATH = "${JAVA_HOME}\\bin;${env.PATH}"
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Environment') {
            steps {
                bat '''
                    @echo off

                    echo ============================
                    echo ===== Environment =========
                    echo ============================

                    echo.
                    echo ===== JAVA =====
                    echo JAVA_HOME=%JAVA_HOME%
                    where java
                    java -version

                    if errorlevel 1 (
                        echo ERROR: Java not found
                        exit /b 1
                    )

                    echo.
                    echo ===== JAVA 17 EXPLICIT =====
                    "%JAVA_HOME%\\bin\\java.exe" -version

                    if errorlevel 1 (
                        echo ERROR: Java 17 not found in JAVA_HOME
                        exit /b 1
                    )

                    echo.
                    echo ===== GIT =====
                    where git
                    git --version

                    if errorlevel 1 (
                        echo ERROR: Git not found
                        exit /b 1
                    )

                    echo.
                    echo ===== PROJECT =====

                    if not exist build.gradle (
                        if not exist build.gradle.kts (
                            echo ERROR: build.gradle or build.gradle.kts not found
                            exit /b 1
                        )
                    )

                    if not exist gradlew.bat (
                        echo ERROR: gradlew.bat not found
                        exit /b 1
                    )
                '''
            }
        }

        stage('Gradle Version') {
            steps {
                bat '''
                    @echo off

                    echo ============================
                    echo ===== Gradle ===============
                    echo ============================

                    echo Java used by Jenkins:
                    "%JAVA_HOME%\\bin\\java.exe" -version

                    echo.
                    echo Gradle version:
                    call gradlew.bat --version

                    if errorlevel 1 (
                        echo ERROR: Gradle wrapper failed
                        exit /b 1
                    )
                '''
            }
        }

        stage('Build') {
            steps {
                bat '''
                    @echo off

                    echo ============================
                    echo ===== Quarkus Build ========
                    echo ============================

                    call gradlew.bat clean build -x test

                    if errorlevel 1 (
                        echo ERROR: Quarkus build failed
                        exit /b 1
                    )
                '''
            }
        }

        stage('Verify Build') {
            steps {
                bat '''
                    @echo off

                    echo ============================
                    echo ===== Verify Build =========
                    echo ============================

                    if not exist "build\\quarkus-app\\quarkus-run.jar" (
                        echo ERROR: quarkus-run.jar not found
                        exit /b 1
                    )

                    if not exist "build\\quarkus-app\\lib" (
                        echo ERROR: Quarkus lib directory not found
                        exit /b 1
                    )

                    if not exist "build\\quarkus-app\\app" (
                        echo ERROR: Quarkus app directory not found
                        exit /b 1
                    )

                    if not exist "build\\quarkus-app\\quarkus" (
                        echo ERROR: Quarkus directory not found
                        exit /b 1
                    )

                    echo Quarkus distribution OK
                    dir "build\\quarkus-app"
                '''
            }
        }

        stage('Stop Service') {
            steps {
                bat '''
                    @echo off

                    echo ============================
                    echo ===== Stop Service =========
                    echo ============================

                    "%PYTHON_HOME%\\python.exe" ^
                        "%SERVICE_MANAGER%" ^
                        stop ^
                        "%SERVICE_ID%"

                    exit /b 0
                '''
            }
        }

        stage('Deploy') {
            steps {
                powershell '''
                    Write-Host "=============================="
                    Write-Host "===== Deploy Quarkus ========="
                    Write-Host "=============================="

                    $source = "$env:WORKSPACE\\build\\quarkus-app"
                    $destination = $env:DEPLOY_DIR

                    if (-not (Test-Path $source)) {
                        throw "Quarkus distribution not found: $source"
                    }

                    if (-not (Test-Path $destination)) {
                        New-Item `
                            -ItemType Directory `
                            -Path $destination `
                            -Force | Out-Null
                    }

                    robocopy `
                        $source `
                        $destination `
                        /MIR

                    $code = $LASTEXITCODE

                    if ($code -gt 7) {
                        throw "Robocopy failed with exit code $code"
                    }

                    Write-Host "Quarkus deployed:"
                    Write-Host $destination

                    exit 0
                '''
            }
        }

        stage('Configure Service') {
            steps {
                powershell '''
                    Write-Host "=============================="
                    Write-Host "===== Configure Service ======"
                    Write-Host "=============================="

                    $javaExe = "$env:JAVA_HOME\\bin\\java.exe"

                    if (-not (Test-Path $javaExe)) {
                        throw "Java 17 executable not found: $javaExe"
                    }

                    Write-Host "Java used by Windows service:"
                    & $javaExe -version

                    & "$env:PYTHON_HOME\\python.exe" `
                        "$env:SERVICE_MANAGER" `
                        install `
                        "$env:SERVICE_ID" `
                        "$env:DEPLOY_DIR" `
                        --name "$env:SERVICE_NAME" `
                        --description "$env:SERVICE_DESCRIPTION" `
                        --type rust `
                        --executable "$javaExe" `
                        --args "-Dquarkus.http.port=$env:PORT -jar quarkus-run.jar"

                    if ($LASTEXITCODE -ne 0) {
                        throw "Service configuration failed"
                    }
                '''
            }
        }

        stage('Start Service') {
            steps {
                bat '''
                    @echo off

                    echo ============================
                    echo ===== Start Service ========
                    echo ============================

                    "%PYTHON_HOME%\\python.exe" ^
                        "%SERVICE_MANAGER%" ^
                        start ^
                        "%SERVICE_ID%"

                    if errorlevel 1 (
                        echo ERROR: Could not start Quarkus service
                        exit /b 1
                    )
                '''
            }
        }

        stage('Verify Service') {
            steps {
                bat '''
                    @echo off

                    echo ============================
                    echo ===== Verify Service ======
                    echo ============================

                    sc query "%SERVICE_ID%"

                    sc query "%SERVICE_ID%" | findstr /I "RUNNING"

                    if errorlevel 1 (
                        echo ERROR: Quarkus service is not running
                        exit /b 1
                    )

                    echo.
                    echo ===== Port %PORT% =====

                    netstat -ano | findstr ":%PORT%"

                    if errorlevel 1 (
                        echo ERROR: Quarkus API is not listening on port %PORT%
                        exit /b 1
                    )
                '''
            }
        }

        stage('Health Check') {
            steps {
                powershell '''
                    $url = "http://127.0.0.1:$env:PORT/"
                    $maxAttempts = 10

                    for (
                        $attempt = 1;
                        $attempt -le $maxAttempts;
                        $attempt++
                    ) {

                        Write-Host "===================================="
                        Write-Host "Health check $attempt/$maxAttempts"
                        Write-Host "GET $url"

                        try {

                            $response = Invoke-WebRequest `
                                -UseBasicParsing `
                                -Uri $url `
                                -TimeoutSec 10

                            Write-Host "HTTP: $($response.StatusCode)"

                            if ($response.StatusCode -eq 200) {
                                Write-Host "Quarkus IP Scan API OK"
                                exit 0
                            }

                        }
                        catch {
                            Write-Host $_.Exception.Message
                        }

                        if ($attempt -lt $maxAttempts) {
                            Start-Sleep -Seconds 3
                        }
                    }

                    throw "Quarkus IP Scan API failed health check."
                '''
            }
        }
    }

    post {

        success {
            echo 'Quarkus IP Scan API deployed successfully.'
            echo 'Internal URL: http://127.0.0.1:8080/'
        }

        failure {
            echo 'Quarkus IP Scan API deployment FAILED.'
        }
    }
}