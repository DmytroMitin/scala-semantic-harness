package semantic.harness.semanticdb_reader

import java.security.MessageDigest
import scala.collection.mutable.ListBuffer

private[semanticdb_reader] enum SemanticdbArtifactOrigin:
  case CheckedIn, Copied, Generated, Unknown

private[semanticdb_reader] enum SemanticdbArtifactRole:
  case Fixture, OrdinarySourceOutput, Resource, Unknown

private[semanticdb_reader] enum SemanticdbWorkspaceScope:
  case InsideWorkspace, OutsideWorkspace

private[semanticdb_reader] enum SemanticdbSourceSetHint:
  case Main, Test, MainResource, TestResource, Unknown

private[semanticdb_reader] enum SemanticdbLanguageHint:
  case Scala, Java, Mixed, Unknown

private[semanticdb_reader] enum SemanticdbOutputKindHint:
  case ClassOutput, ResourceOutput, ToolingInternalOutput, SourceResource, Unknown

private[semanticdb_reader] enum SemanticdbProducerHint:
  case SbtLike, Bloop, Metals, Unknown

private[semanticdb_reader] enum SemanticdbConfigurationHint:
  case Compile, Test, IntegrationTest, Unknown

private[semanticdb_reader] enum SemanticdbHintConfidence:
  case Observed, Corroborated, PathHeuristic, WeakHeuristic, Unknown

private[semanticdb_reader] enum ArtifactHintEvidenceKind:
  case ArtifactPath
  case PathSegment
  case SemanticdbLocation
  case DocumentUri
  case MatchedSourcePath
  case SourceRoot
  case ExactDuplicateGroup
  case WorkspaceBoundary

private[semanticdb_reader] final case class QualifiedArtifactHint[A](
  value: A,
  confidence: SemanticdbHintConfidence,
  evidenceIds: List[String]
)

private[semanticdb_reader] final case class ArtifactHintEvidence(
  id: String,
  kind: ArtifactHintEvidenceKind,
  source: String,
  value: String
)

private[semanticdb_reader] final case class SemanticdbArtifactHintInput(
  artifactPath: String,
  workspaceScope: SemanticdbWorkspaceScope,
  documentUris: List[String],
  matchedSourcePaths: List[String],
  duplicateGroupId: Option[String],
  duplicateArtifactPaths: List[String]
)

private[semanticdb_reader] final case class SemanticdbArtifactHints(
  workspaceScope: SemanticdbWorkspaceScope,
  origins: List[QualifiedArtifactHint[SemanticdbArtifactOrigin]],
  roles: List[QualifiedArtifactHint[SemanticdbArtifactRole]],
  sourceSets: List[QualifiedArtifactHint[SemanticdbSourceSetHint]],
  languages: List[QualifiedArtifactHint[SemanticdbLanguageHint]],
  outputKinds: List[QualifiedArtifactHint[SemanticdbOutputKindHint]],
  producers: List[QualifiedArtifactHint[SemanticdbProducerHint]],
  modules: List[QualifiedArtifactHint[String]],
  configurations: List[QualifiedArtifactHint[SemanticdbConfigurationHint]],
  scalaVersions: List[QualifiedArtifactHint[String]],
  scalaBinaryVersions: List[QualifiedArtifactHint[String]],
  targetDirectories: List[QualifiedArtifactHint[String]],
  generatedSource: List[QualifiedArtifactHint[Boolean]],
  evidence: List[ArtifactHintEvidence],
  unresolved: List[String]
)

private[semanticdb_reader] object SemanticdbArtifactHintClassifier:
  private final case class TargetLayout(
    targetIndex: Int,
    scalaIndex: Int,
    outputIndex: Int,
    scalaVersion: String,
    outputSegment: String
  )

  private final case class BloopLayout(
    bloopIndex: Int,
    moduleIndex: Int,
    internalIndex: Int,
    metalsIndex: Int
  )

  private final case class SourceObservation(
    sourceSet: SemanticdbSourceSetHint,
    language: Option[SemanticdbLanguageHint],
    confidence: SemanticdbHintConfidence,
    evidenceIds: List[String],
    module: Option[String]
  )

  private val ScalaVersionPattern = raw"scala-([0-9]+(?:\.[0-9]+){2}(?:[-+][A-Za-z0-9.-]+)?)".r

  def classify(input: SemanticdbArtifactHintInput): SemanticdbArtifactHints =
    val evidence = EvidenceCollector()
    val artifactPath = normalize(input.artifactPath)
    val artifactSegments = segments(artifactPath)
    val artifactPathEvidence = evidence.add(
      ArtifactHintEvidenceKind.ArtifactPath,
      "artifactPath",
      artifactPath
    )
    evidence.add(
      ArtifactHintEvidenceKind.WorkspaceBoundary,
      "workspaceScope",
      input.workspaceScope.toString
    )

    val semanticdbLocationEvidence =
      findSequence(artifactSegments, List("META-INF", "semanticdb")).map { _ =>
        evidence.add(
          ArtifactHintEvidenceKind.SemanticdbLocation,
          "artifactPath",
          "META-INF/semanticdb"
        )
      }

    val normalizedDocumentUris = input.documentUris.map(normalize).distinct.sorted
    val documentEvidence = normalizedDocumentUris.map { uri =>
      uri -> evidence.add(ArtifactHintEvidenceKind.DocumentUri, "documentUri", uri)
    }
    val normalizedMatchedSources = input.matchedSourcePaths.map(normalize).distinct.sorted
    val matchedSourceEvidence = normalizedMatchedSources.map { path =>
      path -> evidence.add(ArtifactHintEvidenceKind.MatchedSourcePath, "matchedSourcePath", path)
    }
    val normalizedDuplicatePaths = input.duplicateArtifactPaths.map(normalize).distinct.sorted
    val duplicatePathEvidence = normalizedDuplicatePaths.map { path =>
      path -> evidence.add(ArtifactHintEvidenceKind.ArtifactPath, "duplicateArtifactPath", path)
    }.toMap
    val duplicateGroupEvidence = input.duplicateGroupId.map { groupId =>
      evidence.add(ArtifactHintEvidenceKind.ExactDuplicateGroup, "duplicateGroupId", groupId)
    }

    val origins = ListBuffer.empty[QualifiedArtifactHint[SemanticdbArtifactOrigin]]
    val roles = ListBuffer.empty[QualifiedArtifactHint[SemanticdbArtifactRole]]
    val sourceSets = ListBuffer.empty[QualifiedArtifactHint[SemanticdbSourceSetHint]]
    val languages = ListBuffer.empty[QualifiedArtifactHint[SemanticdbLanguageHint]]
    val outputKinds = ListBuffer.empty[QualifiedArtifactHint[SemanticdbOutputKindHint]]
    val producers = ListBuffer.empty[QualifiedArtifactHint[SemanticdbProducerHint]]
    val modules = ListBuffer.empty[QualifiedArtifactHint[String]]
    val configurations = ListBuffer.empty[QualifiedArtifactHint[SemanticdbConfigurationHint]]
    val scalaVersions = ListBuffer.empty[QualifiedArtifactHint[String]]
    val scalaBinaryVersions = ListBuffer.empty[QualifiedArtifactHint[String]]
    val targetDirectories = ListBuffer.empty[QualifiedArtifactHint[String]]

    val fixtureLayout = fixtureSourceResourceLayout(artifactSegments)
    fixtureLayout.foreach { case (srcIndex, fixtureIndex) =>
      val sourceRootId = evidence.add(
        ArtifactHintEvidenceKind.SourceRoot,
        "artifactPath",
        "src/test/resources"
      )
      val fixtureId = evidence.add(
        ArtifactHintEvidenceKind.PathSegment,
        "artifactPath",
        artifactSegments(fixtureIndex)
      )
      val ids = List(artifactPathEvidence, sourceRootId, fixtureId)
      roles += hint(SemanticdbArtifactRole.Fixture, SemanticdbHintConfidence.PathHeuristic, ids)
      sourceSets += hint(SemanticdbSourceSetHint.TestResource, SemanticdbHintConfidence.PathHeuristic, ids)
      outputKinds += hint(SemanticdbOutputKindHint.SourceResource, SemanticdbHintConfidence.PathHeuristic, ids)
      moduleBefore(artifactSegments, srcIndex).foreach { module =>
        modules += hint(module, SemanticdbHintConfidence.PathHeuristic, List(artifactPathEvidence, sourceRootId))
      }
    }

    targetLayout(artifactSegments).foreach { layout =>
      val targetId = evidence.add(ArtifactHintEvidenceKind.PathSegment, "artifactPath", "target")
      val scalaId = evidence.add(
        ArtifactHintEvidenceKind.PathSegment,
        "artifactPath",
        artifactSegments(layout.scalaIndex)
      )
      val outputId = evidence.add(
        ArtifactHintEvidenceKind.PathSegment,
        "artifactPath",
        layout.outputSegment
      )
      val layoutIds = List(artifactPathEvidence, targetId, scalaId, outputId)
      origins += hint(SemanticdbArtifactOrigin.Generated, SemanticdbHintConfidence.PathHeuristic, layoutIds)
      outputKinds += hint(SemanticdbOutputKindHint.ClassOutput, SemanticdbHintConfidence.PathHeuristic, layoutIds)
      producers += hint(SemanticdbProducerHint.SbtLike, SemanticdbHintConfidence.PathHeuristic, layoutIds)
      val configuration =
        if layout.outputSegment == "test-classes" then SemanticdbConfigurationHint.Test
        else SemanticdbConfigurationHint.Compile
      configurations += hint(configuration, SemanticdbHintConfidence.PathHeuristic, layoutIds)
      scalaVersions += hint(layout.scalaVersion, SemanticdbHintConfidence.PathHeuristic, List(scalaId))
      scalaBinaryVersion(layout.scalaVersion).foreach { binaryVersion =>
        scalaBinaryVersions += hint(binaryVersion, SemanticdbHintConfidence.PathHeuristic, List(scalaId))
      }
      val targetDirectory = artifactSegments.take(layout.outputIndex + 1).mkString("/")
      targetDirectories += hint(targetDirectory, SemanticdbHintConfidence.PathHeuristic, layoutIds)
      moduleBefore(artifactSegments, layout.targetIndex).foreach { module =>
        modules += hint(module, SemanticdbHintConfidence.PathHeuristic, List(artifactPathEvidence, targetId))
      }

      val resourceCopy =
        layout.outputSegment == "test-classes" &&
          artifactSegments.drop(layout.outputIndex + 1).contains("semanticdb-fixtures") &&
          semanticdbLocationEvidence.isEmpty
      if resourceCopy then
        val fixtureId = evidence.add(
          ArtifactHintEvidenceKind.PathSegment,
          "artifactPath",
          "semanticdb-fixtures"
        )
        val resourceIds = layoutIds :+ fixtureId
        roles += hint(SemanticdbArtifactRole.Resource, SemanticdbHintConfidence.PathHeuristic, resourceIds)
        sourceSets += hint(SemanticdbSourceSetHint.TestResource, SemanticdbHintConfidence.PathHeuristic, resourceIds)
        outputKinds += hint(SemanticdbOutputKindHint.ResourceOutput, SemanticdbHintConfidence.PathHeuristic, resourceIds)

        val fixturePeer = normalizedDuplicatePaths.find { path =>
          path != artifactPath && fixtureSourceResourceLayout(segments(path)).nonEmpty
        }
        for
          groupId <- duplicateGroupEvidence
          peer <- fixturePeer
          peerId <- duplicatePathEvidence.get(peer)
        do
          origins += hint(
            SemanticdbArtifactOrigin.Copied,
            SemanticdbHintConfidence.Corroborated,
            List(artifactPathEvidence, groupId, peerId, fixtureId)
          )
      else if semanticdbLocationEvidence.nonEmpty then
        roles += hint(
          SemanticdbArtifactRole.OrdinarySourceOutput,
          SemanticdbHintConfidence.PathHeuristic,
          layoutIds ++ semanticdbLocationEvidence
        )
    }

    bloopLayout(artifactSegments).foreach { layout =>
      val bloopId = evidence.add(ArtifactHintEvidenceKind.PathSegment, "artifactPath", ".bloop")
      val moduleId = evidence.add(
        ArtifactHintEvidenceKind.PathSegment,
        "artifactPath",
        artifactSegments(layout.moduleIndex)
      )
      val internalId = evidence.add(
        ArtifactHintEvidenceKind.PathSegment,
        "artifactPath",
        "bloop-internal-classes"
      )
      val metalsId = evidence.add(
        ArtifactHintEvidenceKind.PathSegment,
        "artifactPath",
        artifactSegments(layout.metalsIndex)
      )
      val layoutIds = List(artifactPathEvidence, bloopId, moduleId, internalId, metalsId) ++ semanticdbLocationEvidence
      origins += hint(SemanticdbArtifactOrigin.Generated, SemanticdbHintConfidence.PathHeuristic, layoutIds)
      roles += hint(SemanticdbArtifactRole.OrdinarySourceOutput, SemanticdbHintConfidence.PathHeuristic, layoutIds)
      outputKinds += hint(SemanticdbOutputKindHint.ToolingInternalOutput, SemanticdbHintConfidence.PathHeuristic, layoutIds)
      outputKinds += hint(SemanticdbOutputKindHint.ClassOutput, SemanticdbHintConfidence.PathHeuristic, layoutIds)
      producers += hint(SemanticdbProducerHint.Bloop, SemanticdbHintConfidence.PathHeuristic, List(bloopId, internalId))
      producers += hint(SemanticdbProducerHint.Metals, SemanticdbHintConfidence.PathHeuristic, List(metalsId, internalId))
      modules += hint(artifactSegments(layout.moduleIndex), SemanticdbHintConfidence.PathHeuristic, List(bloopId, moduleId))
      configurations += hint(SemanticdbConfigurationHint.Compile, SemanticdbHintConfidence.PathHeuristic, layoutIds)
      targetDirectories += hint(
        artifactSegments.take(layout.metalsIndex + 1).mkString("/"),
        SemanticdbHintConfidence.PathHeuristic,
        layoutIds
      )
    }

    val sourceObservations =
      documentEvidence.flatMap { case (uri, id) =>
        sourceObservation(uri, id, "documentUri", SemanticdbHintConfidence.PathHeuristic, evidence)
      } ++ matchedSourceEvidence.flatMap { case (path, id) =>
        sourceObservation(path, id, "matchedSourcePath", SemanticdbHintConfidence.Corroborated, evidence)
      }

    sourceObservations.foreach { observation =>
      sourceSets += hint(
        observation.sourceSet,
        observation.confidence,
        observation.evidenceIds
      )
      observation.module.foreach { module =>
        modules += hint(module, observation.confidence, observation.evidenceIds)
      }
    }
    languages ++= aggregateLanguages(sourceObservations)

    normalizedDocumentUris.filter(uri => segments(uri).size == 1).foreach { uri =>
      val language = languageFromFilename(uri)
      language.foreach { value =>
        val id = documentEvidence.find(_._1 == uri).map(_._2).toList
        languages += hint(value, SemanticdbHintConfidence.WeakHeuristic, id)
      }
    }

    val mergedOrigins = mergeHints(origins.toList)(_.toString)
    val mergedRoles = mergeHints(roles.toList)(_.toString)
    val mergedSourceSets = mergeHints(sourceSets.toList)(_.toString)
    val mergedLanguages = mergeLanguages(languages.toList)
    val mergedOutputKinds = mergeHints(outputKinds.toList)(_.toString)
    val mergedProducers = mergeHints(producers.toList)(_.toString)
    val mergedModules = mergeHints(modules.toList)(identity)
    val mergedConfigurations = mergeHints(configurations.toList)(_.toString)
    val mergedScalaVersions = mergeHints(scalaVersions.toList)(identity)
    val mergedScalaBinaryVersions = mergeHints(scalaBinaryVersions.toList)(identity)
    val mergedTargetDirectories = mergeHints(targetDirectories.toList)(identity)
    val generatedSource = List.empty[QualifiedArtifactHint[Boolean]]

    val unresolved = List(
      "origin" -> mergedOrigins.isEmpty,
      "role" -> mergedRoles.isEmpty,
      "sourceSet" -> mergedSourceSets.isEmpty,
      "language" -> mergedLanguages.isEmpty,
      "outputKind" -> mergedOutputKinds.isEmpty,
      "producer" -> mergedProducers.isEmpty,
      "module" -> mergedModules.isEmpty,
      "configuration" -> mergedConfigurations.isEmpty,
      "scalaVersion" -> mergedScalaVersions.isEmpty,
      "scalaBinaryVersion" -> mergedScalaBinaryVersions.isEmpty,
      "targetDirectory" -> mergedTargetDirectories.isEmpty,
      "generatedSource" -> generatedSource.isEmpty
    ).collect { case (dimension, true) => dimension }.sorted

    SemanticdbArtifactHints(
      workspaceScope = input.workspaceScope,
      origins = mergedOrigins,
      roles = mergedRoles,
      sourceSets = mergedSourceSets,
      languages = mergedLanguages,
      outputKinds = mergedOutputKinds,
      producers = mergedProducers,
      modules = mergedModules,
      configurations = mergedConfigurations,
      scalaVersions = mergedScalaVersions,
      scalaBinaryVersions = mergedScalaBinaryVersions,
      targetDirectories = mergedTargetDirectories,
      generatedSource = generatedSource,
      evidence = evidence.result,
      unresolved = unresolved
    )

  private def sourceObservation(
    path: String,
    pathEvidenceId: String,
    source: String,
    confidence: SemanticdbHintConfidence,
    evidence: EvidenceCollector
  ): List[SourceObservation] =
    if path.contains("://") then Nil
    else
      val pathSegments = segments(path)
      val roots = List(
        (List("src", "main", "scala"), SemanticdbSourceSetHint.Main, Some(SemanticdbLanguageHint.Scala)),
        (List("src", "test", "scala"), SemanticdbSourceSetHint.Test, Some(SemanticdbLanguageHint.Scala)),
        (List("src", "main", "java"), SemanticdbSourceSetHint.Main, Some(SemanticdbLanguageHint.Java)),
        (List("src", "test", "java"), SemanticdbSourceSetHint.Test, Some(SemanticdbLanguageHint.Java)),
        (List("src", "main", "resources"), SemanticdbSourceSetHint.MainResource, None),
        (List("src", "test", "resources"), SemanticdbSourceSetHint.TestResource, None)
      )
      roots.flatMap { case (root, sourceSet, language) =>
        findSequence(pathSegments, root).map { rootIndex =>
          val rootId = evidence.add(
            ArtifactHintEvidenceKind.SourceRoot,
            source,
            root.mkString("/")
          )
          SourceObservation(
            sourceSet = sourceSet,
            language = language,
            confidence = confidence,
            evidenceIds = List(pathEvidenceId, rootId),
            module = moduleBefore(pathSegments, rootIndex)
          )
        }
      }

  private def aggregateLanguages(
    observations: List[SourceObservation]
  ): List[QualifiedArtifactHint[SemanticdbLanguageHint]] =
    observations.flatMap { observation =>
      observation.language.map(language => hint(language, observation.confidence, observation.evidenceIds))
    }

  private def mergeLanguages(
    raw: List[QualifiedArtifactHint[SemanticdbLanguageHint]]
  ): List[QualifiedArtifactHint[SemanticdbLanguageHint]] =
    val merged = mergeHints(raw)(_.toString)
    val scala = merged.find(_.value == SemanticdbLanguageHint.Scala)
    val java = merged.find(_.value == SemanticdbLanguageHint.Java)
    (scala, java) match
      case (Some(scalaHint), Some(javaHint)) =>
        List(
          hint(
            SemanticdbLanguageHint.Mixed,
            strongest(List(scalaHint.confidence, javaHint.confidence)),
            (scalaHint.evidenceIds ++ javaHint.evidenceIds).distinct.sorted
          )
        )
      case _ => merged

  private def fixtureSourceResourceLayout(pathSegments: List[String]): Option[(Int, Int)] =
    findSequence(pathSegments, List("src", "test", "resources")).flatMap { srcIndex =>
      pathSegments.zipWithIndex
        .find { case (segment, index) => segment == "semanticdb-fixtures" && index >= srcIndex + 3 }
        .map { case (_, fixtureIndex) => srcIndex -> fixtureIndex }
    }

  private def targetLayout(pathSegments: List[String]): Option[TargetLayout] =
    pathSegments.indices.collectFirst {
      case targetIndex
          if pathSegments(targetIndex) == "target" &&
            targetIndex + 2 < pathSegments.size &&
            scalaVersion(pathSegments(targetIndex + 1)).nonEmpty &&
            Set("classes", "test-classes").contains(pathSegments(targetIndex + 2)) =>
        TargetLayout(
          targetIndex = targetIndex,
          scalaIndex = targetIndex + 1,
          outputIndex = targetIndex + 2,
          scalaVersion = scalaVersion(pathSegments(targetIndex + 1)).get,
          outputSegment = pathSegments(targetIndex + 2)
        )
    }

  private def bloopLayout(pathSegments: List[String]): Option[BloopLayout] =
    pathSegments.indices.collectFirst {
      case bloopIndex
          if pathSegments(bloopIndex) == ".bloop" &&
            bloopIndex + 3 < pathSegments.size &&
            pathSegments(bloopIndex + 1).nonEmpty &&
            pathSegments(bloopIndex + 2) == "bloop-internal-classes" &&
            (pathSegments(bloopIndex + 3) == "classes-Metals" ||
              pathSegments(bloopIndex + 3).startsWith("classes-Metals-")) =>
        BloopLayout(
          bloopIndex = bloopIndex,
          moduleIndex = bloopIndex + 1,
          internalIndex = bloopIndex + 2,
          metalsIndex = bloopIndex + 3
        )
    }

  private def scalaVersion(segment: String): Option[String] =
    segment match
      case ScalaVersionPattern(version) => Some(version)
      case _                            => None

  private def scalaBinaryVersion(version: String): Option[String] =
    val numeric = version.takeWhile(character => character.isDigit || character == '.')
    numeric.split('.').toList match
      case "3" :: _             => Some("3")
      case "2" :: minor :: _    => Some(s"2.$minor")
      case _                     => None

  private def languageFromFilename(path: String): Option[SemanticdbLanguageHint] =
    if path.endsWith(".scala") then Some(SemanticdbLanguageHint.Scala)
    else if path.endsWith(".java") then Some(SemanticdbLanguageHint.Java)
    else None

  private def moduleBefore(pathSegments: List[String], index: Int): Option[String] =
    Option.when(index > 0)(pathSegments.take(index).mkString("/"))

  private def findSequence(pathSegments: List[String], expected: List[String]): Option[Int] =
    if expected.isEmpty || expected.size > pathSegments.size then None
    else pathSegments.sliding(expected.size).zipWithIndex.collectFirst {
      case (candidate, index) if candidate == expected => index
    }

  private def segments(path: String): List[String] =
    path.split('/').toList.filter(_.nonEmpty)

  private[semanticdb_reader] def normalize(value: String): String =
    val replaced = value.replace('\\', '/')
    val schemeIndex = replaced.indexOf("://")
    val normalized =
      if schemeIndex >= 0 then
        val prefix = replaced.substring(0, schemeIndex + 3)
        val suffix = replaced.substring(schemeIndex + 3).replaceAll("/{2,}", "/")
        prefix + suffix
      else replaced.replaceAll("/{2,}", "/")
    Iterator.iterate(normalized)(_.stripPrefix("./")).dropWhile(_.startsWith("./")).next()

  private def hint[A](
    value: A,
    confidence: SemanticdbHintConfidence,
    evidenceIds: List[String]
  ): QualifiedArtifactHint[A] =
    QualifiedArtifactHint(value, confidence, evidenceIds.distinct.sorted)

  private def mergeHints[A](
    raw: List[QualifiedArtifactHint[A]]
  )(render: A => String): List[QualifiedArtifactHint[A]] =
    raw.groupBy(_.value).toList.map { case (value, hints) =>
      QualifiedArtifactHint(
        value = value,
        confidence = strongest(hints.map(_.confidence)),
        evidenceIds = hints.flatMap(_.evidenceIds).distinct.sorted
      )
    }.sortBy(item => render(item.value))

  private def strongest(confidences: List[SemanticdbHintConfidence]): SemanticdbHintConfidence =
    confidences.minBy(confidenceRank)

  private def confidenceRank(confidence: SemanticdbHintConfidence): Int =
    confidence match
      case SemanticdbHintConfidence.Observed       => 0
      case SemanticdbHintConfidence.Corroborated   => 1
      case SemanticdbHintConfidence.PathHeuristic  => 2
      case SemanticdbHintConfidence.WeakHeuristic  => 3
      case SemanticdbHintConfidence.Unknown        => 4

  private final class EvidenceCollector private ():
    private val items = ListBuffer.empty[ArtifactHintEvidence]

    def add(kind: ArtifactHintEvidenceKind, source: String, value: String): String =
      val id = evidenceId(kind, source, value)
      if !items.exists(_.id == id) then
        items += ArtifactHintEvidence(id, kind, source, value)
      id

    def result: List[ArtifactHintEvidence] =
      items.toList.sortBy(item => (item.kind.toString, item.source, item.value, item.id))

  private object EvidenceCollector:
    def apply(): EvidenceCollector = new EvidenceCollector()

  private def evidenceId(
    kind: ArtifactHintEvidenceKind,
    source: String,
    value: String
  ): String =
    val canonical = s"${kind.toString}\u0000$source\u0000$value"
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes("UTF-8"))
    val hex = digest.iterator.map(byte => f"${byte & 0xff}%02x").mkString
    s"evidence:sha256:$hex"
