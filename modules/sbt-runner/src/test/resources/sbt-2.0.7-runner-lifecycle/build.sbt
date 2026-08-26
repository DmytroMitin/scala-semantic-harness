ThisBuild / scalaVersion := "3.3.3"

lazy val root = project.in(file("."))
  .aggregate(passing, failing)

lazy val passing = project.in(file("passing"))
  .settings(
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
  )

lazy val failing = project.in(file("failing"))
  .settings(
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
  )
