# Typeclass Summary Design-spike Fixtures

The fixture matrix is deliberately bounded to three source files. It separates
successful compiler-selected context evidence from failures that remain the
compiler diagnostic's responsibility.

| Pattern | Fixture location | Expected evidence owner |
|---|---|---|
| Simple typeclass and one given | `ResolvedPatterns.scala`: `Show` | SemanticDB spike for the selected given; source/symbols for declarations |
| Context bound and contextual `using` | `Show.render`; `syntax.rendered` | SemanticDB spike for inserted context arguments |
| Extension method from syntax | `Uses.renderedInt` | SemanticDB spike joins the extension reference to the selected given |
| Competing ambiguous instances | `AmbiguousInstance.scala` | Compiler diagnostic only |
| Higher-kinded typeclass | `Functor` and `Uses.mapped` | SemanticDB spike for selected `Functor[Option]` |
| Local shadowing of imported given | `Uses.locallyShadowed` | SemanticDB spike reports source-local selection without a local symbol ID |
| Missing instance | `MissingInstance.scala` | Compiler diagnostic only |
| `effect-summary` useful but insufficient | `EffectOverlap.load` | `effect-summary` reports `F[Int]`; it does not report selected context arguments |
| `infer-type` useful but insufficient | `InferTypeOverlap.inferredExpression` | `infer-type` reports expression type; it does not report selected context arguments |
| Source-only sufficient negative case | `SourceOnly.plain` | No typeclass evidence expected |

`Legacy.legacyInt` and `Uses.legacy` additionally exercise the legacy
`implicit` property when Scala 3 SemanticDB emits it. The spike does not claim
all legacy Scala 2 encodings are supported.
