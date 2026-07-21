@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "OUT_DIR=%~1"
if not defined OUT_DIR set "OUT_DIR=%SCRIPT_DIR%build"
set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set "ZLIB_ROOT=C:\Users\h\miniconda3\Library"
set "SOURCE=%SCRIPT_DIR%native_pbf_extractor.cpp"

if exist "%VCVARS%" goto vcvars_ok
echo Missing Visual Studio environment: %VCVARS% 1>&2
exit /b 2
:vcvars_ok
if exist "%ZLIB_ROOT%\include\zlib.h" goto zlib_header_ok
echo Missing zlib header under %ZLIB_ROOT% 1>&2
exit /b 2
:zlib_header_ok
if exist "%ZLIB_ROOT%\lib\zlibstatic.lib" goto zlib_library_ok
echo Missing static zlib library under %ZLIB_ROOT% 1>&2
exit /b 2
:zlib_library_ok
if exist "%SOURCE%" goto source_ok
echo Missing extractor source: %SOURCE% 1>&2
exit /b 2
:source_ok
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%" || exit /b 2

for /f %%H in ('powershell.exe -NoProfile -Command "(Get-FileHash -Algorithm SHA256 -LiteralPath '%SOURCE%').Hash.ToLowerInvariant()"') do set "SOURCE_SHA256=%%H"
if defined SOURCE_SHA256 goto source_hash_ok
echo Failed to hash extractor source. 1>&2
exit /b 2
:source_hash_ok

call "%VCVARS%" >nul || exit /b 2
cl.exe /nologo /std:c++20 /O2 /EHsc /W4 /MD /D_CRT_SECURE_NO_WARNINGS /DFA_SOURCE_SHA256=\"%SOURCE_SHA256%\" /I"%ZLIB_ROOT%\include" "%SOURCE%" /Fo"%OUT_DIR%\native_pbf_extractor.obj" /Fe"%OUT_DIR%\native_pbf_extractor.exe" /link /LIBPATH:"%ZLIB_ROOT%\lib" zlibstatic.lib bcrypt.lib
exit /b %ERRORLEVEL%
