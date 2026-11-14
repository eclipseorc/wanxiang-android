# 万象智控 Android 最小客户端

这是与 `eclipseorc/wanxiang-server` 协议匹配的原生 Android Java 示例：通过设备 Token 获取一次性 WebSocket ticket，保持设备控制连接，响应管理员观看请求；每次屏幕共享均需设备用户确认 Android 系统 MediaProjection 授权。服务端只转发 WebRTC 信令，不承载视频流。

## 构建

使用 Android Studio 打开仓库根目录，安装 Android SDK 35 和 JDK 17，然后运行 `app` 配置。或在安装 Android SDK/Gradle 的环境中执行：

```sh
gradle assembleDebug
```

生成 APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 连接步骤

1. 服务端启动 HTTP 和 WebSocket 服务，浏览器管理后台创建设备，复制 `deviceId` 与仅显示一次的设备 Token。
2. Android 客户端填写 HTTP API 地址（如 `https://control.example.com`）、设备 WebSocket 地址（如 `wss://relay.example.com/ws/device`）、设备 ID 和 Token，点击“连接服务器”。本机调试可使用 `http://10.0.2.2:8080` 和 `ws://10.0.2.2:8081/ws/device`（Android Emulator 访问宿主机）；真机使用开发机局域网 IP。
3. 管理后台对该设备发起“查看屏幕”。设备显示请求说明和系统授权弹窗；只有明确同意后才开始采集。
4. 用户可在设备端点击“断开连接”，管理员可结束观看。结束观看只停止屏幕采集，设备连接仍保持在线。

WebRTC 初始使用公共 STUN，仅适合联调；企业网络通常需要自建 TURN，并在 Android 与 `web/index.html` 同时配置正确的 ICE servers。不要把 TURN 长期凭据提交到仓库。生产必须使用 HTTPS/WSS；Manifest 为方便本地开发开启了 cleartext traffic，发布前应移除并用 TLS。

## 注意

- 屏幕采集需设备使用者逐次确认系统 MediaProjection 授权，Android 系统会显示共享提示/持续通知；拒绝时不会启动采集。
- 此工程是最小联调样例，不含生产级自动重连、令牌安全存储、厂商后台保活适配、设备策略管理或 TURN 凭据服务。
- `battery` 当前示例上报 0；正式客户端应使用 `BatteryManager` 读取电量。
