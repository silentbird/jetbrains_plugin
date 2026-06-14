# Autocomplete-only build — known limitations & follow-ups

This build strips the AI chat agent and keeps only inline autocomplete + AI commit-message
generation. Two independent backends are configured in **Settings > Tools > Sweep**:

- **Autocomplete** — local `sweep-autocomplete` server (local mode) OR a self-hosted
  OpenAI-compatible endpoint (for the NextEdit model `sweepai/sweep-next-edit-v2-7B`).
- **AI provider** — a separate OpenAI-compatible chat model used for commit messages.

The custom autocomplete path (`AutocompleteIpResolverService.fetchCustomNextEditAutocomplete`)
ports Sweep's `inference.py` (`build_prompt` / `compute_prefill`) and calls the model's
`/v1/completions` endpoint. The items below are deliberate v1 simplifications to revisit.

---

## 1. `recent_changes` format does not match the model's training format  (priority: HIGH)

- **Where:** `AutocompleteIpResolverService.buildNextEditPrompt` — the `${request.recent_changes}`
  slice of the prompt; produced by `RecentEditsTracker` (`recent_changes = ... EditRecord.formattedDiff`).
- **Problem:** the model card's `DIFF_FORMAT` is
  `` <|file_sep|>{path}:{start}:{end}\noriginal:\n{old}\nupdated:\n{new} ``,
  but the plugin sends recent changes as `"File: {path}\n<unified diff>"`. The local
  `sweep-autocomplete` server reformats this internally; we don't have that logic, so the model
  receives diffs in a different shape than it was trained on — likely degrades completion quality.
- **Fix:** change how `recent_changes` is built in `RecentEditsTracker` (request construction,
  ~line 2288) to emit the model's `original:/updated:` `DIFF_FORMAT`. This needs each edit's
  old/new code (the `EditRecord` has `originalText`/`newText`), which is NOT currently carried in
  `NextEditAutocompleteRequest.recent_changes` (only the formatted string). Either reformat at the
  source or add structured edit data to the request.

## 2. `is_pure_insertion_above_cursor` filter not ported  (priority: MEDIUM)

- **Where:** `AutocompleteIpResolverService.buildNextEditResponse`.
- **Problem:** `inference.py` rejects low-value predictions that only insert lines above the cursor
  without editing the cursor line. We skip this filter, so such low-value completions may show.
- **Fix:** port `is_pure_insertion_above_cursor`. Note the original is ambiguous about whether
  `completion` is the model output or the full updated block — verify against real outputs before
  porting, then apply to `prefill + completion` (the full updated block).

## 3. `confidence` hardcoded to 1.0  (priority: LOW)

- **Where:** `AutocompleteIpResolverService.buildNextEditResponse`.
- **Problem:** `/v1/completions` doesn't return a confidence score, so we hardcode `1.0f`. Any
  downstream confidence-based filtering/UX is effectively disabled for the custom path.
- **Fix:** request `logprobs` from the server and derive a confidence, or expose a setting.

## 4. Not end-to-end tested  (priority: HIGH — do first)

- The NextEdit `/v1/completions` path was verified to compile and build, but never run against a
  real deployed `sweep-next-edit-v2-7B` endpoint. Deploy via
  `python3 -m sglang.launch_server --model-path henrik3/sweep-next-edit-v2-7B-AWQ --port 8000 --host 0.0.0.0 --trust-remote-code --context-length 16384`,
  set the Autocomplete endpoint to `http://<host>:8000/v1`, then validate. On failure, the path
  logs the HTTP status + body and the prompt can be inspected.

---

## Lower-priority cleanups (not autocomplete-correctness)

- `AutocompleteIpResolverService` still contains dead Sweep-cloud machinery
  (`startPeriodicResolution`, `performHealthCheck`, `resolveIpAddress`, `isPointedToCloud`,
  `HOSTNAME = "autocomplete.sweep.dev"`) — never called after the fallback removal. Safe to delete.
- `FirstRunSettingsActivity` is now effectively a no-op (no chat shortcuts to check) — can be removed
  along with its `plugin.xml` registration.
- Orphan/legacy fields remain in settings/state from the original plugin (e.g. `githubToken`,
  `baseUrl`, `anthropicApiKey`) — prune when convenient.
