-- Datos mínimos obligatorios (idempotente). Se ejecuta después de schema.sql.
INSERT INTO rol (nombre, descripcion) VALUES
 ('ADMINISTRADOR',       'Administrador del sistema'),
 ('PRESIDENTE',          'Presidente del Consejo / Decano'),
 ('SECRETARIO',          'Secretario del Consejo'),
 ('TITULAR_DOCENTE',     'Consejero Titular Estamento Docente'),
 ('SUPLENTE_DOCENTE',    'Consejero Suplente Estamento Docente'),
 ('TITULAR_ESTUDIANTE',  'Consejero Titular Estamento Estudiantil'),
 ('SUPLENTE_ESTUDIANTE', 'Consejero Suplente Estamento Estudiantil')
ON CONFLICT (nombre) DO NOTHING;

INSERT INTO tipo_tramite (nombre, descripcion) VALUES
 ('GENERAL',   'Trámite general: recepción, revisión y visto bueno'),
 ('RESOLUCION','Proyecto de resolución'),
 ('INFORME',   'Informe presentado al Consejo')
ON CONFLICT (nombre) DO NOTHING;

INSERT INTO flujo_paso (id_tramite, orden, nombre, rol_responsable)
SELECT t.id_tramite, p.orden, p.nombre, p.rol
FROM tipo_tramite t
JOIN (VALUES (1,'RECEPCION','SECRETARIO'),
             (2,'REVISION','SECRETARIO'),
             (3,'VISTO_BUENO','PRESIDENTE')) AS p(orden, nombre, rol) ON TRUE
ON CONFLICT (id_tramite, orden) DO NOTHING;
