# RE:SAFETY Aurora Full HMI × Pleos 통합 보고서 (aurora17)

작성 2026-11-15. 대상 빌드 `apk/RE-SAFETY_FullHMI_0.4.0-aurora17.apk`
sha256 `7CAE973AB50D4AFAEA089FD0EB42A403ABF686F0C05DAAEC755529AD01C5BDA5` · 31,864,649 bytes · versionCode 46 · 서명 인증서 `b89a29fc…`(aurora12와 같은 디버그 키).
소스 보관본 `source_snapshots/aurora17_7CAE973A/` (APK 안의 페이지와 해시 일치). 디자인 기준본 `source_snapshots/aurora12_C864766D/`.

판정 표기: 확인됨 / 부분 확인 / 반증 / 불명. 추정은 [추정].

## 0. 한 줄 요약

화면은 aurora12 그대로다. 그 밑의 값과 동작은, 차량이 있는 환경(Pleos Connect)에서는 Pleos SDK 4종으로, 차량이 없는 환경(일반 Android)에서는 aurora12의 시뮬레이션으로 돈다. APK는 하나이고, 실행할 때 환경을 보고 갈린다.

## A. 구조 (Architecture)

```
Aurora 화면 (assets/index.html: 배치·색·그림 = aurora12 그대로)
  └ 화면 상태·RE:SAFETY 로직 (같은 파일의 JS: 사고 정리, 기록, QR, SOS, 좌석 확인 …)
      └ 다리 window.Native (MainActivity.Bridge)
          ├ 차량 있음 (android.hardware.type.automotive) → VehicleLink.kt / VehicleProtectionPort → Pleos 서비스
          │     Vehicle 2.3.2 · FusedLocation 1.1.3 · NaviHelper 3.0.5 · TextToSpeech 3.3.1 · AAOS CarPropertyManager(기어·속도)
          └ 차량 없음 → Native.prot() 가 "" → 페이지의 시뮬레이션 (aurora12와 같은 코드 경로)
```

- 갈림 지점은 한 곳: `index.html:717` `const VEHMODE = … window.Native.prot() !== ''`.
- 차량 제어의 플랫폼 중립 경계: `ProtectCore.kt:46` `DProtectionPort` (R2D와 같은 인터페이스). 실제 구현은 `VehicleLink.kt:294` `VehicleProtectionPort`.
- 실행 프로필 두 개, APK 하나. Pleos에서는 시스템이 주는 앱 창(1682×1210) 안에 뜬다. 왼쪽 Driving View와 위아래 바는 Pleos 것이며 앱이 바꾸지 않는다.

## B. R2D → Aurora 이식 대응표 (Migration Matrix)

R2D 소스는 `signal-probe-r2d/app/src/main/java/com/contest/signalprobe/`, Aurora는 `signal-probe-fullhmi/app/src/main/java/com/contest/resafety/fullhmi/`. R2D 파일은 읽기만 했다.

| 기능 | R2D 원본 | Aurora 연결 지점 | 호출 API | 화면 연결 | 검증 |
|---|---|---|---|---|---|
| 정차 확인(P·0 km/h, 2.5초 안의 값) | `DProtection.kt:5` DParkGate | `ProtectCore.kt:16` (로직 동일) | AAOS GEAR_SELECTION, PERF_VEHICLE_SPEED | `MainActivity.kt:196` parkBlock → 보호 모드 "정차 조건" | 부분 확인 (아래 한계 1) |
| 보호 모드 실행 순서 (지원 확인→읽기→요청→다시 읽기, 단계마다 정차 재확인) | `DProtection.kt:53` DProtectionRun | `ProtectCore.kt:55` (로직 동일) | — | `MainActivity.kt:209` startProtect | 확인됨 |
| 미등·후방 안개등·실내등·문 잠금 | `DVehicleProtection.kt:19` | `VehicleLink.kt:294` VehicleProtectionPort (`:335` supports, `:360` readAtTarget, `:408` requestTarget) | Light.check/get/set…LightSwitch, Door.checkDoorLockCapabilities / getDoorLockState / setDoorLock | `index.html:1096` protVehHtml | 확인됨 |
| 되돌리기 (이 앱이 바꾼 항목만 처음 값으로) | `ProductActivity.kt:873` dUndoable, `:881` startDRestore | `MainActivity.kt:202` undoable, `:209` startProtect("off") | 위와 같음 | "되돌리기" 버튼 | 확인됨 |
| 기어·속도 읽기 | `VehicleData.kt:435` readAaos | `VehicleLink.kt:248` carLoop | CarPropertyManager | 상단 속도·기어 글자 (`index.html:1076` vehChrome), 사고 정리 3단계 | 확인됨 |
| 좌석 착석·문 열림 | `VehicleData.kt:376` pollSdk | `VehicleLink.kt:99` pollSeatsAndDoors | Seat.getCurrentSeatOccupancy, Door.getCurrentDoorPosition | 좌석 화면 "차량 보고", 사고 정리 3단계 "문·트렁크" | 확인됨 |
| 안전벨트 조회 (누를 때만) | `VehicleData.kt:91` requestBeltReports | `VehicleLink.kt:114` requestBeltReports | Seat.checkSeatBeltBuckledCapabilities, getCurrentSeatBeltBuckledState | "안전벨트 조회" 버튼 → `Native.beltQuery` | 확인됨 |
| 차량 위치 | `VehicleData.kt:150` pollLocation | `VehicleLink.kt:152` pollLocation | FusedLocation.isSupported, getLastKnownLocation | 사고 요약·QR "위치" (`index.html:1081` vehPosLine), 3단계 "차량 위치" | 확인됨 |
| 내비 주소 (30초 안 + 차량 위치와 300 m 안일 때만) | `VehicleData.kt:255` pollNavi, `:278` freshNaviAddress | `VehicleLink.kt:211` pollNavi, `:230` freshAddress | NaviHelper.getCurrentLocationInfo | 위와 같은 줄의 주소 부분 | 확인됨 |
| 결과 음성 안내 | `ProductActivity.kt:893` dSpoken + 안드로이드 기본 TTS | `MainActivity.kt:181` spoken, `:146` startSpeech | **Pleos TextToSpeech 3.3.1** (ON_DEVICE) — R2D와 다른 점 | 화면 변화 없음 | 확인됨 (이벤트 기준) |
| 옮기지 않은 것 | 와이퍼·하향등·배터리·연료 읽기, 차대번호·주행거리 기록, 사고 패스포트, 견인 연습 | — | — | Aurora에 들어갈 자리가 없다 (디자인 동결) | 해당 없음 |

Pleos와 무관한 일반 Android 로직(사고 이벤트 접수·중복 차단, SOS 시험 서버, 사고 정리, 저장·복원, QR, 보험·견인, 119·112 전화 화면)은 Aurora가 이미 갖고 있던 것 그대로이고 이번에 바꾸지 않았다.

바뀐 파일: `MainActivity.kt`, `ProtectCore.kt`(신규), `VehicleLink.kt`(신규), `AndroidManifest.xml`, `app/build.gradle.kts`, `settings.gradle.kts`, `tools/patch_aurora13_vehicle.py`(신규, 페이지 생성 단계), `assets/index.html`(생성물). CSS와 그림은 바꾸지 않았다.

## C. SDK 통합 상태표

| SDK | 버전 | 이 APK에서 쓰는 곳 | Pleos 에뮬레이터 실행 | 포털 신청 상태 (공식 문서) |
|---|---|---|---|---|
| Vehicle (상태 조회) | 2.3.2 | 좌석 착석·벨트, 문 열림, 조명 스위치, 문 잠금 상태 | 확인됨 | 신청 없이 사용 가능 |
| Vehicle (제어) | 2.3.2 | 보호 모드: 미등·실내등 켜기, 문 4개 잠금 해제, 되돌리기 | 확인됨 (콘솔 차량 상태와 일치) | **준비 중 (아직 신청 불가)** |
| NaviHelper | 3.0.5 | 현재 위치 주소 (getCurrentLocationInfo). 경로·목적지 기능은 쓰지 않음 | 확인됨 | 신청 없이 사용 가능 |
| FusedLocation | 1.1.3 | 사고 요약·QR의 차량 위치 | 확인됨 | **준비 중** |
| TextToSpeech | 3.3.1 | 보호 모드 결과 읽어 주기 (ON_DEVICE, 앱 등록 불필요) | 확인됨: 시작·끝 이벤트 2회. 소리를 사람이 들은 것은 아님 | **준비 중** |
| SpeechToText | 3.3.1 | **넣지 않음.** 누를 음성 입력 버튼이 필요한데 디자인 동결 대상 | 미실행. 에뮬레이터에 서비스(caas 4.2.10)와 권한(STT_SERVICE)은 있음 | **준비 중** |
| Gleo Interface | — | **넣지 않음.** App Schema 등록 필요, 호출 기능 최대 3개 | 불명 (에뮬레이터 시험 가능 여부가 문서에 없음) | 문서에 없음 |

출처: 신청 상태 https://document.pleos.ai/docs/pleos-only/create-my-project · Gleo 3개 제한과 스키마 형식 https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability · 가용성 확인 방법 https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability

선언한 권한 11개: INTERNET, android.car CAR_SPEED·CAR_POWERTRAIN, pleos.car CAR_LIGHTS·CAR_DOORS·CONTROL_CAR_LIGHTS·CONTROL_CAR_DOORS·CAR_SEATS·FUSED_LOCATION·NAVI_ROUTE·TTS_SERVICE. (SDK가 스스로 넣는 바인딩 권한 2개는 따로 있음.)

## D. Pleos 생태계 호환표

| 요소 | 역할 | 앱에 공개된 인터페이스 | RE:SAFETY에서의 상태 |
|---|---|---|---|
| Pleos Vehicle OS | 차량 운영체제 | 없음 (SDK를 통해 간접) | 역할상 APK 이식 대상 아님 |
| CODA | 전자·제어 구조 | 없음 | 역할상 APK 이식 대상 아님 |
| Pleos Connect | 인포테인먼트 플랫폼 | Connect SDK 5종 | 실제 연동 완료 (4종, 에뮬레이터) |
| Pleos Playground | 개발자 플랫폼 (SDK 저장소·에뮬레이터·콘솔) | 공개 Maven, 에뮬레이터, 가상 콘솔 | 개발·검증에 사용 중 |
| Gleo AI | 음성 AI | Gleo Interface + App Schema | 공식 인터페이스 확인 / 등록·승인 필요 |
| Pleos App Market | 앱 배포·심사 | 제출·심사 가이드 | 공식 문서 확인 / 제출 안 함. 심사 때 "준비 중" 권한이 걸림 |
| Pleos Fleet | 기업 차량 관제 | REST API·Webhook (문의처 partnership@pleos.ai) | OEM·제휴 필요 [추정: 사용 조건이 문서에 없음 → 불명] |
| Pleos ID | 통합 계정 | 앱용 문서 못 찾음 | 불명 · 현재 직접 접근 불가 |
| Cloud | 서버 인프라 | 앱용 문서 못 찾음 | 불명 · 현재 직접 접근 불가 |
| Smart City | 도시 인프라 | 앱용 문서 못 찾음 | 불명 · 현재 직접 접근 불가 |

출처: https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/ · https://document.pleos.ai/en/api-reference/fleet-api/intro · https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/core-concepts
더 찾아볼 곳: Pleos ID·Cloud·Smart City는 pleos.ai 본 사이트와 Playground 콘솔 로그인 뒤 메뉴.

## E. 디자인 보존 증거 (Visual Regression)

도구: `verification/walk_screens.py`(새로 설치해 같은 순서로 20개 화면 촬영), `verification/compare_shots.py`(앱 창 안에서 화면별 차이 계산, 차이 기준 = 색 채널 24 초과).
결과 폴더: `verification/visual_regression_20261115/`.

**일반 Android (차량 없음), aurora12 → aurora17** — `genericB/compare/`
- 주행 준비 화면 6개: 차이 0.004 %, 전부 시계 숫자 한 칸.
- 사고 화면 14개: 0.004 ~ 0.56 %. 차이 난 자리는 시계, 기록·QR에 찍히는 시각, 왼쪽 장면의 깜빡이는 경광등·비상등·경고 호뿐.
- 기준선: 같은 aurora17을 두 번 찍어 비교하면(`noise_baseline_same_build_twice/`) 같은 자리에서 0.004 ~ 0.33 %가 나온다. 즉 두 빌드 사이의 차이는 같은 빌드를 두 번 찍을 때의 차이와 같은 자리, 같은 크기다.
- 판정: 차량이 없는 환경에서 디자인 변화 없음 — 확인됨.

**Pleos 에뮬레이터 (차량 있음), aurora12 → aurora17** — `pleosA/compare/`
- 20개 화면 모두 같은 화면에 도달. 차이 0.4 ~ 4.3 %.
- 차이 난 자리: 상단 칩 글자("FULL HMI CONCEPT · 독립 시연본" → "FULL HMI · Pleos 차량 연결", 글자가 짧아져 옆 칩 두 개가 왼쪽으로 조금 당겨짐), 출처 표시(SIMULATION → Pleos 차량), 차량 값, 안내 문구, "시연본 안내" 표의 내용, 그리고 위와 같은 시계·애니메이션.
- 보호 모드 화면은 "시뮬레이션 설정" 패널이 실제 차량에는 해당이 없어 나오지 않는다 (의도한 변화).
- 판정: 배치·색·그림 변화 없음, 글자와 값만 바뀜 — 확인됨. 칩 위치가 조금 당겨진 것은 문구 길이 때문.

## F. 실행 증거

| 환경 | 검사 | 결과 | 기록 |
|---|---|---|---|
| Pleos Connect 에뮬레이터 v2.1.0 (2560×1440, 앱 창 1682×1210) | 차량 값 표시, 벨트 조회, 사고 요약·QR의 위치, 보호 모드 켜기·되돌리기를 콘솔 차량 상태와 대조, 음성 안내 | 통과 | `verification/aurora17_pleos_20261115_110335_PASS_…/` |
| 일반 Android 에뮬레이터 (API 34, 2560×1440) | 스모크 16개 (화면 이동, 119 전화 화면·발신 0건, 가상 사고 이벤트, SOS 시험 서버 1회, 재시작 뒤 이어하기, QR 3종 판독) | 16/16, 3.2분 | `verification/evidence/smoke_20261115_112921/` |
| 두 환경 | 화면 20개 비교 | E항 | `verification/visual_regression_20261115/` |

보호 모드 대조값(콘솔 `/vehicle/state`): 전 = 문 잠김 4·실내등 꺼짐·전조등 Off → 켠 뒤 = 문 풀림 4·실내등 켜짐·전조등 Position → 되돌린 뒤 = 전과 같음. 앱 로그 `PROTECT_RESULT … 미등 켜짐 확인 / 후방 안개등 미지원 건너뜀 / 실내등 켜짐 확인 / 앞뒤 문 4개 잠금 해제 확인`.

무효·실패 기록은 지우지 않고 이름에 이유를 붙였다: aurora13 설치 실패(서명 키), aurora14 TTS 권한 누락, aurora15 TTS 호출 순서, aurora16 TTS 언어 코드, "aurora17" 이름의 잘못 복사된 파일 1개(빌드 실패를 놓친 내 실수).

## G. 빌드와 실행 안내

- Full HMI 통합 APK = Pleos 호환 빌드 = 같은 파일(aurora17). 빌드 변형을 나누지 않고 실행 때 갈린다.
- 빌드: `signal-probe-fullhmi`에서 `JAVA_HOME=C:\Android\jdk\jdk-21.0.13+9`, `ANDROID_USER_HOME=C:\Android\user`, `gradlew.bat assembleRelease "-Pandroid.overridePathCheck=true"`. 페이지는 `tools/patch_aurora_step1.py` 다음에 `tools/patch_aurora13_vehicle.py`로 만든다.
- Pleos 에뮬레이터 설치: `adb install --user 10 -r <apk>`. 첫 실행 때 속도·위치 권한을 허용해야 보호 모드와 위치가 동작한다. 가상 콘솔 페이지(127.0.0.1:8088)가 보이는 상태여야 차량 신호가 흐른다.

## H. 최종 진실표 (Technical Truth Table)

| 기능 | 분류 |
|---|---|
| 보호 모드 (미등·실내등·문 잠금 해제·되돌리기) | REAL SDK VERIFIED ON EMULATOR · 양산 배포는 REQUIRES APPROVAL (Vehicle Control 준비 중) |
| 속도·기어·문 상태 표시 | REAL SDK VERIFIED ON EMULATOR |
| 좌석 착석·안전벨트 "차량 보고" | REAL SDK VERIFIED ON EMULATOR |
| 차량 위치·내비 주소 (요약·QR) | REAL SDK VERIFIED ON EMULATOR · FusedLocation 배포는 REQUIRES APPROVAL |
| 보호 모드 결과 음성 안내 | REAL SDK VERIFIED ON EMULATOR (이벤트 기준, 청취 미확인) · REQUIRES APPROVAL |
| 이 앱 자체의 정차 확인 | IMPLEMENTED BUT NOT VERIFIED (한계 1) |
| 후방 안개등 | NOT SUPPORTED (이 에뮬레이터 차량에 없음, 건너뜀으로 표시) |
| 좌석 제어 | SIMULATION ONLY |
| 사고 감지(화면 전환) | SIMULATION ONLY · 실제 충돌 신호는 OEM DEPENDENT |
| 왼쪽 주행·사고 장면 | SIMULATION ONLY (콘셉트 그림) |
| SOS | 시험 서버로만 동작 (SIMULATION ONLY에 준함) |
| 119·112 전화 화면 | 일반 Android 기능으로 실제 동작 (발신은 사람이) |
| 사고 정리·기록·QR·보험·견인 | 일반 Android 기능으로 실제 동작 |
| 음성 인식 (STT) | 미구현 · REQUIRES APPROVAL |
| Gleo 음성 호출 | 미구현 · REQUIRES APPROVAL |
| 경로 안내 (NaviHelper) | 미구현 |
| Pleos ID · Fleet · Cloud · Smart City | OEM DEPENDENT 또는 불명 |

## 한계와 발견

1. **Pleos는 P가 아니면 앱을 통째로 가린다.** 기어 D·0 km/h에서도 시스템이 "주행 중에는 사용할 수 없습니다"로 덮었다(`e02_gear_D_0kmh_after_tap.png`). 그래서 우리 앱의 정차 확인이 거절하는 장면은 에뮬레이터에서 만들 수 없었다. 같은 이유로, 사고 뒤 기어가 P가 아니면 이 앱(119 버튼 포함)을 쓸 수 없다. 공식 정책표도 주의 분산 최적화(DO)를 선언하지 않은 앱은 주행 중 "Not allowed"이고 좌석·차량 설정은 주행 중 제한이라고 적는다(확인됨: https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy). 좌석 제어가 정차 전용이어야 한다는 조건은 이 동작으로 충족된다. 기획서에는 "정차(P) 후 사용"이라고 쓰는 것이 사실에 맞다.
2. **에뮬레이터 성공 ≠ 배포 승인.** FusedLocation·TTS·STT·Vehicle Control은 포털에서 아직 신청조차 열리지 않았다. 공모전 시연에는 지장이 없지만 "앱 마켓 출시 가능"이라고 쓰면 안 된다.
3. 위치의 신선도는 SDK 시각이 아니라 우리가 받은 시각 기준 30초다(R2D와 같은 규칙).
4. APK가 7 MB에서 31.9 MB로 커졌다 (SDK 라이브러리).
5. 왼쪽 장면에 쓴 그림의 출처 확인은 여전히 남아 있다 (공개 저장소에 올리기 전 필요).
