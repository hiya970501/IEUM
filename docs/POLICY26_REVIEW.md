# IEUM 1차 제출본 - 최종 E2E와 26개 규약 점검

검토일: 2026-11-14 / 팀 IEUM / 앱 IEUM 1.4.0 (15)

## 판정 범위

**충족 10 / 부분 10 / 미충족 3 / 외부 확인 2 / 해당 없음 1**

이는 사용자 제공 26항목에 대한 개발팀 자체 점검이며 인증 결과가 아닙니다. '충족'은 현재 코드·에뮬레이터 시험 범위의 해당 요구를 확인했다는 뜻입니다. 실차·지역·마켓 정책 전체 인증으로 해석하지 않습니다.

현재 Debug `a1a80e25b6d77f79f385ef4c400faf2251cf5ab2e0bf7db3183556d326390ba8`의 IONIQ 5 로컬 연구용 모델 검사와 서명 release `5bd1f44372c0116c659115c8616fc4d9f8d71167158909353fb79736fcdb2870`의 배포용 기본 실내 검사를 구분했습니다. 2차에서 고쳐야 할 실패 결과를 지우거나 빠른 표본만 골라 통과로 바꾸지 않았습니다.

## 마지막 시험 결과

| 검사 | 결과 | 범위·차이 |
|---|---|---|
| 빌드/단위/Lint | 통과 | 단위344개, 오류/실패0. release DEX·manifest·모델19핀 검사 |
| 현재 소스 E2E | 8영역 통과 | 공조/모드4·기능8·개인정보15, 자원·메뉴·IME·투영·GPS; 시험 후 정리와 해시 확인 |
| 내부 콘텐츠 반영 | 12회 최대98ms | 실제 OS DOWN 기준. 접근성 확인·물리 화면 출력 시간과 다름 |
| 접근성 콘텐츠 확인 | **12회 최대265ms / 실패** | 250ms보다15ms 초과. 주입/확인 도구 비용 포함; 물리 scanout 미검증 |
| 개발본 메모리 | 콜드HWM268.85MB / 계측 최고272.92MB | 400MB 내 관측. 샘플 사이 최대치와 실차 보장 아님 |
| 정차 초기 CPU | **평균33.73%·최대73.55% / 실패** | 25% 초과. 100%=논리CPU1개; 2CPU 용량 참고 최대36.78%도25% 초과 |
| 주행 제한/CPU | 통과 | D·20km/h: 설정 닫힘/차단, CPU평균0.88%·최대1.74% (<5%), P0 복원 |
| 야간 UI | 통과(범위 한정) | 텍스트280건 최저7.73:1; 물리mm·전체3D 이미지 대비는 미검증 |
| 정확한 서명 release | 통과 | 별도 깨끗한 Pleos AVD. 세부 결과는 FINAL_VERIFICATION.json |
| 서명/네이티브 | 통과(정적 속성) | RSA3072, v3검증,20개 RELRO/NX/실행·쓰기 분리/16KB정렬; 전체 취약점 인증 아님 |

정차 초기 CPU는 시작 직후 약4초 대기 뒤 약11.8초 구간의 스레드 그룹 CPU 델타입니다. Cold start를 빼고 안정 구간만 골라 만족으로 표시하지 않았습니다. CPU 분모는 공식 해석을 추가 확인해야 합니다.

## 26개 항목

### 01. 개발 환경 - 충족

- 목표: API28+ / Gradle8+ / Pleos2.1.0 Cold boot
- 현재 수준/차이: min28·Gradle9.6·API34/Pleos2.1.0. 최종 빌드·344개 단위 검사 통과.
- 2차 개발 방향: 버전 고정과 신규 설치 절차를 유지하고 실차 환경을 추가한다.
- 기준: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator

### 02. 주의 분산 - 부분

- 목표: 운전자 주의 분산 행동 제한
- 현재 수준/차이: D·20km/h에서 설정 닫힘·조작 차단 확인. 앱 자체 안내가 OEM 화면에 가려질 수 있다.
- 2차 개발 방향: OEM 차단을 유지하고 운전 중 정보 노출·카테고리별 허용 범위를 실차로 검증한다.
- 기준: https://source.android.com/docs/automotive/driver_distraction/guidelines?hl=ko

### 03. UX 제한 - 충족

- 목표: UX 제한·불명 상태에서 차단
- 현재 수준/차이: CarUxRestrictions·안전 상태 게이트와 미확인 차단 회귀 통과. 현재 에뮬레이터 범위다.
- 2차 개발 방향: 연결 손실·차종별 UX 플래그를 회귀 목록에 추가한다.
- 기준: https://source.android.com/docs/automotive/driver_distraction/car_uxr?hl=ko

### 04. 운전 상태 - 충족

- 목표: 기어·UX·전경 상태 함께 검사
- 현재 수준/차이: 공조 적용/복원·모드·화면 생명주기 회귀 통과. 주행 시험 후 P·0km/h 복원.
- 2차 개발 방향: 실차 전원 전환·통화·빠른 재생성·지연 콜백을 확대 검증한다.
- 기준: https://source.android.com/docs/automotive/driver_distraction/consume?hl=ko

### 05. 저장 동작 - 부분

- 목표: 불필요한 쓰기·플래시 소모 억제
- 현재 수준/차이: 요약 저장·중복 억제·원자적 저장/롤백 보호 구현. 장기 플래시 쓰기량 계측은 없다.
- 2차 개발 방향: 24시간 쓰기 바이트·횟수를 계측하고 변경분 배치·기간 정리 정책을 검증한다.
- 기준: https://source.android.com/docs/automotive/flash-wear?hl=ko

### 06. 지역 규제 - 외부 확인

- 목표: 지역별 주행 정책 준수
- 현재 수준/차이: 플랫폼 제한을 임의 해제하지 않는다. 국가별 실제 규제 적합성 심사는 미완료다.
- 2차 개발 방향: 출시 지역·OEM별 허용 동작 표와 실차 시험을 확보한다.
- 기준: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy

### 07. 앱 카테고리 - 외부 확인

- 목표: 적합한 카테고리로 등록
- 현재 수준/차이: Mobility Services 제안 초안. Android productivity와 구분. 마켓 승인 미완료.
- 2차 개발 방향: 차량 개인화 범위로 App Market 카테고리 심사를 진행한다.
- 기준: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category

### 08. 구조·실행 - 충족

- 목표: 창·전경·상태 전환 대응
- 현재 수준/차이: 메인 화면·내부 패널·IME·투영·GPS 회귀와 네이티브 정리 확인.
- 2차 개발 방향: 반복 다중 창·메모리 압박·복귀와 장시간 저장 경합을 추가한다.
- 기준: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model

### 09. 하드웨어 접근 - 충족

- 목표: 공식 API·허용된 권한만 사용
- 현재 수준/차이: 차량 제어는 Pleos 공개 SDK를 사용하고 capability 확인 뒤 제어한다.
- 2차 개발 방향: 실차 권한 목록과 지원 좌석/기능을 차종별로 재검증한다.
- 기준: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/platform-api-architecture

### 10. SDK 정책 - 충족

- 목표: 공개 SDK·필요 API 사용
- 현재 수준/차이: Vehicle2.3.2 사용. 숨겨진 VHAL 쓰기나 OEM 전용 권한을 추가하지 않았다.
- 2차 개발 방향: 플랫폼 SDK 업데이트 때 지원/미지원 API 계약을 다시 검사한다.
- 기준: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api

### 11. 호환·가용성 - 부분

- 목표: 미지원과 미수신을 구분
- 현재 수준/차이: 지원 확인·타임아웃·미확인 처리를 구현. API28~37/실차 전체 조합은 미검증.
- 2차 개발 방향: OS·SDK·차종별 지원표와 API28 최소 버전 실기기 검사를 추가한다.
- 기준: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability

### 12. 개인정보 - 부분

- 목표: 동의·최소화·암호화·삭제
- 현재 수준/차이: 최종 개인정보 15건 통과. 프로필 격리·철회·건강 표본과 연결 상태 분리 구현.
- 2차 개발 방향: 운영 TLS·실폰 철회/삭제·계정 전환·서버 보존 정책을 E2E로 확인한다.
- 기준: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy

### 13. 자원 효율 - 미충족

- 목표: RAM≤400MB / 정차CPU≤25% / 주행<5%
- 현재 수준/차이: 현재 계측 HWM 272.92MB. 정차 초기 평균 33.73%·최대 73.55%로 초과. 주행 최대 1.74% 통과.
- 2차 개발 방향: SDK 메타데이터 준비·3D 파싱/엔진 초기화·중복 작업을 분리 추적한다. RAM을 유지하면서 초기 CPU 피크를 낮추고 동일 조건 재측정한다.
- 기준: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt

### 14. 즉각 반응 - 미충족

- 목표: 입력 응답≤250ms
- 현재 수준/차이: 12회 내부 반영 최대98ms. 별도 접근성 확인 최대265ms로 15ms 초과. 물리 화면 출력 시점은 미검증.
- 2차 개발 방향: DOWN→처리→프레임→접근성 게시를 분리한다. 반복 입력·저장 안내·대형 글자 조건에서도 250ms 이내인지 같은 게이트로 재검증한다.
- 기준: https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro

### 15. 색상·대비 - 부분

- 목표: 텍스트·아이콘 대비≥4.5:1
- 현재 수준/차이: 야간 텍스트 280건 최저7.73:1. 복잡한 3D·이미지 전체 픽셀과 실물 패널은 미검증.
- 2차 개발 방향: 모든 선택·오류·보안·주야간 상태의 대비와 실차 화면을 검사한다.
- 기준: https://document.pleos.ai/docs/connect/guide/docs-design/foundations/colors

### 16. 글자 크기 - 부분

- 목표: 중요≥5mm / 보조≥3mm
- 현재 수준/차이: 야간 최소 글자 잉크19px(160dpi 명목 약3.0mm). 실제 패널 물리 크기·시거리 검증은 없다.
- 2차 개발 방향: OEM 패널에서 mm·시거리 실측 후 중요 문구·보조 문구별 최소 크기를 조정한다.
- 기준: https://document.pleos.ai/docs/connect/guide/docs-design/foundations/typography

### 17. 아이콘 - 부분

- 목표: 형태·크기·여백·인지
- 현재 수준/차이: IEUM adaptive 아이콘·동작 라벨 적용. 런처 마스크/실차 시거리 인지는 미검증.
- 2차 개발 방향: 여러 런처 마스크와 실제 차량 시거리에서 아이콘 판독을 평가한다.
- 기준: https://document.pleos.ai/docs/connect/guide/docs-design/foundations/contents-creation/iconography

### 18. 레이아웃 - 부분

- 목표: 여러 창 크기·주야간 대응
- 현재 수준/차이: 최종 기본 화면·야간 검사 통과. 큰 글자·좁은 창·우핸들 전체 행렬은 이전판 이력이며 이번 APK로 전량 재검증하지 않았다.
- 2차 개발 방향: 최종 release로 창 크기·font1.3·주야간·LHD/RHD 교차 행렬을 반복 검증한다.
- 기준: https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout

### 19. Spinner - 해당 없음

- 목표: 스피너 사용 시 공식 규칙
- 현재 수준/차이: 선택용 Spinner를 사용하지 않는다. 진행 표시와는 다른 컴포넌트다.
- 2차 개발 방향: 도입할 때 규칙을 적용한다. 준수 항목을 늘리려고 기능을 추가하지 않는다.
- 기준: https://document.pleos.ai/docs/connect/guide/docs-design/components/spinner

### 20. 선택 메뉴 - 충족

- 목표: 선택·미선택·비활성 명확
- 현재 수준/차이: 모드 녹색/회색·선택 상태와 저장 중 비활성화 회귀 통과.
- 2차 개발 방향: 반복 입력·접근성 포커스·지연 결과 조건을 확장한다.
- 기준: https://document.pleos.ai/docs/connect/guide/docs-design/components/segmented-menu

### 21. 팝업 - 충족

- 목표: 명확한 제목·동작·IME 대응
- 현재 수준/차이: 메뉴·IME·투영·주행 전환 시 창 닫힘 회귀 통과.
- 2차 개발 방향: 실차 입력기·권한창 경합·대형 글자·연속 뒤로 가기를 추가한다.
- 기준: https://document.pleos.ai/docs/connect/guide/docs-design/components/popups/system-popup

### 22. Toast·안내 - 충족

- 목표: 명확한 상태·반복 알림 억제
- 현재 수준/차이: 필수 상태는 지속 문구로 표시. 성공·대기·권한·오류를 구분한다.
- 2차 개발 방향: 실차 오류 폭주와 복귀 때 안내 중복·가림을 검사한다.
- 기준: https://document.pleos.ai/docs/connect/guide/docs-design/components/popups/toast-popup

### 23. 앱 태그 - 부분

- 목표: 공식 영문 앱명 태그
- 현재 수준/차이: IEUM 태그·버전 일치 빌드 검사 통과. 실제 마켓 태그 등록은 안 됐다.
- 2차 개발 방향: 제출 메타데이터를 유지하고 App Market 등록 시 승인 결과를 반영한다.
- 기준: https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-tagging-guide

### 24. 업데이트 - 미충족

- 목표: 최신 확인·HTTPS·마켓 이동
- 현재 수준/차이: 업데이트 검사·URL 검증 코드는 있으나 운영 HTTPS 메타데이터 주소와 마켓 배포가 없다.
- 2차 개발 방향: 발행 서버·마켓 URL을 마련하고 같은 서명/더 높은 코드로 실제 갱신 E2E를 한다.
- 기준: https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-update-guide

### 25. 버전 관리 - 충족

- 목표: 버전명·코드·동일 서명 관리
- 현재 수준/차이: 차량1.4.0/code15와 메타데이터 일치. IEUM 전용 서명 생성·검증.
- 2차 개발 방향: 키를 비공개 보관하고 2차 배포는 같은 키와 code16 이상을 사용한다.
- 기준: https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-version-management-policy

### 26. 앱 보안 - 부분

- 목표: TLS·입력·서명·디버깅 차단
- 현재 수준/차이: release 서명·manifest 격리·모델 출처 핀 검증. 네이티브20개 RELRO/NX/16KB 정렬 통과. 운영 전체 보안 검증은 미완료.
- 2차 개발 방향: 운영 서비스 TLS·권한·공격 입력·의존성 취약점 검토와 실차 보안 검증을 완료한다.
- 기준: https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide

## 2차 개발 우선순위와 완료 기준

1. **입력 250ms·초기 CPU**: 메인 스레드, SDK 초기화, 모델 파싱, 엔진 생성, 접근성 게시를 시간 구간별로 추적한다. 중복 초기화와 숨은 렌더링을 줄이고 GPU/메모리 한도는 유지한다. 같은 접근성 게이트를 포함한 반복 입력에서250ms 초과0건, 정차≤25%·주행<5%를 다시 검사한다. 1000회·P99≤200ms는 내부 여유 목표이며 공식 규약 자체는 아니다.
2. **운영 배포·보안**: 운영 HTTPS/업데이트 메타데이터·마켓 URL·동일 키 업그레이드를 구축한다. 잘못된 인증서/응답·다운그레이드·권한 철회·연결 만료가 안전하게 실패하는지 검증한다. APK 실행 성공을 마켓 승인으로 취급하지 않는다.
3. **시트/제로탭 실차**: 충전/NFC/Bluetooth 등 실제 감지 신호와 승인 기기를 연결한다. P단 인터록, 재전송 방지, 소유권을 유지하고 위치/각도 피드백으로 적용 전후와 실제 이동 완료를 확인한다. 값0이나 명령ACK만으로 실제 이동을 주장하지 않는다.
4. **워치/최근 운동 통신**: 폰·워치 시제품은 가능성 검증용 범위로 유지한다. 물리 Bluetooth 전송, 재연결,60초 만료, Health Connect 동기화·강제종료·권한 철회를 검증한다. 제출 차량 앱의 수신/출처/시각/상태 구분을 우선한다.
5. **음악·추천 정합성·프로필**: VLC 정상 재생/음질부터 확인한 뒤 YouTube Music/Melon 계정·재생목록을 순차 검증한다. 응답이 없으면 제한 시간 뒤 공조는 계속 진행한다. 개인별 만족도·추천 취소율·수동 수정률을 장기 관찰하고 가족/차량 간 공유를 Pleos ID 자동 동기화와 구분한다.
6. **실차 UI·호환성**: 모든 지원 창/폰트/주야간/LHD-RHD를 최종release로 다시 검사한다. 실제 패널에서 중요5mm/보조3mm·시거리·아이콘 인지·다색 이미지 대비를 실측한다. API28 최소 환경과 OEM별 SDK 기능표를 확보한다.

## 남겨둔 한계와 실패 이력

별도 릴리스 환경의 첫 시도는 콘솔 준비 전 HTTP503, 두 번째는 앱 미설치 상태를 오류로 처리한 검사 도구 문제로 설치 전에 중단됐습니다. 세 번째는 시작 직후 접근성 덤프 미생성으로 화면 관측이 중단됐습니다. 네 번째는 설치·P단·3D와CPU계측까지 확인했으나 신규 AVD의 기본 HVAC DDS Disabled 상태에서 공조 readback이 시간 초과됐습니다. 공식 콘솔의 Enabled(HVAC_BASE) 설정을 적용해 다섯 번째 검증을 진행했습니다. 시험 실패는 보존했으며 APK 자체를 바꾼 것은 아닙니다. 필수 콘솔 설정은 README에 반영했습니다.

워치 HTTPS 심박/최근24시간 운동과 폰 화면 꺼짐 자동전달은 이전 사용자 참여 실기기 검증 이력입니다. 이번에는 폰USB가 분리돼 있어 같은 실기기 시험을 반복하지 않았습니다. 실물 Bluetooth·실제 시트 이동·상용 음악 정상 재생·실차 GPS·운영 마켓 등록은 완료로 표시하지 않았습니다.

## 서명 release에서 따로 확인한 수준

- 신규 설치 후 P단·3D 준비를 처음 관측한 상한: 15.62초. 접근성 덤프/폴링 비용이 포함된 관측 상한이며 실제 첫 프레임 시각이나 250ms 입력 반응 값이 아닙니다.
- release CPU 프로세스 시작부터 57개 완전한1초 구간: 최고 72.27% (논리CPU1개 기준), 2CPU 용량 참고 36.13%. trace 오류/손실·미완성 슬라이스0 확인. 25% 기준을 만족하지 못했습니다.
- 동작 후 단일 메모리 관측: PSS 112.07MB, RSS 187.04MB. HWM/콜드 최대값과 구분하며 전체 메모리 인증이 아닙니다.
- 기본 실내에서는 현재 공조 수치·바람 경로/색상 변경은 확인했습니다. 시트 전체 표면은 연구용 전체 차량처럼 빨강/파랑으로 바뀌지 않아, 기본 모델의 명시적 시트/핸들 의미 태그와 표면 틴트 연결은 2차 UI 과제로 남깁니다.
