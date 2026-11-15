package com.example.version1

/** Public loopback help only. Legacy URL-secret onboarding is deliberately retired. */
internal object PairingPage {
    val html = """
<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><title>IEUM 연결 안내</title>
<style>body{font:20px/1.6 system-ui,sans-serif;max-width:640px;margin:32px auto;padding:24px;color:#19313c;background:#fff}h1{font-size:30px}</style>
</head><body><h1>휴대폰 건강 기록 연결</h1>
<p>차량 앱의 워치 연결에서 일회용 연결 코드를 발급하세요.</p>
<p>인증된 HTTPS 중계 경로에서 연결 코드를 교환해야 합니다. 인증 정보는 URL에 넣지 않습니다.</p>
<p>이 페이지는 Apple Health 또는 Health Connect 기록을 읽거나 전송하지 않습니다.</p>
<p>실제 건강 기록 수신 여부는 차량 앱의 출처와 수신 시각에서 확인하세요.</p>
</body></html>
    """.trimIndent()
}
