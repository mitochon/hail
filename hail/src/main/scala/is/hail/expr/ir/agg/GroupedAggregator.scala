package is.hail.expr.ir.agg
import is.hail.annotations.{CodeOrdering, Region, StagedRegionValueBuilder}
import is.hail.asm4s._
import is.hail.expr.ir.{EmitClassBuilder, EmitCode, EmitCodeBuilder, EmitMethodBuilder, EmitRegion, ParamType}
import is.hail.io._
import is.hail.types.encoded.EType
import is.hail.types.physical._
import is.hail.utils._

class GroupedBTreeKey(kt: PType, kb: EmitClassBuilder[_], region: Value[Region], val offset: Value[Long], states: StateTuple) extends BTreeKey {
  val storageType: PStruct = PCanonicalStruct(required = true,
    "kt" -> kt,
    "regionIdx" -> PInt32(true),
    "container" -> states.storageType)
  val compType: PType = kt
  private val kcomp = kb.getCodeOrdering(kt, CodeOrdering.Compare(), ignoreMissingness = false)

  private val compLoader: EmitMethodBuilder[_] = {
    val mb = kb.genEmitMethod("compWithKey", FastIndexedSeq[ParamType](typeInfo[Long], typeInfo[Boolean], compType.ti), typeInfo[Int])
    val off = mb.getCodeParam[Long](1)
    val m = mb.getCodeParam[Boolean](2)
    val v = mb.getCodeParam(3)(compType.ti)
    val ev1 = EmitCode(Code._empty, isKeyMissing(off), PCode(compType, loadKey(off)))
    val ev2 = EmitCode(Code._empty, m, PCode(compType, v))
    mb.emit(compKeys(ev1, ev2))
    mb
  }

  override def compWithKey(off: Code[Long], k: EmitCode): Code[Int] =
    Code(k.setup, compLoader.invokeCode[Int](off, k.m, k.v)) // FIXME EmitParameter

  val regionIdx: Value[Int] = new Value[Int] {
    def get: Code[Int] = Region.loadInt(storageType.fieldOffset(offset, 1))
  }
  val container = new TupleAggregatorState(kb, states, region, containerOffset(offset), regionIdx)

  def isKeyMissing(off: Code[Long]): Code[Boolean] =
    storageType.isFieldMissing(off, 0)
  def loadKey(off: Code[Long]): Code[_] = Region.loadIRIntermediate(kt)(storageType.fieldOffset(off, 0))

  def initValue(cb: EmitCodeBuilder, destc: Code[Long], km: Code[Boolean], kv: Code[_], rIdx: Code[Int]): Unit = {
    val dest = cb.newLocal("ga_init_value_dest", destc)
    val koff = storageType.fieldOffset(dest, 0)
    val storeK =
      if (kt.isPrimitive)
        Region.storeIRIntermediate(kt)(koff, kv)
      else
        StagedRegionValueBuilder.deepCopy(kb, region, kt, coerce[Long](kv), koff)
    if (!kt.required) {
      cb.ifx(km, {
        cb += storageType.setFieldMissing(dest, 0)
      }, {
        cb += storageType.setFieldPresent(dest, 0)
        cb += storeK
      })
    } else {
      cb += storeK
    }

    cb += storeRegionIdx(dest, rIdx)
    container.newState(cb)
  }

  def loadStates(cb: EmitCodeBuilder): Unit = container.load(cb)
  def storeStates(cb: EmitCodeBuilder): Unit = container.store(cb)
  def copyStatesFrom(cb: EmitCodeBuilder, srcOff: Code[Long]): Unit = container.copyFrom(cb, srcOff)

  def storeRegionIdx(off: Code[Long], idx: Code[Int]): Code[Unit] =
    Region.storeInt(storageType.fieldOffset(off, 1), idx)

  def containerOffset(off: Value[Long]): Value[Long] = new Value[Long] {
    def get: Code[Long] = storageType.fieldOffset(off, 2)
  }

  def isEmpty(off: Code[Long]): Code[Boolean] =
    Region.loadInt(storageType.fieldOffset(off, 1)) < 0
  def initializeEmpty(off: Code[Long]): Code[Unit] =
    Region.storeInt(storageType.fieldOffset(off, 1), -1)

  def copy(src: Code[Long], dest: Code[Long]): Code[Unit] =
    Region.copyFrom(src, dest, storageType.byteSize)

  def deepCopy(er: EmitRegion, dest: Code[Long], srcCode: Code[Long]): Code[Unit] =
    EmitCodeBuilder.scopedVoid(er.mb) { cb =>
      val src = cb.newLocal("ga_deep_copy_src", srcCode)
      cb += StagedRegionValueBuilder.deepCopy(er, storageType, src, dest)
      container.copyFrom(cb, containerOffset(src))
      container.store(cb)
    }

  def compKeys(k1: EmitCode, k2: EmitCode): Code[Int] =
    Code(k1.setup, k2.setup, kcomp(k1.m -> k1.v, k2.m -> k2.v))

  def loadCompKey(off: Value[Long]): EmitCode =
    EmitCode(Code._empty, isKeyMissing(off), PCode(compType, loadKey(off)))

}

class DictState(val kb: EmitClassBuilder[_], val keyType: PType, val nested: StateTuple) extends PointerBasedRVAState {
  val nStates: Int = nested.nStates
  val valueType: PStruct = PCanonicalStruct("regionIdx" -> PInt32(true), "states" -> nested.storageType)
  val root: Settable[Long] = kb.genFieldThisRef[Long]("grouped_agg_root")
  val size: Settable[Int] = kb.genFieldThisRef[Int]("grouped_agg_size")
  val keyEType = EType.defaultFromPType(keyType)

  val typ: PStruct = PCanonicalStruct(
    required = true,
    "inits" -> nested.storageType,
    "size" -> PInt32(true),
    "tree" -> PInt64(true))

  private val _elt = kb.genFieldThisRef[Long]()
  private val initStatesOffset: Value[Long] = new Value[Long] {
    def get: Code[Long] = typ.loadField(off, 0)
  }
  val initContainer: TupleAggregatorState = new TupleAggregatorState(kb, nested, region, initStatesOffset)

  val keyed = new GroupedBTreeKey(keyType, kb, region, _elt, nested)
  val tree = new AppendOnlyBTree(kb, keyed, region, root, maxElements = 6)

  def initElement(cb: EmitCodeBuilder, eltOff: Code[Long], k: EmitCode): Unit = {
    cb.assign(size, size + 1)
    cb += region.setNumParents((size + 1) * nStates)
    keyed.initValue(cb, _elt, k.m, k.v, size * nStates)
  }

  def loadContainer(cb: EmitCodeBuilder, kec: EmitCode): Unit = {
    val kev = cb.memoize(kec, "ga_load_cont_k")
    cb.assign(_elt, tree.getOrElseInitialize(cb, kev))
    cb.ifx(keyed.isEmpty(_elt), {
      initElement(cb, _elt, kev)
      keyed.copyStatesFrom(cb, initStatesOffset)
    }, {
      keyed.loadStates(cb)
    })
  }

  def withContainer(cb: EmitCodeBuilder, k: EmitCode, seqOps: EmitCodeBuilder => Unit): Unit = {
    loadContainer(cb, k)
    seqOps(cb)
    keyed.storeStates(cb)
  }

  override def createState(cb: EmitCodeBuilder): Unit = {
    super.createState(cb)
    nested.createStates(cb)
  }

  override def load(regionLoader: Value[Region] => Code[Unit], src: Code[Long]): Code[Unit] = {
    Code(super.load(regionLoader, src),
      off.ceq(0L).mux(Code._empty,
        Code(
          size := Region.loadInt(typ.loadField(off, 1)),
          root := Region.loadAddress(typ.loadField(off, 2)))))
  }

  override def store(regionStorer: Value[Region] => Code[Unit], dest: Code[Long]): Code[Unit] = {
    Code(
      Region.storeInt(typ.fieldOffset(off, 1), size),
      Region.storeAddress(typ.fieldOffset(off, 2), root),
      super.store(regionStorer, dest))
  }

  def init(cb: EmitCodeBuilder, initOps: => Unit): Unit = {
    cb += region.setNumParents(nStates)
    cb += (off := region.allocate(typ.alignment, typ.byteSize))
    initContainer.newState(cb)
    initOps
    initContainer.store(cb)
    cb += (size := 0)
    cb += tree.init
  }

  def combine(cb: EmitCodeBuilder, other: DictState, comb: EmitCodeBuilder => Unit): Unit =
    other.foreach(cb) { (cb, k) => withContainer(cb, k, comb) }

  // loads container; does not update.
  def foreach(cb: EmitCodeBuilder)(f: (EmitCodeBuilder, EmitCode) => Unit): Unit =
    tree.foreach(cb) { (cb, kvOff) =>
      cb += (_elt := kvOff)
      keyed.loadStates(cb)
      f(cb, EmitCode(Code._empty, keyed.isKeyMissing(_elt), PCode(keyType, keyed.loadKey(_elt))))
    }

  def copyFromAddress(cb: EmitCodeBuilder, srcCode: Code[Long]): Unit = {
    val src = cb.newLocal("ga_copy_from_addr_src", srcCode)
    init(cb, initContainer.copyFrom(cb, typ.loadField(src, 0)))
    cb += (size := Region.loadInt(typ.loadField(src, 1)))
    cb += tree.deepCopy(Region.loadAddress(typ.loadField(src, 2)))
  }

  def serialize(codec: BufferSpec): (EmitCodeBuilder, Value[OutputBuffer]) => Unit = {
    val serializers = nested.states.map(_.serialize(codec))
    val kEnc = keyEType.buildEncoderMethod(keyType, kb)
    val km = kb.genFieldThisRef[Boolean]()
    val kv = kb.genFieldThisRef()(typeToTypeInfo(keyType))

    { (cb: EmitCodeBuilder, ob: Value[OutputBuffer]) =>
      initContainer.load(cb)
      nested.toCodeWithArgs(cb,
          IndexedSeq(ob),
          { (cb, i, _, args) =>
            val ob = cb.newLocal("ga_ser_init_ob", coerce[OutputBuffer](args.head))
            serializers(i)(cb, ob)
          })
      tree.bulkStore(cb, ob) { (cb: EmitCodeBuilder, ob: Value[OutputBuffer], kvOff: Code[Long]) =>
          cb += (_elt := kvOff)
          cb += (km := keyed.isKeyMissing(_elt))
          cb += (kv.storeAny(keyed.loadKey(_elt)))
          cb += (ob.writeBoolean(km))
          cb.ifx(!km, {
            cb += kEnc.invokeCode(kv, ob)
          })
          keyed.loadStates(cb)
          nested.toCodeWithArgs(cb,
            Array(ob.get),
            { (cb, i, _, args) =>
              val ob = cb.newLocal("ga_ser_ob", coerce[OutputBuffer](args.head))
              serializers(i)(cb, ob)
            })
        }
    }
  }

  def deserialize(codec: BufferSpec): (EmitCodeBuilder, Value[InputBuffer]) => Unit = {
    val deserializers = nested.states.map(_.deserialize(codec))
    val kDec = keyEType.buildDecoderMethod(keyType, kb)
    val k = kb.newEmitField(keyType)

    { (cb: EmitCodeBuilder, ib: Value[InputBuffer]) =>
      init(cb, nested.toCodeWithArgs(cb,
        FastIndexedSeq(ib),
        { (cb, i, _, args) =>
          val ib = cb.newLocal("ga_deser_init_ib", coerce[InputBuffer](args.head))
          deserializers(i)(cb, ib)
        }))
      tree.bulkLoad(cb, ib) { (cb, ib, koff) =>
          cb += (_elt := koff)
          val kc = EmitCode(Code._empty, ib.readBoolean(), PCode(keyType, cb.invokeCode(kDec, region, ib)))
          cb.assign(k, kc)
          initElement(cb, _elt, k)
          nested.toCodeWithArgs(cb,
            FastIndexedSeq(ib),
            { (cb, i, _, args) =>
              val ib = cb.newLocal("ga_deser_ib", coerce[InputBuffer](args.head))
              deserializers(i)(cb, ib)
            })
          keyed.storeStates(cb)
      }
    }
  }
}

class GroupedAggregator(kt: PType, nestedAggs: Array[StagedAggregator]) extends StagedAggregator {
  type State = DictState

  assert(kt.isCanonical)
  val resultEltType: PTuple = PCanonicalTuple(true, nestedAggs.map(_.resultType): _*)
  val resultType: PDict = PCanonicalDict(kt, resultEltType)
  val initOpTypes: Seq[PType] = Array(PVoid)
  val seqOpTypes: Seq[PType] = Array(kt, PVoid)

  protected def _initOp(cb: EmitCodeBuilder, state: State, init: Array[EmitCode]): Unit = {
    val Array(inits) = init
    state.init(cb, cb += inits.asVoid())
  }

  protected def _seqOp(cb: EmitCodeBuilder, state: State, seq: Array[EmitCode]): Unit = {
    val Array(key, seqs) = seq
    state.withContainer(cb, key, (cb) => cb += seqs.asVoid())
  }

  protected def _combOp(cb: EmitCodeBuilder, state: State, other: State): Unit = {
    state.combine(cb, other, { cb =>
      state.nested.toCode(cb, (cb, i, s) => nestedAggs(i).combOp(cb, s, other.nested(i)))
    })
  }

  protected def _result(cb: EmitCodeBuilder, state: State, srvb: StagedRegionValueBuilder): Unit = {
    cb += srvb.addArray(resultType.arrayFundamentalType, sab => EmitCodeBuilder.scopedVoid(cb.emb) { cb =>
      cb += sab.start(state.size)
      state.foreach(cb) { (cb, k) =>
        cb += sab.addBaseStruct(resultType.elementType, ssb => EmitCodeBuilder.scopedVoid(cb.emb) { cb =>
          cb += ssb.start
          cb += k.setup
          cb.ifx(k.m, cb += ssb.setMissing(), cb += ssb.addWithDeepCopy(kt, k.v))
          cb += ssb.advance()
          cb += ssb.addBaseStruct(resultEltType, svb => EmitCodeBuilder.scopedVoid(cb.emb) { cb =>
            cb += svb.start()
            state.nested.toCode(cb, { (cb, i, s) =>
              nestedAggs(i).result(cb, s, svb)
              cb += svb.advance()
            })
          })
        })
        cb += sab.advance()
      }
    })
  }
}
