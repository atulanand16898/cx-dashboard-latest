# CxAlloy Spring Boot Integration — JWT Auth

A Spring Boot application integrating with the CxAlloy API, backed by H2, secured with **JWT Bearer tokens**.

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                Spring Boot App (Port 8080)                       │
│                                                                  │
│  POST /api/auth/login → JWT issued                               │
│  Authorization: Bearer <token> → all /api/** endpoints          │
│                                                                  │
│  Controllers → Services → Repositories → H2 Database            │
│       ↓                                                          │
│  CxAlloyApiClient (HMAC-SHA256 signed) → tq.cxalloy.com/api/v1  │
└──────────────────────────────────────────────────────────────────┘
```

## Database Tables (H2)

| Table | Source Endpoint |
|-------|-----------------|
| `cxalloy_projects` | GET /project, GET /project/{id} |
| `cxalloy_issues` | GET/POST/PUT/DELETE /issue |
| `cxalloy_users` | GET /user |
| `api_sync_logs` | Audit trail of every sync |
| `raw_api_responses` | Raw JSON per endpoint call |

## Running

```bash
# Java 17+ and Maven required
mvn spring-boot:run
```

---

## 🔐 Authentication (JWT)

### Default Users

| Username | Password | Roles |
|----------|----------|-------|
| `admin` | `admin123` | ADMIN, USER |
| `viewer` | `viewer123` | USER |

### Token Lifetimes

| Token | Expiry |
|-------|--------|
| Access Token | 15 minutes |
| Refresh Token | 7 days |

---

## Auth Endpoints (Public — no token needed)

### 1. Login → Get Tokens

```
POST /api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "admin123"
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "accessTokenExpiresIn": 900,
    "refreshTokenExpiresIn": 604800,
    "username": "admin"
  }
}
```

### 2. Refresh Access Token

```
POST /api/auth/refresh
Content-Type: application/json

{
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

Returns a new access token + new refresh token (old refresh token is rotated/invalidated).

### 3. Logout

```
POST /api/auth/logout
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

Both tokens are blacklisted immediately.

### 4. Get Current User

```
GET /api/auth/me
Authorization: Bearer <access-token>
```

---

## Protected API Endpoints

All require: `Authorization: Bearer <access-token>`

### Sync Everything

```bash
curl -X POST http://localhost:8080/api/sync/all \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."
```

### Projects

```bash
# Get all projects
curl http://localhost:8080/api/projects \
  -H "Authorization: Bearer <token>"

# Get single project
curl http://localhost:8080/api/projects/1 \
  -H "Authorization: Bearer <token>"

# Sync from CxAlloy
curl -X POST http://localhost:8080/api/projects/sync \
  -H "Authorization: Bearer <token>"
```

### Issues

```bash
# Get all issues
curl http://localhost:8080/api/issues \
  -H "Authorization: Bearer <token>"

# Get issues by project
curl "http://localhost:8080/api/issues?projectId=abc123" \
  -H "Authorization: Bearer <token>"

# Create issue (proxied to CxAlloy)
curl -X POST http://localhost:8080/api/issues \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "New Issue",
    "description": "Details here",
    "projectId": "your-project-id",
    "priority": "HIGH",
    "status": "OPEN"
  }'

# Update issue
curl -X PUT http://localhost:8080/api/issues/{issueId} \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"title": "Updated", "status": "IN_PROGRESS"}'

# Delete issue
curl -X DELETE http://localhost:8080/api/issues/{issueId} \
  -H "Authorization: Bearer <token>"

# Sync issues from CxAlloy
curl -X POST http://localhost:8080/api/issues/sync \
  -H "Authorization: Bearer <token>"
```

### Users

```bash
# Get all users
curl http://localhost:8080/api/users \
  -H "Authorization: Bearer <token>"

# Sync users from CxAlloy
curl -X POST http://localhost:8080/api/users/sync \
  -H "Authorization: Bearer <token>"
```

### Sync Stats

```bash
curl http://localhost:8080/api/sync/stats \
  -H "Authorization: Bearer <token>"
```

---

## Complete cURL Workflow Example

```bash
# 1. Login
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | \
  python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")

# 2. Sync all data
curl -X POST http://localhost:8080/api/sync/all \
  -H "Authorization: Bearer $TOKEN"

# 3. Query projects
curl http://localhost:8080/api/projects \
  -H "Authorization: Bearer $TOKEN"

# 4. Logout
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer $TOKEN"
```

---

## H2 Console

URL: `http://localhost:8080/h2-console`
- JDBC URL: `jdbc:h2:mem:cxalloydb`
- Username: `sa`
- Password: `password`
- (No JWT needed — browser accessible)

---

## Project Structure

```
src/main/java/com/cxalloy/integration/
├── CxAlloyIntegrationApplication.java
├── client/
│   ├── CxAlloyApiClient.java
│   └── HmacSignatureUtil.java
├── config/
│   ├── CxAlloyApiProperties.java
│   ├── JacksonConfig.java
│   ├── RestTemplateConfig.java
│   └── SecurityConfig.java          ← JWT security chain
├── controller/
│   ├── AuthController.java          ← /api/auth/* (login/refresh/logout/me)
│   ├── GlobalExceptionHandler.java
│   ├── IssueController.java
│   ├── ProjectController.java
│   ├── SyncController.java
│   └── UserController.java
├── dto/
│   ├── ApiResponse.java
│   ├── IssueRequest.java
│   ├── LoginRequest.java            ← NEW
│   ├── RefreshTokenRequest.java     ← NEW
│   ├── SyncResult.java
│   └── TokenResponse.java          ← NEW
├── model/
│   ├── ApiSyncLog.java
│   ├── CxAlloyUser.java
│   ├── Issue.java
│   ├── Project.java
│   └── RawApiResponse.java
├── repository/ ...
├── security/
│   ├── JwtAuthenticationEntryPoint.java  ← 401 JSON responses
│   ├── JwtAuthenticationFilter.java      ← Token extraction & validation
│   ├── JwtTokenProvider.java             ← HMAC-SHA256 token generation
│   └── TokenBlacklistService.java        ← Logout blacklist
└── service/
    ├── AuthService.java             ← NEW
    ├── IssueService.java
    ├── ProjectService.java
    ├── SyncService.java
    └── UserService.java
```
