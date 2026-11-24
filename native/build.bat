@echo off
rem Builds vendor\nvidia\glow_nvidia.dll with Visual Studio 2022. Run from anywhere.
setlocal
set ROOT=%~dp0..
set SDK=%ROOT%\vendor\nvidia\streamline-2.14.16
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
if not exist "%ROOT%\build\native" mkdir "%ROOT%\build\native"
cl /nologo /std:c++17 /O2 /EHsc /MD /W3 /I"%SDK%\include" "%~dp0glow_nvidia.cpp" /Fo"%ROOT%\build\native\\" /LD /Fe"%ROOT%\vendor\nvidia\glow_nvidia.dll" /link /IMPLIB:"%ROOT%\build\native\glow_nvidia.lib" wintrust.lib crypt32.lib user32.lib
