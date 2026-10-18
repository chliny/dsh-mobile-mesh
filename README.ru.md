<p align="center"><img src="assets/app-icon.svg" alt="Логотип DSH Mobile Mesh" width="180"></p>
<h1 align="center">DSH Mobile Mesh</h1>
<p align="center"><a href="README.md">English</a> · <a href="README.zh-CN.md">中文</a> · <a href="README.hi.md">हिन्दी</a> · <a href="README.es.md">Español</a> · <a href="README.fr.md">Français</a> · <a href="README.ar.md">العربية</a> · <a href="README.bn.md">বাংলা</a> · <a href="README.pt.md">Português</a> · <b>Русский</b> · <a href="README.ur.md">اردو</a> · <a href="README.th.md">ไทย</a></p>

## Возможности

DSH Mobile Mesh — неофициальный Android-клиент для удалённого управления [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness). Agent, Shell, файлы и workspace продолжают работать на сервере с установленным DeepSeek Harness.

- Просмотр workspaces, сессий, истории и потоковых ответов.
- Просмотр результатов инструментов и отправка текста, изображений и файлов.
- Управление целями, планами, подтверждениями, вопросами, задачами, workflow и субагентами.
- Выбор моделей, presets и skills, запуск slash-команд DeepSeek Harness.

### Mesh-подключение

Приложение встроенно запускает на Android userspace-сеть ZeroTier/libzt или Tailscale/tsnet и передаёт на телефон нативный HTTP/WebSocket-интерфейс DeepSeek Harness. DeepSeek Harness не требует изменения способа запуска или установки плагинов, а системный VPN Android не нужен. SSH можно добавить к Direct, ZeroTier или Tailscale, чтобы DeepSeek Harness продолжал слушать `127.0.0.1`.

![Схема подключения DSH Mobile Mesh](assets/mesh-connection-ru.svg)

## Использование

1. Запустите на сервере `dsh web`.
2. Перед использованием ZeroTier или Tailscale установите и авторизуйте соответствующий узел на сервере, где работает DeepSeek Harness: установите [ZeroTier](https://www.zerotier.com/download/) и подключитесь к той же сети, что и приложение, либо установите [Tailscale](https://tailscale.com/docs/install) и войдите в тот же Tailnet.
3. Скачайте APK из [GitHub Releases](https://github.com/sorsama/deepseek-harness-mobile/releases).
4. Выберите Direct, ZeroTier или Tailscale; SSH — дополнительный слой для всех трёх вариантов.
5. Введите launch token DeepSeek Harness по запросу.

## Безопасность

DeepSeek Harness может выполнять команды и читать или изменять файлы на хосте. Не открывайте незащищённый порт в Интернет и защищайте токены, SSH-ключи и данные Mesh.

## Совместимость и базовая версия протокола

DTO Android соответствуют DeepSeek Harness `v0.2.1-rc.2` (commit `687f282e86`): в эту версию входят полный снимок `replace` для `workspace/follow`, идентификатор `session/create`, выбираемый Host, и ввод `terminal/attach` с подтверждениями, добавленный в ветке 0.2.1 alpha. Настройки и панель сведений о подключении показывают эту же базовую версию. При подключении приложение использует существующие read-only проверки `session/list` и `session/projections` и сохраняет обнаруженные возможности для переподключений; поддержка не определяется по номеру версии сервера. Если старый Host не предоставляет маршрут projection, приложение продолжает использовать прежний subagent API.

Обновление `v0.2.1-rc.2` не изменило ни одного endpoint, который вызывает приложение: изменения полей уведомлений об аккаунте относятся к `account/getUnnotifiedBonuses`, который Android-клиент не использует, а изменения клиента терминала — это кэширование обнаружения в браузере поверх неизменённого ответа `terminal/list`. Поэтому старые Host работают с теми же проверками, и приложение не заявляет возможность без реально используемого пути.

Принятая базовая версия 0.2.1 также охватывает `workspace/follow`: старый `baseline` и новый `replace` полностью заменяют рабочие области, архив и порядок закрепления. Проверки чтения `session/list` и `session/projections` сохранены; `session/create.sessionId` по умолчанию пропускается, используется возвращённый ID. Поле чтения `terminal/environment.maxBufferedBytes` выбирает двусторонний поток `terminal/attach`; на старых Host без него остаются `terminal/follow` и вызовы write/resize. Команды нумеруются на каждое подключение, ждут подтверждения Host до следующей отправки и учитывают предел транспортного окна. Результат `terminal/list` кэшируется для каждой Session и используется всеми представлениями терминала этой Session; он перезагружается один раз для каждого нового поколения соединения, создание и подтверждённое закрытие правят кэшированные строки, а единственным источником метаданных терминала остаётся ответ Host.

Приложение принимает как старые ответы projection без `kind`, так и новые варианты `session/projections`. Ответ `migration-required` считается подсказкой без последовательности и не объединяется с водяными знаками событий реального времени. `session/list.formatStatus` — необязательное поле, а `session/fork.allowMigration` по умолчанию не отправляется для совместимости со старыми Host. Записи subagent с режимом `external` отображаются с метаданными сервера, но не открываются как transcript Session. `terminal/environment.cwd` — текущий каталог, записанный Host.

Список бесед Android теперь открывает автоматические задачи, которыми управляет Host. Запрос чтения `schedule/catalog` проверяет доступность; только структурированный ответ об отсутствии маршрута означает, что старый Host не поддерживается. Приложение показывает порядок и статус Host, использует исходный `sessionId` для `schedule/history` и `schedule/delete` и передаёт увиденное правило в `schedule/update.expected` для обнаружения конфликтов. Как в Web, создание задачи начинает Session, где Agent создаёт напоминание. У Host нет API поиска или фильтрации статуса каталога, поэтому Android не дублирует фильтрацию локально.

## Ссылки

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
- [OpenCode Mobile Mesh](https://github.com/chliny/opencode-mobile-mesh)
- [DeepSeek Harness Mobile](https://github.com/sorsama/deepseek-harness-mobile)

## Лицензия

[MIT License](LICENSE). DeepSeek Harness и его брендинг принадлежат соответствующим владельцам.
