@echo off
chcp 936 >nul 2>&1
title Dou+ Push Tool

echo ========================================
echo   Dou+ - Push to GitHub
echo ========================================
echo.

where git >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Git not found.
    echo Download: https://git-scm.com/download/win
    pause
    exit /b 1
)

if not exist "build.gradle.kts" (
    echo [ERROR] Please run this script inside dou+ folder.
    pause
    exit /b 1
)

set /p GITHUB_USER=GitHub username: 
set /p REPO_NAME=Repo name (default: dou+): 
if "%REPO_NAME%"=="" set REPO_NAME=dou+
set /p TOKEN=Personal Access Token: 

echo.
echo Target: https://github.com/%GITHUB_USER%/%REPO_NAME%.git
echo.
set /p CONFIRM=Continue? (Y/N): 
if /i not "%CONFIRM%"=="Y" (
    echo Cancelled.
    pause
    exit /b 0
)

echo.
echo [1/4] Init git repo...
if not exist ".git" (
    git init
    git checkout -b main
) else (
    echo Already initialized.
)

echo.
echo [2/4] Commit files...
git add .
git commit -m "init: Dou+ LSPosed module"

echo.
echo [3/4] Add remote...
git remote remove origin 2>nul
git remote add origin https://%GITHUB_USER%:%TOKEN%@github.com/%GITHUB_USER%/%REPO_NAME%.git

echo.
echo [4/4] Push to GitHub...
git push -u origin main

if %errorlevel% equ 0 (
    echo.
    echo ========================================
    echo   SUCCESS!
    echo ========================================
    echo.
    echo   Repo:    https://github.com/%GITHUB_USER%/%REPO_NAME%
    echo   Actions: https://github.com/%GITHUB_USER%/%REPO_NAME%/actions
    echo.
    echo   Wait for build, then download APK from Artifacts.
    echo.
    echo [SECURITY] Removing token from remote URL...
    git remote set-url origin https://github.com/%GITHUB_USER%/%REPO_NAME%.git
    echo   Token removed from local config.
    echo.
) else (
    echo.
    echo [ERROR] Push failed.
    echo.
    echo Possible causes:
    echo   - Token invalid or expired
    echo   - Repo does not exist (create it on GitHub first)
    echo   - Token missing 'repo' permission
    echo.
)

pause
