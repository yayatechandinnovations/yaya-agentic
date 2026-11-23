# Admin console UI kit — how to build a screen

Companion to `docs/design/admin-console-ux-design.md`. Two screens are the reference implementations; copy their shape:

| Pattern | Reference | Use for |
|---|---|---|
| List page + **full-page editor** (own route) | `lib/features/admin/tools/` (`tools_screen.dart`, `tool_editor_screen.dart`, `tool_form.dart`) | Many fields / sections / JSON: profiles, tools, knowledge sources, auth resolvers |
| List page + **side-sheet editor** | `lib/features/admin/capabilities/capabilities_screen.dart` | ≤ ~10 fields: capabilities, auth bindings, OIDC issuers, JWT secrets, identity routes, recording strategies, templates, opt-outs |

## Kit map

```
lib/ui/                          design system (no feature imports)
  tokens.dart                    StatusColors (success/warning/info/neutral, inputBorder, highlight), monoStyle(), Gap
  theme.dart                     light/dark ThemeData, themeModeProvider
  data/y_page.dart               YPage — title, subtitle, actions, toolbar, content (replaces AdminTwoPane)
  data/live_async.dart           LiveAsyncView — AsyncValue that keeps data on refetch, progress line, error+retry
  data/y_table.dart              YTable/YColumn/YFilter/YCellStack — search, filter chips, sort, highlight, new-rows pill
  data/y_badge.dart              YBadge(label, tone, icon) — status is always a word, never colour alone
  data/y_states.dart             YEmptyState, YErrorState (Retry + Copy details), YSkeletonRows
  data/y_sheet.dart              showYSheet / YSheetScaffold / closeYSheet — right-hand editor sheet
  data/relative_time.dart        formatRelative / formatRelativeIso
  form/y_form_controller.dart    YFormController (validation, touched, server fieldErrors, dirty, drafts, baseVersion), Validators
  form/y_fields.dart             YFieldLabel, YFieldShell, YTextField, YSelectField, YSwitchField(disabledReason), YEnumCards, YSecretField, YWireKey
  form/y_json_field.dart         YJsonField — parse status + Format, schemaAware
  form/y_key_value_field.dart    YKeyValueField — header-style rows, duplicate detection
  form/y_resource_picker.dart    YResourcePicker (searchable single ref, "Create new…"), YChipsField (multi ref)
  form/y_form_layout.dart        YFormSection, YErrorSummary, YBanner, YActionBar, YSaveShortcut
  form/y_editor_page.dart        YEditorPage (full-page editor scaffold, section index, aside), YChangedTag
  form/unsaved_guard.dart        YUnsavedGuard, UnsavedChanges (route onExit + sheet close + beforeunload)
  feedback/toast.dart            showToast — outcomes only, never validation
  feedback/confirm.dart          confirmAction, confirmTyped (type the id for high-blast-radius actions)
  feedback/api_error.dart        ApiErrorInfo.from(e) — message, code, fieldErrors, currentVersion
lib/app/live/
  live_resource.dart             liveResource(ref, kind) / liveRefresh(ref) / pollWhile / freshIdsProvider / recentChangeProvider
  conflict_banner.dart           LiveConflictBanner — "vN was published while you were editing"
  resource_kind.dart             AdminResourceKind (mirrors backend adminevents MAPPINGS)
```

## Rules

1. **Lists stay fresh by themselves.** Every list provider starts with `liveResource(ref, AdminResourceKind.x)` (already added to every existing provider). Render with `LiveAsyncView`, never `.when(loading: spinner)` — refetches must not blank the list. Transient states (ingesting, pending verification) add `pollWhile(ref, busy)` after the fetch instead of a `Timer.periodic` in the widget.
2. **Validation lives at the field.** A `YFormController` per editor; `validate()` returns `field → message`. Never `showSnack(…, error: true)` for input problems; server errors go through `form.applyServerError(e)` (backend `fieldErrors` land under the field; the rest in the summary). `showToast` is for outcomes ("Created x", "Deleted · Undo").
3. **Labels are human; the API name rides along.** `label: 'Tool ID', wireKey: 'id'`. `hint:` is an *example* (`e.g. orders_refund`); `helper:` is the explanation. Mark `required: true` or `optional: true`.
4. **Pick, don't type ids.** References to other resources use `YResourcePicker` / `YChipsField` fed from the existing providers. Enums with consequences use `YEnumCards` with a one-line effect per option (`caution: true` for risky ones).
5. **Rules the server enforces are visible up front.** `YSwitchField(disabledReason: …)` instead of letting a save fail.
6. **Versioned resources: Edit publishes vN+1.** No "Clone & edit". The editor loads the latest version, sends `baseVersion` (`api.createX(req, baseVersion: form.baseVersion)`), shows `LiveConflictBanner`, and the primary button says `Publish vN+1` / `Create x`. Duplicate is a separate action.
7. **Never drop what the form doesn't edit.** Saves replace the whole record. Carry every field the response has that the form doesn't show (see `ToolDraft.toRequest` carrying policy keys, captures, bindings, suggestions). If a freezed model lacks a field the backend returns, add it (nullable / `@Default([])` — not `@Default(<T>[])`, freezed 2 can't parse it) and regenerate.
8. **Unsaved work is protected.** Wrap editors in `YUnsavedGuard` (full pages get it from `YEditorPage`); close sheets with `closeYSheet(context)`; give drafts a `draftKey: 'yaya.draft.<kind>.<tenant>.<id|new>'` and `excludeFromDraft` for secrets.
9. **Destructive actions** use `confirmAction` naming the object and the consequence; `confirmTyped` for archive/rotate/delete-with-dependents.
10. **Deep links.** List routes accept `?id=<id>` (open that record) and `?new=1` (open the create editor) — the ⌘K palette and cross-links rely on it. Full-page editors get `/admin/<area>/new` and `/admin/<area>/:id` routes with `onExit: _guardUnsaved`.
11. **Copy.** Sentence case, operator's words, say the consequence. Empty states explain the resource in one line and offer the create action.
12. **Legibility.** Text colours come from the scheme (`onSurface`, `onSurfaceVariant`) or `StatusColors`; no hard-coded greys, no `fontSize` below 12 (13 for anything read as a sentence). IDs/versions/JSON use `monoStyle(context)`.

## Checks

```bash
cd yayaagenticweb
flutter analyze lib test                         # no new warnings
flutter test --platform chrome test/<your>_test.dart
dart run build_runner build --delete-conflicting-outputs   # only after changing a freezed model
```
