# Prompt — Ejecutar .bat en la Caja por IP (protegido con PIN)

> Documento de especificación ("prompt") para implementar la función en **PlanillaVanguard**
> (WinForms, .NET 10 / `net10.0-windows`). Revisar y aprobar antes de programar.

---

## 1. Objetivo

Agregar a la aplicación una pantalla para **la caja** que permita, **ingresando únicamente la IP**
de la PC destino, ejecutar de forma remota un archivo `.bat` que se encuentra en una carpeta fija
de la unidad `C:` de esa PC. La acción debe estar **protegida por un PIN** antes de ejecutarse.

Ejemplo de ruta del .bat en la máquina destino:
```
C:\VanguardCaja\caja.bat
```
La ruta (carpeta + nombre del .bat) es **configurable** pero fija en operación: el usuario de la
caja solo escribe la **IP** y el **PIN**.

---

## 2. Contexto y supuestos

- Entorno: red local Windows (LAN). Las PCs de caja y la PC que corre PlanillaVanguard están en
  la misma red y el administrador tiene credenciales válidas sobre las cajas.
- Uso legítimo y autorizado: administración de equipos propios de la empresa. No es evasión ni
  acceso no autorizado.
- El `.bat` **ya existe** en cada caja, en la ruta configurada. Este proyecto **no** instala el
  `.bat`; solo lo **dispara**.
- Objetivo "solo ingresando la IP": la carpeta, el nombre del `.bat` y las credenciales de
  administrador se guardan en configuración; el operador solo teclea la IP (y el PIN).

---

## 3. Alcance

### Incluye
1. Pantalla/control en WinForms con:
   - Campo **IP** (con validación de formato IPv4).
   - Campo **PIN** (enmascarado).
   - Botón **Ejecutar**.
   - Área de **estado/registro** (log de resultado: OK / error, código de salida).
2. Validación del **PIN** antes de ejecutar (PIN correcto configurado de forma segura; ver §6).
3. Ejecución remota del `.bat` en la IP indicada.
4. Registro local de cada intento (fecha/hora, IP, usuario, resultado).

### No incluye
- Despliegue o copia del `.bat` a las cajas.
- Gestión de usuarios/roles compleja (más allá del PIN).
- Descubrimiento automático de cajas en la red.

---

## 4. Flujo de usuario

1. El operador abre la pantalla de **Caja**.
2. Escribe la **IP** de la caja destino.
3. Escribe el **PIN**.
4. Pulsa **Ejecutar**.
5. La app valida: IP con formato correcto + PIN correcto.
6. La app ejecuta el `.bat` remoto en `C:\<carpeta>\<archivo>.bat`.
7. Muestra el resultado (éxito / error + detalle) y lo guarda en el log.

---

## 5. Método de ejecución remota (a decidir — recomendación)

Elegir **uno** y dejarlo documentado. Recomendación por orden de preferencia:

- **Opción A (recomendada, sin herramientas externas): WMI** — `Win32_Process.Create` vía
  `System.Management`, apuntando a `\\<IP>\root\cimv2`, lanzando
  `cmd.exe /c "C:\<carpeta>\<archivo>.bat"`. Requiere credenciales de administrador de la caja
  y que WMI/DCOM esté habilitado y permitido por el firewall.
- **Opción B: WinRM / PowerShell remoting** — `Invoke-Command -ComputerName <IP>`. Requiere WinRM
  habilitado en las cajas (`Enable-PSRemoting`).
- **Opción C: PsExec** (Sysinternals) — requiere distribuir `PsExec.exe` y aceptar su EULA;
  evitar si se puede por dependencia externa.

> Nota: la ejecución remota necesita **credenciales** de la cuenta con permisos en la caja.
> Esas credenciales se guardan en configuración protegida (ver §6), no se piden en cada uso.

---

## 6. Seguridad

- **PIN**: no guardar en texto plano. Guardar un **hash** (p. ej. PBKDF2/SHA-256 con sal) en la
  configuración; comparar el hash al validar. PIN configurable por el administrador.
- **Credenciales de admin** de las cajas: almacenar cifradas (p. ej. **DPAPI** /
  `ProtectedData` por usuario-máquina), nunca en texto plano en el repositorio ni en el `.config`.
- **Validación de IP**: usar `IPAddress.TryParse` y restringir a rangos de la LAN si se desea.
- **Bloqueo por intentos**: tras N intentos de PIN fallidos, bloquear temporalmente la acción.
- **Auditoría**: registrar quién ejecutó, en qué IP y el resultado.
- No registrar el PIN ni las credenciales en el log.

---

## 7. Configuración (ejemplo)

Un archivo de configuración (p. ej. `appsettings` o `.config`) con:

| Clave              | Ejemplo                 | Descripción                                  |
|--------------------|-------------------------|----------------------------------------------|
| `BatFolder`        | `C:\VanguardCaja`       | Carpeta del `.bat` en la caja                |
| `BatFileName`      | `caja.bat`              | Nombre del `.bat`                            |
| `PinHash`          | `(hash+sal)`            | Hash del PIN                                 |
| `AdminUser`        | `.\Administrador`       | Usuario con permisos en la caja              |
| `AdminPasswordEnc` | `(cifrado DPAPI)`       | Contraseña cifrada                           |
| `MaxPinAttempts`   | `3`                     | Intentos antes de bloqueo                    |

---

## 8. Criterios de aceptación

- [ ] Ingresando una IP válida + PIN correcto, el `.bat` se ejecuta en la caja destino y la app
      muestra "Ejecutado correctamente" con el código de salida.
- [ ] Con PIN incorrecto, **no** se ejecuta nada y se muestra error; cuenta el intento.
- [ ] Con IP inválida, se muestra error de formato y no se intenta conexión.
- [ ] Si la caja no responde / sin permisos / firewall, se muestra un error claro (no excepción
      cruda) y queda en el log.
- [ ] El PIN y las credenciales **no** aparecen en texto plano en disco ni en el log.
- [ ] Compila en `net10.0-windows` y corre en Windows.

---

## 9. Entregables

1. Pantalla WinForms (nuevo `Form` o control) integrada en la app.
2. Clase/servicio de ejecución remota (según §5).
3. Validación de PIN + almacenamiento seguro (§6).
4. Configuración (§7) con valores de ejemplo (sin secretos reales).
5. Registro/log de ejecuciones.
6. Instrucciones breves de uso y de requisitos en la caja (WMI/WinRM/firewall).

---

## 10. Preguntas abiertas (confirmar antes de programar)

1. ¿Método de ejecución remota? (A = WMI recomendado / B = WinRM / C = PsExec)
2. ¿La carpeta y el nombre del `.bat` son siempre los mismos en todas las cajas?
3. ¿Usar un **único PIN** global o PIN por operador?
4. ¿Las credenciales de administrador son las mismas para todas las cajas?
5. ¿Debe esperar a que el `.bat` termine y capturar su salida, o solo lanzarlo ("fire and forget")?
