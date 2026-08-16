-- Corillo network chat — Phase 2: network results ("¿qué salió hoy/ayer?").
-- Registers the BEAN tool + capability and wires it into player_network_v1.
-- Tenant: yaya. Idempotent (upsert + array-append guarded). Run against the
-- live yayaagentic-postgres (docker exec ... psql -U yaya -d yaya_agentic).

BEGIN;

-- 1) Tool: network_results (BEAN → networkResultsTool). Mirrors the
--    directory_online_bancas policy (read-safe, no confirm, no identity).
INSERT INTO tools_registry (
    id, tenant_id, version, input_schema_json, output_schema_json,
    requires_json, handler_kind, handler_bean_name, policy_json, status
) VALUES (
    'network_results', 'yaya', 1,
    $schema$
    {
      "type": "object",
      "properties": {
        "date": {
          "type": "string",
          "description": "Fecha del sorteo YYYY-MM-DD (America/Santo_Domingo). Omitir para hoy."
        },
        "lottery": {
          "type": "string",
          "description": "Opcional: nombre o nombre corto de una loteria para filtrar (ej. 'La Real'). Omitir para todas."
        },
        "installCode": {
          "type": "string",
          "description": "Opcional: codigo de instalacion de una banca especifica como fuente."
        }
      }
    }
    $schema$::jsonb,
    '{"type": "object"}'::jsonb,
    '{"ownership": null, "requiredScopes": [], "requiredAttributes": []}'::jsonb,
    'BEAN', 'networkResultsTool',
    '{"timeout": "PT10S", "idempotent": true, "maxRetries": 0, "confirmable": false, "confirmPrompt": null, "clientInvocable": false, "requiresBancaContext": false, "requiresApprovedQuote": false}'::jsonb,
    'ACTIVE'
)
ON CONFLICT (tenant_id, id, version) DO UPDATE SET
    input_schema_json  = EXCLUDED.input_schema_json,
    output_schema_json = EXCLUDED.output_schema_json,
    requires_json      = EXCLUDED.requires_json,
    handler_kind       = EXCLUDED.handler_kind,
    handler_bean_name  = EXCLUDED.handler_bean_name,
    policy_json        = EXCLUDED.policy_json,
    status             = EXCLUDED.status;

-- 2) Capability: network_results. Unlike capability.player.results_inquiry
--    (org-scoped HTTP tools, unusable in the org-less network profile), this
--    uses the org-less BEAN proxy — results are lottery-wide across the network.
INSERT INTO capabilities (
    id, tenant_id, version, label, description, llm_guidance, tool_ids_json, requires_json
) VALUES (
    'network_results', 'yaya', 1,
    'Resultados de lotería',
    'Números ganadores de hoy o de una fecha, en toda la red — sin necesidad de estar vinculado a una banca.',
    $guid$PERSONA: Asistente de Corillo en español dominicano, tuteando. El jugador quiere números ganadores: rápido, exacto y sin adornos.

WHEN TO USE: '¿qué salió hoy?', '¿qué salió anoche/ayer?', 'resultados de hoy', '¿cuáles fueron los números?', 'resultados del 10 de agosto'. Los resultados son de toda la red, no de una banca específica — no hace falta que el jugador esté vinculado.

STEPS: (1) Calcula la fecha pedida en America/Santo_Domingo. Para HOY, llama network_results SIN date. Para 'ayer' o una fecha concreta, pásala como date=YYYY-MM-DD. Si el jugador nombró una lotería ('en La Real'), pásala como lottery. (2) Recita, por cada draw, el lotteryName y sus números: usa groupedResults (label + numbers) cuando venga; si no, ordena results por position y léelos tal cual. Recita status/hora si el jugador lo pide. (3) No reordenes ni reformatees los números.

BRANCHES: status='PAST_UNAVAILABLE' → 'Por ahora solo tengo los resultados de hoy; los de fechas anteriores todavía no los puedo sacar aquí.'. status='NO_SOURCE' o 'SOURCE_ERROR' → 'Tuve un problema sacando los resultados, ¿me das un segundo?' y reintenta una vez. draws vacío para hoy → 'Ese sorteo todavía no ha salido; sale más tarde. ¿Te aviso cuando lo carguen?'. Fecha futura → 'Ese sorteo no se ha jugado todavía' — jamás un pronóstico.

HARD GUARDRAILS: NUNCA inventes un número, una fecha ni un sorteo — si no vino en la tool, no existe para esta conversación. NUNCA predigas ni sugieras números 'calientes' o 'que tocan': recitas resultados, no pronósticos; si lo piden, recuérdales con cariño que el sorteo es azar. No mezcles resultados de loterías distintas en una misma línea. Nunca expongas ids internos.

EXAMPLES: '¿qué salió hoy?' → network_results() → 'Hoy: La Real 23-56-81, Nacional 04-19-77.'. • '¿qué salió ayer en La Real?' → network_results(date=ayer) → 'Ayer (lunes 11) en La Real: 23 - 56 - 81.'. • '¿qué número va a salir?' → 'Eso no te lo puedo decir — el sorteo es azar. Lo que sí te digo es qué salió: ¿quieres los de hoy?'.$guid$,
    '["network_results"]'::jsonb,
    '{}'::jsonb
)
ON CONFLICT (tenant_id, id, version) DO UPDATE SET
    label         = EXCLUDED.label,
    description   = EXCLUDED.description,
    llm_guidance  = EXCLUDED.llm_guidance,
    tool_ids_json = EXCLUDED.tool_ids_json,
    requires_json = EXCLUDED.requires_json;

-- 3) Wire network_results into player_network_v1 (append if absent).
UPDATE profiles
   SET capabilities_json = capabilities_json || '["network_results"]'::jsonb
 WHERE tenant_id = 'yaya'
   AND id = 'player_network_v1'
   AND NOT (capabilities_json @> '["network_results"]'::jsonb);

COMMIT;

-- Verify
SELECT id, handler_bean_name, status FROM tools_registry WHERE tenant_id='yaya' AND id='network_results';
SELECT id, tool_ids_json FROM capabilities WHERE tenant_id='yaya' AND id='network_results';
SELECT id, version, capabilities_json FROM profiles WHERE tenant_id='yaya' AND id='player_network_v1';
