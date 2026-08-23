package semantic.harness.sbt_runner

trait SbtClasspathCacheService:
  def resolve(
      request: SbtClasspathRequest,
      mode: SbtClasspathCacheMode
  ): Either[SbtClasspathCacheFailure, SbtClasspathCacheResolution]

object SbtClasspathCacheService:
  def default(
      acquirer: SbtClasspathAcquirer = SbtClasspathAcquirer.default
  ): SbtClasspathCacheService =
    DefaultSbtClasspathCacheService(
      acquirer = acquirer,
      evidence = SbtClasspathEvidenceCollector.default,
      store = SbtClasspathCacheStore.default,
      currentTimeMillis = () => System.currentTimeMillis()
    )

  def local(
      acquirer: SbtClasspathAcquirer,
      store: SbtClasspathCacheStore,
      evidence: SbtClasspathEvidenceCollector = SbtClasspathEvidenceCollector.default,
      currentTimeMillis: () => Long = () => System.currentTimeMillis()
  ): SbtClasspathCacheService =
    DefaultSbtClasspathCacheService(acquirer, evidence, store, currentTimeMillis)

private[sbt_runner] final case class DefaultSbtClasspathCacheService(
    acquirer: SbtClasspathAcquirer,
    evidence: SbtClasspathEvidenceCollector,
    store: SbtClasspathCacheStore,
    currentTimeMillis: () => Long
) extends SbtClasspathCacheService:
  override def resolve(
      request: SbtClasspathRequest,
      mode: SbtClasspathCacheMode
  ): Either[SbtClasspathCacheFailure, SbtClasspathCacheResolution] =
    mode match
      case SbtClasspathCacheMode.Fresh =>
        acquirer
          .acquire(request)
          .left
          .map(SbtClasspathCacheFailure.RefreshAcquisition.apply)
          .map(result =>
            SbtClasspathCacheResolution(
              result,
              SbtClasspathCacheResolutionOrigin.FreshSbt
            )
          )
      case SbtClasspathCacheMode.Refresh =>
        refresh(request)
      case SbtClasspathCacheMode.Reuse =>
        reuse(request)

  private def refresh(
      request: SbtClasspathRequest
  ): Either[SbtClasspathCacheFailure, SbtClasspathCacheResolution] =
    for
      validated <- SbtClasspathRequest
        .validate(request)
        .left
        .map(SbtClasspathCacheFailure.Invalid.apply)
      identity <- SbtClasspathCacheIdentity.from(validated)
      resolution <- store.withLock(identity) { locked =>
        for
          before <- evidence.collectInputs(validated.workspace)
          acquired <- acquirer
            .acquire(validated)
            .left
            .map(SbtClasspathCacheFailure.RefreshAcquisition.apply)
          after <- evidence.collectInputs(validated.workspace)
          _ <-
            if before == after then Right(())
            else
              Left(
                SbtClasspathCacheFailure.StaleEvidence(
                  "conventional input changed during refresh"
                )
              )
          entries <- evidence.collectEntries(acquired.entries)
          record = SbtClasspathCacheRecord(
            format = identity.cacheFormat,
            acquisitionProtocol = identity.acquisitionProtocol,
            identity = identity,
            acquiredAtEpochMillis = currentTimeMillis(),
            inputEvidence = after,
            entryEvidenceCoverageVersion = SbtClasspathEntryEvidence.CoverageVersion,
            entries = entries,
            entryCount = entries.size
          )
          _ <- locked.publish(record)
        yield SbtClasspathCacheResolution(
          acquired,
          SbtClasspathCacheResolutionOrigin.FreshSbt
        )
      }
    yield resolution

  private def reuse(
      request: SbtClasspathRequest
  ): Either[SbtClasspathCacheFailure, SbtClasspathCacheResolution] =
    for
      validated <- SbtClasspathRequest
        .validate(request)
        .left
        .map(SbtClasspathCacheFailure.Invalid.apply)
      identity <- SbtClasspathCacheIdentity.from(validated)
      resolution <- store.withLock(identity) { locked =>
        for
          record <- locked.read(identity)
          currentInputs <- evidence.collectInputs(validated.workspace)
          _ <-
            if currentInputs == record.inputEvidence then Right(())
            else Left(SbtClasspathCacheFailure.StaleEvidence("conventional input"))
          cachedEntries = record.entries.map(_.entry)
          currentEntries <- evidence.collectEntries(cachedEntries)
          _ <- compareEntries(record.entries, currentEntries)
          result = SbtClasspathResult(
            project = identity.project,
            configuration = identity.configuration,
            entries = cachedEntries,
            javaContextToken = validated.targetJava.map(SbtJavaContext.token)
          )
        yield SbtClasspathCacheResolution(
          result,
          SbtClasspathCacheResolutionOrigin.CachedExplicitReuse
        )
      }
    yield resolution

  private def compareEntries(
      recorded: List[SbtClasspathEntryEvidence],
      current: List[SbtClasspathEntryEvidence]
  ): Either[SbtClasspathCacheFailure, Unit] =
    if recorded == current then Right(())
    else
      val category =
        recorded.zip(current).collectFirst {
          case (left, right)
              if left.kind == SbtClasspathEntryKind.Jar && left != right =>
            "JAR content"
          case (left, right)
              if left.kind == SbtClasspathEntryKind.Directory && left != right =>
            "class-directory output"
        }.getOrElse("classpath topology")
      Left(SbtClasspathCacheFailure.StaleEvidence(category))
