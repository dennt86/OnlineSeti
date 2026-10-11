import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
version = json.loads((root / "version.json").read_text(encoding="utf-8"))
print("- IPv6 сохраняется для поддерживающих его выходов при совместной работе с qWDTT и CSQTT.")
print("- Сокращено ожидание недоступного CSQTT, исправлена остановка тестов.")
print("- Device ID CSQTT сохраняется при обновлении подписки и повторном импорте.")
print("- Добавлен OpenFlux: документы Яндекса, Mail.ru и Cups, импорт ссылок, тесты и отдельные выходы для маршрутизации.")
print()
print(f"Основа: Throne {version['throne_version']}, ветка dev, коммит [{version['throne_commit'][:8]}](https://github.com/throneproj/Throne/commit/{version['throne_commit']}).")
