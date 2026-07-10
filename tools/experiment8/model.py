from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Mapping


@dataclass(frozen=True, order=True, slots=True)
class TileKey:
    z: int
    x: int
    y: int

    def __post_init__(self) -> None:
        if not 0 <= self.z <= 29:
            raise ValueError(f"zoom out of range: {self.z}")
        limit = 1 << self.z
        if not 0 <= self.x < limit or not 0 <= self.y < limit:
            raise ValueError(f"tile out of range: {self.z}/{self.x}/{self.y}")

    @property
    def packed(self) -> int:
        return (self.z << 58) | (self.x << 29) | self.y

    @classmethod
    def from_packed(cls, value: int) -> "TileKey":
        if not 0 <= value < (1 << 63):
            raise ValueError(f"packed tile out of range: {value}")
        mask = (1 << 29) - 1
        return cls(z=(value >> 58) & 0x1F, x=(value >> 29) & mask, y=value & mask)

    def center_lon_lat(self) -> tuple[float, float]:
        scale = 1 << self.z
        lon = ((self.x + 0.5) / scale) * 360.0 - 180.0
        mercator_y = math.pi * (1.0 - 2.0 * (self.y + 0.5) / scale)
        lat = math.degrees(math.atan(math.sinh(mercator_y)))
        return lon, lat


@dataclass(frozen=True, slots=True)
class PopulationSummary:
    row_count: int
    counts_by_zoom: Mapping[int, int]
    sha256: str

    def __post_init__(self) -> None:
        counts = dict(sorted(self.counts_by_zoom.items()))
        if self.row_count < 0:
            raise ValueError(f"population row count cannot be negative: {self.row_count}")
        if any(zoom < 0 or zoom > 29 for zoom in counts):
            raise ValueError(f"population zoom out of range: {counts}")
        if any(count < 0 for count in counts.values()):
            raise ValueError(f"population count cannot be negative: {counts}")
        if sum(counts.values()) != self.row_count:
            raise ValueError(
                "population row count does not equal the sum of per-zoom counts: "
                f"{self.row_count} != {sum(counts.values())}"
            )
        object.__setattr__(self, "counts_by_zoom", MappingProxyType(counts))


@dataclass(frozen=True, slots=True)
class SourceLock:
    source_name: str
    service_url: str
    style_path: Path
    metadata_path: Path
    style_sha256: str
    metadata_sha256: str
    population_path: Path
    population: PopulationSummary
