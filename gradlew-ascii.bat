@echo off
setlocal

rem Run Gradle through an ASCII drive path. AIDL's dependency parser fails
rem when this checkout is opened through a path containing Cyrillic characters.
set "DRIVE="
for %%D in (S U V Y) do if not defined DRIVE if not exist %%D:\ set "DRIVE=%%D:"
if not defined DRIVE (
  echo No free temporary drive letter is available. Close an unused mapped drive and retry.
  exit /b 2
)

set "CACHE=%TEMP%\onlineseti-gradle-project-cache-%RANDOM%-%RANDOM%"
subst %DRIVE% "%~dp0"
if errorlevel 1 exit /b %errorlevel%
pushd %DRIVE%\
call gradlew.bat --project-cache-dir "%CACHE%" %*
set "RESULT=%ERRORLEVEL%"
popd
subst %DRIVE% /d
rmdir /s /q "%CACHE%" 2>nul
exit /b %RESULT%
