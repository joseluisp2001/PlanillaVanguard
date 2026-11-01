@echo off
setlocal enabledelayedexpansion
title Ejecutar BAT en Caja remota
color 0A

REM ============================================================
REM  EjecutarCajaRemota.bat
REM  Ejecuta un .bat que esta en C:\<carpeta> de una PC de caja,
REM  de forma remota, ingresando solo el PIN y la IP.
REM  Usa WMIC (nativo de Windows, no instala nada).
REM ============================================================

REM ====================== CONFIGURACION =======================
REM Carpeta y nombre del .bat EN LA CAJA (ruta local de la caja)
set "CARPETA=C:\VanguardCaja"
set "ARCHIVO=caja.bat"

REM PIN para autorizar la ejecucion
set "PIN_CORRECTO=1234"

REM Credenciales de administrador de la caja (opcional).
REM Si las dejas vacias, se usa el usuario actual de esta PC.
set "USUARIO="
set "CLAVE="
REM Ejemplo: set "USUARIO=.\Administrador"   set "CLAVE=miClave"
REM ============================================================

:PIN
echo.
set "PIN="
set /p "PIN=Ingrese PIN: "
if not "%PIN%"=="%PIN_CORRECTO%" (
    echo [ERROR] PIN incorrecto.
    echo.
    pause
    exit /b 1
)

:IP
echo.
set "IP="
set /p "IP=Ingrese la IP de la caja: "
if "%IP%"=="" (
    echo [ERROR] Debe ingresar una IP.
    goto IP
)

REM --- Verificar que la caja responde ---
echo.
echo Verificando conexion con %IP% ...
ping -n 1 -w 1000 %IP% >nul
if errorlevel 1 (
    echo [ERROR] La caja %IP% no responde ^(ping fallido^).
    echo.
    pause
    exit /b 1
)
echo Conexion OK.

REM --- Armar comando remoto ---
set "COMANDO=cmd.exe /c \"%CARPETA%\%ARCHIVO%\""

echo.
echo Ejecutando "%CARPETA%\%ARCHIVO%" en %IP% ...
echo.

if "%USUARIO%"=="" (
    wmic /node:"%IP%" process call create "%COMANDO%"
) else (
    wmic /node:"%IP%" /user:"%USUARIO%" /password:"%CLAVE%" process call create "%COMANDO%"
)

if errorlevel 1 (
    echo.
    echo [ERROR] No se pudo ejecutar en %IP%.
    echo   Revise: permisos de administrador, firewall, WMI habilitado.
) else (
    echo.
    echo [OK] Comando enviado a %IP%.
    echo   ^(ReturnValue = 0 arriba significa que se lanzo correctamente^)
)

echo.
pause
exit /b 0
