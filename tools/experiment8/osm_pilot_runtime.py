from __future__ import annotations

import hashlib
import importlib
import inspect
import json
import sys
from pathlib import Path
from types import CodeType, FunctionType, ModuleType

import tools.experiment8.osm_hydro_source as osm_hydro_source
from tools.experiment8.osm_pilot_common import ProvenanceVerificationError


_CAPTURED_SELECTION_CALLABLE = osm_hydro_source.encode_selection_material
_CAPTURED_SELECTION_CODE = _CAPTURED_SELECTION_CALLABLE.__code__
_SELECTION_CALLABLE_MODULE = "tools.experiment8.osm_hydro_source"
_SELECTION_CALLABLE_QUALNAME = "encode_selection_material"
_SELECTION_SNAPSHOT_MODULE_NAME = (
    "tools.experiment8._verified_osm_hydro_source_snapshot"
)
_SELECTOR_LOGICAL_FILENAME = "tools/experiment8/osm_hydro_source.py"

PINNED_PYTHON_VERSION = "3.11.1"
PINNED_PYTHON_IMPLEMENTATION = "CPython"
PINNED_PYTHON_EXECUTABLE_SHA256 = (
    "0cbf71efa09ec4ce62d95c1448553314728ed5850720c8ad40352bfbb39be99a"
)
PINNED_PYTHON_PLATFORM = "Windows-10-10.0.19045-SP0"
PINNED_PYTHON_CACHE_TAG = "cpython-311"
PINNED_PYTHON_FLAGS = (
    ("bytes_warning", 0),
    ("debug", 0),
    ("dev_mode", False),
    ("dont_write_bytecode", 0),
    ("hash_randomization", 1),
    ("ignore_environment", 0),
    ("inspect", 0),
    ("int_max_str_digits", -1),
    ("interactive", 0),
    ("isolated", 0),
    ("no_site", 0),
    ("no_user_site", 0),
    ("optimize", 0),
    ("quiet", 0),
    ("safe_path", False),
    ("utf8_mode", 0),
    ("verbose", 0),
    ("warn_default_encoding", 0),
)
_PINNED_PYTHON_RUNTIME_MODULES = (
    (
        "extensions/_decimal.pyd",
        "_decimal",
        "b8409829dc4fde70f38754de55d3090a1cd52c78ffece2a08572a58de3af294d",
    ),
    (
        "extensions/_elementtree.pyd",
        "_elementtree",
        "b7ec9604084fa090135032633a38b0564f3f5f37fe1446197d008b78975e0418",
    ),
    (
        "extensions/_hashlib.pyd",
        "_hashlib",
        "441d32922122e59f75a728cc818f8e50613866a6c3dec627098e6cc6c53624e2",
    ),
    (
        "extensions/pyexpat.pyd",
        "pyexpat",
        "867012edb8a6acd2131c4698b69bb94e6ba07607035e7c621aaa24262817e55b",
    ),
    (
        "stdlib/calendar.py",
        "calendar",
        "7c8a3a6bc0791f6f06d213d4cd553edc3110ce338c4cd60f7d72689c0218dc3d",
    ),
    (
        "stdlib/collections/__init__.py",
        "collections",
        "f0067232ba9d4e8f7186e7c9c78aea16cc78494089d299e91dbd1f55f54161de",
    ),
    (
        "stdlib/dataclasses.py",
        "dataclasses",
        "011d2c5e9bcb6a1b4374cd771663514bee3b65f9729f333df94e0d6a1fc6147c",
    ),
    (
        "stdlib/datetime.py",
        "datetime",
        "178ef8f617fbf6135ced13064336fadad37ff78b27bb2f10a03dec25dd0e9240",
    ),
    (
        "stdlib/decimal.py",
        "decimal",
        "f205d8dc95d81b5d2b59362cbe0e385cfeeb98c14a70971f3372be1403378b03",
    ),
    (
        "stdlib/email/_parseaddr.py",
        "email._parseaddr",
        "4960c31f0039780f316149a3773367a3aeec3bb17d360776334d9b9e688da908",
    ),
    (
        "stdlib/email/utils.py",
        "email.utils",
        "521840d2f6a4500babaf7df27a2b1fed2e05ac0350baf367d5454c09acbee525",
    ),
    (
        "stdlib/hashlib.py",
        "hashlib",
        "158b8e4d4f5fcecef24b840af0bee5632b27f948c74faf0810cc598b508e4f97",
    ),
    (
        "stdlib/inspect.py",
        "inspect",
        "c4f44ca133d26697542e3a2fd8da3fd6b4fcd7634823ceb1541b4eed5830578a",
    ),
    (
        "stdlib/json/__init__.py",
        "json",
        "feb17670e443e5db2723f217727dcc5d5e155c40e4e6935b16061c88542f24e7",
    ),
    (
        "stdlib/json/decoder.py",
        "json.decoder",
        "b719fbcfcebd2b174f076e71292e22b1a17d9e258dbe896c768325383bad4f80",
    ),
    (
        "stdlib/json/encoder.py",
        "json.encoder",
        "366a611e210eab51067773e1012b692a9b5c90dc5695bedbeb3a3fccec039e39",
    ),
    (
        "stdlib/json/scanner.py",
        "json.scanner",
        "9841566fb17315ebdd40a1ca9cb214f02cde7171b187d4dc821c80120ea853c3",
    ),
    (
        "stdlib/pathlib.py",
        "pathlib",
        "c7543376b6cbab9886689d710994dcde66733f5d3a4b5711c90dc12c6c2c5801",
    ),
    (
        "stdlib/platform.py",
        "platform",
        "20298de350fa159b8cc9d4a045b974fd0dda978078b357eafb507b6e70b13e37",
    ),
    (
        "stdlib/re/__init__.py",
        "re",
        "e912249b8b1e2880ff212ef728e8becba893ce31bcb68aa2bfbcab2c812e61be",
    ),
    (
        "stdlib/re/_casefix.py",
        "re._casefix",
        "a1a8ce5d2051c96abb0c854f4a9c513c219e821f7285d28330f84eca71c341e2",
    ),
    (
        "stdlib/re/_compiler.py",
        "re._compiler",
        "712bb32f1d9d71e4f08486e5336c1303d65200d3249b1f6e0bef770f68164bbd",
    ),
    (
        "stdlib/re/_constants.py",
        "re._constants",
        "581e6c50e7f71e73f909567a4f2a06bed6b0f95098fdb60a18b8e3d39aa5b5e8",
    ),
    (
        "stdlib/re/_parser.py",
        "re._parser",
        "43c6a6d0873cfbbb1d76a74e72a5f7f6c8d0b09c4e9f427b27288d02d130384d",
    ),
    (
        "stdlib/shutil.py",
        "shutil",
        "2a6b6cfcf64df6a423cfe9d5c568e28eb8e1df03eb802418e131d96c952f0277",
    ),
    (
        "stdlib/tempfile.py",
        "tempfile",
        "130f717f7787a52064572f3138ef204f2be65773b831c947b4960b84359480b6",
    ),
    (
        "stdlib/types.py",
        "types",
        "c762d2321a143aa9a7eaeb30f8ed8042c10a3e98e4fa678e4f659e2136bf85b5",
    ),
    (
        "stdlib/typing.py",
        "typing",
        "921e4d2397c8cb34394a7bf2532250088cdf8a48fec50cd64350c1f5f5186e07",
    ),
    (
        "stdlib/xml/etree/ElementPath.py",
        "xml.etree.ElementPath",
        "9cf2c5248524016c9044bdfe5f81ac1c9ad6edc0a04ac8433a33ead7f7d52413",
    ),
    (
        "stdlib/xml/etree/ElementTree.py",
        "xml.etree.ElementTree",
        "23568c74e60527f84f88468e37325baf76920e762f4828be6c431b620eaae70f",
    ),
)
_PINNED_PYTHON_DLL_SHA256 = (
    "aa1e959dcff75a343b448a797d8a5a041eb03b27565a30f70fd081df7a285038"
)
_PINNED_PYTHON_NATIVE_FILES = (
    (
        "native/libcrypto-1_1.dll",
        ("DLLs", "libcrypto-1_1.dll"),
        "976ce72efd0a8aeeb6e21ad441aa9138434314ea07f777432205947cdb149541",
    ),
    (
        "runtime/python3.dll",
        ("python3.dll",),
        "ddd2c77222e2c693ef73d142422d6bf37d6a37deead17e70741b0ac5c9fe095b",
    ),
    (
        "runtime/vcruntime140.dll",
        ("vcruntime140.dll",),
        "76fdb83fde238226b5bebaf3392ee562e2cb7ca8d3ef75983bf5f9d6c7119644",
    ),
    (
        "runtime/vcruntime140_1.dll",
        ("vcruntime140_1.dll",),
        "e0b66601cc28ecb171c3d4b7ac690c667f47da6b6183bff80604c84c00d265ab",
    ),
)


def _python_runtime_dependency_specs() -> tuple[tuple[str, Path, str], ...]:
    python_root = Path(sys.executable).resolve(strict=True).parent
    specs: list[tuple[str, Path, str]] = [
        (
            "runtime/python311.dll",
            python_root / "python311.dll",
            _PINNED_PYTHON_DLL_SHA256,
        )
    ]
    specs.extend(
        (
            logical_name,
            python_root.joinpath(*relative_parts),
            expected_sha256,
        )
        for logical_name, relative_parts, expected_sha256 in (
            _PINNED_PYTHON_NATIVE_FILES
        )
    )
    for logical_name, module_name, expected_sha256 in (
        _PINNED_PYTHON_RUNTIME_MODULES
    ):
        module = importlib.import_module(module_name)
        module_file = getattr(module, "__file__", None)
        if type(module_file) is not str:
            raise ProvenanceVerificationError(
                f"pinned Python runtime module {module_name!r} has no exact file"
            )
        specs.append((logical_name, Path(module_file), expected_sha256))
    return tuple(sorted(specs, key=lambda item: item[0]))


def _current_python_flags() -> tuple[tuple[str, int | bool], ...]:
    return tuple((name, getattr(sys.flags, name)) for name, _ in PINNED_PYTHON_FLAGS)


def _attest_selection_callable_identity() -> Path:
    current = getattr(osm_hydro_source, "encode_selection_material", None)
    if current is not _CAPTURED_SELECTION_CALLABLE:
        raise ProvenanceVerificationError(
            "imported selection callable identity changed after provenance import"
        )
    if (
        not callable(_CAPTURED_SELECTION_CALLABLE)
        or getattr(_CAPTURED_SELECTION_CALLABLE, "__module__", None)
        != _SELECTION_CALLABLE_MODULE
        or getattr(_CAPTURED_SELECTION_CALLABLE, "__qualname__", None)
        != _SELECTION_CALLABLE_QUALNAME
        or getattr(_CAPTURED_SELECTION_CALLABLE, "__code__", None)
        is not _CAPTURED_SELECTION_CODE
        or inspect.getmodule(_CAPTURED_SELECTION_CALLABLE) is not osm_hydro_source
        or _code_structure_sha256(_CAPTURED_SELECTION_CODE)
        != _CAPTURED_SELECTION_CODE_SHA256
    ):
        raise ProvenanceVerificationError(
            "captured selection callable identity or qualified name is invalid"
        )
    callable_source = inspect.getsourcefile(_CAPTURED_SELECTION_CALLABLE)
    module_source = inspect.getsourcefile(osm_hydro_source)
    module_file = getattr(osm_hydro_source, "__file__", None)
    code_source = getattr(_CAPTURED_SELECTION_CODE, "co_filename", None)
    if None in (callable_source, module_source, module_file, code_source):
        raise ProvenanceVerificationError(
            "captured selection callable has no exact source-path identity"
        )
    try:
        paths = {
            Path(str(value)).resolve(strict=True)
            for value in (callable_source, module_source, module_file, code_source)
        }
    except OSError as error:
        raise ProvenanceVerificationError(
            f"captured selection callable source path is unavailable: {error}"
        ) from error
    if len(paths) != 1:
        raise ProvenanceVerificationError(
            "captured selection callable does not resolve to the imported module source"
        )
    return paths.pop()


def _code_constant_structure(value: object) -> object:
    if value is None:
        return ["none"]
    if value is Ellipsis:
        return ["ellipsis"]
    if type(value) is bool:
        return ["bool", value]
    if type(value) is int:
        return ["int", str(value)]
    if type(value) is float:
        return ["float", value.hex()]
    if type(value) is complex:
        return ["complex", value.real.hex(), value.imag.hex()]
    if type(value) is str:
        return ["str", value]
    if type(value) is bytes:
        return ["bytes", value.hex()]
    if type(value) is tuple:
        return ["tuple", [_code_constant_structure(item) for item in value]]
    if type(value) is frozenset:
        children = [_code_constant_structure(item) for item in value]
        return [
            "frozenset",
            sorted(
                children,
                key=lambda item: json.dumps(
                    item,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                ),
            ),
        ]
    if type(value) is CodeType:
        return ["code", _code_structure(value)]
    raise ProvenanceVerificationError(
        "selector code contains unsupported constant type "
        f"{type(value).__name__!r}"
    )


def _code_structure(code: CodeType) -> dict[str, object]:
    if type(code) is not CodeType:
        raise ProvenanceVerificationError("selector code object type is not exact")
    return {
        "argCount": code.co_argcount,
        "cellVars": list(code.co_cellvars),
        "code": code.co_code.hex(),
        "constants": [_code_constant_structure(item) for item in code.co_consts],
        "exceptionTable": code.co_exceptiontable.hex(),
        "filename": _SELECTOR_LOGICAL_FILENAME,
        "firstLineNumber": code.co_firstlineno,
        "flags": code.co_flags,
        "freeVars": list(code.co_freevars),
        "keywordOnlyArgCount": code.co_kwonlyargcount,
        "lineTable": code.co_linetable.hex(),
        "localCount": code.co_nlocals,
        "name": code.co_name,
        "names": list(code.co_names),
        "positionalOnlyArgCount": code.co_posonlyargcount,
        "qualifiedName": code.co_qualname,
        "stackSize": code.co_stacksize,
        "variableNames": list(code.co_varnames),
    }


def _code_structure_sha256(code: CodeType) -> str:
    encoded = (
        json.dumps(
            _code_structure(code),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _compiled_selector_snapshot(
    source: bytes,
    source_path: Path,
) -> tuple[CodeType, CodeType]:
    if type(source) is not bytes or not isinstance(source_path, Path):
        raise ProvenanceVerificationError(
            "retained selector source bytes or path are unavailable"
        )
    try:
        module_code = compile(
            source,
            _SELECTOR_LOGICAL_FILENAME,
            "exec",
            flags=0,
            dont_inherit=True,
            optimize=sys.flags.optimize,
        )
    except (SyntaxError, TypeError, ValueError, MemoryError) as error:
        raise ProvenanceVerificationError(
            f"retained selector source does not compile under pinned Python: {error}"
        ) from error
    pending = [module_code]
    matches: list[CodeType] = []
    while pending:
        code = pending.pop()
        for constant in code.co_consts:
            if type(constant) is CodeType:
                pending.append(constant)
                if (
                    constant.co_name == _SELECTION_CALLABLE_QUALNAME
                    and constant.co_qualname == _SELECTION_CALLABLE_QUALNAME
                ):
                    matches.append(constant)
    if len(matches) != 1:
        raise ProvenanceVerificationError(
            "retained selector source must compile to exactly one "
            f"{_SELECTION_CALLABLE_QUALNAME!r} code object"
        )
    return module_code, matches[0]


def _load_selector_snapshot(
    source: bytes,
    source_path: Path,
    expected_source_sha256: str,
) -> FunctionType:
    if (
        type(source) is not bytes
        or hashlib.sha256(source).hexdigest() != expected_source_sha256
    ):
        raise ProvenanceVerificationError(
            "selector snapshot bytes do not match their verified source hash"
        )
    module_code, selection_code = _compiled_selector_snapshot(source, source_path)
    selection_code_sha256 = _code_structure_sha256(selection_code)
    if selection_code_sha256 != _CAPTURED_SELECTION_CODE_SHA256:
        raise ProvenanceVerificationError(
            "compiled selector source does not match the captured callable code"
        )
    if _SELECTION_SNAPSHOT_MODULE_NAME in sys.modules:
        raise ProvenanceVerificationError(
            "isolated selector snapshot module name is already occupied"
        )
    module = ModuleType(_SELECTION_SNAPSHOT_MODULE_NAME)
    module.__file__ = _SELECTOR_LOGICAL_FILENAME
    module.__package__ = "tools.experiment8"
    sys.modules[_SELECTION_SNAPSHOT_MODULE_NAME] = module
    try:
        exec(module_code, module.__dict__, module.__dict__)
    except Exception as error:
        raise ProvenanceVerificationError(
            f"verified selector snapshot failed isolated loading: {error}"
        ) from error
    finally:
        if sys.modules.get(_SELECTION_SNAPSHOT_MODULE_NAME) is module:
            del sys.modules[_SELECTION_SNAPSHOT_MODULE_NAME]
    selection_callable = module.__dict__.get(_SELECTION_CALLABLE_QUALNAME)
    if (
        type(selection_callable) is not FunctionType
        or selection_callable.__code__ is not selection_code
        or selection_callable.__qualname__ != _SELECTION_CALLABLE_QUALNAME
        or selection_callable.__module__ != _SELECTION_SNAPSHOT_MODULE_NAME
        or selection_callable.__globals__ is not module.__dict__
    ):
        raise ProvenanceVerificationError(
            "isolated selector snapshot callable identity is inconsistent"
        )
    for value in module.__dict__.values():
        if (
            type(value) is FunctionType
            and value.__module__ == _SELECTION_SNAPSHOT_MODULE_NAME
            and value.__globals__ is not module.__dict__
        ):
            raise ProvenanceVerificationError(
                "isolated selector snapshot helper escaped its exact globals"
            )
    return selection_callable


_CAPTURED_SELECTION_CODE_SHA256 = _code_structure_sha256(
    _CAPTURED_SELECTION_CODE
)


__all__ = [
    "PINNED_PYTHON_CACHE_TAG",
    "PINNED_PYTHON_EXECUTABLE_SHA256",
    "PINNED_PYTHON_FLAGS",
    "PINNED_PYTHON_IMPLEMENTATION",
    "PINNED_PYTHON_PLATFORM",
    "PINNED_PYTHON_VERSION",
]
