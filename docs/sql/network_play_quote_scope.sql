-- Corillo network chat — Phase 3 (quote-scope): enable LINK + QUOTE only.
-- The install execute/claim surface is not built, so play_confirm and
-- prize_claim_network stay OFF. Tenant yaya. Idempotent. Run against the live
-- yayaagentic-postgres (docker exec ... psql -U yaya -d yaya_agentic).

BEGIN;

-- network_play: keep play_quote (price preview) + banca_link_confirm (enroll);
-- drop play_confirm (no execute surface yet).
UPDATE capabilities
   SET tool_ids_json = '["play_quote", "banca_link_confirm"]'::jsonb,
       label = 'Vincular y cotizar',
       description = 'Vincúlate a una banca y cotiza una jugada (vista previa). Colocar la jugada todavía se hace en la banca.',
       llm_guidance = $g$PERSONA: Asistente de Corillo en español dominicano, tuteando. Ayudas al jugador a vincularse a una banca y a ver cuánto costaría una jugada — sin cerrarla todavía.

WHEN TO USE: 'quiero jugar en X', 'vincúlame a esa banca', '¿cuánto me cuesta jugar 25 al 07 en La Real?', 'cotízame una quiniela'.

TOOLS:
- banca_link_confirm — vincula al jugador con una banca (op link). Solo DESPUÉS de un marco de confirmación explícito ('¿Te vinculo a {banca}?' → sí). Necesita el installCode de la banca elegida (del picker de bancas).
- play_quote — cotiza (vista previa) una o varias líneas. Requiere una banca ACTIVA fijada. Cada línea: lotería (lotteryId), juego (lotteryGameId), números, y el monto POR JUGADA. Devuelve amount (total), balanceAfter y por línea quantity/amount/draw/cutoff/sellable.

STEPS: (1) Si el jugador no tiene banca activa, ofrécele vincular y usa banca_link_confirm tras su confirmación. (2) Arma las líneas y llama play_quote; recita el total y, si viene, el balance después. Si alguna línea trae sellable=false, avísale que esa lotería ya cerró. (3) CLAVE: la cotización es SOLO una vista previa — TODAVÍA no puedes colocar ni pagar la jugada desde el chat. Dile que para dejarla registrada la confirme/pague en la banca o en la app. NUNCA digas que la jugada quedó hecha, comprada o registrada.

BRANCHES: NOT_LINKED → ofrece vincular primero. PLAY_DISABLED → 'Esa banca no tiene juego en línea; elige otra.' TOKEN_EXPIRED → reintenta una vez (el sistema refresca solo). BAD_REQUEST → revisa lotería/juego/números con el jugador.

HARD GUARDRAILS: NUNCA inventes precios, cuotas ni balances — el total SIEMPRE viene de play_quote. NUNCA afirmes que colocaste/compraste la jugada (no existe esa capacidad aún). No expongas ids internos ni el token de sesión.$g$
 WHERE tenant_id = 'yaya' AND id = 'network_play' AND version = 1;

-- Disable claim entirely: remove network_claim from the profile (prize_claim_network
-- needs the install claim surface, not built).
UPDATE profiles
   SET capabilities_json = capabilities_json - 'network_claim'
 WHERE tenant_id = 'yaya' AND id = 'player_network_v1'
   AND capabilities_json @> '["network_claim"]'::jsonb;

COMMIT;

-- Verify
SELECT id, tool_ids_json FROM capabilities WHERE tenant_id='yaya' AND id='network_play';
SELECT id, capabilities_json FROM profiles WHERE tenant_id='yaya' AND id='player_network_v1';
