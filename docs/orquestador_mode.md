# Modo Orquestador Multi-Agente — Conocimiento Nominado (Phase H+)

> **Estado**: Knowledge Nominated — **NO se implementa en Phase A–G**.  
> Objetivo: Documentar la visión completa para implementación **post v1.0.0 (Phase H+)**.

---

## Visión General

Un **Modo Orquestador** en el menú "Modo" (sobre la caja de prompts) que permite:

1. **Seleccionar 2+ agentes** (APIs externas, CLIs, LLMs locales instalados).
2. **Elegir un "Orquestador Principal"** entre ellos.
3. **Enviar prompt + contexto** a todos los agentes seleccionados simultáneamente.
4. **Recibir respuestas en tarjetas colapsables** (una por agente, identificada).
5. **Dos caminos**:
   - **Continuar con uno**: Usuario elige la mejor respuesta y sigue con esa IA.
   - **Orquestar**: Pulsa "Enviar al Orquestador" → cada IA re-analiza con respuestas de las demás → Orquestador Principal sintetiza respuesta unificada y depurada.
6. **Si genera plan**: Orquestador redacta plan DAG, delega a agentes coordinados, checkpoints auto-marcados, revisor asignado, trabajo paralelo sin pisarse.
7. **Control usuario**: Puede escribir al Orquestador, pausar/detener agentes, re-asignar tareas.

---

## Arquitectura Requerida (Phase H+)

### H1 — Bus de Contexto Compartido
- Event log persistido (Room) + snapshots periódicos.
- API `broadcast(prompt, providers[])` en `ProviderPoolRouter`.
- Contexto serializado + IDs de correlación (trace ID).

### H2 — Agente Orquestador (TIER_1)
- Recibe: `[{agent_id, response}, ...] + user_prompt` → síntesis unificada.
- Prompt fijo: "Eres el Orquestador. Recibes N análisis. Detecta similitudes, diferencias, contradicciones. Entrega respuesta unificada, depurada, accionable."

### H3 — UI Multi-Agente
- **Tarjetas colapsables** por agente (header: nombre/modelo, cuerpo: respuesta).
- **Botón "Enviar al Orquestador"** fijo en chat (abajo).
- **Tarjetas Checkpoint** auto-marcadas (fases del plan DAG).

### H4 — Coordinación Paralela (sin pisarse)
- Locks por archivo/región (operational transforms / CRDT tipo Automerge/Yjs).
- "Owner" por archivo/módulo asignado por Orquestador.
- Merge automático sin conflictos; conflictos → flag para revisor humano.

### H5 — Modo Planificador Orquestado
- Orquestador genera plan DAG (basado en `DAG Compiler` de C3).
- Delega sub-tareas a agentes específicos (por especialidad).
- Checkpoints auto-marcados en UI al completar cada fase.
- Asigna **Revisor** (otro agente) por fase crítica.
- Usuario ve progreso en tiempo real: tarjetas checkpoint + logs por agente.

### H6 — Control de Usuario
- Chat lateral con Orquestador (pide cambios, frena agente, re-asigna).
- Botón "Pausar agente X" / "Re-asignar tarea a Y".

---

## Reutilización de Arquitectura Actual (A–G)

| Módulo | Reutilización en Phase H |
|--------|--------------------------|
| `ProviderPoolRouter` (E1) | Añadir `broadcast(prompt, providers[])` + pooling de respuestas |
| `ModelCatalog` (C1) | Ya descubre modelos dinámicos (F1 del pipeline) |
| `DAG Compiler` (C3) | Base para planificar tareas paralelas en H5 |
| `KeystoreVault` (C5) | Credenciales por proveedor ya seguras |
| Anillos 0–3 | Ya soportan múltiples proveedores simultáneos |
| `Play Integrity + JWT` | Auth ya lista para multi-sesión |
| `DAG Compiler` (C3) | Base para plan DAG en H5 |

---

## Riesgos y Mitigaciones (Phase H)

| Riesgo | Mitigación |
|--------|------------|
| Conflictos código paralelo | Locks por archivo/región + OT/CRDT (Automerge/Yjs) + "owner" por módulo |
| Contexto explosivo (N agentes × tokens) | Condensador local por agente antes de enviar al Orquestador (≤100 tokens/respuesta) |
| Cuota × N agentes | Modo Orquestador **bajo demanda**; cuota configurable en `services_config` (CONFIG-1) |
| Latencia (N paralelas + síntesis) | Paralelismo real + timeout global configurable (`MAX_ORCHESTRATOR_LATENCY_MS`) |
| Debugging distribuido | Trace ID compartido + logs correlacionados en `ProviderPoolRouter` |

---

## Criterios de Entrada a Phase H (Definition of Ready)

- [ ] v1.0.0 liberada (G4 completado: repo público, tag v1.0.0, 28 tableros, 0 secretos, 0 fallos).
- [ ] `ProviderPoolRouter` estable con `broadcast()` probado en unit tests.
- [ ] `DAG Compiler` genera planes DAG válidos para tareas multi-paso.
- [ ] `KeystoreVault` + `Play Integrity` auditados (C6 ✅).
- [ ] UI base (D1–D8) estable, sin frame drops, 28 tableros con diff < umbral.
- [ ] Presupuesto de cuota para modo multi-agente validado en `services_config`.

---

## Decisiones Pendientes del SOVEREIGN (para Phase H)

1. **Modelo Orquestador por defecto**: ¿Gemini Flash? ¿Groq gpt-oss-120b? ¿Local Llama-3.2-3B?
2. **Máximo agentes simultáneos**: ¿3? ¿5? (impacta cuota y latencia).
3. **Política de merge automático vs revisión humana**: ¿Auto-merge si sin conflictos? ¿Siempre revisor humano?
4. **UI/UX exacta**: ¿Tarjetas apiladas? ¿Side-by-side en tablet/desktop? ¿Colapsables en móvil?
5. **Persistencia de sesiones orquestadas**: ¿Cuánto se guarda? ¿Exportable?

---

## Conclusión

**Implementable: SÍ.**  
**En plan actual (A–G): NO.**  
**Fase correcta: H+ (post v1.0.0).**  

La arquitectura actual (A–G) **ya provee los cimientos**: router multi-proveedor, catálogo dinámico, DAG compiler, vault, anillos, integridad. Phase H añade la capa de **orquestación interactiva multi-agente** sobre esos cimientos.

---

*Documento creado: 2026-10-22*  
*Autor: SOVEREIGN (directiva verbal) + axiom_director (formalización)*  
*Etiqueta: `knowledge-nominated`, `phase-h`, `orquestador`*
