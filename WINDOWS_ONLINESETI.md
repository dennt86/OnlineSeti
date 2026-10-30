# OnlineSeti VPN для Windows

Windows-клиент собирается из общей кодовой базы, но использует отдельные имя, значок, настройки, updater и ссылки `onlineseti://`. Поддержка профилей опирается на движок sing-box и собранные вместе с ним транспорты; это не ограничивается протоколами, пришедшими из подписки.

## Сборка и публикация обновления

В GitHub Actions запустите workflow **OnlineSeti VPN for Windows** с версией, например `1.1.2`. При `publish=true` он соберёт GUI и ядро, проверит запуск упакованного ядра и updater, затем создаст GitHub Release с архивом `OnlineSetiVPN-<версия>-windows64.zip`. Для первичной проверки оставьте публикацию выключенной и скачайте workflow artifact.

Для in-app обновления важны три совпадения: в CMake задаётся `QTHRONE_REPOSITORY` как репозиторий, где опубликован Release; asset называется `OnlineSetiVPN-<версия>-windows64.zip`; внутри архива находится каталог `OnlineSetiVPN/` с `OnlineSetiVPN.exe`, `OnlineSetiCore.exe`, `OnlineSetiUpdater.exe` и остальными runtime-файлами. Updater извлекает этот каталог и заменяет файлы приложения после его завершения.

## Локальная сборка

Нужны те же Qt, MSVC, OpenSSL, Ninja и Go-зависимости, что и для Windows-сборки проекта. Перед CMake-конфигурацией скрипты сборки должны подготовить `build/srslist.h` и runtime-артефакты:

```powershell
$env:ONLINESETI_EDITION = "1"
cmake -S . -B build-onlineseti -DONLINESETI_EDITION=ON -DINPUT_VERSION=1.1.2
cmake --build build-onlineseti --config RelWithDebInfo
```

Без `ONLINESETI_EDITION` остаётся обычная редакция qThrone.
