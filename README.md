# 영웅전설4 Android 통합 작업본

DOS 한글판 원본 데이터를 읽는 독립 Android 필드 엔진입니다. 게임 자료는 저장소와 APK에 포함하지 않습니다. 설치 후 보유한 `ed4.zip`을 선택합니다.

현재: 원본 시작 마을 및 14개 맵, 어빈 보행 8프레임, 터치 목적지 이동·카메라, 1~3배 속도, 탐색 위치 저장/자동 복귀, 원본 삼성·만트라 도입 화면.

현재는 **필드 탐색판**입니다. 원작 NPC·이벤트·충돌/통행·맵 출입구·전투·음악은 미구현입니다. [작업 기준과 남은 과제](WORK_STATUS.md)를 확인해 주세요.

## 원본 검증·분석

```sh
python tools/verify_assets.py /path/to/ed4
python tools/inspect_scenario.py /path/to/ed4 /tmp/ed4-text-index.json
```

검증 도구는 원본 AFLB/BZ 리소스와 14개 맵을 검사합니다. 대사 분석 도구는 CP949 한글 텍스트 후보와 정확한 리소스/바이트 위치를 기록합니다. 이벤트 실행 순서와 조건을 아직 해석하지 않으므로 이를 게임 시나리오 실행으로 취급하지 않습니다.

GitHub Actions가 main 변경마다 테스트 APK를 빌드합니다.
