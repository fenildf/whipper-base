package models

import play.api.Play.current
// redis cache plugin
import play.api.cache.Cache

import play.api.db.slick.DB
import play.api.db.slick.Config.driver.simple._

import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Set

import akka.actor.Actor

import utils._

case class CatalogPOJO(
	var name: Option[String] = None,
	var supid: Option[String] = Option("0"),
	var struct: Option[String] = None,
	var updtime: Option[Long] = Option(0),
	var inittime: Option[Long] = Option(0),
	var tombstone: Option[Int] = Option(0),
	var id: Option[Long] = None)

case class Catalog(
	var name: Option[String] = None,
	var supid: Option[String] = Option("0"),
	var struct: Option[List[StructElem]] = None,
	var updtime: Option[Long] = Option(0),
	var inittime: Option[Long] = Option(0),
	var tombstone: Option[Int] = Option(0),
	var id: Option[Long] = None)

object CatalogResultStatus {
	def CATALOG_NOT_FOUND = { Option(404) }
	def CATALOG_PARAMS_NOT_FOUND = { Option(601) }
	def CATALOG_OK = { Option(200) }
}

case class CatalogResult(
	val status: Option[Int] = None,
	val catalog: Option[Catalog] = None)

case class CatalogListResult(
	val status: Option[Int] = None,
	val catalogs: Option[List[Catalog]],
	val count: Option[Int]) extends Serializable

class Catalogs(tag: Tag) 
	extends Table[CatalogPOJO](tag, "catalog") {
	def id = column[Option[Long]]("id", O.PrimaryKey, O.AutoInc)
	def name = column[Option[String]]("name")
	def supid = column[Option[String]]("supid")
	def struct = column[Option[String]]("struct")
	def updtime = column[Option[Long]]("update_time")
	def inittime = column[Option[Long]]("init_time")
	def tombstone = column[Option[Int]]("tombstone")

	def * = (name, supid, struct,
			updtime, inittime, tombstone, id) <> 
			(CatalogPOJO.tupled, CatalogPOJO.unapply _)
}

trait CatalogJSONTrait extends StructElemJSONTrait {
	// JSON default formats
	implicit val CatalogPOJOFormat = Json.format[CatalogPOJO]
	implicit val CatalogFormat = Json.format[Catalog]
	implicit val CatalogResultFormat = Json.format[CatalogResult]
	implicit val CatalogListResultFormat = Json.format[CatalogListResult]
}

object Catalogs extends CatalogJSONTrait {
	// ORM table of Catalog
	val table = TableQuery[Catalogs]
	implicit lazy val catalogCacheKey = "catalog:list"

	private def getPOJOFromClass(
		catalog: Catalog): CatalogPOJO = {
		var structJSONCmpString = None: Option[String]
		var updtime = Option(System.currentTimeMillis()/1000L)
		var inittime = Option(System.currentTimeMillis()/1000L)
		var tombstone = Option(0)

		if (catalog.struct.isDefined) {
			structJSONCmpString = Snoopy.comp(
				Option(Json.toJson(catalog.struct.get).toString))
		}

		if (catalog.updtime.isDefined) {
			updtime = catalog.updtime
		}

		if (catalog.inittime.isDefined) {
			inittime = catalog.inittime
		}

		if (catalog.tombstone.isDefined) {
			tombstone = catalog.tombstone
		}

		val catalogPOJO = CatalogPOJO(
			catalog.name,
			catalog.supid,
			structJSONCmpString,
			updtime,
			inittime,
			tombstone,
			catalog.id)
		catalogPOJO
	}

	private def getClassFromPOJO(
		catalogPOJO: CatalogPOJO): Catalog = {
		var structListOpt = None: Option[List[StructElem]]
		if (catalogPOJO.struct.isDefined) {
			val structListJSON = Json.parse(Snoopy.decomp(catalogPOJO.struct).get)
			structListJSON.validate[List[StructElem]] match {
				case s: JsSuccess[List[StructElem]] => {
					structListOpt = Option(s.get)
				}
				case e: JsError => {
					// error handling flow
				}
			}
		}

		val catalog = Catalog(
			catalogPOJO.name,
			catalogPOJO.supid,
			structListOpt,
			catalogPOJO.updtime,
			catalogPOJO.inittime,
			catalogPOJO.tombstone,
			catalogPOJO.id)
		catalog
	}

	private def duplicateIfNotNone(
		srcPOJO: CatalogPOJO, dstPOJO: CatalogPOJO) = {
		if (srcPOJO.name.isDefined) {
			dstPOJO.name = srcPOJO.name
		}
		if (srcPOJO.supid.isDefined) {
			dstPOJO.supid = srcPOJO.supid
		}
		if (srcPOJO.struct.isDefined) {
			dstPOJO.struct = srcPOJO.struct
		}
	}

	def add(catalog: Catalog)
		(implicit session: Session): Option[CatalogResult] = {
		var retOpt = None: Option[CatalogResult]

		if (catalog.supid.isDefined) {
			val supid = catalog.supid.get.toLong
			// get the parent catalog by supid
			val supCatalogOpt = table.filter(_.id === supid)
								.filter(_.tombstone === 0)
								.take(1).firstOption
			if (supCatalogOpt.isDefined) {
				val supCatalog = supCatalogOpt.get
				val supidInSupCatalog = supCatalog.supid
				catalog.supid = Option(supidInSupCatalog.get + "." + supCatalog.id.get)
			} else {
				catalog.supid = Option("0")
			}
		} else {
			catalog.supid = Option("0")	
		}

		val catalogPOJO = getPOJOFromClass(catalog)
		// allocate a new catalog
		val id = (table returning table.map(_.id)) += catalogPOJO
		catalog.id = id	

		// wrap the retrieved result
		val catalogResult = CatalogResult(
			CatalogResultStatus.CATALOG_OK,
			Option(catalog))
		retOpt = Option(catalogResult)

		// remove cached
		Cache.remove(catalogCacheKey)

		retOpt
	}

	def retrieve(id: Long)
		(implicit session: Session): Option[CatalogResult] = {
		var retOpt = None: Option[CatalogResult]

		val catalogPOJOOpt = table.filter(_.id === id)
								.filter(_.tombstone === 0)
								.take(1).firstOption
		if (catalogPOJOOpt.isDefined) {
			val catalogPOJO = catalogPOJOOpt.get

			val catalogResult = CatalogResult(
					CatalogResultStatus.CATALOG_OK,
					Option(getClassFromPOJO(catalogPOJO)))
			retOpt = Option(catalogResult)
		} else {
			// catalog doesn't exist
			val catalogResult = CatalogResult(
					CatalogResultStatus.CATALOG_NOT_FOUND,
					None)
			retOpt = Option(catalogResult)
		}

		retOpt		
	}

	def update(catalog: Catalog)
		(implicit session: Session): Option[CatalogResult] = {
		var retOpt = None: Option[CatalogResult]
		val updtime = Option(System.currentTimeMillis()/1000L)

		if (catalog.id.isDefined) {
			val id = catalog.id.get
			val queryPOJOOpt = table.filter(_.id === id)
									.filter(_.tombstone === 0)
									.take(1).firstOption
			if (queryPOJOOpt.isDefined) {
				val queryPOJO = queryPOJOOpt.get
				val updPOJO = getPOJOFromClass(catalog)
				updPOJO.updtime = updtime
				updPOJO.inittime = queryPOJO.inittime

				// update the queryPOJO with updPOJO
				duplicateIfNotNone(updPOJO, queryPOJO)

				table.filter(_.id === queryPOJO.id.get)
					.filter(_.tombstone === 0)
					.map(row => (row.name, row.struct, row.updtime))
					.update((queryPOJO.name, 
						queryPOJO.struct, 
						queryPOJO.updtime))

				val catalogResult = CatalogResult(
						CatalogResultStatus.CATALOG_OK,
						Option(getClassFromPOJO(queryPOJO)))
				retOpt = Option(catalogResult)
			} else {
				// catalog doesn't exist
				val catalogResult = CatalogResult(
						CatalogResultStatus.CATALOG_NOT_FOUND,
						None)
				retOpt = Option(catalogResult)
			}
		} else {
			// invalid id
			val catalogResult = CatalogResult(
					CatalogResultStatus.CATALOG_PARAMS_NOT_FOUND,
					None)
			retOpt = Option(catalogResult)
		}

		retOpt
	}

	def delete(catalog: Catalog)
		(implicit session: Session): Option[CatalogResult] = {
		var retOpt = None: Option[CatalogResult]
		val updtime = Option(System.currentTimeMillis()/1000L)

		if (catalog.id.isDefined) {
			val id = catalog.id.get
			val queryPOJOOpt = table.filter(_.id === id)
									.filter(_.tombstone === 0)
									.take(1).firstOption
			if (queryPOJOOpt.isDefined) {
				val queryPOJO = queryPOJOOpt.get

				val queryPOJOSupID = queryPOJO.supid.get
				val queryPOJODescendSupID = queryPOJOSupID + "." + queryPOJO.id.get

				val queryPOJODescendList = table.filter(_.tombstone === 0)
												.filter(_.supid like queryPOJODescendSupID + "%")
												.list
				// update the supid of all the descends
				for (descend <- queryPOJODescendList) {
					val descendSupID = descend.supid.get
					val updateDescendSupID = queryPOJOSupID + descendSupID.takeRight(
						descendSupID.size - queryPOJODescendSupID.size)
					table.filter(_.tombstone === 0)
						.filter(_.id === descend.id.get)
						.map(row => (row.supid, row.updtime))
						.update((Option(updateDescendSupID), updtime))
				}

				table.filter(_.id === id)
					.map(row => (row.tombstone, row.updtime))
					.update((Option(1), updtime))
				queryPOJO.tombstone = Option(1)
				queryPOJO.updtime = updtime

				// OK
				val catalogResult = CatalogResult(
						CatalogResultStatus.CATALOG_OK,
						Option(getClassFromPOJO(queryPOJO)))
				retOpt = Option(catalogResult)

				// remove cached
				Cache.remove(catalogCacheKey)
			} else {
				// catalog doesn't exists
				val catalogResult = CatalogResult(
						CatalogResultStatus.CATALOG_NOT_FOUND,
						None)
				retOpt = Option(catalogResult)
			}
		} else {
			// invalid id
			val catalogResult = CatalogResult(
					CatalogResultStatus.CATALOG_PARAMS_NOT_FOUND,
					None)
			retOpt = Option(catalogResult)				
		}
		retOpt
	}

	def structAppend(catalog: Catalog)
        (implicit session: Session): Option[CatalogResult] = {
        var retOpt = None: Option[CatalogResult]
        val updtime = Option(System.currentTimeMillis()/1000L)

        if (catalog.struct.isDefined && catalog.id.isDefined) {
            val id = catalog.id.get
            val structUpdatedList = catalog.struct.get

            val queryPOJOOpt = table.filter(_.id === id)
                                    .filter(_.tombstone === 0)
                                    .take(1).firstOption
            if (queryPOJOOpt.isDefined) {
                val queryPOJO = queryPOJOOpt.get
                val queryCatalog = getClassFromPOJO(queryPOJO)

                var structListUpdatedFinal = None: Option[List[StructElem]]
                if (queryCatalog.struct.isDefined) {
                    val queryCatalogStructList = queryCatalog.struct.get
                    structListUpdatedFinal = StructElem.structListUnion(
                            queryCatalogStructList, structUpdatedList)
                } else {
                    val queryCatalogStructList = List[StructElem]()
                    structListUpdatedFinal = StructElem.structListUnion(
                            queryCatalogStructList, structUpdatedList)                
                }

                if (structListUpdatedFinal.isDefined) {
                    val structJSONUpdateCmpString = Snoopy.comp(
                        Option(Json.toJson(structListUpdatedFinal.get).toString))
                    queryPOJO.struct = structJSONUpdateCmpString
                    queryPOJO.updtime = updtime

                    table.filter(_.id === id)
                        .filter(_.tombstone === 0)
                        .map(row => (row.struct, row.updtime))
                        .update((queryPOJO.struct, queryPOJO.updtime))

                    val catalogResult = CatalogResult(
                            CatalogResultStatus.CATALOG_OK,
                            Option(getClassFromPOJO(queryPOJO)))
                    retOpt = Option(catalogResult)
                }
            } else {
                // catalog doesn't exist
                val catalogResult = CatalogResult(
                    CatalogResultStatus.CATALOG_NOT_FOUND,
                    None)
                retOpt = Option(catalogResult)
            }
        } else {
            // invalid input params
            val catalogResult = CatalogResult(
                CatalogResultStatus.CATALOG_PARAMS_NOT_FOUND,
                None)
            retOpt = Option(catalogResult)
        }

        retOpt
    }

    def structRemove(catalog: Catalog)
        (implicit session: Session): Option[CatalogResult] = {
        var retOpt = None: Option[CatalogResult]
        val updtime = Option(System.currentTimeMillis()/1000L)

        if (catalog.struct.isDefined && catalog.id.isDefined) {
            val id = catalog.id.get
            val structRemovedList = catalog.struct.get

            val queryPOJOOpt = table.filter(_.id === id)
                                    .filter(_.tombstone === 0)
                                    .take(1).firstOption
            if (queryPOJOOpt.isDefined) {
                val queryPOJO = queryPOJOOpt.get
                val queryCatalog = getClassFromPOJO(queryPOJO)

                var structListUpdatedFinal = None: Option[List[StructElem]]
                if (queryPOJO.struct.isDefined) {
                    val queryCatalogStructList = queryCatalog.struct.get
                    structListUpdatedFinal = StructElem.structListDiff(
                            queryCatalogStructList, structRemovedList)
                }

                if (structListUpdatedFinal.isDefined) {
                    val structJSONUpdateCmpString = Snoopy.comp(
                        Option(Json.toJson(structListUpdatedFinal.get).toString))
                    queryPOJO.struct = structJSONUpdateCmpString
                    queryPOJO.updtime = updtime

                    table.filter(_.id === id)
                        .filter(_.tombstone === 0)
                        .map(row => (row.struct, row.updtime))
                        .update((queryPOJO.struct, queryPOJO.updtime))

                    val catalogResult = CatalogResult(
                            CatalogResultStatus.CATALOG_OK,
                            Option(getClassFromPOJO(queryPOJO)))
                    retOpt = Option(catalogResult)
                }
            } else {
                // catalog doesn't exist
                val catalogResult = CatalogResult(
                    CatalogResultStatus.CATALOG_NOT_FOUND,
                    None)
                retOpt = Option(catalogResult)
            }
        } else {
            // invalid input params
            val catalogResult = CatalogResult(
                CatalogResultStatus.CATALOG_PARAMS_NOT_FOUND,
                None)
            retOpt = Option(catalogResult)
        }

        retOpt
    }

    def structClean(catalog: Catalog)
        (implicit session: Session): Option[CatalogResult] = {
        var retOpt = None: Option[CatalogResult]
        val updtime = Option(System.currentTimeMillis()/1000L)

        if (catalog.struct.isDefined && catalog.id.isDefined) {
            val id = catalog.id.get

            val queryPOJOOpt = table.filter(_.id === id)
                                    .filter(_.tombstone === 0)
                                    .take(1).firstOption
            if (queryPOJOOpt.isDefined) {
                val queryPOJO = queryPOJOOpt.get
                val structJSONCleanCmpString = Snoopy.comp(
                        Option(JsArray(Seq()).toString))

                table.filter(_.id === id)
                    .filter(_.tombstone === 0)
                    .map(row => (row.struct, row.updtime))
                    .update((structJSONCleanCmpString, updtime))

                queryPOJO.struct = structJSONCleanCmpString
                queryPOJO.updtime = updtime
                val catalogResult = CatalogResult(
                        CatalogResultStatus.CATALOG_OK,
                        Option(getClassFromPOJO(queryPOJO)))
                retOpt = Option(catalogResult)
            } else {
                // catalog doesn't exist
                val catalogResult = CatalogResult(
                    CatalogResultStatus.CATALOG_NOT_FOUND,
                    None)
                retOpt = Option(catalogResult)
            }
        } else {
            // invalid input params
            val catalogResult = CatalogResult(
                CatalogResultStatus.CATALOG_PARAMS_NOT_FOUND,
                None)
            retOpt = Option(catalogResult)
        }

        retOpt
    }

	def query(page: Int, size: Int)
		(implicit session: Session): Option[CatalogListResult] = {
		var retOpt = None: Option[CatalogListResult]

		// get catalog list from cache
		val catalogListResultOptCached = Cache.getAs[CatalogListResult](catalogCacheKey)
		if (catalogListResultOptCached.isDefined) {
			val catalogListResult = catalogListResultOptCached.get
			val catalogListOpt = catalogListResult.catalogs
			if (catalogListOpt.isDefined) {
				val catalogList = catalogListOpt.get
				val count = catalogList.size
				val catalogListWithPaging = catalogList
							.drop(page * size)
							.take(size)
				retOpt = Option(CatalogListResult(
						CatalogResultStatus.CATALOG_OK,
						Option(catalogListWithPaging),
						Option(count)))
			}
		}

		if (!retOpt.isDefined) {
			val count = table.filter(_.tombstone === 0)
							.length.run
			val queryCatalogPOJOList = table.filter(_.tombstone === 0)
											.filter(_.supid === "0")
											.sortBy(_.id.desc)
											.list
			val queryCatalogResultList = queryCatalogPOJOList.map(
										(catalogPOJO) => getClassFromPOJO(catalogPOJO))

			// cache catalog result list
			val catalogListResultCached = CatalogListResult(
						CatalogResultStatus.CATALOG_OK,
						Option(queryCatalogResultList),
						Option(count))
			Cache.set(catalogCacheKey, catalogListResultCached)

			// return catalog list result with paging
			val catalogListWithPaging = queryCatalogResultList
						.drop(page * size)
						.take(size)
			val catalogListResult = CatalogListResult(
						CatalogResultStatus.CATALOG_OK,
						Option(catalogListWithPaging),
						Option(count))
			retOpt = Option(catalogListResult)
		}

		retOpt
	}
}

import models.CatalogsActor.{CatalogQuery, CatalogRetrieve, CatalogAdd, CatalogUpdate, CatalogDelete}
import models.CatalogsActor.{CatalogElemsAppend, CatalogElemsRemove, CatalogElemsClean}

object CatalogsActor {
	case class CatalogQuery(page: Int, size: Int)
	case class CatalogRetrieve(id: Long)
	case class CatalogAdd(catalog: Catalog)
	case class CatalogUpdate(catalog: Catalog)
	case class CatalogDelete(catalog: Catalog)
	case class CatalogElemsAppend(catalog: Catalog)
	case class CatalogElemsRemove(catalog: Catalog)
	case class CatalogElemsClean(catalog: Catalog)
}

class CatalogsActor extends Actor {
	def receive: Receive = {
		case CatalogQuery(page: Int, size: Int) => {
			DB.withTransaction { implicit session =>
				sender ! Catalogs.query(page, size)
			}
		}
		case CatalogRetrieve(id: Long) => {
			DB.withTransaction { implicit session =>
				sender ! Catalogs.retrieve(id)
			}
		}
		case CatalogAdd(catalog: Catalog) => {
			DB.withTransaction { implicit session =>
				sender ! Catalogs.add(catalog)
			}
		}
		case CatalogUpdate(catalog: Catalog) => {
			DB.withTransaction { implicit session =>
				sender ! Catalogs.update(catalog)
			}
		}
		case CatalogDelete(catalog: Catalog) => {
			DB.withTransaction { implicit session =>
				sender ! Catalogs.delete(catalog)
			}
		}
		case CatalogElemsAppend(catalog: Catalog) => {
			DB.withTransaction { implicit session =>
				sender ! Catalogs.structAppend(catalog)
			}
		}
		case CatalogElemsRemove(catalog: Catalog) => {
			DB.withTransaction { implicit session =>
				sender ! Catalogs.structRemove(catalog)
			}
		}
		case CatalogElemsClean(catalog: Catalog) => {
			DB.withTransaction { implicit session =>
				sender ! Catalogs.structClean(catalog)
			}
		}
	}
}