package hmda.query.repository.filing

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Keep, RunnableGraph, Sink }
import hmda.model.fi.lar.LarGenerators
import hmda.query.DbConfiguration._
import hmda.query.model.filing.{ LoanApplicationRegisterQuery, ModifiedLoanApplicationRegister }

import scala.concurrent.duration._
import org.scalatest.{ AsyncWordSpec, BeforeAndAfterAll, MustMatchers }

import scala.concurrent.{ Await, Future }

class FilingComponentSpec extends AsyncWordSpec with MustMatchers with FilingComponent with BeforeAndAfterAll with LarGenerators {

  import LarConverter._
  import config.profile.api._

  val duration = 5.seconds

  val repository = new LarRepository(config)
  val modifiedLarRepository = new ModifiedLarRepository(config)

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  override def beforeAll(): Unit = {
    super.beforeAll()
    dropAllObjects()
    Await.result(repository.createSchema(), duration)
    Await.result(modifiedLarRepository.createSchema(), duration)
  }

  override def afterAll(): Unit = {
    super.afterAll()
    dropAllObjects()
    system.terminate()
  }

  private def dropAllObjects() = {
    val db = repository.config.db
    val dropAll = sqlu"""DROP ALL OBJECTS"""
    Await.result(db.run(dropAll), duration)
  }

  "LAR Repository" must {
    "insert new records" in {
      val lar1 = toLoanApplicationRegisterQuery(sampleLar).copy(institutionId = "inst1")
      val lar2 = toLoanApplicationRegisterQuery(sampleLar).copy(institutionId = "inst1")
      repository.insertOrUpdate(lar1).map(x => x mustBe 1)
      repository.insertOrUpdate(lar2).map(x => x mustBe 1)
      modifiedLarRepository.findByInstitutionId("inst1").map {
        case xs: Seq[ModifiedLoanApplicationRegister] => xs.head.institutionId mustBe lar1.institutionId
      }
    }
    "modify records and read them back" in {
      val lar: LoanApplicationRegisterQuery = sampleLar.copy(agencyCode = 3)
      repository.insertOrUpdate(lar).map(x => x mustBe 1)
      val modified = lar.copy(agencyCode = 7)
      repository.insertOrUpdate(modified).map(x => x mustBe 1)
      repository.findById(lar.id).map {
        case Some(x) => x.agencyCode mustBe 7
        case None => fail
      }
    }
    "delete record" in {
      val lar: LoanApplicationRegisterQuery = toLoanApplicationRegisterQuery(sampleLar)
      repository.insertOrUpdate(lar).map(x => x mustBe 1)
      repository.findById(lar.id).map {
        case Some(_) => succeed
        case None => fail
      }
      repository.deleteById(lar.id).map(x => x mustBe 1)
      repository.findById(lar.id).map {
        case Some(_) => fail
        case None => succeed
      }
    }

    "delete all records for an institution id" in {
      val lar1: LoanApplicationRegisterQuery = toLoanApplicationRegisterQuery(sampleLar).copy(institutionId = "delete")
      val lar2: LoanApplicationRegisterQuery = toLoanApplicationRegisterQuery(sampleLar).copy(institutionId = "delete")
      val lar3: LoanApplicationRegisterQuery = toLoanApplicationRegisterQuery(sampleLar).copy(institutionId = "nope")
      repository.insertOrUpdate(lar1).map(x => x mustBe 1)
      repository.insertOrUpdate(lar2).map(x => x mustBe 1)
      repository.insertOrUpdate(lar3).map(x => x mustBe 1)
      repository.findById(lar1.id).map {
        case Some(_) => succeed
        case None => fail
      }
      repository.deleteByInstitutionId("delete").map(x => x mustBe 1)
      repository.findById(lar1.id).map {
        case Some(_) => fail
        case None => succeed
      }
    }

    "delete all records" in {
      val lar: LoanApplicationRegisterQuery = toLoanApplicationRegisterQuery(sampleLar)
      val lar2: LoanApplicationRegisterQuery = toLoanApplicationRegisterQuery(sampleLar)
      repository.insertOrUpdate(lar).map(x => x mustBe 1)
      repository.insertOrUpdate(lar2).map(x => x mustBe 1)
      repository.findById(lar.id).map {
        case Some(_) => succeed
        case None => fail
      }
      repository.deleteAll.map(x => x mustBe 1)
      repository.findById(lar.id).map {
        case Some(_) => fail
        case None => succeed
      }
    }

    "Stream rows for a specific institution id" in {
      val instId = "test"
      val period = ""
      val lar1 = toLoanApplicationRegisterQuery(sampleLar).copy(institutionId = instId)
      val lar2 = toLoanApplicationRegisterQuery(sampleLar).copy(institutionId = instId)
      val lar3 = toLoanApplicationRegisterQuery(sampleLar).copy(institutionId = instId)
      val lar4 = toLoanApplicationRegisterQuery(sampleLar).copy(institutionId = "otherTest")
      repository.insertOrUpdate(lar1)
      repository.insertOrUpdate(lar2)
      repository.insertOrUpdate(lar3)
      repository.insertOrUpdate(lar4)

      val lars = modifiedLarRepository.findByInstitutionIdSource(instId, period)
      val count = Flow[ModifiedLoanApplicationRegister].map(_ => 1)
      val sum: Sink[Int, Future[Int]] = Sink.fold[Int, Int](0)(_ + _)

      val counterGraph: RunnableGraph[Future[Int]] =
        lars
          .via(count)
          .toMat(sum)(Keep.right)

      val sumF: Future[Int] = counterGraph.run()
      val result = Await.result(sumF, duration)
      result mustBe 3

    }
  }

}

