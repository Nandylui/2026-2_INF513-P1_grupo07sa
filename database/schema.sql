-- =====================================================================
-- SISTEMA CONSEJO FACULTATIVO - Vía Mail (INF513 Tecnología Web, grupo07sa)
-- Esquema PostgreSQL. ES IDEMPOTENTE: se puede ejecutar muchas veces.
--  * Si la BD está vacía, crea todo.
--  * Si ya existen las tablas base del grupo (rol, usuario, mandato,
--    sesion, excusa, documento, asistencia, votacion), las respeta y solo
--    agrega las columnas/tablas que falten (CU4, CU6, CU7, CU8).
-- =====================================================================

-- ---------- CU1: usuarios y roles ----------
CREATE TABLE IF NOT EXISTS rol (
    id_rol      SERIAL PRIMARY KEY,
    nombre      VARCHAR(50) UNIQUE NOT NULL,
    descripcion TEXT
);

CREATE TABLE IF NOT EXISTS usuario (
    id_usuario SERIAL PRIMARY KEY,
    ci         VARCHAR(20)  UNIQUE NOT NULL,
    nombres    VARCHAR(100) NOT NULL,
    apellidos  VARCHAR(100) NOT NULL,
    correo     VARCHAR(150) UNIQUE NOT NULL,
    id_rol     INT REFERENCES rol(id_rol),
    estado     VARCHAR(20) DEFAULT 'ACTIVO'
);
ALTER TABLE usuario ADD COLUMN IF NOT EXISTS fecha_registro TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- ---------- CU2: mandatos (titular + suplente, 2 años) ----------
CREATE TABLE IF NOT EXISTS mandato (
    id_mandato   SERIAL PRIMARY KEY,
    id_titular   INT NOT NULL REFERENCES usuario(id_usuario),
    id_suplente  INT REFERENCES usuario(id_usuario),
    carrera      VARCHAR(100) NOT NULL,
    fecha_inicio DATE NOT NULL,
    fecha_fin    DATE NOT NULL,
    estado       VARCHAR(20) DEFAULT 'VIGENTE'
);

-- ---------- CU3: sesiones, excusas y suplentes ----------
CREATE TABLE IF NOT EXISTS sesion (
    id_sesion   SERIAL PRIMARY KEY,
    fecha_hora  TIMESTAMP NOT NULL,
    lugar       VARCHAR(255) NOT NULL,
    tipo        VARCHAR(50)  NOT NULL,
    estado      VARCHAR(20) DEFAULT 'PROGRAMADA'
);
ALTER TABLE sesion ADD COLUMN IF NOT EXISTS solicitantes       TEXT;
ALTER TABLE sesion ADD COLUMN IF NOT EXISTS fecha_convocatoria TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE sesion ADD COLUMN IF NOT EXISTS quorum_requerido   INT;
ALTER TABLE sesion ADD COLUMN IF NOT EXISTS hora_inicio        TIMESTAMP;
ALTER TABLE sesion ADD COLUMN IF NOT EXISTS hora_fin           TIMESTAMP;

CREATE TABLE IF NOT EXISTS punto_orden_dia (
    id_punto    SERIAL PRIMARY KEY,
    id_sesion   INT NOT NULL REFERENCES sesion(id_sesion),
    orden       INT NOT NULL,
    descripcion TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS excusa (
    id_excusa      SERIAL PRIMARY KEY,
    id_titular     INT REFERENCES usuario(id_usuario),
    id_sesion      INT REFERENCES sesion(id_sesion),
    motivo         TEXT NOT NULL,
    fecha_registro TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Suplente habilitado para una sesión (se crea al registrar una excusa)
CREATE TABLE IF NOT EXISTS habilitacion (
    id_habilitacion SERIAL PRIMARY KEY,
    id_sesion       INT NOT NULL REFERENCES sesion(id_sesion),
    id_excusa       INT REFERENCES excusa(id_excusa) ON DELETE CASCADE,
    id_titular      INT NOT NULL REFERENCES usuario(id_usuario),
    id_suplente     INT NOT NULL REFERENCES usuario(id_usuario),
    fecha           TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ---------- CU4: repositorio documental, 48 h y workflow ----------
CREATE TABLE IF NOT EXISTS tipo_tramite (
    id_tramite  SERIAL PRIMARY KEY,
    nombre      VARCHAR(80) UNIQUE NOT NULL,
    descripcion TEXT
);

CREATE TABLE IF NOT EXISTS flujo_paso (
    id_paso         SERIAL PRIMARY KEY,
    id_tramite      INT NOT NULL REFERENCES tipo_tramite(id_tramite),
    orden           INT NOT NULL,
    nombre          VARCHAR(80) NOT NULL,
    rol_responsable VARCHAR(50) NOT NULL,
    UNIQUE (id_tramite, orden)
);

CREATE TABLE IF NOT EXISTS documento (
    id_documento    SERIAL PRIMARY KEY,
    titulo          VARCHAR(255) NOT NULL,
    id_remitente    INT REFERENCES usuario(id_usuario),
    id_sesion       INT REFERENCES sesion(id_sesion),
    estado          VARCHAR(50) DEFAULT 'RECIBIDO',
    fecha_recepcion TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE documento ADD COLUMN IF NOT EXISTS id_tramite    INT REFERENCES tipo_tramite(id_tramite);
ALTER TABLE documento ADD COLUMN IF NOT EXISTS paso_actual   INT DEFAULT 1;
ALTER TABLE documento ADD COLUMN IF NOT EXISTS extemporaneo  BOOLEAN DEFAULT FALSE;
ALTER TABLE documento ADD COLUMN IF NOT EXISTS descripcion   TEXT;

CREATE TABLE IF NOT EXISTS documento_version (
    id_version   SERIAL PRIMARY KEY,
    id_documento INT NOT NULL REFERENCES documento(id_documento),
    version      INT NOT NULL,
    contenido    TEXT NOT NULL,
    hash_sha256  VARCHAR(64) NOT NULL,
    fecha        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id_documento, version)
);

CREATE TABLE IF NOT EXISTS documento_historial (
    id_historial    SERIAL PRIMARY KEY,
    id_documento    INT NOT NULL REFERENCES documento(id_documento),
    estado_anterior VARCHAR(50),
    estado_nuevo    VARCHAR(50),
    paso            VARCHAR(80),
    observacion     TEXT,
    id_usuario      INT REFERENCES usuario(id_usuario),
    fecha           TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ---------- CU5: asistencia biométrica, quórum y votaciones ----------
CREATE TABLE IF NOT EXISTS asistencia (
    id_asistencia SERIAL PRIMARY KEY,
    id_sesion     INT REFERENCES sesion(id_sesion),
    id_usuario    INT REFERENCES usuario(id_usuario),
    hora_ingreso  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE asistencia ADD COLUMN IF NOT EXISTS metodo       VARCHAR(20) DEFAULT 'BIOMETRICO';
ALTER TABLE asistencia ADD COLUMN IF NOT EXISTS como_suplente BOOLEAN DEFAULT FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS ux_asistencia_sesion_usuario ON asistencia (id_sesion, id_usuario);

CREATE TABLE IF NOT EXISTS votacion (
    id_votacion  SERIAL PRIMARY KEY,
    id_sesion    INT REFERENCES sesion(id_sesion),
    id_documento INT REFERENCES documento(id_documento),
    id_usuario   INT REFERENCES usuario(id_usuario),
    voto         VARCHAR(20) NOT NULL
);
ALTER TABLE votacion ADD COLUMN IF NOT EXISTS fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
CREATE UNIQUE INDEX IF NOT EXISTS ux_votacion_doc_usuario ON votacion (id_documento, id_usuario);

-- ---------- CU6: resoluciones, veto y actas ----------
CREATE TABLE IF NOT EXISTS resolucion (
    id_resolucion        SERIAL PRIMARY KEY,
    numero               VARCHAR(40) UNIQUE NOT NULL,
    id_sesion            INT NOT NULL REFERENCES sesion(id_sesion),
    id_documento         INT NOT NULL REFERENCES documento(id_documento),
    tipo_mayoria         VARCHAR(20) NOT NULL,
    votos_favor          INT NOT NULL,
    votos_contra         INT NOT NULL,
    abstenciones         INT NOT NULL,
    presentes            INT NOT NULL,
    aprobada             BOOLEAN NOT NULL,
    estado               VARCHAR(30) NOT NULL,
    fecha_emision        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fecha_limite         DATE,
    fecha_promulgacion   TIMESTAMP,
    fecha_veto           TIMESTAMP,
    motivo_veto          TEXT
);

CREATE TABLE IF NOT EXISTS acta (
    id_acta          SERIAL PRIMARY KEY,
    id_sesion        INT UNIQUE NOT NULL REFERENCES sesion(id_sesion),
    contenido        TEXT NOT NULL,
    hash_sha256      VARCHAR(64) NOT NULL,
    fecha_generacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ---------- CU7: Gaceta y auditoría ----------
CREATE TABLE IF NOT EXISTS gaceta (
    id_gaceta         SERIAL PRIMARY KEY,
    numero            VARCHAR(40) UNIQUE NOT NULL,
    id_resolucion     INT UNIQUE NOT NULL REFERENCES resolucion(id_resolucion),
    contenido         TEXT NOT NULL,
    fecha_publicacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS auditoria (
    id_auditoria SERIAL PRIMARY KEY,
    fecha        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    remitente    VARCHAR(150),
    comando      VARCHAR(80),
    parametros   TEXT,
    resultado    VARCHAR(10),
    detalle      TEXT
);
