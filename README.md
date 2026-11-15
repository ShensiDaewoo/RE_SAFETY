# RE:SAFETY — Pleos-Based Adaptive Safety HMI

Pleos SDK 기반의 네이티브 기능을 통합하고, 독립 Full HMI 환경에서 전체 사용자 경험을 구현한 프로토타입.

**일상에서는 운전자와 동승자의 안전·편의를 관리하고, 사고 순간에는 흩어진 대응 기능을 하나의 흐름으로 연결하며, 사고 이후에는 기록과 사후 지원까지 이어지는 상황 적응형 차량 안전·편의 서비스.**

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 제출물. 작성 2026-11-15.

**먼저 읽기: `설명서.txt`** (APK 두 개 중 무엇을 어디에 설치하는지 한 장으로 정리)

RE:SAFETY는 APK 두 개로 제출한다.
- **② `Aurora Dawn_RE-SAFETY_release.apk`가 메인 앱이다.**
  - Pleos Connect에서는 오른쪽 앱 영역에 RE:SAFETY 패널로 뜨고, 실제 Pleos SDK와 차량 신호를 쓴다(시연 영상 1부).
  - 일반 Android에서는 Aurora 디자인이 화면 전체에 뜬다(시연 영상 2부).
- **① `Aurora Dawn_RE-SAFETY-SDKProof_release.apk`는 Pleos SDK 실증본이다.**
  - Pleos 전용이다.
  - SDK 호출을 Virtual Console과 대조한 시험 근거용이다.
- 두 결과물의 검증 성과는 서로 섞지 않는다.

| | ① Pleos SDK 실증본 | ② 메인 앱 (Aurora Full HMI) |
|---|---|---|
| APK (`apk/`) | `Aurora Dawn_RE-SAFETY-SDKProof_release.apk` | `Aurora Dawn_RE-SAFETY_release.apk` |
| 무엇을 증명하나 | Pleos 차량에서 실제로 동작하는 사고 대응 기능 (SDK 시험 근거) | 상황에 따라 화면이 바뀌는 차량 HMI 경험과, 같은 기능의 Pleos 실행 |
| 실행 환경 | **Pleos Connect 에뮬레이터** v2.1.0 전용 (일반 Android에서는 열리지 않음) | **Pleos Connect** (앱 영역 패널) · **일반 Android 가로 전체 화면** 2560 × 1440 |
| 앱 목록에 보이는 이름 | RE:SAFETY | RE:SAFETY Full HMI … |
| 소스 | `pleos-sdk-proof-r2d/` | `full-ux-demo/` |
| 버전 | 0.8.0-r2d-dev (versionCode 24) | 0.6.4-adaptive10 (versionCode 59) |
| SHA-256 | `DCBAF29B0BF705BC1F3D0450A8B16C225B151E03B265088093A2E75AA8FC668D` | `8BC11E8A052FFBB4E69C90D54D703643CAB157E82DB93FC95D7D4DB74B4BB24D` |

② 0.6.4-adaptive10은 2026-11-15 최종 시험 중 이전 후보(0.5.0-adaptive4, `5DE8E8D0…`)에서 찾은 결함을 고친 빌드다. 고친 결함은 Pleos에서 좌석 착석이 반대로 표시되던 것이다. 그 밖에 이 빌드에 들어간 것은 다음과 같다.
- 사고 대응 화면 앞의 WARNING 화면
- SpeechToText 음성 입력
- Gleo Interface 3개 기능
- 출발 전 차량 값

**시연 영상:** `docs/demo/RE-SAFETY_demo_20261115.mp4` (1분 40초, 소리 없음). 화면 녹화를 약 1.27배속으로 이었다.
- 1부: Pleos 에뮬레이터에서 실제 SDK 동작. 출발 전 확인(차량 값) → 좌석·안전벨트 → 좌석 제어 → 사고 기록 → 주행 시 Pleos 내비 → 주행 중 사고 신호 뒤 정차하면 WARNING → 보호 모드 → 사고 상황 정리 → 보험·견인 → SOS(화면만, 전송 없음) → 119 전화 화면.
- 2부: 일반 Android의 Full HMI 전체 화면. 좌석 제어 → 주행 → 사고 신호 → 정차 확인 → WARNING → 보호 모드 → 사고 상황 정리(QR까지) → 보험·견인 → SOS → READY.
- 두 부분 모두 이 표의 ② APK(0.6.4-adaptive10)로 찍었다. 사고 신호는 시뮬레이션이고, 전화는 걸지 않았다.
**UX 디자인:** `docs/ux-design/` — Aurora READY·ACCIDENT 실제 화면, 차량 화면 전체에 넣은 OEM 통합 목표 콘셉트(합성), 기획서 문구.
**최종 시험 근거:** `verification/final_20261115/EVIDENCE.md`. 위 표의 APK 두 개를 2026-11-15에 그대로 설치해 다시 시험한 기록이다. 기획서의 근거 번호(P1-01~12, P2-01~13, G2-01~03)가 여기 있다.
- Pleos Virtual Console에서 좌석·와이퍼 값을 바꿔 앱이 따라오는지 대조했다.
- 보호 모드를 콘솔 값과 대조했다.
- 속도 권한이 없을 때 제어가 막히는지 확인했다.
- 위치 좌표가 일치하는지, 주소는 두 위치가 300 m 안일 때만 붙는지 확인했다.
- 119·112는 발신 0건이었다.
- QR은 40회 중 40회 읽혔다.
- 앱을 강제 종료한 뒤에도 기록이 복원됐다.
- Pleos 내비로 화면을 넘기는 것과 음성 출력을 확인했다.
- 정차 뒤 WARNING이 약 4초 보인 뒤 사고 대응 화면으로 넘어갔다.
- ② 좌석 착석이 콘솔과 일치했다(이전 빌드의 반전 결함을 고침).
- 음성 입력은 12글자가 들어갔다.
- Gleo 3개 기능이 각각 화면을 열었다.
- 세 장면 전환 8/8, 기능 점검 16/16.

화면 캡처, 로그, 스크립트 원본이 같이 있다.

팀명은 **Aurora : Dawn**이다. 파일 이름에는 콜론을 쓸 수 없어 `Aurora Dawn`로 적었다(공지 규칙 `팀명_앱이름_release.apk`). 두 APK 모두 release 빌드이고 디버그 키로 서명해 바로 설치된다. 해시 목록은 `apk/SHA256SUMS.txt`.

## 1. 목표 HMI: 화면의 주인은 차량 상황이 정한다

| 장면 | 조건 | 화면 |
|---|---|---|
| A · READY | 정차 (P · 0 km/h) | **RE:SAFETY 푸른 Aurora** — 왼쪽 오로라와 흰 SUV, 오른쪽 출발 전 확인·좌석·안전벨트 확인·좌석 제어·사고 기록 |
| B · DRIVING | 주행 중 | **Pleos 기본 Driving View와 Pleos 내비게이션** |
| 사고 대기 | 주행 중 + 사고 신호 | 주행 화면은 그대로, 음성 안내만 |
| C · ACCIDENT | 사고 신호 + 안전한 정차 | **RE:SAFETY 붉은 Aurora** — 왼쪽 사고 현장, 오른쪽 119·SOS·보호 모드·사고 상황 정리·좌석·안전벨트·보험·견인·기록 |

실제 차량 화면에서 A와 C가 Pleos 시스템 영역(Driving View 자리)까지 차지하려면 제조사의 시스템 HMI 통합이 필요하다. Pleos 공식 문서는 Driving View를 "앱이 임의로 가리거나 종료할 수 없다"고 정한다(https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model). 그래서 이 목표는 ②의 독립 전체 화면에서 **OEM 통합 목표 콘셉트**로 보여 주고, 실제 차량 화면 적용은 후속 협력 목표로 둔다. Pleos 시스템 앱을 수정하거나 우회하지 않았다.

그림: `docs/scenes/`
- 독립 전체 화면 시연(②의 실제 실행 화면): `A_READY_full_ux_demo_standalone.png`, `B_DRIVING_placeholder_full_ux_demo_standalone.png`, `P_ACCIDENT_PENDING_full_ux_demo_standalone.png`, `C_ACCIDENT_full_ux_demo_standalone.png`
- 실제 Pleos 주행 화면(B): `B_DRIVING_real_pleos_emulator.png`, `P_ACCIDENT_PENDING_real_pleos_emulator.png`
- OEM 통합 목표 콘셉트(합성 그림, 실행 화면 아님): `A_READY_OEM_target_concept_composite.png`, `C_ACCIDENT_OEM_target_concept_composite.png`

## 2. 사고 대응 시나리오

1. 주행 중 사고 신호가 오면 화면을 바꾸지 않고 음성으로만 알린다.
2. 정차가 확인되면 사고 대응 화면이 열린다.
3. **119 전화 연결** — 전화 화면에 119를 넣어 연다. 통화는 사람이 건다.
4. **보호 모드** — 미등·실내등을 켜고 앞뒤 문 잠금을 푼다. 항목마다 차량 값을 읽고, 요청하고, 다시 읽어 확인한다. 되돌리기는 이 앱이 바꾼 것만 처음 값으로 돌린다.
5. **사고 상황 정리** — 행동 확인, 부상 입력, 차량 상태, 공유할 내용 선택, QR, 완료의 6단계. 자동 전송은 없다.
6. **좌석·안전벨트** — 차량이 보고한 값과 사람이 직접 확인한 값을 나란히 적는다.
7. **SOS · 보험·견인 · 사고 기록** — 기기 안에만 저장한다.

## 3. ① Pleos SDK 실증본 (R2D)

Kotlin 네이티브 화면의 Pleos 앱. 공식 Pleos 앱 실행 정책과 권한 안에서 동작한다. 소스와 자체 설명은 `pleos-sdk-proof-r2d/` (제출 후보 ZIP을 수정 없이 푼 것; 그 안의 README가 말하는 `apk/` 파일은 이 저장소의 `apk/Aurora Dawn_RE-SAFETY-SDKProof_release.apk`다).

- **실행 환경:** Pleos Connect 에뮬레이터, 앱 창 1682 × 1210.
- **실제 SDK로 검증한 기능** (Pleos 에뮬레이터, `verification/pleos_sdk_proof_r2d/run_20261114_230144/`):
  - 보호 모드 켜기·되돌리기 — Pleos Virtual Console의 차량 상태와 일치. 문 잠김 4 → 풀림 4 → 잠김 4, 실내등 꺼짐 → 켜짐 → 꺼짐, 전조등 Off → Position → Off. Pleos 시스템 Driving View에도 문 열림이 표시됨.
  - 정차 확인(기어 P · 0 km/h) — 신호가 없거나 권한이 없으면 보호 모드 버튼을 잠금 (같은 폴더의 `run_…_GATE_BLOCKED_…` 2건).
  - 차량 위치(FusedLocation 1.1.3), 주소(NaviHelper 3.0.5), 좌석·벨트·문 상태(Vehicle 2.3.2).
  - 그 밖의 시험(QR 판독, 119 전화 화면·발신 0건 등)은 `pleos-sdk-proof-r2d/README.md`.
- **시뮬레이션:** 없음(이 APK의 제출 변형에는 사고 시뮬레이션과 SOS 시험 서버가 들어 있지 않다).
- **OEM 통합 필요:** 실제 충돌 신호 수신, 주행 중 긴급 화면.

## 4. ② 전체 UX 시연본 (Aurora Full HMI)

Aurora 원본 디자인(WebView)으로 A·B·C 장면 전환을 보여 주는 독립 전체 화면 프로토타입. R2D에서 검증된 로직(정차 확인, 보호 모드 실행 순서, 좌석·벨트·위치·주소 읽기)과 Pleos SDK 연결 코드를 함께 담고 있다.

- **실행 환경:** 일반 Android 가로 화면 2560 × 1440 (Android 14 에뮬레이터에서 시험). 차량이 없는 환경이므로 차량 값은 시뮬레이션이다.
- **WARNING 화면:** 사고 대응 화면이 열리기 직전에 "WARNING!"이 1초 간격으로 깜빡이고, "충돌 신호가 감지되었습니다. 사고 대응 모드를 작동합니다."를 4초 보여 준다(누르면 바로 닫힘). 모든 사고 신호가 시뮬레이션이라 "가상 신호 · 시뮬레이션" 표시를 단다. 사고 종류(사람·차량)는 공개 SDK와 가상 신호 모두 알려 주지 않아 적지 않는다. Pleos에서는 주행 중 사고 신호를 받으면 이 화면을 미리 올려 두고, 정차해 사고 대응 화면이 열릴 때 4초를 센다.
- **현재 제출 빌드(adaptive10)의 시험:** `verification/final_20261115/EVIDENCE.md`의 P2-01~13, G2-01~03. 근처 SOS는 시험 서버로 보내기(G2-02)와 Pleos 앱의 받기·확인(P2-13)을 시험했다.
- **이전 빌드(adaptive4)에서 검증한 것** (`verification/full_ux_demo_standalone/`):
  - 세 장면 여덟 단계 전환 8/8 — A → B → A → B → 사고 대기 → C → A, A → C → A (`three_scenes_8_steps/`)
  - 스모크 16/16 — 화면 이동, 119 전화 화면·발신 0건, 가상 사고 이벤트(의심·확정·중복), SOS 시험 서버 1회, 앱 재시작 뒤 이어하기, QR 3종 판독 (`smoke_16_checks/`)
  - 디자인 보존 — Aurora 화면 20개를 디자인 기준본(aurora12)과 같은 조건으로 비교, 시계 숫자·기록 시각·깜빡이는 경광등 자리만 다름 (`visual_compare_aurora12_vs_adaptive4/`)
- **시뮬레이션:** 주행 상태와 차량 값, 사고 신호, B 장면(실제 Pleos 화면 대신 "자리 표시 · Pleos 시스템 화면 아님"이라고 적은 화면), 좌석 제어, 왼쪽 차량 장면, SOS(개발 PC의 시험 서버).
- **OEM 통합 필요:** A·C를 실제 차량 화면의 Driving View 자리까지 표시, 주차 뒤 Aurora로 자동 복귀, 실제 충돌 신호, 주행 중 긴급 알림.
- **이 APK를 Pleos 에뮬레이터에 설치한 경우 (참고):** 앱 창 안에 RE:SAFETY 패널을 표시하고 Pleos SDK를 실제로 호출한다. 확인한 것은 다음과 같다(`verification/full_ux_apk_on_pleos_sdk_calls/`). 이 배치는 ②의 목표 화면이 아니며 Pleos SDK 실증의 주 결과물은 ①이다.
  - 보호 모드가 Pleos Virtual Console의 차량 상태와 일치했다.
  - 주행이 시작되면 Pleos 내비로 화면을 넘겼다. 쓴 API는 `NaviHelper.requestNavigationScreen`이다.
  - Pleos TextToSpeech로 결과를 읽었다. 시작·끝 이벤트와 오디오 믹서 출력 기록까지 확인했고, 사람이 들은 확인은 아니다.
  - 119는 Pleos 전화 화면이 열리는 데까지 확인했고, 통화 기록은 0건이었다.
  - 앱 창은 Driving View·상단 바·하단 바와 겹치지 않았다.

  30초 이상 주행한 뒤에는 주차해도 앱이 스스로 돌아오지 않았다(`long_drive_no_automatic_return/`).

장면 결정 코드: `full-ux-demo/signal-probe-fullhmi/app/src/main/assets/index.html`의 `motion()`, `sceneFor()`, `sceneSync()`, `renderDrive()`. 구조와 전환 규칙: `docs/ADAPTIVE_HMI_ARCHITECTURE.md`. R2D 코드가 어디로 옮겨졌는지(파일·함수 단위): `docs/history/AURORA17_PLEOS_INTEGRATION_REPORT.md` B항.

## 5. Pleos SDK 사용 내역

| SDK · 버전 | ① 실증본 (R2D) | ② 시연본 코드 | 포털 신청 상태 (공식) |
|---|---|---|---|
| Vehicle 2.3.2 상태 조회 | 좌석·벨트·문·조명·와이퍼·하향등·배터리·주행거리 | 좌석·벨트·문·조명·와이퍼·하향등·배터리·주행거리 | 신청 없이 사용 가능 |
| Vehicle 2.3.2 제어 | 보호 모드 | 보호 모드 | 준비 중 |
| FusedLocation 1.1.3 | 차량 위치 | 차량 위치 | 준비 중 |
| NaviHelper 3.0.5 | 주소 | 주소, 내비 화면 요청 | 신청 없이 사용 가능 |
| TextToSpeech | 안드로이드 기본 TTS(Pleos 엔진) | Pleos TextToSpeech SDK 3.3.1 | 준비 중 |
| SpeechToText 3.3.1 | 쓰지 않음 | 메모·견인 위치 음성 입력 (시험 음성으로 확인, 차량 마이크 경로는 미시험) | — |
| Gleo Interface (App Schema) | 쓰지 않음 | 3개 기능: 사고 대응 화면·사고 상황 정리·119 전화 화면 열기 (같은 방식으로 흉내 내 시험, Gleo 직접 호출은 Pleos 검토·승인 뒤) | 공모전 공지: 별도 승인 없이 사용 가능 |

신청 상태 출처: https://document.pleos.ai/docs/pleos-only/create-my-project — 에뮬레이터에서 동작한다는 것은 배포 승인을 뜻하지 않는다.

## 6. 실행 방법

**Pleos Connect 에뮬레이터 (② 메인 앱과 ① 실증본 둘 다)**
```
adb install --user 10 -r "apk/Aurora Dawn_RE-SAFETY_release.apk"
adb install --user 10 -r "apk/Aurora Dawn_RE-SAFETY-SDKProof_release.apk"
```
앱 라이브러리 → 앱에서 "RE:SAFETY Full HMI"(②)나 "RE:SAFETY"(①)를 연다. 첫 실행 때 속도·위치 권한을 허용한다. Pleos Virtual Console 페이지가 열려 있어야 차량 신호가 흐른다.

**일반 Android 가로 화면 (② 메인 앱만, 2560 × 1440 권장)**
```
adb install -r "apk/Aurora Dawn_RE-SAFETY_release.apk"
```
Aurora 전체 화면으로 뜬다. 왼쪽 "주행 시작"을 누르면 B 장면, 그 화면의 "사고 이벤트"를 누르면 사고 대기, "정차 확인"을 누르면 C 장면이 된다. 정차 중 사고 장면은 화면 위 "시뮬레이션" 칩으로 연다.

**빌드** — ①은 `pleos-sdk-proof-r2d/README.md`. ②는 `full-ux-demo/signal-probe-fullhmi`에서 JDK 17 이상으로 `gradlew assembleRelease`(경로에 한글이 있으면 `-Pandroid.overridePathCheck=true`). `assets/index.html`은 `full-ux-demo/tools/`의 스크립트로 만든 생성물이며 저장소에 들어 있다. 만드는 순서는 `patch_aurora_step1` → … → `patch_adaptive_scenes` → `patch_adaptive6_more` → `patch_adaptive7_warning` → `patch_adaptive8_seatfix_warn` → `patch_adaptive9_warn_visible` → `patch_adaptive10_warn_pending`이다.

## 7. 시험 환경과 화면 규격

| | Pleos Connect 에뮬레이터 | 일반 Android 에뮬레이터 |
|---|---|---|
| Android Emulator | 37.2.13.0 | 37.2.13.0 |
| 시스템 이미지 | `android-34 / pleos_car_emulator / x86_64` (release_connect_v2.1.0, Android 14) | `sdk_phone64_x86_64` Android 14 (API 34) |
| 화면 | 2560 × 1440, 160 dpi | 2560 × 1440 |
| 앱이 받는 창 | 1682 × 1210 (x 853–2535, y 90–1300) | 전체 화면 |

## 8. 권한과 주행 제한

- **주행 중에는 Pleos가 앱을 가린다.** 주의 분산 최적화를 선언하지 않은 앱은 주행 중 사용할 수 없다는 공식 정책 그대로다(https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy). 에뮬레이터에서는 기어가 P가 아니면 0 km/h여도 가려졌다. 그래서 두 결과물 모두 **정차(P) 후에 쓰는 앱**이고, 사고 직후 P가 아닌 상태에서는 119 버튼에도 닿을 수 없다. 우회하지 않았고 OEM 통합 과제로 둔다.
- 전화 발신 권한은 두 APK 모두 없다. 119·112는 전화 화면을 여는 데까지다.
- ①의 권한은 `pleos-sdk-proof-r2d/app/src/main/AndroidManifest.xml`. ②는 `full-ux-demo/signal-probe-fullhmi/app/src/main/AndroidManifest.xml`(17개). SDK 연결 코드용 권한과, 개발 PC 시험 서버로 주변 SOS를 실험하기 위한 INTERNET이 들어 있다.

## 9. OEM 통합 목표 (후속 협력 과제)

- A·C 장면을 실제 차량 화면에 표시 (Pleos 시스템 HMI 통합 권한 필요)
- 주행 중 → 정차 시 Aurora로 자동 복귀, 다른 앱이 앞에 있을 때 사고 화면 표시
- 실제 충돌 신호 수신, 주행 중 허용된 긴급 알림
- 경로 안내
- Pleos ID · Fleet · 클라우드 연동 — 앱이 쓸 수 있는 공개 인터페이스를 확인하지 못했다

"OEM 통합 완료"가 아니라 "OEM 통합 목표"다. 위 항목 중 구현을 마친 것은 없다.

**공개 SDK 범위 안에서 이번 제출 빌드에 넣은 것:** SpeechToText 음성 입력, Gleo Interface(App Schema) 3개 기능, 출발 전 차량 값 표시. ② 0.6.4-adaptive10에 들어 있고 Pleos 에뮬레이터에서 시험했다(P2-10~12). 차량 마이크 경로와 Gleo의 실제 음성 호출은 2차 PT 전에 확인한다. 공모전 공지는 참가팀이 Gleo Interface를 별도 승인 없이 쓸 수 있다고 안내한다(https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000008).

## 10. 저장소 구성

```
apk/                   release APK 두 개, SHA256SUMS.txt
pleos-sdk-proof-r2d/   ① 소스 (제출 후보 ZIP 그대로, 자체 README 포함)
full-ux-demo/          ② 소스 (Android 프로젝트, 페이지 생성 도구)
verification/          결과물별 시험 기록
  final_20261115/                   제출 APK 두 개의 최종 시험 (EVIDENCE.md, 근거 번호별 원본)
  pleos_sdk_proof_r2d/              ① Pleos 에뮬레이터
  full_ux_demo_standalone/          ② 독립 전체 화면
  full_ux_apk_on_pleos_sdk_calls/   ② APK를 Pleos 에뮬레이터에서 실행한 참고 기록
  scripts/                          시험 스크립트
docs/                  구조 문서, 장면 그림, 그림 자산 메모, 이전 단계 보고서
```

## 11. 그림 자산

②에 들어간 그림 7장(오로라 배경 2, 차량 장면 2, 좌석 제어 3)은 팀이 직접 만든 콘셉트 그림이고, 공개해도 된다는 것을 팀이 확인했다(2026-11-15, `docs/picture-notes/OWNER_CONFIRMATION.md`). 좌석 제어 그림 3장은 ChatGPT로 만든 그림에서 잘라 썼다. 손본 내용은 `docs/picture-notes/`의 메모에 있다. 화면의 차량 장면은 실제 차량 상태를 찍은 것이 아니라 콘셉트 표시다. ①에는 사진 자산이 없다.
