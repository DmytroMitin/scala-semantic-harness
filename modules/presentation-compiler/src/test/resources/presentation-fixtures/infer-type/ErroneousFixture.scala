package example

object ErroneousFixture:
  val locallyResolved = 42
  val broken = MissingDependency("value")
