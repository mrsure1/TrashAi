# Technical Requirements Document (TRD) — RecycleAI

| 문서 메타 | 내용 |
|-----------|------|
| 현재 버전 | Production Ready |
| 연결된 PRD | Production Ready (`PRD.md`) |
| 최종 갱신 | 2026-05-18 |

---

## 1. 시스템 아키텍처 개요

### 1.1 핵심 아키텍처 요약
**자체 730개 품목 DB + Supabase 클라우드 + 행정안전부 API + Google ML Kit + AndroidX CameraX + Gemini 3.1 Flash Lite + 주황색 커스텀 드래그 박스 + 핀치 줌 최적화 + E-순환거버넌스 연동**으로 구성된 클라우드·네이티브 하이브리드 아키텍처입니다.

### 1.2 아키텍처 원칙
* **무거운 추론 모델 배제 및 로컬 캐싱 극대화**: 모바일 기기의 발열과 배터리 소모를 방지하기 위해 무거운 온디바이스 AI 모델을 배제하고, 자체 730개 품목 DB를 통한 1차 고속 로컬 매칭을 최우선으로 수행합니다.
* **단일 소스 진실 (SSOT)**: 전국 지자체 분리배출 룰셋은 Supabase를 통해 중앙 집중 관리하고, 행정안전부 API를 통해 실시간 사용자 위치를 정확히 매핑합니다.
* **정량적 평가 (Evals) 기반 검증**: 자체 730개 품목 벤치마크를 통해 매칭 속도와 정확도를 수치화하고 지속 검증합니다.

---

## 2. 시스템 상세 구성

### 2.1 AI / Vision 및 매칭 엔진
* **엔진 구성**: **자체 구축 DB (730개 핵심 품목) + Gemini 3.1 Flash Lite 멀티모달 API**
* **하이브리드 매칭 파이프라인**:
  * 카메라 라이브 프리뷰에서 객체가 포착되거나 사용자가 커스텀 드래그로 영역을 지정하면, 해당 이미지 바이트와 좌표를 기반으로 자체 730개 품목 DB에서 1차 고속 키워드/특징 매칭을 수행합니다.
  * 복잡한 재질이나 세부 판별이 필요한 예외 상황에서만 Gemini 3.1 Flash Lite API로 경량화된 비전 요청을 전송하여 정확도와 비용 효율성을 동시에 달성합니다.

### 2.2 바운딩 박스 기반 사물 인식 및 제스처 처리 아키텍처
* **핵심 라이브러리 스택 (External Dependencies)**:
  * 안드로이드 OS 기본 기능이 아닌, 외부 의존성으로 불러와 결합한 2가지 핵심 라이브러리를 기반으로 동작합니다.
  * **Google ML Kit (`com.google.mlkit:object-detection`)**: 카메라 영상 속에서 실시간으로 사물의 위치를 찾아내 좌표(Bounding Box)를 계산해 주는 구글의 온디바이스 AI 라이브러리로, 초당 수십 프레임씩 빠르게 동작하며 사물이 움직이면 실시간으로 따라가는 트래킹(Tracking)을 지원합니다.
  * **AndroidX CameraX (`androidx.camera:camera-core`, `view`, `lifecycle`)**: 안드로이드 폰의 복잡한 카메라 하드웨어를 제어하고 프리뷰 화면을 띄워주는 공식 라이브러리로, 실시간 이미지 프레임 데이터를 멈춤 없이 ML Kit 엔진으로 전달하는 다리 역할을 합니다.
  * **아키텍처 요약**: 두 라이브러리를 결합하여 객체 좌표를 획득한 뒤, Compose 캔버스(Canvas) 위에 직접 네온 색상의 바운딩 박스를 그리도록 프로그래밍한 커스텀 비전 아키텍처입니다.
* **실시간 객체 탐지 및 영역 크롭(Crop)**:
  * 카메라 라이브 프리뷰 상에서 ML Kit Object Detection을 활용해 프레임 내 주요 객체의 바운딩 박스를 실시간으로 포착하고, 이를 캔버스 상에 녹색 네온 박스(`NeonGreen`)로 시각화합니다.
  * 사용자가 특정 객체를 탭하거나 직접 화면을 드래그하여 관심 영역을 지정(주황색 네온 박스, `NeonOrange`)하면, 해당 바운딩 박스 좌표에 해당하는 이미지 영역을 정밀 크롭(Crop)하여 인식 엔진으로 전달합니다.
* **통합 제스처 중재(Gesture Arbitration) 메커니즘**:
  * 단일 포인터 입력(`pointerInput`) 내에서 탭(Tap)과 드래그(Drag) 제스처를 Touch Slop 기준으로 완벽히 분기하여 제스처 충돌을 원천 차단합니다.
  * **유연한 박스 전환 UX**: 사용자가 드래그로 커스텀 바운딩 박스를 생성해 둔 상태(`dragStart/dragEnd != null`)에서도, 화면상의 다른 녹색 바운딩 박스를 탭하면 기존 드래그 상태를 즉시 초기화하고 선택한 객체의 분석(`onBoxTap`)을 매끄럽게 실행합니다.
  * **오터치 및 미세 드래그 방어 (Guardrails)**: 사용자가 실수로 화면을 미세하게 드래그하여 32px 이하의 작은 박스가 생성될 경우, 이를 오터치로 판별하여 즉시 자동 취소(초기화)함으로써 하단 메뉴 미노출 및 화면 터치 잠금(먹통) 현상을 방지합니다. 또한 빈 배경 탭 시 활성화된 드래그 박스를 즉시 해제합니다.

### 2.3 API 및 클라우드 서비스
* **행정안전부 API**: 기기에서 획득한 GPS 좌표를 기반으로 실시간 행정동/지자체 코드 및 최신 분리수거 규정 메타데이터를 수집합니다.
* **Supabase Cloud DB**: 전국 지자체의 분리수거 룰셋(`ItemRule`), 730개 품목 매핑 테이블, 사용자 피드백 및 정정 요청 로그를 저장하고 제공하는 핵심 BaaS 저장소입니다.
* **E-순환거버넌스 연계 모듈**: 대형폐기물 중 '폐가전제품' 분류 시 Supabase에서 E-순환거버넌스 무상 방문 수거 기준표(단일/다량 수거 팁)를 자동으로 호출하여 바텀시트에 병합 렌더링합니다.

### 2.4 런타임 및 클라이언트 스펙
* **언어**: Kotlin (Android Native), Python 3.11 (백엔드 데이터 수집 파이프라인)
* **프레임워크**: Android Jetpack Compose (Native UI)
* **UI 핵심 기술**: `Canvas` 기반 실시간 네온 바운딩 박스 드로잉, `Modifier.layout` 기반 핀치 줌 동적 높이 확장 스크롤.
* **저장소 아키텍처**:
  * 원격 DB (Supabase): `region_rules`, `items_730_master`, `user_clarify_logs` 테이블.
  * 로컬 캐시 (SQLite / Room): 자체 730개 품목 사전 및 최근 조회 지자체 룰셋(`WasteGuideDb`).

---

## 3. 데이터 흐름 (Data Flow)

```
[ 실시간 카메라 & 사용자 인터랙션 ]
  ├── 1. AI 자동 포착 ──> 녹색 네온 박스 (NeonGreen)
  └── 2. 사용자 직접 드래그 ──> 주황색 네온 박스 (NeonOrange) & "이 영역 분석" 팝업
         │
         ▼ (바이트 & 좌표 전송)
[ 매칭 및 클라우드 라우팅 파이프라인 ]
  ├── 1차: 자체 730개 품목 DB 고속 키워드/특징 매칭 (성공 시 즉시 반환)
  └── 2차: Gemini 3.1 Flash Lite API 멀티모달 정밀 분석
         │
         ▼ (품목 ID & GPS 좌표)
[ 행안부 API & Supabase 룰셋 병합 ]
  ├── 행안부 API ──> 현재 위치 지자체 정보 매핑 (예: 고양시 일산동구)
  └── Supabase ──> 지자체별 맞춤 배출 룰 + (폐가전일 경우 E-순환거버넌스 안내표 병합)
         │
         ▼
[ 하단 정보 카드 렌더링 (Bottom Sheet) ]
  └── 핀치 줌(Pinch Zoom) 사용 시 Modifier.layout을 통해 실제 높이가 확장되며 스크롤 완벽 지원!
```

---

## 4. 주요 모듈 및 파일 명세

| 모듈/파일 | 역할 | 상태 |
|---|---|---|
| `MainActivity.kt` | 메인 UI 컨테이너, 카메라 뷰포트, 핀치 줌 & 스크롤 계층 관리, 사용 가이드 렌더링 | ☑ 완료 |
| `AppState.kt` | UI 상태(`AppUiState`), 자체 DB 매칭, 행안부 API 호출, Supabase 연동, UDF 제어 | ☑ 완료 |
| `Overlay.kt` | `isCustomDrag` 플래그 분기 처리 및 녹색(`NeonGreen`)/주황색(`NeonOrange`) 박스 드로잉 | ☑ 완료 |
| `Tokens.kt` | 브랜드 컬러(`RecycleGreen`, `NeonOrange`, `Primary` 등) 및 디자인 토큰 정의 | ☑ 완료 |
| `supabase_client.py` | 전국 지자체 데이터 및 행안부 API 수집 결과를 Supabase DB에 적재하는 파이프라인 | ☑ 완료 |

---

## 5. 주요 기술 결정과 근거 (Trade-offs)

* **결정 1: 무거운 추론 모델 배제 및 자체 DB(730개) + Gemini 하이브리드 도입**
  * **선택 근거**: 모바일 환경에서 배터리와 발열 관리는 필수적이며, 자체 730개 품목 DB를 구축함으로써 85% 이상의 일상 폐기물을 0.1초 이내에 로컬에서 매칭할 수 있게 되었습니다. 예외 상황에서만 Gemini를 호출하여 비용과 성능을 모두 최적화했습니다.
* **결정 2: 행정안전부 API 및 Supabase 연동**
  * **선택 근거**: 지자체별 분리수거 규정은 자주 변경되므로 앱 내부에 하드코딩하는 것은 불가능합니다. 행안부 API로 정확한 행정동을 파악하고, 클라우드 BaaS인 Supabase에서 즉시 최신 룰을 가져옴으로써 관리 비용과 데이터 정합성을 획기적으로 개선했습니다.
* **결정 3: 핀치 줌 구조에서 `Modifier.layout` 도입**
  * **선택 근거**: `graphicsLayer`만으로 화면을 확대하면 부모 스크롤 뷰가 콘텐츠의 늘어난 높이를 알지 못해 하단이 잘리는 치명적인 Compose UX 버그가 있었습니다. `Modifier.layout`을 통해 `scale`에 비례하여 실제 측정 크기를 부모에게 보고하도록 설계하여 완벽한 스크롤 경험을 달성했습니다.
* **결정 4: 커스텀 드래그 영역에 주황색 네온(`NeonOrange`) 적용**
  * **선택 근거**: 사용자가 화면을 터치/드래그했을 때 AI 자동 인식(녹색)과 동일한 색상이 나오면 자신의 조작이 반영되었는지 인지하기 어렵습니다. 명도 대비가 우수하고 조작감을 강조하는 주황색 네온을 채택하여 UX 피드백을 강화했습니다.
* **결정 5: 단일 포인터 인풋 기반 제스처 중재 및 미세 드래그 방어 로직 도입**
  * **선택 근거**: 탭 레이어와 드래그 레이어를 분리할 경우 상위 제스처가 하위 이벤트를 모두 소비하여 바운딩 박스 선택과 드래그 생성이 서로 충돌하는 문제가 발생합니다. 이를 단일 `pointerInput`에서 Touch Slop 기준으로 중재하고, 32px 이하 미세 드래그 자동 취소 및 드래그 중 탭 전환 기능을 추가하여 화면 먹통 없는 매끄러운 바운딩 박스 인식 UX를 완성했습니다.

---

## 6. 가드레일 (Guardrails)

### 6.1 Pre-AI (입력 단계 필터링)
* **PII 및 프라이버시 보호**: 카메라 프레임이나 캡처 이미지 내의 신분증, 택배 송장, 처방전 봉투 등 개인정보가 감지될 경우 클라우드 전송 전 온디바이스에서 자동 블러/차단 처리합니다.
* **위험물 즉시 안내**: 가스통, 배터리, 주사기 등 위험 폐기물 식별 시 자체 DB 필터를 통해 즉시 특수 폐기 안내 스크린을 노출합니다.

### 6.2 Post-AI (출력 단계 검증)
* **Supabase DB 교차 검증**: Gemini API가 환각(Hallucination)으로 잘못된 품목명을 반환하더라도, Supabase의 `items_730_master` 테이블에 존재하지 않는 품목은 바텀시트에 노출하지 않고 폴백 안내로 전환합니다.
* **E-순환거버넌스 정합성 체크**: 폐가전제품 안내 시 공식 무상 수거 기준표 데이터와 일치하는지 스키마 검증 후 렌더링합니다.

---

## 7. 하네스 및 평가 (Evals)

### 7.1 자체 DB 730개 벤치마크 현황
* **총 테스트셋**: 730개 핵심 품목 × 다양한 조명/배경 환경 이미지 3장 = 2,190개 테스트셋.
* **평가 지표**:
  * **pass@1 (1차 매칭 정확도)**: 자체 DB + Gemini 결합 기준 **94.2%** 달성 (목표 90% 초과).
  * **Latency (평균 응답속도)**: 로컬 DB 매칭 45ms, Gemini 폴백 포함 평균 780ms.
  * **지역 매핑 정합성**: 행안부 API ↔ Supabase 룰 매핑 일치율 **99.8%**.

---

## 8. 배포 및 클라우드 환경 (Deployment)

### 8.1 클라이언트 배포
* **플랫폼**: Android Play Store (정식 배포 패키지 `app.trashai`).
* **빌드 아키텍처**: Min SDK 28, Target SDK 34, 100% Jetpack Compose 기반 Native APK.

### 8.2 백엔드 및 클라우드 배포
* **DB 및 Auth**: Supabase Cloud (PostgreSQL 기반).
* **API 게이트웨이**: 행안부 공공 API 및 Google Gemini API 엔드포인트 연동.

---

## 9. 비용 추정 (Cost Estimation)

### 운영 비용 (월 10,000명 MAU 기준)
| 항목 | 단가 | 월 사용량 추정 | 월 비용 예상 |
|---|---|---|---|
| Gemini 3.1 Flash Lite API | ~₩50/호출 | 50,000회 (15% 폴백 비중) | ~₩25,000 |
| Supabase Cloud DB | Pro Plan | 1 instance | ~₩35,000 ($25) |
| **월 합계 예상** | | | **~₩60,000 / 월** |

---

## 10. 의존성 및 환경 변수 (.env)

### Android 앱 구성 (`build.gradle.kts`)
```kotlin
dependencies {
    implementation("androidx.compose.ui:ui:1.6.0")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("io.github.jan-tennert.supabase:postgrest-kt:2.0.0")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
}
```

### 백엔드 파이프라인 (`requirements.txt`)
```
supabase>=2.3.0
google-generativeai>=0.5.0
requests>=2.31.0
pydantic>=2.5.0
```

### 환경 변수 (.env)
```
SUPABASE_URL=https://<your-project-id>.supabase.co
SUPABASE_SERVICE_KEY=<your-service-key>
GEMINI_API_KEY=<your-gemini-key>
ADMIN_REGION_API_KEY=<your-moais-api-key>
```