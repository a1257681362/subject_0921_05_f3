# EVOPS 基础工作区 —— 连锁眼镜店验光与加工底账

Spring Boot 骨架项目。技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

## 启动

1. `mvn -q -DskipTests compile` 验证构建。
2. `mvn spring-boot:run` 启动；Spring Boot 自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。
4. `mvn test` 运行集成测试（13 个用例，内存 H2）。

`docs/schema/evops.sql` 是交付副本，与 `src/main/resources/schema.sql` 保持一致。

## 统一约定

- 响应结构：`{ success, code, message, data }`；成功 `code=OK`，失败返回明确业务码（见 `BizCode`）。
- 全库**禁止物理删除**：镜片作废/恢复、加工单冲销/恢复均以状态位体现；处方编辑时旧行置 `VOID` 保留，另插 `ACTIVE` 新行；全部操作写只追加日志。
- 每次写入留存：操作人 `X-Operator-Id`、请求号 `X-Request-No`、业务时间 `X-Biz-Time`（请求头传入，自动落库）、版本号 `version_no`（乐观锁）。
- 重复提交由库层唯一约束兜底：`t_process_order.uk_order_request(request_no)` → `REQUEST_DUPLICATED`；单号/编码各有唯一约束。
- 关联表（加工单主表 + 左右眼处方）同一事务，要么全部生效，要么全部不见。

## 业务表

| 表 | 说明 |
| --- | --- |
| `t_lens` | 镜片基础信息（ACTIVE/DISCARDED） |
| `t_lens_oper_log` | 镜片 CREATE/UPDATE/DISCARD/RESTORE 记录 |
| `t_process_order` | 加工单主表 |
| `t_process_order_rx` | 左右眼处方（ACTIVE/VOID，每单每眼仅一条有效） |
| `t_process_order_status_log` | 取镜状态流转记录（只追加） |
| `t_sensitive_scan_log` | 加工单文本敏感词扫描留痕（只追加，PASS/MASK/BLOCK/RECHECK） |

## 加工单状态

```
CREATED 已建档 ──start──▶ PROCESSING 加工中 ──finish──▶ READY 待取镜 ──pickup──▶ PICKED_UP 已取镜（终态）
     │                                                                                      
     └──────cancel──▶ CANCELLED 已冲销 ──restore──▶ CREATED
   （CREATED/PROCESSING/READY 均可 cancel；PICKED_UP 不可冲销）
```

处方度数（球镜必填，柱镜、下加光选填）按 0.25D（二十五度）一档校验，轴位 1~180，瞳距 20~90。

## 加工单文本敏感词管控

加工单的文本入口（`customerName`、`remark`）在建档/修改写路径上统一过 DFA 敏感词扫描：

- **先归一再匹配**：全角转半角、字母小写、分隔符（空白与常见干扰标点）剔除——"违-禁"、"违　禁"、"违*禁" 都能命中"违禁"；词库词条入库前同样归一。
- **分级处置**：词库词条按 `词语|级别` 配置，`BLOCK` 拦截（整单拒绝，`SENSITIVE_WORD_BLOCKED`）、`MASK` 打码（命中区间原文整段替换为 `*` 后落库）；缺省 MASK。
- **命中位置返回前端**：`/api/sensitive/scan` 返回原文坐标 `[start, end)`（end 开区间），前端提交前据此标记。
- **词库热更新**：`evops.sensitive.dict-path` 指向 `file:` 外部文件时，文件变更自动生效（扫描前懒检查 mtime）；任何路径都可调 `/api/sensitive/words/reload` 立即重载。词库版本为内容 MD5 短串，随每次扫描留痕。
- **扫描留痕**：每次扫描按加工单按字段写 `t_sensitive_scan_log`（含无命中的 PASS）；BLOCK 拦截尝试独立事务留痕，加工单回滚不丢。
- **反向核对**：`/api/sensitive/recheck` 以 `t_process_order` 为准，用当前词库重扫全量加工单，与最新写路径留痕比对，输出 `neverScanned`（无留痕，如历史直插数据）与 `changed`（处置加重：现词库需要打码/拦截但留痕时不需要——多为词库变严或文本绕开守门被改），并写 RECHECK 快照留痕。给别组的扫描结果数据以此接口校验为准。

## 接口（写接口均带三个审计请求头）

镜片：

- `POST /api/lens` 建档
- `PUT  /api/lens` 修改（body 带 `versionNo` 乐观锁）
- `GET  /api/lens/{id}`、`GET /api/lens?status=&keyword=&current=&size=`
- `POST /api/lens/{id}/discard` 作废、`POST /api/lens/{id}/restore` 恢复

加工单：

- `POST /api/orders` 建档（主表 + 左右眼处方同事务）
- `PUT  /api/orders` 修改（仅 CREATED，处方整组原子替换）
- `GET  /api/orders/by-no/{orderNo}` 按加工单号检索（主单 + 有效处方 + 状态流转记录）
- `GET  /api/orders/{id}`、`GET /api/orders?status=&storeCode=`
- `POST /api/orders/{id}/start | /finish | /pickup | /cancel | /restore`

敏感词管控：

- `POST /api/sensitive/scan` 试扫：`{"text":"..."}` → 命中位置（原文坐标）、打码结果、是否拦截、词库版本
- `POST /api/sensitive/words/reload` 立即重载词库；`GET /api/sensitive/words/info` 当前词库状态
- `POST /api/sensitive/recheck` 反向核对（body 可传 `{"storeCode":"S01"}` 收窄范围）

示例（接口走 Shiro 认证，测试/本地可用 bootstrap/bootstrap）：

```bash
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/lens \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: LENS-0001' \
  -d '{"lensCode":"L-001","lensName":"1.60非球面","price":399.00}'
```
