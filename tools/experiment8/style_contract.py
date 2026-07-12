from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
import threading
import unicodedata
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP, localcontext
from enum import Enum, IntEnum
from pathlib import Path
from types import MappingProxyType
from typing import Callable, Mapping, Sequence

from . import semantic_policy
from .reference_presentation_policy import (
    LABEL_ACTIVE_BAND_LIMIT,
    LINE_LABEL_REPEAT_SPACING_PX,
    MAX_LINE_LABEL_BEND_CENTI_DEGREES,
    PRESENTATION_POLICY_SHA256,
    SemanticSubtype,
)
from .semantic_model import (
    FeatureKind,
    LandEvidence,
    LayerGroup,
    PlacementSourceKind,
    ProtectedStatus,
)


PINNED_STYLE_PATH = Path(
    r"D:\FlightAlert-test-artifacts\experiment 6\phase1\world-basemap-v2-source-lock"
    r"\World_Basemap_v2-root-style.json"
)
PINNED_STYLE_SHA256 = "92cec535724bebd560ce18ba47f5ddbc803e9bef61d8450bd24098f941276c5b"
PINNED_STYLE_LENGTH = 783_960
PINNED_LAYER_COUNT = 916
PINNED_SOURCE_LAYER_COUNT = 115
PINNED_TYPE_COUNTS = (("fill", 64), ("line", 74), ("symbol", 778))

DEFAULT_MIN_ZOOM_CENTI = 0
DEFAULT_MAX_ZOOM_CENTI = 2400
REFERENCE_COLLISION_GROUP = 1

_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_RGBA_RE = re.compile(
    r"^rgba\((\d{1,3}),(\d{1,3}),(\d{1,3}),([0-9]+(?:\.[0-9]+)?)\)$"
)
_TEXT_TEMPLATE_RE = re.compile(r"^\{(_name|_name_global)\}$")
_MISSING = object()


class StyleContractError(ValueError):
    """Pinned Style v8 input cannot satisfy the Flight Alert contract."""


class UnsupportedStyleError(StyleContractError):
    """An included rule contains an unimplemented expression or field."""


def _require_sha256(value: object, label: str) -> str:
    if type(value) is not str or _SHA256_RE.fullmatch(value) is None:
        raise StyleContractError(f"{label} must be 64 lowercase hexadecimal digits")
    return value


def _canonical_text(value: object, label: str) -> str:
    if type(value) is not str:
        raise StyleContractError(f"{label} must be text")
    if unicodedata.normalize("NFC", value) != value:
        raise StyleContractError(f"{label} must already be NFC")
    try:
        value.encode("utf-8", "strict")
    except UnicodeEncodeError as error:
        raise StyleContractError(f"{label} is not valid UTF-8") from error
    return value


def _decimal(value: object, label: str) -> Decimal:
    if type(value) is bool:
        raise StyleContractError(f"{label} must be numeric, not Boolean")
    if type(value) is float:
        raise StyleContractError(f"{label} cannot use a binary float")
    if type(value) is int:
        return Decimal(value)
    if type(value) is Decimal:
        result = value
    elif type(value) is str:
        try:
            result = Decimal(value)
        except InvalidOperation as error:
            raise StyleContractError(f"{label} is not a decimal number") from error
    else:
        raise StyleContractError(f"{label} must be an integer, Decimal, or decimal string")
    if not result.is_finite():
        raise StyleContractError(f"{label} must be finite")
    return result


def _round_decimal(value: Decimal) -> int:
    return int(value.to_integral_value(rounding=ROUND_HALF_UP))


def _fixed_milli(value: object, label: str) -> int:
    result = _round_decimal(_decimal(value, label) * 1000)
    if not -(1 << 63) <= result < 1 << 63:
        raise StyleContractError(f"{label} fixed value exceeds signed 64-bit")
    return result


def _centizoom(value: object, label: str) -> int:
    result = _round_decimal(_decimal(value, label) * 100)
    if not 0 <= result <= 10_000:
        raise StyleContractError(f"{label} centizoom is outside [0, 10000]")
    return result


def _provider_decizoom(value: object, label: str) -> int:
    result = _round_decimal(_decimal(value, label) * 10)
    if not 0 <= result <= 10_000:
        raise StyleContractError(f"{label} provider decizoom is outside [0, 10000]")
    return result


@dataclass(frozen=True, slots=True)
class FixedNumber:
    milli: int

    def __post_init__(self) -> None:
        if type(self.milli) is not int or not -(1 << 63) <= self.milli < 1 << 63:
            raise StyleContractError("fixed number must be a signed 64-bit integer")

    def document(self) -> dict[str, object]:
        return {"kind": "fixed_milli", "value": self.milli}


@dataclass(frozen=True, slots=True)
class ColorValue:
    argb: int

    def __post_init__(self) -> None:
        if type(self.argb) is not int or not 0 <= self.argb <= 0xFFFFFFFF:
            raise StyleContractError("color must be canonical ARGB u32")

    def document(self) -> dict[str, object]:
        return {"argb": self.argb, "kind": "color_argb"}


@dataclass(frozen=True, slots=True)
class FixedTuple:
    values_milli: tuple[int, ...]

    def __post_init__(self) -> None:
        if type(self.values_milli) is not tuple or not self.values_milli:
            raise StyleContractError("fixed tuple must be a nonempty immutable tuple")
        if any(type(value) is not int for value in self.values_milli):
            raise StyleContractError("fixed tuple entries must be exact integers")

    def document(self) -> dict[str, object]:
        return {"kind": "fixed_tuple_milli", "values": list(self.values_milli)}


@dataclass(frozen=True, slots=True)
class StringValue:
    value: str

    def __post_init__(self) -> None:
        _canonical_text(self.value, "style string")

    def document(self) -> dict[str, object]:
        return {"kind": "string", "value": self.value}


@dataclass(frozen=True, slots=True)
class StringTuple:
    values: tuple[str, ...]

    def __post_init__(self) -> None:
        if type(self.values) is not tuple or not self.values:
            raise StyleContractError("string tuple must be nonempty")
        for value in self.values:
            _canonical_text(value, "style string tuple entry")

    def document(self) -> dict[str, object]:
        return {"kind": "string_tuple", "values": list(self.values)}


@dataclass(frozen=True, slots=True)
class BooleanValue:
    value: bool

    def __post_init__(self) -> None:
        if type(self.value) is not bool:
            raise StyleContractError("style Boolean must be exact")

    def document(self) -> dict[str, object]:
        return {"kind": "boolean", "value": self.value}


@dataclass(frozen=True, slots=True)
class TextTemplate:
    source_field: str

    def __post_init__(self) -> None:
        if self.source_field not in {"_name", "_name_global"}:
            raise UnsupportedStyleError(
                "included labels require one exact text-field {_name} or {_name_global}"
            )

    def document(self) -> dict[str, object]:
        return {"kind": "text_field", "sourceField": self.source_field}


AtomicStyleValue = (
    FixedNumber
    | ColorValue
    | FixedTuple
    | StringValue
    | StringTuple
    | BooleanValue
    | TextTemplate
)


@dataclass(frozen=True, slots=True)
class StyleStop:
    input_centi: int | None
    input_milli: int | None
    input_value: object | None
    output: AtomicStyleValue

    def document(self) -> dict[str, object]:
        return {
            "inputCenti": self.input_centi,
            "inputMilli": self.input_milli,
            "inputValue": self.input_value,
            "output": self.output.document(),
        }


@dataclass(frozen=True, slots=True)
class StyleFunction:
    function_type: str
    property_name: str | None
    base_milli: int
    stops: tuple[StyleStop, ...]
    default: AtomicStyleValue | None
    position_scale: int

    def __post_init__(self) -> None:
        if self.function_type not in {"categorical", "interval", "exponential"}:
            raise UnsupportedStyleError("unsupported style function type")
        if type(self.base_milli) is not int or self.base_milli <= 0:
            raise StyleContractError("style function base must be positive")
        if type(self.stops) is not tuple or not self.stops:
            raise StyleContractError("style function requires at least one stop")
        if self.position_scale not in {1, 100, 1000}:
            raise StyleContractError("style function position scale is unknown")

    def document(self) -> dict[str, object]:
        return {
            "baseMilli": self.base_milli,
            "default": None if self.default is None else self.default.document(),
            "functionType": self.function_type,
            "positionScale": self.position_scale,
            "propertyName": self.property_name,
            "stops": [stop.document() for stop in self.stops],
        }


CompiledStyleValue = AtomicStyleValue | StyleFunction


_NUMERIC_FIELDS = frozenset(
    {
        "fill-opacity",
        "icon-padding",
        "icon-rotate",
        "icon-size",
        "line-opacity",
        "line-width",
        "symbol-spacing",
        "text-halo-width",
        "text-letter-spacing",
        "text-line-height",
        "text-max-angle",
        "text-max-width",
        "text-opacity",
        "text-padding",
        "text-size",
    }
)
_COLOR_FIELDS = frozenset(
    {
        "fill-color",
        "fill-outline-color",
        "line-color",
        "text-color",
        "text-halo-color",
    }
)
_FIXED_TUPLE_FIELDS = frozenset(
    {"fill-translate", "line-dasharray", "text-offset"}
)
_STRING_TUPLE_FIELDS = frozenset({"text-font"})
_BOOLEAN_FIELDS = frozenset(
    {
        "fill-antialias",
        "icon-allow-overlap",
        "symbol-avoid-edges",
        "text-keep-upright",
        "text-optional",
    }
)
_STRING_FIELDS = frozenset(
    {
        "fill-pattern",
        "fill-translate-anchor",
        "icon-image",
        "icon-rotation-alignment",
        "line-cap",
        "line-join",
        "symbol-placement",
        "text-anchor",
        "text-justify",
        "text-rotation-alignment",
        "text-transform",
        "visibility",
    }
)
_EXPRESSION_HEADS = frozenset(
    {"case", "coalesce", "concat", "format", "get", "interpolate", "literal", "match", "step", "zoom"}
)


def _color(value: object, label: str) -> ColorValue:
    text = _canonical_text(value, label)
    if re.fullmatch(r"#[0-9a-fA-F]{6}", text):
        return ColorValue(0xFF000000 | int(text[1:], 16))
    match = _RGBA_RE.fullmatch(text)
    if match is None:
        raise UnsupportedStyleError(f"{label} has an unsupported color grammar")
    red, green, blue = (int(match.group(index)) for index in range(1, 4))
    if any(value > 255 for value in (red, green, blue)):
        raise StyleContractError(f"{label} RGB component exceeds 255")
    alpha_decimal = _decimal(match.group(4), f"{label} alpha")
    if not Decimal(0) <= alpha_decimal <= Decimal(1):
        raise StyleContractError(f"{label} alpha is outside [0, 1]")
    alpha = _round_decimal(alpha_decimal * 255)
    return ColorValue((alpha << 24) | (red << 16) | (green << 8) | blue)


def _compile_atomic(field_name: str, value: object) -> AtomicStyleValue:
    if field_name in _NUMERIC_FIELDS:
        return FixedNumber(_fixed_milli(value, field_name))
    if field_name in _COLOR_FIELDS:
        return _color(value, field_name)
    if field_name in _FIXED_TUPLE_FIELDS:
        if type(value) is not list or not value:
            raise StyleContractError(f"{field_name} must be a nonempty numeric array")
        if value and type(value[0]) is str and value[0] in _EXPRESSION_HEADS:
            raise UnsupportedStyleError(f"unsupported expression in {field_name}")
        return FixedTuple(
            tuple(_fixed_milli(item, f"{field_name} item") for item in value)
        )
    if field_name in _STRING_TUPLE_FIELDS:
        if type(value) is not list or not value:
            raise StyleContractError(f"{field_name} must be a nonempty string array")
        return StringTuple(
            tuple(_canonical_text(item, f"{field_name} item") for item in value)
        )
    if field_name in _BOOLEAN_FIELDS:
        if type(value) is not bool:
            raise StyleContractError(f"{field_name} must be Boolean")
        return BooleanValue(value)
    if field_name in _STRING_FIELDS:
        return StringValue(_canonical_text(value, field_name))
    if field_name == "text-field":
        text = _canonical_text(value, "text-field")
        match = _TEXT_TEMPLATE_RE.fullmatch(text)
        if match is None:
            raise UnsupportedStyleError(
                "included labels require one exact text-field {_name} or {_name_global}"
            )
        return TextTemplate(match.group(1))
    raise UnsupportedStyleError(f"unsupported included style field {field_name!r}")


def _is_interpolable(value: AtomicStyleValue) -> bool:
    return isinstance(value, (FixedNumber, ColorValue, FixedTuple))


def compile_style_value(field_name: str, value: object) -> CompiledStyleValue:
    _canonical_text(field_name, "style field name")
    if type(value) is not dict:
        if type(value) is list and value and type(value[0]) is str and value[0] in _EXPRESSION_HEADS:
            raise UnsupportedStyleError(f"unsupported expression in {field_name}")
        return _compile_atomic(field_name, value)

    allowed_keys = {"base", "default", "property", "stops", "type"}
    unknown_keys = set(value).difference(allowed_keys)
    if unknown_keys:
        raise UnsupportedStyleError(
            f"unsupported style function keys {sorted(unknown_keys)!r} in {field_name}"
        )
    stops_value = value.get("stops")
    if type(stops_value) is not list or not stops_value:
        raise StyleContractError(f"{field_name} style function requires stops")
    property_name_value = value.get("property")
    property_name = None
    if property_name_value is not None:
        property_name = _canonical_text(property_name_value, "style function property")
        if property_name not in semantic_policy.RETAINED_RAW_PROPERTIES:
            raise UnsupportedStyleError(
                f"unsupported included feature-property function {property_name!r}"
            )
    explicit_type = value.get("type")
    if explicit_type is not None:
        function_type = _canonical_text(explicit_type, "style function type")
        if function_type not in {"categorical", "interval", "exponential"}:
            raise UnsupportedStyleError(
                f"unsupported style function type {function_type!r}"
            )
    else:
        function_type = ""

    base_milli = _fixed_milli(value.get("base", 1), "style function base")
    if base_milli <= 0:
        raise StyleContractError("style function base must be positive")
    compiled_stops: list[StyleStop] = []
    comparable_inputs: list[object] = []
    for index, item in enumerate(stops_value):
        if type(item) is not list or len(item) != 2:
            raise StyleContractError(f"{field_name} stop {index} must be [input, output]")
        raw_input, raw_output = item
        output = _compile_atomic(field_name, raw_output)
        if function_type == "categorical":
            if type(raw_input) not in {str, int, bool}:
                raise StyleContractError("categorical stop input has an unsupported type")
            input_value = raw_input
            input_centi = None
            input_milli = None
            comparable = (type(raw_input).__name__, raw_input)
        elif property_name is None:
            input_centi = _centizoom(raw_input, f"{field_name} stop zoom")
            input_milli = None
            input_value = None
            comparable = input_centi
        else:
            input_milli = _fixed_milli(raw_input, f"{field_name} property stop")
            input_centi = None
            input_value = None
            comparable = input_milli
        compiled_stops.append(
            StyleStop(input_centi, input_milli, input_value, output)
        )
        comparable_inputs.append(comparable)

    if not function_type:
        function_type = (
            "exponential"
            if all(_is_interpolable(stop.output) for stop in compiled_stops)
            else "interval"
        )
    if function_type != "categorical":
        if any(
            comparable_inputs[index] >= comparable_inputs[index + 1]
            for index in range(len(comparable_inputs) - 1)
        ):
            raise StyleContractError("style function stops must be strictly increasing")
    elif len(set(comparable_inputs)) != len(comparable_inputs):
        raise StyleContractError("categorical style function stop inputs must be unique")

    if function_type == "exponential":
        output_type = type(compiled_stops[0].output)
        if not _is_interpolable(compiled_stops[0].output) or any(
            type(stop.output) is not output_type for stop in compiled_stops
        ):
            raise UnsupportedStyleError(
                "exponential style functions require one interpolable output type"
            )
        if output_type is FixedTuple:
            lengths = {
                len(stop.output.values_milli)  # type: ignore[union-attr]
                for stop in compiled_stops
            }
            if len(lengths) != 1:
                raise StyleContractError(
                    "fixed-tuple style function outputs must have equal lengths"
                )

    default_value = value.get("default", _MISSING)
    default = (
        None
        if default_value is _MISSING
        else _compile_atomic(field_name, default_value)
    )
    position_scale = 1 if function_type == "categorical" else (100 if property_name is None else 1000)
    return StyleFunction(
        function_type=function_type,
        property_name=property_name,
        base_milli=base_milli,
        stops=tuple(compiled_stops),
        default=default,
        position_scale=position_scale,
    )


def _interpolation_factor(
    position: int,
    start: int,
    end: int,
    *,
    base_milli: int,
    position_scale: int,
) -> Decimal:
    if end <= start:
        raise StyleContractError("style interpolation interval is empty")
    if position <= start:
        return Decimal(0)
    if position >= end:
        return Decimal(1)
    numerator = Decimal(position - start)
    denominator = Decimal(end - start)
    base = Decimal(base_milli) / Decimal(1000)
    if base == 1:
        return numerator / denominator
    with localcontext() as context:
        context.prec = 50
        scaled_numerator = numerator / Decimal(position_scale)
        scaled_denominator = denominator / Decimal(position_scale)
        return (context.power(base, scaled_numerator) - 1) / (
            context.power(base, scaled_denominator) - 1
        )


def _interpolate_atomic(
    start: AtomicStyleValue, end: AtomicStyleValue, factor: Decimal
) -> AtomicStyleValue:
    if type(start) is not type(end):
        raise StyleContractError("style interpolation output types differ")
    if isinstance(start, FixedNumber) and isinstance(end, FixedNumber):
        return FixedNumber(
            _round_decimal(
                Decimal(start.milli)
                + Decimal(end.milli - start.milli) * factor
            )
        )
    if isinstance(start, ColorValue) and isinstance(end, ColorValue):
        components: list[int] = []
        for shift in (24, 16, 8, 0):
            first = (start.argb >> shift) & 0xFF
            second = (end.argb >> shift) & 0xFF
            components.append(
                _round_decimal(Decimal(first) + Decimal(second - first) * factor)
            )
        return ColorValue(
            (components[0] << 24)
            | (components[1] << 16)
            | (components[2] << 8)
            | components[3]
        )
    if isinstance(start, FixedTuple) and isinstance(end, FixedTuple):
        if len(start.values_milli) != len(end.values_milli):
            raise StyleContractError("style tuple interpolation lengths differ")
        return FixedTuple(
            tuple(
                _round_decimal(Decimal(first) + Decimal(second - first) * factor)
                for first, second in zip(start.values_milli, end.values_milli)
            )
        )
    raise UnsupportedStyleError("style function output cannot be interpolated")


def resolve_style_value(
    value: CompiledStyleValue,
    *,
    zoom_centi: int | None = None,
    properties: Mapping[str, object] | None = None,
) -> AtomicStyleValue:
    if not isinstance(value, StyleFunction):
        return value
    properties = {} if properties is None else properties
    if value.function_type == "categorical":
        if value.property_name is None:
            raise StyleContractError("categorical function requires a property")
        raw = properties.get(value.property_name, _MISSING)
        if raw is _MISSING:
            if value.default is not None:
                return value.default
            raise StyleContractError(
                f"missing required property {value.property_name!r}"
            )
        for stop in value.stops:
            if type(raw) is type(stop.input_value) and raw == stop.input_value:
                return stop.output
        if value.default is not None:
            return value.default
        raise StyleContractError(
            f"categorical property {value.property_name!r} has no accepted stop"
        )

    if value.property_name is None:
        if type(zoom_centi) is not int:
            raise StyleContractError("zoom style function requires integer centizoom")
        position = zoom_centi
        positions = [stop.input_centi for stop in value.stops]
    else:
        raw = properties.get(value.property_name, _MISSING)
        if raw is _MISSING:
            if value.default is not None:
                return value.default
            raise StyleContractError(
                f"missing required property {value.property_name!r}"
            )
        position = _fixed_milli(raw, value.property_name)
        positions = [stop.input_milli for stop in value.stops]
    if any(item is None for item in positions):
        raise StyleContractError("style function position kind is inconsistent")
    exact_positions = [int(item) for item in positions]
    if position <= exact_positions[0]:
        return value.stops[0].output
    if position >= exact_positions[-1]:
        return value.stops[-1].output
    upper = next(
        index for index, item in enumerate(exact_positions) if position < item
    )
    lower = upper - 1
    if value.function_type == "interval":
        return value.stops[lower].output
    factor = _interpolation_factor(
        position,
        exact_positions[lower],
        exact_positions[upper],
        base_milli=value.base_milli,
        position_scale=value.position_scale,
    )
    return _interpolate_atomic(
        value.stops[lower].output, value.stops[upper].output, factor
    )


@dataclass(frozen=True, slots=True)
class CompiledFilter:
    operator: str
    property_name: str | None = None
    values: tuple[object, ...] = ()
    children: tuple["CompiledFilter", ...] = ()

    @property
    def operators(self) -> tuple[str, ...]:
        found = {self.operator}
        for child in self.children:
            found.update(child.operators)
        found.discard("true")
        return tuple(sorted(found))

    @property
    def property_names(self) -> tuple[str, ...]:
        found = set()
        if self.property_name is not None:
            found.add(self.property_name)
        for child in self.children:
            found.update(child.property_names)
        return tuple(sorted(found))

    def matches(self, properties: Mapping[str, object]) -> bool:
        if self.operator == "true":
            return True
        if self.operator == "all":
            return all(child.matches(properties) for child in self.children)
        assert self.property_name is not None
        raw = properties.get(self.property_name, _MISSING)
        if self.operator == "==":
            return raw is not _MISSING and type(raw) is type(self.values[0]) and raw == self.values[0]
        if self.operator == "in":
            return raw is not _MISSING and any(
                type(raw) is type(value) and raw == value for value in self.values
            )
        if self.operator == "!in":
            return raw is _MISSING or not any(
                type(raw) is type(value) and raw == value for value in self.values
            )
        raise StyleContractError("compiled filter has an unknown operator")

    def document(self) -> object:
        if self.operator == "true":
            return True
        if self.operator == "all":
            return ["all", *[child.document() for child in self.children]]
        return [self.operator, self.property_name, *self.values]


def compile_filter(expression: object) -> CompiledFilter:
    if expression is None:
        return CompiledFilter("true")
    if type(expression) is not list or not expression:
        raise UnsupportedStyleError("style filter must be an operator array")
    operator = expression[0]
    if type(operator) is not str:
        raise UnsupportedStyleError("style filter operator must be text")
    if operator == "all":
        if len(expression) < 2:
            raise StyleContractError("all filter requires at least one child")
        return CompiledFilter(
            "all", children=tuple(compile_filter(child) for child in expression[1:])
        )
    if operator not in {"==", "in", "!in"}:
        raise UnsupportedStyleError(f"unsupported filter operator {operator!r}")
    minimum_length = 3
    if len(expression) < minimum_length or (operator == "==" and len(expression) != 3):
        raise StyleContractError(f"{operator} filter has invalid arity")
    property_name = _canonical_text(expression[1], "filter property")
    if property_name not in semantic_policy.RETAINED_RAW_PROPERTIES:
        raise UnsupportedStyleError(
            f"unsupported included filter property {property_name!r}"
        )
    values = tuple(expression[2:])
    for value in values:
        if type(value) not in {str, int, bool}:
            raise StyleContractError("filter literal has an unsupported type")
        if type(value) is str:
            _canonical_text(value, "filter string literal")
    return CompiledFilter(operator, property_name=property_name, values=values)


def combine_zoom_interval(
    style_min_zoom: object,
    style_max_zoom: object,
    properties: Mapping[str, object],
) -> tuple[int, int]:
    minimum = _centizoom(style_min_zoom, "style minimum zoom")
    maximum = _centizoom(style_max_zoom, "style maximum zoom")
    if "_minzoom" in properties:
        minimum = max(
            minimum,
            _provider_decizoom(properties["_minzoom"], "provider _minzoom"),
        )
    if "_maxzoom" in properties:
        maximum = min(
            maximum,
            _provider_decizoom(properties["_maxzoom"], "provider _maxzoom"),
        )
    if minimum >= maximum:
        raise StyleContractError("combined half-open zoom interval is empty")
    return minimum, maximum


def zoom_interval_contains(interval: tuple[int, int], zoom_centi: int) -> bool:
    if (
        type(interval) is not tuple
        or len(interval) != 2
        or any(type(value) is not int for value in interval)
        or type(zoom_centi) is not int
    ):
        raise StyleContractError("zoom containment requires exact integer centizoom")
    return interval[0] <= zoom_centi < interval[1]


_BOUNDARY_CASING_STYLE_IDS = frozenset(
    {
        "Boundary line/Admin0/casing",
        "Boundary line/Admin1/casing",
        "Boundary line/Admin2/casing",
    }
)
_BOUNDARY_INNER_STYLE_IDS = frozenset(
    {
        "Boundary line/Admin0/line",
        "Boundary line/Admin1/line",
        "Boundary line/Admin2/line",
        "Boundary line/Admin3",
        "Boundary line/Admin4",
        "Boundary line/Admin5",
        "Boundary line/Disputed admin0",
        "Boundary line/Disputed admin1",
        "Boundary line/Disputed admin2",
        "Boundary line/Disputed admin3",
        "Boundary line/Disputed admin4",
        "Boundary line/Disputed admin5",
    }
)
_ROAD_STROKE_NAMES = (
    "4WD",
    "Service",
    "Local",
    "Minor",
    "Minor, ramp or traffic circle",
    "Major",
    "Major, ramp or traffic circle",
    "Highway",
    "Freeway Motorway",
    "Freeway Motorway Highway, ramp or traffic circle",
)
_ROAD_CASING_STYLE_IDS = frozenset(
    f"{source}/{name}/casing"
    for source in ("Road", "Road tunnel")
    for name in _ROAD_STROKE_NAMES
)
_ROAD_INNER_STYLE_IDS = frozenset(
    f"{source}/{name}/line"
    for source in ("Road", "Road tunnel")
    for name in _ROAD_STROKE_NAMES
)
_CASING_STYLE_IDS = frozenset(
    set(_BOUNDARY_CASING_STYLE_IDS)
    | set(_ROAD_CASING_STYLE_IDS)
    | {"Ferry/Rail ferry/casing", "Railroad/casing"}
)
_INNER_STYLE_IDS = frozenset(
    set(_BOUNDARY_INNER_STYLE_IDS)
    | set(_ROAD_INNER_STYLE_IDS)
    | {"Ferry/Rail ferry/line", "Railroad/line"}
)
_OVERLAY_STYLE_IDS = frozenset({"Railroad/symbol"})


def _stroke_role(style_layer_id: str) -> str:
    if style_layer_id in _CASING_STYLE_IDS:
        return "casing"
    if style_layer_id in _INNER_STYLE_IDS:
        return "inner"
    if style_layer_id in _OVERLAY_STYLE_IDS:
        return "overlay"
    return "single"


@dataclass(frozen=True, slots=True)
class LabelStyle:
    text_source_fields: tuple[str, ...]
    collision_group: int
    fade_in_centi: int
    fade_out_centi: int
    whole_text: bool = True
    per_glyph_record_count: int = 0

    def __post_init__(self) -> None:
        if (
            type(self.text_source_fields) is not tuple
            or not self.text_source_fields
            or len(set(self.text_source_fields)) != len(self.text_source_fields)
            or any(
                field not in {"_name", "_name_global"}
                for field in self.text_source_fields
            )
        ):
            raise StyleContractError("label source fields are not canonical")
        for value, label in (
            (self.collision_group, "collision group"),
            (self.fade_in_centi, "fade in"),
            (self.fade_out_centi, "fade out"),
            (self.per_glyph_record_count, "per-glyph count"),
        ):
            if type(value) is not int or value < 0:
                raise StyleContractError(f"label {label} must be nonnegative")
        if self.collision_group == 0:
            raise StyleContractError("label collision group must be nonzero")
        if self.whole_text is not True or self.per_glyph_record_count != 0:
            raise StyleContractError("label template must retain one whole text run")

    def document(self) -> dict[str, object]:
        return {
            "collisionGroup": self.collision_group,
            "fadeInCenti": self.fade_in_centi,
            "fadeOutCenti": self.fade_out_centi,
            "perGlyphRecordCount": self.per_glyph_record_count,
            "resolution": "current_centizoom_and_feature_properties",
            "textSourceFields": list(self.text_source_fields),
            "wholeText": self.whole_text,
        }


@dataclass(frozen=True, slots=True)
class ResolvedLabelStyle:
    text_source_field: str
    placement: str
    avoid_edges: bool
    keep_upright: bool
    repeat_distance_px: int
    font_families: tuple[str, ...]
    font_slant: str
    text_size_milli: int
    letter_spacing_milli_em: int
    max_width_milli_em: int
    max_angle_centi_degrees: int
    offset_milli_em: tuple[int, ...]
    color_argb: int
    halo_color_argb: int
    halo_width_milli_em: int
    opacity_milli: int
    fade_in_centi: int
    fade_out_centi: int
    collision_group: int
    visible: bool
    whole_text: bool = True
    per_glyph_record_count: int = 0

    def __post_init__(self) -> None:
        if self.text_source_field not in {"_name", "_name_global"}:
            raise StyleContractError("label source field is not accepted")
        if self.placement not in {"line", "point"}:
            raise StyleContractError("label placement is unsupported")
        for value, label in (
            (self.repeat_distance_px, "repeat distance"),
            (self.text_size_milli, "text size"),
            (self.letter_spacing_milli_em, "letter spacing"),
            (self.max_width_milli_em, "maximum width"),
            (self.max_angle_centi_degrees, "maximum angle"),
            (self.halo_width_milli_em, "halo width"),
            (self.fade_in_centi, "fade in"),
            (self.fade_out_centi, "fade out"),
            (self.collision_group, "collision group"),
            (self.per_glyph_record_count, "per-glyph count"),
        ):
            if type(value) is not int or value < 0:
                raise StyleContractError(f"label {label} must be a nonnegative integer")
        if not self.font_families:
            raise StyleContractError("label must retain at least one font")
        if self.font_slant not in {"normal", "italic"}:
            raise StyleContractError("label font slant is unknown")
        if not 0 <= self.opacity_milli <= 1000:
            raise StyleContractError("label opacity is outside [0, 1000]")
        if (
            type(self.avoid_edges) is not bool
            or type(self.keep_upright) is not bool
            or type(self.visible) is not bool
        ):
            raise StyleContractError("label visibility flags must be Boolean")
        if self.whole_text is not True or self.per_glyph_record_count != 0:
            raise StyleContractError("line labels must remain one whole shaped text run")

    def document(self) -> dict[str, object]:
        return {
            "avoidEdges": self.avoid_edges,
            "collisionGroup": self.collision_group,
            "colorArgb": self.color_argb,
            "fadeInCenti": self.fade_in_centi,
            "fadeOutCenti": self.fade_out_centi,
            "fontFamilies": list(self.font_families),
            "fontSlant": self.font_slant,
            "haloColorArgb": self.halo_color_argb,
            "haloWidthMilliEm": self.halo_width_milli_em,
            "keepUpright": self.keep_upright,
            "letterSpacingMilliEm": self.letter_spacing_milli_em,
            "maxAngleCentiDegrees": self.max_angle_centi_degrees,
            "maxWidthMilliEm": self.max_width_milli_em,
            "offsetMilliEm": list(self.offset_milli_em),
            "opacityMilli": self.opacity_milli,
            "perGlyphRecordCount": self.per_glyph_record_count,
            "placement": self.placement,
            "repeatDistancePx": self.repeat_distance_px,
            "textSizeMilli": self.text_size_milli,
            "textSourceField": self.text_source_field,
            "visible": self.visible,
            "wholeText": self.whole_text,
        }


@dataclass(frozen=True, slots=True)
class ResolvedCandidateLabelStyle:
    text_source_field: str
    placement: str
    avoid_edges: bool
    keep_upright: bool
    visible: bool

    def __post_init__(self) -> None:
        if self.text_source_field not in {"_name", "_name_global"}:
            raise StyleContractError("candidate label source field is unsupported")
        if self.placement not in {"line", "point"}:
            raise StyleContractError("candidate label placement is unsupported")
        if any(
            type(value) is not bool
            for value in (self.avoid_edges, self.keep_upright, self.visible)
        ):
            raise StyleContractError("candidate label flags must be Boolean")


@dataclass(frozen=True, slots=True)
class LineStyle:
    style_layer_id: str
    stroke_role: str
    style_order: int

    def __post_init__(self) -> None:
        _canonical_text(self.style_layer_id, "line style-layer ID")
        if self.stroke_role not in {"casing", "inner", "overlay", "single"}:
            raise StyleContractError("line stroke role is unsupported")
        if type(self.style_order) is not int or self.style_order < 0:
            raise StyleContractError("line style order must be nonnegative")

    def document(self) -> dict[str, object]:
        return {
            "strokeRole": self.stroke_role,
            "styleLayerId": self.style_layer_id,
            "styleOrder": self.style_order,
        }


@dataclass(frozen=True, slots=True)
class AreaStyle:
    source_style_type: str = "fill"
    renderer_draw_mode: str = "polygon_outline"
    source_fill_evidence_retained: bool = True
    renders_provider_fill: bool = False

    def __post_init__(self) -> None:
        if self.source_style_type != "fill":
            raise StyleContractError("area source style must remain typed as fill")
        if self.renderer_draw_mode != "polygon_outline":
            raise StyleContractError("area renderer mode must be polygon outline")
        if self.source_fill_evidence_retained is not True:
            raise StyleContractError("area source fill evidence must be retained")
        if self.renders_provider_fill is not False:
            raise StyleContractError("reference overlay cannot reproduce provider fill")

    def document(self) -> dict[str, object]:
        return {
            "rendererDrawMode": self.renderer_draw_mode,
            "rendersProviderFill": self.renders_provider_fill,
            "sourceFillEvidenceRetained": self.source_fill_evidence_retained,
            "sourceStyleType": self.source_style_type,
        }


@dataclass(frozen=True, slots=True)
class CompiledLayerRule:
    style_layer_id: str
    style_order: int
    source_layer: str
    layer_group: LayerGroup
    feature_kind: FeatureKind
    semantic_subtype: int
    semantic_kind: str
    source_style_layer_ids: tuple[int, ...]
    render_style_token_ids: tuple[int, ...]
    compiled_filter: CompiledFilter
    layout_values: tuple[tuple[str, CompiledStyleValue], ...]
    paint_values: tuple[tuple[str, CompiledStyleValue], ...]
    min_zoom_centi: int
    max_zoom_centi: int
    fade_in_centi: int
    fade_out_centi: int
    draw_order: int
    semantic_collision_priority: int | None
    priority_basis: str
    retained_property_names: tuple[str, ...]
    raw_style_sha256: str
    semantic_policy_sha256: str
    style_policy_sha256: str
    label_style: LabelStyle | None
    line_style: LineStyle | None
    area_style: AreaStyle | None
    admin_level: int | None
    disputed: bool
    coastline: bool
    intermittent: bool
    tunnel: bool
    shield: bool
    one_way: bool
    land_evidence: LandEvidence
    protected_status: ProtectedStatus
    fallback_text_source_field: str | None
    inherited_style_layer_ids: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        _canonical_text(self.style_layer_id, "compiled style-layer ID")
        _canonical_text(self.source_layer, "compiled source layer")
        if type(self.layer_group) is not LayerGroup:
            raise StyleContractError("compiled rule group is unknown")
        if type(self.feature_kind) is not FeatureKind:
            raise StyleContractError("compiled rule feature kind is unknown")
        if not 0 <= self.min_zoom_centi < self.max_zoom_centi <= 10_000:
            raise StyleContractError("compiled rule zoom interval must be nonempty")
        _require_sha256(self.raw_style_sha256, "compiled raw style SHA-256")
        _require_sha256(
            self.semantic_policy_sha256, "compiled semantic policy SHA-256"
        )
        if self.semantic_policy_sha256 != semantic_policy.SEMANTIC_POLICY_SHA256:
            raise StyleContractError("compiled rule semantic policy SHA-256 drifted")
        _require_sha256(self.style_policy_sha256, "compiled style policy SHA-256")
        if (
            type(self.source_style_layer_ids) is not tuple
            or len(self.source_style_layer_ids) != 1
            or type(self.source_style_layer_ids[0]) is not int
            or self.source_style_layer_ids[0] <= 0
        ):
            raise StyleContractError("compiled rule source style identity is invalid")
        if (
            type(self.render_style_token_ids) is not tuple
            or len(self.render_style_token_ids) != 1
            or type(self.render_style_token_ids[0]) is not int
            or self.render_style_token_ids[0] <= 0
        ):
            raise StyleContractError("compiled rule render token identity is invalid")
        if self.semantic_collision_priority is not None:
            raise StyleContractError(
                "style compilation cannot fabricate feature prominence priority"
            )
        if self.priority_basis != "requires_feature_prominence_decision":
            raise StyleContractError("compiled priority basis is not fail-closed")
        if self.feature_kind is FeatureKind.LABEL:
            if (
                self.label_style is None
                or self.line_style is not None
                or self.area_style is not None
            ):
                raise StyleContractError("label rule style ownership is inconsistent")
        elif self.feature_kind is FeatureKind.LINE:
            if (
                self.line_style is None
                or self.label_style is not None
                or self.area_style is not None
            ):
                raise StyleContractError("line rule style ownership is inconsistent")
        elif (
            self.feature_kind is not FeatureKind.POLYGON_OUTLINE
            or self.area_style is None
            or self.label_style is not None
            or self.line_style is not None
        ):
            raise StyleContractError("polygon rule style ownership is inconsistent")
        if self.admin_level is not None and (
            type(self.admin_level) is not int or not 0 <= self.admin_level <= 5
        ):
            raise StyleContractError("compiled admin level is outside [0, 5]")
        for value in (
            self.disputed,
            self.coastline,
            self.intermittent,
            self.tunnel,
            self.shield,
            self.one_way,
        ):
            if type(value) is not bool:
                raise StyleContractError("compiled classification flags must be Boolean")
        if type(self.land_evidence) is not LandEvidence:
            raise StyleContractError("compiled land evidence is unknown")
        if type(self.protected_status) is not ProtectedStatus:
            raise StyleContractError("compiled protected status is unknown")
        if self.inherited_style_layer_ids:
            raise StyleContractError("source rules cannot inherit another style-layer identity")
        expected_policy_sha256 = _rule_policy_sha256(
            raw_style_sha256=self.raw_style_sha256,
            semantic_policy_sha256=self.semantic_policy_sha256,
            style_layer_id=self.style_layer_id,
            style_order=self.style_order,
            source_layer=self.source_layer,
            source_style_layer_ids=self.source_style_layer_ids,
            render_style_token_ids=self.render_style_token_ids,
            classification=self.classification(),
            compiled_filter=self.compiled_filter,
            layout_values=self.layout_values,
            paint_values=self.paint_values,
            min_zoom_centi=self.min_zoom_centi,
            max_zoom_centi=self.max_zoom_centi,
            fade_in_centi=self.fade_in_centi,
            fade_out_centi=self.fade_out_centi,
            draw_order=self.draw_order,
            semantic_collision_priority=self.semantic_collision_priority,
            priority_basis=self.priority_basis,
            retained_property_names=self.retained_property_names,
            label_style=self.label_style,
            line_style=self.line_style,
            area_style=self.area_style,
            fallback_text_source_field=self.fallback_text_source_field,
            inherited_style_layer_ids=self.inherited_style_layer_ids,
        )
        if self.style_policy_sha256 != expected_policy_sha256:
            raise StyleContractError(
                "style-policy SHA-256 does not bind compiled rule semantics"
            )

    def value(self, field_name: str) -> CompiledStyleValue | None:
        for key, value in self.layout_values + self.paint_values:
            if key == field_name:
                return value
        return None

    def document(self) -> dict[str, object]:
        return {
            "adminLevel": self.admin_level,
            "areaStyle": None if self.area_style is None else self.area_style.document(),
            "coastline": self.coastline,
            "drawOrder": self.draw_order,
            "disputed": self.disputed,
            "fallbackTextSourceField": self.fallback_text_source_field,
            "fadeInCenti": self.fade_in_centi,
            "fadeOutCenti": self.fade_out_centi,
            "featureKind": self.feature_kind.value,
            "filter": self.compiled_filter.document(),
            "inheritedStyleLayerIds": list(self.inherited_style_layer_ids),
            "intermittent": self.intermittent,
            "labelStyle": None if self.label_style is None else self.label_style.document(),
            "landEvidence": self.land_evidence.value,
            "layerGroup": self.layer_group.value,
            "layout": {key: value.document() for key, value in self.layout_values},
            "lineStyle": None if self.line_style is None else self.line_style.document(),
            "maxZoomCentiExclusive": self.max_zoom_centi,
            "minZoomCentiInclusive": self.min_zoom_centi,
            "paint": {key: value.document() for key, value in self.paint_values},
            "oneWay": self.one_way,
            "priorityBasis": self.priority_basis,
            "protectedStatus": self.protected_status.value,
            "renderStyleTokenIds": list(self.render_style_token_ids),
            "retainedPropertyNames": list(self.retained_property_names),
            "rawStyleSha256": self.raw_style_sha256,
            "semanticKind": self.semantic_kind,
            "semanticPolicySha256": self.semantic_policy_sha256,
            "semanticSubtype": self.semantic_subtype,
            "sourceLayer": self.source_layer,
            "sourceStyleLayerIds": list(self.source_style_layer_ids),
            "styleLayerId": self.style_layer_id,
            "styleOrder": self.style_order,
            "stylePolicySha256": self.style_policy_sha256,
            "semanticCollisionPriority": self.semantic_collision_priority,
            "shield": self.shield,
            "tunnel": self.tunnel,
        }

    def classification(self) -> semantic_policy.SemanticClassification:
        return semantic_policy.SemanticClassification(
            layer_group=self.layer_group,
            feature_kind=self.feature_kind,
            semantic_subtype=self.semantic_subtype,
            kind=self.semantic_kind,
            render_style_token_id=self.render_style_token_ids[0],
            admin_level=self.admin_level,
            disputed=self.disputed,
            coastline=self.coastline,
            intermittent=self.intermittent,
            tunnel=self.tunnel,
            shield=self.shield,
            one_way=self.one_way,
            land_evidence=self.land_evidence,
            protected_status=self.protected_status,
        )


@dataclass(frozen=True, slots=True)
class AuditEntry:
    style_layer_id: str
    style_order: int
    layer_type: str
    source_layer: str | None
    outcome: str
    reason: str | None
    layer_group: int | None
    feature_kind: int | None
    semantic_subtype: int | None
    source_style_layer_ids: tuple[int, ...]
    render_style_token_ids: tuple[int, ...]
    supported_operators: tuple[str, ...]

    def document(self) -> dict[str, object]:
        return {
            "featureKind": self.feature_kind,
            "layerGroup": self.layer_group,
            "layerType": self.layer_type,
            "outcome": self.outcome,
            "reason": self.reason,
            "renderStyleTokenIds": list(self.render_style_token_ids),
            "semanticSubtype": self.semantic_subtype,
            "sourceLayer": self.source_layer,
            "sourceStyleLayerIds": list(self.source_style_layer_ids),
            "styleLayerId": self.style_layer_id,
            "styleOrder": self.style_order,
            "supportedOperators": list(self.supported_operators),
        }


class LabelProvenance(str, Enum):
    PINNED_STYLE_LINE_LABEL = "PINNED_STYLE_LINE_LABEL"
    FLIGHT_ALERT_POLICY = "FLIGHT_ALERT_POLICY"


@dataclass(frozen=True, slots=True)
class SourcePathOccurrence:
    source_layer: str
    source_zoom: int
    tile_x: int
    tile_y: int
    feature_id: int
    duplicate_ordinal: int
    path_sha256: str

    def __post_init__(self) -> None:
        _canonical_text(self.source_layer, "source path layer")
        for value, label in (
            (self.source_zoom, "source zoom"),
            (self.tile_x, "source tile x"),
            (self.tile_y, "source tile y"),
            (self.feature_id, "source feature ID"),
            (self.duplicate_ordinal, "duplicate ordinal"),
        ):
            if type(value) is not int or value < 0:
                raise StyleContractError(f"{label} must be a nonnegative integer")
        if self.source_zoom > 30:
            raise StyleContractError("source zoom exceeds 30")
        bound = 1 << self.source_zoom
        if self.tile_x >= bound or self.tile_y >= bound:
            raise StyleContractError("source tile coordinate exceeds source zoom")
        _require_sha256(self.path_sha256, "source path SHA-256")


@dataclass(frozen=True, slots=True)
class NamedGeometryFallback:
    enabled: bool = False
    render_style_token_id: int = semantic_policy.FALLBACK_RENDER_STYLE_TOKEN_ID
    min_zoom_centi: int = 700
    max_zoom_centi: int = 1100
    repeat_distance_px: int = LINE_LABEL_REPEAT_SPACING_PX
    max_angle_centi_degrees: int = MAX_LINE_LABEL_BEND_CENTI_DEGREES
    avoid_edges: bool = True
    keep_upright: bool = True
    collision_group: int = REFERENCE_COLLISION_GROUP
    active_band_limit: int = LABEL_ACTIVE_BAND_LIMIT
    whole_text: bool = True
    text_source_field: str = "_name_en"
    placement: str = "line"

    def __post_init__(self) -> None:
        if type(self.enabled) is not bool:
            raise StyleContractError("fallback enablement must be Boolean")
        if not 0 <= self.min_zoom_centi < self.max_zoom_centi <= 10_000:
            raise StyleContractError("fallback zoom interval must be nonempty")
        if (
            type(self.render_style_token_id) is not int
            or self.render_style_token_id
            != semantic_policy.FALLBACK_RENDER_STYLE_TOKEN_ID
            or type(self.repeat_distance_px) is not int
            or self.repeat_distance_px != LINE_LABEL_REPEAT_SPACING_PX
            or type(self.max_angle_centi_degrees) is not int
            or self.max_angle_centi_degrees
            != MAX_LINE_LABEL_BEND_CENTI_DEGREES
        ):
            raise StyleContractError(
                "fallback must use the exact Flight Alert fallback presentation contract"
            )
        if type(self.avoid_edges) is not bool or type(self.keep_upright) is not bool:
            raise StyleContractError("fallback placement flags must be Boolean")
        if type(self.collision_group) is not int or self.collision_group <= 0:
            raise StyleContractError("fallback collision group must be nonzero")
        if (
            type(self.active_band_limit) is not int
            or not 1 <= self.active_band_limit <= 29
        ):
            raise StyleContractError("fallback active-band limit is outside [1, 29]")
        if self.whole_text is not True:
            raise StyleContractError("fallback placement must retain one whole text run")
        if self.text_source_field != "_name_en":
            raise StyleContractError("fallback text source must be exact _name_en evidence")
        if self.placement != "line":
            raise StyleContractError("fallback placement must remain line placement")

    def document(self) -> dict[str, object]:
        return {
            "activeBandLimit": self.active_band_limit,
            "avoidEdges": self.avoid_edges,
            "collisionGroup": self.collision_group,
            "enabled": self.enabled,
            "keepUpright": self.keep_upright,
            "maxAngleCentiDegrees": self.max_angle_centi_degrees,
            "maxZoomCentiExclusive": self.max_zoom_centi,
            "minZoomCentiInclusive": self.min_zoom_centi,
            "placement": self.placement,
            "renderStyleTokenId": self.render_style_token_id,
            "repeatDistancePx": self.repeat_distance_px,
            "schema": "flight-alert-exp8-named-geometry-fallback-v1",
            "semanticPolicySha256": semantic_policy.SEMANTIC_POLICY_SHA256,
            "textSourceField": self.text_source_field,
            "wholeText": self.whole_text,
        }

    @property
    def policy_sha256(self) -> str:
        canonical = json.dumps(
            self.document(), ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        return hashlib.sha256(b"FAE8FALLBACK1\0" + canonical).hexdigest()


def _candidate_document(
    *,
    occurrence: SourcePathOccurrence,
    display_text: str,
    text_source_field: str,
    provenance: LabelProvenance,
    style_policy_sha256: str,
    render_style_token_id: int,
    layer_group: LayerGroup,
    feature_kind: FeatureKind,
    semantic_subtype: int,
    semantic_kind: str,
    semantic_policy_sha256: str,
    presentation_policy_sha256: str,
    source_style_layer_ids: tuple[int, ...],
    display_interval: tuple[int, int],
    placement: str,
    repeat_distance_px: int,
    max_angle_centi_degrees: int,
    avoid_edges: bool,
    keep_upright: bool,
    collision_group: int,
    active_band_limit: int,
    whole_text: bool,
) -> dict[str, object]:
    return {
        "activeBandLimit": active_band_limit,
        "avoidEdges": avoid_edges,
        "collisionGroup": collision_group,
        "displayMaxZoomCentiExclusive": display_interval[1],
        "displayMinZoomCentiInclusive": display_interval[0],
        "displayText": display_text,
        "duplicateOrdinal": occurrence.duplicate_ordinal,
        "featureId": occurrence.feature_id,
        "keepUpright": keep_upright,
        "featureKind": feature_kind.value,
        "layerGroup": layer_group.value,
        "maxAngleCentiDegrees": max_angle_centi_degrees,
        "placement": placement,
        "placementGeometrySha256": occurrence.path_sha256,
        "placementSourceKind": PlacementSourceKind.DIRECT_SOURCE_PATH.value,
        "provenance": provenance.value,
        "renderStyleTokenId": render_style_token_id,
        "repeatDistancePx": repeat_distance_px,
        "semanticKind": semantic_kind,
        "semanticPolicySha256": semantic_policy_sha256,
        "semanticSubtype": semantic_subtype,
        "presentationPolicySha256": presentation_policy_sha256,
        "sourceLayer": occurrence.source_layer,
        "sourcePathSha256": occurrence.path_sha256,
        "sourceStyleLayerIds": list(source_style_layer_ids),
        "sourceTile": [occurrence.source_zoom, occurrence.tile_x, occurrence.tile_y],
        "stylePolicySha256": style_policy_sha256,
        "textSourceField": text_source_field,
        "wholeText": whole_text,
    }


def _candidate_digest(document: Mapping[str, object]) -> str:
    canonical = json.dumps(
        document, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(b"FAE8STYLECAND3\0" + canonical).hexdigest()


@dataclass(frozen=True, slots=True)
class CompiledLineLabelCandidate:
    candidate_id: int
    candidate_sha256: str
    display_text: str
    text_source_field: str
    provenance: LabelProvenance
    style_policy_sha256: str
    render_style_token_id: int
    layer_group: LayerGroup
    feature_kind: FeatureKind
    semantic_subtype: int
    semantic_kind: str
    semantic_policy_sha256: str
    presentation_policy_sha256: str
    source_style_layer_ids: tuple[int, ...]
    source_layer: str
    source_zoom: int
    source_tile_x: int
    source_tile_y: int
    source_feature_id: int
    duplicate_ordinal: int
    source_path_sha256: str
    placement_geometry_sha256: str
    placement_source_kind: PlacementSourceKind
    display_min_zoom_centi: int
    display_max_zoom_centi: int
    placement: str
    repeat_distance_px: int
    max_angle_centi_degrees: int
    avoid_edges: bool
    keep_upright: bool
    collision_group: int
    active_band_limit: int
    whole_text: bool

    def __post_init__(self) -> None:
        _require_sha256(self.candidate_sha256, "line-label candidate SHA-256")
        _require_sha256(self.source_path_sha256, "candidate source path SHA-256")
        _require_sha256(
            self.placement_geometry_sha256, "candidate placement geometry SHA-256"
        )
        _require_sha256(self.style_policy_sha256, "candidate style-policy SHA-256")
        _require_sha256(
            self.semantic_policy_sha256, "candidate semantic-policy SHA-256"
        )
        _require_sha256(
            self.presentation_policy_sha256,
            "candidate presentation-policy SHA-256",
        )
        _canonical_text(self.display_text, "candidate display text")
        if not self.display_text or self.display_text.isspace():
            raise StyleContractError("candidate display text must be nonblank")
        if self.text_source_field not in {"_name", "_name_global", "_name_en"}:
            raise StyleContractError("candidate text source field is unsupported")
        if type(self.layer_group) is not LayerGroup:
            raise StyleContractError("candidate layer group is unknown")
        if self.feature_kind is not FeatureKind.LABEL:
            raise StyleContractError("line-label candidate must remain label geometry")
        if type(self.semantic_subtype) is not int or self.semantic_subtype < 0:
            raise StyleContractError("candidate semantic subtype must be nonnegative")
        _canonical_text(self.semantic_kind, "candidate semantic kind")
        if not self.semantic_kind:
            raise StyleContractError("candidate semantic kind must be nonempty")
        if self.placement != "line":
            raise StyleContractError("line-label candidate must use line placement")
        for value, label in (
            (self.repeat_distance_px, "repeat distance"),
            (self.collision_group, "collision group"),
            (self.active_band_limit, "active-band limit"),
        ):
            if type(value) is not int or value <= 0:
                raise StyleContractError(f"candidate {label} must be positive")
        if not 1 <= self.active_band_limit <= 29:
            raise StyleContractError("candidate active-band limit is outside [1, 29]")
        if (
            type(self.max_angle_centi_degrees) is not int
            or not 0 <= self.max_angle_centi_degrees <= 18_000
        ):
            raise StyleContractError("candidate maximum bend angle is outside [0, 18000]")
        if type(self.avoid_edges) is not bool or type(self.keep_upright) is not bool:
            raise StyleContractError("candidate placement flags must be Boolean")
        if self.whole_text is not True:
            raise StyleContractError("candidate must retain one whole text run")
        if not (
            type(self.display_min_zoom_centi) is int
            and type(self.display_max_zoom_centi) is int
            and 0
            <= self.display_min_zoom_centi
            < self.display_max_zoom_centi
            <= 10_000
        ):
            raise StyleContractError("candidate display interval must be nonempty")
        if type(self.provenance) is not LabelProvenance:
            raise StyleContractError("candidate provenance is unknown")
        if self.provenance is LabelProvenance.PINNED_STYLE_LINE_LABEL:
            if not self.source_style_layer_ids or self.text_source_field == "_name_en":
                raise StyleContractError("pinned label candidate lacks pinned-style provenance")
        elif self.source_style_layer_ids or self.text_source_field != "_name_en":
            raise StyleContractError("fallback candidate lacks exact fallback provenance")
        if self.placement_geometry_sha256 != self.source_path_sha256:
            raise StyleContractError(
                "line label placement must use its own exact source path"
            )
        if self.placement_source_kind is not PlacementSourceKind.DIRECT_SOURCE_PATH:
            raise StyleContractError("line label must retain direct source path provenance")
        occurrence = SourcePathOccurrence(
            self.source_layer,
            self.source_zoom,
            self.source_tile_x,
            self.source_tile_y,
            self.source_feature_id,
            self.duplicate_ordinal,
            self.source_path_sha256,
        )
        document = _candidate_document(
            occurrence=occurrence,
            display_text=self.display_text,
            text_source_field=self.text_source_field,
            provenance=self.provenance,
            style_policy_sha256=self.style_policy_sha256,
            render_style_token_id=self.render_style_token_id,
            layer_group=self.layer_group,
            feature_kind=self.feature_kind,
            semantic_subtype=self.semantic_subtype,
            semantic_kind=self.semantic_kind,
            semantic_policy_sha256=self.semantic_policy_sha256,
            presentation_policy_sha256=self.presentation_policy_sha256,
            source_style_layer_ids=self.source_style_layer_ids,
            display_interval=(
                self.display_min_zoom_centi,
                self.display_max_zoom_centi,
            ),
            placement=self.placement,
            repeat_distance_px=self.repeat_distance_px,
            max_angle_centi_degrees=self.max_angle_centi_degrees,
            avoid_edges=self.avoid_edges,
            keep_upright=self.keep_upright,
            collision_group=self.collision_group,
            active_band_limit=self.active_band_limit,
            whole_text=self.whole_text,
        )
        expected = _candidate_digest(document)
        if self.candidate_sha256 != expected:
            raise StyleContractError(
                "line-label candidate SHA-256 does not bind semantic provenance"
            )
        if self.candidate_id != int.from_bytes(bytes.fromhex(expected)[:8], "big"):
            raise StyleContractError("line-label candidate ID does not match SHA-256")
        if self.semantic_policy_sha256 != semantic_policy.SEMANTIC_POLICY_SHA256:
            raise StyleContractError("candidate semantic policy SHA-256 drifted")
        if self.presentation_policy_sha256 != PRESENTATION_POLICY_SHA256:
            raise StyleContractError("candidate presentation policy SHA-256 drifted")


def _make_candidate(
    *,
    occurrence: SourcePathOccurrence,
    display_text: str,
    text_source_field: str,
    provenance: LabelProvenance,
    style_policy_sha256: str,
    render_style_token_id: int,
    layer_group: LayerGroup,
    feature_kind: FeatureKind,
    semantic_subtype: int,
    semantic_kind: str,
    semantic_policy_sha256: str,
    presentation_policy_sha256: str,
    source_style_layer_ids: tuple[int, ...],
    display_interval: tuple[int, int],
    placement: str,
    repeat_distance_px: int,
    max_angle_centi_degrees: int,
    avoid_edges: bool,
    keep_upright: bool,
    collision_group: int,
    active_band_limit: int,
    whole_text: bool,
) -> CompiledLineLabelCandidate:
    document = _candidate_document(
        occurrence=occurrence,
        display_text=display_text,
        text_source_field=text_source_field,
        provenance=provenance,
        style_policy_sha256=style_policy_sha256,
        render_style_token_id=render_style_token_id,
        layer_group=layer_group,
        feature_kind=feature_kind,
        semantic_subtype=semantic_subtype,
        semantic_kind=semantic_kind,
        semantic_policy_sha256=semantic_policy_sha256,
        presentation_policy_sha256=presentation_policy_sha256,
        source_style_layer_ids=source_style_layer_ids,
        display_interval=display_interval,
        placement=placement,
        repeat_distance_px=repeat_distance_px,
        max_angle_centi_degrees=max_angle_centi_degrees,
        avoid_edges=avoid_edges,
        keep_upright=keep_upright,
        collision_group=collision_group,
        active_band_limit=active_band_limit,
        whole_text=whole_text,
    )
    digest = _candidate_digest(document)
    return CompiledLineLabelCandidate(
        candidate_id=int.from_bytes(bytes.fromhex(digest)[:8], "big"),
        candidate_sha256=digest,
        display_text=display_text,
        text_source_field=text_source_field,
        provenance=provenance,
        style_policy_sha256=style_policy_sha256,
        render_style_token_id=render_style_token_id,
        layer_group=layer_group,
        feature_kind=feature_kind,
        semantic_subtype=semantic_subtype,
        semantic_kind=semantic_kind,
        semantic_policy_sha256=semantic_policy_sha256,
        presentation_policy_sha256=presentation_policy_sha256,
        source_style_layer_ids=source_style_layer_ids,
        source_layer=occurrence.source_layer,
        source_zoom=occurrence.source_zoom,
        source_tile_x=occurrence.tile_x,
        source_tile_y=occurrence.tile_y,
        source_feature_id=occurrence.feature_id,
        duplicate_ordinal=occurrence.duplicate_ordinal,
        source_path_sha256=occurrence.path_sha256,
        placement_geometry_sha256=occurrence.path_sha256,
        placement_source_kind=PlacementSourceKind.DIRECT_SOURCE_PATH,
        display_min_zoom_centi=display_interval[0],
        display_max_zoom_centi=display_interval[1],
        placement=placement,
        repeat_distance_px=repeat_distance_px,
        max_angle_centi_degrees=max_angle_centi_degrees,
        avoid_edges=avoid_edges,
        keep_upright=keep_upright,
        collision_group=collision_group,
        active_band_limit=active_band_limit,
        whole_text=whole_text,
    )


def _canonical_json_bytes(document: object) -> bytes:
    return (
        json.dumps(
            document,
            allow_nan=False,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def _strict_json_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise StyleContractError(f"verified style has duplicate JSON key {key!r}")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> object:
    raise StyleContractError(
        f"verified style has non-finite JSON constant {value!r}"
    )


def _parse_style_json_bytes(raw_style_bytes: bytes) -> object:
    try:
        text = raw_style_bytes.decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise StyleContractError("verified style is not strict UTF-8") from error
    try:
        return json.loads(
            text,
            object_pairs_hook=_strict_json_object,
            parse_constant=_reject_json_constant,
            parse_float=Decimal,
            parse_int=int,
        )
    except json.JSONDecodeError as error:
        raise StyleContractError("verified style is not valid JSON") from error


def _value_property_names(value: CompiledStyleValue) -> tuple[str, ...]:
    if isinstance(value, StyleFunction) and value.property_name is not None:
        return (value.property_name,)
    return ()


def _text_source_fields(value: CompiledStyleValue) -> tuple[str, ...]:
    if isinstance(value, TextTemplate):
        return (value.source_field,)
    if not isinstance(value, StyleFunction):
        return ()
    fields = {
        stop.output.source_field
        for stop in value.stops
        if isinstance(stop.output, TextTemplate)
    }
    if isinstance(value.default, TextTemplate):
        fields.add(value.default.source_field)
    return tuple(sorted(fields))


def _required_value(
    values: Mapping[str, CompiledStyleValue],
    field_name: str,
    expected_type: type[AtomicStyleValue],
    *,
    zoom_centi: int,
    properties: Mapping[str, object],
) -> AtomicStyleValue:
    value = values.get(field_name)
    if value is None:
        raise StyleContractError(f"included label lacks required {field_name}")
    resolved = resolve_style_value(
        value, zoom_centi=zoom_centi, properties=properties
    )
    if not isinstance(resolved, expected_type):
        raise StyleContractError(f"included {field_name} has the wrong value type")
    return resolved


def _optional_value(
    values: Mapping[str, CompiledStyleValue],
    field_name: str,
    expected_type: type[AtomicStyleValue],
    default: AtomicStyleValue,
    *,
    zoom_centi: int,
    properties: Mapping[str, object],
) -> AtomicStyleValue:
    value = values.get(field_name)
    if value is None:
        return default
    resolved = resolve_style_value(
        value, zoom_centi=zoom_centi, properties=properties
    )
    if not isinstance(resolved, expected_type):
        raise StyleContractError(f"included {field_name} has the wrong value type")
    return resolved


def _resolve_label_style_values(
    layout: Mapping[str, CompiledStyleValue],
    paint: Mapping[str, CompiledStyleValue],
    *,
    current_centizoom: int,
    properties: Mapping[str, object],
) -> ResolvedLabelStyle:
    text_field = _required_value(
        layout,
        "text-field",
        TextTemplate,
        zoom_centi=current_centizoom,
        properties=properties,
    )
    fonts = _required_value(
        layout,
        "text-font",
        StringTuple,
        zoom_centi=current_centizoom,
        properties=properties,
    )
    text_size = _required_value(
        layout,
        "text-size",
        FixedNumber,
        zoom_centi=current_centizoom,
        properties=properties,
    )
    max_width = _required_value(
        layout,
        "text-max-width",
        FixedNumber,
        zoom_centi=current_centizoom,
        properties=properties,
    )
    color = _required_value(
        paint,
        "text-color",
        ColorValue,
        zoom_centi=current_centizoom,
        properties=properties,
    )
    placement = _optional_value(
        layout,
        "symbol-placement",
        StringValue,
        StringValue("point"),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    avoid_edges = _optional_value(
        layout,
        "symbol-avoid-edges",
        BooleanValue,
        BooleanValue(False),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    keep_upright = _optional_value(
        layout,
        "text-keep-upright",
        BooleanValue,
        BooleanValue(True),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    spacing = _optional_value(
        layout,
        "symbol-spacing",
        FixedNumber,
        FixedNumber(0),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    letter_spacing = _optional_value(
        layout,
        "text-letter-spacing",
        FixedNumber,
        FixedNumber(0),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    max_angle = _optional_value(
        layout,
        "text-max-angle",
        FixedNumber,
        FixedNumber(45_000),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    offset = _optional_value(
        layout,
        "text-offset",
        FixedTuple,
        FixedTuple((0, 0)),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    halo_color = _optional_value(
        paint,
        "text-halo-color",
        ColorValue,
        ColorValue(0),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    halo_width = _optional_value(
        paint,
        "text-halo-width",
        FixedNumber,
        FixedNumber(0),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    opacity = _optional_value(
        paint,
        "text-opacity",
        FixedNumber,
        FixedNumber(1000),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    visibility = _optional_value(
        layout,
        "visibility",
        StringValue,
        StringValue("visible"),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    assert isinstance(text_field, TextTemplate)
    assert isinstance(fonts, StringTuple)
    assert isinstance(text_size, FixedNumber)
    assert isinstance(max_width, FixedNumber)
    assert isinstance(color, ColorValue)
    assert isinstance(placement, StringValue)
    assert isinstance(avoid_edges, BooleanValue)
    assert isinstance(keep_upright, BooleanValue)
    assert isinstance(spacing, FixedNumber)
    assert isinstance(letter_spacing, FixedNumber)
    assert isinstance(max_angle, FixedNumber)
    assert isinstance(offset, FixedTuple)
    assert isinstance(halo_color, ColorValue)
    assert isinstance(halo_width, FixedNumber)
    assert isinstance(opacity, FixedNumber)
    assert isinstance(visibility, StringValue)
    if spacing.milli % 1000 != 0:
        raise StyleContractError("symbol spacing is not an exact pixel integer")
    if max_angle.milli % 10 != 0:
        raise StyleContractError("text maximum angle is not exact centidegrees")
    font_slant = "italic" if "Arial Italic" in fonts.values else "normal"
    return ResolvedLabelStyle(
        text_source_field=text_field.source_field,
        placement=placement.value,
        avoid_edges=avoid_edges.value,
        keep_upright=keep_upright.value,
        repeat_distance_px=spacing.milli // 1000,
        font_families=fonts.values,
        font_slant=font_slant,
        text_size_milli=text_size.milli,
        letter_spacing_milli_em=letter_spacing.milli,
        max_width_milli_em=max_width.milli,
        max_angle_centi_degrees=max_angle.milli // 10,
        offset_milli_em=offset.values_milli,
        color_argb=color.argb,
        halo_color_argb=halo_color.argb,
        halo_width_milli_em=halo_width.milli,
        opacity_milli=opacity.milli,
        fade_in_centi=0,
        fade_out_centi=0,
        collision_group=REFERENCE_COLLISION_GROUP,
        visible=visibility.value != "none",
    )


def _resolve_candidate_label_style_values(
    layout: Mapping[str, CompiledStyleValue],
    *,
    current_centizoom: int,
    properties: Mapping[str, object],
) -> ResolvedCandidateLabelStyle:
    text_field = _required_value(
        layout,
        "text-field",
        TextTemplate,
        zoom_centi=current_centizoom,
        properties=properties,
    )
    placement = _optional_value(
        layout,
        "symbol-placement",
        StringValue,
        StringValue("point"),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    avoid_edges = _optional_value(
        layout,
        "symbol-avoid-edges",
        BooleanValue,
        BooleanValue(False),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    keep_upright = _optional_value(
        layout,
        "text-keep-upright",
        BooleanValue,
        BooleanValue(True),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    visibility = _optional_value(
        layout,
        "visibility",
        StringValue,
        StringValue("visible"),
        zoom_centi=current_centizoom,
        properties=properties,
    )
    assert isinstance(text_field, TextTemplate)
    assert isinstance(placement, StringValue)
    assert isinstance(avoid_edges, BooleanValue)
    assert isinstance(keep_upright, BooleanValue)
    assert isinstance(visibility, StringValue)
    return ResolvedCandidateLabelStyle(
        text_source_field=text_field.source_field,
        placement=placement.value,
        avoid_edges=avoid_edges.value,
        keep_upright=keep_upright.value,
        visible=visibility.value != "none",
    )


def _constant_zoom_value_interval(
    value: CompiledStyleValue,
    *,
    current_centizoom: int,
    interval: tuple[int, int],
) -> tuple[int, int]:
    if not isinstance(value, StyleFunction) or value.property_name is not None:
        return interval
    if value.function_type != "interval":
        raise StyleContractError(
            "candidate-inherited zoom style must resolve in constant intervals"
        )
    positions = tuple(stop.input_centi for stop in value.stops)
    if any(position is None for position in positions):
        raise StyleContractError("candidate zoom style has inconsistent stop inputs")
    exact_positions = tuple(int(position) for position in positions)
    selected = 0
    for index, position in enumerate(exact_positions):
        if current_centizoom >= position:
            selected = index
        else:
            break
    selected_output = value.stops[selected].output
    first = selected
    while first > 0 and value.stops[first - 1].output == selected_output:
        first -= 1
    last = selected
    while (
        last + 1 < len(value.stops)
        and value.stops[last + 1].output == selected_output
    ):
        last += 1
    minimum = interval[0] if first == 0 else max(interval[0], exact_positions[first])
    maximum = (
        interval[1]
        if last + 1 == len(value.stops)
        else min(interval[1], exact_positions[last + 1])
    )
    if not minimum <= current_centizoom < maximum:
        raise StyleContractError(
            "candidate constant-style interval does not contain resolution zoom"
        )
    return minimum, maximum


def _candidate_constant_style_interval(
    layout: Mapping[str, CompiledStyleValue],
    *,
    current_centizoom: int,
    interval: tuple[int, int],
) -> tuple[int, int]:
    result = interval
    for field_name in (
        "text-field",
        "symbol-placement",
        "symbol-avoid-edges",
        "text-keep-upright",
        "visibility",
    ):
        value = layout.get(field_name)
        if value is not None:
            result = _constant_zoom_value_interval(
                value,
                current_centizoom=current_centizoom,
                interval=result,
            )
    return result


def _candidate_constant_style_intervals(
    layout: Mapping[str, CompiledStyleValue],
    *,
    interval: tuple[int, int],
) -> tuple[tuple[int, int], ...]:
    intervals: list[tuple[int, int]] = []
    current_centizoom = interval[0]
    while current_centizoom < interval[1]:
        resolved_interval = _candidate_constant_style_interval(
            layout,
            current_centizoom=current_centizoom,
            interval=interval,
        )
        if (
            resolved_interval[0] != current_centizoom
            or resolved_interval[1] <= current_centizoom
        ):
            raise StyleContractError(
                "candidate constant-style interval enumeration did not advance"
            )
        intervals.append(resolved_interval)
        current_centizoom = resolved_interval[1]
    return tuple(intervals)


def _compile_label_style(
    layout: Mapping[str, CompiledStyleValue],
    paint: Mapping[str, CompiledStyleValue],
    *,
    min_zoom_centi: int,
) -> LabelStyle:
    text_value = layout.get("text-field")
    if text_value is None:
        raise StyleContractError("included label lacks required text-field")
    text_source_fields = _text_source_fields(text_value)
    if not text_source_fields:
        raise StyleContractError("included label lacks an accepted text source field")
    resolved = _resolve_label_style_values(
        layout,
        paint,
        current_centizoom=min_zoom_centi,
        properties={},
    )
    return LabelStyle(
        text_source_fields=text_source_fields,
        collision_group=resolved.collision_group,
        fade_in_centi=resolved.fade_in_centi,
        fade_out_centi=resolved.fade_out_centi,
    )


def resolve_label_style(
    rule: CompiledLayerRule,
    *,
    current_centizoom: int,
    properties: Mapping[str, object],
) -> ResolvedLabelStyle:
    if not isinstance(rule, CompiledLayerRule) or rule.label_style is None:
        raise StyleContractError("label style resolution requires a compiled label rule")
    if type(current_centizoom) is not int or not 0 <= current_centizoom <= 10_000:
        raise StyleContractError("current label centizoom is outside [0, 10000]")
    return _resolve_label_style_values(
        dict(rule.layout_values),
        dict(rule.paint_values),
        current_centizoom=current_centizoom,
        properties=properties,
    )


def _selector_properties(compiled_filter: CompiledFilter) -> dict[str, object]:
    result: dict[str, object] = {}

    def visit(item: CompiledFilter) -> None:
        if item.operator == "all":
            for child in item.children:
                visit(child)
            return
        if item.operator == "==" and item.property_name is not None:
            result[item.property_name] = item.values[0]
        elif (
            item.operator == "!in"
            and item.property_name == "Viz"
            and 3 in item.values
        ):
            result["Viz"] = 0

    visit(compiled_filter)
    return result


def _rule_policy_sha256(
    *,
    raw_style_sha256: str,
    semantic_policy_sha256: str,
    style_layer_id: str,
    style_order: int,
    source_layer: str,
    source_style_layer_ids: tuple[int, ...],
    render_style_token_ids: tuple[int, ...],
    classification: semantic_policy.SemanticClassification,
    compiled_filter: CompiledFilter,
    layout_values: tuple[tuple[str, CompiledStyleValue], ...],
    paint_values: tuple[tuple[str, CompiledStyleValue], ...],
    min_zoom_centi: int,
    max_zoom_centi: int,
    fade_in_centi: int,
    fade_out_centi: int,
    draw_order: int,
    semantic_collision_priority: int | None,
    priority_basis: str,
    retained_property_names: tuple[str, ...],
    label_style: LabelStyle | None,
    line_style: LineStyle | None,
    area_style: AreaStyle | None,
    fallback_text_source_field: str | None,
    inherited_style_layer_ids: tuple[str, ...],
) -> str:
    document = {
        "classification": {
            "adminLevel": classification.admin_level,
            "coastline": classification.coastline,
            "disputed": classification.disputed,
            "featureKind": classification.feature_kind.value,
            "intermittent": classification.intermittent,
            "kind": classification.kind,
            "landEvidence": classification.land_evidence.value,
            "layerGroup": classification.layer_group.value,
            "oneWay": classification.one_way,
            "protectedStatus": classification.protected_status.value,
            "renderStyleTokenId": classification.render_style_token_id,
            "semanticSubtype": classification.semantic_subtype,
            "shield": classification.shield,
            "tunnel": classification.tunnel,
        },
        "fallbackTextSourceField": fallback_text_source_field,
        "filter": compiled_filter.document(),
        "layout": {key: value.document() for key, value in layout_values},
        "paint": {key: value.document() for key, value in paint_values},
        "minZoomCentiInclusive": min_zoom_centi,
        "maxZoomCentiExclusive": max_zoom_centi,
        "fadeInCenti": fade_in_centi,
        "fadeOutCenti": fade_out_centi,
        "drawOrder": draw_order,
        "semanticCollisionPriority": semantic_collision_priority,
        "priorityBasis": priority_basis,
        "retainedPropertyNames": list(retained_property_names),
        "labelStyle": None if label_style is None else label_style.document(),
        "lineStyle": None if line_style is None else line_style.document(),
        "areaStyle": None if area_style is None else area_style.document(),
        "inheritedStyleLayerIds": list(inherited_style_layer_ids),
        "rawStyleSha256": _require_sha256(
            raw_style_sha256, "rule identity raw style SHA-256"
        ),
        "semanticPolicySha256": _require_sha256(
            semantic_policy_sha256, "rule identity semantic policy SHA-256"
        ),
        "sourceLayer": source_layer,
        "sourceStyleLayerIds": list(source_style_layer_ids),
        "renderStyleTokenIds": list(render_style_token_ids),
        "styleLayerId": style_layer_id,
        "styleOrder": style_order,
    }
    canonical = json.dumps(
        document,
        allow_nan=False,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(b"FAE8RULEPOL3\0" + canonical).hexdigest()


def _compile_rule(
    layer: Mapping[str, object],
    *,
    style_order: int,
    raw_style_sha256: str,
    source_style_layer_id: int,
) -> CompiledLayerRule:
    style_layer_id = _canonical_text(layer.get("id"), "style-layer ID")
    layer_type = layer.get("type")
    if layer_type not in {"fill", "line", "symbol"}:
        raise StyleContractError("included style rule has an unsupported source type")
    if layer.get("source") != "esri":
        raise StyleContractError("included style rule must bind the pinned esri source")
    source_layer = _canonical_text(layer.get("source-layer"), "source layer")
    source_policy = semantic_policy.SOURCE_LAYER_POLICIES.get(source_layer)
    if source_policy is None or layer_type not in source_policy.accepted_types:
        raise StyleContractError("included style rule lacks source-layer ownership")
    compiled_filter = compile_filter(layer.get("filter"))

    raw_layout = layer.get("layout", {})
    raw_paint = layer.get("paint", {})
    if type(raw_layout) is not dict or type(raw_paint) is not dict:
        raise StyleContractError("included layout and paint values must be objects")
    layout_values = tuple(
        (key, compile_style_value(key, raw_layout[key]))
        for key in sorted(raw_layout)
    )
    paint_values = tuple(
        (key, compile_style_value(key, raw_paint[key]))
        for key in sorted(raw_paint)
    )
    layout = dict(layout_values)
    paint = dict(paint_values)
    min_zoom_centi = _centizoom(
        layer.get("minzoom", 0), f"{style_layer_id} minimum zoom"
    )
    max_zoom_centi = _centizoom(
        layer.get("maxzoom", 24), f"{style_layer_id} maximum zoom"
    )
    if min_zoom_centi >= max_zoom_centi:
        raise StyleContractError("included style rule has an empty zoom interval")
    selector = _selector_properties(compiled_filter)
    try:
        classification = semantic_policy.classification_for_style_rule(
            source_layer, style_layer_id, layer_type, selector
        )
    except semantic_policy.SemanticPolicyError as error:
        raise StyleContractError(
            f"included style rule {style_layer_id!r} has no exact semantic policy: {error}"
        ) from error

    if classification.feature_kind is FeatureKind.LABEL:
        label_style = _compile_label_style(
            layout, paint, min_zoom_centi=min_zoom_centi
        )
        line_style = None
        area_style = None
    elif classification.feature_kind is FeatureKind.LINE:
        label_style = None
        line_style = LineStyle(
            style_layer_id,
            "overlay" if classification.one_way else _stroke_role(style_layer_id),
            style_order,
        )
        area_style = None
    else:
        label_style = None
        line_style = None
        area_style = AreaStyle()
    retained = set(semantic_policy.RETAINED_RAW_PROPERTIES)
    retained.update(compiled_filter.property_names)
    for _, value in layout_values + paint_values:
        retained.update(_value_property_names(value))
    text_field_value = layout.get("text-field")
    if text_field_value is not None:
        retained.update(_text_source_fields(text_field_value))
    if source_policy.fallback_text_source_field is not None:
        retained.add(source_policy.fallback_text_source_field)
    retained_property_names = tuple(sorted(retained))

    return CompiledLayerRule(
        style_layer_id=style_layer_id,
        style_order=style_order,
        source_layer=source_layer,
        layer_group=classification.layer_group,
        feature_kind=classification.feature_kind,
        semantic_subtype=classification.semantic_subtype,
        semantic_kind=classification.kind,
        source_style_layer_ids=(source_style_layer_id,),
        render_style_token_ids=(classification.render_style_token_id,),
        compiled_filter=compiled_filter,
        layout_values=layout_values,
        paint_values=paint_values,
        min_zoom_centi=min_zoom_centi,
        max_zoom_centi=max_zoom_centi,
        fade_in_centi=0,
        fade_out_centi=0,
        draw_order=style_order,
        semantic_collision_priority=None,
        priority_basis="requires_feature_prominence_decision",
        retained_property_names=retained_property_names,
        raw_style_sha256=raw_style_sha256,
        semantic_policy_sha256=semantic_policy.SEMANTIC_POLICY_SHA256,
        style_policy_sha256=_rule_policy_sha256(
            raw_style_sha256=raw_style_sha256,
            semantic_policy_sha256=semantic_policy.SEMANTIC_POLICY_SHA256,
            style_layer_id=style_layer_id,
            style_order=style_order,
            source_layer=source_layer,
            source_style_layer_ids=(source_style_layer_id,),
            render_style_token_ids=(classification.render_style_token_id,),
            classification=classification,
            compiled_filter=compiled_filter,
            layout_values=layout_values,
            paint_values=paint_values,
            min_zoom_centi=min_zoom_centi,
            max_zoom_centi=max_zoom_centi,
            fade_in_centi=0,
            fade_out_centi=0,
            draw_order=style_order,
            semantic_collision_priority=None,
            priority_basis="requires_feature_prominence_decision",
            retained_property_names=retained_property_names,
            label_style=label_style,
            line_style=line_style,
            area_style=area_style,
            fallback_text_source_field=source_policy.fallback_text_source_field,
            inherited_style_layer_ids=(),
        ),
        label_style=label_style,
        line_style=line_style,
        area_style=area_style,
        admin_level=classification.admin_level,
        disputed=classification.disputed,
        coastline=classification.coastline,
        intermittent=classification.intermittent,
        tunnel=classification.tunnel,
        shield=classification.shield,
        one_way=classification.one_way,
        land_evidence=classification.land_evidence,
        protected_status=classification.protected_status,
        fallback_text_source_field=source_policy.fallback_text_source_field,
    )


def _excluded_entry(
    layer: Mapping[str, object],
    *,
    style_order: int,
    reason: str,
) -> AuditEntry:
    style_layer_id = _canonical_text(layer.get("id"), "style-layer ID")
    layer_type = layer.get("type")
    if type(layer_type) is not str:
        raise StyleContractError("style layer type must be text")
    source_layer_value = layer.get("source-layer")
    source_layer = (
        None
        if source_layer_value is None
        else _canonical_text(source_layer_value, "source layer")
    )
    return AuditEntry(
        style_layer_id=style_layer_id,
        style_order=style_order,
        layer_type=layer_type,
        source_layer=source_layer,
        outcome="excluded",
        reason=reason,
        layer_group=None,
        feature_kind=None,
        semantic_subtype=None,
        source_style_layer_ids=(),
        render_style_token_ids=(),
        supported_operators=(),
    )


def _style_layer_exclusion_reason(
    layer: Mapping[str, object], style_layer_id: str
) -> str | None:
    layer_type = layer.get("type")
    layout = layer.get("layout", {})
    if type(layout) is not dict:
        raise StyleContractError("style layer layout must be an object")
    source_layer_value = layer.get("source-layer")
    source_layer = (
        None
        if source_layer_value is None
        else _canonical_text(source_layer_value, "source layer")
    )
    source_policy = (
        None
        if source_layer is None
        else semantic_policy.SOURCE_LAYER_POLICIES.get(source_layer)
    )
    allowlisted_water_area_fill = (
        layer_type == "fill"
        and source_policy is not None
        and layer_type in source_policy.accepted_types
        and source_layer
        in {
            "Water area",
            "Water area large scale",
            "Water area medium scale",
            "Water area small scale",
        }
    )
    if layer_type == "fill" and not allowlisted_water_area_fill:
        return "satellite_base_owned_fill"
    if (
        layer_type == "symbol"
        and "text-field" not in layout
        and style_layer_id not in semantic_policy.ONE_WAY_STYLE_IDS
    ):
        return "icon_only"
    if source_policy is None or layer_type not in source_policy.accepted_types:
        return "not_renderer_contract"
    return None


def _derive_rules_and_audit_from_verified_document(
    document: object,
    *,
    raw_style_sha256: str,
) -> tuple[tuple[CompiledLayerRule, ...], tuple[AuditEntry, ...]]:
    if type(document) is not dict or document.get("version") != 8:
        raise StyleContractError("verified source rule identity requires Style v8")
    layers = document.get("layers")
    if type(layers) is not list or any(type(layer) is not dict for layer in layers):
        raise StyleContractError("verified source rule identity has invalid layers")
    layer_ids = tuple(
        _canonical_text(layer.get("id"), f"verified style layer {index} ID")
        for index, layer in enumerate(layers)
    )
    if len(set(layer_ids)) != len(layer_ids):
        raise StyleContractError("verified source rule identity has duplicate layers")
    source_style_ids = {
        style_layer_id: index
        for index, style_layer_id in enumerate(sorted(layer_ids), start=1)
    }
    rules: list[CompiledLayerRule] = []
    audit: list[AuditEntry] = []
    for style_order, layer in enumerate(layers):
        style_layer_id = layer_ids[style_order]
        reason = _style_layer_exclusion_reason(layer, style_layer_id)
        if reason is not None:
            audit.append(
                _excluded_entry(layer, style_order=style_order, reason=reason)
            )
            continue
        rule = _compile_rule(
            layer,
            style_order=style_order,
            raw_style_sha256=raw_style_sha256,
            source_style_layer_id=source_style_ids[style_layer_id],
        )
        rules.append(rule)
        audit.append(
            AuditEntry(
                style_layer_id=style_layer_id,
                style_order=style_order,
                layer_type=layer["type"],
                source_layer=rule.source_layer,
                outcome="included",
                reason=None,
                layer_group=rule.layer_group.value,
                feature_kind=rule.feature_kind.value,
                semantic_subtype=rule.semantic_subtype,
                source_style_layer_ids=rule.source_style_layer_ids,
                render_style_token_ids=rule.render_style_token_ids,
                supported_operators=rule.compiled_filter.operators,
            )
        )
    return tuple(rules), tuple(audit)


def _derived_audit_counts(audit: Sequence[AuditEntry]) -> dict[str, int]:
    if any(entry.outcome not in {"included", "excluded"} for entry in audit):
        raise StyleContractError("contract audit identity has an unknown outcome")
    return {
        "excluded": sum(entry.outcome == "excluded" for entry in audit),
        "extraction_failure": 0,
        "included": sum(entry.outcome == "included" for entry in audit),
    }


def _included_rule_counts(
    rules: Sequence[CompiledLayerRule],
) -> dict[str, int]:
    return {
        "boundaries": sum(
            semantic_policy.SOURCE_LAYER_POLICIES[rule.source_layer].family
            == "boundaries"
            for rule in rules
        ),
        "labels": sum(rule.feature_kind is FeatureKind.LABEL for rule in rules),
        "public_lands": sum(
            rule.layer_group is LayerGroup.PUBLIC_LANDS for rule in rules
        ),
        "transportation": sum(
            rule.layer_group is LayerGroup.TRANSPORTATION for rule in rules
        ),
        "water": sum(rule.layer_group is LayerGroup.WATER for rule in rules),
        "water_polygons": sum(
            rule.layer_group is LayerGroup.WATER
            and rule.feature_kind is FeatureKind.POLYGON_OUTLINE
            for rule in rules
        ),
    }


def _source_style_ids_from_audit(
    audit: Sequence[AuditEntry],
) -> dict[str, int]:
    return {
        style_layer_id: index
        for index, style_layer_id in enumerate(
            sorted(entry.style_layer_id for entry in audit), start=1
        )
    }


def _build_audit_document(
    *,
    audit: Sequence[AuditEntry],
    audit_counts: Mapping[str, int],
    raw_style_sha256: str,
) -> dict[str, object]:
    return {
        "counts": dict(audit_counts),
        "entries": [entry.document() for entry in audit],
        "rawStyleSha256": raw_style_sha256,
        "schema": "flight-alert-exp8-style-audit-v1",
    }


def _build_catalog_document(
    *,
    raw_style_sha256: str,
    raw_style_length: int,
    style_version: int,
    layer_count: int,
    layer_type_counts: Sequence[tuple[str, int]],
    text_bearing_symbol_count: int,
    rules: Sequence[CompiledLayerRule],
    audit: Sequence[AuditEntry],
) -> dict[str, object]:
    source_style_ids = _source_style_ids_from_audit(audit)
    return {
        "completeAuditLayerCount": len(audit),
        "includedRuleCounts": _included_rule_counts(rules),
        "inputByteLength": raw_style_length,
        "masterOnlyGeometrySemanticSubtypeIds": sorted(
            item.value for item in semantic_policy.MasterOnlyGeometrySubtype
        ),
        "presentationSemanticSubtypeIds": sorted(
            item.value for item in SemanticSubtype
        ),
        "presentationFilters": {
            key: list(value)
            for key, value in sorted(
                semantic_policy.PRESENTATION_FILTER_SUBTYPES.items()
            )
        },
        "presentationPolicySha256": PRESENTATION_POLICY_SHA256,
        "rawStyleSha256": raw_style_sha256,
        "renderStyleTokenIds": {
            item.name: item.value for item in semantic_policy.RenderStyleToken
        },
        "retainedRawPropertyNames": list(semantic_policy.RETAINED_RAW_PROPERTIES),
        "rules": [rule.document() for rule in rules],
        "schema": "flight-alert-exp8-style-contract-v3",
        "semanticPolicySha256": semantic_policy.SEMANTIC_POLICY_SHA256,
        "sourceLayerGroups": {
            key: value.value
            for key, value in sorted(semantic_policy.SOURCE_LAYER_GROUPS.items())
        },
        "sourceLayerPolicy": {
            key: {
                "acceptedTypes": list(value.accepted_types),
                "fallbackLabelKind": value.fallback_label_kind,
                "fallbackLabelSubtype": value.fallback_label_subtype,
                "fallbackTextSourceField": value.fallback_text_source_field,
                "family": value.family,
                "layerGroup": value.layer_group.value,
            }
            for key, value in sorted(
                semantic_policy.SOURCE_LAYER_POLICIES.items()
            )
        },
        "sourceStyleLayerIds": dict(sorted(source_style_ids.items())),
        "styleLayerCount": layer_count,
        "styleLayerTypeCounts": dict(layer_type_counts),
        "styleVersion": style_version,
        "textBearingSymbolLayerCount": text_bearing_symbol_count,
        "supportedFilterOperators": ["!in", "==", "all", "in"],
        "transportSemanticSubtypeIds": {
            item.name: item.value for item in semantic_policy.TransportSubtype
        },
        "unsupportedIncludedExpressions": 0,
    }


def _build_rule_index(
    rules: Sequence[CompiledLayerRule],
) -> Mapping[tuple[str, FeatureKind], tuple[CompiledLayerRule, ...]]:
    buckets: dict[tuple[str, FeatureKind], list[CompiledLayerRule]] = {}
    for rule in rules:
        buckets.setdefault((rule.source_layer, rule.feature_kind), []).append(rule)
    return MappingProxyType(
        {
            key: tuple(sorted(buckets[key], key=lambda rule: rule.style_order))
            for key in sorted(buckets, key=lambda item: (item[0], item[1].value))
        }
    )


@dataclass(frozen=True, slots=True)
class StyleContract:
    raw_style_sha256: str
    raw_style_bytes: bytes = field(repr=False)
    raw_style_length: int
    style_version: int
    layer_count: int
    layer_type_counts: tuple[tuple[str, int], ...]
    source_layer_count: int
    text_bearing_symbol_count: int
    rules: tuple[CompiledLayerRule, ...]
    rule_index_by_source_kind: Mapping[
        tuple[str, FeatureKind], tuple[CompiledLayerRule, ...]
    ]
    audit: tuple[AuditEntry, ...]
    audit_counts: Mapping[str, int]
    catalog_document: Mapping[str, object]
    audit_bytes: bytes
    catalog_bytes: bytes

    def __post_init__(self) -> None:
        self._validate_evidence_identity()

    def _validate_evidence_identity(self) -> None:
        _require_sha256(self.raw_style_sha256, "contract raw style SHA-256")
        if type(self.raw_style_bytes) is not bytes:
            raise StyleContractError(
                "verified source rule identity requires immutable source bytes"
            )
        if hashlib.sha256(self.raw_style_bytes).hexdigest() != self.raw_style_sha256:
            raise StyleContractError("verified source rule identity SHA-256 drifted")
        if type(self.raw_style_length) is not int or self.raw_style_length < 0:
            raise StyleContractError("contract catalog identity has invalid byte length")
        if len(self.raw_style_bytes) != self.raw_style_length:
            raise StyleContractError("verified source rule identity length drifted")
        if self.style_version != 8 or type(self.style_version) is not int:
            raise StyleContractError("contract catalog identity has invalid style version")
        if type(self.rules) is not tuple or type(self.audit) is not tuple:
            raise StyleContractError("contract audit identity must be immutable")
        source_document = _parse_style_json_bytes(self.raw_style_bytes)
        expected_source_rules, expected_source_audit = (
            _derive_rules_and_audit_from_verified_document(
                source_document,
                raw_style_sha256=self.raw_style_sha256,
            )
        )
        if _canonical_json_bytes(
            [rule.document() for rule in self.rules]
        ) != _canonical_json_bytes(
            [rule.document() for rule in expected_source_rules]
        ):
            raise StyleContractError(
                "verified source rule identity disagrees with compiled rules"
            )
        if _canonical_json_bytes(
            [entry.document() for entry in self.audit]
        ) != _canonical_json_bytes(
            [entry.document() for entry in expected_source_audit]
        ):
            raise StyleContractError(
                "verified source rule identity disagrees with style audit"
            )
        if type(self.layer_count) is not int or self.layer_count != len(self.audit):
            raise StyleContractError("contract audit identity has the wrong layer count")
        if len({entry.style_layer_id for entry in self.audit}) != len(self.audit):
            raise StyleContractError("contract audit identity has duplicate ownership")
        if tuple(entry.style_order for entry in self.audit) != tuple(
            range(self.layer_count)
        ):
            raise StyleContractError("contract audit identity has unstable source order")
        expected_type_counts = tuple(
            sorted(
                {
                    layer_type: sum(
                        entry.layer_type == layer_type for entry in self.audit
                    )
                    for layer_type in {entry.layer_type for entry in self.audit}
                }.items()
            )
        )
        if self.layer_type_counts != expected_type_counts:
            raise StyleContractError("contract catalog identity has wrong type counts")
        expected_source_layer_count = len(
            {
                entry.source_layer
                for entry in self.audit
                if entry.source_layer is not None
            }
        )
        if self.source_layer_count != expected_source_layer_count:
            raise StyleContractError(
                "contract catalog identity has wrong source-layer count"
            )
        if (
            type(self.text_bearing_symbol_count) is not int
            or self.text_bearing_symbol_count < 0
        ):
            raise StyleContractError(
                "contract catalog identity has invalid text-bearing count"
            )

        included_entries = tuple(
            entry for entry in self.audit if entry.outcome == "included"
        )
        if tuple(rule.style_order for rule in self.rules) != tuple(
            sorted(rule.style_order for rule in self.rules)
        ):
            raise StyleContractError("contract catalog identity has unstable rule order")
        if len(self.rules) != len(included_entries):
            raise StyleContractError("contract audit identity has wrong included ownership")
        source_style_ids = _source_style_ids_from_audit(self.audit)
        for rule, entry in zip(self.rules, included_entries):
            if (
                rule.style_layer_id != entry.style_layer_id
                or rule.style_order != entry.style_order
                or rule.source_layer != entry.source_layer
                or entry.layer_group != rule.layer_group.value
                or entry.feature_kind != rule.feature_kind.value
                or entry.semantic_subtype != rule.semantic_subtype
                or entry.source_style_layer_ids != rule.source_style_layer_ids
                or entry.render_style_token_ids != rule.render_style_token_ids
                or entry.supported_operators != rule.compiled_filter.operators
            ):
                raise StyleContractError(
                    "contract audit identity disagrees with compiled rules"
                )
            if rule.source_style_layer_ids != (
                source_style_ids[rule.style_layer_id],
            ):
                raise StyleContractError(
                    "contract catalog identity has wrong source style identity"
                )
            if (
                rule.raw_style_sha256 != self.raw_style_sha256
                or rule.semantic_policy_sha256
                != semantic_policy.SEMANTIC_POLICY_SHA256
            ):
                raise StyleContractError(
                    "contract catalog identity has wrong rule policy identity"
                )

        expected_index = _build_rule_index(self.rules)
        if (
            type(self.rule_index_by_source_kind) is not MappingProxyType
            or any(
                type(key) is not tuple
                or len(key) != 2
                or type(key[0]) is not str
                or type(key[1]) is not FeatureKind
                or type(value) is not tuple
                for key, value in self.rule_index_by_source_kind.items()
            )
            or dict(self.rule_index_by_source_kind) != dict(expected_index)
        ):
            raise StyleContractError("contract rule-index identity is inconsistent")
        expected_audit_counts = _derived_audit_counts(self.audit)
        if (
            type(self.audit_counts) is not MappingProxyType
            or any(
                type(key) is not str or type(value) is not int
                for key, value in self.audit_counts.items()
            )
            or _canonical_json_bytes(dict(self.audit_counts))
            != _canonical_json_bytes(expected_audit_counts)
        ):
            raise StyleContractError("contract audit-count identity is inconsistent")
        expected_audit_bytes = _canonical_json_bytes(
            _build_audit_document(
                audit=self.audit,
                audit_counts=expected_audit_counts,
                raw_style_sha256=self.raw_style_sha256,
            )
        )
        if type(self.audit_bytes) is not bytes or self.audit_bytes != expected_audit_bytes:
            raise StyleContractError("contract audit identity bytes are inconsistent")
        expected_catalog_document = _build_catalog_document(
            raw_style_sha256=self.raw_style_sha256,
            raw_style_length=self.raw_style_length,
            style_version=self.style_version,
            layer_count=self.layer_count,
            layer_type_counts=self.layer_type_counts,
            text_bearing_symbol_count=self.text_bearing_symbol_count,
            rules=self.rules,
            audit=self.audit,
        )
        expected_catalog_bytes = _canonical_json_bytes(expected_catalog_document)
        if (
            type(self.catalog_document) is not MappingProxyType
            or _canonical_json_bytes(dict(self.catalog_document))
            != expected_catalog_bytes
        ):
            raise StyleContractError("contract catalog identity is inconsistent")
        if (
            type(self.catalog_bytes) is not bytes
            or self.catalog_bytes != expected_catalog_bytes
        ):
            raise StyleContractError("contract catalog identity bytes are inconsistent")

    def rule(self, style_layer_id: str) -> CompiledLayerRule:
        for rule in self.rules:
            if rule.style_layer_id == style_layer_id:
                return rule
        raise KeyError(style_layer_id)

    def audit_entry(self, style_layer_id: str) -> AuditEntry:
        for entry in self.audit:
            if entry.style_layer_id == style_layer_id:
                return entry
        raise KeyError(style_layer_id)

    def rules_for(
        self, source_layer: str, feature_kind: FeatureKind
    ) -> tuple[CompiledLayerRule, ...]:
        _canonical_text(source_layer, "indexed source layer")
        if type(feature_kind) is not FeatureKind:
            raise StyleContractError("indexed rule lookup requires FeatureKind")
        return self.rule_index_by_source_kind.get((source_layer, feature_kind), ())

    def rule_matches(
        self,
        rule: CompiledLayerRule,
        properties: Mapping[str, object],
        *,
        zoom_centi: int,
    ) -> bool:
        if not isinstance(rule, CompiledLayerRule):
            raise StyleContractError("runtime style matching requires a compiled rule")
        if type(zoom_centi) is not int or not 0 <= zoom_centi <= 10_000:
            raise StyleContractError("runtime style zoom is outside [0, 10000]")
        interval = _intersect_centi_interval(
            rule.min_zoom_centi, rule.max_zoom_centi, properties
        )
        if interval is None or not zoom_interval_contains(interval, zoom_centi):
            return False
        if not rule.compiled_filter.matches(properties):
            return False
        return semantic_policy.classification_matches(
            rule.classification(),
            source_layer=rule.source_layer,
            style_layer_id=rule.style_layer_id,
            properties=properties,
        )

    def stroke_stack(
        self,
        source_layer: str,
        properties: Mapping[str, object],
        *,
        zoom_centi: int,
    ) -> tuple[LineStyle, ...]:
        if type(zoom_centi) is not int:
            raise StyleContractError("stroke stack zoom must be integer centizoom")
        matched = [
            rule.line_style
            for rule in self.rules_for(source_layer, FeatureKind.LINE)
            if rule.line_style is not None
            and self.rule_matches(rule, properties, zoom_centi=zoom_centi)
        ]
        role_order = {"casing": 0, "inner": 1, "single": 1, "overlay": 2}
        return tuple(
            sorted(
                (item for item in matched if item is not None),
                key=lambda item: (role_order[item.stroke_role], item.style_order),
            )
        )

    def line_label_candidates(
        self,
        occurrence: SourcePathOccurrence,
        properties: Mapping[str, object],
        *,
        fallback_policy: NamedGeometryFallback | None = None,
        extraction_audit: "TextExtractionAudit | None" = None,
    ) -> tuple[CompiledLineLabelCandidate, ...]:
        if not isinstance(occurrence, SourcePathOccurrence):
            raise StyleContractError("line-label occurrence must be SourcePathOccurrence")
        direct_candidates: list[CompiledLineLabelCandidate] = []
        first_reachable_centizoom = occurrence.source_zoom * 100
        for rule in self.rules_for(occurrence.source_layer, FeatureKind.LABEL):
            if rule.label_style is None:
                continue
            interval = _intersect_centi_interval(
                rule.min_zoom_centi, rule.max_zoom_centi, properties
            )
            if interval is None:
                continue
            layout = dict(rule.layout_values)
            for candidate_interval in _candidate_constant_style_intervals(
                layout, interval=interval
            ):
                if candidate_interval[1] <= first_reachable_centizoom:
                    continue
                resolution_centizoom = candidate_interval[0]
                if not self.rule_matches(
                    rule, properties, zoom_centi=resolution_centizoom
                ):
                    continue
                resolved = _resolve_candidate_label_style_values(
                    layout,
                    current_centizoom=resolution_centizoom,
                    properties=properties,
                )
                if resolved.placement != "line" or not resolved.visible:
                    continue
                display_text = extract_display_text(
                    rule,
                    properties,
                    current_centizoom=resolution_centizoom,
                    audit=extraction_audit,
                )
                if display_text is None:
                    continue
                direct_candidates.append(
                    _make_candidate(
                        occurrence=occurrence,
                        display_text=display_text,
                        text_source_field=resolved.text_source_field,
                        provenance=LabelProvenance.PINNED_STYLE_LINE_LABEL,
                        style_policy_sha256=rule.style_policy_sha256,
                        render_style_token_id=rule.render_style_token_ids[0],
                        layer_group=rule.layer_group,
                        feature_kind=rule.feature_kind,
                        semantic_subtype=rule.semantic_subtype,
                        semantic_kind=rule.semantic_kind,
                        semantic_policy_sha256=rule.semantic_policy_sha256,
                        presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
                        source_style_layer_ids=rule.source_style_layer_ids,
                        display_interval=candidate_interval,
                        placement=resolved.placement,
                        repeat_distance_px=LINE_LABEL_REPEAT_SPACING_PX,
                        max_angle_centi_degrees=MAX_LINE_LABEL_BEND_CENTI_DEGREES,
                        avoid_edges=resolved.avoid_edges,
                        keep_upright=resolved.keep_upright,
                        collision_group=rule.label_style.collision_group,
                        active_band_limit=LABEL_ACTIVE_BAND_LIMIT,
                        whole_text=rule.label_style.whole_text,
                    )
                )
        if direct_candidates:
            return tuple(direct_candidates)

        fallback_policy = fallback_policy or NamedGeometryFallback()
        if not fallback_policy.enabled:
            return ()
        source_policy = semantic_policy.SOURCE_LAYER_POLICIES.get(
            occurrence.source_layer
        )
        if source_policy is None or source_policy.fallback_text_source_field is None:
            return ()
        field = source_policy.fallback_text_source_field
        if (
            field != fallback_policy.text_source_field
            or source_policy.fallback_label_subtype is None
            or source_policy.fallback_label_kind is None
        ):
            raise StyleContractError(
                "fallback source policy does not bind exact label evidence"
            )
        display_text = _extract_text_field(
            properties, field, audit=extraction_audit
        )
        if display_text is None:
            return ()
        interval = _intersect_centi_interval(
            fallback_policy.min_zoom_centi,
            fallback_policy.max_zoom_centi,
            properties,
        )
        if interval is None:
            return ()
        if interval[1] <= first_reachable_centizoom:
            return ()
        return (
            _make_candidate(
                occurrence=occurrence,
                display_text=display_text,
                text_source_field=field,
                provenance=LabelProvenance.FLIGHT_ALERT_POLICY,
                style_policy_sha256=fallback_policy.policy_sha256,
                render_style_token_id=fallback_policy.render_style_token_id,
                layer_group=source_policy.layer_group,
                feature_kind=FeatureKind.LABEL,
                semantic_subtype=source_policy.fallback_label_subtype,
                semantic_kind=source_policy.fallback_label_kind,
                semantic_policy_sha256=semantic_policy.SEMANTIC_POLICY_SHA256,
                presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
                source_style_layer_ids=(),
                display_interval=interval,
                placement=fallback_policy.placement,
                repeat_distance_px=fallback_policy.repeat_distance_px,
                max_angle_centi_degrees=fallback_policy.max_angle_centi_degrees,
                avoid_edges=fallback_policy.avoid_edges,
                keep_upright=fallback_policy.keep_upright,
                collision_group=fallback_policy.collision_group,
                active_band_limit=fallback_policy.active_band_limit,
                whole_text=fallback_policy.whole_text,
            ),
        )

    def line_label_candidate(
        self,
        occurrence: SourcePathOccurrence,
        properties: Mapping[str, object],
        *,
        fallback_policy: NamedGeometryFallback | None = None,
        extraction_audit: "TextExtractionAudit | None" = None,
    ) -> CompiledLineLabelCandidate | None:
        candidates = self.line_label_candidates(
            occurrence,
            properties,
            fallback_policy=fallback_policy,
            extraction_audit=extraction_audit,
        )
        if len(candidates) > 1:
            raise StyleContractError(
                "line-label occurrence has multiple reachable style intervals; "
                "use line_label_candidates"
            )
        return candidates[0] if candidates else None


@dataclass(slots=True)
class TextExtractionAudit:
    no_text_count: int = 0
    emitted_text_count: int = 0

    def __post_init__(self) -> None:
        for value in (self.no_text_count, self.emitted_text_count):
            if type(value) is not int or value < 0:
                raise StyleContractError(
                    "text extraction audit counters must be nonnegative integers"
                )

    def document(self) -> dict[str, int]:
        return {
            "emittedTextCount": self.emitted_text_count,
            "noTextCount": self.no_text_count,
        }


def _extract_text_field(
    properties: Mapping[str, object],
    source_field: str,
    *,
    audit: TextExtractionAudit | None = None,
) -> str | None:
    if audit is not None and not isinstance(audit, TextExtractionAudit):
        raise StyleContractError("text extraction audit has the wrong type")
    value = properties.get(source_field, _MISSING)
    if value is _MISSING or value is None:
        if audit is not None:
            audit.no_text_count += 1
        return None
    if type(value) is not str:
        raise StyleContractError(f"display field {source_field!r} must be text")
    try:
        value.encode("utf-8", "strict")
    except UnicodeEncodeError as error:
        raise StyleContractError("display text is not valid UTF-8") from error
    normalized = unicodedata.normalize("NFC", value).rstrip()
    if not normalized or normalized.isspace():
        if audit is not None:
            audit.no_text_count += 1
        return None
    if audit is not None:
        audit.emitted_text_count += 1
    return normalized


def extract_display_text(
    rule: CompiledLayerRule,
    properties: Mapping[str, object],
    *,
    current_centizoom: int | None = None,
    audit: TextExtractionAudit | None = None,
) -> str | None:
    if not isinstance(rule, CompiledLayerRule) or rule.label_style is None:
        raise StyleContractError("display-text extraction requires a label rule")
    text_value = rule.value("text-field")
    if text_value is None:
        raise StyleContractError("display-text extraction lacks text-field evidence")
    if (
        isinstance(text_value, StyleFunction)
        and text_value.property_name is None
        and current_centizoom is None
    ):
        raise StyleContractError(
            "dynamic display-text extraction requires current centizoom"
        )
    resolved = resolve_style_value(
        text_value,
        zoom_centi=current_centizoom,
        properties=properties,
    )
    if not isinstance(resolved, TextTemplate):
        raise StyleContractError("display-text extraction resolved a non-text field")
    return _extract_text_field(properties, resolved.source_field, audit=audit)


def _intersect_centi_interval(
    minimum: int, maximum: int, properties: Mapping[str, object]
) -> tuple[int, int] | None:
    if "_minzoom" in properties:
        minimum = max(
            minimum,
            _provider_decizoom(properties["_minzoom"], "provider _minzoom"),
        )
    if "_maxzoom" in properties:
        maximum = min(
            maximum,
            _provider_decizoom(properties["_maxzoom"], "provider _maxzoom"),
        )
    if minimum >= maximum:
        return None
    return minimum, maximum


def _combine_centi_interval(
    minimum: int, maximum: int, properties: Mapping[str, object]
) -> tuple[int, int]:
    interval = _intersect_centi_interval(minimum, maximum, properties)
    if interval is None:
        raise StyleContractError("combined half-open zoom interval is empty")
    return interval


def _combine_rule_interval(
    rule: CompiledLayerRule, properties: Mapping[str, object]
) -> tuple[int, int]:
    return _combine_centi_interval(
        rule.min_zoom_centi, rule.max_zoom_centi, properties
    )


def _compile_document(
    document: object,
    *,
    raw_style_bytes: bytes,
    raw_style_sha256: str,
    raw_style_length: int,
    require_pinned_inventory: bool,
) -> StyleContract:
    if type(document) is not dict:
        raise StyleContractError("Style v8 root must be an object")
    if document.get("version") != 8 or type(document.get("version")) is not int:
        raise StyleContractError("only exact Style v8 input is accepted")
    layers = document.get("layers")
    if type(layers) is not list:
        raise StyleContractError("Style v8 layers must be an array")
    for index, layer in enumerate(layers):
        if type(layer) is not dict:
            raise StyleContractError(f"style layer {index} must be an object")
    layer_ids = [
        _canonical_text(layer.get("id"), f"style layer {index} ID")
        for index, layer in enumerate(layers)
    ]
    if len(set(layer_ids)) != len(layer_ids):
        raise StyleContractError("duplicate style-layer ID")
    layer_types: dict[str, int] = {}
    source_layers: set[str] = set()
    text_bearing_symbol_count = 0
    for index, layer in enumerate(layers):
        layer_type = layer.get("type")
        if layer_type not in {"fill", "line", "symbol"}:
            raise StyleContractError(
                f"style layer {index} has an unsupported layer type"
            )
        layer_types[layer_type] = layer_types.get(layer_type, 0) + 1
        layout = layer.get("layout", {})
        if type(layout) is not dict:
            raise StyleContractError("style layer layout must be an object")
        if layer_type == "symbol" and "text-field" in layout:
            text_bearing_symbol_count += 1
        if "source-layer" in layer:
            source_layers.add(
                _canonical_text(layer["source-layer"], "source layer")
            )
    type_counts = tuple(sorted(layer_types.items()))
    if require_pinned_inventory:
        if raw_style_length != PINNED_STYLE_LENGTH:
            raise StyleContractError("pinned style byte length drifted")
        if len(layers) != PINNED_LAYER_COUNT:
            raise StyleContractError("pinned style layer count drifted")
        if type_counts != PINNED_TYPE_COUNTS:
            raise StyleContractError("pinned style layer-type inventory drifted")
        if len(source_layers) != PINNED_SOURCE_LAYER_COUNT:
            raise StyleContractError("pinned style source-layer inventory drifted")
        if text_bearing_symbol_count != 767:
            raise StyleContractError("pinned text-bearing symbol inventory drifted")

    source_style_ids = {
        style_layer_id: index
        for index, style_layer_id in enumerate(sorted(layer_ids), start=1)
    }
    rules: list[CompiledLayerRule] = []
    audit: list[AuditEntry] = []
    for style_order, layer in enumerate(layers):
        style_layer_id = layer_ids[style_order]
        layer_type = layer["type"]
        exclusion_reason = _style_layer_exclusion_reason(layer, style_layer_id)
        if exclusion_reason is not None:
            audit.append(
                _excluded_entry(
                    layer,
                    style_order=style_order,
                    reason=exclusion_reason,
                )
            )
            continue
        try:
            rule = _compile_rule(
                layer,
                style_order=style_order,
                raw_style_sha256=raw_style_sha256,
                source_style_layer_id=source_style_ids[style_layer_id],
            )
        except (StyleContractError, semantic_policy.SemanticPolicyError) as error:
            if isinstance(error, UnsupportedStyleError):
                raise
            raise StyleContractError(
                f"extraction failure in included style layer {style_layer_id!r}: {error}"
            ) from error
        rules.append(rule)
        audit.append(
            AuditEntry(
                style_layer_id=style_layer_id,
                style_order=style_order,
                layer_type=layer_type,
                source_layer=rule.source_layer,
                outcome="included",
                reason=None,
                layer_group=rule.layer_group.value,
                feature_kind=rule.feature_kind.value,
                semantic_subtype=rule.semantic_subtype,
                source_style_layer_ids=rule.source_style_layer_ids,
                render_style_token_ids=rule.render_style_token_ids,
                supported_operators=rule.compiled_filter.operators,
            )
        )

    if len(audit) != len(layers) or len({entry.style_layer_id for entry in audit}) != len(layers):
        raise StyleContractError("style audit does not own every layer exactly once")
    audit_counts = _derived_audit_counts(audit)
    catalog_document = _build_catalog_document(
        raw_style_sha256=raw_style_sha256,
        raw_style_length=raw_style_length,
        style_version=8,
        layer_count=len(layers),
        layer_type_counts=type_counts,
        text_bearing_symbol_count=text_bearing_symbol_count,
        rules=rules,
        audit=audit,
    )
    audit_document = _build_audit_document(
        audit=audit,
        audit_counts=audit_counts,
        raw_style_sha256=raw_style_sha256,
    )
    audit_bytes = _canonical_json_bytes(audit_document)
    catalog_bytes = _canonical_json_bytes(catalog_document)
    return StyleContract(
        raw_style_sha256=raw_style_sha256,
        raw_style_bytes=raw_style_bytes,
        raw_style_length=raw_style_length,
        style_version=8,
        layer_count=len(layers),
        layer_type_counts=type_counts,
        source_layer_count=len(source_layers),
        text_bearing_symbol_count=text_bearing_symbol_count,
        rules=tuple(rules),
        rule_index_by_source_kind=_build_rule_index(rules),
        audit=tuple(audit),
        audit_counts=MappingProxyType(audit_counts),
        catalog_document=MappingProxyType(catalog_document),
        audit_bytes=audit_bytes,
        catalog_bytes=catalog_bytes,
    )


def compile_style_bytes(
    raw_style_bytes: bytes,
    *,
    expected_sha256: str,
    require_pinned_inventory: bool = True,
) -> StyleContract:
    if type(raw_style_bytes) is not bytes:
        raise StyleContractError("raw style input must be immutable bytes")
    expected_sha256 = _require_sha256(expected_sha256, "expected style SHA-256")
    actual_sha256 = hashlib.sha256(raw_style_bytes).hexdigest()
    if actual_sha256 != expected_sha256:
        raise StyleContractError(
            f"raw style SHA-256 mismatch: expected {expected_sha256}, got {actual_sha256}"
        )
    document = _parse_style_json_bytes(raw_style_bytes)
    return _compile_document(
        document,
        raw_style_bytes=raw_style_bytes,
        raw_style_sha256=actual_sha256,
        raw_style_length=len(raw_style_bytes),
        require_pinned_inventory=require_pinned_inventory,
    )


def compile_style_contract(
    style_path: Path | str = PINNED_STYLE_PATH,
    *,
    expected_sha256: str = PINNED_STYLE_SHA256,
) -> StyleContract:
    path = Path(style_path)
    raw = path.read_bytes()
    return compile_style_bytes(
        raw,
        expected_sha256=expected_sha256,
        require_pinned_inventory=expected_sha256 == PINNED_STYLE_SHA256,
    )


def style_evidence_bytes(contract: StyleContract) -> dict[str, bytes]:
    if not isinstance(contract, StyleContract):
        raise StyleContractError("style evidence requires a compiled StyleContract")
    contract._validate_evidence_identity()
    manifest_document = {
        "auditByteLength": len(contract.audit_bytes),
        "auditSha256": hashlib.sha256(contract.audit_bytes).hexdigest(),
        "catalogByteLength": len(contract.catalog_bytes),
        "catalogSha256": hashlib.sha256(contract.catalog_bytes).hexdigest(),
        "includedRuleCount": contract.audit_counts["included"],
        "masterOnlyGeometrySemanticSubtypeIds": sorted(
            item.value for item in semantic_policy.MasterOnlyGeometrySubtype
        ),
        "presentationFilters": {
            key: list(value)
            for key, value in sorted(
                semantic_policy.PRESENTATION_FILTER_SUBTYPES.items()
            )
        },
        "rawStyleByteLength": contract.raw_style_length,
        "rawStyleSha256": contract.raw_style_sha256,
        "reconciledStyleLayerCount": contract.layer_count,
        "schema": "flight-alert-exp8-style-evidence-manifest-v3",
        "presentationPolicySha256": PRESENTATION_POLICY_SHA256,
        "presentationSemanticSubtypeIds": sorted(
            item.value for item in SemanticSubtype
        ),
        "semanticPolicySha256": semantic_policy.SEMANTIC_POLICY_SHA256,
        "unsupportedIncludedExpressionCount": 0,
    }
    return {
        "audit.json": contract.audit_bytes,
        "catalog.json": contract.catalog_bytes,
        "manifest.json": _canonical_json_bytes(manifest_document),
    }


_STYLE_EVIDENCE_FILENAMES = ("audit.json", "catalog.json", "manifest.json")
_STYLE_EVIDENCE_GENERATION_DOMAIN = b"FAE8STYLEGEN1\0"
_STYLE_EVIDENCE_CURRENT_SCHEMA = "flight-alert-exp8-style-evidence-current-v1"


def _style_evidence_generation_sha256(files: Mapping[str, bytes]) -> str:
    if tuple(sorted(files)) != _STYLE_EVIDENCE_FILENAMES:
        raise StyleContractError("style evidence generation file set differs")
    file_documents: list[dict[str, object]] = []
    for filename in _STYLE_EVIDENCE_FILENAMES:
        data = files[filename]
        if type(data) is not bytes:
            raise StyleContractError("style evidence generation bytes must be immutable")
        file_documents.append(
            {
                "byteLength": len(data),
                "filename": filename,
                "sha256": hashlib.sha256(data).hexdigest(),
            }
        )
    identity = _canonical_json_bytes(
        {
            "files": file_documents,
            "schema": "flight-alert-exp8-style-evidence-generation-v1",
        }
    )
    return hashlib.sha256(_STYLE_EVIDENCE_GENERATION_DOMAIN + identity).hexdigest()


def _style_evidence_current_bytes(generation_sha256: str) -> bytes:
    generation_sha256 = _require_sha256(
        generation_sha256, "style evidence generation SHA-256"
    )
    return _canonical_json_bytes(
        {
            "generationSha256": generation_sha256,
            "schema": _STYLE_EVIDENCE_CURRENT_SCHEMA,
        }
    )


_REPARSE_POINT_ATTRIBUTE = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)


def _stat_is_reparse(value: os.stat_result) -> bool:
    return stat.S_ISLNK(value.st_mode) or bool(
        getattr(value, "st_file_attributes", 0) & _REPARSE_POINT_ATTRIBUTE
    )


def _stat_identity(value: os.stat_result) -> tuple[int, int, int, int]:
    return (
        value.st_dev,
        value.st_ino,
        stat.S_IFMT(value.st_mode),
        getattr(value, "st_file_attributes", 0),
    )


def _absolute_style_evidence_path(path: Path | str) -> Path:
    return Path(os.path.abspath(os.fspath(path)))


def _path_components(path: Path) -> tuple[Path, ...]:
    result: list[Path] = []
    current = path
    while True:
        result.append(current)
        if current.parent == current:
            break
        current = current.parent
    return tuple(reversed(result))


def _require_existing_ancestry_non_reparse(
    path: Path, label: str
) -> None:
    for component in _path_components(path):
        try:
            information = os.lstat(component)
        except FileNotFoundError:
            return
        if _stat_is_reparse(information):
            raise StyleContractError(f"{label} has reparse ancestry")
        if not stat.S_ISDIR(information.st_mode):
            raise StyleContractError(f"{label} path component is not a directory")


def _require_canonical_non_reparse_directory(
    path: Path | str, label: str
) -> tuple[Path, tuple[int, int, int, int]]:
    absolute = _absolute_style_evidence_path(path)
    for component in _path_components(absolute):
        try:
            information = os.lstat(component)
        except FileNotFoundError as error:
            raise StyleContractError(f"{label} path component is missing") from error
        if _stat_is_reparse(information):
            raise StyleContractError(f"{label} has reparse ancestry")
        if not stat.S_ISDIR(information.st_mode):
            raise StyleContractError(f"{label} path component is not a directory")
    resolved = absolute.resolve(strict=True)
    if os.path.normcase(os.path.normpath(str(resolved))) != os.path.normcase(
        os.path.normpath(str(absolute))
    ):
        raise StyleContractError(f"{label} does not name its canonical target")
    information = os.lstat(absolute)
    return absolute, _stat_identity(information)


def _require_stable_directory_identity(
    path: Path,
    expected_identity: tuple[int, int, int, int],
    label: str,
) -> None:
    canonical, actual_identity = _require_canonical_non_reparse_directory(
        path, label
    )
    if canonical != path or actual_identity != expected_identity:
        raise StyleContractError(f"{label} stable identity changed")


def _prepare_style_evidence_output(
    output: Path | str,
) -> tuple[Path, tuple[int, int, int, int]]:
    absolute = _absolute_style_evidence_path(output)
    if not absolute.name:
        raise StyleContractError("style evidence output must name a directory")
    _require_existing_ancestry_non_reparse(
        absolute.parent, "style evidence parent"
    )
    if not os.path.lexists(absolute.parent):
        raise StyleContractError(
            "style evidence output requires a pre-existing trusted parent"
        )
    parent, parent_identity = _require_canonical_non_reparse_directory(
        absolute.parent, "style evidence parent"
    )
    try:
        absolute.mkdir(exist_ok=True)
    except FileNotFoundError as error:
        raise StyleContractError(
            "style evidence trusted parent changed before output creation"
        ) from error
    _require_stable_directory_identity(
        parent, parent_identity, "style evidence parent"
    )
    return _require_canonical_non_reparse_directory(
        absolute, "style evidence output"
    )


def _validate_regular_file_stat(
    information: os.stat_result, label: str
) -> None:
    if _stat_is_reparse(information) or not stat.S_ISREG(information.st_mode):
        raise StyleContractError(f"{label} is not a real regular file")
    if information.st_nlink != 1:
        raise StyleContractError(f"{label} must not be a hardlink")


def _read_regular_file_stable(path: Path, label: str) -> bytes:
    try:
        before = os.lstat(path)
    except FileNotFoundError as error:
        raise StyleContractError(f"{label} is missing") from error
    _validate_regular_file_stat(before, label)
    descriptor = os.open(
        path,
        os.O_RDONLY
        | getattr(os, "O_BINARY", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        opened = os.fstat(descriptor)
        _validate_regular_file_stat(opened, label)
        if _stat_identity(opened) != _stat_identity(before):
            raise StyleContractError(f"{label} stable identity changed before read")
        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        after_descriptor = os.fstat(descriptor)
        _validate_regular_file_stat(after_descriptor, label)
        if (
            _stat_identity(after_descriptor) != _stat_identity(opened)
            or after_descriptor.st_size != opened.st_size
        ):
            raise StyleContractError(f"{label} changed while being read")
    finally:
        os.close(descriptor)
    after_path = os.lstat(path)
    _validate_regular_file_stat(after_path, label)
    if _stat_identity(after_path) != _stat_identity(before):
        raise StyleContractError(f"{label} stable identity changed after read")
    data = b"".join(chunks)
    if len(data) != before.st_size:
        raise StyleContractError(f"{label} byte length changed while being read")
    return data


def _verify_style_evidence_readback(
    directory: Path, expected: Mapping[str, bytes]
) -> None:
    directory, directory_identity = _require_canonical_non_reparse_directory(
        directory, "style evidence readback"
    )
    actual_names = sorted(os.listdir(directory))
    expected_names = sorted(expected)
    if actual_names != expected_names:
        raise StyleContractError("style evidence readback file set differs")
    for filename in expected_names:
        path = directory / filename
        actual = _read_regular_file_stable(
            path, f"style evidence readback {filename}"
        )
        wanted = expected[filename]
        if (
            len(actual) != len(wanted)
            or hashlib.sha256(actual).digest() != hashlib.sha256(wanted).digest()
            or actual != wanted
        ):
            raise StyleContractError(
                f"style evidence readback differs for {filename!r}"
            )
    if sorted(os.listdir(directory)) != expected_names:
        raise StyleContractError("style evidence readback file set changed")
    _require_stable_directory_identity(
        directory, directory_identity, "style evidence readback"
    )


def _read_style_evidence_generation(directory: Path) -> dict[str, bytes]:
    directory, directory_identity = _require_canonical_non_reparse_directory(
        directory, "style evidence generation"
    )
    actual_names = tuple(sorted(os.listdir(directory)))
    if actual_names != _STYLE_EVIDENCE_FILENAMES:
        raise StyleContractError("style evidence generation file set differs")
    result: dict[str, bytes] = {}
    for filename in _STYLE_EVIDENCE_FILENAMES:
        path = directory / filename
        result[filename] = _read_regular_file_stable(
            path, f"style evidence generation {filename}"
        )
    if tuple(sorted(os.listdir(directory))) != _STYLE_EVIDENCE_FILENAMES:
        raise StyleContractError("style evidence generation file set changed")
    _require_stable_directory_identity(
        directory, directory_identity, "style evidence generation"
    )
    return result


def _read_style_evidence_pointer(pointer_path: Path) -> str:
    raw = _read_regular_file_stable(
        pointer_path, "style evidence current pointer"
    )
    document = _parse_style_json_bytes(raw)
    if type(document) is not dict or set(document) != {
        "generationSha256",
        "schema",
    }:
        raise StyleContractError("style evidence current pointer shape differs")
    if document["schema"] != _STYLE_EVIDENCE_CURRENT_SCHEMA:
        raise StyleContractError("style evidence current pointer schema differs")
    generation_sha256 = _require_sha256(
        document["generationSha256"], "style evidence current generation SHA-256"
    )
    if raw != _style_evidence_current_bytes(generation_sha256):
        raise StyleContractError("style evidence current pointer is not canonical")
    return generation_sha256


def _parse_canonical_evidence_document(
    raw: bytes, label: str
) -> dict[str, object]:
    document = _parse_style_json_bytes(raw)
    if type(document) is not dict:
        raise StyleContractError(f"{label} must be a JSON object")
    try:
        canonical = _canonical_json_bytes(document)
    except (TypeError, ValueError) as error:
        raise StyleContractError(f"{label} contains a noncanonical JSON value") from error
    if raw != canonical:
        raise StyleContractError(f"{label} is not canonical JSON")
    return document


def _require_evidence_shape(
    document: Mapping[str, object], expected_keys: set[str], label: str
) -> None:
    if type(document) is not dict or set(document) != expected_keys:
        raise StyleContractError(f"{label} shape differs")


def _require_evidence_exact_int(
    value: object, label: str, *, minimum: int = 0
) -> int:
    if type(value) is not int or value < minimum:
        raise StyleContractError(f"{label} must be an exact integer >= {minimum}")
    return value


def _canonical_evidence_values_equal(first: object, second: object) -> bool:
    try:
        return _canonical_json_bytes(first) == _canonical_json_bytes(second)
    except (TypeError, ValueError):
        return False


def _require_evidence_semantic_equal(
    actual: object, expected: object, label: str
) -> None:
    if not _canonical_evidence_values_equal(actual, expected):
        raise StyleContractError(
            f"style evidence semantic cross-link {label} differs"
        )


_AUDIT_ENTRY_KEYS = {
    "featureKind",
    "layerGroup",
    "layerType",
    "outcome",
    "reason",
    "renderStyleTokenIds",
    "semanticSubtype",
    "sourceLayer",
    "sourceStyleLayerIds",
    "styleLayerId",
    "styleOrder",
    "supportedOperators",
}
_CATALOG_RULE_KEYS = {
    "adminLevel",
    "areaStyle",
    "coastline",
    "disputed",
    "drawOrder",
    "fadeInCenti",
    "fadeOutCenti",
    "fallbackTextSourceField",
    "featureKind",
    "filter",
    "inheritedStyleLayerIds",
    "intermittent",
    "labelStyle",
    "landEvidence",
    "layerGroup",
    "layout",
    "lineStyle",
    "maxZoomCentiExclusive",
    "minZoomCentiInclusive",
    "oneWay",
    "paint",
    "priorityBasis",
    "protectedStatus",
    "renderStyleTokenIds",
    "retainedPropertyNames",
    "rawStyleSha256",
    "semanticCollisionPriority",
    "semanticKind",
    "semanticPolicySha256",
    "semanticSubtype",
    "shield",
    "sourceLayer",
    "sourceStyleLayerIds",
    "styleLayerId",
    "styleOrder",
    "stylePolicySha256",
    "tunnel",
}


def _validate_style_audit_document(
    audit: dict[str, object],
) -> tuple[list[dict[str, object]], dict[str, int]]:
    _require_evidence_shape(
        audit,
        {"counts", "entries", "rawStyleSha256", "schema"},
        "style evidence audit",
    )
    if audit["schema"] != "flight-alert-exp8-style-audit-v1":
        raise StyleContractError("style evidence audit schema differs")
    _require_sha256(audit["rawStyleSha256"], "style evidence audit raw style SHA-256")
    counts = audit["counts"]
    if type(counts) is not dict or set(counts) != {
        "excluded",
        "extraction_failure",
        "included",
    }:
        raise StyleContractError("style evidence audit counts shape differs")
    typed_counts = {
        key: _require_evidence_exact_int(
            counts[key], f"style evidence audit {key} count"
        )
        for key in ("excluded", "extraction_failure", "included")
    }
    if typed_counts["extraction_failure"] != 0:
        raise StyleContractError("style evidence audit contains extraction failures")
    entries = audit["entries"]
    if type(entries) is not list:
        raise StyleContractError("style evidence audit entries must be a list")
    typed_entries: list[dict[str, object]] = []
    seen_style_ids: set[str] = set()
    included_count = 0
    excluded_count = 0
    for index, entry in enumerate(entries):
        _require_evidence_shape(entry, _AUDIT_ENTRY_KEYS, "style evidence audit entry")
        style_layer_id = _canonical_text(
            entry["styleLayerId"], "style evidence audit style-layer ID"
        )
        if not style_layer_id or style_layer_id in seen_style_ids:
            raise StyleContractError("style evidence audit ownership is not unique")
        seen_style_ids.add(style_layer_id)
        if _require_evidence_exact_int(
            entry["styleOrder"], "style evidence audit style order"
        ) != index:
            raise StyleContractError("style evidence audit order differs")
        if entry["layerType"] not in {"fill", "line", "symbol"}:
            raise StyleContractError("style evidence audit layer type differs")
        if entry["sourceLayer"] is not None:
            _canonical_text(entry["sourceLayer"], "style evidence audit source layer")
        for list_name in (
            "renderStyleTokenIds",
            "sourceStyleLayerIds",
            "supportedOperators",
        ):
            if type(entry[list_name]) is not list:
                raise StyleContractError(
                    f"style evidence audit {list_name} must be a list"
                )
        if any(
            type(value) is not str or value not in {"!in", "==", "all", "in"}
            for value in entry["supportedOperators"]
        ):
            raise StyleContractError("style evidence audit operator differs")
        if entry["outcome"] == "included":
            included_count += 1
            if entry["reason"] is not None:
                raise StyleContractError("included style audit entry has a reason")
            for name in ("featureKind", "layerGroup", "semanticSubtype"):
                _require_evidence_exact_int(
                    entry[name], f"included style evidence audit {name}"
                )
            for name in ("renderStyleTokenIds", "sourceStyleLayerIds"):
                values = entry[name]
                if len(values) != 1 or type(values[0]) is not int or values[0] <= 0:
                    raise StyleContractError(
                        f"included style evidence audit {name} differs"
                    )
        elif entry["outcome"] == "excluded":
            excluded_count += 1
            if type(entry["reason"]) is not str or not entry["reason"]:
                raise StyleContractError("excluded style audit entry lacks a reason")
            if any(
                entry[name] is not None
                for name in ("featureKind", "layerGroup", "semanticSubtype")
            ) or any(
                entry[name]
                for name in (
                    "renderStyleTokenIds",
                    "sourceStyleLayerIds",
                    "supportedOperators",
                )
            ):
                raise StyleContractError("excluded style audit entry retains semantics")
        else:
            raise StyleContractError("style evidence audit outcome differs")
        typed_entries.append(entry)
    if (
        included_count != typed_counts["included"]
        or excluded_count != typed_counts["excluded"]
        or included_count + excluded_count != len(typed_entries)
    ):
        raise StyleContractError("style evidence audit count cross-link differs")
    return typed_entries, typed_counts


def _filter_operator_names(value: object) -> tuple[str, ...]:
    if type(value) is bool:
        if value is not True:
            raise StyleContractError("style evidence catalog filter is malformed")
        return ()
    if type(value) is not list or not value or type(value[0]) is not str:
        raise StyleContractError("style evidence catalog filter is malformed")
    operator = value[0]
    if operator not in {"!in", "==", "all", "in"}:
        raise StyleContractError("style evidence catalog filter operator differs")
    nested: list[str] = [operator]
    if operator == "all":
        if len(value) < 2:
            raise StyleContractError("style evidence catalog all filter is empty")
        for child in value[1:]:
            nested.extend(_filter_operator_names(child))
    elif len(value) < 3:
        raise StyleContractError("style evidence catalog filter arity differs")
    return tuple(sorted(set(nested)))


def _expected_source_layer_policy_document() -> dict[str, object]:
    return {
        key: {
            "acceptedTypes": list(value.accepted_types),
            "fallbackLabelKind": value.fallback_label_kind,
            "fallbackLabelSubtype": value.fallback_label_subtype,
            "fallbackTextSourceField": value.fallback_text_source_field,
            "family": value.family,
            "layerGroup": value.layer_group.value,
        }
        for key, value in sorted(semantic_policy.SOURCE_LAYER_POLICIES.items())
    }


def _catalog_atomic_style_value(
    document: object, field_name: str
) -> AtomicStyleValue:
    if type(document) is not dict or type(document.get("kind")) is not str:
        raise StyleContractError("style evidence catalog rule schema is malformed")
    kind = document["kind"]
    if kind == "fixed_milli":
        _require_evidence_shape(
            document, {"kind", "value"}, "catalog fixed style value"
        )
        value: AtomicStyleValue = FixedNumber(
            _require_evidence_exact_int(
                document["value"], "catalog fixed style value", minimum=-(1 << 63)
            )
        )
        if not -(1 << 63) <= value.milli < 1 << 63:
            raise StyleContractError("style evidence catalog fixed value overflows")
    elif kind == "color_argb":
        _require_evidence_shape(
            document, {"argb", "kind"}, "catalog color style value"
        )
        value = ColorValue(
            _require_evidence_exact_int(
                document["argb"], "catalog color style value"
            )
        )
    elif kind == "fixed_tuple_milli":
        _require_evidence_shape(
            document, {"kind", "values"}, "catalog fixed-tuple style value"
        )
        values = document["values"]
        if type(values) is not list or not values or any(
            type(item) is not int or not -(1 << 63) <= item < 1 << 63
            for item in values
        ):
            raise StyleContractError(
                "style evidence catalog fixed-tuple value is malformed"
            )
        value = FixedTuple(tuple(values))
    elif kind == "string":
        _require_evidence_shape(
            document, {"kind", "value"}, "catalog string style value"
        )
        value = StringValue(
            _canonical_text(document["value"], "catalog string style value")
        )
    elif kind == "string_tuple":
        _require_evidence_shape(
            document, {"kind", "values"}, "catalog string-tuple style value"
        )
        values = document["values"]
        if type(values) is not list or not values:
            raise StyleContractError(
                "style evidence catalog string-tuple value is malformed"
            )
        value = StringTuple(
            tuple(
                _canonical_text(item, "catalog string-tuple style value")
                for item in values
            )
        )
    elif kind == "boolean":
        _require_evidence_shape(
            document, {"kind", "value"}, "catalog Boolean style value"
        )
        value = BooleanValue(document["value"])
    elif kind == "text_field":
        _require_evidence_shape(
            document, {"kind", "sourceField"}, "catalog text-field style value"
        )
        value = TextTemplate(document["sourceField"])
    else:
        raise StyleContractError("style evidence catalog rule schema has unknown value kind")

    expected_type: type[AtomicStyleValue]
    if field_name in _NUMERIC_FIELDS:
        expected_type = FixedNumber
    elif field_name in _COLOR_FIELDS:
        expected_type = ColorValue
    elif field_name in _FIXED_TUPLE_FIELDS:
        expected_type = FixedTuple
    elif field_name in _STRING_TUPLE_FIELDS:
        expected_type = StringTuple
    elif field_name in _BOOLEAN_FIELDS:
        expected_type = BooleanValue
    elif field_name in _STRING_FIELDS:
        expected_type = StringValue
    elif field_name == "text-field":
        expected_type = TextTemplate
    else:
        raise StyleContractError(
            f"style evidence catalog rule schema has unknown field {field_name!r}"
        )
    if type(value) is not expected_type:
        raise StyleContractError(
            "style evidence catalog rule schema has a field/value type mismatch"
        )
    if not _canonical_evidence_values_equal(value.document(), document):
        raise StyleContractError(
            "style evidence catalog rule schema has a noncanonical style value"
        )
    return value


def _catalog_compiled_style_value(
    document: object, field_name: str
) -> CompiledStyleValue:
    if type(document) is not dict:
        raise StyleContractError("style evidence catalog rule schema is malformed")
    if "kind" in document:
        return _catalog_atomic_style_value(document, field_name)
    _require_evidence_shape(
        document,
        {
            "baseMilli",
            "default",
            "functionType",
            "positionScale",
            "propertyName",
            "stops",
        },
        "catalog style function",
    )
    function_type = document["functionType"]
    if function_type not in {"categorical", "interval", "exponential"}:
        raise StyleContractError("style evidence catalog rule schema has unknown function")
    base_milli = _require_evidence_exact_int(
        document["baseMilli"], "catalog style function base", minimum=1
    )
    if base_milli >= 1 << 63:
        raise StyleContractError(
            "style evidence catalog rule schema function base overflows"
        )
    position_scale = document["positionScale"]
    if type(position_scale) is not int or position_scale not in {1, 100, 1000}:
        raise StyleContractError(
            "style evidence catalog rule schema has unknown position scale"
        )
    property_name = document["propertyName"]
    if property_name is not None:
        property_name = _canonical_text(
            property_name, "catalog style function property"
        )
        if property_name not in semantic_policy.RETAINED_RAW_PROPERTIES:
            raise StyleContractError(
                "style evidence catalog rule schema has unknown function property"
            )
    expected_scale = (
        1
        if function_type == "categorical"
        else (100 if property_name is None else 1000)
    )
    if position_scale != expected_scale:
        raise StyleContractError(
            "style evidence catalog rule schema has inconsistent position scale"
        )
    stop_documents = document["stops"]
    if type(stop_documents) is not list or not stop_documents:
        raise StyleContractError("style evidence catalog rule schema has no stops")
    stops: list[StyleStop] = []
    comparable_inputs: list[object] = []
    for stop_document in stop_documents:
        _require_evidence_shape(
            stop_document,
            {"inputCenti", "inputMilli", "inputValue", "output"},
            "catalog style stop",
        )
        input_centi = stop_document["inputCenti"]
        input_milli = stop_document["inputMilli"]
        input_value = stop_document["inputValue"]
        if function_type == "categorical":
            if (
                input_centi is not None
                or input_milli is not None
                or type(input_value) not in {str, int, bool}
            ):
                raise StyleContractError(
                    "style evidence catalog categorical stop is malformed"
                )
            comparable = (type(input_value).__name__, input_value)
        elif property_name is None:
            if (
                type(input_centi) is not int
                or not 0 <= input_centi <= 10_000
                or input_milli is not None
                or input_value is not None
            ):
                raise StyleContractError(
                    "style evidence catalog zoom stop is malformed"
                )
            comparable = input_centi
        else:
            if (
                input_centi is not None
                or type(input_milli) is not int
                or not -(1 << 63) <= input_milli < 1 << 63
                or input_value is not None
            ):
                raise StyleContractError(
                    "style evidence catalog property stop is malformed"
                )
            comparable = input_milli
        output = _catalog_atomic_style_value(
            stop_document["output"], field_name
        )
        stops.append(StyleStop(input_centi, input_milli, input_value, output))
        comparable_inputs.append(comparable)
    if function_type == "categorical":
        if property_name is None:
            raise StyleContractError(
                "style evidence catalog rule schema categorical function lacks a property"
            )
        if len(set(comparable_inputs)) != len(comparable_inputs):
            raise StyleContractError(
                "style evidence catalog categorical stops are duplicated"
            )
    elif any(
        comparable_inputs[index] >= comparable_inputs[index + 1]
        for index in range(len(comparable_inputs) - 1)
    ):
        raise StyleContractError(
            "style evidence catalog function stops are not increasing"
        )
    if function_type == "exponential":
        output_type = type(stops[0].output)
        if not _is_interpolable(stops[0].output) or any(
            type(stop.output) is not output_type for stop in stops
        ):
            raise StyleContractError(
                "style evidence catalog exponential outputs are inconsistent"
            )
        if output_type is FixedTuple and len(
            {len(stop.output.values_milli) for stop in stops}  # type: ignore[union-attr]
        ) != 1:
            raise StyleContractError(
                "style evidence catalog fixed-tuple outputs differ in length"
            )
    default_document = document["default"]
    default = (
        None
        if default_document is None
        else _catalog_atomic_style_value(default_document, field_name)
    )
    value = StyleFunction(
        function_type=function_type,
        property_name=property_name,
        base_milli=base_milli,
        stops=tuple(stops),
        default=default,
        position_scale=position_scale,
    )
    if not _canonical_evidence_values_equal(value.document(), document):
        raise StyleContractError(
            "style evidence catalog rule schema has a noncanonical function"
        )
    return value


def _catalog_style_values(
    document: object, label: str
) -> tuple[tuple[str, CompiledStyleValue], ...]:
    if type(document) is not dict:
        raise StyleContractError(f"style evidence catalog rule schema {label} differs")
    result: list[tuple[str, CompiledStyleValue]] = []
    for field_name in sorted(document):
        _canonical_text(field_name, f"catalog rule {label} field")
        result.append(
            (
                field_name,
                _catalog_compiled_style_value(document[field_name], field_name),
            )
        )
    return tuple(result)


def _catalog_label_style(document: object) -> LabelStyle | None:
    if document is None:
        return None
    _require_evidence_shape(
        document,
        {
            "collisionGroup",
            "fadeInCenti",
            "fadeOutCenti",
            "perGlyphRecordCount",
            "resolution",
            "textSourceFields",
            "wholeText",
        },
        "catalog label style",
    )
    if document["resolution"] != "current_centizoom_and_feature_properties":
        raise StyleContractError("style evidence catalog rule schema label resolution differs")
    fields = document["textSourceFields"]
    if type(fields) is not list:
        raise StyleContractError("style evidence catalog rule schema label fields differ")
    return LabelStyle(
        text_source_fields=tuple(fields),
        collision_group=document["collisionGroup"],
        fade_in_centi=document["fadeInCenti"],
        fade_out_centi=document["fadeOutCenti"],
        whole_text=document["wholeText"],
        per_glyph_record_count=document["perGlyphRecordCount"],
    )


def _catalog_line_style(document: object) -> LineStyle | None:
    if document is None:
        return None
    _require_evidence_shape(
        document,
        {"strokeRole", "styleLayerId", "styleOrder"},
        "catalog line style",
    )
    return LineStyle(
        style_layer_id=document["styleLayerId"],
        stroke_role=document["strokeRole"],
        style_order=document["styleOrder"],
    )


def _catalog_area_style(document: object) -> AreaStyle | None:
    if document is None:
        return None
    _require_evidence_shape(
        document,
        {
            "rendererDrawMode",
            "rendersProviderFill",
            "sourceFillEvidenceRetained",
            "sourceStyleType",
        },
        "catalog area style",
    )
    return AreaStyle(
        source_style_type=document["sourceStyleType"],
        renderer_draw_mode=document["rendererDrawMode"],
        source_fill_evidence_retained=document["sourceFillEvidenceRetained"],
        renders_provider_fill=document["rendersProviderFill"],
    )


def _decode_catalog_rule_schema(
    rule: Mapping[str, object], compiled_filter: CompiledFilter
) -> CompiledLayerRule:
    try:
        retained_names = rule["retainedPropertyNames"]
        inherited_names = rule["inheritedStyleLayerIds"]
        if type(retained_names) is not list or type(inherited_names) is not list:
            raise StyleContractError(
                "style evidence catalog rule schema property lists differ"
            )
        retained_tuple = tuple(
            _canonical_text(name, "catalog retained property")
            for name in retained_names
        )
        allowed_retained = set(semantic_policy.RETAINED_RAW_PROPERTIES) | {
            "_name",
            "_name_en",
            "_name_global",
        }
        if (
            retained_tuple != tuple(sorted(set(retained_tuple)))
            or not set(retained_tuple).issubset(allowed_retained)
        ):
            raise StyleContractError(
                "style evidence catalog rule schema retained properties differ"
            )
        inherited_tuple = tuple(
            _canonical_text(name, "catalog inherited style-layer ID")
            for name in inherited_names
        )
        label_style = _catalog_label_style(rule["labelStyle"])
        line_style = _catalog_line_style(rule["lineStyle"])
        area_style = _catalog_area_style(rule["areaStyle"])
        layout_values = _catalog_style_values(rule["layout"], "layout")
        paint_values = _catalog_style_values(rule["paint"], "paint")
        decoded = CompiledLayerRule(
            style_layer_id=rule["styleLayerId"],
            style_order=rule["styleOrder"],
            source_layer=rule["sourceLayer"],
            layer_group=LayerGroup(rule["layerGroup"]),
            feature_kind=FeatureKind(rule["featureKind"]),
            semantic_subtype=rule["semanticSubtype"],
            semantic_kind=rule["semanticKind"],
            source_style_layer_ids=tuple(rule["sourceStyleLayerIds"]),
            render_style_token_ids=tuple(rule["renderStyleTokenIds"]),
            compiled_filter=compiled_filter,
            layout_values=layout_values,
            paint_values=paint_values,
            min_zoom_centi=rule["minZoomCentiInclusive"],
            max_zoom_centi=rule["maxZoomCentiExclusive"],
            fade_in_centi=rule["fadeInCenti"],
            fade_out_centi=rule["fadeOutCenti"],
            draw_order=rule["drawOrder"],
            semantic_collision_priority=rule["semanticCollisionPriority"],
            priority_basis=rule["priorityBasis"],
            retained_property_names=retained_tuple,
            raw_style_sha256=rule["rawStyleSha256"],
            semantic_policy_sha256=rule["semanticPolicySha256"],
            style_policy_sha256=rule["stylePolicySha256"],
            label_style=label_style,
            line_style=line_style,
            area_style=area_style,
            admin_level=rule["adminLevel"],
            disputed=rule["disputed"],
            coastline=rule["coastline"],
            intermittent=rule["intermittent"],
            tunnel=rule["tunnel"],
            shield=rule["shield"],
            one_way=rule["oneWay"],
            land_evidence=LandEvidence(rule["landEvidence"]),
            protected_status=ProtectedStatus(rule["protectedStatus"]),
            fallback_text_source_field=rule["fallbackTextSourceField"],
            inherited_style_layer_ids=inherited_tuple,
        )
        if decoded.draw_order != decoded.style_order:
            raise StyleContractError(
                "style evidence catalog rule schema draw order differs"
            )
        if any(
            fade > 10_000
            for fade in (decoded.fade_in_centi, decoded.fade_out_centi)
        ):
            raise StyleContractError(
                "style evidence catalog rule schema fade is outside centizoom domain"
            )
        source_policy = semantic_policy.SOURCE_LAYER_POLICIES.get(
            decoded.source_layer
        )
        if source_policy is None:
            raise StyleContractError(
                "style evidence catalog rule schema source policy differs"
            )
        if (
            decoded.fallback_text_source_field
            != source_policy.fallback_text_source_field
        ):
            raise StyleContractError(
                "style evidence catalog rule schema fallback field differs"
            )

        layout = dict(layout_values)
        paint = dict(paint_values)
        expected_fade_in = 0
        expected_fade_out = 0
        if decoded.feature_kind is FeatureKind.LABEL:
            expected_label_style = _compile_label_style(
                layout,
                paint,
                min_zoom_centi=decoded.min_zoom_centi,
            )
            if decoded.label_style != expected_label_style:
                raise StyleContractError(
                    "style evidence catalog rule schema derived label style differs"
                )
            expected_fade_in = expected_label_style.fade_in_centi
            expected_fade_out = expected_label_style.fade_out_centi
        elif decoded.feature_kind is FeatureKind.LINE:
            expected_line_style = LineStyle(
                decoded.style_layer_id,
                "overlay" if decoded.one_way else _stroke_role(decoded.style_layer_id),
                decoded.style_order,
            )
            if decoded.line_style != expected_line_style:
                raise StyleContractError(
                    "style evidence catalog rule schema derived line style differs"
                )
        elif decoded.area_style != AreaStyle():
            raise StyleContractError(
                "style evidence catalog rule schema derived area style differs"
            )
        if (
            decoded.fade_in_centi != expected_fade_in
            or decoded.fade_out_centi != expected_fade_out
        ):
            raise StyleContractError(
                "style evidence catalog rule schema derived fade differs"
            )

        required_retained = set(semantic_policy.RETAINED_RAW_PROPERTIES)
        required_retained.update(compiled_filter.property_names)
        for _name, value in layout_values + paint_values:
            required_retained.update(_value_property_names(value))
        text_field_value = layout.get("text-field")
        if text_field_value is not None:
            required_retained.update(_text_source_fields(text_field_value))
        if decoded.fallback_text_source_field is not None:
            required_retained.add(decoded.fallback_text_source_field)
        if retained_tuple != tuple(sorted(required_retained)):
            raise StyleContractError(
                "style evidence catalog rule schema retained properties differ"
            )
        if line_style is not None and (
            line_style.style_layer_id != decoded.style_layer_id
            or line_style.style_order != decoded.style_order
        ):
            raise StyleContractError(
                "style evidence catalog rule schema line identity differs"
            )
        if not _canonical_evidence_values_equal(decoded.document(), rule):
            raise StyleContractError(
                "style evidence catalog rule schema is noncanonical"
            )
        return decoded
    except (
        KeyError,
        TypeError,
        ValueError,
        StyleContractError,
        semantic_policy.SemanticPolicyError,
    ) as error:
        if isinstance(error, StyleContractError) and "catalog rule schema" in str(error):
            raise
        raise StyleContractError(
            "style evidence catalog rule schema is malformed"
        ) from error


def _catalog_rule_policy_sha256(rule: Mapping[str, object]) -> str:
    render_style_token_ids = rule["renderStyleTokenIds"]
    if (
        type(render_style_token_ids) is not list
        or len(render_style_token_ids) != 1
        or type(render_style_token_ids[0]) is not int
    ):
        raise StyleContractError(
            "style evidence catalog rule render token identity differs"
        )
    document = {
        "classification": {
            "adminLevel": rule["adminLevel"],
            "coastline": rule["coastline"],
            "disputed": rule["disputed"],
            "featureKind": rule["featureKind"],
            "intermittent": rule["intermittent"],
            "kind": rule["semanticKind"],
            "landEvidence": rule["landEvidence"],
            "layerGroup": rule["layerGroup"],
            "oneWay": rule["oneWay"],
            "protectedStatus": rule["protectedStatus"],
            "renderStyleTokenId": render_style_token_ids[0],
            "semanticSubtype": rule["semanticSubtype"],
            "shield": rule["shield"],
            "tunnel": rule["tunnel"],
        },
        "fallbackTextSourceField": rule["fallbackTextSourceField"],
        "filter": rule["filter"],
        "layout": rule["layout"],
        "paint": rule["paint"],
        "minZoomCentiInclusive": rule["minZoomCentiInclusive"],
        "maxZoomCentiExclusive": rule["maxZoomCentiExclusive"],
        "fadeInCenti": rule["fadeInCenti"],
        "fadeOutCenti": rule["fadeOutCenti"],
        "drawOrder": rule["drawOrder"],
        "semanticCollisionPriority": rule["semanticCollisionPriority"],
        "priorityBasis": rule["priorityBasis"],
        "retainedPropertyNames": rule["retainedPropertyNames"],
        "labelStyle": rule["labelStyle"],
        "lineStyle": rule["lineStyle"],
        "areaStyle": rule["areaStyle"],
        "inheritedStyleLayerIds": rule["inheritedStyleLayerIds"],
        "rawStyleSha256": rule["rawStyleSha256"],
        "semanticPolicySha256": rule["semanticPolicySha256"],
        "sourceLayer": rule["sourceLayer"],
        "sourceStyleLayerIds": rule["sourceStyleLayerIds"],
        "renderStyleTokenIds": render_style_token_ids,
        "styleLayerId": rule["styleLayerId"],
        "styleOrder": rule["styleOrder"],
    }
    try:
        canonical = json.dumps(
            document,
            allow_nan=False,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise StyleContractError(
            "style evidence catalog rule policy document is malformed"
        ) from error
    return hashlib.sha256(b"FAE8RULEPOL3\0" + canonical).hexdigest()


def _validate_style_catalog_document(
    catalog: dict[str, object],
    audit_entries: Sequence[dict[str, object]],
    audit_counts: Mapping[str, int],
) -> list[dict[str, object]]:
    _require_evidence_shape(
        catalog,
        {
            "completeAuditLayerCount",
            "includedRuleCounts",
            "inputByteLength",
            "masterOnlyGeometrySemanticSubtypeIds",
            "presentationFilters",
            "presentationPolicySha256",
            "presentationSemanticSubtypeIds",
            "rawStyleSha256",
            "renderStyleTokenIds",
            "retainedRawPropertyNames",
            "rules",
            "schema",
            "semanticPolicySha256",
            "sourceLayerGroups",
            "sourceLayerPolicy",
            "sourceStyleLayerIds",
            "styleLayerCount",
            "styleLayerTypeCounts",
            "styleVersion",
            "supportedFilterOperators",
            "textBearingSymbolLayerCount",
            "transportSemanticSubtypeIds",
            "unsupportedIncludedExpressions",
        },
        "style evidence catalog",
    )
    if catalog["schema"] != "flight-alert-exp8-style-contract-v3":
        raise StyleContractError("style evidence catalog schema differs")
    runtime_cross_links = {
        "master-only semantic subtypes": (
            catalog["masterOnlyGeometrySemanticSubtypeIds"],
            sorted(item.value for item in semantic_policy.MasterOnlyGeometrySubtype),
        ),
        "presentation filters": (
            catalog["presentationFilters"],
            {
                key: list(value)
                for key, value in sorted(
                    semantic_policy.PRESENTATION_FILTER_SUBTYPES.items()
                )
            },
        ),
        "presentation policy SHA-256": (
            catalog["presentationPolicySha256"],
            PRESENTATION_POLICY_SHA256,
        ),
        "presentation semantic subtypes": (
            catalog["presentationSemanticSubtypeIds"],
            sorted(item.value for item in SemanticSubtype),
        ),
        "render style tokens": (
            catalog["renderStyleTokenIds"],
            {item.name: item.value for item in semantic_policy.RenderStyleToken},
        ),
        "retained raw properties": (
            catalog["retainedRawPropertyNames"],
            list(semantic_policy.RETAINED_RAW_PROPERTIES),
        ),
        "semantic policy SHA-256": (
            catalog["semanticPolicySha256"],
            semantic_policy.SEMANTIC_POLICY_SHA256,
        ),
        "source layer groups": (
            catalog["sourceLayerGroups"],
            {
                key: value.value
                for key, value in sorted(semantic_policy.SOURCE_LAYER_GROUPS.items())
            },
        ),
        "source layer policy": (
            catalog["sourceLayerPolicy"],
            _expected_source_layer_policy_document(),
        ),
        "transport semantic subtypes": (
            catalog["transportSemanticSubtypeIds"],
            {item.name: item.value for item in semantic_policy.TransportSubtype},
        ),
    }
    for label, (actual, expected) in runtime_cross_links.items():
        _require_evidence_semantic_equal(actual, expected, label)
    if catalog["styleVersion"] != 8 or type(catalog["styleVersion"]) is not int:
        raise StyleContractError("style evidence catalog style version differs")
    if catalog["supportedFilterOperators"] != ["!in", "==", "all", "in"]:
        raise StyleContractError("style evidence catalog filter operators differ")
    if (
        type(catalog["unsupportedIncludedExpressions"]) is not int
        or catalog["unsupportedIncludedExpressions"] != 0
    ):
        raise StyleContractError("style evidence catalog has unsupported expressions")
    layer_count = _require_evidence_exact_int(
        catalog["styleLayerCount"], "style evidence catalog layer count"
    )
    complete_count = _require_evidence_exact_int(
        catalog["completeAuditLayerCount"],
        "style evidence catalog complete audit count",
    )
    if layer_count != complete_count or layer_count != len(audit_entries):
        raise StyleContractError("style evidence semantic cross-link layer count differs")
    _require_evidence_exact_int(
        catalog["inputByteLength"], "style evidence catalog input byte length"
    )
    _require_sha256(
        catalog["rawStyleSha256"], "style evidence catalog raw style SHA-256"
    )
    type_counts = catalog["styleLayerTypeCounts"]
    if type(type_counts) is not dict or any(
        key not in {"fill", "line", "symbol"}
        or type(value) is not int
        or value < 0
        for key, value in type_counts.items()
    ):
        raise StyleContractError("style evidence catalog layer type counts differ")
    expected_type_counts: dict[str, int] = {}
    for entry in audit_entries:
        layer_type = entry["layerType"]
        expected_type_counts[layer_type] = expected_type_counts.get(layer_type, 0) + 1
    if type_counts != expected_type_counts or sum(type_counts.values()) != layer_count:
        raise StyleContractError("style evidence catalog layer type counts differ")
    text_count = _require_evidence_exact_int(
        catalog["textBearingSymbolLayerCount"],
        "style evidence catalog text-bearing count",
    )
    if text_count > type_counts.get("symbol", 0):
        raise StyleContractError("style evidence catalog text-bearing count differs")

    expected_source_ids = {
        style_layer_id: index
        for index, style_layer_id in enumerate(
            sorted(entry["styleLayerId"] for entry in audit_entries), start=1
        )
    }
    _require_evidence_semantic_equal(
        catalog["sourceStyleLayerIds"],
        expected_source_ids,
        "source style identities",
    )
    rules = catalog["rules"]
    if type(rules) is not list:
        raise StyleContractError("style evidence catalog rules must be a list")
    included_entries = [
        entry for entry in audit_entries if entry["outcome"] == "included"
    ]
    if len(rules) != audit_counts["included"] or len(rules) != len(included_entries):
        raise StyleContractError("style evidence semantic cross-link rule count differs")
    feature_kind_for_type = {
        "fill": FeatureKind.POLYGON_OUTLINE.value,
        "line": FeatureKind.LINE.value,
        "symbol": FeatureKind.LABEL.value,
    }
    typed_rules: list[dict[str, object]] = []
    for entry, rule in zip(included_entries, rules):
        _require_evidence_shape(
            rule, _CATALOG_RULE_KEYS, "style evidence catalog rule"
        )
        for name in (
            "coastline",
            "disputed",
            "intermittent",
            "oneWay",
            "shield",
            "tunnel",
        ):
            if type(rule[name]) is not bool:
                raise StyleContractError(
                    f"style evidence catalog rule {name} must be Boolean"
                )
        for name in (
            "drawOrder",
            "fadeInCenti",
            "fadeOutCenti",
            "featureKind",
            "layerGroup",
            "maxZoomCentiExclusive",
            "minZoomCentiInclusive",
            "semanticSubtype",
            "styleOrder",
        ):
            _require_evidence_exact_int(
                rule[name], f"style evidence catalog rule {name}"
            )
        if rule["adminLevel"] is not None:
            admin_level = _require_evidence_exact_int(
                rule["adminLevel"], "style evidence catalog rule admin level"
            )
            if admin_level > 5:
                raise StyleContractError("style evidence catalog admin level differs")
        for name in ("landEvidence", "protectedStatus"):
            _require_evidence_exact_int(
                rule[name], f"style evidence catalog rule {name}"
            )
        if rule["semanticCollisionPriority"] is not None:
            raise StyleContractError(
                "style evidence catalog fabricated semantic collision priority"
            )
        if rule["priorityBasis"] != "requires_feature_prominence_decision":
            raise StyleContractError("style evidence catalog priority basis differs")
        if type(rule["semanticKind"]) is not str or not rule["semanticKind"]:
            raise StyleContractError("style evidence catalog semantic kind differs")
        for name in (
            "renderStyleTokenIds",
            "sourceStyleLayerIds",
            "inheritedStyleLayerIds",
            "retainedPropertyNames",
        ):
            if type(rule[name]) is not list:
                raise StyleContractError(
                    f"style evidence catalog rule {name} must be a list"
                )
        if rule["inheritedStyleLayerIds"]:
            raise StyleContractError("style evidence catalog rule inherits style identity")
        for name in ("renderStyleTokenIds", "sourceStyleLayerIds"):
            if (
                len(rule[name]) != 1
                or type(rule[name][0]) is not int
                or rule[name][0] <= 0
            ):
                raise StyleContractError(
                    f"style evidence catalog rule {name} differs"
                )
        source_layer = _canonical_text(
            rule["sourceLayer"], "style evidence catalog rule source layer"
        )
        style_layer_id = _canonical_text(
            rule["styleLayerId"], "style evidence catalog rule style-layer ID"
        )
        source_policy = semantic_policy.SOURCE_LAYER_POLICIES.get(source_layer)
        if source_policy is None or entry["layerType"] not in source_policy.accepted_types:
            raise StyleContractError("style evidence catalog rule source policy differs")
        try:
            owned = semantic_policy.source_style_identity_is_owned(
                source_layer, style_layer_id
            )
        except semantic_policy.SemanticPolicyError as error:
            raise StyleContractError(
                "style evidence catalog source/style identity is malformed"
            ) from error
        if not owned:
            raise StyleContractError(
                "style evidence semantic cross-link source/style ownership differs"
            )
        if rule["sourceStyleLayerIds"] != [expected_source_ids[style_layer_id]]:
            raise StyleContractError(
                "style evidence semantic cross-link source style identity differs"
            )
        try:
            compiled_filter = compile_filter(
                None if rule["filter"] is True else rule["filter"]
            )
            if not _canonical_evidence_values_equal(
                compiled_filter.document(), rule["filter"]
            ):
                raise StyleContractError(
                    "style evidence catalog filter canonical form differs"
                )
            classification = semantic_policy.classification_for_style_rule(
                source_layer,
                style_layer_id,
                entry["layerType"],
                _selector_properties(compiled_filter),
            )
        except (StyleContractError, semantic_policy.SemanticPolicyError) as error:
            raise StyleContractError(
                "style evidence semantic cross-link classifier semantics differ"
            ) from error
        recorded_classification = {
            "adminLevel": rule["adminLevel"],
            "coastline": rule["coastline"],
            "disputed": rule["disputed"],
            "featureKind": rule["featureKind"],
            "intermittent": rule["intermittent"],
            "kind": rule["semanticKind"],
            "landEvidence": rule["landEvidence"],
            "layerGroup": rule["layerGroup"],
            "oneWay": rule["oneWay"],
            "protectedStatus": rule["protectedStatus"],
            "renderStyleTokenId": rule["renderStyleTokenIds"][0],
            "semanticSubtype": rule["semanticSubtype"],
            "shield": rule["shield"],
            "tunnel": rule["tunnel"],
        }
        _require_evidence_semantic_equal(
            recorded_classification,
            semantic_policy.semantic_classification_document(classification),
            "classifier semantics",
        )
        expected_feature_kind = (
            FeatureKind.LINE.value
            if style_layer_id in semantic_policy.ONE_WAY_STYLE_IDS
            else feature_kind_for_type[entry["layerType"]]
        )
        expected_layer_group = source_policy.layer_group.value
        if style_layer_id in semantic_policy._DISPUTED_LABEL_STYLE_CLASSIFICATIONS:
            disputed_classification = (
                semantic_policy.classify_disputed_label_style(
                    source_layer, style_layer_id
                )
            )
            assert disputed_classification is not None
            expected_layer_group = disputed_classification.layer_group.value
            expected_feature_kind = disputed_classification.feature_kind.value
        if (
            rule["layerGroup"] != expected_layer_group
            or rule["featureKind"] != expected_feature_kind
        ):
            raise StyleContractError(
                "style evidence semantic cross-link rule source semantics differ"
            )
        _require_sha256(rule["rawStyleSha256"], "catalog rule raw style SHA-256")
        _require_sha256(
            rule["semanticPolicySha256"], "catalog rule semantic policy SHA-256"
        )
        _require_sha256(rule["stylePolicySha256"], "catalog rule style policy SHA-256")
        if (
            rule["rawStyleSha256"] != catalog["rawStyleSha256"]
            or rule["semanticPolicySha256"] != semantic_policy.SEMANTIC_POLICY_SHA256
        ):
            raise StyleContractError(
                "style evidence semantic cross-link rule policy identity differs"
            )
        if rule["stylePolicySha256"] != _catalog_rule_policy_sha256(rule):
            raise StyleContractError(
                "style evidence semantic cross-link rule policy identity differs"
            )
        _decode_catalog_rule_schema(rule, compiled_filter)
        entry_projection = {
            "featureKind": rule["featureKind"],
            "layerGroup": rule["layerGroup"],
            "renderStyleTokenIds": rule["renderStyleTokenIds"],
            "semanticSubtype": rule["semanticSubtype"],
            "sourceLayer": rule["sourceLayer"],
            "sourceStyleLayerIds": rule["sourceStyleLayerIds"],
            "styleLayerId": rule["styleLayerId"],
            "styleOrder": rule["styleOrder"],
            "supportedOperators": list(_filter_operator_names(rule["filter"])),
        }
        audit_projection = {key: entry[key] for key in entry_projection}
        _require_evidence_semantic_equal(
            entry_projection,
            audit_projection,
            f"audit/catalog rule {style_layer_id!r}",
        )
        typed_rules.append(rule)

    expected_included_counts = {
        "boundaries": sum(
            semantic_policy.SOURCE_LAYER_POLICIES[rule["sourceLayer"]].family
            == "boundaries"
            for rule in typed_rules
        ),
        "labels": sum(
            rule["featureKind"] == FeatureKind.LABEL.value for rule in typed_rules
        ),
        "public_lands": sum(
            rule["layerGroup"] == LayerGroup.PUBLIC_LANDS.value
            for rule in typed_rules
        ),
        "transportation": sum(
            rule["layerGroup"] == LayerGroup.TRANSPORTATION.value
            for rule in typed_rules
        ),
        "water": sum(
            rule["layerGroup"] == LayerGroup.WATER.value for rule in typed_rules
        ),
        "water_polygons": sum(
            rule["layerGroup"] == LayerGroup.WATER.value
            and rule["featureKind"] == FeatureKind.POLYGON_OUTLINE.value
            for rule in typed_rules
        ),
    }
    _require_evidence_semantic_equal(
        catalog["includedRuleCounts"],
        expected_included_counts,
        "included rule counts",
    )
    return typed_rules


def _validate_style_manifest_document(
    manifest: dict[str, object],
    files: Mapping[str, bytes],
    audit: Mapping[str, object],
    catalog: Mapping[str, object],
    audit_counts: Mapping[str, int],
    rules: Sequence[Mapping[str, object]],
) -> None:
    _require_evidence_shape(
        manifest,
        {
            "auditByteLength",
            "auditSha256",
            "catalogByteLength",
            "catalogSha256",
            "includedRuleCount",
            "masterOnlyGeometrySemanticSubtypeIds",
            "presentationFilters",
            "presentationPolicySha256",
            "presentationSemanticSubtypeIds",
            "rawStyleByteLength",
            "rawStyleSha256",
            "reconciledStyleLayerCount",
            "schema",
            "semanticPolicySha256",
            "unsupportedIncludedExpressionCount",
        },
        "style evidence manifest",
    )
    if manifest["schema"] != "flight-alert-exp8-style-evidence-manifest-v3":
        raise StyleContractError("style evidence manifest schema differs")
    for filename, length_name, hash_name in (
        ("audit.json", "auditByteLength", "auditSha256"),
        ("catalog.json", "catalogByteLength", "catalogSha256"),
    ):
        length = _require_evidence_exact_int(
            manifest[length_name], f"style evidence manifest {length_name}"
        )
        digest = _require_sha256(
            manifest[hash_name], f"style evidence manifest {hash_name}"
        )
        if length != len(files[filename]) or digest != hashlib.sha256(
            files[filename]
        ).hexdigest():
            raise StyleContractError(
                f"style evidence manifest {filename} length/hash differs"
            )
    manifest_cross_links = {
        "master-only semantic subtypes": (
            manifest["masterOnlyGeometrySemanticSubtypeIds"],
            catalog["masterOnlyGeometrySemanticSubtypeIds"],
        ),
        "presentation filters": (
            manifest["presentationFilters"], catalog["presentationFilters"]
        ),
        "presentation policy SHA-256": (
            manifest["presentationPolicySha256"],
            catalog["presentationPolicySha256"],
        ),
        "presentation semantic subtypes": (
            manifest["presentationSemanticSubtypeIds"],
            catalog["presentationSemanticSubtypeIds"],
        ),
        "semantic policy SHA-256": (
            manifest["semanticPolicySha256"], catalog["semanticPolicySha256"]
        ),
        "raw style SHA-256": (
            manifest["rawStyleSha256"], catalog["rawStyleSha256"]
        ),
        "audit raw style SHA-256": (
            audit["rawStyleSha256"], catalog["rawStyleSha256"]
        ),
        "raw style byte length": (
            manifest["rawStyleByteLength"], catalog["inputByteLength"]
        ),
        "reconciled layer count": (
            manifest["reconciledStyleLayerCount"], catalog["styleLayerCount"]
        ),
        "included rule count": (
            manifest["includedRuleCount"], audit_counts["included"]
        ),
    }
    for label, (actual, expected) in manifest_cross_links.items():
        _require_evidence_semantic_equal(actual, expected, label)
    if manifest["includedRuleCount"] != len(rules):
        raise StyleContractError(
            "style evidence semantic cross-link manifest rule count differs"
        )
    if (
        type(manifest["unsupportedIncludedExpressionCount"]) is not int
        or manifest["unsupportedIncludedExpressionCount"] != 0
    ):
        raise StyleContractError("style evidence manifest has unsupported expressions")


def _validate_style_evidence_documents(files: Mapping[str, bytes]) -> None:
    audit = _parse_canonical_evidence_document(
        files["audit.json"], "style evidence audit"
    )
    catalog = _parse_canonical_evidence_document(
        files["catalog.json"], "style evidence catalog"
    )
    manifest = _parse_canonical_evidence_document(
        files["manifest.json"], "style evidence manifest"
    )
    audit_entries, audit_counts = _validate_style_audit_document(audit)
    rules = _validate_style_catalog_document(catalog, audit_entries, audit_counts)
    _validate_style_manifest_document(
        manifest, files, audit, catalog, audit_counts, rules
    )


def read_style_evidence(
    output_directory: Path | str,
    *,
    expected_generation_sha256: str,
) -> dict[str, bytes]:
    """Read and validate one externally pinned immutable evidence generation."""
    expected_generation_sha256 = _require_sha256(
        expected_generation_sha256, "trusted style evidence generation SHA-256"
    )
    output, output_identity = _require_canonical_non_reparse_directory(
        output_directory, "style evidence output"
    )
    generations, generations_identity = _require_canonical_non_reparse_directory(
        output / "generations", "style evidence generations path"
    )
    current_generation_sha256 = _read_style_evidence_pointer(
        output / "current.json"
    )
    if current_generation_sha256 != expected_generation_sha256:
        raise StyleContractError(
            "style evidence current pointer differs from trusted generation"
        )
    generation, generation_identity = _require_canonical_non_reparse_directory(
        generations / expected_generation_sha256,
        "style evidence generation",
    )
    files = _read_style_evidence_generation(generation)
    if (
        _style_evidence_generation_sha256(files)
        != expected_generation_sha256
    ):
        raise StyleContractError("style evidence generation identity differs")
    _validate_style_evidence_documents(files)
    _require_stable_directory_identity(
        generation, generation_identity, "style evidence generation"
    )
    _require_stable_directory_identity(
        generations, generations_identity, "style evidence generations path"
    )
    _require_stable_directory_identity(
        output, output_identity, "style evidence output"
    )
    return files


@dataclass(frozen=True, slots=True)
class _StyleEvidenceWriterLock:
    path: Path
    descriptor: int
    output: Path
    output_identity: tuple[int, int, int, int]


def _lock_descriptor_nonblocking(descriptor: int) -> None:
    os.lseek(descriptor, 0, os.SEEK_SET)
    if os.name == "nt":
        import msvcrt

        msvcrt.locking(descriptor, msvcrt.LK_NBLCK, 1)
        return
    if os.name == "posix":
        import fcntl

        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return
    raise StyleContractError(
        "style evidence advisory locking is unsupported on this platform"
    )


def _unlock_descriptor(descriptor: int) -> None:
    os.lseek(descriptor, 0, os.SEEK_SET)
    if os.name == "nt":
        import msvcrt

        msvcrt.locking(descriptor, msvcrt.LK_UNLCK, 1)
        return
    if os.name == "posix":
        import fcntl

        fcntl.flock(descriptor, fcntl.LOCK_UN)
        return
    raise StyleContractError(
        "style evidence advisory unlocking is unsupported on this platform"
    )


def _acquire_style_evidence_writer_lock(
    output: Path | str,
) -> _StyleEvidenceWriterLock:
    output, output_identity = _prepare_style_evidence_output(output)
    lock_path = output / ".writer.lock"
    preexisting_lock_identity: tuple[int, int, int, int] | None = None
    if os.path.lexists(lock_path):
        preexisting_information = os.lstat(lock_path)
        _validate_regular_file_stat(
            preexisting_information, "style evidence writer lock"
        )
        preexisting_lock_identity = _stat_identity(preexisting_information)
    open_flags = (
        os.O_CREAT
        | os.O_RDWR
        | getattr(os, "O_BINARY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    if preexisting_lock_identity is None:
        open_flags |= os.O_EXCL
    try:
        descriptor = os.open(lock_path, open_flags, 0o600)
    except FileExistsError as error:
        raise StyleContractError(
            "style evidence writer lock identity changed before open"
        ) from error
    locked = False
    try:
        opened = os.fstat(descriptor)
        _validate_regular_file_stat(opened, "style evidence writer lock")
        path_before_initialization = os.lstat(lock_path)
        _validate_regular_file_stat(
            path_before_initialization, "style evidence writer lock"
        )
        opened_identity = _stat_identity(opened)
        if (
            _stat_identity(path_before_initialization) != opened_identity
            or (
                preexisting_lock_identity is not None
                and preexisting_lock_identity != opened_identity
            )
        ):
            raise StyleContractError(
                "style evidence writer lock identity changed before initialization"
            )
        if opened.st_size == 0:
            if os.write(descriptor, b"\0") != 1:
                raise StyleContractError(
                    "style evidence writer lock initialization was incomplete"
                )
            os.fsync(descriptor)
        try:
            _lock_descriptor_nonblocking(descriptor)
        except OSError as error:
            raise StyleContractError(
                "another writer owns this style evidence output"
            ) from error
        locked = True
        path_information = os.lstat(lock_path)
        _validate_regular_file_stat(
            path_information, "style evidence writer lock"
        )
        if _stat_identity(path_information) != _stat_identity(os.fstat(descriptor)):
            raise StyleContractError("style evidence writer lock identity changed")
        ownership = (
            f"pid={os.getpid()}\nthread={threading.get_ident()}\n"
            f"output={output}\n"
        ).encode("utf-8")
        os.lseek(descriptor, 1, os.SEEK_SET)
        os.ftruncate(descriptor, 1)
        view = memoryview(ownership)
        written = 0
        while written < len(view):
            count = os.write(descriptor, view[written:])
            if count <= 0:
                raise StyleContractError(
                    "style evidence writer lock metadata write did not advance"
                )
            written += count
        os.fsync(descriptor)
        _require_stable_directory_identity(
            output, output_identity, "style evidence output"
        )
        return _StyleEvidenceWriterLock(
            path=lock_path,
            descriptor=descriptor,
            output=output,
            output_identity=output_identity,
        )
    except BaseException:
        if locked:
            try:
                _unlock_descriptor(descriptor)
            except BaseException:
                pass
        os.close(descriptor)
        raise


def _release_style_evidence_writer_lock(
    writer_lock: _StyleEvidenceWriterLock,
) -> None:
    release_error: BaseException | None = None
    try:
        _require_stable_directory_identity(
            writer_lock.output,
            writer_lock.output_identity,
            "style evidence output",
        )
        path_information = os.lstat(writer_lock.path)
        _validate_regular_file_stat(
            path_information, "style evidence writer lock"
        )
        if _stat_identity(path_information) != _stat_identity(
            os.fstat(writer_lock.descriptor)
        ):
            raise StyleContractError("style evidence writer lock identity changed")
    except BaseException as error:
        release_error = error
    try:
        _unlock_descriptor(writer_lock.descriptor)
    except BaseException as error:
        if release_error is None:
            release_error = error
    finally:
        os.close(writer_lock.descriptor)
    if release_error is not None:
        raise release_error


def _sync_posix_directory_metadata(directory: Path) -> None:
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
    descriptor = os.open(directory, flags)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _windows_replace_write_through(source: Path, destination: Path) -> None:
    import ctypes
    from ctypes import wintypes

    move_file_ex = ctypes.WinDLL("kernel32", use_last_error=True).MoveFileExW
    move_file_ex.argtypes = (
        wintypes.LPCWSTR,
        wintypes.LPCWSTR,
        wintypes.DWORD,
    )
    move_file_ex.restype = wintypes.BOOL
    movefile_replace_existing = 0x1
    movefile_write_through = 0x8
    if not move_file_ex(
        str(source),
        str(destination),
        movefile_replace_existing | movefile_write_through,
    ):
        raise ctypes.WinError(ctypes.get_last_error())


def _prepare_directory_metadata_for_commit(directory: Path) -> None:
    if os.name == "nt":
        # Each child was flushed and MoveFileExW supplies the directory-entry barrier.
        return
    if os.name == "posix":
        _sync_posix_directory_metadata(directory)
        return
    raise StyleContractError(
        "durable style evidence directory commits are unsupported on this platform"
    )


def _commit_replace_with_metadata_barrier(
    source: Path, destination: Path
) -> None:
    source = Path(source)
    destination = Path(destination)
    if source.parent != destination.parent:
        raise StyleContractError(
            "style evidence durable replace must stay in one directory"
        )
    if os.name == "nt":
        _windows_replace_write_through(source, destination)
        return
    if os.name == "posix":
        os.replace(source, destination)
        _sync_posix_directory_metadata(destination.parent)
        return
    raise StyleContractError(
        "durable style evidence replace is unsupported on this platform"
    )


def _write_descriptor_fsync(descriptor: int, data: bytes) -> None:
    try:
        view = memoryview(data)
        written = 0
        while written < len(view):
            count = os.write(descriptor, view[written:])
            if count <= 0:
                raise StyleContractError("style evidence file write did not advance")
            written += count
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _write_file_fsync(path: Path, data: bytes) -> None:
    descriptor = os.open(
        path,
        os.O_CREAT
        | os.O_EXCL
        | os.O_WRONLY
        | getattr(os, "O_BINARY", 0),
        0o600,
    )
    _write_descriptor_fsync(descriptor, data)


def _verify_pointer_readback(path: Path, expected: bytes) -> None:
    if (
        _read_regular_file_stable(path, "style evidence current pointer readback")
        != expected
    ):
        raise StyleContractError("style evidence current pointer readback differs")


def _remove_owned_directory(
    path: Path,
    expected_identity: tuple[int, int, int, int],
    label: str,
) -> None:
    if not os.path.lexists(path):
        return
    canonical, actual_identity = _require_canonical_non_reparse_directory(
        path, label
    )
    if canonical != path or actual_identity != expected_identity:
        raise StyleContractError(f"{label} stable identity changed before cleanup")
    shutil.rmtree(path)


def _remove_owned_regular_file(
    path: Path,
    expected_identity: tuple[int, int, int, int],
    label: str,
) -> None:
    if not os.path.lexists(path):
        return
    information = os.lstat(path)
    _validate_regular_file_stat(information, label)
    if _stat_identity(information) != expected_identity:
        raise StyleContractError(f"{label} stable identity changed before cleanup")
    path.unlink()


def _write_style_evidence_generation_owned(
    contract: StyleContract,
    output: Path,
    *,
    output_identity: tuple[int, int, int, int],
    publication_hook: Callable[[str, Path], None] | None,
) -> None:
    expected = style_evidence_bytes(contract)
    generation_sha256 = _style_evidence_generation_sha256(expected)
    _require_stable_directory_identity(
        output, output_identity, "style evidence output"
    )
    generations = output / "generations"
    generations.mkdir(exist_ok=True)
    generations, generations_identity = _require_canonical_non_reparse_directory(
        generations, "style evidence generations path"
    )
    staging = Path(
        tempfile.mkdtemp(
            prefix=".generation.", suffix=".staging", dir=str(generations)
        )
    )
    staging, staging_identity = _require_canonical_non_reparse_directory(
        staging, "style evidence generation staging"
    )
    generation = generations / generation_sha256
    generation_created = False
    generation_identity: tuple[int, int, int, int] | None = None
    pointer_staging: Path | None = None
    pointer_staging_identity: tuple[int, int, int, int] | None = None
    pointer_commit_attempted = False
    current_replaced = False
    try:
        for filename, data in expected.items():
            _write_file_fsync(staging / filename, data)
        if publication_hook is not None:
            publication_hook("before_generation_readback", staging)
        _verify_style_evidence_readback(staging, expected)
        if publication_hook is not None:
            publication_hook("before_generation_publish", staging)
        _require_stable_directory_identity(
            output, output_identity, "style evidence output"
        )
        _require_stable_directory_identity(
            generations,
            generations_identity,
            "style evidence generations path",
        )
        if os.path.lexists(generation):
            _verify_style_evidence_readback(generation, expected)
            _remove_owned_directory(
                staging,
                staging_identity,
                "style evidence generation staging",
            )
        else:
            _prepare_directory_metadata_for_commit(staging)
            _commit_replace_with_metadata_barrier(staging, generation)
            generation_created = True
        generation, generation_identity = _require_canonical_non_reparse_directory(
            generation, "style evidence generation"
        )
        if publication_hook is not None:
            publication_hook("after_generation_published", generation)
        _verify_style_evidence_readback(generation, expected)

        pointer_bytes = _style_evidence_current_bytes(generation_sha256)
        descriptor, pointer_name = tempfile.mkstemp(
            prefix=".current.", suffix=".staging", dir=str(output)
        )
        pointer_staging = Path(pointer_name)
        _write_descriptor_fsync(descriptor, pointer_bytes)
        pointer_information = os.lstat(pointer_staging)
        _validate_regular_file_stat(
            pointer_information, "style evidence current pointer staging"
        )
        pointer_staging_identity = _stat_identity(pointer_information)
        if publication_hook is not None:
            publication_hook("before_pointer_readback", pointer_staging)
        _verify_pointer_readback(pointer_staging, pointer_bytes)
        if publication_hook is not None:
            publication_hook("before_current_replace", pointer_staging)
        _require_stable_directory_identity(
            output, output_identity, "style evidence output"
        )
        current = output / "current.json"
        if os.path.lexists(current):
            _read_regular_file_stable(current, "style evidence current pointer")
        pointer_commit_attempted = True
        _commit_replace_with_metadata_barrier(pointer_staging, current)
        current_replaced = True
        if publication_hook is not None:
            publication_hook("after_current_replaced", current)
        if read_style_evidence(
            output,
            expected_generation_sha256=generation_sha256,
        ) != expected:
            raise StyleContractError("published style evidence generation differs")
        if publication_hook is not None:
            publication_hook("after_current_readback", generation)
    except BaseException:
        generation_is_referenced = current_replaced
        if pointer_commit_attempted and not current_replaced:
            current = output / "current.json"
            if os.path.lexists(current):
                try:
                    generation_is_referenced = (
                        _read_regular_file_stable(
                            current, "style evidence current pointer"
                        )
                        == pointer_bytes
                    )
                except StyleContractError:
                    # A completed replace followed by a failed durability barrier is
                    # indistinguishable from a concurrently damaged pointer. Retaining
                    # the generation is the only cleanup choice that cannot dangle it.
                    generation_is_referenced = True
        if pointer_staging is not None and pointer_staging_identity is not None:
            _remove_owned_regular_file(
                pointer_staging,
                pointer_staging_identity,
                "style evidence current pointer staging",
            )
        if os.path.lexists(staging):
            _remove_owned_directory(
                staging,
                staging_identity,
                "style evidence generation staging",
            )
        if (
            generation_created
            and not generation_is_referenced
            and generation_identity is not None
            and os.path.lexists(generation)
        ):
            _remove_owned_directory(
                generation,
                generation_identity,
                "style evidence unreferenced generation",
            )
        raise


def write_style_evidence(
    contract: StyleContract,
    output_directory: Path | str,
    *,
    publication_hook: Callable[[str, Path], None] | None = None,
) -> None:
    """Publish immutable evidence with durable generation and pointer commits.

    Generation-directory replacement is not an atomicity premise. Readers supply an
    independently trusted generation SHA-256 and verify ``current.json`` against it.
    The caller provisions the immediate parent and prevents concurrent namespace
    replacement of that trusted parent while publication is active.
    """
    if publication_hook is not None and not callable(publication_hook):
        raise StyleContractError("style evidence publication hook must be callable")
    writer_lock = _acquire_style_evidence_writer_lock(output_directory)
    try:
        output = writer_lock.output
        current = output / "current.json"
        if os.path.lexists(current):
            _read_regular_file_stable(current, "style evidence current pointer")
        _write_style_evidence_generation_owned(
            contract,
            output,
            output_identity=writer_lock.output_identity,
            publication_hook=publication_hook,
        )
    finally:
        _release_style_evidence_writer_lock(writer_lock)
