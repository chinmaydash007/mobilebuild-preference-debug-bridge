# Version 1 protocol

Mobile Build discovers provider authorities ending in `.mobilebuild.preferences`. For an application ID of `com.example.debug`, the authority must be:

```text
com.example.debug.mobilebuild.preferences
```

All routes use `content://<authority>/v1/…`. Reads run through `adb exec-out content read`; saves stream JSON through stdin to `adb shell -T content write` so values never appear in process arguments.

## Read routes

`handshake` identifies the app:

```json
{"version":1,"packageID":"com.example.debug","label":"Example"}
```

`stores` lists registered stores without returning values:

```json
{
  "version": 1,
  "stores": [
    {
      "id": "settings",
      "label": "Settings",
      "kind": "registered",
      "available": true
    }
  ]
}
```

`store/<base64url-store-id>` returns a snapshot. The ID uses unpadded Base64 URL encoding:

```json
{
  "version": 1,
  "storeID": "settings",
  "entries": [
    {"key":"enabled","value":{"type":"boolean","value":true}},
    {"key":"count","value":{"type":"int","value":4}},
    {"key":"timestamp","value":{"type":"long","value":"9007199254740991"}},
    {"key":"ratio","value":{"type":"float","value":"1.5"}},
    {"key":"name","value":{"type":"string","value":"sample"}},
    {"key":"tags","value":{"type":"stringSet","value":["a","b"]}}
  ]
}
```

Long and Float values are decimal strings to prevent JSON precision loss. Unsupported types use `{"type":"unsupported","value":"…"}` and cannot be saved.

`result/<request-uuid>` returns `pending` until a streamed save completes, then returns one terminal response. Terminal responses are consumed after being read.

## Save route

Write the request body to `save/<request-uuid>`:

```json
{
  "version": 1,
  "storeID": "settings",
  "changes": [
    {
      "key": "enabled",
      "before": {"type":"boolean","value":true},
      "after": {"type":"boolean","value":false}
    }
  ]
}
```

The bridge accepts only existing keys whose `before` value and type still match. It validates every change before one synchronous commit.

Successful response:

```json
{"version":1,"status":"ok"}
```

Conflict response:

```json
{
  "version": 1,
  "status": "conflict",
  "conflicts": [
    {"key":"enabled","current":{"type":"boolean","value":false}}
  ]
}
```

Validation, opening, or commit failures return `status: "failed"` with a generic message. Exception text, key material, and encrypted XML are never included.
