package de.zalando.swagger

import de.zalando.apifirst.Domain
import Domain._
import TypeMetaConverter._
import de.zalando.apifirst.Domain.naming.Name
import strictModel._
import naming.dsl._

import scala.language.{implicitConversions, postfixOps}

/**
 * @author  slasch 
 * @since   14.10.2015.
 */
object TypeConverter extends ParameterNaming {

  private type TypeConstructor = TypeMeta => Type
  private type TypeConstructors = Seq[TypeConstructor]

  @throws[MatchError]
  private implicit def fromParameterType(tpe: (ParameterType.Value, String)): TypeConstructor =
    (tpe._1, Option(tpe._2)) match {
      case (ParameterType.INTEGER, Some("int64"))     => Domain.Lng
      case (ParameterType.INTEGER, Some("int32"))     => Domain.Int
      case (ParameterType.INTEGER, _)                 => Domain.Int
      case (ParameterType.NUMBER, Some("float"))      => Domain.Flt
      case (ParameterType.NUMBER, Some("double"))     => Domain.Dbl
      case (ParameterType.NUMBER, _)                  => Domain.Dbl
      case (ParameterType.BOOLEAN, _)                 => Domain.Bool
      case (ParameterType.STRING, Some("byte"))       => Domain.Byt
      case (ParameterType.STRING, Some("date"))       => Domain.Date
      case (ParameterType.STRING, Some("date-time"))  => Domain.DateTime
      case (ParameterType.STRING, Some("password"))   => Domain.Password
      case (ParameterType.STRING, fmt)                => Domain.Str.curried(fmt)
      case (ParameterType.FILE, _)                    => Domain.File
    }

  @throws[MatchError]
  private implicit def fromPrimitiveType(tpe: (PrimitiveType.Val, String)): TypeConstructor =
    (tpe._1, Option(tpe._2)) match {
      case (PrimitiveType.INTEGER, Some("int64"))     => Domain.Lng
      case (PrimitiveType.INTEGER, Some("int32"))     => Domain.Int
      case (PrimitiveType.INTEGER, _)                 => Domain.Int
      case (PrimitiveType.NUMBER, Some("float"))      => Domain.Flt
      case (PrimitiveType.NUMBER, Some("double"))     => Domain.Dbl
      case (PrimitiveType.NUMBER, _)                  => Domain.Dbl
      case (PrimitiveType.BOOLEAN, _)                 => Domain.Bool
      case (PrimitiveType.STRING, Some("byte"))       => Domain.Byt
      case (PrimitiveType.STRING, Some("date"))       => Domain.Date
      case (PrimitiveType.STRING, Some("date-time"))  => Domain.DateTime
      case (PrimitiveType.STRING, Some("password"))   => Domain.Password
      case (PrimitiveType.STRING, fmt)                => Domain.Str.curried(fmt)
      case (PrimitiveType.NULL, _)                    => Domain.Null
      // TODO object, array
    }

  private def wrapInArray(t: NamedType, collectionFormat: Option[String]): NamedType =
    t._1 -> Domain.Arr(t._2, t._2.meta, collectionFormat.map(_.toString))

  private def wrapInOption(t: NamedType): NamedType =
    t._1 -> Domain.Opt(t._2, t._2.meta)

  private def wrapInCatchAll(t: NamedTypes): NamedTypes =
    (t.head._1 -> Domain.CatchAll(t.head._2, t.head._2.meta)) +: t.tail

  def fromModel(model: strictModel.SwaggerModel): NamedTypes =
    fromDefinitions(model.definitions) ++
      fromPaths(model.paths) ++
      fromParameters(model.parameters)


  def fromParameters(parameters: ParameterDefinitions): NamedTypes =
    Option(parameters).toSeq.flatten flatMap {  p => fromParamListItem("parameters" / p._1, p._2) }

  def fromDefinitions(definitions: Definitions): NamedTypes =
    Option(definitions).toSeq.flatten flatMap { d => fromSchema("definitions" / d._1, d._2, Nil) }

  def fromPaths(paths: Paths): NamedTypes =
    fromPathParameters("", paths) ++ fromResponses(paths).flatten ++ fromOperationParameters(paths).toSeq.flatten

  private def fromPathParameters(prefix: Name, paths: Paths): NamedTypes =
    allPathItems(paths) flatMap fromNamedParamListItem

  private def forAllOperations[T](paths: Paths, logic: (Name, Operation) => T) =
    for {
      (prefix, path) <- Option(paths).toSeq.flatten
      operationName <- path.operationNames
      operation = path.operation(operationName)
      name = prefix / operationName
    } yield logic(name, operation)

  private def fromOperationParameters(paths: Paths): Iterable[NamedTypes] =
    forAllOperations(paths, parametersCollector)

  private def parametersCollector(name: Name, operation: Operation): NamedTypes =
    Option(operation.parameters).toSeq.flatten flatMap { fromParamListItem(name, _) }

  private def allPathItems(paths: Paths): Seq[(Name, ParametersListItem)] =
    Option(paths).toSeq.flatten flatMap { case (name, pathItem) =>
      pathItem.params(name) map { p => Name(p._1) -> p._2 }
    }

  private def responseCollector: (Name, Operation) => (Name, Responses) = (name, op) => name -> op.responses

  private def fromResponses(paths: Paths): Seq[NamedTypes] =
    for {
      (prefix, responses) <- forAllOperations(paths, responseCollector)
      (suffix, response) <- responses
      fullName = prefix / suffix
    } yield fromSchemaOrFileSchema(fullName, response.schema, Nil)

  private def fromNull(name: Name): NamedTypes = Seq(name -> Null(TypeMeta(None)))

  def fromNamedParamListItem[T](pair: (Name, ParametersListItem)): NamedTypes =
    fromParamListItem(pair._1, pair._2)

  def fromParamListItem[T](name: Name, param: ParametersListItem): NamedTypes =
    param match {
      case r @ JsonReference(ref)             => fromReference(name, ref)
      case nb: NonBodyParameterCommons[_, _]  => fromNonBodyParameter(name, nb)
      case bp: BodyParameter[_]               => fromBodyParameter(name, bp)
      case other => ??? // FIXME
    }

  implicit def fromNonBodyParameter[T, CF](name: Name, param: NonBodyParameterCommons[T, CF]): NamedTypes = {
    val meta = TypeMeta(Option(param.format))
    val fullName = name / param.name
    val result = if (param.isArray) wrapInArray(fromPrimitivesItems(fullName, param.items), Option(param.collectionFormat).map(_.toString))
    else if (!param.required) wrapInOption(fullName -> (param.`type`, param.format)(meta))
    else fullName -> (param.`type`, param.format)(meta)
    Seq(result)
  }

  implicit def fromBodyParameter[T](name: Name, param: BodyParameter[T]): NamedTypes =
    fromSchemaOrFileSchema(name / param.name, param.schema, if (param.required) Seq(param.name) else Nil)

  implicit def fromSchemaOrReference[T](name: Name, param: SchemaOrReference[T], required: Seq[String]): NamedTypes =
    Option(param).toSeq flatMap {
      case Left(s)                    => fromSchema(name, s, required)
      case Right(JsonReference(ref))  => fromReference(name, ref)
    }

  implicit def fromSchemaOrFileSchema[T](name: Name, param: SchemaOrFileSchema[T], required: Seq[String]): NamedTypes =
    param match {
      case any if any == null             => fromNull(name)
      case Left(s: SchemaOrReference[_])  => fromSchemaOrReference(name, s, required)
      case Right(fs: FileSchema[_])       => fromFileSchema(fs, required)
    }

  implicit def fromReference(name: Name, ref: Ref): NamedTypes =
    Seq(name -> Domain.Reference(ref, TypeMeta(Option(ref))))

  implicit def fromPrimitivesItems[T](name: Name, items: PrimitivesItems[T]): NamedType = {
    val meta = TypeMeta(Option(items.format))
    if (items.isArray)
      wrapInArray(fromPrimitivesItems(name, items.items), Option(items.collectionFormat).map(_.toString))
    else
      name -> (items.`type`, items.format)(meta)
  }

  implicit def fromSchemaOrSchemaArray[T](name: Name, param: SchemaOrSchemaArray[T]): NamedTypes =
    param match {
      case Right(sa)    => fromSchemaArray(name, sa)
      case Left(sr)     => fromSchemaOrReference(name, sr, Nil)
    }

  implicit def fromSchemaArray(name: Name, sa: SchemaArray): NamedTypes =
      sa flatMap { s => fromSchemaOrFileSchema(name, s, Nil) }

  implicit def fromSchema[T](name: Name, param: Schema[_], required: Seq[String]): NamedTypes = {
    val tpe = if (param.`type` != null) param.`type` else PrimitiveType.OBJECT
    tpe match {
      case t: ArrayJsonSchemaType => fromArrayJsonSchema(name, param, t)
      case p: PrimitiveType.Val   => fromPrimitiveType(name, param, p, required)
    }
  }

  implicit def fromPrimitiveType[T](name: Name, param: Schema[_], p: PrimitiveType.Val, required: Seq[String]): NamedTypes = p match {
    case PrimitiveType.ARRAY =>
      require(param.items.nonEmpty)
      val types = fromSchemaOrSchemaArray(name, param.items.get)
      wrapInArray(types.head, None) +: types.tail
    case PrimitiveType.OBJECT =>
      param.allOf map {
        extensionType(name)
      } getOrElse {
        val catchAll = fromSchemaOrBoolean(param.additionalProperties, param)
        val normal = fromSchemaProperties(param.properties, param.required)
        fromTypes(name, normal ++ catchAll.toSeq.flatten, Option(param.discriminator))
      }
    case _ =>
      val primitiveType = name -> (p, param.format)(param)
      if (isRequired(name, required)) Seq(primitiveType) else Seq(wrapInOption(primitiveType))
  }

  implicit def fromArrayJsonSchema[T](name: Name, param: Schema[_], t: ArrayJsonSchemaType): NamedTypes =
    t.toSeq map PrimitiveType.fromString map {
      name -> fromPrimitiveType(_, param.format)(param)
    }

  implicit def fromSchemaProperties[T](param: SchemaProperties, required: Seq[String]): NamedTypes =
    Option(param).toSeq.flatten flatMap { p =>
      fromSchemaOrFileSchema(p._1, p._2, required)
    }

  implicit def fromSchemaOrBoolean[T](param: SchemaOrBoolean[T], meta: TypeMeta): Option[NamedTypes] =
    Option(param) map {
      case Left(s)        => wrapInCatchAll(fromSchemaOrReference("additionalProperties", s, Nil))
      case Right(true)    => wrapInCatchAll(Seq(Name("additionalProperties") -> Str(None, meta)))   // FIXME
      case Right(false)   => wrapInCatchAll(Seq(Name("additionalProperties") -> Str(None, meta)))  // FIXME
      case sp: ParameterDefinitions       => ??? // wrapInCatchAll(fromParameters(sp))                 // FIXME
    }

  implicit def extensionType[T](name: Name)(schema: SchemaArray): NamedTypes =
    fromSchemaArray(name, schema)

  implicit def fromTypes(name: Name, types: NamedTypes, discriminator: Option[String]): NamedTypes = {
    val extend = Nil // FIXME
    val fields = types map { t => Field(TypeName(t._1.simple), t._2, TypeMeta(None)) }

    // FIXME if discriminator is set then `x-apifirst-model` should be one of the possible TypeKinds
    val meta = TypeMeta(None, Nil, ModelKind.Concrete)
    Seq(name -> Domain.TypeDef(TypeName(name.simple), fields, extend, meta))
  }

  implicit def fromFileSchema[T](schema: FileSchema[T], required: Seq[String]): NamedTypes = {
    // TODO
    ???
  }

  private def isRequired[T](name: Name, required: Seq[String]): Boolean =
    required != null && required.contains(name.simple)

}