# Vision

AI coding agents are good at synthesis but weak at semantic understanding.

Scala and FP languages contain rich semantic information:

- inferred types
- givens and implicits
- typeclass resolution
- extension methods
- effect types
- macro expansion
- compiler phases
- build targets
- test ownership

This project exposes that information to agents through stable tools.

The hypothesis:

> Strong type systems become an AI advantage when agents can query and use type-system facts directly.

The initial target is Scala 3, but the design should be general enough to inspire similar tools for Haskell, OCaml, F#, Rust, and other strongly typed languages.