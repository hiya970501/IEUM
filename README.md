# IEUM · 이음

워치의 생체·운동 정보와 운전자 선호를 바탕으로 공조·시트·핸들 열선을 맞추는 Pleos Connect 차량 개인화 앱입니다.

## 실행 방법

1. Pleos Connect 에뮬레이터를 Cold boot로 실행합니다.
2. Vehicle Virtual Console에서 기어를 **P**, 속도를 **0 km/h**, HVAC를 Enabled (`subscribe_hvac_base`)로 설정하고 콘솔 세션을 유지합니다.
3. 저장소를 내려받은 뒤 루트 폴더에서 APK를 설치하고 실행합니다.

```powershell
adb devices
adb -s emulator-5554 shell am get-current-user
adb -s emulator-5554 install --user 10 -r apk/IEUM_IEUM_release.apk
adb -s emulator-5554 shell am start --user 10 -n com.example.version1/.MainActivity
```

예시의 기기 번호(`emulator-5554`)와 차량 사용자 번호(`10`)는 실제 조회 결과에 맞게 바꿉니다. 설치 후 앱 목록의 **IEUM** 아이콘으로도 실행할 수 있습니다.

## 테스트 환경 및 필요한 설정

- **환경:** Windows, Android 14(API 34) / x86_64, Pleos Vehicle SDK 2.3.2.
- **차량 제어:** P단·정차 상태, HVAC 활성화, Vehicle Virtual Console 세션 및 차량 SDK 연결.
- **워치 데이터:** 전송 기기와 차량의 연결 승인 및 건강 데이터 전달 동의. Health Connect를 사용하는 휴대폰은 운동 기록 읽기 권한 허용.
- **날씨:** 에뮬레이터 위치 설정, 앱 위치 권한 및 인터넷 연결.
- **음악:** 호환 미디어 앱 설치와 해당 서비스의 계정·음원 준비.

## Pleos Connect 에뮬레이터 버전과 화면 규격

- **버전:** Pleos Connect Emulator 2.1.0
- **전체 디스플레이:** 2560 × 1440 px, 160 dpi, 가로 화면
- 앱은 차량 시스템 영역을 제외한 앱 창에 표시됩니다.

## APK 파일 위치

- [apk/IEUM_IEUM_release.apk](apk/IEUM_IEUM_release.apk)
- [GitHub Release: IEUM 1.4.0](https://github.com/hiya970501/IEUM/releases/tag/v1.4.0)
