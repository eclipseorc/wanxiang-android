# 设备自动配对「机型脚本引擎」设计方案

> 目标：解决「无线调试自动配对」流程对不同机型差异的硬编码问题，把「机型知识」从
> Android 代码中抽出，变为**由服务端下发、App 本地缓存的 JSON 脚本**，实现
> **加新机型不改 APK、不重新编译、不重新签名**。

---

## 1. 背景与痛点

当前 `AutoAdbAccessibilityService` 把 Moto E14 的页面结构写死为 Java 常量：

- 打开「开发者选项」的 Intent
- 主页列表项文案「无线调试」、子页开关文案「使用"无线调试"」
- 确认框按钮「允许」
- 滚动上限 12 次、单阶段超时 20s
- 6 位配对码正则
- 硬编码的 6 阶段状态机

**每加一个机型 = 改这套代码 + 重新编译 + 重新签名 + 重新装 APK**。7~8 个机型
就会互相打架、无法维护。

## 2. 核心思路（一句话）

> App 内只保留一个**通用动作执行引擎**，不感知任何机型；机型差异全部变成一份
> **JSON 脚本**（动作序列），由服务端下发 + 本地缓存。加新机型 = 服务端加一份
> JSON，APK 一个字节不动。

## 3. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│  服务端（Go）                                                │
│                                                             │
│  device_model_scripts 表                                     │
│    ├─ match_patterns: ["motorola moto e14", "moto e14"]      │
│    ├─ steps_json:      [...]                                 │
│    └─ version / updated_at                                   │
│         │                                                   │
│         │  经 WebSocket 下发（复用 mdm-policy 同款链路）      │
│         ▼                                                   │
└─────────────────────────────────────────────────────────────┘
                          │  mdm-script 消息
                          ▼
┌─────────────────────────────────────────────────────────────┐
│  App（Android）                                              │
│                                                             │
│  1. ConnectionForegroundService                             │
│     └─ 连接建立后 pullDeviceScript()，收到后写本地缓存文件     │
│                                                             │
│  2. DeviceScriptEngine（新，通用引擎，零机型知识）             │
│     └─ 解析 DeviceScript，逐步执行动作原语                   │
│                                                             │
│  3. AutoAdbAccessibilityService（改造，只保留执行能力）        │
│     └─ 不再含任何写死的机型常量                              │
└─────────────────────────────────────────────────────────────┘
```

## 4. JSON 脚本 schema（App 与服务端共用）

一个机型一份脚本，按 `Build.MANUFACTURER + " " + Build.MODEL` 匹配。

```json
{
  "scriptId": "moto-e14",
  "name": "Moto E14",
  "version": 1,
  "matchPatterns": ["motorola moto e14", "moto e14", "e14"],
  "steps": [
    { "type": "launch", "action": "android.settings.APPLICATION_DEVELOPMENT_SETTINGS" },
    { "type": "click", "by": "text", "value": ["无线调试"], "scroll": true, "scrollMax": 12 },
    { "type": "click", "by": "text", "value": ["使用\"无线调试\""], "node": "switch" },
    { "type": "click", "by": "text", "value": ["允许", "Allow"], "optional": true },
    { "type": "click", "by": "text", "value": ["使用配对码配对设备"] },
    { "type": "readPair", "codeRegex": "^\\d{6}$" },
    { "type": "end" }
  ]
}
```

### 动作原语（引擎认识的最小动作集）

| type | 说明 | 关键字段 | 对应现有能力 |
|---|---|---|---|
| `launch` | 打开某系统页 | `action`（Intent action 名） | `startAutoPairTask` |
| `click` | 找节点并点击 | `by`(text/id/desc/class)、`value`、`scroll`、`scrollMax`、`node`(switch)、`optional` | `findNodeByText` + `performClick` + `scrollDown` |
| `readPair` | 读配对码+地址并配对 | `codeRegex`、`addrRegex` | `findPairCode` + `findIpPort` + `triggerPair` |
| `waitFor` | 等待某节点出现 | `by`、`value`、`timeoutMs` | `advanceTo` + 轮询 |
| `end` | 显式终态步骤 | 无 | 脚本收尾 |

### 结束判定：显式终态（关键设计）

引擎**不以「步骤数组走完」判断结束**——不同机型步数不同（有的 5 步、有的 7 步甚至更多），
步数是脚本的事，引擎不关心。引擎围绕**可验证的三态**运转：

| 终态 | 判定依据 |
|---|---|
| 成功 | `readPair` 读码并触发本地配对，配对**异步回调 onSuccess** |
| 失败 | 脚本非法（空/缺 `end` 终态）、配对回调 onError、整体超时 |
| 继续 | 其余情况，等下一拍自推进重试 |

要点：
- 脚本最后一步应是 `type:"end"`，作为「步骤耗尽」的显式标记。
- 真正的成功只由 `readPair` 触发的配对异步回调驱动，而非「跑完 N 步」。
- 到达 `end` 却未配对成功 → 判定失败（脚本配置错误或流程未走通）。
- 引擎通过 `ResultCallback` 把「成功/失败」回传给无障碍服务，由服务收尾。


### 匹配字段（`by` 与 `match` 策略）

- `by`: `text` | `id` | `desc` | `class`
- 匹配模式: `exact`（精确）| `contains`（子串，默认）| `regex`
- `node: "switch"` 表示要找 Switch 类型节点（复用 `findToggleSwitch` 的「向上找父级含文本」逻辑）
- `optional: true` 表示该步找不到也不失败（对应 `findAnyByTexts` 兜底）

## 5. 服务端改动清单

### 5.1 数据表（`internal/storage/sqlite/db.go`）

```sql
CREATE TABLE IF NOT EXISTS device_model_scripts(
  id TEXT PRIMARY KEY,
  match_patterns TEXT NOT NULL DEFAULT '[]',   -- JSON 数组，候选匹配串
  steps_json TEXT NOT NULL DEFAULT '[]',        -- JSON 数组，动作序列
  version INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL
);
```

> 迁移方式沿用现有 `addColumnIfMissing` / 建表后 `CREATE TABLE IF NOT EXISTS` 模式，
> 新表无需迁移旧数据。

### 5.2 Store 方法（`internal/storage/sqlite/store.go`）

新增 `DeviceModelScript` 结构体 + CRUD：

- `ListDeviceModelScripts() ([]DeviceModelScript, error)`
- `GetDeviceModelScriptByMatch(model string) (DeviceModelScript, error)` — 按 `厂商+型号` 串在 `match_patterns` 里做前缀/子串匹配，命中即返回
- `UpsertDeviceModelScript(script) error`
- `DeleteDeviceModelScript(id) error`

### 5.3 WebSocket 下发（`internal/transport/websocket/server.go`）

对称复用 `mdm-policy` 机制：

- `handleDevice` 增加 case `"mdm-pull-script"` → `sendDeviceScript(c)`
- 新方法 `sendDeviceScript(c)`：用设备上报的 `model`（已落库 `devices.model`）匹配脚本，下发给设备
- 消息格式：`{"type":"mdm-script","script":{...},"version":N}`
- 管理端推送：case `"mdm-script-push"` → `pushDeviceScripts()`（可选，后续做）

### 5.4 管理接口（`internal/transport/adminapi/mdm.go` 或新文件 `device_script.go`）

- `GET  /api/admin/device-scripts` — 列出全部脚本
- `POST /api/admin/device-scripts` — 新增/更新（含 match_patterns + steps_json）
- `DELETE /api/admin/device-scripts/{id}` — 删除
- 全部 `requireAdmin` + `Audit`

## 5.5 下发逻辑（时序与触发时机）

> 核心原则：**「配置分发」与「动作执行」分离**。下发只负责把对的脚本送到对的设备；
> 真正点开关、读配对码的动作由引擎在触发时（开机自启 / 用户点「测试自动配对」）执行。

### 下发全景

```
管理后台（浏览器）              服务端（Go）                  设备（Android）
     │                              │                              │
     │ ①管理员编辑/保存机型脚本       │                              │
     ├─POST /api/admin/device-scripts►│                              │
     │                              │ ②落库 device_model_scripts 表  │
     │                              │                              │
     │                              │◄─③连上后发 mdm-pull-script─────┤
     │                              │ ④按设备 model 匹配脚本          │
     │                              ├─⑤下发 mdm-script {script,version}►│
     │                              │                              │ ⑥写本地缓存
     │                              │                              │ ⑦触发时读缓存执行
```

### 两种时机

**时机 A：设备主动「拉」（主路径）**
- 触发点：`ConnectionForegroundService` 的 `onOpen`（连接建立成功），与
  `pullMdmPolicy()` 对称新增 `pullDeviceScript()`。
- 设备端上行：`{ "type": "mdm-pull-script" }`
- 服务端 `handleDevice` → `sendDeviceScript(c)`：
  1. `DeviceByID(c.id)` 取 `model`（已由设备首次连接时 `UpsertDevice` 写入）
  2. 拿 model 在 `match_patterns` 里做前缀/子串匹配
  3. 命中 → 下发完整脚本；未命中 → 下发空脚本 + 明确标志（设备记日志，本次不配对）

**时机 B：服务端主动「推」（增量更新，可选，第一批先不做）**
- 触发点：管理员改了脚本后，经 `/ws/admin` 发 `{ "type": "mdm-script-push" }`
- 服务端 `pushDeviceScripts()`：推给所有在线的、机型匹配的设备（对称 `pushMdmPolicies`）
- 价值：改完不用等设备重启/重连；但「重连即拉」已覆盖主场景，故第一批仅做拉。

### 设备端收到后的处理

`onMessage` 新增 `"mdm-script"` 分支 → `handleDeviceScript(msg)`：
1. **版本比较**：本地 `version` ≥ 下发 `version` 则忽略（防旧消息覆盖新脚本）
2. **写缓存**：`getFilesDir()/device_script.json`
3. **记日志**：`DebugConsole.info("已更新机型脚本 vN")`

> 收到脚本 ≠ 立刻执行。脚本是「配置」不是「命令」，执行在触发时才发生。

### 设备 model 的来源（无需新增上报字段）

设备端 `requestTicket()` 已把 `model = Build.MANUFACTURER + " " + Build.MODEL`
放进换票请求体，服务端 `UpsertDevice(id, model)` 已写入 `devices.model`。
`sendDeviceScript` 直接 `DeviceByID` 取 model 即可匹配。

### 失败与兜底

| 情况 | 处理 |
|---|---|
| model 匹配不到脚本 | 下发空脚本 + 标志，设备记日志，本次不配对 |
| 设备离线时脚本已改 | 下次重连「拉」自然拿到新版（时机 A 兜底） |
| 脚本解析失败 | 记日志，保留旧缓存，不影响既有功能 |
| 脚本字段前后不兼容 | 引擎忽略未知字段、缺字段用默认值，旧 App 不崩 |

## 6. Android 端改动清单

### 6.1 新增 `DeviceScript.java`（脚本模型）

解析 JSON → 内存对象：`match[]`、`version`、`steps[]`（每个 step 是 `type + fields`）。

### 6.2 新增 `DeviceScriptEngine.java`（通用引擎）

- `execute(Context, DeviceScript, Callback)`：逐步执行 steps，调用现有工具方法
- 工具方法从 `AutoAdbAccessibilityService` 抽成可复用（或引擎持有 service 引用）
- 提供 `findNode` / `click` / `scrollDown` / `readPair` 等原语，全部参数化

### 6.3 改造 `AutoAdbAccessibilityService.java`

- 删除写死的机型常量与 6 阶段状态机
- 改为：收到「执行自动配对」→ 读本地缓存的 `DeviceScript` → 交给引擎执行
- 保留 `dispatchGesture` 滚动、`findClickableParent`、深度遍历、`triggerPair` 等**通用**能力

### 6.4 改造 `ConnectionForegroundService.java`

- `onOpen` 里除 `pullMdmPolicy()` 外增加 `pullDeviceScript()`
- `onMessage` 处理 `"mdm-script"` → 写本地缓存文件 + 通知引擎
- 缓存路径：`context.getFilesDir()/device_script.json`

## 7. 实施顺序（分批）

1. **第一批（服务端底座）**：建表 + Store CRUD + adminapi CRUD 接口
2. **第二批（下发链路）**：WebSocket `mdm-pull-script` / `mdm-script` 下发
3. **第三批（App 引擎）**：`DeviceScript` + `DeviceScriptEngine` + 改造 `AutoAdbAccessibilityService`
4. **第四批（App 拉取）**：`ConnectionForegroundService` 拉取 + 缓存 + 首份 Moto E14 脚本
5. **第五批（验证）**：Moto E14 真机回归 + 加第二个机型脚本验证「不改 APK」目标

## 8. 关键决策记录

- 配置来源：**服务端下发 + 本地缓存**（新机型不改 APK）
- 存量处理：**重构成引擎 + 配置**（Moto 流程抽为第一份脚本）
- 存储：**独立表** `device_model_scripts`
- 匹配：**厂商 + 型号字符串匹配**（`Build.MANUFACTURER + " " + Build.MODEL`）

## 9. 风险与注意事项

- **首份脚本必须用已调通的 Moto E14 流程**回填，确保「引擎重构」不引入回归。
- 切换 release 签名后，DPC 校验相关 checksum 需重新计算（存量待办）。
- 脚本下发要带 `version`，App 端做版本比较避免重复执行/降级覆盖。
- 脚本字段尽量**前后兼容**：未知字段忽略、缺字段用默认值，避免服务端升级打爆旧 App。
