import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
version = json.loads((root / "version.json").read_text(encoding="utf-8"))
print("- Добавлен olcRTC: Jitsi по умолчанию, Телемост и WB Stream, поддерживаемые транспорты и импорт ссылок.")
print("- olcRTC доступен для основного соединения, правил маршрутизации, тестов задержки и скорости.")
print("- Идентификатор устройства сохраняется при обновлении подписки и повторном импорте olcRTC.")
print("- Пароли и ключи в редакторах qWDTT, CSQTT, OpenFlux и olcRTC отображаются текстом.")
print()
print(f"Основа: Throne {version['throne_version']}, ветка dev, коммит [{version['throne_commit'][:8]}](https://github.com/throneproj/Throne/commit/{version['throne_commit']}).")
