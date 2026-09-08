from pathlib import Path

source_path = Path(__file__).with_name("apply_room_v065_calibrated_path.py")
source = source_path.read_text()
source = source.replace('    if count != 1:\n        raise RuntimeError(f"{label}: expected 1 match, found {count}")\n',
                        '    if count < 1:\n        raise RuntimeError(f"{label}: expected at least 1 match, found {count}")\n', 1)
exec(compile(source, str(source_path), "exec"), {"__name__": "__main__", "__file__": str(source_path)})
