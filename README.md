# 영웅전설4 Android 통합 작업본

DOS 한글판 원본 DAT를 읽는 독립 Android 엔진입니다. 게임 자료는 저장소와 APK에 포함하지 않습니다. 설치 후 보유한 `ed4.zip`을 선택합니다.

현재는 **원본 도입부·필드·시나리오 실행 시험판**입니다. 실행 파일의 초기값에 따라 도입부 맵(리소스 55)에서 시작합니다. 처음 대사, 원본 캐릭터·소품 동작 합성, 터치 이동, 지형 높이와 발밑 마스크를 이용한 통행 판정, 마을 NPC 대화, 일부 출입구 이벤트, 진행 상태 저장/복원을 구현했습니다. 메뉴에서 14개 원본 맵을 탐색할 수 있습니다.

**처음부터 엔딩까지 플레이할 수 있는 완성판은 아닙니다.** NPC 자동 행동·선택지·미지원 이벤트, 광역 맵 구역 전환, 완전한 충돌·길찾기·앞뒤 가림, 전투·마법·아이템 화면·음악·원작 세이브 호환이 남았습니다. 미지원 명령은 실행을 중단하고 해당 이벤트의 플래그·맵 변경을 되돌립니다. 상점·전투를 임시 기능으로 대신하지 않습니다. [작업 기준과 검증 결과](WORK_STATUS.md)를 확인해 주세요.

## 원본 검증

```sh
python tools/verify_assets.py /path/to/ed4
python tools/inspect_scenario.py /path/to/ed4 /tmp/ed4-text-index.json
```

JVM 네이티브 엔진 검증에는 Kotlin/JVM 컴파일러를 사용합니다.

```sh
kotlinc app/src/main/java/com/ed4mobile/app/Ed4Archive.kt \
  app/src/main/java/com/ed4mobile/app/Ed4Scenario.kt \
  app/src/main/java/com/ed4mobile/app/Ed4Terrain.kt \
  app/src/main/java/com/ed4mobile/app/Ed4Layout.kt \
  tools/NativeScenarioCheck.kt -include-runtime -d /tmp/ed4-check.jar
java -jar /tmp/ed4-check.jar /path/to/ed4
```

이 검증은 실제 도입부 초기화·첫 대사, 원본 지형 이동, 마을 NPC 5명의 대사 완료, 14개 맵의 그래픽·동작 테이블을 확인합니다. 전체 스토리 완료를 검증하는 테스트는 아닙니다.

GitHub Actions는 main 변경마다 JVM 단위 테스트와 Android APK 빌드를 실행합니다.
