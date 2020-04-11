package com.twilio.guardrail
package core

import cats.data.{ NonEmptyList, State }
// Issue #496: Injected StructuredLogger too slow import cats.free.{ Free, GuardrailFreeHacks }
import cats.implicits._
import cats.{ FlatMap, Monad, ~> }
import cats.free.Free
import com.twilio.guardrail.languages.LA
import com.twilio.guardrail.terms._
import java.nio.file.Paths
import scala.util.control.NonFatal

class CoreTermInterp[L <: LA](
    defaultFramework: String,
    handleModules: NonEmptyList[String] => Target[CodegenApplication[L, ?] ~> Target],
    frameworkMapping: PartialFunction[String, CodegenApplication[L, ?] ~> Target],
    handleImport: String => Either[Error, L#Import]
) extends CoreTerms[L, Target] {
  implicit def MonadF: Monad[Target] = Target.targetInstances
  def getDefaultFramework =
    Target.log.function("getDefaultFramework") {
      Target.log.debug(s"Providing ${defaultFramework}") >> Target.pure(Some(defaultFramework))
    }

  def extractGenerator(context: Context, vendorDefaultFramework: Option[String]) =
    Target.log.function("extractGenerator") {
      for {
        _ <- Target.log.debug("Looking up framework")
        framework <- NonEmptyList
          .fromList(context.modules)
          .toRight(context.framework)
          .bitraverse(
            ctxFramework =>
              for {
                frameworkName <- Target.fromOption(ctxFramework.orElse(vendorDefaultFramework), NoFramework)
                framework     <- Target.fromOption(PartialFunction.condOpt(frameworkName)(frameworkMapping), UnknownFramework(frameworkName))
                _             <- Target.log.debug(s"Found: $framework")
              } yield framework,
            handleModules
          )
      } yield framework.merge
    }

  def validateArgs(parsed: List[Args]) =
    for {
      args <- Target.pure(parsed.filterNot(_.defaults))
      args <- Target.fromOption(NonEmptyList.fromList(args.filterNot(Args.isEmpty)), NoArgsSpecified)
      args <- if (args.exists(_.printHelp))
        Target.raiseError[NonEmptyList[Args]](PrintHelp)
      else Target.pure(args)
    } yield args

  def parseArgs(args: Array[String]) = {
    def expandTilde(path: String): String =
      path.replaceFirst("^~", System.getProperty("user.home"))
    val defaultArgs =
      Args.empty.copy(context = Args.empty.context, defaults = true)

    type From = (List[Args], List[String])
    type To   = List[Args]
    val start: From = (List.empty[Args], args.toList)
    import Target.log.debug
    Target.log.function("parseArgs") {
      FlatMap[Target].tailRecM[From, To](start)({
        case pair @ (sofar, rest) =>
          val empty = sofar
            .filter(_.defaults)
            .reverse
            .headOption
            .getOrElse(defaultArgs)
            .copy(defaults = false)
          def Continue(x: From): Target[Either[From, To]] = Target.pure(Either.left(x))
          def Return(x: To): Target[Either[From, To]]     = Target.pure(Either.right(x))
          def Bail(x: Error): Target[Either[From, To]]    = Target.raiseError(x)
          for {
            _ <- debug(s"Processing: ${rest.take(5).mkString(" ")}${if (rest.length > 3) "..." else ""} of ${rest.length}")
            step <- pair match {
              case (already, Nil) =>
                debug("Finished") >> Return(already)
              case (Nil, xs @ (_ :: _)) => Continue((empty :: Nil, xs))
              case (sofar :: already, "--defaults" :: xs) =>
                Continue((empty.copy(defaults = true) :: sofar :: already, xs))
              case (sofar :: already, "--client" :: xs) =>
                Continue((empty :: sofar :: already, xs))
              case (sofar :: already, "--server" :: xs) =>
                Continue((empty.copy(kind = CodegenTarget.Server) :: sofar :: already, xs))
              case (sofar :: already, "--models" :: xs) =>
                Continue((empty.copy(kind = CodegenTarget.Models) :: sofar :: already, xs))
              case (sofar :: already, "--framework" :: value :: xs) =>
                Continue((sofar.copy(context = sofar.context.copy(framework = Some(value))) :: already, xs))
              case (sofar :: already, "--help" :: xs) =>
                Continue((sofar.copy(printHelp = true) :: already, List.empty))
              case (sofar :: already, "--specPath" :: value :: xs) =>
                Continue((sofar.copy(specPath = Option(expandTilde(value))) :: already, xs))
              case (sofar :: already, "--tracing" :: xs) =>
                Continue((sofar.copy(context = sofar.context.copy(tracing = true)) :: already, xs))
              case (sofar :: already, "--outputPath" :: value :: xs) =>
                Continue((sofar.copy(outputPath = Option(expandTilde(value))) :: already, xs))
              case (sofar :: already, "--packageName" :: value :: xs) =>
                Continue((sofar.copy(packageName = Option(value.trim.split('.').to[List])) :: already, xs))
              case (sofar :: already, "--dtoPackage" :: value :: xs) =>
                Continue((sofar.copy(dtoPackage = value.trim.split('.').to[List]) :: already, xs))
              case (sofar :: already, "--import" :: value :: xs) =>
                Continue((sofar.copy(imports = sofar.imports :+ value) :: already, xs))
              case (sofar :: already, "--module" :: value :: xs) =>
                Continue((sofar.copy(context = sofar.context.copy(modules = sofar.context.modules :+ value)) :: already, xs))
              case (_, unknown) =>
                debug("Unknown argument") >> Bail(UnknownArguments(unknown))
            }
          } yield step
      })
    }
  }

  def processArgSet(targetInterpreter: CodegenApplication[L, ?] ~> Target)(args: Args): Target[ReadSwagger[Target[List[WriteTree]]]] = {
    import scala.meta.parsers.Parsed
    implicit def parsed2Either[Z]: Parsed[Z] => Either[Parsed.Error, Z] = {
      case x: Parsed.Error      => Left(x)
      case Parsed.Success(tree) => Right(tree)
    }
    Target.log.function("processArgSet")(for {
      _          <- Target.log.debug("Processing arguments")
      specPath   <- Target.fromOption(args.specPath, MissingArg(args, Error.ArgName("--specPath")))
      outputPath <- Target.fromOption(args.outputPath, MissingArg(args, Error.ArgName("--outputPath")))
      pkgName    <- Target.fromOption(args.packageName, MissingArg(args, Error.ArgName("--packageName")))
      kind       = args.kind
      dtoPackage = args.dtoPackage
      context    = args.context
      customImports <- args.imports
        .traverse(
          x =>
            for {
              _ <- Target.log.debug(s"Attempting to parse $x as an import directive")
              customImport <- handleImport(x)
                .fold[Target[L#Import]](err => Target.raiseError(UnparseableArgument("import", err.toString)), Target.pure _)
            } yield customImport
        )
      _ <- Target.log.debug("Finished processing arguments")
    } yield {
      ReadSwagger(
        Paths.get(specPath), {
          swagger =>
            try {
              val Sw = SwaggerTerms.swaggerTerm[L, CodegenApplication[L, ?]]
              val program = for {
                _                  <- Sw.log.debug("Running guardrail codegen")
                definitionsPkgName <- ScalaTerms.scalaTerm[L, CodegenApplication[L, ?]].fullyQualifyPackageName(pkgName)
                (proto, codegen) <- Common
                  .prepareDefinitions[L, CodegenApplication[L, ?]](kind, context, Tracker(swagger), definitionsPkgName ++ ("definitions" :: dtoPackage))
                result <- Common
                  .writePackage[L, Free[CodegenApplication[L, ?], ?]](proto, codegen, context)(Paths.get(outputPath), pkgName, dtoPackage, customImports)
              } yield result
              // Issue #496: Injected StructuredLogger too slow
              // GuardrailFreeHacks.injectLogs(program, Set("LogPush", "LogPop", "LogDebug", "LogInfo", "LogWarning", "LogError"), Sw.log.push, Sw.log.pop, Free.pure(()))
              program
                .foldMap(targetInterpreter)
            } catch {
              case NonFatal(ex) =>
                val stackTrace =
                  ex.getStackTrace()
                    .toList
                    .foldLeftM[State[Option[String], ?], List[String]](List.empty)({
                      case (acc, next) =>
                        for {
                          lastClassName <- State.get
                          _             <- State.set(Option(next.getClassName()))
                        } yield {
                          if (next.getClassName().startsWith("com.twilio")) {
                            acc :+ s"        at ${next.toString()}"
                          } else {
                            if (lastClassName.exists(_.startsWith("com.twilio"))) {
                              acc :+ "          ..."
                            } else acc
                          }
                        }
                    })
                    .runA(Option.empty)
                    .value
                Target.raiseException(s"""
                  |Error attempting to process ${specPath}:
                  |
                  |${ex.toString()}
                  |${stackTrace.mkString("\n")}
                  |""".stripMargin.trim)
            }
        }
      )
    })
  }
}
