package semantic.harness.presentation

import java.nio.file.Path

trait DynamicSemanticService:
  def symbolAt(file: Path, line: Int, column: Int): Either[String, SymbolAtResult]
  def inferType(request: InferTypeRequest): Either[String, InferTypeResult]
