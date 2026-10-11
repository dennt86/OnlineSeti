import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
version = json.loads((root / "version.json").read_text(encoding="utf-8"))
print("- Добавлены OpenFlux и olcRTC: импорт профилей, подключение, тесты задержки и скорости.")
print("- qWDTT, CSQTT, OpenFlux и olcRTC можно выбирать в правилах раздельной маршрутизации.")
print("- Ограничения IPv4 применяются к отдельным транспортам, сохраняя IPv6 для остальных выходов.")
print("- CSQTT и olcRTC сохраняют идентификатор устройства при обновлении подписки; тест CSQTT можно отменить.")
print("- В настройках интерфейса добавлено отключаемое украшение к Хэллоуину с анимацией.")
print("- Пароли и ключи в редакторах qWDTT, CSQTT, OpenFlux и olcRTC отображаются текстом.")
print()
print(f"Основа: Throne {version['throne_version']}, ветка dev, коммит [{version['throne_commit'][:8]}](https://github.com/throneproj/Throne/commit/{version['throne_commit']}).")
