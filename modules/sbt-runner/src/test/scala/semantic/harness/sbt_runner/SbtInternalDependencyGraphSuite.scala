package semantic.harness.sbt_runner

class SbtInternalDependencyGraphSuite extends munit.FunSuite:
  test("empty direct transitive and diamond dependency closures use deterministic DFS order"):
    val empty = SbtInternalDependencyGraph.resolve("app", Map("app" -> node("app")))
    assertEquals(empty.admitted, Nil)

    val direct = SbtInternalDependencyGraph.resolve(
      "app",
      Map("app" -> node("app", edge("core")), "core" -> node("core"))
    )
    assertEquals(
      direct.admitted.map(value => value.project -> value.role),
      List("core" -> SbtInternalDependencyRole.Direct)
    )

    val diamond = SbtInternalDependencyGraph.resolve(
      "app",
      Map(
        "app" -> node("app", edge("macros"), edge("laws")),
        "macros" -> node("macros", edge("core")),
        "laws" -> node("laws", edge("core")),
        "core" -> node("core")
      )
    )
    assertEquals(
      diamond.admitted.map(value => value.project -> value.role),
      List(
        "macros" -> SbtInternalDependencyRole.Direct,
        "core" -> SbtInternalDependencyRole.Transitive,
        "laws" -> SbtInternalDependencyRole.Direct
      )
    )

  test("cycles terminate and aggregates never enter the dependency closure"):
    val result = SbtInternalDependencyGraph.resolve(
      "app",
      Map(
        "app" -> node("app", List(edge("core")), aggregates = List("docs")),
        "core" -> node("core", edge("app")),
        "docs" -> node("docs", edge("hidden")),
        "hidden" -> node("hidden")
      )
    )
    assertEquals(result.admitted.map(_.project), List("core"))
    assertEquals(result.cycles.map(cycle => cycle.from -> cycle.to), List("core" -> "app"))
    assert(!result.admitted.exists(value => value.project == "docs" || value.project == "hidden"))

  test("Compile mapping policy admits only default and unambiguous explicit Compile to Compile"):
    val admitted = List(None, Some("compile"), Some("compile->compile"), Some("compile->compile;test->test"))
    admitted.foreach(mapping => assert(SbtCompileDependencyMapping.classify(mapping).admitted, clue(mapping)))

    val excluded = List(Some("test"), Some("test->test"), Some("provided->compile"))
    excluded.foreach(mapping =>
      assertEquals(
        SbtCompileDependencyMapping.classify(mapping),
        SbtCompileDependencyMapping.ExcludedNoCompileToCompile,
        clue(mapping)
      )
    )

    val unsupported = List(Some("compile->compile,test"), Some("compile;test->test"), Some("compile->"), Some("compile->compile->test"))
    unsupported.foreach(mapping =>
      assertEquals(
        SbtCompileDependencyMapping.classify(mapping),
        SbtCompileDependencyMapping.UnsupportedOrAmbiguous,
        clue(mapping)
      )
    )

  test("excluded and unsupported mappings are typed and not traversed"):
    val result = SbtInternalDependencyGraph.resolve(
      "app",
      Map(
        "app" -> node(
          "app",
          edge("testKit", Some("test->test")),
          edge("ambiguous", Some("compile->compile,test")),
          edge("core", Some("compile->compile"))
        ),
        "testKit" -> node("testKit", edge("hidden")),
        "ambiguous" -> node("ambiguous", edge("alsoHidden")),
        "core" -> node("core"),
        "hidden" -> node("hidden"),
        "alsoHidden" -> node("alsoHidden")
      )
    )
    assertEquals(result.admitted.map(_.project), List("core"))
    assertEquals(
      result.excluded.map(value => (value.project, value.role, value.mapping)),
      List(
        ("testKit", SbtInternalDependencyRole.Direct, SbtCompileDependencyMapping.ExcludedNoCompileToCompile),
        ("ambiguous", SbtInternalDependencyRole.Direct, SbtCompileDependencyMapping.UnsupportedOrAmbiguous)
      )
    )

  test("an excluded direct edge does not promote an admitted transitive path to Direct"):
    val result = SbtInternalDependencyGraph.resolve(
      "app",
      Map(
        "app" -> node(
          "app",
          edge("core", Some("test->test")),
          edge("macros")
        ),
        "macros" -> node("macros", edge("core")),
        "core" -> node("core")
      )
    )
    assertEquals(
      result.admitted.map(value => value.project -> value.role),
      List(
        "macros" -> SbtInternalDependencyRole.Direct,
        "core" -> SbtInternalDependencyRole.Transitive
      )
    )

  test("the dependency bound admits exactly 128 projects"):
    val projects = (1 to SbtInternalDependencyGraph.MaxDependencyProjects + 1).map(index => s"p$index").toList
    val result = SbtInternalDependencyGraph.resolve(
      "app",
      Map("app" -> node("app", projects.map(edge(_)), Nil)) ++
        projects.map(project => project -> node(project)).toMap
    )
    assertEquals(result.admitted.map(_.project), projects.take(SbtInternalDependencyGraph.MaxDependencyProjects))

  private def node(
      project: String,
      dependencies: SbtInternalDependencyEdge*
  ): SbtInternalDependencyNode = node(project, dependencies.toList, Nil)

  private def node(
      project: String,
      dependencies: List[SbtInternalDependencyEdge],
      aggregates: List[String]
  ): SbtInternalDependencyNode =
    SbtInternalDependencyNode(project, dependencies, aggregates)

  private def edge(
      project: String,
      mapping: Option[String] = None
  ): SbtInternalDependencyEdge = SbtInternalDependencyEdge(project, mapping)
