"""Create a source archive without build caches, generated assets or signing keys."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

project = Path(__file__).resolve().parents[1]
excluded = {".git", ".gradle", ".kotlin", ".cache", "build", "node_modules", "dist"}
output = project / "dist" / "pi-android-source.zip"
output.parent.mkdir(exist_ok=True)
with ZipFile(output, "w", ZIP_DEFLATED) as archive:
    for path in sorted(project.rglob("*")):
        relative = path.relative_to(project)
        if any(part in excluded for part in relative.parts) or not path.is_file():
            continue
        if path.name in {"local.properties", "pi-runtime.cjs", "build-info.json"}:
            continue
        if path.suffix in {".jks", ".hprof"}:
            continue
        archive.write(path, Path("android") / relative)
print(output)
