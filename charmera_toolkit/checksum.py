"""The SD updater's filename convention.

The camera scans the card root for `JH_5408<YYYYMMDDhhmm>_<8 hex>.bin`. The eight hex digits are the
unsigned 32-bit sum of every byte in the file (verified against all three published update files and
against the Generalplus SDK source the updater descends from). The twelve digits are a build timestamp;
the updater only runs when they differ from the stamp compiled into the running firmware. That stamp is
`202505201029` in both builds known so far (the public dump and the film-mods author's build, which differ
in their bytes), so it tells a camera to flash or not; it does not say which build a camera runs. Nothing
else in the name or the file is validated.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

PRODUCT_PREFIX = "JH_5408"
STOCK_TIMESTAMP = "202505201029"   # the stamp both known builds carry; a name with it is ignored by either
UPDATE_FILE_SIZE = 0x1E0000  # 30 blocks of 64 KiB: the size the published community update files use
OFFICIAL_UPDATE_SIZE = 0x169000  # where official updates and the film-mods files end: code and resources only
FLASH_SIZE = 0x200000
BLOCK_SIZE = 0x10000
PAGE_SIZE = 0x100

# [0-9], not \d: \d also matches Arabic-Indic, full-width and other Unicode digits, and the camera reads
# fixed ASCII character positions.
_NAME_RE = re.compile(r"^JH_(?P<product>[0-9]{4})(?P<stamp>[0-9]{12})_(?P<sum>[0-9A-Fa-f]{8})\.bin$")
_STAMP_RE = re.compile(r"[0-9]{12}")


def byte_sum(data: bytes) -> int:
    """Unsigned 32-bit sum of all bytes, the only integrity check the updater performs."""
    return sum(data) & 0xFFFFFFFF


@dataclass(frozen=True)
class UpdateName:
    product: str
    stamp: str
    checksum: int

    @property
    def filename(self) -> str:
        return f"JH_{self.product}{self.stamp}_{self.checksum:08X}.bin"

    def stamp_datetime(self) -> Optional[datetime]:
        try:
            return datetime.strptime(self.stamp, "%Y%m%d%H%M")
        except ValueError:
            return None


def parse_update_name(name: str) -> UpdateName:
    """Split an update filename into its parts. Raises ValueError with the reason when it does not fit."""
    m = _NAME_RE.fullmatch(name)
    if not m:
        raise ValueError(
            f"{name!r} is not an update filename: expected JH_5408<12 digits>_<8 hex>.bin "
            "(the camera matches JH_5408*.bin and reads fixed character positions)"
        )
    return UpdateName(product=m.group("product"), stamp=m.group("stamp"), checksum=int(m.group("sum"), 16))


def now_stamp() -> str:
    """A fresh 12-digit stamp (UTC). Callers that print and write a name must compute it once."""
    return datetime.now(timezone.utc).strftime("%Y%m%d%H%M")


def build_update_name(data: bytes, stamp: Optional[str] = None, product: str = "5408") -> UpdateName:
    """Name a payload the way the camera expects. The stamp must differ from the running firmware's."""
    if stamp is None:
        stamp = now_stamp()
    if not _STAMP_RE.fullmatch(stamp):
        raise ValueError("stamp must be 12 ASCII digits 0-9, YYYYMMDDhhmm")
    if stamp == STOCK_TIMESTAMP:
        raise ValueError(
            f"stamp {STOCK_TIMESTAMP} equals the stock build stamp; the updater would ignore the file"
        )
    return UpdateName(product=product, stamp=stamp, checksum=byte_sum(data))
