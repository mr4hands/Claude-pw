"""Thin wrapper around the Claude API that returns schema-validated JSON.

Every agent in the newsroom talks to the model through `LLM.json()`, so the
pipeline can be tested offline by swapping in a fake with the same method.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from typing import Any, Protocol

DEFAULT_MODEL = "claude-opus-5-5"


class LLM(Protocol):
    def json(self, *, system: str, prompt: str, schema: dict, effort: str = "medium") -> dict: ...


class RefusedError(RuntimeError):
    """The model (and its fallback) declined the request."""


@dataclass
class ClaudeLLM:
    model: str = field(default_factory=lambda: os.environ.get("NEWSROOM_MODEL", DEFAULT_MODEL))
    max_tokens: int = 32000
    usage: dict[str, int] = field(default_factory=lambda: {"input": 0, "output": 0, "calls": 0})

    def __post_init__(self) -> None:
        import anthropic

        self._client = anthropic.Anthropic()

    def json(self, *, system: str, prompt: str, schema: dict, effort: str = "medium") -> dict:
        # Streaming keeps long Hebrew articles clear of HTTP timeouts.
        with self._client.beta.messages.stream(
            model=self.model,
            max_tokens=self.max_tokens,
            system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
            messages=[{"role": "user", "content": prompt}],
            thinking={"type": "adaptive"},
            output_config={"effort": effort, "format": {"type": "json_schema", "schema": schema}},
            betas=["server-side-fallback-2026-07-01"],
            extra_body={"fallbacks": "default"},
        ) as stream:
            response = stream.get_final_message()

        self.usage["calls"] += 1
        self.usage["input"] += response.usage.input_tokens
        self.usage["output"] += response.usage.output_tokens

        if response.stop_reason == "refusal":
            raise RefusedError(str(response.stop_details))
        if response.stop_reason == "max_tokens":
            raise RuntimeError("response truncated at max_tokens")
        text = next(b.text for b in response.content if b.type == "text")
        return json.loads(text)


def obj(properties: dict[str, Any], required: list[str] | None = None) -> dict:
    """Build a strict JSON-schema object (all fields required, no extras)."""
    return {
        "type": "object",
        "properties": properties,
        "required": required if required is not None else list(properties),
        "additionalProperties": False,
    }


STR = {"type": "string"}
INT = {"type": "integer"}
BOOL = {"type": "boolean"}


def arr(items: dict) -> dict:
    return {"type": "array", "items": items}


def enum(*values: str) -> dict:
    return {"type": "string", "enum": list(values)}
