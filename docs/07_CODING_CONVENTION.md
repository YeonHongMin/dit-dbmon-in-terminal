# DOC-7: Coding Convention (Java)

## 7.1 핵심 원칙

- Java 8 소스/타겟 호환 유지
- 외부 라이브러리 최소화 (Lanterna + JDBC 드라이버만)
- 단일 fat JAR 배포 (maven-shade-plugin)
- 외부 JSON 라이브러리 미사용 (자체 `toJson()`/`quote()`)

## 7.2 프로젝트 구조

```
java/oracle-bridge/
├── pom.xml
└── src/main/java/io/dit/oracle/
    ├── OracleBridgeMain.java   -- Entry point, CLI 라우팅
    ├── OracleCollector.java    -- 모든 JDBC 쿼리
    ├── MetricsBuffer.java      -- Ring buffer, sparkline
    └── OracleMonitorTui.java   -- Lanterna TUI 렌더링
```

## 7.3 코딩 스타일

- **네이밍**: camelCase (변수/메서드), PascalCase (클래스), UPPER_SNAKE (상수)
- **라인 길이**: 120자 권장
- **들여쓰기**: 4 spaces
- **문자열**: `+` 연산자로 SQL 쿼리 연결 (멀티라인)
- **Java 8 제약**: diamond operator OK, lambda OK, `var` 불가, `switch expression` 불가

## 7.4 SQL 쿼리 작성 규칙

- V$ 뷰 직접 참조 (V$SYSMETRIC, V$SESSION, V$SQL, V$EVENTMETRIC 등)
- `FETCH FIRST N ROWS ONLY` 사용 (Oracle 12c+)
- 모니터링 쿼리 자기 제외: `s.sid <> SYS_CONTEXT('USERENV', 'SID')`
- Idle wait 제외: `wait_class <> 'Idle'`
- per-query error isolation: `collectAll()` 내 각 쿼리 독립 try/catch

## 7.5 TUI 렌더링 규칙

- Lanterna Screen layer 사용 (GUI toolkit X)
- `screen.refresh(Screen.RefreshType.DELTA)` 사용 (깜빡임 방지)
- Box-drawing: Unicode 문자 (`┌─┐│└─┘├┤┬┴┼`)
- Sparkline: Unicode block chars (`▁▂▃▄▅▆▇█`)
- 색상: `TextColor.RGB` 사용
- 렌더 루프: 데이터 변경/키 입력/리사이즈 시에만 렌더

## 7.6 보안

- 비밀번호/토큰 하드코딩 금지
- JDBC URL 파라미터로 접속 정보 전달
- CLI args `--password` 로 런타임 전달

## 7.7 빌드 및 검증

```bash
mvn clean package -f java/oracle-bridge/pom.xml
```
