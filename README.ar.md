<p align="center"><img src="assets/deepseek-mobile.svg" alt="شعار DSH Mobile Mesh" width="180"></p>
<h1 align="center">DSH Mobile Mesh</h1>
<p align="center"><a href="README.md">English</a> · <a href="README.zh-CN.md">中文</a> · <a href="README.hi.md">हिन्दी</a> · <a href="README.es.md">Español</a> · <a href="README.fr.md">Français</a> · <b>العربية</b> · <a href="README.bn.md">বাংলা</a> · <a href="README.pt.md">Português</a> · <a href="README.ru.md">Русский</a> · <a href="README.ur.md">اردو</a> · <a href="README.th.md">ไทย</a></p>

## الميزات

DSH Mobile Mesh هو تطبيق Android غير رسمي للتحكم عن بُعد في [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness). يستمر تشغيل Agent وShell والملفات وworkspace على الخادم الذي ثُبّت عليه DeepSeek Harness.

- تصفح مساحات العمل والجلسات والسجل والردود المباشرة.
- عرض نتائج الأدوات وإرسال النصوص والصور والملفات.
- إدارة الأهداف والخطط والموافقات والأسئلة والمهام وسير العمل والوكلاء الفرعيين.
- تغيير النماذج والإعدادات والمهارات وتشغيل أوامر DeepSeek Harness.

### اتصال Mesh

يُشغّل التطبيق داخل Android شبكة ZeroTier/libzt أو Tailscale/tsnet بوضع userspace، ويحوّل واجهة HTTP/WebSocket الأصلية لـ DeepSeek Harness إلى الهاتف. لا يحتاج DeepSeek Harness إلى تغيير طريقة التشغيل أو تثبيت إضافة، ولا يلزم استخدام VPN على مستوى نظام Android. يمكن إضافة SSH إلى Direct أو ZeroTier أو Tailscale، مع إبقاء DeepSeek Harness مستمعاً على `127.0.0.1`.

![مسار اتصال DSH Mobile Mesh](assets/mesh-connection.svg)

## الاستخدام

1. شغّل `dsh web` على الخادم.
2. قبل استخدام ZeroTier أو Tailscale، ثبّت وسجّل دخول العقدة المناسبة على الخادم الذي يعمل عليه DeepSeek Harness: ثبّت [ZeroTier](https://www.zerotier.com/download/) وانضم إلى الشبكة نفسها الموجودة في التطبيق، أو ثبّت [Tailscale](https://tailscale.com/docs/install) وسجّل الدخول إلى Tailnet نفسه.
3. نزّل APK من [GitHub Releases](https://github.com/sorsama/deepseek-harness-mobile/releases).
4. اختر Direct أو ZeroTier أو Tailscale؛ SSH طبقة اختيارية للطرق الثلاث.
5. أدخل رمز تشغيل DeepSeek Harness عند الطلب.

## الأمان

يمكن لـ DeepSeek Harness تنفيذ الأوامر وقراءة الملفات وكتابتها على المضيف. لا تعرض منفذاً غير محمي للإنترنت واحفظ الرموز ومفاتيح SSH وبيانات Mesh بأمان.

## التوافق

الإصدار المختبر: [dsh-v0.1.5-alpha.2](https://github.com/deepseek-ai/deepseek-harness/tree/dsh-v0.1.5-alpha.2)، الحزمة `0.1.5-rc.2`.

## المراجع

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
- [OpenCode Mobile Mesh](https://github.com/chliny/opencode-mobile-mesh)
- [DeepSeek Harness Mobile](https://github.com/sorsama/deepseek-harness-mobile)

## الترخيص

[MIT License](LICENSE). جميع العلامات التجارية تخص أصحابها.
