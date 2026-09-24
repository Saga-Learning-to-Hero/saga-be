# AI BYOK production configuration

Course AI credentials ("bring your own key") are saved by the assigned lecturer, encrypted at rest
in saga-be, and forwarded to saga-ai inside a request-scoped encrypted envelope. saga-be never calls
OpenAI with a course key itself.

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
platform fallback). Course-sourced requests use the decrypted course key.

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

Saving a key never calls OpenAI; it starts as `UNVERIFIED`. The first real inference updates it:

| Outcome | Status |
| --- | --- |
| Successful inference with the course key | `ACTIVE` |
| Provider rejects the course key (`AI_PROVIDER_AUTH_FAILED`) | `INVALID` |
| Provider rate/quota limit (`AI_PROVIDER_RATE_LIMITED`) | `DEGRADED` |
| Envelope, internal-token, crypto/config, timeout or transient failures | unchanged |
