package semantic.harness.benchmark

import io.circe.parser.decode
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

object BenchmarkCaseLoader:
  def loadFromDirectory(directory: Path): Either[String, List[BenchmarkCase]] =
    if !Files.exists(directory) then Left(s"Benchmark case directory does not exist: $directory")
    else if !Files.isDirectory(directory) then Left(s"Benchmark case path is not a directory: $directory")
    else
      val files = Files
        .list(directory)
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".json"))
        .toList
        .sortBy(_.getFileName.toString)

      files
        .foldLeft[Either[String, List[BenchmarkCase]]](Right(Nil)) { (acc, path) =>
          for
            values <- acc
            value <- loadFile(path)
          yield values :+ value
        }
        .flatMap { cases =>
          val errors = cases.flatMap(value => BenchmarkValidation.validate(value).map(error => s"${value.id}: $error"))
          if errors.isEmpty then Right(cases)
          else Left(errors.mkString("; "))
        }

  def loadResourceDirectory(resourcePath: String): Either[String, List[BenchmarkCase]] =
    Option(Thread.currentThread().getContextClassLoader.getResource(resourcePath)) match
      case Some(resource) if resource.getProtocol == "file" =>
        loadFromDirectory(Path.of(URLDecoder.decode(resource.getPath, StandardCharsets.UTF_8)))
      case Some(resource) =>
        Left(s"Unsupported benchmark resource protocol for $resourcePath: ${resource.getProtocol}")
      case None =>
        Left(s"Benchmark resource directory does not exist: $resourcePath")

  private def loadFile(path: Path): Either[String, BenchmarkCase] =
    try decode[BenchmarkCase](Files.readString(path)).left.map(error => s"Unable to decode benchmark case $path: ${error.getMessage}")
    catch
      case exception: Exception =>
        Left(s"Unable to read benchmark case $path: ${exception.getMessage}")
