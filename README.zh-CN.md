<p align="center"><img src="assets/deepseek-mobile.svg" alt="DSH Mobile Mesh Logo" width="180"></p>
<h1 align="center">DSH Mobile Mesh</h1>
<p align="center"><a href="README.md">English</a> · <b>中文</b> · <a href="README.hi.md">हिन्दी</a> · <a href="README.es.md">Español</a> · <a href="README.fr.md">Français</a> · <a href="README.ar.md">العربية</a> · <a href="README.bn.md">বাংলা</a> · <a href="README.pt.md">Português</a> · <a href="README.ru.md">Русский</a> · <a href="README.ur.md">اردو</a> · <a href="README.th.md">ไทย</a></p>

## 功能特性

DSH Mobile Mesh 是 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的非官方 Android 远程控制端。Agent、Shell、文件和工作区继续运行在安装 DeepSeek Harness 的服务器上。

- 查看工作区、会话、历史消息和实时回复。
- 查看工具结果，发送文字、图片和文件。
- 管理目标、计划、待办、审批、提问、任务、工作流和子代理。
- 切换模型、预设和技能，执行 DeepSeek Harness 斜杠命令。
- 搜索会话、查看轨迹和用量、导出日志并接收通知。

### Mesh 连接

App 在 Android 内置运行 ZeroTier/libzt 或 Tailscale/tsnet 用户态网络，将 DeepSeek Harness 原生 HTTP/WebSocket 接口转发到手机。DeepSeek Harness 无需修改启动方式或安装插件，也不需要 Android 系统级 VPN。直连、ZeroTier 和 Tailscale 都可以叠加 SSH 转发，让 DeepSeek Harness 保持监听 127.0.0.1。

![DSH Mobile Mesh 连接流程](assets/mesh-connection.svg)

## 使用方法

1. 在服务器上按官方方式启动 DeepSeek Harness：`dsh web`。
2. 使用 ZeroTier 或 Tailscale 前，先在运行 DeepSeek Harness 的服务器上部署并登录对应节点：安装 [ZeroTier](https://www.zerotier.com/download/) 并加入与 App 相同的网络，或安装 [Tailscale](https://tailscale.com/docs/install) 并登录同一 Tailnet。
3. 从 [GitHub Releases](https://github.com/sorsama/deepseek-harness-mobile/releases) 下载并安装最新 APK。
4. 在 App 中选择直连、ZeroTier 或 Tailscale；SSH 是三者都可以叠加的可选传输层。
5. 按提示输入 DeepSeek Harness 启动令牌。

## 安全提示

DeepSeek Harness 可以在主机上执行命令和读写文件。请优先使用 ZeroTier、Tailscale 或 SSH，不要将未保护的 DeepSeek Harness 端口暴露到公网，并保护启动令牌、SSH 密钥和 Mesh 身份数据。

## 兼容版本

已测试版本：[dsh-v0.1.5-alpha.2](https://github.com/deepseek-ai/deepseek-harness/tree/dsh-v0.1.5-alpha.2)，对应包版本 `0.1.5-rc.2`。

## 参考

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
- [OpenCode Mobile Mesh](https://github.com/chliny/opencode-mobile-mesh)
- [DeepSeek Harness Mobile](https://github.com/sorsama/deepseek-harness-mobile)

## 许可证

[MIT License](LICENSE)。DeepSeek Harness 及其品牌归各自权利人所有；本项目是独立的社区项目。
