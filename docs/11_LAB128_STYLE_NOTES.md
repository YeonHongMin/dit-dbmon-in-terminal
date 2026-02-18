# DOC-11: LAB128 Style Notes

## 목표
기존 LAB128 모니터링이 제공하던 운영 감각을 DIT TUI에 반영한다.

## 현재 반영된 항목
- 인스턴스 overview 박스 (한 화면에서 상태 확인)
- 실시간 시계열 ring buffer (`TimeSeriesBuffer`)
- DB 선택 후 단일 모니터 기동 (`dit demo --db <oracle|mysql|postgresql|mssql>`)
- 수집 주기 조절 (`dit demo --db oracle --interval 1.0`)
- 종료 시 snapshot 저장/재시작 시 복원 (`~/.dit/demo_snapshot_<db>.json`)
- 기록/재생 (`dit demo --record ...`, `dit play --file ...`)
- 재생 제어: play/pause, 속도 조절, 프레임 단위/점프 이동
- 타임라인 커서/앵커 선택 및 적용 (`[` `]` `v` `enter`)
- incident 하이라이트 및 incident 프레임 점프(`n/p`)
- 임계치 오버라이드(`--thresholds <json>`) 기반 재생 분석

## 다음 반영 항목
- 실제 Collector 데이터를 overview/차트에 연결
- DBMS별 탭 커스터마이징 (Oracle RAC, SQL Server Wait 등)
- threshold 기반 알림 정책(중복 억제, ack/resolved)
- custom query 등록 UI/설정
