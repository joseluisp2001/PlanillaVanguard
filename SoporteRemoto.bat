@echo off
setlocal enabledelayedexpansion
title Soporte Remoto - Vanguard
color 0A

REM ============================================================
REM  SoporteRemoto.bat
REM  1) Registro: cada usuario guarda su NOMBRE e IP en el
REM     servidor \\10.48.10.9\Soporte\usuarios.txt
REM  2) Soporte remoto (con PIN): ejecutar / programar / correr
REM     / borrar una tarea en el equipo indicado por su IP.
REM  Usa WMIC y SCHTASKS (nativos de Windows).
REM ============================================================

REM ======================= CONFIGURACION ======================
REM Servidor y archivo central de usuarios
set "SERVIDOR=\\10.48.10.9\Soporte"
set "ARCHIVO_USUARIOS=%SERVIDOR%\usuarios.txt"

REM PIN que protege las acciones de soporte
set "PIN_CORRECTO=1234"

REM .bat que esta en el equipo remoto (ruta local de ese equipo)
set "CARPETA=C:\VanguardCaja"
set "ARCHIVO=caja.bat"

REM Nombre con el que se crea la tarea programada en el remoto
set "NOMBRE_TAREA=VanguardSoporte"

REM Credenciales de administrador del equipo remoto (opcional).
REM Si quedan vacias se usa tu sesion actual.
set "USUARIO="
set "CLAVE="
REM Ejemplo: set "USUARIO=.\Administrador"   set "CLAVE=miClave"

REM Prefijo de red de tu LAN (para detectar la IP correcta al registrar)
set "PREFIJO_LAN=10.48."
REM ============================================================

REM --- Armar credenciales segun la herramienta ---
set "WMICCRED="
set "STCRED="
if defined USUARIO set "WMICCRED=/user:"%USUARIO%" /password:"%CLAVE%""
if defined USUARIO set "STCRED=/u "%USUARIO%" /p "%CLAVE%""


:MENU
cls
echo ============================================
echo         SOPORTE REMOTO - VANGUARD
echo ============================================
echo   [1] Registrarme (guardar mi nombre e IP)
echo   [2] Soporte remoto (requiere PIN)
echo   [3] Ver usuarios registrados
echo   [0] Salir
echo ============================================
set "OP="
set /p "OP=Opcion: "
if "%OP%"=="1" goto REGISTRO
if "%OP%"=="2" goto PIN
if "%OP%"=="3" goto VER
if "%OP%"=="0" exit /b 0
goto MENU


REM ====================== REGISTRO ============================
:REGISTRO
cls
echo --- REGISTRO DE USUARIO ---
echo.
set "NOMBRE="
set /p "NOMBRE=Tu nombre: "
if "%NOMBRE%"=="" (
    echo [ERROR] El nombre no puede estar vacio.
    pause
    goto MENU
)

REM Detectar IP local (prefiere la del prefijo de la LAN)
set "MIIP="
set "IP_FALLBACK="
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do (
    set "tmp=%%a"
    set "tmp=!tmp: =!"
    set "IP_FALLBACK=!tmp!"
    echo !tmp! | findstr /b /c:"%PREFIJO_LAN%" >nul && set "MIIP=!tmp!"
)
if not defined MIIP set "MIIP=!IP_FALLBACK!"

echo.
echo IP detectada: !MIIP!
set "IPIN="
set /p "IPIN=Enter para usarla, o escribe otra IP: "
if not "!IPIN!"=="" set "MIIP=!IPIN!"

if "!MIIP!"=="" (
    echo [ERROR] No se pudo determinar la IP.
    pause
    goto MENU
)

REM Agregar la linea al archivo central del servidor
>>"%ARCHIVO_USUARIOS%" echo %DATE% %TIME% ^| !NOMBRE! ^| !MIIP! ^| %COMPUTERNAME%
if errorlevel 1 (
    echo.
    echo [ERROR] No se pudo escribir en %ARCHIVO_USUARIOS%
    echo   Revisa que tengas acceso de escritura al recurso compartido.
) else (
    echo.
    echo [OK] Registrado: !NOMBRE! - !MIIP!
)
echo.
pause
goto MENU


REM ====================== VER USUARIOS ========================
:VER
cls
echo --- USUARIOS REGISTRADOS ---
echo   (archivo: %ARCHIVO_USUARIOS%)
echo ------------------------------------------------------------
if exist "%ARCHIVO_USUARIOS%" (
    type "%ARCHIVO_USUARIOS%"
) else (
    echo No existe el archivo o no hay acceso al servidor.
)
echo ------------------------------------------------------------
echo.
pause
goto MENU


REM ====================== PIN SOPORTE =========================
:PIN
cls
set "PIN="
set /p "PIN=Ingrese PIN de soporte: "
if not "%PIN%"=="%PIN_CORRECTO%" (
    echo [ERROR] PIN incorrecto.
    pause
    goto MENU
)

:SOPORTE
cls
echo --- SOPORTE REMOTO ---
echo.
set "IP="
set /p "IP=IP del equipo (o Enter para ver la lista): "
if "%IP%"=="" goto VER_EN_SOPORTE

REM Verificar que responde
echo.
echo Verificando conexion con %IP% ...
ping -n 1 -w 1000 %IP% >nul
if errorlevel 1 (
    echo [ERROR] %IP% no responde ^(ping fallido^).
    echo.
    pause
    goto SOPORTE
)
echo Conexion OK.
echo.

:ACCIONES
echo ============================================
echo   Equipo: %IP%
echo   .bat  : %CARPETA%\%ARCHIVO%
echo ============================================
echo   [1] Ejecutar AHORA el .bat
echo   [2] Programar tarea (diario a una hora)
echo   [3] Correr tarea ya programada
echo   [4] Borrar tarea programada
echo   [0] Volver al menu
echo ============================================
set "AC="
set /p "AC=Accion: "
if "%AC%"=="1" goto ACC_EJEC
if "%AC%"=="2" goto ACC_PROG
if "%AC%"=="3" goto ACC_RUN
if "%AC%"=="4" goto ACC_DEL
if "%AC%"=="0" goto MENU
goto ACCIONES

:ACC_EJEC
echo.
echo Ejecutando en %IP% ...
wmic /node:"%IP%" %WMICCRED% process call create "cmd.exe /c \"%CARPETA%\%ARCHIVO%\""
call :RESULTADO
goto ACCIONES

:ACC_PROG
echo.
set "HORA="
set /p "HORA=Hora de ejecucion (HH:MM, ej 09:00): "
if "%HORA%"=="" ( echo [ERROR] Hora vacia. & pause & goto ACCIONES )
echo.
echo Programando tarea "%NOMBRE_TAREA%" en %IP% (diario %HORA%) ...
schtasks /create /s %IP% %STCRED% /tn "%NOMBRE_TAREA%" /tr "%CARPETA%\%ARCHIVO%" /sc daily /st %HORA% /ru SYSTEM /f
call :RESULTADO
goto ACCIONES

:ACC_RUN
echo.
echo Ejecutando la tarea "%NOMBRE_TAREA%" en %IP% ...
schtasks /run /s %IP% %STCRED% /tn "%NOMBRE_TAREA%"
call :RESULTADO
goto ACCIONES

:ACC_DEL
echo.
echo Borrando la tarea "%NOMBRE_TAREA%" en %IP% ...
schtasks /delete /s %IP% %STCRED% /tn "%NOMBRE_TAREA%" /f
call :RESULTADO
goto ACCIONES

:VER_EN_SOPORTE
cls
echo --- USUARIOS REGISTRADOS ---
if exist "%ARCHIVO_USUARIOS%" ( type "%ARCHIVO_USUARIOS%" ) else ( echo No hay acceso al servidor. )
echo.
pause
goto SOPORTE


REM ====================== SUBRUTINA ===========================
:RESULTADO
if errorlevel 1 (
    echo.
    echo [ERROR] La operacion fallo en %IP%.
    echo   Revisa: permisos de administrador, firewall, WMI/Programador de tareas.
) else (
    echo.
    echo [OK] Operacion enviada a %IP% correctamente.
)
echo.
pause
exit /b
