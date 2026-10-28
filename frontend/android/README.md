# Android-обёртка судейского приложения (тренировочная сборка)

Нативная WebView-обёртка вокруг фронтенда из `frontend/`. Внутри APK работает
`JudgeServer` — тот же сервер, что и `serve.py`, портированный на Java и
запущенный на самом устройстве (127.0.0.1:8781, при занятом порте — ошибка запуска без смены origin).
Никакого внешнего десктоп-сервера приложение не требует и не эмулирует.

- Статика (`index.html`, `ui.mjs`, `core.mjs`) лежит в `app/assets/` байт-в-байт
  из `frontend/` и отдаётся локально.
- `/api/routes/`, `/api/competitors/`, `/api/openapi.json` (только GET) проксируются
  на живой бэкенд `https://agile-sandbag-viewless.ngrok-free.dev` с заголовком
  `ngrok-skip-browser-warning: 1`, лимитом 2 МБ и проверкой JSON.
- POST/PUT и все остальные endpoint'ы возвращают 403 — запись на сервер
  заблокирована до появления авторизации и чтения результатов, как на десктопе.
- Тренировочный режим хранит результаты в localStorage устройства и ничего
  не отправляет на сервер.

Сборка: `./build.sh keystore.jks` (нужны JDK 17+, build-tools 34, platform android-34).
Тесты сервера: `app/test/ServerTest.java` (JVM, мок-бэкенд + живой API),
UI-прогон через headless Chrome описан в истории проверок.

Пакет `dev.bouldering.judge`, minSdk 26, targetSdk 34, versionCode 1 (0.1.0).
Ключ подписи в репозиторий не входит.


Android installation and launch are NOT verified yet. Local software emulator crashes before boot, and no physical Android device is attached. The APK is a candidate, not a ready delivery. Chrome screenshots are web UI evidence only, not Android screenshots. Native export uses the Android document picker and remains unverified on-device. Stable fixed port preserves localStorage across restarts. Navigation is restricted to the local origin, file/content access disabled, backup disabled. Signing material lives only in a separate private owner Drive archive and is excluded from this source backup.
