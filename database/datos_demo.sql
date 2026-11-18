-- Datos de DEMOSTRACIÓN (idempotente). Se cargan con el comando CARGARDEMO.
-- Todos los correos son ficticios: use REGISTRARUSUARIO / MODIFICARUSUARIO para poner su correo real.
INSERT INTO usuario (ci, nombres, apellidos, correo, id_rol)
SELECT v.ci, v.nombres, v.apellidos, v.correo, r.id_rol
FROM (VALUES
 ('1000001','Admin','Sistema','admin.demo@consejo.test','ADMINISTRADOR'),
 ('2000001','Roberto','Vargas Peña','presidente.demo@consejo.test','PRESIDENTE'),
 ('2000002','Maria','Gomez Rojas','secretaria.demo@consejo.test','SECRETARIO'),
 ('3000001','Carlos','Mendez Soria','titular.doc.inf@consejo.test','TITULAR_DOCENTE'),
 ('3000002','Lucia','Fernandez Ortiz','suplente.doc.inf@consejo.test','SUPLENTE_DOCENTE'),
 ('3000003','Jorge','Salvatierra Paz','titular.doc.sis@consejo.test','TITULAR_DOCENTE'),
 ('3000004','Ana','Quiroga Lima','suplente.doc.sis@consejo.test','SUPLENTE_DOCENTE'),
 ('4000001','Diego','Rivero Cuellar','titular.est.inf@consejo.test','TITULAR_ESTUDIANTE'),
 ('4000002','Valeria','Suarez Roca','suplente.est.inf@consejo.test','SUPLENTE_ESTUDIANTE'),
 ('4000003','Mateo','Justiniano Vaca','titular.est.sis@consejo.test','TITULAR_ESTUDIANTE'),
 ('4000004','Camila','Egüez Solis','suplente.est.sis@consejo.test','SUPLENTE_ESTUDIANTE')
) AS v(ci, nombres, apellidos, correo, rol)
JOIN rol r ON r.nombre = v.rol
ON CONFLICT DO NOTHING;

INSERT INTO mandato (id_titular, id_suplente, carrera, fecha_inicio, fecha_fin)
SELECT t.id_usuario, s.id_usuario, v.carrera, DATE '2026-01-01', DATE '2027-12-31'
FROM (VALUES ('3000001','3000002','INFORMATICA'),('3000003','3000004','SISTEMAS'),
             ('4000001','4000002','INFORMATICA'),('4000003','4000004','SISTEMAS')) AS v(cit, cis, carrera)
JOIN usuario t ON t.ci = v.cit JOIN usuario s ON s.ci = v.cis
WHERE NOT EXISTS (SELECT 1 FROM mandato m WHERE m.id_titular = t.id_usuario);
