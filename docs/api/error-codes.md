# Error Codes

| Code | HTTP | Meaning |
| --- | --- | --- |
| `FIELD_REQUIRED` | 400 | A required request field is missing or blank |
| `INVALID_FIELD` | 400 | A field has the wrong type or enum value |
| `FORBIDDEN` | 403 | Actor does not have the required capability |
| `RESOURCE_NOT_FOUND` | 404 | Requested resource does not exist |
| `RESOURCE_VERSION_CONFLICT` | 409 | Expected status/version does not match the current row |
| `OPERATION_PLAN_INVALID_TRANSITION` | 409 | Operation plan state transition is not allowed |
| `FENCING_TOKEN_INVALID` | 409 | Fencing token is missing, expired, already consumed, or belongs to another resource |
| `REDIS_UNAVAILABLE` | 409 | Redis fencing is enabled but no Redis client is available |
| `REDIS_FENCING_FAILED` | 409 | Redis did not return a fencing sequence |

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
