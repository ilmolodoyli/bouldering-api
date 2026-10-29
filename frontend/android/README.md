# Судейское Android-приложение 0.7.0 (тест)

Одобренная навигация: Турниры, Судейство, Чат, Управление, Профиль. Это тестовый каркас, не полноценное соревнование. Серверная роль неизвестна: нет authenticated session/me, поэтому пять разделов показаны с явными ограничениями, а не выдуманным локальнымRBAC.

## Работает локально
- DB участники/трассы/исходный счёт через GET. Компактный выбор спортсмена, стрелки предыдущий/следующий, трасса, попытки/зона/top.
- Автоматический следующий номер; ровно+1 на действие. Ручные правки только через «Исправить».
- Перед TOP подтверждение «Засчитать топ и зону?» с точной парой и числами. Отмена0изменений, подтверждение1запись. ПервыйTOP1/1/1. Ранняя зона не перезаписывается.
- Зона не toggle, снять только через Исправить. После каждого сохранения/исправления карточка закрывается к выбору следующего спортсмена.
- Попытки/зона/top/история на устройстве, НЕ на сервере. Тот же package/key/origin/storage key, предыдущая история остаётся, архив старой тренировки экспортируется в Профиле.
- При неизвестном server baseline новый ввод закрыт, cached локальные записи не стираются. Ошибки storage не выдаются за сохранение.
- GET турниров показывает title/stage, но не live-рейтинг/архивные протоколы.

## Заблокировано до server contract
Нет настоящего входа/me/RBAC/назначения трасс, защищённых server score writes с version/idempotency. Поэтому Управление/чат/личная статистика недоступны, регистрация не запрашивает пароль. Прежние create/delete операции ВРЕМЕННО закрыты в UI и native proxy; все неGET методы возвращают403. Это намеренное ограничение, не готовая безопасная админка. Никакого выбора роли, который выдаёт себя за права. Backend/main не менялись.

Проверка29Oct18:26: live OpenAPI и main.py всё ещё без auth enforcement; register задаётathlete, users/role/tournaments/stage существуют, но без caller guards. Route DELETE опечатка уже исправлена в backend source, сам DELETE не тестировался.

## Проверки и caveats
Integrated Chromium320/390:topconfirm/cancel,1/1/1, ранняяzone2/top3, исправление/снятие, перетрассовая изоляция/reload, no-scroll обычного ввода при844px, baseline500 блок, mutation0. Java native proxy проверяется на controlled backend; build/signature.
Native0.6 запуск/системные диалоги/реальный updater/data continuity на телефоне не проверены. Эмулятор ранее падал до старта, повторные попытки без нового сигнала не делаем. Увеличенный шрифт/низкая высота экрана могут потребовать scroll ради доступности. Прежде чем обновить, экспортируйте историю. Устанавливать поверх старого APK без удаления. Auto-update в Профиле, silent path условный, Android может запросить confirmation.

Source: https://agile-sandbag-viewless.ngrok-free.dev/openapi.json
https://raw.githubusercontent.com/ilmolodoyli/bouldering-api/main/main.py

Android8+/min26,target34,versionCode7. Ключ подписи не в исходниках; отдельный private backup.


## Registration in 0.7
Profile accepts a user-entered username and password only on an explicit submit. The native proxy permits exactly POST /auth/register with same-origin, Host, action header, content type, payload and destination guards. HTTPS is required outside the loopback controlled fixture; redirects are rejected. Response201 must contain the exact positive ID, matching username and initial athlete role. Only ID/username/initial role are stored as a receipt, never as authentication. Password is not stored, exported or logged; fields clear on submit, tab leave and background. WebView form saving/autofill are disabled. Password length is capped at 72 UTF-8 bytes for the published bcrypt registration handler.

No real accounts were created during validation. Controlled tests cover201 receipt, duplicate names, bad replies, uncertain failure, redirect rejection and all other writes remaining gated. A timeout or ambiguous response must not be automatically retried. Login/session/me and authenticated role verification remain unimplemented; public OpenAPI advertises login and user lookup without a session/token contract. Receipt ID is not authority to show private organizer data.

Profile action buttons have a14px vertical gap. Existing scoring behavior and unsent storage keys remain unchanged. Android runtime/install/upgrade are not verified for this build.
