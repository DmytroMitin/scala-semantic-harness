ThisBuild / scalaVersion := "3.3.3"

lazy val root = (project in file("."))
  .aggregate(core2_13, compileFail_2, testFail_2)
  .settings(publish / skip := true)

lazy val core2_13 = (project in file("core"))
  .settings(
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
  )

lazy val compileFail_2 = (project in file("compile-fail"))

lazy val testFail_2 = (project in file("test-fail"))
  .settings(
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
  )
