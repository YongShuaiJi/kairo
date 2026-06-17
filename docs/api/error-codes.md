# Error Codes

| Code | HTTP | Meaning |
| --- | --- | --- |
| `FIELD_REQUIRED` | 400 | A required request field is missing or blank |
| `INVALID_FIELD` | 400 | A field has the wrong type or enum value |
| `FORBIDDEN` | 403 | Actor does not have the required capability |
| `RESOURCE_NOT_FOUND` | 404 | Requested resource does not exist |
| `RESOURCE_VERSION_CONFLICT` | 409 | Expected status/version does not match the current row |
| `RECORDING_SESSION_INVALID_TRANSITION` | 409 | Recording session state transition is not allowed |
| `REPLAY_PLAN_INVALID_TRANSITION` | 409 | Replay plan state transition is not allowed |
| `OPERATION_PLAN_INVALID_TRANSITION` | 409 | Operation plan state transition is not allowed |
| `EXTRACTION_TASK_INVALID_TRANSITION` | 409 | Extraction task state transition is not allowed |
| `REPLAY_EXECUTION_INVALID_TRANSITION` | 409 | Replay execution state transition is not allowed |
| `FENCING_TOKEN_INVALID` | 409 | Fencing token is missing, expired, already consumed, or belongs to another resource |
| `REDIS_UNAVAILABLE` | 409 | Redis fencing is enabled but no Redis client is available |
| `REDIS_FENCING_FAILED` | 409 | Redis did not return a fencing sequence |
| `SOURCE_SESSION_NOT_COMPLETED` | 409 | Dataset creation requires a completed recording session |
| `SELF_APPROVAL_FORBIDDEN` | 409 | Requester cannot approve their own approval request |

Error responses use:

```json
{
  "code": "RESOURCE_VERSION_CONFLICT",
  "message": "Resource status or version has changed",
  "correlationId": "corr-123",
  "details": {},
  "retryable": false
}
```
