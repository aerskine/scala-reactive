package org.deler.reactive

import org.junit.runner.RunWith
import org.specs._
import org.specs.mock.Mockito
import org.specs.runner.{JUnitSuiteRunner, JUnit}
import scala.collection._
import org.joda.time.{Duration, Instant}
import org.scalacheck.{Arbitrary, Prop}
import java.util.concurrent.TimeoutException

@RunWith(classOf[JUnitSuiteRunner])
class ObservableTest extends Specification with JUnit with Mockito with ScalaCheck {
  import Observable.iterableToObservableWrapper


  val ex = new Exception("fail")

  val scheduler = new TestScheduler
  val observer = new TestObserver[String](scheduler)

  /**
   * Generate arbitrary sequences for use with ScalaCheck.
   */
  implicit def arbitraryObservable[T](implicit a: Arbitrary[List[T]]): Arbitrary[Observable[T]] = Arbitrary {
    for (s <- Arbitrary.arbitrary[List[T]]) yield s.toObservable
  }

  "Observable.amb" should {
    "return the left observable when it produces a notification before the right observable" in {
      val left = scheduler.createHotObservable(Seq(250 -> OnCompleted))
      val right = scheduler.createHotObservable(Seq(300 -> OnCompleted))

      val notifications = scheduler.run(left amb right)

      notifications must be equalTo Seq(250 -> OnCompleted)
      left.subscriptions must be equalTo Seq(200 -> 250)
      right.subscriptions must be equalTo Seq(200 -> 250)
    }

    "return the right observable when it produces a notification before the left observable" in {
      val left = scheduler.createHotObservable(Seq(350 -> OnCompleted))
      val right = scheduler.createHotObservable(Seq(300 -> OnCompleted))

      val notifications = scheduler.run(left amb right)

      notifications must be equalTo Seq(300 -> OnCompleted)
      left.subscriptions must be equalTo Seq(200 -> 300)
      right.subscriptions must be equalTo Seq(200 -> 300)
    }
  }

  "Observable.create" should {
    "invoke delegate on subscription with the observer as argument" in {
      var delegateCalled = false
      val observable = Observable.create[String] {
        observer =>
          delegateCalled = true
          observer.onNext("delegate")
          () => {}
      }

      observable.subscribe(observer)

      delegateCalled must be equalTo true
      observer.notifications must be equalTo Seq(0 -> OnNext("delegate"))
    }

    "invoke delegate's result when subscription is closed" in {
      var actionCalled = false
      val observable = Observable.create[String] {observer => () => actionCalled = true}
      val subscription = observable.subscribe(observer)

      subscription.close()

      actionCalled must be equalTo true
    }

    "enforce observable contract (no onNext after onCompleted)" in {
      val notifications = scheduler run {
        Observable.create {
          observer: Observer[String] =>
            observer.onCompleted()
            observer.onNext("ignored")
            () => {}
        }
      }

      notifications must be equalTo Seq(200 -> OnCompleted)
    }

    "enforce observable contract (no onNext after onError)" in {
      val notifications = scheduler run {
        Observable.create {
          observer: Observer[String] =>
            observer.onError(ex)
            observer.onNext("ignored")
            () => {}
        }
      }

      notifications must be equalTo Seq(200 -> OnError(ex))
    }

    "close subscription after onCompleted" in {
      var closed = false
      Observable.create {
        observer: Observer[String] =>
          observer.onCompleted()
          () => {closed = true}
      }.subscribe(observer)

      closed must beTrue
    }

  }

  "Observable.interval" should {
    "generate a sequence value seperated by duration" in {
      val notifications = scheduler.run {Observable.interval(new Duration(300))(scheduler)}

      notifications must be equalTo Seq(500 -> OnNext(0), 800 -> OnNext(1))
    }
  }

  "Observable.takeUntil" should {
    "not produce any value when other observable sequence produces first value" in {
      val source = scheduler.createHotObservable(Seq(300 -> OnNext("ignored")))
      val other = scheduler.createHotObservable(Seq(250 -> OnNext("trigger")))

      val notifications = scheduler.run(source.takeUntil(other))

      notifications must be equalTo Seq(250 -> OnCompleted)
      source.subscriptions must be equalTo Seq(200 -> 250)
      other.subscriptions must be equalTo Seq(200 -> 250)
    }

    "produce all source values when it completes before the other observable produces a value" in {
      val source = scheduler.createHotObservable(Seq(
        300 -> OnNext("first"),
        320 -> OnNext("second"),
        350 -> OnCompleted))
      val other = scheduler.createHotObservable(Seq(400 -> OnNext("trigger")))

      val notifications = scheduler.run(source.takeUntil(other))

      notifications must be equalTo Seq(
        300 -> OnNext("first"),
        320 -> OnNext("second"),
        350 -> OnCompleted)
      source.subscriptions must be equalTo Seq(200 -> 350)
      other.subscriptions must be equalTo Seq(200 -> 350)
    }

    "unsubscribe from source when first value is produced by other" in {
      val source = scheduler.createHotObservable(Seq(
        300 -> OnNext("first"),
        320 -> OnNext("second"),
        350 -> OnCompleted))
      val other = scheduler.createHotObservable(Seq(310 -> OnNext("trigger")))

      val notifications = scheduler.run(source.takeUntil(other))

      notifications must be equalTo Seq(
        300 -> OnNext("first"),
        310 -> OnCompleted)
      source.subscriptions must be equalTo Seq(200 -> 310)
      other.subscriptions must be equalTo Seq(200 -> 310)
    }

    "unsubscribe from other when it completes before source completes" in {
      val source = scheduler.createHotObservable(Seq(
        300 -> OnNext("first"),
        320 -> OnNext("second"),
        350 -> OnCompleted))
      val other = scheduler.createHotObservable(Seq(310 -> OnCompleted))

      val notifications = scheduler.run(source.takeUntil(other))

      notifications must be equalTo Seq(
        300 -> OnNext("first"),
        320 -> OnNext("second"),
        350 -> OnCompleted)
      source.subscriptions must be equalTo Seq(200 -> 350)
      other.subscriptions must be equalTo Seq(200 -> 310)
    }
  }

  "Observable.timer" should {
    "generate zero when expired" in {
      val notifications = scheduler.run {Observable.timer(new Duration(300))(scheduler)}

      notifications must be equalTo Seq(500 -> OnNext(0), 500 -> OnCompleted)
    }

    "not generate a value when unsubscribed" in {
      val notifications = scheduler.run(
        Observable.timer(new Duration(300))(scheduler),
        unsubscribeAt = new Instant(400))

      notifications must beEmpty
    }
  }

  "Observable.timeout" should {
    "return the source observable when it produces a value before the timeout" in {
      val source = scheduler.createHotObservable(Seq(
        300 -> OnNext("first"),
        500 -> OnNext("second"),
        600 -> OnCompleted))
      val other = scheduler.createHotObservable(Seq(
        450 -> OnNext("after timeout"),
        800 -> OnCompleted))

      val notifications = scheduler.run(source.timeout(new Duration(200), other, scheduler))

      notifications must be equalTo Seq(
        300 -> OnNext("first"),
        500 -> OnNext("second"),
        600 -> OnCompleted)
      source.subscriptions must be equalTo Seq(200 -> 600)
      other.subscriptions must beEmpty
    }

    "return the other observable when the source observable does not produce a value before the timeout" in {
      val source = scheduler.createHotObservable(Seq(
        300 -> OnNext("first"),
        500 -> OnNext("second"),
        600 -> OnCompleted))
      val other = scheduler.createHotObservable(Seq(
        450 -> OnNext("after timeout"),
        800 -> OnCompleted))

      val notifications = scheduler.run(source.timeout(new Duration(50), other, scheduler))

      notifications must be equalTo Seq(
        450 -> OnNext("after timeout"),
        800 -> OnCompleted)
      source.subscriptions must be equalTo Seq(200 -> 250)
      other.subscriptions must be equalTo Seq(250 -> 800)
    }

    "raise a TimeoutException when no other observable is provided" in {
      val source = scheduler.createHotObservable(Seq(500 -> OnCompleted))

      val notifications = scheduler.run(source.timeout(new Duration(200), scheduler))

      notifications must beLike {
        case Seq(Pair(400, OnError(error))) => error.isInstanceOf[TimeoutException]
      }
      source.subscriptions must be equalTo Seq(200 -> 400)
    }
  }

  "Iterable.toObservable" should {
    implicit val defaultToTestScheduler = scheduler

    "invoke onComplete when empty" in {
      val notifications = scheduler.run(Seq().toObservable)

      notifications must be equalTo Seq(201 -> OnCompleted)
    }
    "invoke onNext for each contained element followed by onComplete" in {
      val notifications = scheduler.run(Seq("first", "second").toObservable)

      notifications must be equalTo Seq(201 -> OnNext("first"), 202 -> OnNext("second"), 203 -> OnCompleted)
    }
    "stop producing values when the subscription is closed" in {
      val notifications = scheduler.run(Seq("first", "second").toObservable, unsubscribeAt = new Instant(202))

      notifications must be equalTo Seq(201 -> OnNext("first"))
    }
  }

  "Observable" should {
    implicit val defaultToTestScheduler = scheduler

    "allow easy subscription using single onNext method" in {
      Observable("value") subscribe (onNext = observer onNext _)
      scheduler.run()

      observer.notifications must be equalTo Seq(1 -> OnNext("value"))
    }

    "allow easy subscription using multiple methods" in {
      Observable("value") subscribe (onCompleted = () => observer.onCompleted(), onNext = observer.onNext(_))
      scheduler.run()

      observer.notifications must be equalTo Seq(1 -> OnNext("value"), 2 -> OnCompleted)
    }

    "allow easy subscription using the onError method" in {
      Observable.raise(ex) subscribe (onNext = observer.onNext(_), onError = observer.onError(_))
      scheduler.run()

      observer.notifications must be equalTo Seq(1 -> OnError(ex))
    }

    "collect values" in {
      val observable = Observable(1, "string")

      val notifications = scheduler run {
        observable.collect {case x: String => x.reverse}
      }

      notifications must be equalTo Seq(202 -> OnNext("gnirts"), 203 -> OnCompleted)
    }

    "allow filtering by type" in {
      val observable: Observable[Any] = Observable(1, "string")

      val notifications: Seq[(Int, Notification[String])] = scheduler run {
        observable.ofType(classOf[String])
      }

      notifications must be equalTo Seq(202 -> OnNext("string"), 203 -> OnCompleted)
    }

    "allow observing using for-comprehension" in {
      val observable = Observable("value")

      val notifications = scheduler run {
        for (v <- observable) yield v.reverse
      }

      notifications must be equalTo Seq(201 -> OnNext("eulav"), 202 -> OnCompleted)
    }

    "handle error on exception in map function" in {
      val notifications = scheduler run {Observable("value").map(_ => throw ex)}

      notifications must be equalTo Seq(201 -> OnError(ex))
    }

    "allow filtering using for-comprehension" in {
      val observable = Observable("first", "second")

      val notifications = scheduler run {
        for (v <- observable if v == "first") yield v.reverse
      }

      notifications must be equalTo Seq(201 -> OnNext("tsrif"), 203 -> OnCompleted)
    }

    "handle error on exception in filter predicate" in {
      val notifications = scheduler run {Observable("value").filter(_ => throw ex)}

      notifications must be equalTo Seq(201 -> OnError(ex))
    }

    "allow for nested for-comprehension" in {
      val observable1 = Observable("a", "b")
      val observable2 = Observable("c", "d")

      val notifications = scheduler run {
        for (e1 <- observable1; e2 <- observable2) yield e1 + e2
      }

      notifications must be equalTo Seq(
        // 201 -> observable1 produces onNext("a") and schedules onNext("b")
        // 201 -> first observable2 schedules onNext("c")
        // 202 -> observable1 produces onNext("b") and schedules onCompleted
        // 202 -> first observable2 produces OnNext("c") and schedules onNext("d")
        202 -> OnNext("ac"),
        // 202 -> second observable2 schedules onNext("c")
        // 203 -> observable1 produces onCompleted
        // 203 -> first observable2 produces onNext("d") and schedules onCompleted
        203 -> OnNext("ad"),
        // 203 -> second observable2 produces onNext("c") and schedules onNext("d")
        203 -> OnNext("bc"),
        // 204 -> first observable2 produces onCompleted
        // 204 -> second observable2 produces onNext("d") and schedules onCompleted
        204 -> OnNext("bd"),
        // 205 -> second observable2 produces onCompleted
        205 -> OnCompleted)
    }

    "stop nested for-comprehension on unsubscribe" in {
      val observable1 = Observable("a", "b")
      val observable2 = Observable("c", "d")

      val notifications = scheduler.run({
        for (e1 <- observable1; e2 <- observable2) yield e1 + e2
      }, unsubscribeAt = new Instant(204))

      notifications must be equalTo Seq(
        202 -> OnNext("ac"),
        203 -> OnNext("ad"),
        203 -> OnNext("bc"))
    }

    "stop merge on unsubscribe" in {
      val observables = Observable(Observable("a", "b"), Observable("c", "d"))

      val notifications = scheduler.run({observables.merge}, unsubscribeAt = new Instant(204))

      notifications must be equalTo Seq(
        202 -> OnNext("a"),
        203 -> OnNext("b"),
        203 -> OnNext("c"))
    }

    "pass values from flattened observables in the order produced" in {
      val observable1 = Observable("a", "b")
      val observable2 = Observable("c", "d", "e")

      val notifications = scheduler run {
        for (e1 <- observable1; e2 <- observable2) yield e1 + e2
      }

      notifications must be equalTo Seq(
        202 -> OnNext("ac"),
        203 -> OnNext("ad"),
        203 -> OnNext("bc"),
        204 -> OnNext("ae"),
        204 -> OnNext("bd"),
        205 -> OnNext("be"),
        206 -> OnCompleted)
    }

    "perform side effects while yielding same values" in {
      val observable = Observable("a", "b")
      var count = 0
      val values = mutable.ListBuffer[String]()

      val notifications = scheduler run {
        observable perform {v => values += v; count += 1}
      }

      count must be equalTo 2
      values.toSeq must be equalTo Seq("a", "b")
      notifications must be equalTo Seq(
        201 -> OnNext("a"),
        202 -> OnNext("b"),
        203 -> OnCompleted)
    }

    "pass error notifactions to observers of perform" in {
      var invoked = false

      val notifications = scheduler run {
        Observable.raise(ex).perform(_ => invoked = true)
      }

      invoked must beFalse
      notifications must be equalTo Seq(201 -> OnError(ex))
    }
  }

  "Observable.first" should {
    "return the first observed value" in {
      val value = Observable("value").first

      value must be equalTo "value"
    }

    "ignore subsequent values" in {
      val value = Observable("first", "second").first

      value must be equalTo "first"
    }

    "throw an exception on error" in {
      val error: Observable[String] = Observable.raise(ex)

      error.first must throwException(ex)
    }

    "throw an exception when the observable is empty" in {
      val empty: Observable[String] = Observable.empty

      empty.first must throwA[IllegalStateException]
    }
  }

  "Observable.toSeq" should {
    "return all values until observable is completed" in {
      val seq = Observable.interval(new Duration(10)).take(5).toSeq

      seq must be equalTo (0 to 4)
    }
  }

  "empty observables" should {
    implicit val defaultToTestScheduler = scheduler

    "only publish onCompleted" in {
      val notifications = scheduler.run(Observable.empty)

      notifications must be equalTo Seq(201 -> OnCompleted)
    }

    "not publish anything when subscription is closed" in {
      val notifications = scheduler.run(Observable.empty, unsubscribeAt = new Instant(201))

      notifications must beEmpty
    }

  }

  "singleton observables" should {
    implicit val defaultToTestScheduler = scheduler

    "only publish single event followed by onCompleted" in {
      val notifications = scheduler.run(Observable.value("value"))

      notifications must be equalTo Seq(201 -> OnNext("value"), (202 -> OnCompleted))
    }

    "stop publishing when subscription is closed" in {
      val notifications = scheduler.run(Observable.value("value"), unsubscribeAt = new Instant(202))

      notifications must be equalTo Seq(201 -> OnNext("value"))
    }

  }

  "take n" should {
    implicit val defaultToTestScheduler = scheduler

    val sequence = Observable(1, 2, 3, 4)

    "stop immediately when n is 0" in {
      val notifications = scheduler.run(sequence.take(0))

      notifications must be equalTo Seq(201 -> OnCompleted)
    }

    "return only the first value when n = 1" in {
      val notifications = scheduler.run(sequence.take(1))

      notifications must be equalTo Seq(201 -> OnNext(1), 201 -> OnCompleted)
    }

    "return only the first three values when n = 3" in {
      val notifications = scheduler.run(sequence.take(3))

      notifications must be equalTo Seq(201 -> OnNext(1), 202 -> OnNext(2), 203 -> OnNext(3), 203 -> OnCompleted)
    }

    "stop return the all values when n is larger than the observable length" in {
      val notifications = scheduler.run(sequence.take(99))

      notifications must be equalTo Seq(
        201 -> OnNext(1), 202 -> OnNext(2), 203 -> OnNext(3), 204 -> OnNext(4), 205 -> OnCompleted)
    }

    "stop producing values when the subscription is disposed" in {
      val notifications = scheduler.run(sequence.take(99), unsubscribeAt = new Instant(203))

      notifications must be equalTo Seq(201 -> OnNext(1), 202 -> OnNext(2))
    }

    "have independent subscribers" in {
      val taken = sequence.take(1)

      val ns1 = scheduler.run(taken)
      val ns2 = scheduler.run(taken, unsubscribeAt = new Instant(1300))

      ns1 must be equalTo Seq(201 -> OnNext(1), 201 -> OnCompleted)
      ns2 must be equalTo Seq(1002 -> OnNext(1), 1002 -> OnCompleted)
    }
  }

  "concatenated observables" should {

    "be equal to first and second concatenated" in {
      Prop.forAll {(o1: Observable[Int], o2: Observable[Int]) => (o1 ++ o2).toSeq == (o1.toSeq ++ o2.toSeq)} must pass
    }

    "should subscribe to second when first completes" in {
      val first = scheduler.createHotObservable(Seq(
        300 -> OnNext("first"),
        400 -> OnCompleted))
      val second = scheduler.createHotObservable(Seq(
        399 -> OnNext("early"),
        400 -> OnNext("second"),
        500 -> OnCompleted))

      val notifications = scheduler run {first ++ second}

      notifications must be equalTo Seq(
        300 -> OnNext("first"),
        400 -> OnNext("second"),
        500 -> OnCompleted)

      first.subscriptions must be equalTo Seq(200 -> 400)
      second.subscriptions must be equalTo Seq(400 -> 500)
    }

    "not subscribe to second when first terminates with an error" in {
      val first = scheduler.createHotObservable(Seq(
        300 -> OnNext("first"),
        400 -> OnError(ex)))
      val second = scheduler.createHotObservable(Seq(
        400 -> OnNext("ignored"),
        500 -> OnCompleted))

      val notifications = scheduler run {first ++ second}

      notifications must be equalTo Seq(
        300 -> OnNext("first"),
        400 -> OnError(ex))

      first.subscriptions must be equalTo Seq(200 -> 400)
      second.subscriptions must beEmpty
    }

    "terminate when subscription is closed while observing first sequence" in {
      val first = scheduler.createHotObservable(Seq(
        300 -> OnNext("first"),
        400 -> OnCompleted))
      val second = scheduler.createHotObservable(Seq(
        400 -> OnNext("ignored"),
        500 -> OnCompleted))

      val notifications = scheduler.run({first ++ second}, unsubscribeAt = new Instant(350))

      notifications must be equalTo Seq(
        300 -> OnNext("first"))

      first.subscriptions must be equalTo Seq(200 -> 350)
      second.subscriptions must beEmpty
    }

    "terminate when subscription is closed while obesrving second sequence" in {
      val first = scheduler.createHotObservable(Seq(
        300 -> OnNext("first"),
        400 -> OnCompleted))
      val second = scheduler.createHotObservable(Seq(
        400 -> OnNext("second"),
        500 -> OnCompleted))

      val notifications = scheduler.run({first ++ second}, unsubscribeAt = new Instant(450))

      notifications must be equalTo Seq(
        300 -> OnNext("first"),
        400 -> OnNext("second"))

      first.subscriptions must be equalTo Seq(200 -> 400)
      second.subscriptions must be equalTo Seq(400 -> 450)
    }
  }

  "repeated observables" should {
    implicit val defaultToTestScheduler = scheduler

    "not publish anything when source is empty" in {
      val notifications = scheduler.run {Observable.empty.repeat}

      notifications must beEmpty
    }

    "republish single value" in {
      val notifications = scheduler.run {Observable.value("value").repeat.take(3)}

      notifications must be equalTo Seq(201 -> OnNext("value"), 203 -> OnNext("value"), 205 -> OnNext("value"), 205 -> OnCompleted)
    }

    "repeat source observable" in {
      val notifications = scheduler.run {Observable(1, 2, 3).repeat.take(7)}

      notifications must be equalTo Seq(
        201 -> OnNext(1), 202 -> OnNext(2), 203 -> OnNext(3),
        205 -> OnNext(1), 206 -> OnNext(2), 207 -> OnNext(3),
        209 -> OnNext(1), 209 -> OnCompleted)
    }

    "repeat zero" in {
      val notifications = scheduler.run {Observable.value("value").repeat(0)}

      notifications must be equalTo Seq(200 -> OnCompleted)
    }

    "repeat once" in {
      val notifications = scheduler.run {Observable.value("value").repeat(1)}

      notifications must be equalTo Seq(201 -> OnNext("value"), 202 -> OnCompleted)
    }

    "repeat twice" in {
      val notifications = scheduler.run {Observable.value("value").repeat(2)}

      notifications must be equalTo Seq(201 -> OnNext("value"), 203 -> OnNext("value"), 204 -> OnCompleted)
    }
  }

  "Observable.materialize" should {
    val observable = new ReplaySubject[String]
    val observer = mock[Observer[Notification[String]]]

    "provide each value in the stream as an instance of Notification" in {
      observable.onNext("hello")
      observable.onCompleted()

      val result = observable.materialize.subscribe(observer)

      there was one(observer).onNext(OnNext("hello")) then one(observer).onNext(OnCompleted)
    }

    "provide the error stream as an instance of OnError" in {
      observable.onError(ex)

      val result = observable.materialize.subscribe(observer)

      there was one(observer).onNext(OnError(ex))
    }
  }

  "Observable.dematerialize" should {
    val observable = new BasicSubject[Notification[String]]
    val observer = mock[Observer[String]]

    "map each OnNext value to Observer.onNext" in {
      observable.dematerialize.subscribe(observer)

      observable.onNext(OnNext("first"))
      observable.onNext(OnNext("second"))

      there was one(observer).onNext("first") then one(observer).onNext("second")
      there were noMoreCallsTo(observer)
    }

    "map OnCompleted to Observer.onCompleted" in {
      observable.dematerialize.subscribe(observer)

      observable.onNext(OnCompleted)

      there was one(observer).onCompleted()
      there were noMoreCallsTo(observer)
    }

    "map OnError to Observer.onError" in {
      observable.dematerialize.subscribe(observer)

      observable.onNext(OnError(ex))

      there was one(observer).onError(ex)
      there were noMoreCallsTo(observer)
    }

    "stop once OnCompleted has been processed" in {
      observable.dematerialize.subscribe(observer)

      observable.onNext(OnCompleted)
      observable.onNext(OnNext("ignored"))

      there was one(observer).onCompleted()
      there were noMoreCallsTo(observer)
    }

    "stop once OnError has been processed" in {
      observable.dematerialize.subscribe(observer)

      observable.onNext(OnError(ex))
      observable.onNext(OnNext("ignored"))

      there was one(observer).onError(ex)
      there were noMoreCallsTo(observer)
    }
  }

  "Observable exception handling" should {
    implicit val defaultToTestScheduler = scheduler

    "raise an error" in {
      val notifications = scheduler.run(Observable.raise(ex))

      notifications must be equalTo Seq(201 -> OnError(ex))
    }

    "switch to next rescue source on error" in {
      val errorSource = Observable.raise(ex)
      val rescueSource = Observable.value("rescue")

      val notifications = scheduler.run(errorSource.rescue(rescueSource))

      notifications must be equalTo Seq(202 -> OnNext("rescue"), 203 -> OnCompleted)
    }

    "still subscribe to next source when exception is raised immediately" in {
      val immediateError = Observable.raise(ex)(Scheduler.immediate)
      val rescueSource = Observable.value("rescue")

      val notifications = scheduler.run(immediateError.rescue(rescueSource))

      notifications must be equalTo Seq(201 -> OnNext("rescue"), 202 -> OnCompleted)
    }
  }

  "scheduled subscriptions" should {
    implicit val defaultToTestScheduler = scheduler

    "use specified scheduler" in {
      val observable = scheduler.createHotObservable(Seq())

      scheduler.run {observable.subscribeOn(scheduler)}

      observable.subscriptions must be equalTo Seq(201 -> 1001)
    }

    "schedule unsubscribe when subscription is closed closed" in {
      val observable = scheduler.createHotObservable(Seq())

      scheduler.run(observable.subscribeOn(scheduler), unsubscribeAt = new Instant(150))

      observable.subscriptions must be equalTo Seq(201 -> 202)
    }
  }

  "scheduled observers" should {
    "use specified scheduler for each notification" in {
      val observable = Observable(1, 2, 3)(Scheduler.immediate)

      val notifications = scheduler.run {observable.observeOn(scheduler)}

      notifications must be equalTo Seq(201 -> OnNext(1), 202 -> OnNext(2), 203 -> OnNext(3), 204 -> OnCompleted)
    }

    "work with immediate scheduler" in {
      var invoked = false

      Observable.value("value").observeOn(Scheduler.immediate).subscribe(_ => invoked = true)

      invoked must beTrue
    }

    "work with immediate scheduler when notifications are received through another scheduler" in {
      var invoked = false

      Observable.value("value")(Scheduler.currentThread).observeOn(Scheduler.immediate).subscribe(_ => invoked = true)

      invoked must beTrue
    }
  }

  "Observable.let" should {
    "pass this to delegate" in {
      val observable = Observable.empty

      val result = observable.let(x => x)

      (result eq observable) must beTrue
    }

    "create observable only once to avoid additional side-effects" in {
      var count = 0
      def make() = {
        count += 1
        Observable("value")
      }

      val result = make().let(xs => xs ++ xs)

      count must be equalTo 1
      result.toSeq must be equalTo Seq("value", "value")
    }
  }

}
