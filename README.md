# 万象智控 Android 设备端（升级版）

本版本在最小可运行版基础上做了升级，重点强化了：

- 更稳定的 WebSocket 生命周期管理
- 更明确的 `watch-request` / `watch-stop` 流程处理
- 更安全的 `MediaProjection` 同意与清理
- 更可靠的 `PeerConnection`/ICE 的 offer/answer 处理
- 屏幕共享前台通知，减少 Android 12+ 后台限制导致的终止风险

## 关键改进

1. 连接流程
   - 使用设备 Token 获取一次性 WebSocket ticket
   - 连接成功后等待管理员 `watch-request`
   - 支持用户手动断开连接和服务端断开连接

2. 屏幕共享同意
   - 设备端在收到 `watch-request` 后弹出确认对话框
   - 用户确认后才调用 `MediaProjectionManager.createScreenCaptureIntent()`
   - 取消/拒绝时发送 `watch-denied`

3. WebRTC 处理
   - 创建 `PeerConnection`
   - 采集屏幕并创建 VideoTrack
   - 发送 offer，并处理 answer 与 candidate
   - 正确处理 `watch-stop` 和连接失效时的清理

4. 后台保活
   - 使用 `ProjectionForegroundService` 作为前台服务，保证共享期间不会被系统过早终止

## 运行要求

- Android Studio Jellyfish / 2024 及以上
- Android SDK 35
- JDK 17
- `org.webrtc:google-webrtc` 依赖

## 使用方式

1. 启动后端服务：`eclipseorc/wanxiang-server`
2. 在管理后台创建设备并复制 `deviceId` / `deviceToken`
3. 在 Android 客户端中填入：
   - HTTP API 地址，例如 `http://10.0.2.2:8080`
   - WebSocket 地址，例如 `ws://10.0.2.2:8081/ws/device`
   - 设备 ID
   - 设备 Token
4. 点击“连接设备服务”
5. 后台管理员发起“查看屏幕”后，设备端弹出授权提示
6. 点“授权并开始”，系统弹出屏幕捕获授权
7. 授权后，WebRTC 开始协商并发送屏幕流

## 注意事项

- 正式环境必须用 HTTPS/WSS，不建议公开暴露 WebSocket
- 生产环境建议接入 TURN，保证 NAT/防火墙环境下也能完成 ICE 连接
- 此示例是“最小客户端增强版”，适合联调和功能验证，不替代完整的设备管理/安全策略实现

