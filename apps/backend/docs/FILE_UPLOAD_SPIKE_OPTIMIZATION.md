# 파일 업로드 스파이크 최적화

## 문제

Artillery 파일 업로드 시나리오는 이미지를 선택한 직후 전송하며,
고동시성에서 Presign `200` 응답을 15초 안에 관찰하지 못하면 전체 워커가 종료된다.

100명 API 단독 측정에서 Presign은 p99 424.7ms, S3 PUT은 p99 7,425.6ms였다.
원본 파일 크기와 해시를 검증하는 E2E 계약 때문에 이미지 압축은 적용하지 않는다.

## 원인

- Presign 요청마다 JWT에 이미 포함된 `userId`를 두고 이메일로 사용자를 MongoDB에서 다시 조회했다.
- S3 PUT 진행률 이벤트마다 React 상태를 변경해 다수 브라우저가 동시에 재렌더링될 수 있었다.
- 파일 선택 직후 전송하면 React state commit 전에 전송 핸들러가 실행될 가능성이 있었다.

## 개선

- 인증 과정에서 검증된 `Authentication.details.userId`를 Presign에 재사용한다.
- 진행률은 10%p 변화 또는 100% 완료 시점에만 UI에 반영한다.
- 선택한 파일을 ref에 동기적으로 보관해 즉시 전송해도 업로드 흐름이 누락되지 않게 한다.
- 업로드 완료까지 입력 UI를 비활성화하고 `onSubmit`을 `await`해 중복 전송을 막는다.
- 비멱등 multipart/Presign 업로드의 공통 Axios 재시도를 끄고, 클라이언트와 서버 크기 제한을 5MB로 통일한다.

## 기대 효과

- Presign 1회당 MongoDB operation을 `find + insert` 2회에서 `insert` 1회로 줄인다.
- 1,000명 스파이크에서 사용자 재조회 최대 1,000회를 제거한다.
- 업로드당 UI 진행률 갱신을 최대 약 10회로 제한해 부하 생성기의 CPU 경쟁을 줄인다.

## 확인 방법

1. 같은 파일과 VU/Duration으로 변경 전후 Artillery를 실행한다.
2. `POST /api/files/upload/presign`의 상태 코드와 p95/p99를 비교한다.
3. `loadtest/file-upload-api-load.js`로 Presign과 S3 PUT을 분리 측정한다.
4. 기존 이미지 렌더링·다운로드·원본 해시 E2E가 모두 통과하는지 확인한다.
