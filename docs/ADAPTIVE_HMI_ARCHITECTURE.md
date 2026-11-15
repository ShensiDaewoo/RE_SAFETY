# RE:SAFETY × Pleos — 상황 적응형 HMI (adaptive4)

작성 2026-11-15. 빌드 `apk/RE-SAFETY_AdaptiveHMI_0.5.0-adaptive4.apk`
sha256 `5DE8E8D08CA130E2711C75B7CFB96CD40633E51AA2E1E310EDB1178F1BF51C63` · 31,867,801 bytes · versionCode 53 · aurora17과 같은 서명·같은 패키지.
aurora17(`FULL_HMI_AURORA_SUBMISSION_WORK`)과 R2D 제출 후보는 건드리지 않았다. 이 폴더가 별도 작업본이다.

판정 표기: 확인됨 / 부분 확인 / 불명. 추정은 [추정].

> **2026-11-15 갱신:** 이 문서는 adaptive4 기준이다. 제출 APK는 그 뒤 0.6.4-adaptive10(`8BC11E8A…`)으로 바뀌었다.
> 장면 전환 구조(이 문서의 내용)는 같다. adaptive10에서 바뀐 것은 다음과 같다.
> - Pleos에서 좌석 착석이 반대로 나오던 결함 수정
> - 사고 대응 화면 앞의 WARNING 화면
> - SpeechToText, Gleo Interface, 출발 전 차량 값
>
> 시험 근거는 `verification/final_20261115/EVIDENCE.md`에 있다.
>
> 이 저장소에서 읽을 때: 이 문서는 작업 폴더 기준으로 쓰였다. 저장소에서는 이름이 이렇게 바뀌어 있다.
> `apk/RE-SAFETY_AdaptiveHMI_0.5.0-adaptive4.apk` → `apk/Aurora Dawn_RE-SAFETY_release.apk` (adaptive4 시점에는 같은 파일이었고, 지금은 adaptive10) · `signal-probe-fullhmi/…`, `tools/…` → `full-ux-demo/…` ·
> Pleos 에뮬레이터 실행 기록 → `verification/full_ux_apk_on_pleos_sdk_calls/` · 독립 실행 기록·화면 비교·스모크 → `verification/full_ux_demo_standalone/`.
> 제출 구조에서 이 APK의 역할은 **전체 UX 시연본**(일반 Android 전체 화면)이다. Pleos SDK 실증의 주 결과물은 R2D(`apk/Aurora Dawn_RE-SAFETY-SDKProof_release.apk`)다.

## 1. 한 문장

**READY = 우리 Aurora · DRIVING = Pleos 기본 화면 · ACCIDENT = 우리 Aurora.** 화면의 주인은 차량 상황이 정한다.

## 2. 세 장면

| 장면 | 조건 | 화면의 주인 | 그림 (`docs/scenes/`) |
|---|---|---|---|
| A READY | 정차 (P · 0 km/h), 사고 없음 | RE:SAFETY Aurora (푸른 오로라, 네 카드) | `A_READY_OEM_target_concept_composite.png` (OEM 통합 목표 콘셉트, 합성) · `A_READY_fullux_apk_in_pleos_app_window.png` (이 APK를 Pleos 앱 창에서 실행) |
| B DRIVING | 주행 중 | **Pleos 기본 Driving View + Pleos 내비게이션** | `B_DRIVING_real_pleos_emulator.png` (실제 에뮬레이터 화면) |
| 사고 대기 | 주행 중 + 사고 신호 | Pleos 주행 화면 그대로 + 음성 안내만 | `P_ACCIDENT_PENDING_real_pleos_emulator.png` |
| C ACCIDENT | 사고 신호 + 정차 (P · 0 km/h, 2초 유지) | RE:SAFETY Aurora (붉은 오로라, 사고 대응) | `C_ACCIDENT_OEM_target_concept_composite.png` (OEM 통합 목표 콘셉트, 합성) · `C_ACCIDENT_fullux_apk_in_pleos_app_window.png` (이 APK를 Pleos 앱 창에서 실행) |

"OEM 통합 목표 콘셉트" 그림은 합성이다: 가운데는 이 앱의 실제 화면을 목표 배치로 렌더링한 것(`tools/make_target_picture.py`), 위아래 바는 실제 Pleos 에뮬레이터 화면의 픽셀. 돌아가는 빌드의 캡처가 아니다.
"지금" 그림과 B·사고 대기 그림은 Pleos Connect 에뮬레이터 v2.1.0에서 adaptive4를 실행해 찍은 실제 화면이다.

## 3. 장면 결정 (Context-Aware HMI Orchestrator)

한 곳에서만 결정한다: `signal-probe-fullhmi/app/src/main/assets/index.html`

- `:1928 motion()` — 주행 여부. Pleos에서는 Vehicle 신호(기어 · 속도). 독립 실행에서는 시뮬레이션 스위치. "주행"은 즉시 믿고, "정차"는 2초 유지돼야 믿는다. 신호가 끊기면 마지막 상태를 유지한다(주행을 정차로 바꾸지 않는다).
- `:1939 sceneFor(accident, motion)` — 사고 아님+정차 → READY, 사고 아님+주행 → DRIVING, 사고+주행 → ACCIDENT_PENDING, 사고+정차 → ACCIDENT_RESPONSE.
- `:1940 sceneSync()` — 장면이 바뀌면 기록(`SCENE a -> b`)하고 `window.Native.scene`으로 알린다.
- `:1951 renderDrive()` — 독립 실행에서만 B의 자리 표시를 그린다. Pleos에서는 아무것도 그리지 않는다.

Pleos 쪽 동작: `MainActivity.kt:204 onScene()` — DRIVING이 되면 `VehicleLink.kt:233 requestNavigationForeground()`로 Pleos 내비에 화면을 넘긴다(공식 `NaviHelper.requestNavigationScreen(Foreground)`, 권한 `pleos.car.permission.NAVI_CUSTOM_MAP`). 사고 대기가 되면 Pleos TextToSpeech로 한 번 안내하고 앱은 제자리에 남는다. `MainActivity.kt:188 sceneWatch()` — 정차 감시.

전환 (그림의 화살표와 같다):
READY → DRIVING (주행 시작) · DRIVING → READY (주차 완료) · DRIVING → ACCIDENT_PENDING (주행 중 사고 신호) · ACCIDENT_PENDING → ACCIDENT_RESPONSE (정차 확인) · READY → ACCIDENT_RESPONSE (정차 중 사고 신호) · ACCIDENT_RESPONSE → READY (사고 대응 종료).

## 4. 실행 환경 두 가지, 코드와 디자인 자산은 하나

| | Pleos Connect (차량 있음) | 독립 실행 (일반 Android, 차량 없음) |
|---|---|---|
| 역할 | 실제 SDK 연동 실증 | OEM 통합 뒤 화면의 목표 시연 |
| A · C | Pleos가 주는 앱 창에 **RE:SAFETY 패널만** 그린다. 차량 장면과 상태 바는 Pleos 것이므로 따로 그리지 않는다 (겹쳐 그리던 aurora17 방식을 버림) | Aurora Full HMI 전체 (aurora12와 같은 화면) |
| B | Pleos Driving View + Pleos 내비게이션 (실제 시스템 화면) | "자리 표시"라고 적은 화면. Pleos 화면이나 지도를 흉내 내지 않는다 |
| 주행 신호 | 실제 기어 · 속도 | 화면의 시뮬레이션 버튼 |
| 사고 신호 | 가상 이벤트 (시뮬레이션) | 가상 이벤트 (시뮬레이션) |

Pleos에서 A · C의 왼쪽을 우리 Aurora 차량 장면으로 바꾸는 것은 일반 앱 권한으로 할 수 없다. 공식 문서: Driving View는 "앱이 임의로 가리거나 종료할 수 없습니다" (https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model). 그래서 목표 화면(A · C 전체)은 OEM 통합 과제다.

## 5. 지금 되는 전환과 OEM 통합이 필요한 전환

Pleos 에뮬레이터 실측 (`verification/adaptive_pleos_*`):

| 전환 | 일반 앱 권한으로 | 근거 |
|---|---|---|
| READY → DRIVING: Pleos 내비로 넘기기 | **됨** (확인됨) | `SCENE_HANDOVER to Pleos navigation: ok`, 포커스가 Pleos 내비로 이동 |
| 주행 중 앱 사용 차단 | Pleos가 직접 한다 (확인됨) | "주행 중에는 사용할 수 없습니다" 화면 |
| DRIVING → ACCIDENT_PENDING: 사고 화면을 띄우지 않고 음성만 | **됨** (확인됨, 신호는 가상) | `TTS_SPEAK`, 화면은 Pleos 주행 화면 유지 |
| ACCIDENT_PENDING → ACCIDENT_RESPONSE: 정차하면 사고 화면이 저절로 | **됨** — 앱이 앞에 남아 있는 경우 (확인됨) | `…_app_stays_in_front_after_accident_signal` |
| 같은 전환, 운전자가 다른 앱(내비)을 앞에 둔 경우 | **안 됨** → 운전자가 독에서 RE:SAFETY를 연다 | `…_navigation_put_in_front_no_automatic_return` |
| DRIVING → READY: 주차하면 Aurora가 저절로 | 10초 안쪽 주행 뒤에는 됐고, **30초 주행 뒤에는 안 됨** (부분 확인) | `adaptive_pleos_long_drive_return_check_…` [추정: 뒤로 간 앱을 시스템이 멈춰 두기 때문] |
| A · C에서 왼쪽 Driving View 자리를 Aurora 장면으로 | **안 됨** — OEM 통합 필요 | 공식 문서 |
| 실제 충돌 신호 받기 | **없음** — 공개 API 없음, OEM 통합 필요 | Vehicle SDK 문서 |

## 6. 검증 결과

| 환경 | 검사 | 결과 | 기록 |
|---|---|---|---|
| Pleos 에뮬레이터 | A → B → A → B → 사고 대기 → C → A, 실제 기어 · 속도로 | 전환 6개 모두 기대대로, 페이지 오류 0 | `verification/adaptive_pleos_20261115_122125_adaptive4_PASS_app_stays_in_front_after_accident_signal/` |
| Pleos 에뮬레이터 | C에서 보호 모드 켜기 · 되돌리기를 콘솔과 대조 | 일치 | 같은 폴더 `results.json` |
| 독립 실행 | 세 장면 여덟 단계 | 8/8 | `verification/adaptive_standalone_20261115_122546_adaptive4_PASS_three_scenes_8_steps/` |
| 독립 실행 | Aurora 화면 20개를 aurora12와 비교 | 시계 숫자 · 기록 시각 · 깜빡이는 경광등 자리만 다름 | `verification/visual_regression_standalone/compare_aurora12_vs_adaptive4/` |
| 독립 실행 | 스모크 16개 | 16/16 | `verification/evidence/smoke_20261115_122845/` |

중간 빌드 adaptive1~3과 무효 실행 1건은 이유를 이름에 붙여 남겼다.

## 7. 사실대로 구분

- 실제 SDK, 에뮬레이터 실행 확인: 기어 · 속도 · 문 · 좌석 · 벨트 읽기, 보호 모드 제어(Vehicle 2.3.2), 차량 위치(FusedLocation 1.1.3), 주소와 **내비 화면 요청**(NaviHelper 3.0.5), 음성 안내(TextToSpeech 3.3.1).
- 시뮬레이션: 사고 신호, 독립 실행의 주행 상태와 B 화면, 좌석 제어, 왼쪽 Aurora 차량 장면의 상태 문구.
- OEM 통합 필요: A · C 전체 화면(Driving View 자리 포함), 주차 뒤 자동 복귀, 실제 충돌 신호, 주행 중 긴급 알림 화면.
- 넣지 않음: SpeechToText, Gleo.
- 포털 신청 상태는 그대로다: FusedLocation · TTS · Vehicle Control은 "준비 중" (https://document.pleos.ai/docs/pleos-only/create-my-project).
