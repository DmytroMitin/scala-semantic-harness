package semantic.harness.reconciliation

import java.nio.file.Path
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.semanticdb_reader.SemanticdbForSourceReport
import semantic.harness.semanticdb_reader.SemanticdbSourceMatch

final case class SemanticPointEvidenceRequest(
  workspace: Path,
  sourceFile: Path,
  line: Int,
  column: Int
)

enum LivePointEvidence:
  case Resolved(result: SymbolAtResult)
  case Unresolved(result: SymbolAtResult)
  case Unavailable(reason: String)

enum ReconciliationNotAttemptedReason:
  case NoArtifactCandidate
  case AmbiguousArtifactCandidates
  case PartialOrUnparseableArtifactEvidence
  case ArtifactEvidenceUnavailable
  case LivePointUnavailable
  case SelectedArtifactUnreadable

enum PointReconciliation:
  case Completed(result: ReconciliationResult)
  case NotAttempted(reason: ReconciliationNotAttemptedReason, detail: String)

final case class SemanticPointEvidence(
  request: SemanticPointEvidenceRequest,
  discovery: SemanticdbForSourceReport,
  selectedArtifact: Option[SemanticdbSourceMatch],
  livePoint: LivePointEvidence,
  reconciliation: PointReconciliation
)
