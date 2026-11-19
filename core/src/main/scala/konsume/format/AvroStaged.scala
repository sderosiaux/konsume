package konsume.format

import io.circe.Json
import konsume.Record
import konsume.format.Plan.*
import konsume.jq.{Gen, Jq, JqError, Runtime, StagingCompiler}
import org.apache.avro.Schema
import org.apache.avro.Schema.Type.*

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.quoted.*

/** Compiles a jq program, for one Avro writer schema, to code that reads the Avro bytes
  * directly: no GenericRecord, no JSON tree.
  *
  *  1. the program is generated once in recording mode, to find the paths from the root whose
  *     values it uses (`.user.age`, `.user.country`);
  *  2. a reading plan is derived from the writer schema: fields are read in schema order, the
  *     needed ones into typed local variables (a `long`, a `double`, the offset and length of a
  *     UTF-8 string, a union index...), the others skipped, and reading stops after the last
  *     needed field;
  *  3. the program is generated again, on these variables.
  *
  * A record kept unchanged is emitted with its original bytes; any other output as JSON.
  */
object AvroStaged:

  type Fn = (Record[Any, Array[Byte]], ArrayBuffer[Record[Any, Array[Byte]]]) => Unit

  private val cache = ConcurrentHashMap[(Jq, Schema), Fn]()

  def compile(program: Jq, schema: Schema): Fn =
    cache.computeIfAbsent((program, schema), _ => compileUncached(program, schema))

  /** The generated code, for debugging. */
  def show(program: Jq, schema: Schema): String = StagingCompiler.show(code(program, schema, ArrayBuffer.empty))

  /** Internal entry point for measuring actual compilation, without cache hits or cache growth. */
  private[konsume] def compileUncached(program: Jq, schema: Schema): Fn =
    val schemas = ArrayBuffer.empty[Schema]
    StagingCompiler.run(code(program, schema, schemas))(schemas.toArray)

  // --------------------------------------------------------------------------- reading plan

  private enum Instr:
    case ReadLong(slot: Int)
    case ReadFloat(slot: Int)
    case ReadDouble(slot: Int)
    case ReadBoolean(slot: Int)
    case ReadIndex(slot: Int)
    case ReadString(off: Int, len: Int)
    case ReadJson(slot: Int, schema: Int)
    case Skip(schema: Schema)
    case Branch(index: Int, branches: Vector[List[Instr]])

  /** Where the value of a path will be, once read. */
  private enum PVal:
    case PStatic(value: Json)
    case PLong(slot: Int)
    case PDouble(slot: Int, isFloat: Boolean)
    case PBoolean(slot: Int)
    case PString(off: Int, len: Int)
    case PJson(slot: Int, rank: Int)
    case PEnum(slot: Int, symbols: Vector[String])
    case PChoice(index: Int, branches: Vector[PVal])
    /** `.name` of a value read entirely: done on its JSON view, when used */
    case PField(parent: PVal, name: String)

  import Instr.*, PVal.*

  private final class Planner(schemas: ArrayBuffer[Schema]):
    val slots = ArrayBuffer.empty[SlotType]

    private def slot(t: SlotType): Int =
      slots += t
      slots.size - 1

    def schemaRef(s: Schema): Int =
      val i = schemas.indexWhere(_ eq s)
      if i >= 0 then i
      else
        schemas += s
        schemas.size - 1

    /** Reads a value of `schema` at `prefix`; `needEnd`: the cursor must end after it. */
    def value(schema: Schema, prefix: Gen.Path, d: Demand, needEnd: Boolean): (List[Instr], Map[Gen.Path, PVal]) =
      schema.getType match
        case NULL => (Nil, d.valuePaths(prefix).map(_ -> PStatic(Json.Null)).toMap)
        case UNION =>
          val index    = slot(SlotType.Int)
          val branches = schema.getTypes.asScala.toVector.map(value(_, prefix, d, needEnd))
          val env      = d.valuePaths(prefix).map(p => p -> PChoice(index, branches.map(_._2(p)))).toMap
          (List(ReadIndex(index), Branch(index, branches.map(_._1))), env)
        case RECORD if !d.asValue => record(schema, prefix, d.children, needEnd)
        case _ =>
          val (instrs, v) = read(schema)
          (instrs, derived(v, prefix, d))

    private def record(schema: Schema, prefix: Gen.Path, children: Map[String, Demand], needEnd: Boolean) =
      val fields = schema.getFields.asScala.toVector
      val last   = fields.lastIndexWhere(f => children.contains(f.name))
      val limit  = if needEnd then fields.size - 1 else last // stop after the last needed field
      val instrs = List.newBuilder[Instr]
      var env    = Map.empty[Gen.Path, PVal]
      for i <- 0 to limit do
        val f = fields(i)
        children.get(f.name) match
          case Some(d) =>
            val (is, e) = value(f.schema, prefix :+ f.name, d, needEnd || i < limit)
            instrs ++= is
            env ++= e
          case None => instrs += Skip(f.schema)
      // a field the writer schema does not have is null, and so is everything under it
      for (name, d) <- children if schema.getField(name) == null do
        env ++= d.valuePaths(prefix :+ name).map(_ -> PStatic(Json.Null))
      (instrs.result(), env)

    private def read(schema: Schema): (List[Instr], PVal) = schema.getType match
      case BOOLEAN    => val s = slot(SlotType.Boolean); (List(ReadBoolean(s)), PBoolean(s))
      case INT | LONG => val s = slot(SlotType.Long); (List(ReadLong(s)), PLong(s))
      case FLOAT      => val s = slot(SlotType.Double); (List(ReadFloat(s)), PDouble(s, isFloat = true))
      case DOUBLE     => val s = slot(SlotType.Double); (List(ReadDouble(s)), PDouble(s, isFloat = false))
      case ENUM =>
        val s = slot(SlotType.Int)
        (List(ReadIndex(s)), PEnum(s, schema.getEnumSymbols.asScala.toVector))
      case STRING =>
        val off = slot(SlotType.Int)
        val len = slot(SlotType.Int)
        (List(ReadString(off, len)), PString(off, len))
      case _ => // records used as a whole, arrays, maps, bytes, fixed: their JSON view
        val s = slot(SlotType.Json)
        (List(ReadJson(s, schemaRef(schema))), PJson(s, rankOf(schema)))

    /** The rank in jq's order of the JSON view of values of `schema` (not null, union, boolean). */
    private def rankOf(schema: Schema): Int = schema.getType match
      case ARRAY                                                   => Runtime.ArrayRank
      case RECORD | MAP                                            => Runtime.ObjectRank
      case _ if schema.getLogicalType.isInstanceOf[org.apache.avro.LogicalTypes.Decimal] => Runtime.NumberRank
      case BYTES | FIXED                                           => Runtime.StringRank // base64
      case _                                                       => Runtime.NumberRank

    private def derived(v: PVal, prefix: Gen.Path, d: Demand): Map[Gen.Path, PVal] =
      (if d.asValue then Map(prefix -> v) else Map.empty) ++
        d.children.flatMap((n, cd) => derived(PField(v, n), prefix :+ n, cd))

  // ------------------------------------------------------------------------ code generation

  private def code(program: Jq, schema: Schema, schemas: ArrayBuffer[Schema])(using Quotes): Expr[Array[Schema] => Fn] =
    import Gen.*
    hoist(program) { consts =>
      // 1. which paths does the program use?
      val demand = Plan.demand(program, consts)
      // 2. how to read them
      val planner      = Planner(schemas)
      val (plan, penv) = planner.value(schema, Nil, demand, needEnd = false)
      val slotTypes    = planner.slots.toList.zipWithIndex

      '{ (schemaArray: Array[Schema]) => (record: Record[Any, Array[Byte]], out: ArrayBuffer[Record[Any, Array[Byte]]]) =>
        val bytes = record.value
        val cur   = AvroCursor(bytes, Confluent.HeaderSize)
        ${
          declare(slotTypes) { slots =>
            val read = instrs(plan, 'cur, 'schemaArray, planner.schemaRef, slots)
            // 3. the program, on the values read
            val env = locally {
              given Ctx = Ctx(consts)
              penv.map((p, v) => p -> sval(v, slots, 'bytes))
            }
            given Ctx = Ctx(consts, pathMode = true, env = env)
            val root  = env.getOrElse(Nil, SVal.PathRef(Nil))
            val run = gen(program, root, Some(Nil)) { v =>
              if v eq root then '{ out += record; () }
              else '{ out += record.copy(value = ${ json(v) }.noSpaces.getBytes(UTF_8)); () }
            }
            '{
              $read
              try $run
              catch case _: JqError => () // the record is skipped, outputs already emitted are kept
            }
          }
        }
      }
    }

  private def sval(v: PVal, slots: Map[Int, Slot], bytes: Expr[Array[Byte]])(using Gen.Ctx, Quotes): Gen.SVal =
    import Gen.SVal.*
    def get[T: Type](s: Int): Expr[T] = slots(s)._1.asExprOf[T]
    v match
      case PStatic(j)          => Static(j)
      case PLong(s)            => Num(get[Long](s))
      case PDouble(s, isFloat) => Dbl(get[Double](s), isFloat)
      case PBoolean(s)         => Bool(get[Boolean](s))
      case PString(off, len)   => Str(bytes, get[Int](off), get[Int](len))
      case PJson(s, rank)      => Opaque(get[Json](s), rank)
      case PEnum(s, symbols)   => Choice(get[Int](s), symbols.map(sym => Static(Json.fromString(sym))))
      case PChoice(s, bs)      => Choice(get[Int](s), bs.map(sval(_, slots, bytes)))
      case PField(parent, n) =>
        Dyn('{ Runtime.field(${ Gen.json(sval(parent, slots, bytes)) }, ${ Expr(n) }) })

  private def instrs(
      is: List[Instr],
      cur: Expr[AvroCursor],
      schemas: Expr[Array[Schema]],
      ref: Schema => Int,
      slots: Map[Int, Slot]
  )(using Quotes): Expr[Unit] =
    def set(s: Int)(v: Expr[Any]): Expr[Unit] = slots(s)._2(v)
    def one(i: Instr): Expr[Unit] = i match
      case ReadLong(s)    => set(s)('{ $cur.readLong() })
      case ReadFloat(s)   => set(s)('{ $cur.readFloat().toDouble })
      case ReadDouble(s)  => set(s)('{ $cur.readDouble() })
      case ReadBoolean(s) => set(s)('{ $cur.readBoolean() })
      case ReadIndex(s)   => set(s)('{ $cur.readInt() })
      case ReadString(off, len) =>
        '{
          val n = $cur.readLength()
          ${ set(off)('{ $cur.pos }) }
          ${ set(len)('n) }
          $cur.skip(n)
        }
      case ReadJson(s, k) => set(s)('{ AvroRuntime.readJson($cur, $schemas(${ Expr(k) })) })
      case Skip(schema)   => skip(schema, cur, schemas, ref)
      case Branch(s, bs) =>
        val index = slots(s)._1.asExprOf[Int]
        Gen.choose(index, bs.map(instrs(_, cur, schemas, ref, slots)) :+ '{ throw DecodeError("invalid union index") })
    Expr.block(is.map(one), '{ () })

  /** Skipping code, inlined for small schemas, generic for big or recursive ones. */
  private def skip(schema: Schema, cur: Expr[AvroCursor], schemas: Expr[Array[Schema]], ref: Schema => Int)(using
      Quotes
  ): Expr[Unit] =
    schema.getType match
      case NULL              => '{ () }
      case BOOLEAN           => '{ $cur.skip(1) }
      case INT | LONG | ENUM => '{ $cur.skipVarint() }
      case FLOAT             => '{ $cur.skip(4) }
      case DOUBLE            => '{ $cur.skip(8) }
      case STRING | BYTES    => '{ $cur.skip($cur.readLength()) }
      case FIXED             => '{ $cur.skip(${ Expr(schema.getFixedSize) }) }
      case _ if size(schema, Set.empty) > 32 =>
        '{ AvroRuntime.skip($cur, $schemas(${ Expr(ref(schema)) })) }
      case RECORD => Expr.block(schema.getFields.asScala.toList.map(f => skip(f.schema, cur, schemas, ref)), '{ () })
      case UNION =>
        val i = '{ $cur.readInt() }
        '{
          val index = $i
          ${
            Gen.choose('index, schema.getTypes.asScala.toVector.map(skip(_, cur, schemas, ref)) :+ '{
              throw DecodeError("invalid union index")
            })
          }
        }
      case ARRAY => blocks(cur, skip(schema.getElementType, cur, schemas, ref))
      case MAP   => blocks(cur, '{ $cur.skip($cur.readLength()); ${ skip(schema.getValueType, cur, schemas, ref) } })

  /** Arrays and maps: blocks of items; a negative count gives the block size, skipped at once. */
  private def blocks(cur: Expr[AvroCursor], item: Expr[Unit])(using Quotes): Expr[Unit] = '{
    var n = $cur.readLong()
    while n != 0 do
      if n < 0 then $cur.skip($cur.readLength())
      else
        var i = 0L
        while i < n do
          $item
          i += 1
      n = $cur.readLong()
  }

  /** Number of schema nodes, "infinite" for recursive schemas. */
  private def size(s: Schema, seen: Set[String]): Int = s.getType match
    case RECORD if seen(s.getFullName) => 10_000
    case RECORD => 1 + s.getFields.asScala.map(f => size(f.schema, seen + s.getFullName)).sum
    case UNION  => 1 + s.getTypes.asScala.map(size(_, seen)).sum
    case ARRAY  => 1 + size(s.getElementType, seen)
    case MAP    => 1 + size(s.getValueType, seen)
    case _      => 1
