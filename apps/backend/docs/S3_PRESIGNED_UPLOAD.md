# S3 Presigned 직접 업로드

## 문제

다중 백엔드 인스턴스에서 로컬 디스크에 파일을 저장하면 업로드 노드와 조회 노드가 달라질 때 파일을 찾지 못한다. 서버가 파일 본문을 중계하면 Tomcat 연결과 네트워크도 파일 크기만큼 점유한다.

## 개선 흐름

1. 프론트가 `POST /api/files/upload/presign`에 파일명, MIME 타입, 크기를 전달한다.
2. 백엔드는 파일 정책을 검증하고 `chat/<서버 생성 UUID성 파일명>` key와 10분짜리 Presigned PUT URL을 반환한다.
3. 프론트는 AWS 자격증명 없이 해당 URL로 파일을 직접 PUT한다.
4. 채팅 메시지에는 기존과 동일한 파일 `_id`, 파일명, MIME 타입, 크기를 전달한다.
5. 파일 조회는 기존 `/api/files/view/{filename}`에서 참가자 권한을 확인한 후 5분짜리 Presigned GET URL로 리다이렉트한다.

기존 API와 Socket.IO 파일 메시지 계약은 유지한다. 로컬 개발은 `FILE_STORAGE_TYPE=local`과 기존 서버 업로드를 계속 사용한다.

## 운영 환경변수

```env
FILE_STORAGE_TYPE=s3
AWS_S3_BUCKET=ktb-15-bucket
AWS_REGION=ap-northeast-2
NEXT_PUBLIC_FILE_UPLOAD_MODE=presigned
```

프론트 Docker 빌드에는 GitHub Repository Variable `FRONTEND_FILE_UPLOAD_MODE=presigned`를 설정한다. 백엔드 서버의 기존 env 파일에는 `FILE_STORAGE_TYPE`, `AWS_S3_BUCKET`, `AWS_REGION`을 설정한다.

백엔드 인스턴스에는 `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` 권한을 가진 IAM Role을 연결한다. Access Key와 Secret Key를 프론트 또는 저장소에 넣지 않는다.

## S3 CORS

버킷에서 운영 프론트 Origin의 직접 PUT을 허용해야 한다.

```json
[
  {
    "AllowedOrigins": ["https://chat.goorm-ktb-015.goorm.team"],
    "AllowedMethods": ["PUT", "GET", "HEAD"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3000
  }
]
```

## 확인 방법

- Presign 응답에 `uploadUrl`, `objectKey`, 기존 파일 메타데이터가 포함되는지 확인
- S3 PUT이 200인지 확인
- 서로 다른 백엔드 노드를 거쳐도 이미지 미리보기와 다운로드가 성공하는지 확인
- 기존 전체 E2E와 동일 조건 Artillery에서 파일 시나리오가 통과하는지 확인
