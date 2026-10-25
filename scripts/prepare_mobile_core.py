import json
import re
import sys
from pathlib import Path


def prepare(core: Path):
    lock = json.loads((Path(__file__).resolve().parents[1] / "core-dependencies.json").read_text())
    mod_path, sum_path = core / "go.mod", core / "go.sum"
    mod, sums = mod_path.read_text(), sum_path.read_text()
    for module, dependency in lock.items():
        pattern = rf"(?m)^(\s*{re.escape(module)}\s+)(\S+)(.*)$"
        match = re.search(pattern, mod)
        if not match or match[2] not in (dependency["from"], dependency["version"]):
            raise ValueError(f"Unexpected core dependency: {module}; review core-dependencies.json")
        mod = re.sub(pattern, lambda m: m[1] + dependency["version"] + m[3], mod, count=1)
        for suffix, key in (("", "sum"), ("/go.mod", "mod_sum")):
            prefix = f"{module} {dependency['version']}{suffix} "
            expected = prefix + dependency[key]
            existing = next((line for line in sums.splitlines() if line.startswith(prefix)), None)
            if existing is not None and existing != expected:
                raise ValueError(f"Unexpected checksum: {module}{suffix}")
            if existing is None:
                sums = sums.rstrip("\n") + "\n" + expected + "\n"
    mod_path.write_text(mod, newline="\n")
    sum_path.write_text(sums, newline="\n")


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))
