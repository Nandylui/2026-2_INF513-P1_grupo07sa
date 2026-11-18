# Sistema Vía Mail Básico — Consejo Facultativo (GRUPO07SA)

**INF513 Tecnología Web · UAGRM FICCT · Primer parcial**

Sistema *Email-Driven* en Java puro: el usuario envía un comando en el **asunto** de un correo, el sistema lo lee por POP3, lo ejecuta contra PostgreSQL y responde por SMTP al mismo remitente (texto + HTML).

Cubre los 8 casos de uso del Consejo Facultativo:

| CU | Alcance |
|----|---------|
| CU1 | Usuarios y roles |
| CU2 | Miembros, mandatos y quórum |
| CU3 | Convocatorias (48 h), excusas y suplentes |
| CU4 | Repositorio documental, versiones y workflow |
| CU5 | Asistencia, quórum y votaciones |
| CU6 | Resoluciones, veto y actas (hash SHA-256) |
| CU7 | Fiscalización, auditoría y Gaceta |
| CU8 | Reportes |

## 1. Arquitectura

```
 Remitente (Gmail)                 Servidor tecnoweb.org.bo                 Sistema Java
 ───────────────                   ────────────────────────                 ───────────────────────────────
 asunto: COMANDO["p1",...] ──SMTP──▶ buzón grupo07sa ◀──POP3 (110, cada 30 s)── EmailReader
                                                                                   │
                                                                                   ▼
                                                                           CommandParser  (analizador léxico/sintáctico)
                                                                                   │
                                                                                   ▼
                                                                           CommandExecutor ── valida parámetros, permisos
                                                                                   │          registra access.log / auditoría
                                                                                   ▼
                                                                           Registro → Módulo (CU1..CU8)
                                                                                   │
                                                                                   ▼
                                                                           DatabaseOperations (PreparedStatement)
                                                                                   │
                                                                                   ▼
                                                                           PostgreSQL
 respuesta (texto + HTML) ◀──SMTP (25)── EmailSender ◀── CommandResult ◀──────────┘
```

Paquetes (`src/`):

- `config` — `AppConfig` lee `config.properties` (sin credenciales en el código).
- `email` — `EmailReader` (POP3), `EmailSender` (SMTP), `EmailConfig`.
- `commands` — `CommandParser`, `CommandExecutor`, `Registro`, `CommandResult`, módulos `ModuloUsuarios` (CU1+CU2), `ModuloSesiones` (CU3), `ModuloDocumentos` (CU4), `ModuloAsistencia` (CU5), `ModuloResoluciones` (CU6), `ModuloFiscalizacion` (CU7), `ModuloReportes` (CU8), `ModuloSistema`.
- `database` — `DatabaseConnection`, `DatabaseOperations`, `DatabaseInitializer`, `BackupManager`, `Auditoria`.
- `util` — `AppLogger`, `Util`.
- `main` — `SistemaPrincipal` (servicio), `SimuladorComandos` (pruebas sin correo), `Respaldo` (CLI), `GenerarCorreoPruebas`.

### Flujo del analizador

```
asunto ──▶ normaliza (comillas tipográficas, espacios)
       ──▶ regex  ^NOMBRE\s*(\[ ... \])?$
       ──▶ tokenizador de parámetros ("a","b c","con \"comillas\"")
       ──▶ ¿existe el comando en Registro? ── no ──▶ respuesta ERROR + ejemplos
       ──▶ ¿nº de parámetros entre min y max? ── no ──▶ respuesta ERROR
       ──▶ ¿permiso del remitente? (si seguridad.validar_remitente=true)
       ──▶ handler del módulo ──▶ CommandResult ──▶ correo
```

Si el asunto no trae un comando válido, se busca el comando en el cuerpo del correo. Los correos automáticos (`Auto-Submitted`) se ignoran. El mensaje se borra del buzón **solo después** de responder; si la BD no responde, queda en el servidor y se reintenta.

## 2. Base de datos

Scripts en `database/`:

- `schema.sql` — tablas (idempotente): `rol`, `usuario`, `mandato`, `sesion`, `punto_orden_dia`, `excusa`, `habilitacion`, `tipo_tramite`, `flujo_paso`, `documento`, `documento_version`, `documento_historial`, `asistencia`, `votacion`, `resolucion`, `acta`, `gaceta`, `auditoria`.
- `seed.sql` — roles y trámites/flujos base.
- `datos_demo.sql` — usuarios, mandatos y datos de demostración (comando `CARGARDEMO`).
- `guion_pruebas.txt` — guion de extremo a extremo para `SimuladorComandos`.

El sistema crea las tablas y los datos base **solo** al conectarse (`DatabaseInitializer`).

## 3. Instalación y configuración

Requisitos: JDK 17+ (STS usa JavaSE-25), PostgreSQL, y los jars de `lib/` (`javax.mail-1.6.2`, `activation-1.1.1`, `postgresql-42.7.1`).

1. Importar el proyecto en Spring Tool Suite (*File > Import > Existing Projects*).
2. Crear la BD local (`db_prueba_tecno`) en PostgreSQL.
3. Copiar `config.properties.example` a `config.properties` y completar claves.
4. Ejecutar `main.SistemaPrincipal` (Run As > Java Application).

### Pasar de local a remoto (cuando habiliten la IP)

Solo cambiar en `config.properties`:

```
perfil=remoto
```

y verificar `bd.remoto.url`, `bd.remoto.usuario`, `bd.remoto.clave` y `correo.*`. No se toca código. Si el servidor remoto cae y `bd.contingencia=true`, el sistema trabaja con la BD local mientras tanto y vuelve al principal cuando responde.

### Probar sin correo

- `main.SimuladorComandos` (consola interactiva) o `main.SimuladorComandos database/guion_pruebas.txt`.
- `main.GenerarCorreoPruebas` genera `correo_pruebas/correo_pruebas.html` y `.md` (tabla COMANDO | DESCRIPCION | EJEMPLO por CU) para enviar al docente.

## 4. Respaldo y recuperación

- Respaldo automático cada `backup.intervalo_minutos` en `backups/` (archivos `.sql` con `INSERT`, rotación `backup.conservar`).
- Si la BD aparece vacía (limpiada o recreada), se recrea el esquema y se **restaura sola** desde el último respaldo.
- Manual: `main.Respaldo respaldar | listar | restaurar [archivo]`, o por correo `RESPALDAR`, `OBTENERRESPALDOS`.

## 5. Logs

`logs/access.log` (cada comando: remitente, comando, resultado, ms) y `logs/error.log` (excepciones). Además, la tabla `auditoria` guarda las operaciones de escritura.

## 6. Comandos

Los nombres están en español (como el resto del sistema); el analizador no distingue mayúsculas. Enviar `HELP` o `HELP["CU3"]` para ver el catálogo (`HELP` es el único comando en inglés, como pide el enunciado de referencia; `AYUDA` también se acepta como alias). Ejemplos:

```
OBTENERUSUARIOS["*"]
REGISTRARUSUARIO["9876543","Maria","Gomez","maria@gmail.com","SECRETARIO"]
REGISTRARSESION["2026-12-01 09:00","Sala del Consejo","ORDINARIA"]
REGISTRARVOTO["2000001","1","1","A_FAVOR"]
```

## 7. Reglas de negocio principales

Mandatos de 2 años sin reelección inmediata; quórum = mitad + 1 de escaños; convocatoria con ≥ 48 h; excusa habilita al suplente; un voto por persona e inmodificable; mayoría ABSOLUTA o DOS_TERCIOS; promulgación/veto en 5 días hábiles; veto superable por 2/3; acta inalterable con hash SHA-256; Gaceta correlativa.

## 8. Entrega

`2026-2_INF513-P1_grupo07sa.zip` (código fuente) + documento PUDS + este repositorio público en GitHub.
