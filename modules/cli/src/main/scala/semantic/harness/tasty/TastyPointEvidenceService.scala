package semantic.harness.tasty

import java.nio.file.{Files, LinkOption, Path}
import semantic.harness.sbt_runner.*

final case class TastyPointEvidenceInput(
    workspace: Path,
    source: String,
    line: Int,
    column: Int,
    project: SbtProjectId,
    targetJava: Option[ValidatedSbtJavaHome]
)

final case class TastyPointEvidenceService(
    receiptAcquirer: SbtTastyCompileReceiptAcquirer = SbtTastyCompileReceiptAcquirer.default,
    inspector: ExactTastyInspector = ExactTastyInspector.default
):
  def inspect(input: TastyPointEvidenceInput): Either[String, TastyPointEvidenceReport] =
    for
      before <- TastySourceInput.validate(input.workspace, input.source, input.line, input.column)
      receipt <- receiptAcquirer
        .acquire(
          SbtTastyCompileRequest(
            before.workspace,
            input.project,
            Path.of(before.relativePath),
            input.targetJava
          )
        )
        .left.map(_ => "Unable to acquire the bounded TASTy compile receipt")
      report <- fromReceipt(input, before, receipt)
    yield report

  private def fromReceipt(
      input: TastyPointEvidenceInput,
      before: ValidatedTastySource,
      receipt: SbtTastyCompileReceipt
  ): Either[String, TastyPointEvidenceReport] =
    currentSource(before, input).flatMap { afterCompile =>
      if afterCompile.sha256 != before.sha256 then
        Right(sourceChanged(input, before.sha256, afterCompile.sha256, receipt))
      else if receipt.compileStatus == SbtTastyCompileStatus.Failed then
        Right(
          baseReport(
            input,
            TastyPointEvidenceStatus.CompileFailed,
            TastyCompileStatus.Failed,
            receipt.scalaVersion,
            TastyArtifactEvidence(TastyArtifactState.NotInspected, 0, 0, None),
            before.sha256,
            afterCompile.sha256
          )
        )
      else if !receipt.sourceIncluded then
        Right(
          baseReport(
            input,
            TastyPointEvidenceStatus.ArtifactUnavailable,
            TastyCompileStatus.Succeeded,
            receipt.scalaVersion,
            TastyArtifactEvidence(TastyArtifactState.Unavailable, 0, 0, None),
            before.sha256,
            afterCompile.sha256
          )
        )
      else
        (receipt.scalaVersion, receipt.classDirectory) match
          case (Some(version), Some(classDirectory)) =>
            TastyArtifactInventory.inspect(before.workspace, classDirectory) match
              case Left(failure) =>
                val state = failure match
                  case TastyArtifactInventoryFailure.Unavailable       => TastyArtifactState.Unavailable
                  case TastyArtifactInventoryFailure.RejectedByBounds => TastyArtifactState.RejectedByBounds
                Right(
                  baseReport(
                    input,
                    TastyPointEvidenceStatus.ArtifactUnavailable,
                    TastyCompileStatus.Succeeded,
                    Some(version),
                    TastyArtifactEvidence(state, 0, 0, None),
                    before.sha256,
                    afterCompile.sha256
                  )
                )
              case Right(Nil) =>
                Right(
                  baseReport(
                    input,
                    TastyPointEvidenceStatus.ArtifactUnavailable,
                    TastyCompileStatus.Succeeded,
                    Some(version),
                    TastyArtifactEvidence(TastyArtifactState.Unavailable, 0, 0, None),
                    before.sha256,
                    afterCompile.sha256
                  )
                )
              case Right(candidates) =>
                inspectCandidates(input, before, receipt, version, candidates)
          case _ =>
            Right(
              baseReport(
                input,
                TastyPointEvidenceStatus.ArtifactUnavailable,
                TastyCompileStatus.Succeeded,
                receipt.scalaVersion,
                TastyArtifactEvidence(TastyArtifactState.Unavailable, 0, 0, None),
                before.sha256,
                afterCompile.sha256
              )
            )
    }

  private def inspectCandidates(
      input: TastyPointEvidenceInput,
      before: ValidatedTastySource,
      receipt: SbtTastyCompileReceipt,
      version: String,
      candidates: List[TastyArtifactCandidate]
  ): Either[String, TastyPointEvidenceReport] =
    validateDependencyClasspath(receipt.dependencyClasspath).flatMap { dependencies =>
      inspector.inspect(
        version,
        before.workspace,
        before.path,
        input.line,
        input.column,
        candidates,
        dependencies
      ) match
        case Left(failure) =>
          currentSource(before, input).map { after =>
            if after.sha256 != before.sha256 then sourceChanged(input, before.sha256, after.sha256, receipt)
            else
              val status = failure match
                case ExactTastyInspectorFailure.UnsupportedTargetScala => TastyPointEvidenceStatus.UnsupportedTargetScala
                case ExactTastyInspectorFailure.InspectorUnavailable   => TastyPointEvidenceStatus.InspectorUnavailable
                case ExactTastyInspectorFailure.InspectorFailed        => TastyPointEvidenceStatus.InspectorFailed
              baseReport(
                input,
                status,
                TastyCompileStatus.Succeeded,
                Some(version),
                TastyArtifactEvidence(TastyArtifactState.Available, candidates.size, 0, None),
                before.sha256,
                after.sha256
              )
          }
        case Right(inspection) =>
          currentSource(before, input).map { after =>
            if after.sha256 != before.sha256 then sourceChanged(input, before.sha256, after.sha256, receipt)
            else
              val selected = inspection.trees.sortBy(tree => (
                tree.endOffset - tree.startOffset,
                if tree.tree.symbol.nonEmpty then 0 else 1,
                -tree.startOffset,
                tree.endOffset,
                tree.tree.kind,
                tree.candidate.relativePath
              )).headOption
              val status =
                if selected.nonEmpty then TastyPointEvidenceStatus.Resolved
                else TastyPointEvidenceStatus.NoTypedTreeAtPoint
              baseReport(
                input,
                status,
                TastyCompileStatus.Succeeded,
                Some(version),
                TastyArtifactEvidence(
                  TastyArtifactState.Available,
                  candidates.size,
                  inspection.inspectedCount,
                  selected.map(tree => TastyArtifactDigest("TASTy", tree.candidate.byteSize, tree.candidate.sha256))
                ),
                before.sha256,
                after.sha256,
                selected.map(_.tree),
                Some(inspection.provenance)
              )
          }
    }

  private def currentSource(
      before: ValidatedTastySource,
      input: TastyPointEvidenceInput
  ): Either[String, ValidatedTastySource] =
    TastySourceInput.validate(before.workspace, before.relativePath, input.line, input.column)

  private def validateDependencyClasspath(entries: List[Path]): Either[String, List[Path]] =
    if entries.size > 512 then Left("TASTy dependency classpath exceeds the entry limit")
    else
      val safe = entries.forall { path =>
        Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) &&
        (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
      }
      Either.cond(safe, entries.map(_.toAbsolutePath.normalize()).distinct, "TASTy dependency classpath is unavailable")

  private def sourceChanged(
      input: TastyPointEvidenceInput,
      before: String,
      after: String,
      receipt: SbtTastyCompileReceipt
  ): TastyPointEvidenceReport =
    baseReport(
      input,
      TastyPointEvidenceStatus.SourceChangedDuringRequest,
      if receipt.compileStatus == SbtTastyCompileStatus.Succeeded then TastyCompileStatus.Succeeded else TastyCompileStatus.Failed,
      receipt.scalaVersion,
      TastyArtifactEvidence(TastyArtifactState.NotInspected, 0, 0, None),
      before,
      after,
      freshnessDisposition = TastyFreshnessDisposition.SourceChanged
    )

  private def baseReport(
      input: TastyPointEvidenceInput,
      status: TastyPointEvidenceStatus,
      compileStatus: TastyCompileStatus,
      scalaVersion: Option[String],
      artifactEvidence: TastyArtifactEvidence,
      before: String,
      after: String,
      selectedTree: Option[TastySelectedTree] = None,
      inspectorProvenance: Option[TastyInspectorProvenance] = None,
      freshnessDisposition: TastyFreshnessDisposition = TastyFreshnessDisposition.SameRequestSourceStable
  ): TastyPointEvidenceReport =
    TastyPointEvidenceReport(
      status = status,
      request = TastyPointRequest(
        input.source,
        TastyPointPosition(input.line, input.column),
        input.project.value
      ),
      compile = TastyCompileEvidence(compileStatus),
      targetScalaVersion = scalaVersion,
      artifactEvidence = artifactEvidence,
      selectedTree = selectedTree,
      freshness = TastyFreshnessEvidence(freshnessDisposition, before, after),
      inspector = inspectorProvenance,
      warnings = List(
        "Post-compile evidence is bounded to one authoritative selected Compile request.",
        "The inspector uses the exact acquired stable Scala 3 line in a bounded child process.",
        "Target compiler options, compiler plugins, plugin options, and plugin lifecycle are not replayed.",
        "Process separation is fault and resource containment, not a security sandbox."
      )
    )
