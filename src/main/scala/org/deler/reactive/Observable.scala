package org.deler.reactive

import java.util.concurrent.atomic.AtomicInteger
import org.joda.time.Duration
import scala.collection._
import scala.concurrent.SyncVar
import scala.util.control.Exception._
import java.util.concurrent.{TimeoutException, LinkedBlockingQueue}

/**
 * An observable can be subscribed to by an [[org.deler.reactive.Observer]]. Observables produce zero or more values
 * using `onNext` optionally terminated by either a call to `onCompleted` or `onError`.
 *
 * @define coll observable sequence
 * @define Coll Observable
 */
trait Observable[+A] {
  import Observable._

  /**
   * Subscribes `observer`.
   */
  def subscribe(observer: Observer[A]): Subscription

  /**
   * Subscribes `onNext` to every value produced by this Observable.
   */
  def subscribe(onNext: A => Unit): Subscription = subscribe(onNext, defaultOnError, defaultOnCompleted)

  /**
   * Subscribes `onNext` and `onError` to this Observable.
   */
  def subscribe(onNext: A => Unit, onError: Exception => Unit): Subscription = subscribe(onNext, onError, defaultOnCompleted)

  /**
   * Subscribes `onNext` and `onCompleted` to this Observable.
   */
  def subscribe(onNext: A => Unit, onCompleted: () => Unit): Subscription = subscribe(onNext, defaultOnError, onCompleted)

  /**
   * Subscribes `onNext`, `onError`, and `onCompleted` to this Observable.
   */
  def subscribe(onNext: A => Unit, onError: Exception => Unit, onCompleted: () => Unit): Subscription = {
    val completedCallback = onCompleted
    val errorCallback = onError
    val nextCallback = onNext
    this subscribe (new Observer[A] {
      override def onCompleted() = completedCallback()

      override def onError(error: Exception) = errorCallback(error)

      override def onNext(value: A) = nextCallback(value)
    })
  }

  /**
   * Appends `that` observable to this observable.
   */
  def ++[B >: A](that: Observable[B]): Observable[B] = createWithSubscription {
    observer =>
      val subscription = new MutableSubscription
      val result = new MutableSubscription(subscription)

      subscription.set(this.subscribe(new DelegateObserver(observer) {
        override def onCompleted() = result clearAndSet {that.subscribe(observer)}
      }))

      result
  }

  /**
   * Returns the first $coll to produce a notification.
   */
  def amb[B >: A](other: Observable[B]): Observable[B] = createWithSubscription {
    observer =>
      val Unknown = 0
      val Left = 1
      val Right = 2

      val leftOrRight = new AtomicInteger(Unknown)

      val leftSubscription = new MutableSubscription
      val rightSubscription = new MutableSubscription
      val result = new CompositeSubscription(leftSubscription, rightSubscription)

      leftSubscription.set(this.materialize subscribe {
        notification =>
          if (leftOrRight.compareAndSet(Unknown, Left))
            result -= rightSubscription
          if (leftOrRight.get == Left)
            notification.accept(observer)
      })
      rightSubscription.set(other.materialize subscribe {
        notification =>
          if (leftOrRight.compareAndSet(Unknown, Right))
            result -= leftSubscription
          if (leftOrRight.get == Right)
            notification.accept(observer)
      })

      result
  }

  /**
   * A new observable defined by applying a partial function to all values produced by this observable on which the
   * function is defined.
   */
  def collect[B](pf: PartialFunction[A, B]): Observable[B] = {
    for (value <- this if pf.isDefinedAt(value)) yield pf(value)
  }

  /**
   * A new $coll by applying a function to all elements of this $coll and merging the results.
   */
  def flatMap[B](f: A => Observable[B]): Observable[B] = new NestedObservableWrapper(this.map(f)).merge

  /**
   * A new observable only containing the values from this observable for which the predicate is satisfied.
   */
  def filter(predicate: A => Boolean): Observable[A] = createWithSubscription {
    observer =>
      this.subscribe(new DelegateObserver(observer) {
        override def onNext(value: A) {
          catching(classOf[Exception]) either predicate(value) match {
            case Left(error) => observer.onError(error.asInstanceOf[Exception])
            case Right(true) => observer.onNext(value)
            case Right(false) =>
          }
        }
      })
  }

  /**
   * Returns the first value in this $coll.
   *
   * @throws IllegalStateException this $coll is empty.
   */
  def first: A = {
    val result = new SyncVar[Notification[A]]
    this.materialize.take(1).subscribe(result.set(_))
    result.get match {
      case OnNext(value) => value
      case OnError(error) => throw error
      case OnCompleted => throw new IllegalStateException("sequence is empty")
    }
  }

  /**
   * Alias for `filter` that is used by the Scala for-loop construct.
   */
  def withFilter(predicate: A => Boolean) = filter(predicate)

  /**
   * Binds `this` to the parameter of `f` so you can reuse `this` without additional side-effects. Example:
   *
   * {{{
   * makeObservable(x).let(xs => xs ++ xs)
   * }}}
   *
   * will invoke `makeObservable(x)` only once.
   */
  def let[B](f: Observable[A] => Observable[B]) = f(this)

  /**
   * A new observable defined by applying a function to all values produced by this observable.
   */
  def map[B](f: A => B): Observable[B] = createWithSubscription {
    observer =>
      this.subscribe(new Observer[A] {
        override def onNext(value: A) {
          catching(classOf[Exception]) either f(value) match {
            case Left(error) => observer.onError(error.asInstanceOf[Exception])
            case Right(mapped) => observer.onNext(mapped)
          }
        }

        override def onError(error: Exception) = observer.onError(error)

        override def onCompleted() = observer.onCompleted()
      })
  }

  /**
   * A new observable that materializes each notification of this observable as a [[org.deler.reactive.Notification]].
   */
  def materialize: Observable[Notification[A]] = createWithSubscription {
    observer =>
      this.subscribe(new Observer[A] {
        override def onCompleted() = observer.onNext(OnCompleted)

        override def onError(error: Exception) = observer.onNext(OnError(error))

        override def onNext(value: A) = observer.onNext(OnNext(value))
      })
  }

  /**
   * A new observable that only contains the values from this observable which are instances of the specified type.
   */
  def ofType[B](clazz: Class[B]): Observable[B] = {
    for (value <- this if clazz.isInstance(value)) yield clazz.cast(value)
  }

  /**
   * A new observable that executes `action` for its side-effects for each value produced by this observable.
   */
  def perform(action: A => Unit): Observable[A] = {
    for (value <- this) yield {
      action(value)
      value
    }
  }

  /**
   * Repeats the source observable indefinitely.
   */
  def repeat: Observable[A] = createWithSubscription {
    observer =>
      val result = new MutableSubscription
      def run() {
        result clearAndSet this.subscribe(new DelegateObserver(observer) {
          override def onCompleted() = run()
        })
      }

      run()
      result
  }

  /**
   * Repeats the source observable `n` times.
   */
  def repeat(n: Int): Observable[A] = createWithSubscription {
    observer =>
      val result = new MutableSubscription
      def run(count: Int) {
        if (count >= n) {
          observer.onCompleted()
        } else {
          result clearAndSet this.subscribe(new DelegateObserver(observer) {
            override def onCompleted() {
              run(count + 1)
            }
          })
        }
      }

      run(0)
      result
  }

  /**
   * A new observable that only produces up to `n` values from this observable and then completes.
   */
  def take(n: Int): Observable[A] = createWithSubscription {
    observer =>
      this.subscribe(new DelegateObserver(observer) {
        var count: Int = 0

        override def onNext(value: A) {
          if (count < n) {
            count += 1
            observer.onNext(value)
          }
          if (count >= n) {
            observer.onCompleted()
          }
        }
      })
  }

  /**
   * Takes values from this $coll until the `other` $coll produces a value.
   */
  def takeUntil(other: Observable[Any]): Observable[A] = createWithSubscription {
    observer =>
      val sourceSubscription = new MutableSubscription
      val otherSubscription = new MutableSubscription
      val result = new CompositeSubscription(sourceSubscription, otherSubscription)

      val target = new DelegateObserver[A](observer) with SynchronizedObserver[A]

      sourceSubscription.set(this.subscribe(target))
      otherSubscription.set(other.subscribe(new Observer[Any] {
        override def onNext(value: Any) {
          result.close()
          target.onCompleted()
        }

        override def onError(error: Exception) {
          result.close()
          target.onError(error)
        }

        override def onCompleted() {
          result -= otherSubscription
        }
      }))

      result
  }

  /**
   * Returns an $coll that produces values from this $coll until `dueTime`, when it raises a
   * [[java.util.concurrent.TimeoutException]].
   *
   * Completes when either this $coll completes before the timeout or the timeout occurs.
   *
   * Uses the `Scheduler.threadPool` scheduler.
   */
  def timeout(dueTime: Duration): Observable[A] = timeout(dueTime, Scheduler.threadPool)

  /**
   * Returns an $coll that produces values from this $coll until `dueTime`, when it raises a
   * [[java.util.concurrent.TimeoutException]].
   *
   * Completes when either this $coll completes before the timeout or the timeout occurs.
   */
  def timeout(dueTime: Duration, scheduler: Scheduler): Observable[A] = {
    timeout(dueTime, Observable.raise(new TimeoutException), scheduler)
  }

  /**
   * Returns an $coll that produces values from this $coll until `dueTime`, when it switches to the `other` $coll.
   *
   * Completes when either this $coll completes before the timeout or the `other` $coll completes after the timeout.
   *
   * Uses the `Scheduler.threadPool` scheduler.
   */
  def timeout[B >: A](dueTime: Duration, other: Observable[B]): Observable[B] = timeout(dueTime, other, Scheduler.threadPool)

  /**
   * Returns an $coll that produces values from this $coll until `dueTime`, when it switches to the `other` $coll.
   *
   * Completes when either this $coll completes before the timeout or the `other` $coll completes after the timeout.
   */
  def timeout[B >: A](dueTime: Duration, other: Observable[B], scheduler: Scheduler): Observable[B] = {
    val source = this.map(Some(_)) amb Observable.timer(dueTime)(scheduler).map(_ => None)
    source flatMap {
      case Some(value) => Observable.value(value)
      case None => other
    }
  }

  /**
   * Converts an Observable into a (lazy) Stream of values.
   */
  def toSeq: Seq[A] = {
    val result = new LinkedBlockingQueue[Notification[A]]
    val subscription = this.materialize.subscribe(result.put(_))

    def resultToStream: Stream[A] = {
      result.take() match {
        case OnCompleted =>
          subscription.close()
          Stream.Empty
        case OnNext(value) =>
          value #:: resultToStream
        case OnError(error) =>
          subscription.close()
          throw error
      }
    }

    resultToStream
  }

  /**
   * Switches to `source` when `this` terminates with an error.
   */
  def rescue[B >: A](source: Observable[B]): Observable[B] = createWithSubscription {
    observer =>
      val subscription = new MutableSubscription
      val result = new MutableSubscription(subscription)

      subscription.set(this.subscribe(new DelegateObserver(observer) {
        override def onError(error: Exception) = result clearAndSet {source.subscribe(observer)}
      }))

      result
  }

  /**
   * Asynchronously subscribe (and unsubscribe) observers on `scheduler`. The subscription is always completed
   * before the observer is unsubscribed.
   */
  def subscribeOn(scheduler: Scheduler): Observable[A] = createWithSubscription {
    observer =>
      val result = new MutableSubscription
      scheduler schedule {
        val subscription = this.subscribe(observer)

        result.set(new ScheduledSubscription(subscription, scheduler))
      }
      result
  }

  /**
   * Asynchronously notify observers on the specified `scheduler`. Notifications are still
   * delivered one at a time, in-order. An internal, unbounded queue is used to ensure the producer
   * is not limited by a slow consumer.
   */
  def observeOn(scheduler: Scheduler): Observable[A] = createWithSubscription {
    observer =>
      val queue = new LinkedBlockingQueue[Notification[A]]()
      val size = new AtomicInteger(0)

      val producerSubscription = new MutableSubscription
      val consumerSubscription = new MutableSubscription
      val result = new CompositeSubscription(producerSubscription, consumerSubscription)

      def startConsumer() {
        consumerSubscription.clearAndSet(scheduler scheduleRecursive {
          reschedule =>
            queue.remove() match {
              case OnCompleted =>
                result.close()
                observer.onCompleted()
              case OnError(error) =>
                result.close()
                observer.onError(error)
              case OnNext(value) =>
                observer.onNext(value)
                if (size.decrementAndGet() > 0) {
                  reschedule()
                }
            }
        })
      }

      producerSubscription.set(this.materialize.subscribe {
        notification =>
          queue.put(notification)
          if (size.getAndIncrement() == 0) {
            startConsumer()
          }
      })

      result
  }

  def synchronize: Observable[A] = createWithSubscription {
    observer =>
      this.subscribe(new DelegateObserver[A](observer) with SynchronizedObserver[A])
  }
}

/**
 * @define coll observable sequence
 */
object Observable {
  def apply[A](values: A*)(implicit scheduler: Scheduler = Scheduler.currentThread): Observable[A] =
    new IterableToObservableWrapper(values).toObservable(scheduler)

  /**
   * The default `onNext` handler does nothing.
   */
  def defaultOnNext[A](value: A) {}

  /**
   * The default `onError` handler throws the `error`.
   */
  def defaultOnError(error: Exception) {throw error}

  /**
   * The default `onCompleted` handler does nothing.
   */
  def defaultOnCompleted() {}

  def noop() {}

  def create[A](delegate: Observer[A] => () => Unit): Observable[A] = createWithSubscription {
    observer =>
      val unsubscribe = delegate(observer)
      new ActionSubscription(unsubscribe)
  }

  def createWithSubscription[A](delegate: Observer[A] => Subscription): Observable[A] = new Observable[A] {
    override def subscribe(observer: Observer[A]) = CurrentThreadScheduler runImmediate {
      val subscription = new MutableSubscription
      subscription.set(delegate(new DelegateObserver[A](observer) with ConformingObserver[A] {
        override protected def close() = subscription.close()
      }))
      subscription
    }
  }

  /**
   * Returns an empty $coll.
   */
  def empty(implicit scheduler: Scheduler = Scheduler.immediate): Observable[Nothing] = createWithSubscription {
    observer =>
      scheduler schedule observer.onCompleted()
  }

  /**
   * Returns a $coll that terminates with `error`.
   */
  def raise(error: Exception)(implicit scheduler: Scheduler = Scheduler.immediate): Observable[Nothing] = createWithSubscription {
    observer =>
      scheduler schedule observer.onError(error)
  }

  /**
   * Returns a $coll that contains a single `value`.
   */
  def value[A](value: A)(implicit scheduler: Scheduler = Scheduler.immediate): Observable[A] = {
    Seq(value).toObservable(scheduler)
  }

  /**
   * Returns an infinite $coll that produces a new value each `period`. The values produced are `0`, `1`, `2`, ...
   */
  def interval(period: Duration)(implicit scheduler: Scheduler = Scheduler.threadPool): Observable[Int] = createWithSubscription {
    observer =>
      var counter = 0
      scheduler.scheduleRecursiveAfter(period) {
        reschedule =>
          observer.onNext(counter)
          counter += 1
          reschedule(period)
      }
  }

  /**
   * Returns an $coll that produces a single value after `dueTime`. The value produced is `0`.
   */
  def timer(dueTime: Duration)(implicit scheduler: Scheduler = Scheduler.threadPool): Observable[Int] = createWithSubscription {
    observer =>
      scheduler.scheduleAfter(dueTime) {
        observer.onNext(0)
        observer.onCompleted()
      }
  }


  class IterableToObservableWrapper[+A](val iterable: Iterable[A]) {
    def subscribe(observer: Observer[A], scheduler: Scheduler = Scheduler.currentThread): Subscription = this.toObservable(scheduler).subscribe(observer)

    def toObservable(implicit scheduler: Scheduler = Scheduler.currentThread): Observable[A] = createWithSubscription {
      observer =>
        val it = iterable.iterator
        scheduler scheduleRecursive {
          self =>
            if (it.hasNext) {
              observer.onNext(it.next())
              self()
            } else {
              observer.onCompleted()
            }
        }
    }
  }

  implicit def iterableToObservableWrapper[A](iterable: Iterable[A]): IterableToObservableWrapper[A] = new IterableToObservableWrapper(iterable)

  class NestedObservableWrapper[A](source: Observable[Observable[A]]) {
    def merge: Observable[A] = createWithSubscription {
      observer =>
        val target = new DelegateObserver[A](observer) with SynchronizedObserver[A]
        val generatorSubscription = new MutableSubscription

        val activeCount = new AtomicInteger(1)
        val result = new CompositeSubscription(generatorSubscription)

        generatorSubscription.set(source.subscribe(
          onError = {
            error =>
              result.close()
              target.onError(error)
          },
          onCompleted = {
            () =>
              result -= generatorSubscription
              if (activeCount.decrementAndGet() == 0) {
                target.onCompleted()
              }
          },
          onNext = {
            value =>
              activeCount.incrementAndGet()

              val holder = new MutableSubscription
              result += holder

              holder.set(value.subscribe(new Observer[A] {
                override def onCompleted() {
                  result -= holder
                  if (activeCount.decrementAndGet() == 0) {
                    target.onCompleted()
                  }
                }

                override def onError(error: Exception) {
                  result.close()
                  target.onError(error)
                }

                override def onNext(value: A) {
                  target.onNext(value)
                }
              }))
          }))

        result
    }
  }

  implicit def nestedObservableWrapper[A](source: Observable[Observable[A]]) = new NestedObservableWrapper(source)

  class DematerializeObservableWrapper[A](source: Observable[Notification[A]]) {
    def dematerialize: Observable[A] = createWithSubscription {
      observer =>
        source.subscribe(onNext = _.accept(observer), onError = observer.onError, onCompleted = observer.onCompleted)
    }
  }

  implicit def dematerializeObservableWrapper[A](source: Observable[Notification[A]]) = new DematerializeObservableWrapper(source)

}
