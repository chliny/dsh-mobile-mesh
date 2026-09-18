# 原始需求

zerotier 连接不稳定，在前台会断连，提示 `sent ping but didn't receive pong within 10000ms (after 0 successful ping/pongs)`，界面显示“A 正在重连·第1次尝试”。需要定位并修复 ZeroTier 前台连接断开、WebSocket ping/pong 超时和重连慢的问题，同时不能影响已经验证正常的 Tailscale 连接、Tailscale/ZeroTier 切换，以及后台恢复行为。固定的短超时不适合弱网络：应使用足够长的基础超时，并根据首次连接/首次有效 RTT（用户称为首次 TTL 耗时）动态计算合理的 pong 超时，避免弱网络下过早断连和无限重连失败。

## 目标理解

1. 识别 10 秒 ping/pong 超时发生在 DSH WebSocket/mux、SSH relay，还是 ZeroTier native relay 层。
2. 区分“真实网络断开”和“ZeroTier/SSH relay 暂时没有及时转发数据”，避免把短暂抖动立即升级为整代连接失败。
3. 保持前台连接稳定：短暂的 pong 延迟不应导致不必要的重连；真实断开仍需快速重建 ZeroTier relay、SSH tunnel 和 WebSocket generation。
4. 保留 Tailscale 特有的授权、pairing 和恢复路径，不让 ZeroTier 的修复改变 Tailscale 行为。
5. 用 Pixel 3 进行实际验证，确认前台连接、切换、断线恢复和会话列表。

## 实现思路

### 1. 先完成证据采集和分层定位

- 采集 Pixel 3 上完整的 `ConnectionLoop`、`HarnessSession`、`RemoteStreamMux`、`ConnectionManager`、`ZeroTierConnector`、`ZeroTierRelay`、`SshTunnelManager` 日志。
- 记录 ping 发出、pong 收到、超时、transport failure、relay termination、reconnect start/end 的时间戳。
- 检查 `HarnessSession` 默认 10 秒 timeout 的语义，确认它是否是单次 ping/pong deadline，是否适合移动网络和 ZeroTier relay。
- 检查 `ConnectionLoop` 对 ping/pong 失败的处理，确认是否存在 0 successful ping/pongs 时过早终止 generation 的逻辑。
- 检查 ZeroTier relay 的 socket read/write、keepalive、线程池和关闭时序，排除 relay 本身吞掉 pong 或被错误关闭。

### 2. 设计最小范围的稳定性修复

优先采用分层、可验证的修复，而不是全局放大 timeout：

- WebSocket/mux 层：将 ping/pong failure 分为“单次超时”和“连续失败”。单次 10 秒未收到 pong 时先进入短暂 grace/探测状态，不立即销毁 generation；连续失败达到明确阈值后才触发 carrier recovery。
- ZeroTier 专用策略：仅对 ZeroTier 使用移动网络容错的 ping/pong grace，不改变 Tailscale 默认策略。策略应是纯函数，便于单元测试。
- 保持现有前台立即恢复行为，但增加恢复去重和 generation token 校验，避免多个并发 recovery 互相关闭新 relay。
- 不重复执行普通 ZeroTier recovery 的 DSH token pairing；只重建 native/SSH/WebSocket carrier。
- 保留 ZeroTier 45 秒 transport budget，但避免每次短暂 ping 抖动都重新走完整的慢启动路径。
- 若证据表明 pong 实际到达 SSH/ZeroTier relay 但被 WebSocket 层丢失，优先修复 relay 生命周期或读写线程，而不是继续延长 timeout。

### 3. 保持 Tailscale 行为隔离

- 不修改 Tailscale 授权 URL、WebView、`authorizationPending`、`preservePendingIdentity` 和 token pairing 逻辑。
- 所有新策略必须明确按 `MeshTransport.ZERO_TIER` 分支；Tailscale 使用原有 ping/pong 和 recovery policy。
- 运行已有 Tailscale 流程回归，确认 relay、SSH、pairing、WebSocket 和会话列表仍然成功。

## 影响范围

预期检查或可能修改的文件：

- `core/src/main/kotlin/dev/dsh/mobile/mesh/core/wire/HarnessSession.kt`
- `core/src/main/kotlin/dev/dsh/mobile/mesh/core/wire/ConnectionLoop.kt`
- `core/src/main/kotlin/dev/dsh/mobile/mesh/core/wire/RemoteStreamMux.kt`
- `androidApp/src/main/java/dev/dsh/mobile/mesh/connection/ConnectionManager.kt`
- `androidApp/src/main/java/dev/dsh/mobile/mesh/connection/ZeroTierConnector.kt`
- `androidApp/src/main/java/dev/dsh/mobile/mesh/connection/SshTunnelManager.kt`
- 对应的 core 和 Android 回归测试目录
- `.tmp/` 下的 Pixel 3 自动化与日志证据文件

不应修改或破坏：

- Tailscale native login/WebView 授权流程
- DSH launch token 内容及日志脱敏
- 用户现有 app 数据、ZeroTier node identity 和 Tailscale identity
- 与本问题无关的 UI、SessionStore、ChatTranscript 修改

## 风险 / 不确定点

1. `10_000ms` 可能来自 WebSocket ping/pong 约定，也可能来自 HarnessSession RPC/请求超时；必须先读实现并通过日志确认，不能盲目修改。
2. 放宽 pong deadline 会延迟真实断线检测；需要连续失败阈值、上限和前台/后台策略，避免假连接长期存在。
3. ZeroTier native relay 可能存在 libzt callback 与 Java relay close 的竞态；修改关闭顺序可能触发 native 崩溃，需要保留进程级 node 复用策略。
4. Pixel 3 当前设备数据必须保留；只允许 `adb install -r`，不得清数据或卸载。
5. 若真实原因是远端服务器负载、Wi-Fi/移动网络切换或 ZeroTier 控制面问题，客户端只能改善容错与恢复，不能保证完全消除外部断网。
6. 所有代码修改后必须运行相关回归测试和 `./.tmp/build-android.sh`；若测试受无关工作区失败影响，应记录具体错误，不得宣称完成。

## 执行步骤

1. 读取 `HarnessSession`、`ConnectionLoop`、`RemoteStreamMux` 和 transport failure 代码，绘制当前 ping/pong 到 reconnect 的调用链。
2. 在不暴露凭据的前提下，给 Pixel 3 安装当前 debug APK，执行 ZeroTier 前台连接并采集带时间戳的 focused logcat；确认超时发生层级。
3. 检查并补充诊断日志：ping sequence、pong sequence、连续失败数、当前 mesh transport、relay token、generation token；禁止输出密码/token。
4. 添加纯函数策略测试，覆盖：单次 ZeroTier pong 超时、连续失败阈值、Tailscale 不受影响、恢复中的重复事件被去重。
5. 根据证据实现最小修复：优先修复 pong 丢失/relay 生命周期；若只是移动网络抖动，再增加 ZeroTier 专用 grace/连续失败策略。
6. 运行相关 core/Android 单元测试，并执行完整 `./.tmp/build-android.sh`。
7. 安装新 debug APK 到 Pixel 3，验证 ZeroTier 前台持续连接；通过受控的网络/relay 断开场景验证一次快速恢复，不重复 pairing。
8. 验证 Tailscale 全流程仍然成功：Tailscale relay、SSH authentication、DSH token pairing、ConnectionLoop、会话列表。
9. 验证 Tailscale 与 ZeroTier 连接切换、前台恢复和后台恢复，记录每次恢复耗时以及是否出现多余完整启动。
10. 只有在日志和 UI 同时证明稳定后，才提交代码、发布 APK，并更新本计划的验证结果。

## 核心代码示例

以下仅为待验证方向，不能在未确认调用链前直接照搬：

```kotlin
internal data class PingPongDecision(
    val consecutiveFailures: Int,
    val recover: Boolean,
)

internal fun decideZeroTierPingPong(
    consecutiveFailures: Int,
    pongReceived: Boolean,
    recoveryThreshold: Int = 2,
): PingPongDecision {
    val nextFailures = if (pongReceived) 0 else consecutiveFailures + 1
    return PingPongDecision(
        consecutiveFailures = nextFailures,
        recover = nextFailures >= recoveryThreshold,
    )
}
```

策略接入必须满足：

```kotlin
if (config.meshTransport == MeshTransport.ZERO_TIER) {
    // 仅 ZeroTier 使用经过测试的短暂 pong grace。
} else {
    // Tailscale 保持既有策略，尤其不能改变授权恢复行为。
}
```

恢复路径仍需保持单一入口和 token 校验：

```kotlin
if (appInForeground && lifecycle.accepts(generationToken)) {
    recoverTransportAfterCarrierLoss()
}
```

## 关键诊断结果

已确认当前 Android OkHttp 全局客户端在 `androidApp/src/main/java/dev/dsh/mobile/mesh/di/AppModule.kt` 中配置：

```kotlin
.pingInterval(10, TimeUnit.SECONDS)
```

OkHttp 的 WebSocket ping 在 10 秒内收不到 pong 时会主动失败连接，错误文本正是：

```text
sent ping but didn't receive pong within 10000ms
```

因此这不是 `HarnessSession.exchange(timeoutMs = 10_000)` 的 HTTP token 交换超时，也不是 `ConnectionLoop.readyTimeoutMs`。当前 ZeroTier 连接经过约 50 秒后出现该错误，与 WebSocket heartbeat 触发时间一致。现有代码注释也明确说明这是用于快速检测 carrier failure 的设置。

下一步应避免继续盲目放大所有 HTTP timeout，而是为 WebSocket carrier 提供按 mesh transport 隔离的 ping/recovery 策略：

- Tailscale 保持当前心跳行为，避免改变已经验证的 Tailscale 流程；
- ZeroTier 使用更宽松但有上限的 heartbeat interval，或使用独立 `OkHttpClient`；
- 仅 ZeroTier 的短暂 pong 抖动不应立即摧毁 generation；
- 真正连续失败仍应触发现有前台快速 recovery；
- 增加配置策略纯函数测试，证明 Tailscale 和 ZeroTier 的间隔/故障阈值隔离。

暂未修改业务代码，因为需要先确定 `DshApiClient`、`ConnectionLoop` 和 WebSocket client factory 是否能安全地按 `HostConfig.meshTransport` 选择客户端；这属于下一步实现前的调用链确认。

## 验证记录

### Pixel 3 当前实测（2026-09-18）

本次使用已保存的 ZeroTier 连接 `gmkzt.chliny.me:3080`，未清除应用数据，未卸载应用。

初始连接曾成功：

```text
SSH transport ready in 17997ms
SSH authentication ready in 133ms
SSH local forward ready
ConnectionLoop: generation connected in 68ms
Connected generation published
```

但前台稳定性验证未通过。连接约 50 秒后出现：

```text
sent ping but didn't receive pong within 10000ms (after 3 successful ping/pongs)
Starting tokened transport recovery (attempt 1)
```

随后 ZeroTier/SSH 恢复耗时较长，且测试期间应用进入后台后记录为：

```text
Background retention disabled; suspending active connection
Foreground resumes pending connection
SSH transport ready in 2364ms
SSH authentication ready in 123ms
```

当前结论：

- ZeroTier 初始连接可以成功；
- 前台长时间稳定性未通过，仍会在约 50 秒后因 ping/pong 超时触发重连；
- 后台切换后能够触发恢复，返回后的 SSH 建链本身约 2.5 秒，但尚未证明恢复后的 WebSocket generation 和会话列表稳定；
- 当前 APK/策略仍不能宣称 ZeroTier 前台稳定；
- 需要继续定位 `ConnectionLoop`/`HarnessSession` ping-pong 超时与 ZeroTier relay 转发之间的关系，再进行代码修复和重新验证。

已有事实仍保留：Pixel 3 曾成功完成 Tailscale relay、SSH、DSH token pairing、WebSocket 和会话列表；本次 ZeroTier 验证结果为失败，不影响该 Tailscale 事实。
