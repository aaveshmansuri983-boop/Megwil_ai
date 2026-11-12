"""Prepare a client-only copy of the pinned MCP Python SDK for Android."""
from __future__ import annotations
import argparse
import shutil
from pathlib import Path

EXPECTED_COMMIT = "09e3a05e13211d1081efcdc9a962affb02e40c05"

def prepare(source: Path, destination: Path, commit: str) -> None:
    source = source.resolve()
    destination = destination.resolve()
    if commit != EXPECTED_COMMIT:
        raise SystemExit(f"Unexpected MCP SDK revision: {commit}")
    required = (
        source / "client" / "session.py",
        source / "client" / "stdio" / "__init__.py",
        source / "types.py",
        source / "shared" / "session.py",
    )
    for path in required:
        if not path.is_file():
            raise SystemExit(f"Pinned MCP SDK source is incomplete: {path}")
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(source, destination)

    # JARVIS Android uses only the MCP stdio client, not the server or CLI.
    for name in ("server", "cli"):
        shutil.rmtree(destination / name, ignore_errors=True)
    (destination / "__init__.py").write_text(
        '"""Client-only MCP SDK subset for JARVIS Android."""\n'
        "from .client.session import ClientSession\n"
        "from .client.stdio import StdioServerParameters, stdio_client\n"
        "__all__ = [\"ClientSession\", \"StdioServerParameters\", \"stdio_client\"]\n"
    )
    (destination / "client" / "__init__.py").write_text('"""MCP client package."""\n')
    for name in ("os", "shared"):
        init_file = destination / name / "__init__.py"
        if init_file.exists():
            init_file.write_text('"""MCP support package."""\n')

    session_file = destination / "client" / "session.py"
    session = session_file.read_text()
    upstream_import = "from jsonschema import SchemaError, ValidationError, validate"
    if upstream_import not in session:
        raise SystemExit("Pinned MCP client session no longer has the expected JSON Schema import")
    session_file.write_text(session.replace(
        upstream_import,
        "from mcp.android_schema import SchemaError, ValidationError, validate",
        1,
    ))
    schema_source = Path(__file__).with_name("android_schema.py")
    if not schema_source.is_file():
        raise SystemExit(f"Android MCP schema validator is missing: {schema_source}")
    shutil.copy2(schema_source, destination / "android_schema.py")

    license_source = source.parent.parent / "LICENSE"
    if license_source.is_file():
        shutil.copy2(license_source, destination / "LICENSE.txt")
    (destination / "MCP-SOURCE.txt").write_text(
        "upstream=https://github.com/modelcontextprotocol/python-sdk\n"
        f"commit={commit}\n"
        "scope=client-only stdio MCP SDK; server and CLI packages excluded\n"
        "schema_validation=Android JSON Schema subset in android_schema.py\n"
    )
    print(f"Prepared client-only MCP SDK at {destination} ({commit})")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    prepare(args.source, args.destination, args.commit)
