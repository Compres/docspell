package docspell.store.queries

import cats.data.NonEmptyList
import cats.data.OptionT
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import fs2.Stream

import docspell.common.{IdRef, _}
import docspell.store.Store
import docspell.store.impl.Implicits._
import docspell.store.impl._
import docspell.store.records._

import bitpeace.FileMeta
import doobie._
import doobie.implicits._
import org.log4s._

object QItem {
  private[this] val logger = getLogger

  def moveAttachmentBefore(
      itemId: Ident,
      source: Ident,
      target: Ident
  ): ConnectionIO[Int] = {

    // rs < rt
    def moveBack(rs: RAttachment, rt: RAttachment): ConnectionIO[Int] =
      for {
        n <- RAttachment.decPositions(itemId, rs.position, rt.position)
        k <- RAttachment.updatePosition(rs.id, rt.position)
      } yield n + k

    // rs > rt
    def moveForward(rs: RAttachment, rt: RAttachment): ConnectionIO[Int] =
      for {
        n <- RAttachment.incPositions(itemId, rt.position, rs.position)
        k <- RAttachment.updatePosition(rs.id, rt.position)
      } yield n + k

    (for {
      _ <- OptionT.liftF(
        if (source == target)
          Sync[ConnectionIO].raiseError(new Exception("Attachments are the same!"))
        else ().pure[ConnectionIO]
      )
      rs <- OptionT(RAttachment.findById(source)).filter(_.itemId == itemId)
      rt <- OptionT(RAttachment.findById(target)).filter(_.itemId == itemId)
      n <- OptionT.liftF(
        if (rs.position == rt.position || rs.position + 1 == rt.position)
          0.pure[ConnectionIO]
        else if (rs.position < rt.position) moveBack(rs, rt)
        else moveForward(rs, rt)
      )
    } yield n).getOrElse(0)

  }

  case class ItemData(
      item: RItem,
      corrOrg: Option[ROrganization],
      corrPerson: Option[RPerson],
      concPerson: Option[RPerson],
      concEquip: Option[REquipment],
      inReplyTo: Option[IdRef],
      folder: Option[IdRef],
      tags: Vector[RTag],
      attachments: Vector[(RAttachment, FileMeta)],
      sources: Vector[(RAttachmentSource, FileMeta)],
      archives: Vector[(RAttachmentArchive, FileMeta)]
  ) {

    def filterCollective(coll: Ident): Option[ItemData] =
      if (item.cid == coll) Some(this) else None
  }

  def findItem(id: Ident): ConnectionIO[Option[ItemData]] = {
    val IC  = RItem.Columns.all.map(_.prefix("i"))
    val OC  = ROrganization.Columns.all.map(_.prefix("o"))
    val P0C = RPerson.Columns.all.map(_.prefix("p0"))
    val P1C = RPerson.Columns.all.map(_.prefix("p1"))
    val EC  = REquipment.Columns.all.map(_.prefix("e"))
    val ICC = List(RItem.Columns.id, RItem.Columns.name).map(_.prefix("ref"))
    val FC  = List(RFolder.Columns.id, RFolder.Columns.name).map(_.prefix("f"))

    val cq =
      selectSimple(
        IC ++ OC ++ P0C ++ P1C ++ EC ++ ICC ++ FC,
        RItem.table ++ fr"i",
        Fragment.empty
      ) ++
        fr"LEFT JOIN" ++ ROrganization.table ++ fr"o ON" ++ RItem.Columns.corrOrg
        .prefix("i")
        .is(ROrganization.Columns.oid.prefix("o")) ++
        fr"LEFT JOIN" ++ RPerson.table ++ fr"p0 ON" ++ RItem.Columns.corrPerson
        .prefix("i")
        .is(RPerson.Columns.pid.prefix("p0")) ++
        fr"LEFT JOIN" ++ RPerson.table ++ fr"p1 ON" ++ RItem.Columns.concPerson
        .prefix("i")
        .is(RPerson.Columns.pid.prefix("p1")) ++
        fr"LEFT JOIN" ++ REquipment.table ++ fr"e ON" ++ RItem.Columns.concEquipment
        .prefix("i")
        .is(REquipment.Columns.eid.prefix("e")) ++
        fr"LEFT JOIN" ++ RItem.table ++ fr"ref ON" ++ RItem.Columns.inReplyTo
        .prefix("i")
        .is(RItem.Columns.id.prefix("ref")) ++
        fr"LEFT JOIN" ++ RFolder.table ++ fr"f ON" ++ RItem.Columns.folder
        .prefix("i")
        .is(RFolder.Columns.id.prefix("f")) ++
        fr"WHERE" ++ RItem.Columns.id.prefix("i").is(id)

    val q = cq
      .query[
        (
            RItem,
            Option[ROrganization],
            Option[RPerson],
            Option[RPerson],
            Option[REquipment],
            Option[IdRef],
            Option[IdRef]
        )
      ]
      .option
    val attachs  = RAttachment.findByItemWithMeta(id)
    val sources  = RAttachmentSource.findByItemWithMeta(id)
    val archives = RAttachmentArchive.findByItemWithMeta(id)

    val tags = RTag.findByItem(id)

    for {
      data <- q
      att  <- attachs
      srcs <- sources
      arch <- archives
      ts   <- tags
    } yield data.map(d =>
      ItemData(d._1, d._2, d._3, d._4, d._5, d._6, d._7, ts, att, srcs, arch)
    )
  }

  case class ListItem(
      id: Ident,
      name: String,
      state: ItemState,
      date: Timestamp,
      dueDate: Option[Timestamp],
      source: String,
      direction: Direction,
      created: Timestamp,
      fileCount: Int,
      corrOrg: Option[IdRef],
      corrPerson: Option[IdRef],
      concPerson: Option[IdRef],
      concEquip: Option[IdRef],
      folder: Option[IdRef],
      notes: Option[String]
  )

  case class Query(
      account: AccountId,
      name: Option[String],
      states: Seq[ItemState],
      direction: Option[Direction],
      corrPerson: Option[Ident],
      corrOrg: Option[Ident],
      concPerson: Option[Ident],
      concEquip: Option[Ident],
      folder: Option[Ident],
      tagsInclude: List[Ident],
      tagsExclude: List[Ident],
      tagCategoryIncl: List[String],
      tagCategoryExcl: List[String],
      dateFrom: Option[Timestamp],
      dateTo: Option[Timestamp],
      dueDateFrom: Option[Timestamp],
      dueDateTo: Option[Timestamp],
      allNames: Option[String],
      itemIds: Option[Set[Ident]],
      orderAsc: Option[RItem.Columns.type => Column]
  )

  object Query {
    def empty(account: AccountId): Query =
      Query(
        account,
        None,
        Seq.empty,
        None,
        None,
        None,
        None,
        None,
        None,
        Nil,
        Nil,
        Nil,
        Nil,
        None,
        None,
        None,
        None,
        None,
        None,
        None
      )
  }

  case class Batch(offset: Int, limit: Int) {
    def restrictLimitTo(n: Int): Batch =
      Batch(offset, math.min(n, limit))

    def next: Batch =
      Batch(offset + limit, limit)

    def first: Batch =
      Batch(0, limit)
  }

  object Batch {
    val all: Batch = Batch(0, Int.MaxValue)

    def page(n: Int, size: Int): Batch =
      Batch(n * size, size)

    def limit(c: Int): Batch =
      Batch(0, c)
  }

  private def findItemsBase(
      q: Query,
      distinct: Boolean,
      noteMaxLen: Int,
      moreCols: Seq[Fragment],
      ctes: (String, Fragment)*
  ): Fragment = {
    val IC         = RItem.Columns
    val AC         = RAttachment.Columns
    val PC         = RPerson.Columns
    val OC         = ROrganization.Columns
    val EC         = REquipment.Columns
    val FC         = RFolder.Columns
    val itemCols   = IC.all
    val personCols = List(PC.pid, PC.name)
    val orgCols    = List(OC.oid, OC.name)
    val equipCols  = List(EC.eid, EC.name)
    val folderCols = List(FC.id, FC.name)

    val finalCols = commas(
      Seq(
        IC.id.prefix("i").f,
        IC.name.prefix("i").f,
        IC.state.prefix("i").f,
        coalesce(IC.itemDate.prefix("i").f, IC.created.prefix("i").f),
        IC.dueDate.prefix("i").f,
        IC.source.prefix("i").f,
        IC.incoming.prefix("i").f,
        IC.created.prefix("i").f,
        fr"COALESCE(a.num, 0)",
        OC.oid.prefix("o0").f,
        OC.name.prefix("o0").f,
        PC.pid.prefix("p0").f,
        PC.name.prefix("p0").f,
        PC.pid.prefix("p1").f,
        PC.name.prefix("p1").f,
        EC.eid.prefix("e1").f,
        EC.name.prefix("e1").f,
        FC.id.prefix("f1").f,
        FC.name.prefix("f1").f,
        // sql uses 1 for first character
        IC.notes.prefix("i").substring(1, noteMaxLen),
        // last column is only for sorting
        q.orderAsc match {
          case Some(co) =>
            coalesce(co(IC).prefix("i").f, IC.created.prefix("i").f)
          case None =>
            IC.created.prefix("i").f
        }
      ) ++ moreCols
    )

    val withItem = selectSimple(itemCols, RItem.table, IC.cid.is(q.account.collective))
    val withPerson =
      selectSimple(personCols, RPerson.table, PC.cid.is(q.account.collective))
    val withOrgs =
      selectSimple(orgCols, ROrganization.table, OC.cid.is(q.account.collective))
    val withEquips =
      selectSimple(equipCols, REquipment.table, EC.cid.is(q.account.collective))
    val withFolder =
      selectSimple(folderCols, RFolder.table, FC.collective.is(q.account.collective))
    val withAttach = fr"SELECT COUNT(" ++ AC.id.f ++ fr") as num, " ++ AC.itemId.f ++
      fr"from" ++ RAttachment.table ++ fr"GROUP BY (" ++ AC.itemId.f ++ fr")"

    val selectKW = if (distinct) fr"SELECT DISTINCT" else fr"SELECT"
    withCTE(
      (Seq(
        "items"   -> withItem,
        "persons" -> withPerson,
        "orgs"    -> withOrgs,
        "equips"  -> withEquips,
        "attachs" -> withAttach,
        "folders" -> withFolder
      ) ++ ctes): _*
    ) ++
      selectKW ++ finalCols ++ fr" FROM items i" ++
      fr"LEFT JOIN attachs a ON" ++ IC.id.prefix("i").is(AC.itemId.prefix("a")) ++
      fr"LEFT JOIN persons p0 ON" ++ IC.corrPerson.prefix("i").is(PC.pid.prefix("p0")) ++
      fr"LEFT JOIN orgs o0 ON" ++ IC.corrOrg.prefix("i").is(OC.oid.prefix("o0")) ++
      fr"LEFT JOIN persons p1 ON" ++ IC.concPerson.prefix("i").is(PC.pid.prefix("p1")) ++
      fr"LEFT JOIN equips e1 ON" ++ IC.concEquipment
      .prefix("i")
      .is(EC.eid.prefix("e1")) ++
      fr"LEFT JOIN folders f1 ON" ++ IC.folder.prefix("i").is(FC.id.prefix("f1"))
  }

  def findItems(
      q: Query,
      maxNoteLen: Int,
      batch: Batch
  ): Stream[ConnectionIO, ListItem] = {
    val IC = RItem.Columns
    val PC = RPerson.Columns
    val OC = ROrganization.Columns
    val EC = REquipment.Columns

    // inclusive tags are AND-ed
    val tagSelectsIncl = (q.tagsInclude
      .map(tid =>
        selectSimple(
          List(RTagItem.Columns.itemId),
          RTagItem.table,
          RTagItem.Columns.tagId.is(tid)
        )
      ) ++ q.tagCategoryIncl.map(cat =>
      TagItemName.itemsInCategory(NonEmptyList.of(cat))
    ))
      .map(f => sql"(" ++ f ++ sql") ")

    // exclusive tags are OR-ed
    val tagSelectsExcl =
      TagItemName.itemsWithTagOrCategory(q.tagsExclude, q.tagCategoryExcl)

    val iFolder  = IC.folder.prefix("i")
    val name     = q.name.map(_.toLowerCase).map(queryWildcard)
    val allNames = q.allNames.map(_.toLowerCase).map(queryWildcard)
    val cond = and(
      IC.cid.prefix("i").is(q.account.collective),
      IC.state.prefix("i").isOneOf(q.states),
      IC.incoming.prefix("i").isOrDiscard(q.direction),
      name
        .map(n => IC.name.prefix("i").lowerLike(n))
        .getOrElse(Fragment.empty),
      allNames
        .map(n =>
          or(
            OC.name.prefix("o0").lowerLike(n),
            PC.name.prefix("p0").lowerLike(n),
            PC.name.prefix("p1").lowerLike(n),
            EC.name.prefix("e1").lowerLike(n),
            IC.name.prefix("i").lowerLike(n),
            IC.notes.prefix("i").lowerLike(n)
          )
        )
        .getOrElse(Fragment.empty),
      RPerson.Columns.pid.prefix("p0").isOrDiscard(q.corrPerson),
      ROrganization.Columns.oid.prefix("o0").isOrDiscard(q.corrOrg),
      RPerson.Columns.pid.prefix("p1").isOrDiscard(q.concPerson),
      REquipment.Columns.eid.prefix("e1").isOrDiscard(q.concEquip),
      RFolder.Columns.id.prefix("f1").isOrDiscard(q.folder),
      if (q.tagsInclude.isEmpty && q.tagCategoryIncl.isEmpty) Fragment.empty
      else
        IC.id.prefix("i") ++ sql" IN (" ++ tagSelectsIncl
          .reduce(_ ++ fr"INTERSECT" ++ _) ++ sql")",
      if (q.tagsExclude.isEmpty && q.tagCategoryExcl.isEmpty) Fragment.empty
      else IC.id.prefix("i").f ++ sql" NOT IN (" ++ tagSelectsExcl ++ sql")",
      q.dateFrom
        .map(d =>
          coalesce(IC.itemDate.prefix("i").f, IC.created.prefix("i").f) ++ fr">= $d"
        )
        .getOrElse(Fragment.empty),
      q.dateTo
        .map(d =>
          coalesce(IC.itemDate.prefix("i").f, IC.created.prefix("i").f) ++ fr"<= $d"
        )
        .getOrElse(Fragment.empty),
      q.dueDateFrom.map(d => IC.dueDate.prefix("i").isGt(d)).getOrElse(Fragment.empty),
      q.dueDateTo.map(d => IC.dueDate.prefix("i").isLt(d)).getOrElse(Fragment.empty),
      q.itemIds
        .map(ids =>
          NonEmptyList
            .fromList(ids.toList)
            .map(nel => IC.id.prefix("i").isIn(nel))
            .getOrElse(IC.id.prefix("i").is(""))
        )
        .getOrElse(Fragment.empty),
      or(iFolder.isNull, iFolder.isIn(QFolder.findMemberFolderIds(q.account)))
    )

    val order = q.orderAsc match {
      case Some(co) =>
        orderBy(coalesce(co(IC).prefix("i").f, IC.created.prefix("i").f) ++ fr"ASC")
      case None =>
        orderBy(
          coalesce(IC.itemDate.prefix("i").f, IC.created.prefix("i").f) ++ fr"DESC"
        )
    }
    val limitOffset =
      if (batch == Batch.all) Fragment.empty
      else fr"LIMIT ${batch.limit} OFFSET ${batch.offset}"

    val query = findItemsBase(q, true, maxNoteLen, Seq.empty)
    val frag =
      query ++ fr"WHERE" ++ cond ++ order ++ limitOffset
    logger.trace(s"List $batch items: $frag")
    frag.query[ListItem].stream
  }

  case class SelectedItem(itemId: Ident, weight: Double)
  def findSelectedItems(
      q: Query,
      maxNoteLen: Int,
      items: Set[SelectedItem]
  ): Stream[ConnectionIO, ListItem] =
    if (items.isEmpty) Stream.empty
    else {
      val IC = RItem.Columns
      val values = items
        .map(it => fr"(${it.itemId}, ${it.weight})")
        .reduce((r, e) => r ++ fr"," ++ e)

      val from = findItemsBase(
        q,
        true,
        maxNoteLen,
        Seq(fr"tids.weight"),
        ("tids(item_id, weight)", fr"(VALUES" ++ values ++ fr")")
      ) ++
        fr"INNER JOIN tids ON" ++ IC.id.prefix("i").f ++ fr" = tids.item_id" ++
        fr"ORDER BY tids.weight DESC"

      logger.trace(s"fts query: $from")
      from.query[ListItem].stream
    }

  case class ListItemWithTags(item: ListItem, tags: List[RTag])

  /** Same as `findItems` but resolves the tags for each item. Note that
    * this is implemented by running an additional query per item.
    */
  def findItemsWithTags(
      collective: Ident,
      search: Stream[ConnectionIO, ListItem]
  ): Stream[ConnectionIO, ListItemWithTags] = {
    def findTag(
        cache: Ref[ConnectionIO, Map[Ident, RTag]],
        tagItem: RTagItem
    ): ConnectionIO[Option[RTag]] =
      for {
        cc <- cache.get
        fromCache = cc.get(tagItem.tagId)
        orFromDB <-
          if (fromCache.isDefined) fromCache.pure[ConnectionIO]
          else RTag.findById(tagItem.tagId)
        _ <-
          if (fromCache.isDefined) ().pure[ConnectionIO]
          else
            orFromDB match {
              case Some(t) => cache.update(tmap => tmap.updated(t.tagId, t))
              case None    => ().pure[ConnectionIO]
            }
      } yield orFromDB

    for {
      resolvedTags <- Stream.eval(Ref.of[ConnectionIO, Map[Ident, RTag]](Map.empty))
      item         <- search
      tagItems     <- Stream.eval(RTagItem.findByItem(item.id))
      tags         <- Stream.eval(tagItems.traverse(ti => findTag(resolvedTags, ti)))
      ftags = tags.flatten.filter(t => t.collective == collective)
    } yield ListItemWithTags(item, ftags.toList.sortBy(_.name))
  }

  def delete[F[_]: Sync](store: Store[F])(itemId: Ident, collective: Ident): F[Int] =
    for {
      rn <- QAttachment.deleteItemAttachments(store)(itemId, collective)
      tn <- store.transact(RTagItem.deleteItemTags(itemId))
      mn <- store.transact(RSentMail.deleteByItem(itemId))
      n  <- store.transact(RItem.deleteByIdAndCollective(itemId, collective))
    } yield tn + rn + n + mn

  private def findByFileIdsQuery(
      fileMetaIds: NonEmptyList[Ident],
      limit: Option[Int]
  ): Fragment = {
    val IC      = RItem.Columns.all.map(_.prefix("i"))
    val aItem   = RAttachment.Columns.itemId.prefix("a")
    val aId     = RAttachment.Columns.id.prefix("a")
    val aFileId = RAttachment.Columns.fileId.prefix("a")
    val iId     = RItem.Columns.id.prefix("i")
    val sId     = RAttachmentSource.Columns.id.prefix("s")
    val sFileId = RAttachmentSource.Columns.fileId.prefix("s")
    val rId     = RAttachmentArchive.Columns.id.prefix("r")
    val rFileId = RAttachmentArchive.Columns.fileId.prefix("r")
    val m1Id    = RFileMeta.Columns.id.prefix("m1")
    val m2Id    = RFileMeta.Columns.id.prefix("m2")
    val m3Id    = RFileMeta.Columns.id.prefix("m3")

    val from =
      RItem.table ++ fr"i INNER JOIN" ++ RAttachment.table ++ fr"a ON" ++ aItem.is(iId) ++
        fr"INNER JOIN" ++ RAttachmentSource.table ++ fr"s ON" ++ aId.is(sId) ++
        fr"INNER JOIN" ++ RFileMeta.table ++ fr"m1 ON" ++ m1Id.is(aFileId) ++
        fr"INNER JOIN" ++ RFileMeta.table ++ fr"m2 ON" ++ m2Id.is(sFileId) ++
        fr"LEFT OUTER JOIN" ++ RAttachmentArchive.table ++ fr"r ON" ++ aId.is(rId) ++
        fr"LEFT OUTER JOIN" ++ RFileMeta.table ++ fr"m3 ON" ++ m3Id.is(rFileId)

    val q = selectSimple(
      IC,
      from,
      and(or(m1Id.isIn(fileMetaIds), m2Id.isIn(fileMetaIds), m3Id.isIn(fileMetaIds)))
    )

    limit match {
      case Some(n) => q ++ fr"LIMIT $n"
      case None    => q
    }
  }

  def findOneByFileIds(fileMetaIds: Seq[Ident]): ConnectionIO[Option[RItem]] =
    NonEmptyList.fromList(fileMetaIds.toList) match {
      case Some(nel) =>
        findByFileIdsQuery(nel, Some(1)).query[RItem].option
      case None =>
        (None: Option[RItem]).pure[ConnectionIO]
    }

  def findByFileIds(fileMetaIds: Seq[Ident]): ConnectionIO[Vector[RItem]] =
    NonEmptyList.fromList(fileMetaIds.toList) match {
      case Some(nel) =>
        findByFileIdsQuery(nel, None).query[RItem].to[Vector]
      case None =>
        Vector.empty[RItem].pure[ConnectionIO]
    }

  def findByChecksum(checksum: String, collective: Ident): ConnectionIO[Vector[RItem]] = {
    val IC         = RItem.Columns.all.map(_.prefix("i"))
    val aItem      = RAttachment.Columns.itemId.prefix("a")
    val aId        = RAttachment.Columns.id.prefix("a")
    val aFileId    = RAttachment.Columns.fileId.prefix("a")
    val iId        = RItem.Columns.id.prefix("i")
    val iColl      = RItem.Columns.cid.prefix("i")
    val sId        = RAttachmentSource.Columns.id.prefix("s")
    val sFileId    = RAttachmentSource.Columns.fileId.prefix("s")
    val rId        = RAttachmentArchive.Columns.id.prefix("r")
    val rFileId    = RAttachmentArchive.Columns.fileId.prefix("r")
    val m1Id       = RFileMeta.Columns.id.prefix("m1")
    val m2Id       = RFileMeta.Columns.id.prefix("m2")
    val m3Id       = RFileMeta.Columns.id.prefix("m3")
    val m1Checksum = RFileMeta.Columns.checksum.prefix("m1")
    val m2Checksum = RFileMeta.Columns.checksum.prefix("m2")
    val m3Checksum = RFileMeta.Columns.checksum.prefix("m3")

    val from =
      RItem.table ++ fr"i INNER JOIN" ++ RAttachment.table ++ fr"a ON" ++ aItem.is(iId) ++
        fr"INNER JOIN" ++ RAttachmentSource.table ++ fr"s ON" ++ aId.is(sId) ++
        fr"INNER JOIN" ++ RFileMeta.table ++ fr"m1 ON" ++ m1Id.is(aFileId) ++
        fr"INNER JOIN" ++ RFileMeta.table ++ fr"m2 ON" ++ m2Id.is(sFileId) ++
        fr"LEFT OUTER JOIN" ++ RAttachmentArchive.table ++ fr"r ON" ++ aId.is(rId) ++
        fr"LEFT OUTER JOIN" ++ RFileMeta.table ++ fr"m3 ON" ++ m3Id.is(rFileId)

    selectSimple(
      IC,
      from,
      and(
        or(m1Checksum.is(checksum), m2Checksum.is(checksum), m3Checksum.is(checksum)),
        iColl.is(collective)
      )
    ).query[RItem]
      .to[Vector]
  }

  private def queryWildcard(value: String): String = {
    def prefix(n: String) =
      if (n.startsWith("*")) s"%${n.substring(1)}"
      else n

    def suffix(n: String) =
      if (n.endsWith("*")) s"${n.dropRight(1)}%"
      else n

    prefix(suffix(value))
  }

  final case class NameAndNotes(
      id: Ident,
      collective: Ident,
      folder: Option[Ident],
      name: String,
      notes: Option[String]
  )
  def allNameAndNotes(
      coll: Option[Ident],
      chunkSize: Int
  ): Stream[ConnectionIO, NameAndNotes] = {
    val iId     = RItem.Columns.id
    val iColl   = RItem.Columns.cid
    val iName   = RItem.Columns.name
    val iFolder = RItem.Columns.folder
    val iNotes  = RItem.Columns.notes

    val cols  = Seq(iId, iColl, iFolder, iName, iNotes)
    val where = coll.map(cid => iColl.is(cid)).getOrElse(Fragment.empty)
    selectSimple(cols, RItem.table, where)
      .query[NameAndNotes]
      .streamWithChunkSize(chunkSize)
  }
}
