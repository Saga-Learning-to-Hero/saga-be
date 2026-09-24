# AI BYOK production configuration

Course AI credentials ("bring your own key") are saved by the assigned lecturer, encrypted at rest
in saga-be, and forwarded to saga-ai inside a request-scoped encrypted envelope. saga-be never calls
an AI provider with a course key itself. A course can hold one credential per provider (OpenAI,
Gemini, OpenRouter) for each role (PRIMARY, SECONDARY) at the same time.

All values below are **server-side secrets**. Never put them in the frontend, Vercel, or any
`NEXT_PUBLIC_*` variable. Never commit them.

## Required variables

### saga-be (Railway)

| Variable | Value |
| --- | --- |
| `SAGA_AI_CREDENTIAL_MASTER_KEY` | New random key, format below. At-rest encryption of saved course keys. |
| `SAGA_AI_CREDENTIAL_TRANSPORT_KEY` | New random key, format below. Must be **different** from the master key. |
| `SAGA_AI_RUNTIME_INTERNAL_TOKEN` | Shared bearer token for saga-be → saga-ai. |
| `SAGA_AI_ENABLED` | `true` |
| `SAGA_AI_PRIMARY_PROVIDER` | `remote` |
| `SAGA_AI_RUNTIME_ENABLED` | `true` |
| `SAGA_AI_RUNTIME_BASE_URL` | Private saga-ai URL (Railway private networking). |

### saga-ai (Railway)

| Variable | Value |
| --- | --- |
| `SAGA_AI_CREDENTIAL_TRANSPORT_KEY` | **Identical** to saga-be's transport key. |
| `SAGA_AI_INTERNAL_TOKEN` | **Identical** to saga-be's `SAGA_AI_RUNTIME_INTERNAL_TOKEN`. |
| `SAGA_AI_ENABLED` | `true` |
| `SAGA_AI_PROVIDER` | `openai` |

`OPENAI_API_KEY` on saga-ai is only needed for platform-sourced requests (for example manual
platform fallback). Course-sourced requests use the decrypted course key. There is no platform
Gemini or OpenRouter key: those providers are only ever called with a course credential.
`SAGA_AI_PROVIDER=openai` enables the Gemini and OpenRouter course adapters as well; their optional
timeouts are `SAGA_AI_GEMINI_TIMEOUT_SECONDS` and `SAGA_AI_OPENROUTER_TIMEOUT_SECONDS` (default 60).

## Key format

`SAGA_AI_CREDENTIAL_MASTER_KEY` and `SAGA_AI_CREDENTIAL_TRANSPORT_KEY` must each be **exactly 32
random bytes encoded as standard, padded Base64**: 44 characters ending in `=`. URL-safe Base64,
hex, unpadded Base64, or any other length is treated exactly like a missing key.

The internal token is free-form but must be byte-identical on both services (no surrounding spaces
or newlines). 32 random bytes as 64 hex characters is recommended.

Generate each value separately (PowerShell; the value goes to the clipboard, not the screen):

```powershell
# 32-byte AES key: run once for MASTER, once more for TRANSPORT
$b = New-Object byte[] 32; $r = [System.Security.Cryptography.RandomNumberGenerator]::Create(); $r.GetBytes($b); $r.Dispose(); [Convert]::ToBase64String($b) | Set-Clipboard; Remove-Variable b

# Internal bearer token (64 hex characters)
$b = New-Object byte[] 32; $r = [System.Security.Cryptography.RandomNumberGenerator]::Create(); $r.GetBytes($b); $r.Dispose(); (-join ($b | ForEach-Object { $_.ToString('x2') })) | Set-Clipboard; Remove-Variable b
```

## Rotation

There is a single master key version. Changing `SAGA_AI_CREDENTIAL_MASTER_KEY` makes every stored
course key undecryptable (`AI_CREDENTIAL_DECRYPTION_FAILED`); lecturers must save their keys again.
The transport key and internal token can be rotated at any time as long as both services are
updated together.

## Fail-closed behavior

Missing or invalid configuration never blocks startup; it fails only when a credential is used.

| Situation | Result |
| --- | --- |
| Save a course key without a valid master key | HTTP 503 `AI_CREDENTIAL_MASTER_KEY_NOT_CONFIGURED` |
| Course-sourced run on a provider other than `remote` | Run fails `AI_COURSE_CREDENTIAL_REQUIRES_REMOTE_PROVIDER` before any decrypt or provider call; never uses the platform key |
| Master/transport key missing or undecryptable at run time | Run fails with the specific `AI_CREDENTIAL_*` code; provider never called |
| saga-ai cannot open the envelope | Run fails `AI_CREDENTIAL_ENVELOPE_INVALID` |
| saga-ai rejects the internal token | Run fails `AI_RUNTIME_UNAVAILABLE` |

A course credential is never downgraded to the platform key, and the SECONDARY role only ever uses
its own course credential.

## Credential status

Saving a key never calls the provider; it starts as `UNVERIFIED`. The first real inference updates
**only the credential that inference used**:

| Outcome | Status |
| --- | --- |
| Successful inference with the course key | `ACTIVE` |
| Provider rejects the course key (`AI_PROVIDER_AUTH_FAILED`) | `INVALID` |
| Provider quota or rate limit (`AI_PROVIDER_QUOTA_EXHAUSTED`, `AI_PROVIDER_RATE_LIMITED`) | `DEGRADED` |
| Timeout, provider unavailable, invalid result, model/capability errors | unchanged |
| Envelope, internal-token, crypto/config (SAGA-side) failures | unchanged |

## Provider bindings and course fallback

`ai_course_settings` binds PRIMARY and SECONDARY to a `(provider, modelId)` pair taken from the
server-side catalog (`AiModelCatalog`; saga-ai re-checks the same allowlist). No binding means the
legacy behaviour: the OpenAI course credential with the platform model.

An optional, ordered, course-owned PRIMARY fallback chain (at most 3 entries, each tried at most
once per run) continues only after `AI_PROVIDER_QUOTA_EXHAUSTED`, `AI_PROVIDER_RATE_LIMITED`,
`AI_PROVIDER_TIMEOUT` or `AI_PROVIDER_UNAVAILABLE`. It never continues after an invalid credential,
an unsupported model/capability, an invalid result, or a SAGA crypto/config error, and it only
ever uses the course's own PRIMARY credential for that provider -- never a platform credential and
never the SECONDARY credential. A fallback entry without a usable course credential is skipped.
The provider/model/credential that actually served (or last failed) the run and the attempt list
are stored on the PRIMARY decision (`ai_provider`, `model_id`, `fallback_attempts_json`).

SECONDARY is independent: its own binding and SECONDARY credential, no fallback, no reuse of the
PRIMARY credential. Automatic Commit/Task/Risk runs never use platform credentials; manual
platform fallback (Academic Classification, Progress Narrative) keeps its existing opt-in rule.

## Free tiers and data policy

`freeTierEligible` in the catalog (Gemini 3.8/3.7 Flash, `openrouter/free`) is informational only.
External providers set, and may change at any time, whether a free tier exists, its limits, and its
data terms. Free tiers commonly allow the provider to retain and use submitted prompts and outputs
(for example for model improvement or human review), and `openrouter/free` routes each request to
whichever free upstream model is available, each with its own terms. **Do not treat a free tier as
suitable for sensitive production data** (student personal data, grades, private repository code).
The lecturer who configures a course key is responsible for choosing a provider tier whose data
policy fits the course; SAGA sends only the evidence bundle of each analysis and never the key to
anything other than the configured provider.
