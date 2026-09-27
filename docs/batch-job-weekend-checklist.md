# 加工单批量核对 · 周末变更窗口实施检查单

> 适用变更：加工单敏感词**反向核对**后台任务化（批量处理不再占用请求线程）。
> 实施窗口：**周六/周日业务低峰**。本单实施前发给当班验光师，IT 与门店负责人共同逐项勾选。
> 接口均需认证（`-u bootstrap:bootstrap`），写接口带头 `X-Operator-Id`（操作人）、`X-Request-No`（请求号，勿重复）、`X-Biz-Time`（可选）。

## 一、窗口与人员（实施前一天确认）

- [ ] 变更时间已定在周末低峰时段（建议周六 07:00–09:00 演练，正式跑选周日低峰）
- [ ] IT 值守人、门店负责人、当班验光师已拉群，电话号码已互通
- [ ] 已通知**别组取数方**：同步接口 `POST /api/sensitive/recheck` 保留可用；新增大批量异步任务接口
- [ ] 新版本包已就位，应用可正常停启

## 二、实施前备份与记录

- [ ] 记录当前词库状态（词库版本 `dictVersion`、词条数 `wordCount`）：

  ```bash
  curl -s -u bootstrap:bootstrap http://localhost:8080/api/sensitive/words/info
  ```

- [ ] **停止应用**后备份 H2 数据文件（启动时文件被占用，先停再拷）：

  ```bash
  cp data/evops.mv.db  data/evops.mv.db.bak-$(date +%Y%m%d)
  ```

- [ ] 备份文件异地/至少非工作目录留存，磁盘剩余空间充足
- [ ] 启动新版本，健康检查 `GET /api/health` 正常；确认新表已建（`t_batch_job`、`t_batch_job_item`）

## 三、单店试跑（先跑一家店，验光师在场核对）

- [ ] 提交**单店**核对任务（如 S01），立即返回任务号 `jobNo`：

  ```bash
  curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/batch/jobs \
    -H 'Content-Type: application/json' \
    -H 'X-Operator-Id: 9001' -H 'X-Request-No: BATCH-TRIAL-001' \
    -d '{"jobType":"RECHECK","storeCode":"S01"}'
  ```

- [ ] 凭任务号轮询进度（后台执行不堵请求；`progressPct` 按 **5%** 一档跳动）：

  ```bash
  curl -s -u bootstrap:bootstrap http://localhost:8080/api/batch/jobs/<jobNo>
  ```

  关注字段：`jobStatus`（PENDING→RUNNING→SUCCEEDED/FAILED/CANCELLED）、`totalCount`、`processedCount`、`successCount`、`failedCount`、`progressPct`。
- [ ] 任务 `SUCCEEDED` 后，与验光师一起看 `result`：
  - `neverScanned`：**无任何扫描留痕的加工单**（多为历史直插数据）——逐单确认是否需补录/补扫；
  - `changed`：词库变严或文本被绕改导致**处置加重**的字段（`orderNo/field/recordedAction/currentAction/currentHits`）——逐单看 `customerName`/`remark` 打码是否合理；
  - `matchedFields`：留痕与现词库一致的字段数；
  - `failedOrders`：核对失败的单及原因（正常应为空）。
- [ ] 抽单复核：`GET /api/orders/by-no/<orderNo>` 看主单文本、处方、流转记录是否与清单一致

## 四、全量推开

- [ ] 单店结果经验光师确认无误后，提交**全量**任务（不传 storeCode）：

  ```bash
  curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/batch/jobs \
    -H 'Content-Type: application/json' \
    -H 'X-Operator-Id: 9001' -H 'X-Request-No: BATCH-FULL-001' \
    -d '{"jobType":"RECHECK"}'
  ```

- [ ] 轮询至终态；同类型任务同时只允许一个（PENDING/RUNNING），重复提交返回 `JOB_ALREADY_RUNNING`
- [ ] 全量结果 `neverScanned` / `changed` 清单交验光师存档，约定后续处理时限

## 五、异常处置

- [ ] **要中止**：`POST /api/batch/jobs/<jobNo>/cancel`——协作式取消，当前单处理完即停，已处理结果保留
- [ ] **有失败单或被取消**：`POST /api/batch/jobs/<jobNo>/rerun`——**断点重跑**，已成功的单不重扫、不重复写留痕，只补未完成/失败的单
- [ ] **服务重启中断**：重启时执行中的任务自动置 `FAILED`（明细保留），遗留 `PENDING` 自动继续；查 `GET /api/batch/jobs?status=FAILED` 后对相关任务 rerun
- [ ] 紧急情况下同步接口 `POST /api/sensitive/recheck` 仍可用（单事务、小范围/应急）

## 六、数据安全与保留（向验光师说明）

- 批量核对**只追加** RECHECK 扫描留痕（`t_sensitive_scan_log`），**不改加工单业务数据**，加工单状态/处方不受影响。
- 任务运行记录（`t_batch_job` / `t_batch_job_item`）是运维临时数据，终态后**保留 21 天**，每日 03:17 由系统物理清理——这是全库"禁止物理删除"的唯一例外，清理的只是任务进度记录，业务留痕仍永久可查。
- 任务审计可追溯：后台执行沿用提交人的操作人 ID，`request_no` 记为 `JOB:<jobNo>`。

## 七、回滚

- [ ] 任务可随时取消，无业务数据变更，原则上无需数据回滚；
- [ ] 若需回退应用版本：停应用 → 用第二步的 `evops.mv.db.bak-*` 恢复数据文件 → 启动旧版本包（新增任务表对旧版本无影响）。

## 八、完成确认

| 事项 | 结果 | 操作人 | 时间 |
| --- | --- | --- | --- |
| 备份完成 |  |  |  |
| 单店试跑 + 验光师核对 |  |  |  |
| 全量任务完成 |  |  |  |
| neverScanned/changed 清单已交接 |  |  |  |
| failedOrders 为空或已重跑清零 |  |  |  |

当班验光师签字：____________　门店负责人签字：____________　IT 签字：____________
