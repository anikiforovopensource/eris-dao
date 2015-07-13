package com.pagerduty.eris.dao

import com.netflix.astyanax.{Keyspace, Cluster}
import com.pagerduty.eris.serializers._
import com.pagerduty.eris.schema.SchemaLoader
import com.pagerduty.eris.{TimeUuid, TestCluster}
import java.util.logging.{Level, Logger}
import org.scalamock.scalatest.MockFactory
import org.scalatest.{Outcome, Matchers}
import org.scalatest.fixture.FreeSpec
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}


class TestDao(protected val cluster: Cluster, protected val keyspace: Keyspace)
  extends MapperDao[TimeUuid, test.TestEntity]
{
  val entityClass = classOf[test.TestEntity]
  val mainFamily = entityColumnFamily("testDaoMainCf")()

  def intercepted: Seq[String] = _intercepted
  def clearIntercepted() = { _intercepted = Seq.empty[String] }
  private var _intercepted = Seq.empty[String]
  override protected def instrument[T](methodName: String): (Future[T] => Future[T]) = {
    future => {
      _intercepted :+= methodName
      future
    }
  }

  def find(id: TimeUuid) = mapperFind(id)
  def find(ids: Iterable[TimeUuid], batchSize: Option[Int]) = mapperFind(ids, batchSize)
  def persist(id: TimeUuid, entity: test.TestEntity) = mapperPersist(id, entity)
  def remove(id: TimeUuid) = mapperRemove(id)
}


class MapperDaoCrudSpec extends FreeSpec with Matchers with MockFactory {
  Logger.getLogger("com.pagerduty.eris.schema.SchemaLoader").setLevel(Level.OFF)

  type FixtureParam = TestDao

  override def withFixture(test: OneArgTest): Outcome = {
    val cluster = TestCluster.cluster
    val keyspace = cluster.getKeyspace("MapperDaoCrudSpec" + Thread.currentThread.getId)
    val dao = new TestDao(cluster, keyspace)
    val schemaLoader = new SchemaLoader(cluster, dao.columnFamilyDefs)

    try {
      schemaLoader.loadSchema()
      withFixture(test.toNoArgTest(dao))
    }
    finally {
      schemaLoader.dropSchema()
    }
  }

  def wait[T](future: Future[T]): T = Await.result(future, Duration.Inf)


  "When doing CRUD MapperDao should" - {

    "persist, find, and remove correctly" in { dao =>
      val id = TimeUuid()
      val entity = test.TestEntity("a", 10)

      wait(dao.find(id)) shouldBe None
      wait(dao.persist(id, entity))
      wait(dao.find(id)) shouldBe Some(entity)
      wait(dao.remove(id))
      wait(dao.find(id)) shouldBe None

      dao.intercepted shouldBe Seq(
        "mapperFind", "mapperPersist", "mapperFind", "mapperRemove", "mapperFind")
    }

    "find batch correctly" in { dao =>
      val data = for (i <- 0 until 10) yield (TimeUuid(), new test.TestEntity(i.toString(), i))
      val entries = data.toMap
      val partial = entries.take(5)
      for ((id, entity) <- entries) wait(dao.persist(id, entity))
      dao.clearIntercepted()

      wait(dao.find(partial.keySet, None)) shouldBe partial
      wait(dao.find(entries.keySet, Some(2))) shouldBe entries

      dao.intercepted shouldBe Seq("mapperFindBatch", "mapperFindBatch")
    }
  }
}