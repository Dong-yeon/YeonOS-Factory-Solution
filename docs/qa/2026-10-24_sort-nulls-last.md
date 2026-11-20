# QA 2026-10-24 — 정렬 `desc nulls last` 가 NOT NULL 키에 붙은 offset 목록 (N-1 후속)

> 독립 QA 세션(docs/29 8장), 상위 요청. **시험·문서만 — 제품 코드 수정 없음.** 기준 main `960778a0`.
> 근거: `docs/qa/2026-10-24_generic-plan.md` N-1(LOT 지연 조인 100만 행 — `nulls last` 를 빼면 1쪽 0.4초 → 0.4ms).
> 가드: `src/test/java/io/smarthappy/guard/SortNullsLastGuardTest.java` — 이 표는 그 시험이 찍는 판정을 모듈별로 옮긴 것이다 **[코드]**.

## 0. 요약

- **대상**: offset 이 있는 문장 84개 중 `nulls last` 가 든 정렬 `foreach` 를 쓰는 문장 34개.
- **NOT NULL 키에 `nulls last` 가 붙은 문장**: 33개, 정렬 식 150개(같은 문장의 안쪽·바깥 정렬을 따로 센다 — 지연 조인 문장). 나머지 1개(`CmsCardMapper#findHistPage` — union)는 걸리지 않는다.
- **왜 문제인가**: 기본 색인(오름차순·NULLS LAST)을 거꾸로 읽으면 DESC NULLS FIRST 순서가 된다. 그래서 `desc nulls last` 는 칸이 NOT NULL 이어도 색인 순서와 맞지 않는다. 플래너가 정렬을 따로 하고, 1쪽도 후보 전체를 정렬한다.
  - `asc nulls last` 는 기본 색인 순서와 같아 문제없다. **화면이 그 키로 내림차순을 고를 때만** 걸린다.
  - 그런데 원장·문서 목록의 기본 정렬은 대개 "최신 먼저"(`createdAt,desc` 등)라 첫 화면부터 걸린다.
- **판정 방법 [코드]**
  - 정렬 식 `별칭.칸` 의 별칭을 그 문장의 from/join 에서 표로 푼다. 파생 표·lateral 은 select 목록을 따라 한 단계씩 푼다.
  - NOT NULL 여부는 **시험 DB 카탈로그**(`pg_attribute.attnotnull`)로 본다 — 마이그레이션 전체를 적용한 상태다.
  - left join 으로 붙은 표의 칸은 결과에서 NULL 이 될 수 있으므로 "빈 값 가능" 으로 둔다.
  - 계산식(`coalesce`·부질의·jsonb·집계 별칭), union 파생 표는 판정하지 않는다(보고만).
- **우선**: 기본 키가 걸린 표 기준.
  - 높음 = 원장(OffsetLedgerGuard 의 원장 표 목록)
  - 중간 = 문서 표(발주·작업지시·ECO 등)
  - 낮음 = 기준정보·작은 표(설비·코드·템플릿·단말 — 행 수가 적어 체감 차이가 거의 없다)
- **고치는 법**(모듈)
  - NOT NULL 키는 `nulls last` 를 붙이지 않는다. 키마다 `<when>` 안에서 고르면 된다. `master/WorkCalMapper.xml#findActivePage` 가 이미 키별로 `desc nulls last`/`nulls first` 를 고르는 꼴이다.
  - 결과 순서는 바뀌지 않는다(NULL 이 없으므로).
  - 가드 허용 목록에서 그 식이 빠지므로, 같은 PR 에서 `KNOWN` 줄을 고친다.
  - 색인 효과를 보려면 정렬 키에 맞는 색인(예: `lot_master (comp_cd, created_at, lot_id)`)이 있어야 한다. 원장 목록은 지연 조인(OffsetLedgerGuard)과 함께 하는 것이 효과가 크다.

## 1. 모듈별 표

### APS

| 매퍼 · 문장 | 기본 키 대상 표 | 우선 | 기본 정렬 키(otherwise) | NOT NULL 키 — nulls last 뺄 것 | 빈 값 가능(둬도 됨) | 판정 안 함 |
|---|---|---|---|---|---|---|
| `aps/ApsMapper.xml#findPlanPage` | `aps_plan` | 낮음 | `a.calculated_at` **NOT NULL** | `a.plan_no` `a.plant_cd` `a.horizon_start` `a.late_wo_cnt` `a.unsched_op_cnt` `a.status` `a.calculated_at` | `a.plan_nm` | — |

### CMS

| 매퍼 · 문장 | 기본 키 대상 표 | 우선 | 기본 정렬 키(otherwise) | NOT NULL 키 — nulls last 뺄 것 | 빈 값 가능(둬도 됨) | 판정 안 함 |
|---|---|---|---|---|---|---|
| `cms/CmsCounterMapper.xml#findCounterPage` | `cms_counter_reading` | 높음 | `l.read_at` 빈 값 가능 | `c.counter_nm` `c.counter_kind` `c.use_yn` `c.counter_cd` | `l.read_at` | coalesce(e.equip_cd, a.asset_cd)(계산식)<br>(select r.read_at from cms_counter_reading r where r.counter_id = c.counter_id and r.void_yn = 'n' order by r.read_at desc, r.reading_id desc limit 1)(계산식) |
| `cms/CmsPmMapper.xml#findRunPage` | `cms_chk_run` | 높음 | `x.due_date` **NOT NULL** | `p.plan_cd` `p.exec_mode` `r.status` `r.due_date` `x.plan_cd` `x.exec_mode` `x.status` `x.due_date` | `r.result` `m.mwo_no` `r.performed_at` `x.result` `x.mwo_no` `x.performed_at` | coalesce(e.equip_cd, a.asset_cd)(계산식)<br>x.target_cd(계산식) |
| `cms/CmsStateMapper.xml#findReviewPage` | `cms_equip_state_log` | 높음 | `s.start_at` **NOT NULL** | `e.equip_cd` `s.start_at` | `s.end_at` `m.mwo_no` | coalesce(nr.state_cat, s.state_cat)(계산식) |
| `cms/EquipCountMapper.xml#findPage` | `cms_equip_count` | 높음 | `c.occurred_at` **NOT NULL** | `c.recorded_at` `e.equip_cd` `c.metric_cd` `c.occurred_at` | — | — |
| `cms/CmsAlarmMapper.xml#findAlarmPage` | `cms_alarm_def` | 중간 | `d.severity` 빈 값 가능 | `e.equip_cd` `l.alarm_cd` `l.raised_at` | `l.cleared_at` `d.alarm_nm` `d.severity` | coalesce(dc.alarm_nm, dn.alarm_nm)(계산식)<br>coalesce(dc.severity, dn.severity)(계산식) |
| `cms/CmsMapper.xml#findMwoPage` | `cms_mwo` | 중간 | `m.reported_at` **NOT NULL** | `m.mwo_no` `m.mwo_type` `m.title` `m.priority` `m.status` `m.reported_at` | `m.due_date` | coalesce(e.equip_cd, a.asset_cd)(계산식) |
| `cms/CmsMapper.xml#findFailCodePage` | `cms_fail_code` | 낮음 | `f.code_type` **NOT NULL** | `f.fail_cd` `f.fail_nm` `f.sort_no` `f.use_yn` `f.code_type` | `f.fail_cat` `c.class_cd` | — |
| `cms/CmsMapper.xml#findProfilePage` | `mst_equip` | 낮음 | `e.plant_cd` **NOT NULL** | `e.equip_cd` `e.equip_nm` `c.class_cd` `e.plant_cd` | `h.node_cd` `u.user_nm` | coalesce(p.criticality, 'c')(계산식)<br>coalesce(p.key_equip_yn, 'n')(계산식) |
| `cms/CmsPmMapper.xml#findPlanPage` | `cms_pm_plan` | 낮음 | `x.next_due_date` 빈 값 가능 | `x.plan_cd` `x.plan_nm` `x.tmpl_cd` `x.exec_mode` `x.use_yn` | `x.assignee_nm` `x.next_due_date` | x.target_cd(계산식) |
| `cms/CmsPmMapper.xml#findTmplPage` | `cms_chk_tmpl` | 낮음 | `t.tmpl_cd` **NOT NULL** | `t.tmpl_nm` `t.target_kind` `t.use_yn` `t.tmpl_cd` | `c.class_cd` | — |
| `cms/CmsCardMapper.xml#findHistPage` | `?` | — | `h.at` 판정 안 함 | — | — | h.kind(파생 h 이 union — 결과를 늘 정렬해 색인 순서와 무관)<br>h.ref_no(파생 h 이 union — 결과를 늘 정렬해 색인 순서와 무관)<br>h.at(파생 h 이 union — 결과를 늘 정렬해 색인 순서와 무관) |

### MES

| 매퍼 · 문장 | 기본 키 대상 표 | 우선 | 기본 정렬 키(otherwise) | NOT NULL 키 — nulls last 뺄 것 | 빈 값 가능(둬도 됨) | 판정 안 함 |
|---|---|---|---|---|---|---|
| `production/LotMapper.xml#findLotPage` | `lot_master` | 높음 | `m.created_at` **NOT NULL** | `m.lot_no` `i.item_cd` `m.origin` `m.created_at` | — | — |
| `production/ReceiptMapper.xml#woPage` | `prd_wo` | 중간 | `w.plan_start` **NOT NULL** | `w.wo_no` `i.item_cd` `w.status` `w.plan_start` | — | — |
| `production/WoMapper.xml#findWoPage` | `prd_wo` | 중간 | `w.plan_start` **NOT NULL** | `w.wo_no` `i.item_cd` `w.plan_end` `w.priority` `w.status` `w.plan_start` | — | — |

### PLM

| 매퍼 · 문장 | 기본 키 대상 표 | 우선 | 기본 정렬 키(otherwise) | NOT NULL 키 — nulls last 뺄 것 | 빈 값 가능(둬도 됨) | 판정 안 함 |
|---|---|---|---|---|---|---|
| `plm/PlmMapper.xml#findEcoPage` | `plm_eco` | 중간 | `e.created_at` **NOT NULL** | `e.eco_no` `e.title` `e.effective_date` `o.user_nm` `e.status` `e.created_at` | — | — |
| `plm/PlmMapper.xml#findEcrPage` | `plm_ecr` | 중간 | `r.created_at` **NOT NULL** | `r.ecr_no` `r.title` `r.status` `r.created_at` | `i.item_cd` `e.eco_no` | — |

### PMS

| 매퍼 · 문장 | 기본 키 대상 표 | 우선 | 기본 정렬 키(otherwise) | NOT NULL 키 — nulls last 뺄 것 | 빈 값 가능(둬도 됨) | 판정 안 함 |
|---|---|---|---|---|---|---|
| `pms/PortfolioMapper.xml#findPortfolioPage` | `pms_project` | 중간 | `x.sop_date` 빈 값 가능 | `x.proj_no` `x.proj_nm` `x.pm_user_nm` `x.progress_pct` | `x.cust_nm` `x.next_gate_date` `x.sop_date` | x.phase_nm(계산식)<br>x.sop_days(계산식)<br>x.slip_days(계산식)<br>x.late_cnt(계산식)<br>x.ready_pct(계산식)<br>x.open_issue_cnt(계산식) |

### POP(MES)

| 매퍼 · 문장 | 기본 키 대상 표 | 우선 | 기본 정렬 키(otherwise) | NOT NULL 키 — nulls last 뺄 것 | 빈 값 가능(둬도 됨) | 판정 안 함 |
|---|---|---|---|---|---|---|
| `pop/PopMapper.xml#findTerminalPage` | `sys_terminal` | 낮음 | `t.terminal_cd` **NOT NULL** | `t.terminal_nm` `t.plant_cd` `t.terminal_type` `t.terminal_cd` | `t.last_seen_at` | — |

### SRM

| 매퍼 · 문장 | 기본 키 대상 표 | 우선 | 기본 정렬 키(otherwise) | NOT NULL 키 — nulls last 뺄 것 | 빈 값 가능(둬도 됨) | 판정 안 함 |
|---|---|---|---|---|---|---|
| `srm/NotifyMapper.xml#findNoticePage` | `sys_outbox` | 높음 | `o.created_at` **NOT NULL** | `o.topic` `o.status` `o.created_at` | — | coalesce(o.payload ->> 'pono', o.payload ->> 'claimno')(계산식)<br>o.payload ->> 'custnm'(계산식) |
| `srm/AckMapper.xml#findPosPage` | `srm_po` | 중간 | `p.po_date` **NOT NULL** | `p.po_no` `c.cust_nm` `p.status` `p.po_date` | `p.issued_at` `va.ack_status` | — |
| `srm/AckMapper.xml#findStatusPage` | `srm_po_line` | 중간 | `x.due_date` **NOT NULL** | `x.po_no` `x.cust_nm` `x.line_no` `x.item_cd` `x.due_date` | `x.ack_status` | x.promised_last(계산식)<br>x.late_days(계산식) |
| `srm/AsnMapper.xml#findAsnPage` | `srm_asn` | 중간 | `x.eta_date` **NOT NULL** | `a.asn_no` `c.cust_nm` `a.plant_cd` `a.ship_date` `a.status` `a.eta_date` `x.asn_no` `x.cust_nm` `x.plant_cd` `x.ship_date` `x.status` `x.eta_date` | — | open_line_cnt(계산 열 별칭)<br>x.open_line_cnt(계산식) |
| `srm/ClaimMapper.xml#findClaimPage` | `srm_claim` | 중간 | `x.created_at` **NOT NULL** | `x.claim_no` `x.cust_nm` `x.title` `x.status` `x.created_at` | `x.reply_due` | — |
| `srm/EvalMapper.xml#findEvalPage` | `srm_eval` | 중간 | `e.period_to` **NOT NULL** | `e.eval_no` `c.cust_nm` `e.status` `e.period_to` | `e.total_score` `e.grade_cd` | — |
| `srm/PoMapper.xml#findPoPage` | `srm_po` | 중간 | `p.po_date` **NOT NULL** | `p.po_no` `c.cust_nm` `p.total_amt` `p.status` `p.po_date` | — | (select count(*) from srm_po_line l where l.po_id = p.po_id)(계산식) |
| `srm/PrMapper.xml#findPrPage` | `srm_pr` | 중간 | `p.pr_date` **NOT NULL** | `p.pr_no` `p.status` `p.pr_date` | `pl.plant_nm` `ru.user_nm` `p.need_date` | (select count(*) from srm_pr_line l where l.pr_id = p.pr_id)(계산식) |
| `srm/ReturnMapper.xml#findReturnPage` | `srm_return` | 중간 | `x.created_at` **NOT NULL** | `x.return_no` `x.cust_nm` `x.ncr_no` `x.item_cd` `x.status` `x.created_at` | — | — |
| `srm/EvalMapper.xml#findTmplPage` | `srm_eval_tmpl` | 낮음 | `t.tmpl_cd` **NOT NULL** | `t.tmpl_nm` `t.status` `t.valid_from` `t.tmpl_cd` | — | — |
| `srm/SupplierMapper.xml#findSupplierPage` | `mst_cust` | 낮음 | `c.cust_cd` **NOT NULL** | `c.cust_nm` `c.cust_cd` | `s.trade_status` `u.user_nm` | sc.cnt(계산식) |

### 기준정보(MES)

| 매퍼 · 문장 | 기본 키 대상 표 | 우선 | 기본 정렬 키(otherwise) | NOT NULL 키 — nulls last 뺄 것 | 빈 값 가능(둬도 됨) | 판정 안 함 |
|---|---|---|---|---|---|---|
| `master/AssetMapper.xml#findAssets` | `mst_phys_asset` | 낮음 | `a.updated_at` **NOT NULL** | `a.asset_cd` `a.asset_nm` `a.asset_type` `a.updated_at` | — | (a.last_calib_date + a.calib_cycle_days)(계산식) |
| `master/WorkCalMapper.xml#findActivePage` | `mst_work_cal` | 낮음 | `c.cal_date` **NOT NULL** | `c.day_type` `c.cal_date` | `c.plant_cd` `c.shift_cd` | — |
| `master/WorkforceMapper.xml#findQualPage` | `mst_qual` | 낮음 | `q.sort_no` **NOT NULL** | `q.qual_cd` `q.qual_nm` `q.sort_no` | `q.valid_months` | holder_cnt(계산 열 별칭)<br>proc_cnt(계산 열 별칭) |

### 영업(MES)

| 매퍼 · 문장 | 기본 키 대상 표 | 우선 | 기본 정렬 키(otherwise) | NOT NULL 키 — nulls last 뺄 것 | 빈 값 가능(둬도 됨) | 판정 안 함 |
|---|---|---|---|---|---|---|
| `sales/SalesMapper.xml#findOrderPage` | `sal_order` | 중간 | `o.order_date` **NOT NULL** | `o.order_no` `c.cust_cd` `o.status` `o.order_date` | — | next_due(계산 열 별칭) |

표의 계산식은 flatten 때문에 소문자로 보인다(예: `coalesce(p.criticality, 'c')`). 원문은 대문자다.

## 2. 가드 — `SortNullsLastGuardTest`

- **판정**: offset 문장 + `nulls last` 가 든 `foreach` 의 `<when>`·`<otherwise>` 식마다 위 방법으로 판정한다. NOT NULL 이면 위반이다.
- **허용 목록 `KNOWN`**: 문장 → 지금 걸리는 식(쉼표) 33줄. 실패 조건은 둘이다.
  - 새 문장·새 식이 걸리면 실패한다(목록과 다르다).
  - 고쳐서 식이 빠지면 "KNOWN 을 지금 상태로 / 빼세요" 로 실패한다.
- **자체 시험**: 임시 매퍼로 left join·파생 표·키별 `desc nulls last`·offset 없는 문장을 확인한다.
- **판정 안 하는 것**: 계산식, 여러 표로 풀리는 별칭, union. 실패가 아니라 보고만 한다. 시험 출력 `[nulls last]` 에 문장 | 식 | 판정 | 근거가 찍힌다.
- **한계 [코드]**
  - 정렬 식이 `<choose>` 밖(foreach 없이 고정 `order by … desc nulls last`)에 있으면 보지 않는다. 지금 그런 offset 문장은 없다.
  - 별칭 풀이는 글자 기준이다. 같은 별칭을 다른 표에 쓰면 "판정 안 함" 이 된다.
