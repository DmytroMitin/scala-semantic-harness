package semantic.harness.benchmark

import io.circe.Decoder
import io.circe.Encoder
import io.circe.HCursor
import io.circe.generic.semiauto.deriveEncoder

final case class BenchmarkCase(
  id: String,
  title: String,
  description: String,
  mode: String,
  initialProject: String,
  successCommand: String,
  allowedCommands: List[String],
  expectedSignals: List[String],
  expectedIntent: Option[String] = None,
  acceptablePatchFamilies: List[String] = Nil,
  intentNotes: List[String] = Nil
)

object BenchmarkCase:
  given Encoder[BenchmarkCase] = deriveEncoder
  given Decoder[BenchmarkCase] =
    (c: HCursor) =>
      for
        id <- c.downField("id").as[String]
        title <- c.downField("title").as[String]
        description <- c.downField("description").as[String]
        mode <- c.downField("mode").as[String]
        initialProject <- c.downField("initialProject").as[String]
        successCommand <- c.downField("successCommand").as[String]
        allowedCommands <- c.downField("allowedCommands").as[List[String]]
        expectedSignals <- c.downField("expectedSignals").as[List[String]]
        expectedIntent <- c.downField("expectedIntent").as[Option[String]]
        acceptablePatchFamilies <- c.downField("acceptablePatchFamilies").as[Option[List[String]]]
        intentNotes <- c.downField("intentNotes").as[Option[List[String]]]
      yield BenchmarkCase(
        id = id,
        title = title,
        description = description,
        mode = mode,
        initialProject = initialProject,
        successCommand = successCommand,
        allowedCommands = allowedCommands,
        expectedSignals = expectedSignals,
        expectedIntent = expectedIntent,
        acceptablePatchFamilies = acceptablePatchFamilies.getOrElse(Nil),
        intentNotes = intentNotes.getOrElse(Nil)
      )

final case class BenchmarkRun(
  caseId: String,
  mode: String,
  success: Boolean,
  iterations: Int,
  commandsUsed: List[String],
  semanticCommandsUsed: List[String] = Nil,
  semanticAssessment: String = "uncertain",
  finalStatus: String,
  notes: List[String],
  intentAssessment: Option[String] = None,
  intentAssessmentNotes: List[String] = Nil,
  commandCompliance: Option[String] = None,
  commandComplianceNotes: List[String] = Nil,
  requiredCommandsUsed: List[String] = Nil,
  forbiddenCommandsUsed: List[String] = Nil,
  extraCommandsUsed: List[String] = Nil,
  environmentDeviations: List[String] = Nil
)

object BenchmarkRun:
  given Encoder[BenchmarkRun] = deriveEncoder
  given Decoder[BenchmarkRun] =
    (c: HCursor) =>
      for
        caseId <- c.downField("caseId").as[String]
        mode <- c.downField("mode").as[String]
        success <- c.downField("success").as[Boolean]
        iterations <- c.downField("iterations").as[Int]
        commandsUsed <- c.downField("commandsUsed").as[List[String]]
        semanticCommandsUsed <- c.downField("semanticCommandsUsed").as[Option[List[String]]]
        semanticAssessment <- c.downField("semanticAssessment").as[Option[String]]
        finalStatus <- c.downField("finalStatus").as[String]
        notes <- c.downField("notes").as[List[String]]
        intentAssessment <- c.downField("intentAssessment").as[Option[String]]
        intentAssessmentNotes <- c.downField("intentAssessmentNotes").as[Option[List[String]]]
        commandCompliance <- c.downField("commandCompliance").as[Option[String]]
        commandComplianceNotes <- c.downField("commandComplianceNotes").as[Option[List[String]]]
        requiredCommandsUsed <- c.downField("requiredCommandsUsed").as[Option[List[String]]]
        forbiddenCommandsUsed <- c.downField("forbiddenCommandsUsed").as[Option[List[String]]]
        extraCommandsUsed <- c.downField("extraCommandsUsed").as[Option[List[String]]]
        environmentDeviations <- c.downField("environmentDeviations").as[Option[List[String]]]
      yield BenchmarkRun(
        caseId = caseId,
        mode = mode,
        success = success,
        iterations = iterations,
        commandsUsed = commandsUsed,
        semanticCommandsUsed = semanticCommandsUsed.getOrElse(Nil),
        semanticAssessment = semanticAssessment.getOrElse("uncertain"),
        finalStatus = finalStatus,
        notes = notes,
        intentAssessment = intentAssessment,
        intentAssessmentNotes = intentAssessmentNotes.getOrElse(Nil),
        commandCompliance = commandCompliance,
        commandComplianceNotes = commandComplianceNotes.getOrElse(Nil),
        requiredCommandsUsed = requiredCommandsUsed.getOrElse(Nil),
        forbiddenCommandsUsed = forbiddenCommandsUsed.getOrElse(Nil),
        extraCommandsUsed = extraCommandsUsed.getOrElse(Nil),
        environmentDeviations = environmentDeviations.getOrElse(Nil)
      )
