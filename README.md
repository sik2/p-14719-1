# 마이크로서비스 백엔드

## 작업 이력

---

# 0000 - 초기 프로젝트 설정

## 개요
Spring Boot 4.0.1 기반 마이크로서비스 백엔드 프로젝트

## 기술 스택
- Java 25
- Spring Boot 4.0.1
- Spring Data JPA
- Spring Security
- Spring Batch
- Spring Kafka
- JWT (jjwt 0.12.6)
- MySQL 8.4
- Redpanda (Kafka 호환)

## 프로젝트 구조
```
├── common/           # 공유 라이브러리 모듈
├── member-service/   # 회원 관리 (포트: 8080)
├── post-service/     # 게시글 관리 (포트: 8081)
├── payout-service/   # 정산 관리 (포트: 8082)
├── cash-service/     # 지갑/결제 관리 (포트: 8083)
├── market-service/   # 상품/주문 관리 (포트: 8084)
└── k8s/              # Kubernetes 배포 설정
```

## 인프라
- Kubernetes (Docker Desktop)
- Traefik Ingress Controller
- Redpanda (Kafka 호환 메시지 브로커)

## 인증/인가
- JWT 기반 AccessToken
- API Key 인증
- 시스템 API Key (서비스 간 통신)

---

# 0001 - Spring Cloud Gateway 도입

## 개요
API Gateway를 도입하여 인증/인가를 Gateway에서 1회만 처리하도록 아키텍처 변경

## 변경 이유
- 기존: 각 서비스마다 CustomAuthenticationFilter에서 인증 처리 (중복)
- 변경: Gateway에서 인증 후 X-User-* 헤더로 사용자 정보 전달

## 아키텍처 변경

### Before
```
Client → Ingress → 각 서비스 (인증 처리)
                   └── CustomAuthenticationFilter
                   └── AuthTokenValidator
```

### After
```
Client → Ingress → API Gateway (인증 처리) → 각 서비스 (헤더만 읽음)
                   └── AuthenticationGlobalFilter    └── Rq.getActor()
                   └── MemberServiceClient               (X-User-* 헤더 파싱)
```

## 신규 모듈: api-gateway

### 의존성 (build.gradle.kts)
```kotlin
extra["springCloudVersion"] = "2025.1.0"

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
}
```

> **Note**: Spring Cloud 2025.1.0에서 Gateway artifact 이름이 변경됨
> - Before: `spring-cloud-starter-gateway`
> - After: `spring-cloud-starter-gateway-server-webflux`

### 디렉토리 구조
```
api-gateway/
├── build.gradle.kts
└── src/main/
    ├── java/com/back/
    │   ├── ApiGatewayApplication.java
    │   ├── client/
    │   │   └── MemberServiceClient.java
    │   ├── config/
    │   │   ├── JacksonConfig.java
    │   │   └── SecurityConfig.java
    │   ├── dto/
    │   │   └── MemberDto.java
    │   └── filter/
    │       └── AuthenticationGlobalFilter.java
    └── resources/
        ├── application.yml
        └── application-prod.yml
```

### AuthenticationGlobalFilter.java
Gateway의 핵심 인증 필터 (GlobalFilter 구현)

| 처리 순서 | 설명 |
|----------|------|
| 1 | /api/ 로 시작하지 않으면 통과 |
| 2 | 공개 엔드포인트 체크 (login, join, GET posts) |
| 3 | Authorization 헤더 파싱 (Bearer apiKey accessToken) |
| 4 | 시스템 API Key 체크 → 시스템 사용자로 처리 |
| 5 | member-service 호출하여 토큰/API Key 검증 |
| 6 | 검증 성공 시 헤더 추가 후 다음 필터로 전달 |

### 공개 엔드포인트 (인증 불필요)
| 경로 | 메서드 | 설명 |
|------|--------|------|
| `/api/v1/member/members/login` | POST | 로그인 |
| `/api/v1/member/members/logout` | POST | 로그아웃 |
| `/api/v1/member/members/join` | POST | 회원가입 |
| `/api/v1/post/posts` | GET | 게시글 목록 조회 |
| `/api/v1/post/posts/{id}` | GET | 게시글 상세 조회 |
| `/api/v1/post/posts/{id}/comments` | GET | 댓글 목록 조회 |

### 인증 성공 시 추가되는 헤더
| 헤더 | 설명 |
|------|------|
| X-User-Id | 사용자 ID |
| X-User-Name | 사용자명 (username) |
| X-User-Nickname | 닉네임 |

### MemberServiceClient.java
WebClient 기반 member-service 연동

| 메서드 | 엔드포인트 | 설명 |
|--------|-----------|------|
| validateToken() | POST /validate-token | AccessToken 검증 |
| findByApiKey() | GET /by-apikey/{apiKey} | API Key로 회원 조회 |
| generateAccessToken() | POST /{id}/access-token | AccessToken 재발급 |

### SecurityConfig.java
WebFlux Security 설정
- CSRF 비활성화
- Form Login 비활성화
- 모든 요청 permitAll (인증은 GlobalFilter에서 처리)
- CORS 설정 (localhost:3000, cdpn.io 허용)

### application.yml (로컬)
```yaml
server:
  port: 9000

spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: member-service
              uri: http://localhost:8080
              predicates:
                - Path=/api/v1/member/**
            - id: post-service
              uri: http://localhost:8081
              predicates:
                - Path=/api/v1/post/**
            # ... (payout:8082, cash:8083, market:8084)
```

### application-prod.yml (K8s)
```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: member-service
              uri: http://member-service:8080
              predicates:
                - Path=/api/v1/member/**
            # ... (service-name:8080)
```

## 포트 설정
| 서비스 | 로컬 포트 | K8s 포트 |
|--------|----------|----------|
| api-gateway | 9000 | 9000 |
| member-service | 8080 | 8080 |
| post-service | 8081 | 8080 |
| payout-service | 8082 | 8080 |
| cash-service | 8083 | 8080 |
| market-service | 8084 | 8080 |

## 변경 파일
| 파일 | 변경 내용 |
|------|----------|
| settings.gradle.kts | api-gateway 모듈 추가 |
| api-gateway/* | 신규 생성 |

## API 테스트 예제

### 회원가입
```bash
curl -X POST http://localhost:9000/api/v1/member/members/join \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"1234","nickname":"테스터"}'
```

### 로그인
```bash
curl -X POST http://localhost:9000/api/v1/member/members/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"1234"}'
```

응답 예시:
```json
{
  "resultCode": "200",
  "data": {
    "apiKey": "abc123...",
    "accessToken": "eyJhbG..."
  }
}
```

### 인증된 요청
```bash
# Authorization 헤더 사용
curl -X GET http://localhost:9000/api/v1/member/members/me \
  -H "Authorization: Bearer {apiKey} {accessToken}"

# 시스템 API Key 사용 (서비스 간 통신)
curl -X GET http://localhost:9000/api/v1/member/members/1 \
  -H "Authorization: Bearer test-system-api-key"
```

---

# 0002 - common 모듈 인증 로직 제거

## 개요
Gateway에서 인증을 처리하므로 각 서비스의 인증 로직 제거

## 변경 이유
- Gateway에서 인증 후 X-User-* 헤더로 사용자 정보 전달
- 각 서비스는 헤더만 읽으면 되므로 기존 인증 로직 불필요
- 코드 중복 제거 및 단일 책임 원칙 적용

## 삭제된 파일

| 모듈 | 파일 | 설명 |
|------|------|------|
| common | CustomAuthenticationFilter.java | JWT/API Key 인증 필터 |
| common | AuthTokenValidator.java | 토큰 검증 인터페이스 |
| member-service | LocalAuthTokenValidator.java | AuthTokenValidator 구현체 |

> **Note**: `MemberApiClient`는 인증 외 기능(getRandomSecureTip 등)을 위해 유지

## 수정된 파일

### common/SecurityConfig.java
- CustomAuthenticationFilter 의존성 제거
- addFilterBefore() 제거
- 모든 요청 permitAll() 처리 (인증은 Gateway에서 처리)
- CORS 설정 제거 (Gateway에서 처리)
- exceptionHandling 제거 (Gateway에서 인증 처리)

```java
// Before
.requestMatchers("/api/*/**").authenticated()
.addFilterBefore(customAuthenticationFilter, ...)
.cors(cors -> cors.configurationSource(corsConfigurationSource()))
.exceptionHandling(...)

// After
.anyRequest().permitAll()
// CORS, exceptionHandling 제거
```

### common/Rq.java - getActor() 수정
Gateway가 전달한 헤더에서 사용자 정보 읽기

```java
public SecurityUser getActor() {
    // 1. SecurityContext에서 먼저 확인 (기존 방식)
    SecurityUser actor = Optional.ofNullable(
            SecurityContextHolder.getContext().getAuthentication()
        )
        .map(Authentication::getPrincipal)
        .filter(principal -> principal instanceof SecurityUser)
        .map(principal -> (SecurityUser) principal)
        .orElse(null);

    if (actor != null) {
        return actor;
    }

    // 2. Gateway가 전달한 헤더에서 읽기
    String userId = req.getHeader("X-User-Id");
    if (userId == null || userId.isBlank()) {
        return null;
    }

    return new SecurityUser(
            Integer.parseInt(userId),
            req.getHeader("X-User-Name"),
            "",
            req.getHeader("X-User-Nickname"),
            Collections.emptyList()
    );
}
```

## 인증 흐름 변경

### Before (각 서비스에서 인증)
```
1. Client 요청 (Authorization: Bearer apiKey accessToken)
2. CustomAuthenticationFilter에서 토큰 파싱
3. AuthTokenValidator.validateToken() 호출
4. SecurityContext에 Authentication 설정
5. Controller에서 @AuthenticationPrincipal 사용
```

### After (Gateway에서 인증)
```
1. Client 요청 (Authorization: Bearer apiKey accessToken)
2. Gateway AuthenticationGlobalFilter에서 토큰 검증
3. 검증 성공 시 X-User-* 헤더 추가
4. 각 서비스 Rq.getActor()에서 헤더 읽기
5. Controller에서 rq.getActor() 사용
```

---

# 0003 - docker-compose Gateway 추가

## 개요
docker-compose에 api-gateway 서비스 추가

## 변경 사항

### docker-compose.yml
```yaml
api-gateway:
  <<: *service-common
  build:
    <<: *build-common
    args:
      MODULE: api-gateway
  container_name: api-gateway
  image: sik2dev/p-14650-2:api-gateway
  ports:
    - "9000:9000"
  depends_on:
    - member-service
    - post-service
    - payout-service
    - cash-service
    - market-service
```

## 실행 방법

### 로컬 개발 (IDE 개별 실행)
```bash
# Kafka만 실행
docker-compose up redpanda

# IntelliJ에서 각 서비스 Run Configuration 실행
# api-gateway → localhost:9000
# member-service → localhost:8080
# post-service → localhost:8081
# ...
```

### Docker Compose 전체 실행
```bash
docker-compose --profile prod up
```

## 접속 정보
| 서비스 | URL |
|--------|-----|
| API Gateway | http://localhost:9000 |
| Redpanda Console | http://localhost:8090 |

---

# 0004 - Kubernetes Gateway 설정

## 개요
Kubernetes에 api-gateway 배포 설정 추가 및 Ingress 라우팅 변경

## 변경 사항

### 신규: k8s/api-gateway.yaml
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api-gateway
  namespace: backend
spec:
  replicas: 1
  selector:
    matchLabels:
      app: api-gateway
  template:
    spec:
      containers:
        - name: api-gateway
          image: sik2dev/p-14650-2:api-gateway
          ports:
            - containerPort: 9000
          # Health Check, Resources 등
---
apiVersion: v1
kind: Service
metadata:
  name: api-gateway
spec:
  ports:
    - port: 9000
      targetPort: 9000
```

### 수정: k8s/ingress.yaml
모든 트래픽을 api-gateway로 단일 라우팅

```yaml
# Before (각 서비스로 직접 라우팅)
paths:
  - path: /api/v1/member → member-service:8080
  - path: /api/v1/post → post-service:8080
  - path: /api/v1/market → market-service:8080
  - path: /api/v1/cash → cash-service:8080
  - path: /api/v1/payout → payout-service:8080

# After (Gateway로 단일 라우팅)
paths:
  - path: / → api-gateway:9000
```

### 수정: k8s/kustomization.yaml
```yaml
resources:
  - api-gateway.yaml  # 추가
```

## 트래픽 흐름

```
Client
   │
   ▼
┌─────────────────────────────────────────┐
│          Ingress (Traefik)              │
│       /* → api-gateway:9000             │
└───────────────────┬─────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│            API Gateway                  │
│         (인증/인가 처리)                 │
└───────────────────┬─────────────────────┘
                    │
    ┌───────┬───────┼───────┬───────┐
    ▼       ▼       ▼       ▼       ▼
 member   post   payout   cash   market
 :8080    :8080   :8080   :8080   :8080
```

## 배포 명령어
```bash
# 전체 배포
kubectl apply -k k8s/

# Gateway만 재배포
kubectl rollout restart deployment/api-gateway -n backend

# 상태 확인
kubectl get pods -n backend
```

## K8s 접속 정보
| 서비스 | URL |
|--------|-----|
| API | http://api.127.0.0.1.nip.io/api/v1/{service}/... |

---

# 0005 - Swagger 통합

## 개요
API Gateway에서 모든 서비스의 Swagger 문서를 통합 제공

## 변경 사항

### api-gateway/build.gradle.kts
```kotlin
implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:3.0.1")
```

> **Note**: Spring Boot 4.0 / Spring Framework 7.0에서는 springdoc-openapi 3.0.x 필요
> - springdoc-openapi 2.x는 Spring Framework 6.x 기반
> - springdoc-openapi 3.0.x는 Spring Framework 7.x 지원

### application.yml
```yaml
# 각 서비스의 API docs를 Gateway에서 통합
springdoc:
  swagger-ui:
    urls:
      - name: member-service
        url: /v3/api-docs/member
      - name: post-service
        url: /v3/api-docs/post
      - name: payout-service
        url: /v3/api-docs/payout
      - name: cash-service
        url: /v3/api-docs/cash
      - name: market-service
        url: /v3/api-docs/market

# API docs 라우팅
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: member-service-docs
              uri: http://localhost:8080
              predicates:
                - Path=/v3/api-docs/member
              filters:
                - RewritePath=/v3/api-docs/member, /v3/api-docs
            # ... (각 서비스 동일 패턴)
```

## 접속 방법
| URL | 설명 |
|-----|------|
| http://localhost:9000/swagger-ui.html | Swagger UI (통합) |
| http://localhost:9000/v3/api-docs/member | member-service API 스펙 |
| http://localhost:9000/v3/api-docs/post | post-service API 스펙 |

## 아키텍처
```
Client → Gateway (swagger-ui.html)
              │
              ├─ /v3/api-docs/member → member-service:8080/v3/api-docs
              ├─ /v3/api-docs/post → post-service:8081/v3/api-docs
              ├─ /v3/api-docs/payout → payout-service:8082/v3/api-docs
              ├─ /v3/api-docs/cash → cash-service:8083/v3/api-docs
              └─ /v3/api-docs/market → market-service:8084/v3/api-docs
```

---

# 0006 - Gateway CORS 및 Swagger 서버 URL 설정

## 개요
Gateway 통합 Swagger에서 "Try it out" 기능 사용을 위한 CORS 및 서버 URL 설정

## 문제 상황
```
Swagger UI (localhost:9000)
    ↓ API 요청
각 서비스 포트 (8080, 8081...) → CORS 에러
```

## 해결 방법
1. 각 서비스의 OpenAPI 스펙에서 서버 URL을 Gateway로 설정
2. Gateway에서 CORS 처리 (common에서 제거)

## 변경 사항

### common/SwaggerConfig.java
```java
@Value("${springdoc.server-url:}")
private String serverUrl;

// Gateway 통합 Swagger 사용 시 필수
// 설정 없으면 각 서비스 포트로 직접 요청 → CORS 에러
// 설정 있으면 Gateway(9000)로 요청 → 정상 동작
if (serverUrl != null && !serverUrl.isBlank()) {
    openAPI.servers(List.of(new Server().url(serverUrl)));
}
```

### 각 서비스 application.yml
```yaml
springdoc:
  server-url: http://localhost:9000
```

### 각 서비스 application-prod.yml
```yaml
springdoc:
  server-url: ${GATEWAY_URL:http://api-gateway:9000}
```

### api-gateway/SecurityConfig.java
| 항목 | 변경 |
|------|------|
| allowedOrigins | localhost:9000 추가 |
| allowedMethods | OPTIONS 추가 |
| 경로 | /api/** → /** |
| OPTIONS 요청 | permitAll() 추가 |

### common/SecurityConfig.java
| 항목 | 변경 |
|------|------|
| CORS 설정 | 제거 (Gateway에서 처리) |
| exceptionHandling | 제거 (Gateway에서 인증 처리) |

## CORS 처리 위치
```
Gateway 아키텍처에서 CORS는 Gateway에서만 처리

Client (브라우저)
    ↓ CORS 체크
API Gateway (CORS 허용) ← 여기서만 CORS 설정
    ↓ 내부 통신 (CORS 불필요)
백엔드 서비스들
```

## 요청 흐름
```
Swagger UI (localhost:9000/swagger-ui.html)
    ↓ springdoc.server-url 설정으로 Gateway로 요청
API Gateway (localhost:9000) ← CORS 허용
    ↓ 라우팅
각 서비스 (8080, 8081...)
```
