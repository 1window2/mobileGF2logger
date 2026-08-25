# mobileGF2logger — 소녀전선 2(GF2) 서클 관리 도구

[![Android](https://github.com/1window2/mobileGF2logger/actions/workflows/android.yml/badge.svg)](https://github.com/1window2/mobileGF2logger/actions/workflows/android.yml)
[![CodeQL](https://github.com/1window2/mobileGF2logger/actions/workflows/codeql.yml/badge.svg)](https://github.com/1window2/mobileGF2logger/actions/workflows/codeql.yml)
[![Dependabot](https://img.shields.io/github/issues-pr/1window2/mobileGF2logger/dependencies?label=Dependabot&logo=dependabot)](https://github.com/1window2/mobileGF2logger/pulls?q=is%3Apr+is%3Aopen+author%3Aapp%2Fdependabot)

이 프로젝트는 [GIRLS' FRONTLINE 2: EXILIUM](https://gf2exilium.sunborngame.com/) (소녀전선 2, GF2) Android 클라이언트용 **서클(Platoon) 관리 및 기록 도구**입니다. Android만 지원하며 iOS 버전은 출시할 계획이 없습니다.

mobileGF2logger는 서클장을 위한 가벼운 비루팅 앱입니다. Android의 앱별 VPN 권한을 사용하여 지원되는 평문 서버 응답을 휴대전화 안에서 파싱하며, 원본 트래픽은 저장하지 않습니다.

## 기능

- 컴퓨터나 루트 권한 없이 필수 서클 프로필(`21905`), 멤버(`21917`), 활동(`21935`), 동향(`21960`) 응답을 캡처합니다.
- 지원 Android 클라이언트, 선택한 서버 지역, 서클 ID를 기준으로 감지한
  서클의 데이터를 자동 분리하며, 홈·서클·주간·설정 화면에서 활성
  서클을 전환할 수 있습니다.
- 가입 및 탈퇴 멤버, 서로 겹치지 않는 반복 가입 이력, 동향의 정확한 시각, 수정 가능한 닉네임과 개인 비고를 관리합니다.
- 선택한 서버의 일일 초기화 시각을 기준으로 일요일부터 토요일까지의 일반 주간 또는 흙먼지 주간 표를 만들며, 커트라인과 누락 데이터 수동 수정을 지원합니다.
- 한 번만 캡처는 네 가지 유용한 서클 페이로드의 수집 상태를 표시하고 체크리스트가 완료되면 자동으로 중지합니다.
- 모든 주간 셀을 누르면 근거를 설명하고, 근거 상태 패널에서 누락되거나 불확실한 데이터를 요약합니다.
- 주간 표마다 표시 당시의 멤버 문맥까지 포함한 전체 자동 기록을 최대 15개 보관하여 잘못 가져온 뒤에도 이전 표를 미리 보고 복원할 수 있습니다.
- 중단된 가져오기를 먼저 복구한 뒤 명단 CSV의 영향을 미리 보여 주고, 실행 취소용 1단계 자동 체크포인트를 보관합니다.
- 명단 CSV는 사용자가 명시적으로 선택한 서클이나 클라이언트·서버·이름·
  ID를 직접 입력한 신규 서클에만 가져옵니다. `21917`에는 서클 식별
  정보가 없으므로 미리보기에는 항상 적용 대상을 표시하고 확인을
  요청합니다.
- 이름, UID, 비공개 메모의 포함 여부를 선택해 주간 PNG를 저장하거나 공유할 수 있습니다.
- 확인 후 검증된 원본 CSV를 사용자가 소유한 선택적 Discord 수신 웹훅으로 전송할 수 있습니다.
- 최근 파싱 패킷 100개와 저장 패킷 50개를 보관하며, 표 및 원본 보기, 복사, 내보내기, 선택, 삭제를 지원합니다.
- 멤버 정렬, 드래그 순서 유지, 최근 스냅샷 비교, 주간 CSV 내보내기, 다른 서클을 변경하지 않는 프로필별 백업 및 복원을 지원합니다.
- 새 서클은 확인된 클라이언트에 맞는 서버를 사용자가 선택할 때까지 패킷을 제한된 메모리에만 보관하며, 강제 종료 또는 프로세스 종료 시 확인되지 않은 데이터를 버립니다.
- 서버 정보는 데이터를 이동하지 않고 수정할 수 있으며, 서클 삭제는 두 번의 확인과 정확한 서클 이름 입력을 거쳐 해당 격리 데이터만 제거합니다.
- 첫 사용 시 메인, 설정, 서클 관리, 주간 기능, 파싱 패킷 화면을 안내하며, 한국어/English 전환과 건너뛰기를 지원합니다.
- 영어와 한국어, 시스템/라이트/다크 테마를 지원합니다. 알려진 Darkwinter/HaoPlay 6개 서버 지역의 초기화 시각을 기기 시간대로 환산하며, 감지된 서클을 선택하면 해당 프로필에 저장된 지역을 자동으로 따릅니다.
- HaoPlay(`com.haoplay.game.and.exilium`)와 Darkwinter(`com.Sunborn.SnqxExilium.Glo`) Android 클라이언트를 별도의 VPN 대상으로 등록합니다.
- 다음 열 순서의 UTF-8 서클 멤버 CSV 파일을 만듭니다.

```text
uid,name,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin,logTime
```

## 사용 방법

1. Android 8.0 이상 기기에 ARM64 APK를 설치합니다.
2. **GF2logger**를 열고 필요한 지원 클라이언트가 설치되어 있는지 확인한 뒤 **한 번만 캡처**를 선택합니다.
3. Android의 VPN 요청을 승인한 뒤 게임을 엽니다.
4. **서클(Platoon)**에 들어가 **동향(Updates)**과 **멤버(Members)**를 엽니다.
5. GF2logger로 돌아와 캡처한 패킷과 서클 데이터를 확인합니다.

Android 10 이상에서는 Android가 각 연결을 소유한 지원 게임을
식별합니다. Android 8–9에서는 지원 클라이언트가 하나만 설치된 경우에만
안전하게 관리 데이터를 분류할 수 있으며, 두 클라이언트가 모두 설치된
상태의 미식별 데이터는 가져오지 않습니다. 평문 프로토콜에는 신뢰할 수
있는 서버 식별자가 없으므로 첫 캡처 전에 설정에서 HaoPlay와 Darkwinter의
서버를 올바르게 선택하세요.

앱은 확정된 각 서클의 파싱 내역, 관리 데이터, 생성한 CSV 파일을 서로
분리된 기기 비공개 저장소에 보관합니다. TLS, 인증서 고정 또는 안티치트
체계를 우회하지 않으며 게임 트래픽을 변경하지 않습니다. 서버 응답에는
최근의 일부 이력만 포함될 수 있으므로, 누락된 과거 가입 이력은 직접
추가할 수 있습니다.

## 참고

mobileGF2logger는 Windows 클라이언트용 GF2 로거인 [blead/gfl2logger](https://github.com/blead/gfl2logger)에서 영감을 받은, Android 기기 내 서클 관리에 맞춘 독립적인 구현입니다.
