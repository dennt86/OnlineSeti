import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
version = json.loads((root / "version.json").read_text(encoding="utf-8"))
print("- Добавлено украшение «Новый год»; Хэллоуин и Новый год используют одинаковый размер и масштаб.")
print("- Украшение уступает место уведомлению о перезапуске и скрывается при нехватке места.")
print("- Исправлено отображение текста и ссылок в узком окне; таблица и журнал защищены от чрезмерного сжатия.")
print()
print(f"Основа: Throne {version['throne_version']}, ветка dev, коммит [{version['throne_commit'][:8]}](https://github.com/throneproj/Throne/commit/{version['throne_commit']}).")
