# fp-analyzers

FP-specific analyzers for Scala code.

Current v0 capability:

- `effect-summary` model and syntax-first analyzer.
- top-level `schemaVersion` for `effect-summary` JSON:
  `semantic-scala.effect-summary.v1`.
- declared method return type summaries for one Scala source file.
- conservative owner context for simple enclosing `object`, `class`, `trait`,
  and `enum` definitions.
- conservative package/source context for supported package declarations, with
  package-qualified local names such as `example.UserRepo.find`.
- conservative outer return type categories: `plain`, `option`, `either`,
  `future`, `io`, `zio`, `generic-effect`, and `unknown`.

Limits:

- no inferred return types.
- other semantic harness JSON payloads have their own command-specific
  `schemaVersion` values outside this module.
- no import or type alias resolution.
- no semantic owner identity; `packageQualifiedName` is syntactic context, not a
  compiler symbol.
- no presentation compiler or SemanticDB integration.
- no Cats Effect or ZIO semantic interpretation beyond direct syntactic names.
- no brace package syntax, import resolution, or alias resolution.
- complex nested or multiline syntax may omit context fields instead of
  guessing.

Benchmark coverage starts with `fp-effect-either-preservation-1`, which checks
whether agents preserve an `Either[String, User]` failure channel when using
effect-summary evidence. `fp-effect-generic-wrapper-1` and
`fp-effect-misleading-1` exercise owner context such as `UserRepo.find`,
`BoxUserRepo.find`, `Parser.parseOption`, and `Parser.parseEither`; package
context now reports names such as `example.UserRepo.find` when the source uses a
supported package declaration.
