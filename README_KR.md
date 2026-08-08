# mobileGF2logger — 소녀전선 2(GF2) 서클 관리 도구

[![Android](https://github.com/1window2/mobileGF2logger/actions/workflows/android.yml/badge.svg)](https://github.com/1window2/mobileGF2logger/actions/workflows/android.yml)
[![CodeQL](https://github.com/1window2/mobileGF2logger/actions/workflows/codeql.yml/badge.svg)](https://github.com/1window2/mobileGF2logger/actions/workflows/codeql.yml)
[![Dependabot](https://img.shields.io/github/issues-pr/1window2/mobileGF2logger/dependencies?label=Dependabot&logo=dependabot)](https://github.com/1window2/mobileGF2logger/pulls?q=is%3Apr+is%3Aopen+author%3Aapp%2Fdependabot)

이 프로젝트는 [GIRLS' FRONTLINE 2: EXILIUM](https://gf2exilium.sunborngame.com/) (소녀전선 2, GF2) Android 클라이언트용 **서클(Platoon) 관리 및 기록 도구**입니다. Android만 지원하며 iOS 버전은 출시할 계획이 없습니다.

mobileGF2logger는 서클장을 위한 가벼운 비루팅 앱입니다. Android의 앱별 VPN 권한을 사용하여 지원되는 평문 서버 응답을 휴대전화 안에서 파싱하며, 원본 트래픽은 저장하지 않습니다.

## 기능

- 컴퓨터나 루트 권한 없이 필수 멤버(`21917`), 활동(`21935`), 동향(`21960`) 응답을 캡처합니다.
- 가입 및 탈퇴 멤버, 반복 가입 이력, 동향의 정확한 시각, 수정 가능한 닉네임과 개인 비고를 관리합니다.
- 05:00 게임 초기화 시각을 기준으로 일요일부터 토요일까지의 일반 주간 또는 흙먼지 주간 표를 만들며, 커트라인과 누락 데이터 수동 수정을 지원합니다.
- 최근 파싱 패킷 100개와 저장 패킷 50개를 보관하며, 표 및 원본 보기, 복사, 내보내기, 선택, 삭제를 지원합니다.
- 멤버 정렬, 드래그 순서 유지, 최근 스냅샷 비교, 주간 CSV 내보내기, 서클 관리 데이터 백업 및 복원을 지원합니다.
- 영어와 한국어를 지원하며 화면에 표시하는 시각에는 Android 기기의 시간대를 사용합니다.
- 다음 열 순서의 UTF-8 서클 멤버 CSV 파일을 만듭니다.

```text
uid,name,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin,logTime
```

## 사용 방법

1. Android 8.0 이상 기기에 ARM64 APK를 설치합니다.
2. **GF2logger**를 열고 게임 패키지를 확인한 뒤 **서클 명단 한 번 캡처**를 선택합니다.
3. Android의 VPN 요청을 승인한 뒤 게임을 엽니다.
4. **서클(Platoon)**에 들어가 **동향(Updates)**과 **멤버(Members)**를 엽니다.
5. GF2logger로 돌아와 캡처한 패킷과 서클 데이터를 확인합니다.

앱은 파싱 내역, 관리 데이터, 생성한 CSV 파일을 기기의 비공개 저장소에 보관합니다. TLS, 인증서 고정 또는 안티치트 체계를 우회하지 않으며 게임 트래픽을 변경하지 않습니다. 서버 응답에는 최근의 일부 이력만 포함될 수 있으므로, 누락된 과거 가입 이력은 직접 추가할 수 있습니다.
