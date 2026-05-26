# Bug Task: Wrap Teacher Item Hints and Options Arrays for CourseService

## Background

Site calls BFF teacher item editor endpoints with frontend-facing URLs and array request bodies.
For example, Site sends:

```http
PUT /api/v1/teacher/items/{itemId}/hints
PUT /api/v1/teacher/items/{itemId}/options
```

with request bodies shaped as plain arrays:

```json
[
  {
    "orderIndex": 0,
    "text": "Think about input parsing."
  }
]
```

```json
[
  {
    "orderIndex": 0,
    "label": "A",
    "text": "Option text",
    "correct": true,
    "explanation": "Because it is correct."
  }
]
```

CourseService does not expose `/api/v1/teacher/**`. BFF maps teacher-facing URLs to CourseService admin URLs:

```text
BFF PUT /api/v1/teacher/items/{itemId}/hints
 -> CourseService PUT /api/v1/admin/course-items/{itemId}/hints

BFF PUT /api/v1/teacher/items/{itemId}/options
 -> CourseService PUT /api/v1/admin/course-items/{itemId}/options
```

CourseService expects object wrappers for these endpoints:

```json
{
  "hints": [
    {
      "orderIndex": 0,
      "text": "Think about input parsing."
    }
  ]
}
```

```json
{
  "options": [
    {
      "orderIndex": 0,
      "label": "A",
      "text": "Option text",
      "correct": true,
      "explanation": "Because it is correct."
    }
  ]
}
```

BFF already wraps array request bodies for:

```http
PUT /api/v1/teacher/items/{itemId}/content-blocks
PUT /api/v1/teacher/items/{itemId}/test-cases
```

but it did not wrap:

```http
PUT /api/v1/teacher/items/{itemId}/hints
PUT /api/v1/teacher/items/{itemId}/options
```

As a result, CourseService received a JSON array where it expected an object and returned `500 INTERNAL_SERVER_ERROR`.

## Tasks

- [ ] Update `CourseTeacherProxyController` to detect `PUT /api/v1/teacher/items/{itemId}/hints`.
- [ ] Wrap a JSON array body for hints into an object with the `hints` field before proxying to CourseService.
- [ ] Update `CourseTeacherProxyController` to detect `PUT /api/v1/teacher/items/{itemId}/options`.
- [ ] Wrap a JSON array body for options into an object with the `options` field before proxying to CourseService.
- [ ] Preserve existing URL mapping from BFF teacher paths to CourseService admin paths.
- [ ] Preserve existing wrappers for `content-blocks` and `test-cases`.
- [ ] Add integration tests proving hints array bodies are wrapped for CourseService.
- [ ] Add integration tests proving options array bodies are wrapped for CourseService.

## Endpoint Details

### Hints

Frontend-facing BFF request:

```http
PUT /api/v1/teacher/items/{itemId}/hints
```

Site body:

```json
[
  {
    "orderIndex": 0,
    "text": "Think about input parsing."
  }
]
```

CourseService upstream request must be:

```http
PUT /api/v1/admin/course-items/{itemId}/hints
```

CourseService body must be:

```json
{
  "hints": [
    {
      "orderIndex": 0,
      "text": "Think about input parsing."
    }
  ]
}
```

### Options

Frontend-facing BFF request:

```http
PUT /api/v1/teacher/items/{itemId}/options
```

Site body:

```json
[
  {
    "orderIndex": 0,
    "label": "A",
    "text": "Option text",
    "correct": true,
    "explanation": "Because it is correct."
  }
]
```

CourseService upstream request must be:

```http
PUT /api/v1/admin/course-items/{itemId}/options
```

CourseService body must be:

```json
{
  "options": [
    {
      "orderIndex": 0,
      "label": "A",
      "text": "Option text",
      "correct": true,
      "explanation": "Because it is correct."
    }
  ]
}
```

## Acceptance Criteria

- [ ] `PUT /api/v1/teacher/items/{itemId}/hints` still proxies to `PUT /api/v1/admin/course-items/{itemId}/hints`.
- [ ] If the hints request body is a JSON array, BFF sends CourseService `{ "hints": [...] }`.
- [ ] `PUT /api/v1/teacher/items/{itemId}/options` still proxies to `PUT /api/v1/admin/course-items/{itemId}/options`.
- [ ] If the options request body is a JSON array, BFF sends CourseService `{ "options": [...] }`.
- [ ] Non-array bodies are passed through unchanged.
- [ ] Existing `content-blocks` and `test-cases` wrappers still work.
- [ ] Regression tests cover both hints and options wrapping.
- [ ] Saving hints and quiz options through Site no longer causes CourseService `500` from request-body shape mismatch.
