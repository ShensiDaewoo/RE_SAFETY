# RE:SAFETY 최종 제출 APK 시험 근거 (2026-11-15)

제출하는 APK 두 개를 그대로 설치해서 다시 시험한 기록입니다. 시험마다 근거 번호를 붙였습니다. 기획서의 "시험 근거"는 이 번호를 가리킵니다.

- **① Pleos APK**: `Aurora Dawn_RE-SAFETY-SDKProof_release.apk`, SHA-256 `DCBAF29B0BF705BC1F3D0450A8B16C225B151E03B265088093A2E75AA8FC668D`, 0.8.0-r2d-dev (versionCode 24)
- **② Full HMI APK**: `Aurora Dawn_RE-SAFETY_release.apk`, SHA-256 `8BC11E8A052FFBB4E69C90D54D703643CAB157E82DB93FC95D7D4DB74B4BB24D`, 0.6.4-adaptive10 (versionCode 59)
  - 같은 날 이전 제출 후보였던 0.5.0-adaptive4(`5DE8E8D0…`)를 시험하다가 좌석 착석이 반대로 표시되는 결함을 찾아(아래 P2-08) 고친 빌드입니다.
  - 사고 대응 화면 앞의 WARNING 화면, 음성 입력(SpeechToText), Gleo 3개 기능, 출발 전 차량 값도 이 빌드에 들어 있습니다.
  - adaptive4의 시험 기록은 `previous_build_5DE8E8D0_*` 폴더에 그대로 두었습니다.
- 시험마다 기기에 설치된 APK의 SHA-256을 다시 읽어 위 값과 같은지 확인했습니다.
- **시험 환경**
  - Pleos Connect 에뮬레이터: 시스템 이미지 `release_connect_v2.1.0`, 2560 × 1440, 사용자 10
  - 차량 값: Pleos Virtual Console(127.0.0.1:8088)로 바꾸고 읽었습니다.
  - 일반 Android 14 에뮬레이터: API 34, 2560 × 1440
- **시험 시각** (2026-11-15, 한국 시간)
  - ①: 16:28 ~ 16:59
  - ② adaptive10: 17:50 ~ 18:13
- **한계**
  - 모든 시험은 에뮬레이터에서 했습니다. 실차 검증은 아닙니다.
  - 사고(충돌) 신호는 공개 SDK에 없어서 가상 이벤트를 썼습니다.
  - 전화는 걸지 않았습니다.

## ① Pleos APK — Pleos Connect 에뮬레이터 (`pleos_1_r2d/`)

| 번호 | 무엇을 | 어떻게 | 결과 | 파일 |
|---|---|---|---|---|
| P1-01 | 설치한 APK가 제출본인지 | 기기의 base.apk SHA-256을 읽음 | `dcbaf29b…fc668d`, 제출본과 같음 | e01_installed_sha256.txt |
| P1-02 | 위험한 권한이 없는지 | 설치된 패키지의 요청 권한 목록 | 차량·위치 권한 10개. **INTERNET 0, CALL_PHONE 0** | e01_package_version.txt |
| P1-03 | 좌석 착석이 차량 값을 따르는지 | ① 콘솔은 4석 모두 빈 좌석 ② 콘솔에서 운전석·운전석 뒷좌석을 착석으로 바꾼 뒤 화면을 다시 엶 | ① 앱도 4석 "빈 좌석" ② 앱이 **바꾼 두 자리만 "착석 가능성"**, 나머지는 "빈 좌석" | e03_car_seats.json, e04_*.png, e05_car_*.json, e05_seat_after_console_change.png |
| P1-04 | 안전벨트 조회 | "안전벨트 조회"를 누름 | 4석 "벨트 · 미조회" → **"벨트 · 미체결 보고"** 4석. Pleos 주행 화면도 "안전 벨트를 매세요"를 표시 | e03_seat_screen.txt, e04_after_belt_query.txt |
| P1-05 | 보호 모드가 실제 차량을 바꾸는지 | 켜기와 되돌리기를 누르고 그때마다 콘솔 값을 읽음 | 문 잠김 **4 → 풀림 4 → 잠김 4**, 실내등 꺼짐 → 켜짐 → 꺼짐, 전조등 Off → Position → Off. 켜기 PASS, 되돌리기 PASS | protect_vs_console/results.json, c01~c03.png |
| P1-06 | 차량 신호를 모를 때 제어를 막는지 | 속도 권한을 거부한 상태에서 보호 모드 켜기를 누름 | 화면에 "최근 속도와 기어를 확인할 수 없어요". 눌러도 콘솔 값 **변화 없음** (문 4개 잠김, 실내등 꺼짐). 시험 뒤 권한을 되돌림 | k3_perm_state.txt, k4_*, k5_car_after_tap.json, k5_after_tap.png |
| P1-07 | 위치가 차량 좌표를 따르는지 | 차량 위치를 37.56654, 126.97797로 지정 (`adb emu geo fix`) | 앱 "현재 위치 확인" 화면이 **37.56654, 126.97797**, 출처 "차량 좌표"로 같은 값을 표시 | steps.log, e08_location_screen.* |
| P1-08 | 주소를 함부로 붙이지 않는지 | 내비가 아는 위치(37.41272, 127.09468)와 차량 위치가 약 20 km 떨어진 상태 → 같은 위치로 맞춤 | 떨어져 있을 때 "내비 주소 없음". 같은 위치에서는 **"수정구 시흥동"**. 두 위치가 300 m 안일 때만 주소를 씀. 보험·견인 3단계의 견인 통화 준비에도 같은 주소와 좌표가 나옴 | e08_*, e08b_full_log.txt, e09_location_navi_agrees.*, t4_tow_step3.* |
| P1-09 | 사고 정리의 차량 상태가 실제 값인지 | 사고 상황 정리 3단계에서 콘솔 앞유리 와이퍼를 Off → Low로 바꿈 | 앱 와이퍼 "꺼짐" → **"작동 중"**. 그 뒤 만든 QR에도 "와이퍼(차량): 작동"이 들어감 | g3_*, g4_*, g6_step5_qr.png.decoded.txt |
| P1-10 | 119·112가 전화를 걸지 않는지 | 119와 112 버튼을 누르고 통화 목록과 시스템 기록을 봄 | 전화 화면에 119, 112가 입력돼 열림. 통화 목록 **앞뒤 모두 0건**. 시스템 기록의 DIAL(전화 화면 열기) 3건, **CALL(발신) 0건** | e10_*, e11_*, e12_*.txt |
| P1-11 | QR이 실제로 읽히는지 | 두 경로(보험·견인 → QR로 공유 / 사고 상황 정리 → QR 만들기)를 실제 화면 탭으로 20번씩 반복하고 화면 캡처를 OpenCV로 읽음 | **40번 중 40번** "[RE:SAFETY]"로 시작하는 글이 읽힘. 앱 프로세스 번호가 처음부터 끝까지 같음(**충돌 0건**) | qr_loops/qr_tow.log, qr_loops/qr_guide.log, *_decoded.txt |
| P1-12 | 앱을 껐다 켜도 기록이 남는지 | 사고 기록을 연 뒤 앱 프로세스를 강제 종료하고 다시 실행 | 위치, 직접 확인 2건과 시각, 앱 동작 기록이 **종료 전과 글자 하나까지 같게** 다시 보임 | r1_record_before_restart.txt, r2_pid_after_kill.txt, r3_record_after_restart.txt |

① APK는 음성 읽기(TTS)를 안드로이드 기본 엔진으로 하는데, 이 에뮬레이터에는 엔진이 없어 소리가 나지 않습니다. Pleos TextToSpeech SDK로 읽는 ②에서 확인했습니다(P2-06).

## ② Full HMI APK (adaptive10) — Pleos Connect 에뮬레이터 (`pleos_2_fullhmi_a10/`)

| 번호 | 무엇을 | 결과 | 파일 |
|---|---|---|---|
| P2-01 | 설치한 APK가 제출본인지 | 설치본 SHA-256 `8bc11e8a…4bb24d`, 제출본과 같음 | seat_vs_console/s00_installed_sha256.txt, scenes_navigation_protect/results.json |
| P2-02 | 시스템 화면을 가리지 않는지 | 앱 창 [853,90]–[2535,1300]. 왼쪽 주행 화면 [0,0]–[853,1300], 상단·하단 바와 **겹침 없음** | rc_window_119_tts/results.json, D_app_window_on_pleos.png |
| P2-03 | 출발하면 물러나는지 | 콘솔에서 기어 D, 30 km/h → 앱이 `NaviHelper.requestNavigationScreen(Foreground)`를 보냄 → 화면 앞에 **Pleos 내비게이션 앱**이 옴. 주차하면 다시 READY | scenes_navigation_protect/results.json (B_DRIVING의 focus), B1_driving_30kmh.png |
| P2-04 | 주행 중 사고 신호와 WARNING | 가상 사고 이벤트를 주행 중에 보냄 → 사고 대기, 화면은 내비 그대로, 음성으로만 알림. 이때 WARNING 화면이 앱 안에서 대기(18:08:00.5). P에 2초 머문 뒤 사고 대응 화면이 열리는 순간 4초 카운트 시작(18:08:15.4), **18:08:19.6에 닫히고** 사고 대응 화면이 보임. 같은 빌드로 찍은 녹화(warning_in_demo, 최종 시연 영상 1부)에서도 같은 순서가 보임. 원본 해상도 스틸: `docs/figures_final_20261115/P4_warning_after_parking.png` | scenes_navigation_protect/results.json, warning_in_demo/* |
| P2-05 | 보호 모드 | 켜기 → 콘솔 값 바뀜, 되돌리기 → 원래대로 (changed true, restored true). 따로 한 두 번째 시험에서도 같은 항목별 결과 | scenes_navigation_protect/results.json, rc_window_119_tts/C_after_*.png |
| P2-06 | 음성 안내 | Pleos TextToSpeech로 8번 읽음(장면 시험 4, 보호 모드 시험 4), 매번 시작·끝 이벤트가 짝으로 기록됨. 읽는 동안 쉬지 않는 오디오 출력 스레드가 **2개 → 4개**로 늘어남(시스템 오디오 기록). 사람이 귀로 들은 확인은 아님 | rc_window_119_tts/C_audio_flinger_*.txt, results.json |
| P2-07 | 119 | Pleos 전화 화면(ai.umos.callandmessage)이 앞에 열림, 119 입력. 통화 수 앞뒤 **0 → 0** | rc_window_119_tts/results.json, B_after_119_tap.png |
| P2-08 | **좌석 착석 (이전 빌드의 결함을 고침)** | 이전 빌드 adaptive4: 콘솔에서 운전석·운전석 뒷좌석을 착석으로 두자 앱이 조수석 쪽 두 자리를 착석으로 표시(반대). 원인은 이 에뮬레이터의 Vehicle SDK 2.3.2가 빈 좌석에 `true`를 돌려주는 점을 ②가 반영하지 않은 것(①의 `SdkSeatAdapter`는 반영). **adaptive10**: 같은 콘솔 상태에서 앱이 **운전석·운전석 뒷좌석만 "착석 가능성"**, 앱 상태값도 `D=y, P=n, RL=y, RR=n` | seat_vs_console/s02_seat_screen.png, precar/results.json(veh_after.seats), 이전: previous_build_5DE8E8D0_pleos_2_fullhmi/seat_vs_console/ |
| P2-09 | 안전벨트 조회 | "안전벨트 조회" → 4석 모두 **"미체결 보고 (차량)"**. Pleos 주행 화면도 "안전 벨트를 매세요" | seat_vs_console/s02_seat_screen.png, s02_app_log.txt |
| P2-10 | 음성 입력 (SpeechToText) | 시험 음성(한국어 문장, PC에서 만든 16 kHz 음성)을 SDK의 sendAudio 경로로 넣음 → 메모 칸에 **12글자**가 입력됨. 차량 마이크 경로는 시험하지 않음 | stt_gleo/results.json, stt_1_pre_after_voice.png |
| P2-11 | Gleo Interface 3개 기능 | Gleo 앱이 설치 뒤 schema를 읽은 기록 확인. 3개 기능을 Pleos 안내대로 시작하면 사고 대응 화면·사고 상황 정리·119 전화 화면이 열림, 통화 **0 → 0**. Gleo가 직접 부른 것이 아니라 같은 방식으로 흉내 낸 시험임(실제 음성 호출은 Pleos 검토·승인 뒤) | stt_gleo/results.json, gleo_*.png |
| P2-12 | 출발 전 차량 정보 | "차량 정보 읽기" → 값을 읽어 화면에 표시하고 앱 재시작 뒤에도 남음. 사고 기록 맨 위에 "출발 전 차량 정보 (Pleos 차량) · 차대 확인 안 됨 · 누적 0 km · 주행 가능 0 km"가 남음(이 에뮬레이터는 차대번호·주행거리를 비워서 보냄). 스크립트의 "기록 줄 수 증가" 판정은 기록이 이미 최대 60줄이라 실패로 나왔고, 기록 화면에서 직접 확인함 | precar/results.json, r1_record_screen.txt |

| P2-13 | 근처 SOS 받기 (Pleos) | 개발 PC 안의 시험 서버(127.0.0.1)와 시험 신원 2개(앱, 보내는 쪽 E)를 새로 만들어 시험. 앱에서 "SOS 알림 받기"를 켜고, 100 m 떨어진 가상 위치의 E가 SOS를 보냄 → 서버가 앱에 전달(matched) → Pleos 앱 화면에 **"TEST / 실제 사고 아님 · 근처 SOS 요청 (거리 300m 이내, 89초 남음)"**과 확인·거절 버튼 → 앱에서 확인을 누르자 E의 요청 상태가 **ACKED 1**. 실제 차량 간 전송이나 실제 위치가 아님 | sos_receive/sos_receive_result.json, S2_sos_received_on_pleos.png, S3_after_ack_on_pleos.png, capture_sos_receive.log.txt |

## ② Full HMI APK (adaptive10) — 일반 Android (`generic_2_fullhmi_a10/`)

| 번호 | 무엇을 | 결과 | 파일 |
|---|---|---|---|
| G2-01 | 세 장면 전환 | READY → DRIVING → READY → DRIVING → 사고 대기 → 사고 대응 → READY, READY → 사고 대응 → READY. **8단계 중 8단계** 기대한 장면. 화면 오류 0. 첫 실행은 WARNING 화면(4초)이 다음 탭을 받아 실패해 INVALID로 남기고, 사고 화면 뒤 4.6초 기다리도록 고쳐 다시 함 | three_scenes_8_steps/results.json, s01~s08.png, INVALID_* |
| G2-02 | 기능 점검 16개 | **16 / 16 통과** (3.8분). 내용: 모든 화면 열고 돌아오기, 119 전화 화면만 열기(통화 0 → 0), 가상 사고 이벤트(의심·확정·중복), 개발 PC 안의 시험 서버를 쓴 SOS 1회(외부 전송 아님): 동의 → 요청 전송 → 서버 접수 → 주변 수신자 1명 전달 → 수신 확인 1명(s5_sos_acked.png), 앱 재시작 뒤 사고 정리 이어하기, QR 3종을 판독기 3개 중 2개 이상으로 읽기 | smoke_16_checks/results.json |
| G2-03 | WARNING 화면 | "정차 확인" 뒤 WARNING이 깜빡이며 약 4초 보이고 붉은 사고 대응 화면으로 넘어감(같은 빌드의 녹화와 최종 시연 영상 2부) | warning_in_demo/*, `docs/figures_final_20261115/G2_warning_fullhmi.png` |

## 다시 해 보는 방법

`scripts/` 폴더에 쓴 스크립트가 모두 있습니다.

| 시험 | 스크립트 |
|---|---|
| P1-03 ~ P1-12 단계별 실행 | `pl.py` |
| P1-11 QR 반복 | `r2d_qr_loop.py` |
| P1-05 보호 모드 대조 | `run_protect_crosscheck.py` |
| P2-01 ~ P2-07 | `run_adaptive_pleos.py`, `rc_pleos_checks.py` |
| P2-08 ~ P2-09 | `pl.py` |
| P2-10 ~ P2-11 | `run_stt_gleo_pleos.py` |
| P2-12 | `run_precar_check.py` |
| G2-01 | `run_adaptive_standalone.py` |
| G2-02 | `run_adaptive10_smoke.py` |

실행 순서와 시각은 각 폴더의 `steps.log`에 남겨 두었습니다.
