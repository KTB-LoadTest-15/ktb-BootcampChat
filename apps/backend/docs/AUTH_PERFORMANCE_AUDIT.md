# 회원가입·로그인 성능 개선 후보

## 목적과 범위

- 대상 API: `POST /api/auth/register`, `POST /api/auth/login`
- API URL, method, 요청·응답, JWT·session 계약은 유지한다.
- 회원가입·로그인 E2E 시나리오 통과를 안정성 기준으로 삼는다.
- 비밀번호 해시·이메일 암호화 강도를 성능을 위해 낮추지 않는다.

## 현재 요청 흐름

### 회원가입

```text
입력 검증
→ users email 중복 조회
→ BCrypt 패스워드 해시
→ User save (email 소문자화·암호화 listener 실행)
→ 201 응답
```

일반적인 정상 요청의 MongoDB operation은 `find + insert` 2회이며, 실제 수는 command 계측으로 확인한다.

### 로그인

```text
AuthController의 users email 조회
→ AuthenticationManager
→ UserDetailsServiceImpl의 users email 재조회
→ BCrypt matches
→ AuthController의 기존 session 삭제
→ SessionService.createSession의 기존 session 재삭제
→ Session save
→ JWT 생성
→ 200 응답
```

정상 로그인 1건은 코드상 `users find` 2회, `sessions delete` 2회, `sessions save` 1회로 최대 5회의 MongoDB operation이 발생할 수 있다.

## P0 — 바로 확인·개선할 항목

### 1. 로그인 사용자 중복 조회

**문제**  
`AuthController` 와 `UserDetailsServiceImpl`이 동일한 email로 `users`를 각각 조회한다.

**원인**  
controller는 응답 DTO·userId를 위해 `User`를 미리 조회하고, Spring Security는 인증을 위해 다시 `loadUserByUsername()`을 호출한다. 현재 Security principal은 id·name이 없는 Spring 기본 `User`이다.

**확인 방법**  
정상 로그인 1회의 MongoDB `find` command를 계측한다.

**개선 방법**  
id·name·email·password를 보유한 custom principal을 `UserDetailsServiceImpl`에서 반환하고, 인증 성공 후 principal에서 DTO·session 생성에 필요한 값을 사용한다. controller의 선행 `findByEmail()`은 제거한다.

**기대 효과**  
정상 로그인의 `users find`를 2회에서 1회로 축소한다.

### 2. 로그인 세션 중복 삭제

**문제**  
`AuthController.login()`이 `removeAllUserSessions()`를 호출한 뒤 `createSession()`을 호출하지만, `createSession()` 내부에서도 같은 삭제를 수행한다.

**원인**  
단일 세션 정책 책임이 controller와 service 두 곳에 중복돼 있다.

**확인 방법**  
정상 로그인 1회의 `sessions` delete command 수를 계측한다.

**개선 방법**  
단일 세션 보장 책임을 `SessionService.createSession()`에만 두고 controller의 선행 삭제를 제거한다.

**기대 효과**  
로그인당 `sessions delete` 1회를 제거하고 세션 정책을 한 곳에서 관리한다.

### 3. 회원가입 email 정규화 순서 불일치

**문제**  
중복 확인은 요청 email 원문으로 수행하고 저장은 소문자로 변환한다. `Test@Example.com`이 이미 `test@example.com`으로 저장돼 있으면 선행 조회가 놓칠 수 있고, 결국 unique index 예외까지 진행한다.

**원인**  
입력 정규화가 controller·builder·Mongo event listener에 분산돼 있다.

**확인 방법**  
대소문자만 다른 동일 email로 회원가입하고 `find`·duplicate-key 발생을 확인한다.

**개선 방법**  
요청 처리 시 email을 한 번 소문자화하여 중복 조회와 저장에 같은 값을 사용한다. unique index와 `DuplicateKeyException` 처리는 동시성에 대한 최종 보장으로 유지한다.

**기대 효과**  
대소문자 차이 중복 요청이 불필요한 BCrypt·insert 시도까지 진행하는 것을 막는다.

## P1 — 측정 후 결정할 항목

### 4. 세션 delete + insert의 atomic replace/upsert 검토

현재 단일 세션 교체는 delete 후 insert로 최소 2회 쓰기를 수행한다. `userId` unique index와 atomic upsert로 1회로 축소할 수 있지만, 동시 로그인·중복 로그인 알림·기존 소켓 종료 순서에 영향을 줄 수 있다. P0 중복 제거 후에도 session write가 병목일 때만 별도로 검증한다.

### 5. BCrypt 비용과 보안 기준

현재 cost는 4이며 회원가입의 `encode`, 로그인의 `matches`가 CPU 비용을 발생시킨다. 별도 API 부하에서 CPU·p95·p99를 측정하되, 성능 목적으로 cost를 더 낮추지 않는다. 운영 보안 기준에 맞는 cost 상향은 성능 개선과 별도의 보안 결정으로 다룬다.

### 6. email 암호화 listener 비용

`User` 저장 전 listener가 `ApplicationContext`에서 `EncryptionUtil`을 조회하고 email을 암호화한다. 회원가입 profile에서 암호화가 유의미한 CPU 비용인지 측정한 후, 필요하면 bean 직접 주입과 email 변경 시에만 암호화하는 방식을 검토한다. 암호화 자체는 제거하지 않는다.

### 7. 회원가입의 사용하지 않는 metadata 생성 제거

`registerUser()`는 `SessionMetadata`를 생성하지만 세션을 만들지 않고 metadata도 사용하지 않는다. 성능 효과는 작지만 dead code로서 제거 후 기존 응답이 같은지 확인한다.

## 건드리지 않을 항목

- API URL·HTTP status·response body·인증 header
- 단일 세션 정책과 중복 로그인 처리 계약
- email unique index·duplicate-key 최종 보장
- 비밀번호 해시와 email 암호화
- 기존 `e2e/**` 시나리오·action·설정

## 권장 작업 순서

1. 정상·실패 회원가입/로그인의 현재 MongoDB command 수와 응답 시간 기록
2. 로그인 세션 중복 삭제 제거
3. custom principal로 로그인 사용자 중복 조회 제거
4. 회원가입 email 정규화 시점 통일과 미사용 metadata 제거
5. 동일 조건으로 MongoDB command 수·평균·p95·p99 재측정
6. 기존 회원가입·로그인 E2E 및 전체 E2E 통과 확인
7. 남은 병목이 확인될 때만 atomic session upsert·listener 최적화 검토

## 예상 최소 개선폭

P0 1·2번을 적용하면 정상 로그인의 코드상 MongoDB operation 상한을 최대 5회에서 3회(`users find` 1 + `sessions delete` 1 + `sessions save` 1)로 줄일 수 있다. 실제 개선폭은 command listener 또는 profiler로 확인한다.
