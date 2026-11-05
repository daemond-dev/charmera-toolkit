"""The app's checker carries its own copy of the build identity; it must match charmera_toolkit/image.py."""
import re
from pathlib import Path

from charmera_toolkit import checksum, image

SRC = Path(__file__).resolve().parent.parent / "android" / "src" / "io" / "github" / "daemonddev" / "charmera" / "UpdateCheck.java"


def test_app_build_identity_matches_the_python_one():
    java = SRC.read_text(encoding="utf-8")
    block = java[java.index("ORIGINAL_2025 = new Build("): java.index(");", java.index("ORIGINAL_2025 = new Build("))]
    b = image.ORIGINAL_2025
    for text in (b.stamp, b.build_string.decode(), b.compiled.decode(), b.software_sha256, b.code_sha256, b.sensor_table_sha256):
        assert f'"{text}"' in block, text
    for number in (b.stamp_offset, b.build_offset, b.compiled_offset, *b.sensor_table):
        assert re.search(rf"0x{number:X}\b", block, re.I), hex(number)
    assert f"UPDATE_SIZE = 0x{checksum.UPDATE_FILE_SIZE:X}" in java
    assert f'STOCK_STAMP' not in java or checksum.STOCK_TIMESTAMP in java
