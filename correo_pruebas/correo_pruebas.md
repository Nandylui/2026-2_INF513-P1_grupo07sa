# Sistema Vía Mail Básico - Consejo Facultativo (GRUPO07SA)

Enviar cada comando **en el asunto** del correo a `grupo07sa@tecnoweb.org.bo`.

Formato: `COMANDO["param1","param2",...]` (no distingue mayúsculas). Fechas: `AAAA-MM-DD HH:mm`.

## CU1 - Gestionar Usuarios

| COMANDO | DESCRIPCION | EJEMPLO |
|---|---|---|
| `OBTENERROLES` | Lista los roles del sistema | `OBTENERROLES` |
| `OBTENERUSUARIOS[filtro]` | Lista usuarios. Filtro opcional: * (todos), un ROL (ej. SECRETARIO) o un ESTADO (ACTIVO/INACTIVO) | `OBTENERUSUARIOS["*"]` |
| `OBTENERUSUARIO[ci]` | Muestra los datos de un usuario por su CI | `OBTENERUSUARIO["9876543"]` |
| `REGISTRARUSUARIO[ci,nombres,apellidos,correo,rol]` | Registra un usuario con su rol (ver OBTENERROLES) | `REGISTRARUSUARIO["9876543","Maria","Gomez","maria@gmail.com","SECRETARIO"]` |
| `MODIFICARUSUARIO[ci,campo,valor]` | Modifica un campo del usuario: nombres, apellidos, correo, rol o estado | `MODIFICARUSUARIO["9876543","correo","maria.nuevo@gmail.com"]` |
| `ELIMINARUSUARIO[ci]` | Da de baja (INACTIVO) a un usuario. No se borra para conservar el historial | `ELIMINARUSUARIO["9876543"]` |

## CU2 - Gestionar Miembros y Quórum

| COMANDO | DESCRIPCION | EJEMPLO |
|---|---|---|
| `OBTENERMIEMBROS` | Lista los miembros vigentes del Consejo (titular y suplente por carrera/estamento) | `OBTENERMIEMBROS` |
| `OBTENERMANDATOS[filtro]` | Lista mandatos (historial). Filtro opcional: * , VIGENTE, CONCLUIDO o una carrera | `OBTENERMANDATOS["VIGENTE"]` |
| `REGISTRARMANDATO[ci_titular,ci_suplente,carrera,fecha_inicio,fecha_fin]` | Registra un mandato de 2 años (titular + suplente de la misma carrera y estamento). Suplente: CI o NINGUNO | `REGISTRARMANDATO["3000001","3000002","INFORMATICA","2026-01-01","2027-12-31"]` |
| `MODIFICARMANDATO[id_mandato,campo,valor]` | Modifica un mandato: ci_suplente (o NINGUNO), fecha_fin o estado (VIGENTE/CONCLUIDO) | `MODIFICARMANDATO["1","ci_suplente","3000002"]` |
| `ELIMINARMANDATO[id_mandato]` | Concluye un mandato (pasa a CONCLUIDO; se conserva en el historial) | `ELIMINARMANDATO["1"]` |
| `CALCULARQUORUM[id_sesion]` | Calcula el quórum (mayoría absoluta). Con id_sesion indica además si ya se alcanzó | `CALCULARQUORUM["1"]` |

## CU3 - Convocatorias, Excusas y Suplentes

| COMANDO | DESCRIPCION | EJEMPLO |
|---|---|---|
| `OBTENERSESIONES[filtro]` | Lista sesiones. Filtro opcional: * , estado (PROGRAMADA, EN_CURSO, FINALIZADA, CANCELADA) o tipo (ORDINARIA, EXTRAORDINARIA) | `OBTENERSESIONES["PROGRAMADA"]` |
| `OBTENERSESION[id_sesion]` | Detalle de una sesión: datos, orden del día y documentos | `OBTENERSESION["1"]` |
| `REGISTRARSESION[fecha_hora,lugar,tipo,solicitantes,orden_dia]` | Convoca una sesión (ordinaria o extraordinaria) con 48 h de anticipación. Extraordinaria: solicitantes = CI del Presidente o al menos 3 CI de consejeros separados por ;  orden_dia = puntos separados por ;  (use "" para omitir un parámetro) | `REGISTRARSESION["2027-03-10 09:30","Sala del Consejo","ORDINARIA","","Informe del decano;Aprobar POA"]` |
| `MODIFICARSESION[id_sesion,campo,valor]` | Modifica una sesión PROGRAMADA: lugar, fecha_hora u orden_dia (puntos separados por ;) | `MODIFICARSESION["1","lugar","Auditorio"]` |
| `ELIMINARSESION[id_sesion]` | Cancela una sesión PROGRAMADA (queda registrada como CANCELADA) | `ELIMINARSESION["1"]` |
| `OBTENEREXCUSAS[id_sesion]` | Lista excusas (todas o de una sesión) | `OBTENEREXCUSAS["1"]` |
| `REGISTRAREXCUSA[ci_titular,id_sesion,motivo]` | Registra la excusa de un titular y habilita automáticamente a su suplente para esa sesión | `REGISTRAREXCUSA["3000001","1","Viaje por comisión de servicio"]` |
| `ELIMINAREXCUSA[id_excusa]` | Anula una excusa (el suplente deja de estar habilitado) mientras la sesión esté PROGRAMADA | `ELIMINAREXCUSA["1"]` |
| `OBTENERHABILITADOS[id_sesion]` | Quiénes pueden asistir/votar en una sesión (titulares sin excusa, suplentes habilitados y Presidente) | `OBTENERHABILITADOS["1"]` |

## CU4 - Repositorio Documental y Workflow

| COMANDO | DESCRIPCION | EJEMPLO |
|---|---|---|
| `OBTENERTRAMITES` | Lista los tipos de trámite y sus pasos de aprobación (workflow) | `OBTENERTRAMITES` |
| `OBTENERDOCUMENTOS[filtro]` | Lista documentos. Filtro opcional: * , un ESTADO, EXTEMPORANEO o el número de una sesión | `OBTENERDOCUMENTOS["RECIBIDO"]` |
| `OBTENERDOCUMENTO[id_documento]` | Detalle de un documento: datos, paso actual del flujo y versiones | `OBTENERDOCUMENTO["1"]` |
| `REGISTRARDOCUMENTO[titulo,ci_remitente,id_sesion,tramite,contenido]` | Recibe un documento para una sesión. Si llega con menos de 48 h de anticipación queda como EXTEMPORÁNEO. tramite (opcional): GENERAL, RESOLUCION, INFORME. contenido (opcional): crea la versión 1 con su hash SHA-256 | `REGISTRARDOCUMENTO["Proyecto de reglamento","2000002","1","RESOLUCION","Texto del proyecto..."]` |
| `MODIFICARDOCUMENTO[id_documento,campo,valor]` | Modifica titulo, descripcion o id_sesion (reprograma el documento a otra sesión) mientras no esté APROBADO/RECHAZADO | `MODIFICARDOCUMENTO["1","titulo","Nuevo título"]` |
| `ELIMINARDOCUMENTO[id_documento]` | Anula un documento (estado ANULADO) si todavía no tiene votos | `ELIMINARDOCUMENTO["1"]` |
| `AVANZARDOCUMENTO[id_documento,ci_responsable,observacion]` | Workflow: completa el paso pendiente del trámite (lo debe hacer el rol responsable). Al terminar el último paso el documento queda APROBADO | `AVANZARDOCUMENTO["1","2000002","Revisado sin observaciones"]` |
| `RECHAZARDOCUMENTO[id_documento,ci_responsable,motivo]` | Workflow: rechaza el documento en el paso pendiente (queda RECHAZADO) | `RECHAZARDOCUMENTO["1","2000002","Falta firma del decano"]` |
| `REGISTRARVERSION[id_documento,contenido]` | Guarda una nueva versión del documento con su huella SHA-256 | `REGISTRARVERSION["1","Texto actualizado..."]` |
| `OBTENERVERSIONES[id_documento]` | Lista las versiones de un documento | `OBTENERVERSIONES["1"]` |
| `OBTENERHISTORIALDOCUMENTO[id_documento]` | Trazabilidad del documento: cambios de estado y quién los hizo | `OBTENERHISTORIALDOCUMENTO["1"]` |

## CU5 - Asistencia, Quórum y Votaciones

| COMANDO | DESCRIPCION | EJEMPLO |
|---|---|---|
| `REGISTRARASISTENCIA[ci,id_sesion]` | Registra la asistencia (huella del reloj biométrico). Solo cuentan titulares sin excusa, suplentes habilitados y el Presidente | `REGISTRARASISTENCIA["3000001","1"]` |
| `OBTENERASISTENCIA[id_sesion]` | Lista de asistentes de una sesión y estado del quórum | `OBTENERASISTENCIA["1"]` |
| `INICIARSESION[id_sesion]` | Abre la sesión si hay quórum (mayoría absoluta de asistentes). Pasa a EN_CURSO | `INICIARSESION["1"]` |
| `FINALIZARSESION[id_sesion]` | Cierra la sesión EN_CURSO (pasa a FINALIZADA) | `FINALIZARSESION["1"]` |
| `REGISTRARVOTO[ci,id_sesion,id_documento,voto]` | Registra el voto de un consejero presente sobre un documento. voto: A_FAVOR, EN_CONTRA o ABSTENCION (un voto por persona) | `REGISTRARVOTO["3000001","1","1","A_FAVOR"]` |
| `OBTENERVOTOS[id_sesion,id_documento]` | Votos emitidos sobre un documento y su recuento | `OBTENERVOTOS["1","1"]` |

## CU6 - Resoluciones, Veto y Actas

| COMANDO | DESCRIPCION | EJEMPLO |
|---|---|---|
| `EMITIRRESOLUCION[id_sesion,id_documento,tipo_mayoria,numero]` | Cierra la votación de un documento y emite la resolución. tipo_mayoria: ABSOLUTA (por defecto) o DOS_TERCIOS. numero opcional (si se omite se genera RES-AAAA-###) | `EMITIRRESOLUCION["1","1","ABSOLUTA"]` |
| `OBTENERRESOLUCIONES[filtro]` | Lista resoluciones. Filtro opcional: * o estado (EMITIDA, RECHAZADA, PROMULGADA, VETADA, SUPERADO_VETO, PUBLICADA) | `OBTENERRESOLUCIONES["*"]` |
| `OBTENERRESOLUCION[id_resolucion]` | Detalle de una resolución | `OBTENERRESOLUCION["1"]` |
| `PROMULGARRESOLUCION[id_resolucion,ci_presidente]` | El Presidente promulga una resolución aprobada dentro de 5 días hábiles (o con el veto superado) | `PROMULGARRESOLUCION["1","2000001"]` |
| `VETARRESOLUCION[id_resolucion,ci_presidente,motivo]` | El Presidente veta una resolución EMITIDA dentro de 5 días hábiles | `VETARRESOLUCION["1","2000001","Contraviene el estatuto"]` |
| `SUPERARVETO[id_resolucion]` | Intenta superar el veto: requiere los 2/3 de los presentes a favor | `SUPERARVETO["1"]` |
| `GENERARACTA[id_sesion,observaciones]` | Genera el acta de una sesión FINALIZADA (asistentes, quórum, documentos, votos y resoluciones) con su huella SHA-256 inalterable | `GENERARACTA["1","Sin observaciones"]` |
| `OBTENERACTA[id_sesion]` | Muestra el acta de una sesión | `OBTENERACTA["1"]` |

## CU7 - Fiscalizar, Auditar y Publicar Gaceta

| COMANDO | DESCRIPCION | EJEMPLO |
|---|---|---|
| `PUBLICARGACETA[id_resolucion]` | Publica en la Gaceta una resolución PROMULGADA (queda PUBLICADA) | `PUBLICARGACETA["1"]` |
| `OBTENERGACETA[numero]` | Lista las publicaciones de la Gaceta, o muestra una por su número (ej. GAC-2026-001) | `OBTENERGACETA["*"]` |
| `VERIFICARACTA[id_sesion]` | Audita la integridad del acta: recalcula el SHA-256 y lo compara con el guardado | `VERIFICARACTA["1"]` |
| `FISCALIZARSESION[id_sesion]` | Fiscaliza el cumplimiento normativo de una sesión (convocatoria, quórum, votos, plazos, acta, Gaceta) | `FISCALIZARSESION["1"]` |
| `OBTENERAUDITORIA[limite,filtro]` | Bitácora de auditoría: últimos comandos procesados (limite por defecto 20, máx 100). filtro opcional: correo o comando | `OBTENERAUDITORIA["20","REGISTRARVOTO"]` |

## CU8 - Reportes

| COMANDO | DESCRIPCION | EJEMPLO |
|---|---|---|
| `REPORTEASISTENCIA[id_sesion]` | Reporte de asistencia: resumen por sesión, o detalle de una sesión | `REPORTEASISTENCIA["1"]` |
| `REPORTEVOTACIONES[id_sesion]` | Reporte de votaciones por documento (todas las sesiones o una) | `REPORTEVOTACIONES["1"]` |
| `REPORTERESOLUCIONES[estado]` | Reporte de resoluciones con conteo por estado; filtro opcional por estado | `REPORTERESOLUCIONES["PROMULGADA"]` |
| `REPORTEEXCUSAS[desde,hasta]` | Reporte de excusas por consejero en un rango de fechas (AAAA-MM-DD, opcional) | `REPORTEEXCUSAS["2026-01-01","2026-12-31"]` |
| `REPORTEDOCUMENTOS[estado]` | Reporte del repositorio documental: conteo por estado y extemporáneos | `REPORTEDOCUMENTOS` |
| `REPORTEGENERAL` | Resumen general del sistema (totales de cada módulo) | `REPORTEGENERAL` |

## Sistema (ayuda, estado y respaldo)

| COMANDO | DESCRIPCION | EJEMPLO |
|---|---|---|
| `AYUDA[tema]` | Muestra los comandos disponibles. Tema opcional: un CU (CU1..CU8), SISTEMA o el nombre de un comando | `AYUDA` |
| `ESTADOSISTEMA` | Estado del sistema: base de datos en uso, contingencia, correo, reglas y respaldos | `ESTADOSISTEMA` |
| `RESPALDAR` | Crea ahora un respaldo de todos los datos (carpeta backups/) | `RESPALDAR` |
| `OBTENERRESPALDOS` | Lista los respaldos disponibles | `OBTENERRESPALDOS` |
| `RESTAURAR[archivo]` | Restaura los datos desde un respaldo (el más reciente si no se indica). Deshabilitado por correo salvo seguridad.permitir_restaurar_por_correo=true; por defecto se hace desde la consola con main.Respaldo | `RESTAURAR["respaldo_20261117_101500.sql"]` |
| `INICIALIZARBD` | Recrea las tablas que falten y los datos base (no borra datos existentes) | `INICIALIZARBD` |
| `CARGARDEMO` | Carga datos de demostración (presidente, secretario y 4 consejeros con suplentes) para probar los comandos | `CARGARDEMO` |

