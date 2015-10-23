package de.zalando.swagger

import de.zalando.apifirst.Application.{ApiCall, HandlerCall}
import de.zalando.apifirst.Domain.Type
import de.zalando.apifirst.Domain.naming.Name
import de.zalando.apifirst.Http.{MimeType, Verb}
import de.zalando.apifirst.Path.FullPath
import de.zalando.apifirst._
import de.zalando.swagger.strictModel._
import Domain.naming.dsl._
/**
  * @author  slasch 
  * @since   20.10.2015.
  */
class PathsConverter(val keyPrefix: String, val model: SwaggerModel, typeDefs: ParameterNaming#NamedTypes,
                     val definitionFileName: Option[String] = None)
  extends ParameterNaming with HandlerGenerator {

  private val FIXED = None // There is no way to define fixed parameters in swagger spec

  lazy val convert = fromPaths(model.paths, model.basePath)

  private def fromPath(basePath: BasePath)(pathDef: (String, PathItem)) = {
    implicit val (url, path) = pathDef
    for {
      operationName     <- path.operationNames
      verb              <- verbFromOperationName(operationName)
      operation         = path.operation(operationName)
      namePrefix        = url / operationName
      params            = parameters(path, operation, namePrefix)
      astPath           = Path.path2path(url, params)
      handlerCall       <- handler(operation, path, params, operationName, astPath).toSeq
      results           = resultTypes(namePrefix, operation)
      (mimeIn, mimeOut) = mimeTypes(operation)
      errMappings       = errorMappings(path, operation)
    } yield ApiCall(verb, astPath, handlerCall, mimeIn, mimeOut, errMappings, results)
  }

  private def fromPaths(paths: Paths, basePath: BasePath) = Option(paths).toSeq.flatten flatMap fromPath(basePath)

  private def resultTypes(prefix: Name, operation: Operation): Map[String, Type] =
    operation.responses map {
      case (code, definition) if code.forall(_.isDigit) => code -> findType(prefix, code)._2
      case ("default", definition)  => "default" -> findType(prefix, "default")._2
      case (other, _)               => throw new IllegalArgumentException(s"Expected numeric error code or 'default', but was $other")
    }

  private def parameters(path: PathItem, operation: Operation, namePrefix: Name) = {
    val pathParams        = fromParameterList(path.parameters, namePrefix)
    val operationParams   = fromParameterList(operation.parameters, namePrefix)
    pathParams ++ operationParams
  }

  private def fromParameterList(parameters: ParametersList, parameterNamePrefix: Name): Seq[Application.Parameter] = {
    Option(parameters).toSeq.flatten flatMap fromParametersListItem(parameterNamePrefix)
  }

  private def verbFromOperationName(operationName: String): Seq[Verb] =
    Http.string2verb(operationName).orElse {
      throw new scala.IllegalArgumentException(s"Could not parse HTTP verb $operationName")
    }.toSeq

  private def errorMappings(path: PathItem, operation: Operation) =
    Seq(operation.vendorErrorMappings, path.vendorErrorMappings, model.vendorErrorMappings).
      filter(_ != null).reduce(_ ++ _).toSet.toMap // TODO check that operation > path > model

  private def mimeTypes(operation: Operation) = {
    val mimeIn = orderedMediaTypeList(operation.consumes, model.consumes)
    val mimeOut = orderedMediaTypeList(operation.produces, model.produces)
    (mimeIn, mimeOut)
  }

  def orderedMediaTypeList(hiPriority: MediaTypeList, lowPriority: MediaTypeList): Set[MimeType] = {
    Option(hiPriority).orElse(Option(lowPriority)).toSet.flatten.map {
      new MimeType(_)
    }
  }

  @throws[MatchError]
  private def fromParametersListItem(prefix: Name)(li: ParametersListItem): Seq[Application.Parameter] = li match {
    case jr @ JsonReference(ref) => Nil // a parameter reference, probably can be ignored
    case bp: BodyParameter[_] => Seq(fromBodyParameter(prefix, bp))
    case nbp: NonBodyParameterCommons[_, _] => Seq(fromNonBodyParameter(prefix, nbp))
  }

  private def fromBodyParameter(prefix: Name, p: BodyParameter[_]): Application.Parameter = {
    val default = None
    val (name, typeDef) = findType(prefix, p.name)
    val (constraint, encode) = Constraints(p.in)
    Application.Parameter(name, typeDef, FIXED, default, constraint, encode, ParameterPlace.BODY)
  }

  private def fromNonBodyParameter(prefix: Name, p: NonBodyParameterCommons[_, _]): Application.Parameter = {
    val default = if (p.required) Option(p.default).map(_.toString) else None
    val (name, typeDef) = findType(prefix, p.name)
    val (constraint, encode) = Constraints(p.in)
    val place = ParameterPlace.withName(p.in)
    Application.Parameter(name, typeDef, FIXED, default, constraint, encode, place)
  }

  private def findType(prefix: Name, paramName: String): (Name, Type) = {
    val name = prefix / paramName
    val typeDef = typeDefByName(name) orElse findTypeByPath(prefix, paramName) getOrElse {
      println(typeDefs.mkString("\n"))
      throw new IllegalStateException(s"Could not find type definition with a name $name")
    }
    (name, typeDef)
  }

  private def findTypeByPath(fullPath: Name, name: String): Option[Type] = {
    val (path, operation) = (fullPath.namespace, fullPath.simple)
    Http.string2verb(operation) flatMap { _ => typeDefByName(path / name) }
  }

  private def typeDefByName(name: Name): Option[Type] =
    typeDefs.find(_._1 == name.toString).map(_._2)

}

object Constraints {
  private val byType: Map[String, (String, Boolean)] = Map(
    "formData" -> (".+", true),
    "path" -> ("[^/]+", true),
    "header" -> (".+", false),
    "body" -> (".+", false),
    "query" -> (".+", true)
  )
  def apply(in: String): (String, Boolean) = byType(in)
}

trait HandlerGenerator extends StringUtil {
  def keyPrefix: String
  def model: SwaggerModel
  def definitionFileName: Option[String]
  def handler(operation: Operation, path: PathItem, params: Seq[Application.Parameter], verb: String, callPath: FullPath): Option[HandlerCall] = for {
    handlerText <- getOrGenerateHandlerLine(operation, path, verb, callPath)
    parseResult = HandlerParser.parse(handlerText)
    handler <- if (parseResult.successful) Some(parseResult.get) else None
  } yield handler.copy(parameters = params)

  private def getOrGenerateHandlerLine(operation: Operation, path: PathItem, verb: String, callPath: FullPath): Option[String] =
    operation.vendorExtensions.get(s"$keyPrefix-handler") orElse
      path.vendorExtensions.get(s"$keyPrefix-handler") orElse
      generateHandlerLine(operation, callPath, verb)

  private def generateHandlerLine(operation: Operation, path: FullPath, verb: String): Option[String] = {
    model.vendorExtensions.get(s"$keyPrefix-package") map { pkg =>
      val controller = definitionFileName map { capitalize("\\.", _) } getOrElse {
        throw new IllegalStateException(s"The definition file name must be defined in order to use '$keyPrefix-package' directive")
      }
      val method = Option(operation.operationId).map(camelize(" ", _)) getOrElse {
        verb.toLowerCase + capitalize("/", path.string("by/" + _.value))
      }
      s"$pkg.$controller.$method"
    }
  }
}