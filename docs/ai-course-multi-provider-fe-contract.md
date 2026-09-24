# Course AI multi-provider: API contract for the frontend

Backend contract for the lecturer "Course AI" settings screen: provider credentials (OpenAI,
Gemini, OpenRouter, Cohere) and the provider/model bindings for PRIMARY, the fallback chain, and SECONDARY.
None of these endpoints contacts an AI provider. Saving a key never checks it with the provider:
the status shows `UNVERIFIED` until the first real analysis runs.

Base path: `/api/lecturer/courses/{courseId}`. Auth is the SAGA session cookie. Mutating calls
need the usual `X-XSRF-TOKEN` header. Only the course's assigned lecturer is authorized. Students
get `403 ACCESS_DENIED`; admins and other lecturers get `403 LECTURER_COURSE_FORBIDDEN`.

Canonical values:

- `provider`: `OPENAI` | `GEMINI` | `OPENROUTER` | `COHERE`. Requests are case-insensitive; responses are
  always upper case.
- `role`: `PRIMARY` | `SECONDARY`.
- `status`: `UNVERIFIED` | `ACTIVE` | `DEGRADED` | `INVALID` | `REVOKED`.

## 1. Model catalog

`GET /ai-provider-catalog` returns the only models that can be bound. Build every provider/model
picker from this response; never hard-code model ids. This is SAGA's product catalog, a
deliberate subset of what each provider's API offers:

- OpenAI and Gemini entries are specific provider models.
- `openrouter/free` is OpenRouter's Free Models Router. It is not one model: for each request it
  picks an available free model that supports what the request needs, including structured
  output.

```json
{
  "providers": [
    { "provider": "OPENAI", "displayName": "OpenAI", "models": [
      { "provider": "OPENAI", "modelId": "gpt-5.6-luna",  "displayName": "GPT-5.6 Luna",  "freeTierEligible": false, "supportsStructuredOutput": true, "recommendedForAutomation": true },
      { "provider": "OPENAI", "modelId": "gpt-5.6-terra", "displayName": "GPT-5.6 Terra", "freeTierEligible": false, "supportsStructuredOutput": true, "recommendedForAutomation": true },
      { "provider": "OPENAI", "modelId": "gpt-5.6-sol",   "displayName": "GPT-5.6 Sol",   "freeTierEligible": false, "supportsStructuredOutput": true, "recommendedForAutomation": true } ] },
    { "provider": "GEMINI", "displayName": "Google Gemini", "models": [
      { "provider": "GEMINI", "modelId": "gemini-3.8-flash",       "displayName": "Gemini 3.8 Flash",      "freeTierEligible": true,  "supportsStructuredOutput": true, "recommendedForAutomation": true },
      { "provider": "GEMINI", "modelId": "gemini-3.5-flash-lite",  "displayName": "Gemini 3.5 Flash-Lite", "freeTierEligible": true,  "supportsStructuredOutput": true, "recommendedForAutomation": true },
      { "provider": "GEMINI", "modelId": "gemini-3.1-pro-preview", "displayName": "Gemini 3.1 Pro",        "freeTierEligible": false, "supportsStructuredOutput": true, "recommendedForAutomation": false } ] },
    { "provider": "OPENROUTER", "displayName": "OpenRouter", "models": [
      { "provider": "OPENROUTER", "modelId": "openrouter/free", "displayName": "OpenRouter Free Models Router", "freeTierEligible": true, "supportsStructuredOutput": true, "recommendedForAutomation": false } ] },
    { "provider": "COHERE", "displayName": "Cohere", "models": [
      { "provider": "COHERE", "modelId": "command-a-plus-05-2026", "displayName": "Command A+", "freeTierEligible": true, "supportsStructuredOutput": true, "recommendedForAutomation": true },
      { "provider": "COHERE", "modelId": "command-a-03-2025", "displayName": "Command A", "freeTierEligible": true, "supportsStructuredOutput": true, "recommendedForAutomation": false } ] }
  ],
  "freeTierNotice": "Free-tier eligibility is informational only. External providers set and may change free-tier availability, limits and data-use terms at any time; free tiers may use submitted content to improve their models and are not suitable for sensitive production data."
}
```

Show `freeTierNotice` wherever a free-tier badge appears. `recommendedForAutomation: false` is a
warning label only; the model can still be selected. Gemini 3.1 Pro is a Google preview model and
is paid-tier only.

If `GET /ai-settings` returns a binding whose `modelId` is missing from the catalog (for example
`gemini-3.7-flash`, which SAGA no longer offers), show it as "no longer available" and ask the
lecturer to pick a catalog model. "No longer available" means it cannot be selected for a new or
updated binding: an existing persisted `gemini-3.7-flash` binding may continue to run until the
lecturer changes it. No other model is substituted, and past runs keep their recorded model.

## 2. Credentials (one per provider and role)

| Method | Path | Body | Response |
| --- | --- | --- | --- |
| GET | `/ai-credentials` | – | `CredentialMetadata[]`, every stored row, including revoked ones |
| GET | `/ai-credentials/{role}/{provider}` | – | `CredentialMetadata` (`configured:false` if none) |
| PUT | `/ai-credentials/{role}/{provider}` | `{ "apiKey": "..." }` | `CredentialMetadata` (always `UNVERIFIED`) |
| DELETE | `/ai-credentials/{role}/{provider}` | – | `204`; affects only that provider |

`CredentialMetadata`:

```json
{
  "configured": true,
  "provider": "GEMINI",
  "role": "PRIMARY",
  "status": "UNVERIFIED",
  "lastFour": "x9Qa",
  "createdAt": "2026-09-25T10:15:00",
  "updatedAt": "2026-09-25T10:15:00",
  "lastSuccessfulUseAt": null
}
```

The key is write-only. No response ever contains it, and none contains ciphertext, nonce,
fingerprint or any server key. Clear the input field after a successful PUT and never store the key
in client state, local storage or logs. If the body includes `provider`, it must match the path.

Status meaning for the UI: `ACTIVE` means the last use succeeded. `DEGRADED` means the provider
reported a quota or rate limit (the key is fine; check the plan or billing). `INVALID` means the
provider rejected the key (re-enter it). `REVOKED` means it was deleted.

Legacy routes still work and target OpenAI: `GET` and `DELETE /ai-credentials/{role}`, and
`PUT /ai-credentials/{role}` with `{ "provider": "openai", "apiKey": "..." }`.

## 3. Settings and bindings

`GET /ai-settings`, `PATCH /ai-settings` (unchanged body `{automationEnabled, allowPlatformFallback}`)
and `PUT /ai-settings/bindings` all return:

```json
{
  "automationEnabled": true,
  "allowPlatformFallback": false,
  "primaryBinding": { "provider": "GEMINI", "modelId": "gemini-3.8-flash" },
  "fallbackEnabled": true,
  "fallbackBindings": [
    { "provider": "OPENROUTER", "modelId": "openrouter/free" },
    { "provider": "OPENAI", "modelId": "gpt-5.6-luna" }
  ],
  "secondaryBinding": { "provider": "OPENAI", "modelId": "gpt-5.6-terra" }
}
```

A `null` `primaryBinding` or `secondaryBinding` means legacy mode: the course's OpenAI credential
with the platform-chosen model. `fallbackBindings` is the stored chain. It is returned even while
`fallbackEnabled` is `false`, so the UI can re-enable it without re-entering anything.

`PUT /ai-settings/bindings` replaces all bindings. It never changes credentials,
`automationEnabled` or `allowPlatformFallback`.

```json
{
  "primaryBinding": { "provider": "GEMINI", "modelId": "gemini-3.8-flash" },
  "fallbackEnabled": true,
  "fallbackBindings": [ { "provider": "OPENROUTER", "modelId": "openrouter/free" } ],
  "secondaryBinding": { "provider": "OPENAI", "modelId": "gpt-5.6-terra" }
}
```

Validation (all `400`, nothing is saved):

| Code | Cause |
| --- | --- |
| `AI_PROVIDER_NOT_SUPPORTED` | Unknown provider |
| `AI_MODEL_NOT_SUPPORTED` | Model not in the catalog, or it belongs to a different provider |
| `AI_MODEL_CAPABILITY_UNSUPPORTED` | Model lacks structured output for every analysis |
| `AI_BINDING_INVALID` | Missing `modelId`, duplicate fallback, primary repeated in the chain, more than 3 fallbacks, fallback enabled without a primary or with an empty chain |

A binding does not require the credential to exist yet. Warn when a bound provider has no usable
credential for that role (none, `INVALID` or `REVOKED`). Runs for that role are then unavailable,
and SAGA never substitutes another provider's key.

`COHERE` uses the same write-only credential route (`/ai-credentials/{role}/COHERE`), binding,
fallback, and SECONDARY semantics as every other course provider. For example, a PRIMARY binding
is `{ "provider": "COHERE", "modelId": "command-a-plus-05-2026" }`. Credential is not binding:
the first stores the key for a role, while the second selects provider/model. Provider decisions
for Cohere inference report `aiProvider: "COHERE"`. Load this catalog dynamically; do not hardcode
Cohere model ids in the frontend.

## 4. Runtime semantics to explain in the UI

- The fallback chain applies to PRIMARY only, is tried in order, and uses each entry at most once
  per run. It continues only after quota exhaustion, a rate limit, a timeout, or temporary provider
  unavailability. It never continues after an invalid key, an unsupported model, an invalid AI
  result, or a server configuration error. It uses only this course's own PRIMARY key for each
  provider, never a platform key.
- SECONDARY uses only its own binding and SECONDARY key. It has no fallback and never reuses the
  PRIMARY key.
- Automatic Commit, Task and Risk analysis never use platform credentials.
  `allowPlatformFallback` keeps its existing meaning: manual Academic Classification and Progress
  Narrative only.
- The analysis decision payload (existing analysis read endpoints) now has two extra fields:
  - `aiProvider`: the provider that actually served the run, or `null` on legacy runs.
  - `fallbackAttemptsJson`: a JSON string such as
    `[{"provider":"GEMINI","modelId":"gemini-3.8-flash","outcome":"AI_PROVIDER_QUOTA_EXHAUSTED"},{"provider":"OPENROUTER","modelId":"openrouter/free","outcome":"SUCCEEDED"}]`.
    An `outcome` of `SKIPPED_NO_CREDENTIAL` marks a chain entry that had no usable key.
