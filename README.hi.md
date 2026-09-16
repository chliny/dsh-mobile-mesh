<p align="center"><img src="assets/app-icon.svg" alt="DSH Mobile Mesh logo" width="180"></p>
<h1 align="center">DSH Mobile Mesh</h1>
<p align="center"><a href="README.md">English</a> · <a href="README.zh-CN.md">中文</a> · <b>हिन्दी</b> · <a href="README.es.md">Español</a> · <a href="README.fr.md">Français</a> · <a href="README.ar.md">العربية</a> · <a href="README.bn.md">বাংলা</a> · <a href="README.pt.md">Português</a> · <a href="README.ru.md">Русский</a> · <a href="README.ur.md">اردو</a> · <a href="README.th.md">ไทย</a></p>

## सुविधाएँ

DSH Mobile Mesh, [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) के लिए एक अनौपचारिक Android रिमोट है। Agent, Shell, फ़ाइलें और workspace उसी सर्वर पर चलते हैं जहाँ DeepSeek Harness स्थापित है।

- Workspace, sessions, इतिहास और live replies देखें।
- Tool परिणाम देखें और text, images या files भेजें।
- Goals, plans, approvals, questions, jobs, workflows और subagents संभालें।
- Models, presets और skills बदलें तथा DeepSeek Harness slash commands चलाएँ।

### Mesh कनेक्शन

App Android में ZeroTier/libzt या Tailscale/tsnet userspace नेटवर्क अंतर्निर्मित रूप से चलाता है और DeepSeek Harness के native HTTP/WebSocket interface को फोन तक forward करता है। DeepSeek Harness के launch mode में बदलाव या plugin की आवश्यकता नहीं है और Android system VPN भी आवश्यक नहीं है। Direct, ZeroTier और Tailscale तीनों पर SSH forwarding जोड़ी जा सकती है, जिससे DeepSeek Harness `127.0.0.1` पर सुनता रह सकता है।

![DSH Mobile Mesh connection flow](assets/mesh-connection-hi.svg)

## उपयोग

1. सर्वर पर `dsh web` चलाएँ।
2. ZeroTier या Tailscale उपयोग करने से पहले DeepSeek Harness वाले सर्वर पर संबंधित node install और sign in करें: [ZeroTier](https://www.zerotier.com/download/) को install करके App वाले network से जुड़ें, या [Tailscale](https://tailscale.com/docs/install) install करके उसी Tailnet में sign in करें।
3. नवीनतम APK [GitHub Releases](https://github.com/sorsama/deepseek-harness-mobile/releases) से डाउनलोड करें।
4. Direct, ZeroTier या Tailscale चुनें; SSH इन तीनों के ऊपर वैकल्पिक layer है।
5. App के निर्देश पर DeepSeek Harness launch token भरें।

## सुरक्षा

DeepSeek Harness host पर commands चला और files पढ़/लिख सकता है। Unprotected port को public internet पर न रखें और token, SSH keys तथा Mesh identity सुरक्षित रखें।

## संगतता

परीक्षित संस्करण: [dsh-v0.1.5-alpha.2](https://github.com/deepseek-ai/deepseek-harness/tree/dsh-v0.1.5-alpha.2), package `0.1.5-rc.2`।

## संदर्भ

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
- [OpenCode Mobile Mesh](https://github.com/chliny/opencode-mobile-mesh)
- [DeepSeek Harness Mobile](https://github.com/sorsama/deepseek-harness-mobile)

## लाइसेंस

[MIT License](LICENSE)। DeepSeek Harness और उसकी branding उनके संबंधित मालिकों की संपत्ति हैं।
