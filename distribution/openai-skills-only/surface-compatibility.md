# Surface compatibility

Observed from current first-party documentation on 28 September 2026 and local
Codex CLI 0.157.1 qualification.

| Surface | Skill loading | Local workspace and scripts | First-use GitHub fetch | Result |
| --- | --- | --- | --- | --- |
| Codex CLI | Yes; installed marketplace skill was discovered and read | Yes on qualified Linux x86_64 host with Python 3.11+ | Host network/approval dependent; cold and warm paths passed locally | Supported and proven locally |
| Codex in ChatGPT desktop | Documented plugin surface | Expected only when its Codex execution environment exposes the required local host capabilities | Environment/policy dependent | Package contract applies, but this exact client was not separately exercised |
| ChatGPT Work | Skills are documented, but local script/file execution is environment-specific | Not established for this helper | Not established | Unqualified pending OpenAI product-specific review |
| Ordinary ChatGPT Chat, web, and mobile | Universal listings can expose skills | No guaranteed user-local Linux workspace or packaged CLI execution | Not established | Unsupported for the Alpha-3 helper workflow |

The listing must not imply universal runtime functionality. Current OpenAI
guidance says to contact an OpenAI partner before submission when core value
requires local execution, arbitrary local file access, hardware/application
access, offline operation, or inbound messages. This candidate requires that
contact for local execution, local Scala-file access, and warm offline reuse.
