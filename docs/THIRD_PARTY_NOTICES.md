# 제3자 소프트웨어와 모델

배포 모델·환경 텍스처의 출처와 SHA-256은 `config/release-model-assets.json`에 고정했습니다.
기본 실내는 CC0 기록이 있는 일반 실내 모델이며 자체 송풍·열선 효과를 포함합니다.
Filament 환경·유틸리티는 Apache-2.0 기록을 포함합니다.
원문은 `app/src/main/assets/cabin-LICENSE.txt`, `filament-LICENSE.txt`와
`app/src/main/java/com/google/android/filament/utils/LICENSE`를 참조하세요.

주요 의존성은 Pleos Vehicle SDK 2.3.2, Filament 1.75.0, ZXing 3.5.3,
SQLCipher Android 4.20.0, AndroidX, Kotlin Coroutines 1.9.0입니다.
각 의존성의 원래 라이선스·이용 조건이 적용됩니다. Pleos SDK는 지정 Maven 저장소에서 받습니다.
SDK 바이너리나 제조사 연구용 전체 차량 모델을 별도 재배포하지 않습니다.

개발 에뮬레이터에만 설치된 IONIQ 5/KONA/NEXO 연구용 모델, 원본 쇼룸 자료,
VLC와 상용 음악 앱의 APK·음원은 이 제출물에 포함하지 않았습니다.
