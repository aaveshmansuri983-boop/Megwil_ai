"""Small JSON Schema validator for the Android-only vendored MCP client."""
from __future__ import annotations
import json
import math
import re

class SchemaError(ValueError):
    """The supplied schema is malformed or uses an unsupported reference."""

class ValidationError(ValueError):
    """A value does not satisfy its JSON Schema."""

def _type_matches(value, kind):
    if kind == "null": return value is None
    if kind == "boolean": return isinstance(value, bool)
    if kind == "object": return isinstance(value, dict)
    if kind == "array": return isinstance(value, list)
    if kind == "string": return isinstance(value, str)
    if kind == "integer": return isinstance(value, int) and not isinstance(value, bool)
    if kind == "number": return isinstance(value, (int, float)) and not isinstance(value, bool)
    raise SchemaError("Unsupported JSON Schema type: " + repr(kind))

def _resolve_ref(root, ref):
    if not isinstance(ref, str) or not ref.startswith("#/"):
        raise SchemaError("Only local JSON Schema references are supported on Android")
    current = root
    for part in ref[2:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        try:
            current = current[part] if isinstance(current, dict) else current[int(part)]
        except (KeyError, IndexError, ValueError, TypeError) as exc:
            raise SchemaError("Unresolved JSON Schema reference: " + ref) from exc
    if not isinstance(current, dict):
        raise SchemaError("JSON Schema reference must resolve to an object: " + ref)
    return current

def validate(instance, schema):
    """Validate an instance against the MCP tool-output JSON Schema subset."""
    if not isinstance(schema, dict):
        raise SchemaError("JSON Schema must be an object")
    root = schema

    def check(value, rule, path="$", depth=0):
        if depth > 64:
            raise SchemaError("JSON Schema nesting/reference depth exceeded")
        if not isinstance(rule, dict):
            raise SchemaError("JSON Schema at " + path + " must be an object")
        if "$ref" in rule:
            check(value, _resolve_ref(root, rule["$ref"]), path, depth + 1)

        if "type" in rule:
            kinds = rule["type"] if isinstance(rule["type"], list) else [rule["type"]]
            if not kinds or not all(isinstance(kind, str) for kind in kinds):
                raise SchemaError("JSON Schema type must be a non-empty string or list")
            if not any(_type_matches(value, kind) for kind in kinds):
                raise ValidationError(path + " has the wrong JSON type")
        if "const" in rule and value != rule["const"]:
            raise ValidationError(path + " does not equal const")
        if "enum" in rule:
            if not isinstance(rule["enum"], list):
                raise SchemaError("JSON Schema enum must be an array")
            if value not in rule["enum"]:
                raise ValidationError(path + " is not an allowed enum value")

        for keyword in ("allOf", "anyOf", "oneOf"):
            if keyword not in rule:
                continue
            subschemas = rule[keyword]
            if not isinstance(subschemas, list) or not subschemas:
                raise SchemaError("JSON Schema " + keyword + " must be a non-empty array")
            passed = 0
            for sub in subschemas:
                try:
                    check(value, sub, path, depth + 1)
                    passed += 1
                except ValidationError:
                    pass
            if keyword == "allOf" and passed != len(subschemas):
                raise ValidationError(path + " does not satisfy allOf")
            if keyword == "anyOf" and passed == 0:
                raise ValidationError(path + " does not satisfy anyOf")
            if keyword == "oneOf" and passed != 1:
                raise ValidationError(path + " does not satisfy exactly one oneOf branch")
        if "not" in rule:
            try:
                check(value, rule["not"], path, depth + 1)
            except ValidationError:
                pass
            else:
                raise ValidationError(path + " matches a forbidden schema")

        if isinstance(value, dict):
            required = rule.get("required", [])
            if not isinstance(required, list) or any(not isinstance(x, str) for x in required):
                raise SchemaError("JSON Schema required must be an array of strings")
            missing = [key for key in required if key not in value]
            if missing:
                raise ValidationError(path + " is missing required properties: " + ", ".join(missing))
            if "minProperties" in rule and len(value) < rule["minProperties"]:
                raise ValidationError(path + " has too few properties")
            if "maxProperties" in rule and len(value) > rule["maxProperties"]:
                raise ValidationError(path + " has too many properties")
            properties = rule.get("properties", {})
            if not isinstance(properties, dict):
                raise SchemaError("JSON Schema properties must be an object")
            for key, child in properties.items():
                if key in value:
                    check(value[key], child, path + "." + key, depth + 1)
            additional = rule.get("additionalProperties", True)
            for key, child_value in value.items():
                if key in properties:
                    continue
                if additional is False:
                    raise ValidationError(path + " contains unexpected property " + repr(key))
                if isinstance(additional, dict):
                    check(child_value, additional, path + "." + str(key), depth + 1)

        if isinstance(value, list):
            if "minItems" in rule and len(value) < rule["minItems"]:
                raise ValidationError(path + " has too few items")
            if "maxItems" in rule and len(value) > rule["maxItems"]:
                raise ValidationError(path + " has too many items")
            if rule.get("uniqueItems") is True:
                encoded = [json.dumps(x, sort_keys=True, separators=(",", ":")) for x in value]
                if len(encoded) != len(set(encoded)):
                    raise ValidationError(path + " contains duplicate items")
            prefix = rule.get("prefixItems", [])
            if prefix:
                if not isinstance(prefix, list):
                    raise SchemaError("JSON Schema prefixItems must be an array")
                for index, child in enumerate(prefix[:len(value)]):
                    check(value[index], child, path + "[" + str(index) + "]", depth + 1)
            items = rule.get("items")
            if isinstance(items, dict):
                start = len(prefix) if isinstance(prefix, list) else 0
                for index, child_value in enumerate(value[start:], start):
                    check(child_value, items, path + "[" + str(index) + "]", depth + 1)

        if isinstance(value, str):
            if "minLength" in rule and len(value) < rule["minLength"]:
                raise ValidationError(path + " is shorter than minLength")
            if "maxLength" in rule and len(value) > rule["maxLength"]:
                raise ValidationError(path + " is longer than maxLength")
            if "pattern" in rule:
                try:
                    pattern = re.compile(rule["pattern"])
                except (TypeError, re.error) as exc:
                    raise SchemaError("Invalid JSON Schema pattern") from exc
                if not pattern.search(value):
                    raise ValidationError(path + " does not match pattern")

        if isinstance(value, (int, float)) and not isinstance(value, bool):
            if not math.isfinite(float(value)):
                raise ValidationError(path + " must be a finite number")
            for keyword, predicate in (
                ("minimum", lambda a, b: a >= b),
                ("maximum", lambda a, b: a <= b),
                ("exclusiveMinimum", lambda a, b: a > b),
                ("exclusiveMaximum", lambda a, b: a < b),
            ):
                if keyword in rule and not predicate(value, rule[keyword]):
                    raise ValidationError(path + " violates " + keyword)
            if "multipleOf" in rule:
                divisor = rule["multipleOf"]
                if not isinstance(divisor, (int, float)) or divisor <= 0:
                    raise SchemaError("JSON Schema multipleOf must be positive")
                quotient = value / divisor
                if not math.isclose(quotient, round(quotient), rel_tol=1e-9, abs_tol=1e-9):
                    raise ValidationError(path + " violates multipleOf")
    check(instance, schema)
    return None
