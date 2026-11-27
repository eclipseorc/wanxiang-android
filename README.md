# 万象智控 Android 设备端

设备端客户端：本地生成 deviceId 作为唯一凭证，向服务端换一次性 WebSocket ticket 后建立连接，并周期上报心跳（电量、RTT、状态），供管理后台展示设备在线与运行状态。

万象智控安卓客户端引用了[Genymobile](https://github.com/Genymobile)的[scrcpy](https://github.com/Genymobile/scrcpy)项目，在此非常感谢!

![](C:\Users\Administrator\Downloads\Screenshot_20261126-223147.png)![](C:\Users\Administrator\Downloads\Screenshot_20261126-223159.png)

## 功能

1. **配置页**：分别填写 Web 服务器地址/端口、WebSocket 服务器地址/端口，配置持久化到本地，下次启动自动回填。
2. **设备标识**：deviceId 由 App 首次启动时自动生成（UUID）并持久化，界面只读显示，供管理后台对应设备。
3. **连接**：点击「连接」→ 启动连接保活前台服务 → 换一次性 ticket → 连 `/ws/device`。
4. **心跳上报**：连接成功后每 30 秒发送 `heartbeat`（含电量、RTT、状态），管理后台据此展示设备在线与状态。

## 连接保活机制

设备连接运行在**前台服务**（`ConnectionForegroundService`）中，保证后台/锁屏不掉线：

1. **前台服务 + 常驻通知**：系统不会轻易杀前台服务，连接得以长期存活。
2. **`START_STICKY`**：服务被系统杀死后尝试重启，重启后自动重连。
3. **断线自动重连（指数退避）**：非用户主动断开时自动重连，退避 1s→2s→4s…封顶 30s；连接成功后重置。
4. **电池优化白名单引导**：首次连接时引导用户把 App 加入省电白名单，避免 Doze 模式锁屏后冻结网络。
5. **心跳健壮化**：心跳发送异常不中断定时循环。

> 说明：不采用双进程守护、高频唤醒等灰色保活手段；在 minSdk 26 / targetSdk 34 下，前台服务 + 白名单 + 自动重连是规范且有效的方式。

## 运行要求

- Android Studio Jellyfish / 2024 及以上
- Android SDK 35
- JDK 17
- 依赖：`com.squareup.okhttp3:okhttp`、`androidx.core:core`、`com.google.android.material:material`

## 使用方式

1. 启动后端四个服务：`eclipseorc/wanxiang-server`（web-server:9001、admin-server:9002、websocket-server:9003、scrcpy-server:9004）。
2. 在「配置」页填入：
   - Web 服务器地址/端口，例如 `10.0.2.2` / `9001`（模拟器访问宿主机用 `10.0.2.2`，真机用服务器局域网 IP）
   - WebSocket 服务器地址/端口，例如 `10.0.2.2` / `9003`
3. 点击「连接」，状态显示「设备已连接」即接入成功。连接运行在前台服务中，可退到后台或锁屏。
4. 打开管理后台 `http://<服务器>:9002`，设备清单中即可看到该设备在线，并显示电量、RTT 等信息。

## 注意事项

- 正式环境必须用 HTTPS/WSS，不建议公开暴露 WebSocket。
- 设备不登录、无独立令牌：deviceId 由本机生成并作为唯一凭证，服务端首次收到即自动登记设备。
- 远程屏幕控制由管理后台通过 scrcpy（ADB 无线调试）发起，本 App 不参与屏幕采集。
- 若设备仍频繁掉线，请确认已把 App 加入电池优化白名单，且未开启激进的省电模式。
